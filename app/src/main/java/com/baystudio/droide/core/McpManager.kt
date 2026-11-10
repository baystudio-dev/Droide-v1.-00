package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

enum class McpProtocolPreference { AUTO, MODERN_2026, LEGACY_2025 }

data class McpServer(
    val name: String,
    val argv: List<String>,
    val protocol: McpProtocolPreference = McpProtocolPreference.AUTO,
    val configFingerprint: String,
    val status: String = "configured",
    val source: String = "Workspace",
    val enabled: Boolean = true,
)

class McpManager(
    private val workDir: File,
    private val plugins: AgentPluginSource = AgentPluginSource.EMPTY,
    private val processHost: StdioProcessHost? = null,
) {
    private val json = Json { ignoreUnknownKeys = false }

    fun list(): List<McpServer> {
        val projectFile = runCatching { PathSecurity.resolveWithin(workDir, ".droide/mcp.json") }.getOrNull()
        val project = if (projectFile == null || !projectFile.isFile) {
            McpHealthRegistry.clearConfigIssue(workDir)
            emptyList()
        } else if (projectFile.length() !in 1..MAX_CONFIG_BYTES.toLong()) {
            McpHealthRegistry.configIssue(workDir, "MCP configuration is empty or too large")
            emptyList()
        } else runCatching {
            val root = json.parseToJsonElement(projectFile.readText()).jsonObject
            val parsed = parseWorkspaceConfiguration(root)
            McpHealthRegistry.clearConfigIssue(workDir)
            parsed
        }.getOrElse { failure ->
            McpHealthRegistry.configIssue(
                workDir,
                if (failure.message.orEmpty().startsWith("Unsupported MCP")) "Unsupported MCP configuration" else "Invalid .droide/mcp.json",
                failure.message,
            )
            emptyList()
        }
        val pluginServers = AgentPluginContributions.mcpFiles(plugins, workDir).flatMap { (plugin, file) ->
            runCatching {
                val root = json.parseToJsonElement(file.readText()).jsonObject
                val map = (root["servers"] as? JsonObject) ?: (root["mcpServers"] as? JsonObject)
                    ?: root.takeIf { candidate -> candidate.values.all { it is JsonObject } }
                parseServerMap(map, plugin)
            }.getOrDefault(emptyList())
        }
        val servers = (project + pluginServers).distinctBy { it.name }.take(MAX_SERVERS).sortedBy { it.name.lowercase() }
        if (processHost != null) pruneConfiguredSessions(workspaceKey(), processHost, servers)
        McpHealthRegistry.syncConfigured(workDir, servers)
        return servers
    }

    internal fun parseWorkspaceConfiguration(root: JsonObject): List<McpServer> {
        val configured = root["servers"] as? JsonObject
            ?: error("MCP config requires an object field named 'servers'")
        require(configured.size <= MAX_SERVERS) { "At most $MAX_SERVERS MCP servers are supported" }
        val parsed = parseServerMap(configured, null)
        require(parsed.size == configured.size) { "One or more MCP server entries are invalid or unsupported" }
        return parsed
    }

    private fun parseServerMap(servers: JsonObject?, plugin: AgentPluginContributionRoot?): List<McpServer> {
        if (servers == null || servers.size > MAX_SERVERS) return emptyList()
        return servers.entries.mapNotNull { (rawName, value) ->
            val localName = rawName.trim()
            if (rawName != localName || !SERVER_NAME.matches(localName)) return@mapNotNull null
            val name = plugin?.let { namespacedServerName(it, localName) } ?: localName
            val obj = value as? JsonObject ?: return@mapNotNull null

            if (obj.containsKey("url") || obj.containsKey("httpUrl") || (obj["transport"] != null && obj["transport"] != JsonPrimitive("stdio")) ||
                (obj["type"] != null && obj["type"] != JsonPrimitive("stdio"))) {
                if (plugin == null) error("Unsupported MCP transport for '$name': only local command/args stdio is available; remote HTTP is not supported")
                return@mapNotNull null
            }
            val env = obj["env"]
            if (env != null && (env !is JsonObject || env.isNotEmpty())) {
                if (plugin == null) error("Unsupported MCP env for '$name': environment injection and credential variables are not supported; configure a stdio server without env")
                return@mapNotNull null
            }
            val enabled = obj["enabled"]?.let { element ->
                val primitive = element as? JsonPrimitive ?: return@mapNotNull null
                if (primitive.isString) return@mapNotNull null
                primitive.booleanOrNull ?: return@mapNotNull null
            } ?: true
            val rawCommand = (obj["command"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            val command = plugin?.let { AgentPluginContributions.expandPluginRoot(rawCommand, it) } ?: rawCommand
            if (!safeCommand(command)) return@mapNotNull null
            val args = obj["args"]?.let { element ->
                val array = element as? JsonArray ?: return@mapNotNull null
                if (array.size > MAX_ARG_COUNT) return@mapNotNull null
                array.map { item ->
                    val rawArg = (item as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                    val arg = plugin?.let { AgentPluginContributions.expandPluginRoot(rawArg, it) } ?: rawArg
                    if (arg.length > MAX_ARG_CHARS || arg.any { it == '\u0000' || it == '\n' || it == '\r' }) return@mapNotNull null
                    arg
                }
            } ?: emptyList()
            val protocol = when (obj["protocol"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
                null, "", "auto" -> McpProtocolPreference.AUTO
                "2026-07-28", "modern", "modern-2026" -> McpProtocolPreference.MODERN_2026
                "2025-11-25", "legacy", "legacy-2025" -> McpProtocolPreference.LEGACY_2025
                else -> return@mapNotNull null
            }
            val argv = listOf(command) + args
            val provenance = plugin?.let { listOf("plugin:${it.id}@${it.contentSha256}") }.orEmpty()
            McpServer(name, argv, protocol, fingerprint(provenance + argv + if (enabled) emptyList() else listOf("enabled:false"), protocol),
                source = plugin?.let { "Plugin · ${it.id}" } ?: "Workspace", enabled = enabled)
        }
    }

    private fun namespacedServerName(plugin: AgentPluginContributionRoot, server: String): String {
        val raw = "${plugin.id}.$server"
        if (raw.length <= 80 && SERVER_NAME.matches(raw)) return raw
        return "${plugin.id.take(36)}.${server.take(32)}.${plugin.contentSha256.take(8)}".take(80)
    }

    // Stable permission scope.
    fun permissionResource(serverName: String, toolName: String? = null): String {
        val server = list().firstOrNull { it.name == serverName }
        val logical = if (toolName.isNullOrBlank()) serverName else "$serverName/$toolName"
        if (server == null) return logical
        return "$logical@cfg:${approvalFingerprint(server).take(PERMISSION_FINGERPRINT_CHARS)}"
    }

    internal class ServerSelection internal constructor(
        val server: McpServer,
        internal val workspace: String,
        internal val host: StdioProcessHost?,
        internal val fingerprint: String,
    ) {
        val runtimeIdentity: String get() = permissionResource()

        fun permissionResource(toolName: String? = null): String {
            val logical = if (toolName.isNullOrBlank()) server.name else "${server.name}/$toolName"
            return "$logical@cfg:${fingerprint.take(PERMISSION_FINGERPRINT_CHARS)}"
        }
    }

    internal fun select(serverName: String): ServerSelection =
        select(list().firstOrNull { it.name == serverName } ?: error("MCP server not found: $serverName"))

    internal fun select(server: McpServer): ServerSelection {
        check(server.enabled) { "MCP server is disabled: ${server.name}" }
        val snapshot = server.copy(argv = java.util.Collections.unmodifiableList(ArrayList(server.argv)))
        return ServerSelection(snapshot, workspaceKey(), processHost, approvalFingerprint(snapshot))
    }

    private fun approvalFingerprint(server: McpServer): String {
        val executionIdentity = processHost?.executionScope?.name ?: "UNBOUND"
        return sha256(
            listOf(server.configFingerprint, "exec:$executionIdentity", server.argv.first(), workspaceKey()) + localArgumentProvenance(server.argv)
        )
    }

    private fun requireCurrent(selection: ServerSelection) {
        check(selection.workspace == workspaceKey() && selection.host === processHost) {
            "MCP execution context changed; review its permission again"
        }
        val current = list().firstOrNull { it.name == selection.server.name }
        check(current?.enabled == true && current.configFingerprint == selection.server.configFingerprint &&
            approvalFingerprint(selection.server) == selection.fingerprint) {
            "MCP server configuration changed; retry after reviewing its permission"
        }
    }

    suspend fun discoverTools(serverName: String): List<JsonObject> = discoverTools(serverName, null)

    internal suspend fun discoverTools(selection: ServerSelection): List<JsonObject> = discoverTools(selection.server.name, selection)

    private suspend fun discoverTools(serverName: String, selection: ServerSelection?): List<JsonObject> = withServer(serverName, McpHealthPhase.DISCOVERY, selection) { server, session, negotiated ->
        val tools = session.listTools(negotiated)
        McpHealthRegistry.connected(workDir, server, tools.size, negotiated.version)
        tools
    }

    suspend fun listTools(serverName: String): String = listTools(serverName, null)

    internal suspend fun listTools(selection: ServerSelection): String = listTools(selection.server.name, selection)

    private suspend fun listTools(serverName: String, selection: ServerSelection?): String = withServer(serverName, McpHealthPhase.DISCOVERY, selection) { server, session, negotiated ->
        val tools = session.listTools(negotiated)
        McpHealthRegistry.connected(workDir, server, tools.size, negotiated.version)
        val raw = buildJsonObject {
            put("server", serverName); put("protocolVersion", negotiated.version); put("tools", JsonArray(tools))
        }.toString()
        if (raw.length <= 10_000) raw else McpAgentResult.render(serverName, negotiated.version, "list_tools", buildJsonObject { put("tools", JsonArray(tools)) })
    }

    suspend fun callTool(serverName: String, toolName: String, arguments: JsonObject = buildJsonObject {}): String =
        callTool(serverName, toolName, arguments, null)

    internal suspend fun callTool(selection: ServerSelection, toolName: String, arguments: JsonObject = buildJsonObject {}): String =
        callTool(selection.server.name, toolName, arguments, selection)

    private suspend fun callTool(serverName: String, toolName: String, arguments: JsonObject, selection: ServerSelection?): String {
        require(TOOL_NAME.matches(toolName)) { "Invalid MCP tool name" }
        require(arguments.toString().length <= MAX_ARGUMENT_JSON_CHARS) { "MCP tool arguments are too large" }
        return withServer(serverName, McpHealthPhase.TOOL_CALL, selection) { server, session, negotiated ->
            val available = session.listTools(negotiated)
            require(available.any { it["name"]?.jsonPrimitive?.contentOrNull == toolName }) {
                "MCP tool is not advertised by server: $toolName"
            }
            val result = session.callTool(negotiated, toolName, arguments)
            McpHealthRegistry.connected(workDir, server, available.size, negotiated.version)
            McpAgentResult.render(serverName, negotiated.version, "call_tool:$toolName", result)
        }
    }

    suspend fun tool(serverName: String, input: String): String = tool(serverName, input, null)

    internal suspend fun tool(selection: ServerSelection, input: String): String = tool(selection.server.name, input, selection)

    private suspend fun tool(serverName: String, input: String, selection: ServerSelection?): String {
        if (input.isBlank()) return listTools(serverName, selection)
        if (input.length > MAX_ARGUMENT_JSON_CHARS) return "ERROR: MCP input is too large"
        val parsed = runCatching { json.parseToJsonElement(input).jsonObject }.getOrElse {
            return "ERROR: MCP input must be JSON: {\"tool\":\"name\",\"arguments\":{...}}"
        }
        val name = parsed["tool"]?.jsonPrimitive?.contentOrNull
            ?: return "ERROR: MCP input is missing string field 'tool'"
        val arguments = when (val element = parsed["arguments"]) {
            null, JsonNull -> buildJsonObject {}
            is JsonObject -> element
            else -> return "ERROR: MCP 'arguments' must be an object"
        }
        return callTool(serverName, name, arguments, selection)
    }

    suspend fun contextOperation(serverName: String, operation: String, parameters: JsonObject): String =
        contextOperation(serverName, operation, parameters, null)

    internal suspend fun contextOperation(selection: ServerSelection, operation: String, parameters: JsonObject): String =
        contextOperation(selection.server.name, operation, parameters, selection)

    private suspend fun contextOperation(serverName: String, operation: String, parameters: JsonObject, selection: ServerSelection?): String =
        withServer(serverName, McpHealthPhase.TOOL_CALL, selection) { server, session, protocol ->
            val result = session.contextOperation(protocol, operation, parameters)
            val previousCount = McpHealthRegistry.observe(workDir).value.firstOrNull { it.serverName == server.name }?.toolsCount
            McpHealthRegistry.connected(workDir, server, previousCount, protocol.version)
            McpAgentResult.render(serverName, protocol.version, operation, result)
        }

    private suspend fun <T> withServer(
        serverName: String,
        phase: McpHealthPhase,
        approved: ServerSelection?,
        block: suspend (McpServer, McpStdioSession, NegotiatedMcp) -> T,
    ): T = withContext(Dispatchers.IO) {
        val selection = approved ?: select(serverName)
        require(selection.server.name == serverName) { "MCP selection does not match the requested server" }
        requireCurrent(selection)
        val server = selection.server
        val host = processHost ?: run {
            val failure = IllegalStateException("MCP execution host is not bound; select a Linux or Device Workstation backend")
            McpHealthRegistry.failure(workDir, server, McpHealthPhase.ENVIRONMENT, failure)
            throw failure
        }
        check(host.executionScope in setOf(ProcessExecutionScope.LOCAL_LINUX_ARM64, ProcessExecutionScope.DEVICE_ADB)) {
            "MCP servers must execute in a bound Linux or Device Workstation backend"
        }
        val mapper = host.pathMapper()
        val requestedCommand = mapper?.toRemoteProtocolString(server.argv.first()) ?: server.argv.first()
        val executable = host.resolveExecutable(requestedCommand)
        if (executable == null) {
            val failure = IllegalStateException("MCP executable not available in Device Workstation: ${server.argv.first()}")
            McpHealthRegistry.failure(workDir, server, McpHealthPhase.ENVIRONMENT, failure)
            throw failure
        }
        val mappedArgs = server.argv.drop(1).map { arg -> mapper?.toRemoteProtocolString(arg) ?: arg }
        val argv = listOf(executable) + mappedArgs
        require(argv.size in 1..MAX_ARG_COUNT + 1) { "Invalid MCP server argv" }
        val slot = sessionSlot(SessionKey(workspaceKey(), server.name, host))
        slot.mutex.withLock {
            try {
                check(!slot.retired) { "MCP session was closed; retry the operation" }
                requireCurrent(selection)
                val identity = selection.runtimeIdentity
                val invocation = sha256(argv)
                val now = System.nanoTime()
                slot.active?.takeIf {
                    it.identity != identity || it.invocation != invocation ||
                        now - slot.lastUsedNanos > SESSION_IDLE_NANOS || !it.session.isAlive
                }?.let {
                    it.session.close()
                    slot.active = null
                }
                val active = slot.active ?: run {
                    McpHealthRegistry.starting(workDir, server, McpHealthPhase.PROCESS, "Starting MCP session")
                    val resolution = resolveProtocol(server, argv) { requireCurrent(selection) }
                    val session = McpStdioSession(MCP_IO_POOL.start {
                        requireCurrent(selection)
                        host.start(argv, emptyMap(), null)
                    }, resolution.preference, resolution.modernAlreadyProbed, json)
                    try {
                        McpHealthRegistry.starting(workDir, server, McpHealthPhase.PROTOCOL, "Negotiating MCP protocol")
                        val negotiated = session.negotiate()
                        PooledSession(session, negotiated, server.configFingerprint, identity, invocation).also { slot.active = it }
                    } catch (failure: Throwable) {
                        session.close()
                        throw failure
                    }
                }
                check(!slot.retired) { "MCP session was closed; retry the operation" }
                requireCurrent(selection)
                McpHealthRegistry.starting(workDir, server, phase, if (phase == McpHealthPhase.TOOL_CALL) "Running MCP tool" else "Discovering MCP tools")
                block(server, active.session, active.negotiated).also {
                    requireCurrent(selection)
                    slot.lastUsedNanos = System.nanoTime()
                }
            } catch (cancelled: CancellationException) {
                slot.active?.session?.close()
                slot.active = null
                throw cancelled
            } catch (failure: Throwable) {
                slot.active?.session?.close()
                slot.active = null
                McpHealthRegistry.failure(workDir, server, phase, failure)
                throw failure
            } finally {
                if (slot.retired) {
                    slot.active?.session?.close()
                    slot.active = null
                }
            }
        }
    }

    private fun workspaceKey(): String = runCatching { workDir.canonicalPath }.getOrElse { workDir.absolutePath }

    private data class SessionKey(val workspace: String, val server: String, val host: StdioProcessHost)
    private class SessionSlot {
        val mutex = Mutex()
        @Volatile var active: PooledSession? = null
        @Volatile var retired = false
        @Volatile var lastUsedNanos = System.nanoTime()
    }
    private data class PooledSession(
        val session: McpStdioSession,
        val negotiated: NegotiatedMcp,
        val configFingerprint: String,
        val identity: String,
        val invocation: String,
    )

    private suspend fun resolveProtocol(server: McpServer, argv: List<String>, validate: () -> Unit): ProtocolResolution {
        if (server.protocol != McpProtocolPreference.AUTO) {
            return ProtocolResolution(server.protocol, modernAlreadyProbed = false)
        }
        val now = System.nanoTime()
        ERA_CACHE[server.configFingerprint]?.takeIf { now - it.createdAtNanos <= ERA_CACHE_TTL_NANOS }?.let { cached ->
            return ProtocolResolution(cached.preference, modernAlreadyProbed = cached.preference == McpProtocolPreference.MODERN_2026)
        }

        val modern = probeModernSibling(argv, validate)
        val resolved = if (modern) McpProtocolPreference.MODERN_2026 else McpProtocolPreference.LEGACY_2025
        putEraCache(server.configFingerprint, EraCacheEntry(resolved, now), now)
        return ProtocolResolution(resolved, modernAlreadyProbed = modern)
    }

    private suspend fun probeModernSibling(argv: List<String>, validate: () -> Unit): Boolean {
        val host = processHost ?: error("MCP execution host is not bound")
        check(host.executionScope in setOf(ProcessExecutionScope.LOCAL_LINUX_ARM64, ProcessExecutionScope.DEVICE_ADB)) {
            "MCP servers must execute in a bound Linux or Device Workstation backend"
        }
        val probe = McpStdioSession(
            io = MCP_IO_POOL.start { validate(); host.start(argv, emptyMap(), null) },
            preference = McpProtocolPreference.MODERN_2026,
            modernAlreadyProbed = false,
            json = json,
        )
        return try {
            probe.discoverModern(strict = false) != null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        } finally {
            probe.close()
        }
    }

    private fun safeCommand(command: String): Boolean {
        if (command.isBlank() || command.length > MAX_COMMAND_CHARS || command.any { it == '\u0000' || it == '\n' || it == '\r' }) return false
        // argvthe referenced API is never interpreted by a shell.
        if (command.any { it in " ;&|`$<>" }) return false
        return true
    }

    private fun fingerprint(argv: List<String>, protocol: McpProtocolPreference): String =
        sha256(listOf(protocol.name) + argv)

    private fun localArgumentProvenance(argv: List<String>): List<String> = argv.drop(1).mapNotNull { arg ->
        val candidate = runCatching {
            val raw = File(arg)
            if (raw.isAbsolute) raw else PathSecurity.resolveWithin(workDir, arg)
        }.getOrNull() ?: return@mapNotNull null
        if (!candidate.isFile || PathSecurity.isSymbolicLink(candidate)) return@mapNotNull null
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return@mapNotNull null
        val identity = runCatching {
            if (PathSecurity.contains(workDir, canonical)) {
                "workspace:${canonical.relativeTo(workDir.canonicalFile).invariantSeparatorsPath}"
            } else {
                "external:${canonical.absolutePath}"
            }
        }.getOrElse { "external:${canonical.absolutePath}" }
        if (canonical.length() <= MAX_PROVENANCE_FILE_BYTES) {
            "$identity:sha256:${sha256File(canonical)}"
        } else {
            "$identity:large:${canonical.length()}:${canonical.lastModified()}"
        }
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(parts: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            digest.update(part.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class ProtocolResolution(
        val preference: McpProtocolPreference,
        val modernAlreadyProbed: Boolean,
    )

    private data class EraCacheEntry(
        val preference: McpProtocolPreference,
        val createdAtNanos: Long,
    )

    private data class NegotiatedMcp(val version: String, val modern: Boolean)

    private class McpStdioSession(
        private val io: McpStdioIo,
        private val preference: McpProtocolPreference,
        private val modernAlreadyProbed: Boolean,
        private val json: Json,
    ) {
        private val process: HostedStdioProcess get() = io.process
        private val stdout: InputStream = io.stdout
        private var nextId = 1L
        val isAlive: Boolean get() = !io.closed && process.isAlive

        suspend fun negotiate(): NegotiatedMcp = when (preference) {
            McpProtocolPreference.MODERN_2026 -> {
                if (modernAlreadyProbed) NegotiatedMcp(MODERN_VERSION, modern = true)
                else discoverModern(strict = true) ?: error("MCP server does not support $MODERN_VERSION")
            }
            McpProtocolPreference.LEGACY_2025 -> initializeLegacy()
            McpProtocolPreference.AUTO -> error("AUTO must be resolved before opening an MCP operation session")
        }

        suspend fun discoverModern(strict: Boolean): NegotiatedMcp? {
            val deadline = deadlineAfter(DISCOVER_TIMEOUT_MS)
            val id = nextId++
            val response = try {
                send(rpcRequest(
                    id = id,
                    method = "server/discover",
                    params = buildJsonObject { put("_meta", modernMeta()) },
                ), deadline)
                readResponse(id, deadline)
            } catch (error: McpTimeoutException) {
                if (strict) throw error else return null
            }
            val rpcError = response["error"] as? JsonObject
            if (rpcError != null) {
                if (strict) error("MCP modern discovery failed: ${errorSummary(rpcError)}")
                return null
            }
            val result = response["result"] as? JsonObject
                ?: if (strict) error("MCP discovery response has no object result") else return null
            val versions = result["supportedVersions"]?.let { element ->
                (element as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
            }.orEmpty()
            if (MODERN_VERSION !in versions) {
                if (strict) error("MCP server did not advertise $MODERN_VERSION")
                return null
            }
            validateModernCompleteResult(result, "server/discover")
            return NegotiatedMcp(MODERN_VERSION, modern = true)
        }

        private suspend fun initializeLegacy(): NegotiatedMcp {
            val response = request(
                method = "initialize",
                params = buildJsonObject {
                    put("protocolVersion", LEGACY_VERSION)
                    put("capabilities", buildJsonObject {})
                    put("clientInfo", clientInfo())
                },
                timeoutMs = REQUEST_TIMEOUT_MS,
                modern = false,
            )
            val result = requireResult(response, "initialize", modern = false)
            val negotiated = result["protocolVersion"]?.jsonPrimitive?.contentOrNull
                ?: error("MCP initialize response is missing protocolVersion")
            require(negotiated in SUPPORTED_LEGACY_VERSIONS) { "Unsupported MCP legacy protocol version: $negotiated" }
            send(buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "notifications/initialized")
            }, deadlineAfter(REQUEST_TIMEOUT_MS))
            return NegotiatedMcp(negotiated, modern = false)
        }

        suspend fun listTools(protocol: NegotiatedMcp): List<JsonObject> {
            val all = mutableListOf<JsonObject>()
            val seenNames = mutableSetOf<String>()
            val seenCursors = mutableSetOf<String>()
            var cursor: String? = null
            repeat(MAX_TOOL_PAGES) {
                val params = buildJsonObject {
                    cursor?.let { put("cursor", it) }
                }
                val response = request("tools/list", params, REQUEST_TIMEOUT_MS, protocol.modern)
                val result = requireResult(response, "tools/list", protocol.modern)
                val page = result["tools"] as? JsonArray ?: error("MCP tools/list result has no tools array")
                page.forEach { element ->
                    val tool = element as? JsonObject ?: error("MCP tools/list returned a non-object tool")
                    val name = tool["name"]?.jsonPrimitive?.contentOrNull ?: error("MCP tool has no name")
                    require(TOOL_NAME.matches(name)) { "MCP server advertised an invalid tool name" }
                    require(seenNames.add(name)) { "MCP server advertised duplicate tool name: $name" }
                    require(tool["inputSchema"] is JsonObject) { "MCP tool $name has no object inputSchema" }
                    all += tool
                    require(all.size <= MAX_TOOLS) { "MCP server advertised too many tools" }
                }
                val next = result["nextCursor"]?.jsonPrimitive?.contentOrNull
                if (next.isNullOrBlank()) return all
                require(next.length <= MAX_CURSOR_CHARS) { "MCP tools/list cursor is too large" }
                require(seenCursors.add(next)) { "MCP tools/list repeated a pagination cursor" }
                cursor = next
            }
            error("MCP tools/list exceeded pagination limit")
        }

        suspend fun contextOperation(protocol: NegotiatedMcp, operation: String, parameters: JsonObject): JsonObject {
            val method = when (operation) {
                "list_resources" -> "resources/list"
                "list_resource_templates" -> "resources/templates/list"
                "read_resource" -> "resources/read"
                "list_prompts" -> "prompts/list"
                "get_prompt" -> "prompts/get"
                else -> error("Unsupported MCP context operation: $operation")
            }
            parameters["cursor"]?.let {
                require(it is JsonPrimitive && it.isString && it.content.length in 1..MAX_CURSOR_CHARS) { "Invalid MCP cursor" }
            }
            if (operation == "read_resource") {
                val uri = parameters["uri"] as? JsonPrimitive
                require(uri?.isString == true && uri.content.length in 1..4_096) { "Resource URI is missing or too long" }
            }
            if (operation == "get_prompt") {
                val name = parameters["name"] as? JsonPrimitive
                require(name?.isString == true && TOOL_NAME.matches(name.content)) { "Invalid MCP prompt name" }
                val args = parameters["arguments"] as? JsonObject ?: buildJsonObject {}
                require(args.size <= 64 && args.all { (_, value) -> value is JsonPrimitive && value.isString }) {
                    "MCP prompt arguments must be a bounded string-valued object"
                }
            }
            require(parameters.toString().length <= MAX_ARGUMENT_JSON_CHARS) { "MCP context arguments are too large" }
            val response = request(method, parameters, REQUEST_TIMEOUT_MS, protocol.modern)
            val result = requireResult(response, method, protocol.modern)
            if (protocol.modern) require(result["resultType"] == JsonPrimitive("complete")) {
                "MCP context operation requires unsupported multi-round-trip/Tasks interaction"
            }
            val arrayName = when (operation) {
                "list_resources" -> "resources"
                "list_resource_templates" -> "resourceTemplates"
                "read_resource" -> "contents"
                "list_prompts" -> "prompts"
                else -> "messages"
            }
            val entries = result[arrayName] as? JsonArray ?: error("MCP $method result has no $arrayName array")
            require(entries.size <= 512 && entries.all { it is JsonObject }) { "MCP context page is invalid or too large" }
            result["nextCursor"]?.let {
                require(it is JsonPrimitive && it.isString && it.content.length in 1..MAX_CURSOR_CHARS) { "Invalid MCP nextCursor" }
                require(it != parameters["cursor"]) { "MCP pagination repeated its cursor" }
            }
            return result
        }

        suspend fun callTool(protocol: NegotiatedMcp, toolName: String, arguments: JsonObject): JsonObject {
            val response = request(
                method = "tools/call",
                params = buildJsonObject {
                    put("name", toolName)
                    put("arguments", arguments)
                },
                timeoutMs = TOOL_CALL_TIMEOUT_MS,
                modern = protocol.modern,
            )
            val result = requireResult(response, "tools/call", protocol.modern)
            if (protocol.modern) {
                when (val resultType = result["resultType"]?.jsonPrimitive?.contentOrNull) {
                    "complete" -> Unit
                    "input_required" -> error("MCP tool requires a 2026 multi-round-trip client interaction that Droide has not enabled for this Agent call")
                    "task" -> error("MCP tool returned a Tasks extension result, but this Agent call did not advertise Tasks capability")
                    else -> error("MCP tool returned unsupported resultType: $resultType")
                }
            }
            return result
        }

        private suspend fun request(method: String, params: JsonObject, timeoutMs: Long, modern: Boolean): JsonObject {
            val deadline = deadlineAfter(timeoutMs)
            val id = nextId++
            val effective = if (modern) JsonObject(params + ("_meta" to modernMeta())) else params
            send(rpcRequest(id, method, effective), deadline)
            return readResponse(id, deadline)
        }

        private fun rpcRequest(id: Long, method: String, params: JsonObject): JsonObject = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }

        private fun modernMeta(): JsonObject = buildJsonObject {
            put("io.modelcontextprotocol/protocolVersion", MODERN_VERSION)
            put("io.modelcontextprotocol/clientCapabilities", buildJsonObject {})
            put("io.modelcontextprotocol/clientInfo", clientInfo())
        }

        private fun clientInfo(): JsonObject = buildJsonObject {
            put("name", "Droide")
            put("version", "1.00")
        }

        private suspend fun readResponse(id: Long, deadline: Long): JsonObject {
            var ignored = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                checkDeadline(deadline)
                val line = readLineBounded(deadline)
                val message = runCatching { json.parseToJsonElement(line).jsonObject }
                    .getOrElse { error("MCP server emitted invalid JSON-RPC${stderrSuffix()}") }
                checkDeadline(deadline)
                require(message["jsonrpc"]?.jsonPrimitive?.contentOrNull == "2.0") { "Invalid MCP JSON-RPC version" }
                val responseId = message["id"]?.jsonPrimitive?.longOrNull
                if (responseId == id && ("result" in message || "error" in message)) return message
                if (message["method"] != null && message["id"] != null) handleServerRequest(message, deadline)
                ignored += 1
                require(ignored <= MAX_UNRELATED_MESSAGES) { "Too many unrelated MCP messages" }
            }
        }

        private suspend fun handleServerRequest(message: JsonObject, deadline: Long) {
            val method = message["method"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val id = message["id"] ?: return
            val reply = if (method == "ping") {
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("result", buildJsonObject {})
                }
            } else {
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("error", buildJsonObject {
                        put("code", -32601)
                        put("message", "Client method not supported")
                    })
                }
            }
            send(reply, deadline)
        }

        private suspend fun readLineBounded(deadline: Long): String {
            val output = ByteArrayOutputStream(minOf(MAX_FRAME_BYTES, 16 * 1024))
            while (true) {
                currentCoroutineContext().ensureActive()
                checkDeadline(deadline)
                while (stdout.available() > 0) {
                    // Keep enforcing the caller's budget even while a peer continuously emits bytes. Concrete hosted
                    // streams must honor available()/read() contracts.
                    if ((output.size() and 1023) == 0) {
                        currentCoroutineContext().ensureActive()
                        checkDeadline(deadline)
                    }
                    val byte = stdout.read()
                    if (byte < 0) break
                    if (byte == '\n'.code) {
                        checkDeadline(deadline)
                        val bytes = output.toByteArray()
                        require(bytes.size <= MAX_FRAME_BYTES) { "MCP frame is too large" }
                        val line = bytes.toString(Charsets.UTF_8).trimEnd('\r')
                        require(line.isNotBlank()) { "MCP server emitted an empty JSON-RPC frame" }
                        return line
                    }
                    output.write(byte)
                    require(output.size() <= MAX_FRAME_BYTES) { "MCP frame is too large" }
                }
                if (!process.isAlive && stdout.available() == 0) {
                    error("MCP server exited before a complete response${stderrSuffix()}")
                }
                checkDeadline(deadline)
                delay(POLL_DELAY_MS)
            }
        }

        private fun requireResult(response: JsonObject, method: String, modern: Boolean): JsonObject {
            val rpcError = response["error"] as? JsonObject
            if (rpcError != null) error("MCP $method failed: ${errorSummary(rpcError)}")
            val result = response["result"] as? JsonObject ?: error("MCP $method response has no object result")
            if (modern) validateModernCompleteResult(result, method)
            return result
        }

        private fun validateModernCompleteResult(result: JsonObject, method: String) {
            val resultType = result["resultType"]?.jsonPrimitive?.contentOrNull
                ?: error("MCP $method modern response is missing required resultType")
            require(resultType == "complete" || resultType == "input_required" || resultType == "task") {
                "MCP $method returned unsupported resultType: $resultType"
            }
        }

        private fun errorSummary(error: JsonObject): String {
            val code = error["code"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            val message = error["message"]?.jsonPrimitive?.contentOrNull ?: "unknown error"
            return "$code ${message.take(500)}"
        }

        private fun deadlineAfter(timeoutMs: Long): Long = System.nanoTime() + timeoutMs * 1_000_000L

        private fun checkDeadline(deadline: Long) {
            if (deadline - System.nanoTime() <= 0) throw McpTimeoutException("MCP response timed out${stderrSuffix()}")
        }

        private suspend fun send(message: JsonObject, deadline: Long) {
            check(!io.closed && process.isAlive) { "MCP server process is not running${stderrSuffix()}" }
            checkDeadline(deadline)
            val line = message.toString()
            require(line.length <= MAX_REQUEST_CHARS && '\n' !in line && '\r' !in line) { "MCP request is too large or invalid" }
            try { io.write(line, deadline) }
            catch (timeout: McpTimeoutException) { throw McpTimeoutException("${timeout.message}${stderrSuffix()}") }
        }

        private fun stderrSuffix(): String {
            val tail = io.stderrTail.trim().takeLast(1_000)
            return if (tail.isBlank()) "" else "; stderr=${tail.replace('\n', ' ')}"
        }

        fun close() = io.close()
    }

    companion object {
        private val SESSION_SLOTS = LinkedHashMap<SessionKey, SessionSlot>()
        private const val SESSION_IDLE_NANOS = 5L * 60L * 1_000_000_000L
        private const val MAX_SESSION_SLOTS = 64
        private val MCP_IO_POOL = McpStdioIoPool(MAX_SESSION_SLOTS)

        private fun sessionSlot(key: SessionKey): SessionSlot = synchronized(SESSION_SLOTS) {
            val now = System.nanoTime()
            val iterator = SESSION_SLOTS.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val slot = entry.value
                if ((slot.retired || now - slot.lastUsedNanos > SESSION_IDLE_NANOS) && slot.mutex.tryLock()) {
                    try {
                        slot.retired = true
                        slot.active?.session?.close()
                        slot.active = null
                        McpHealthRegistry.sessionClosed(File(entry.key.workspace), entry.key.server, "idle cleanup")
                        iterator.remove()
                    } finally { slot.mutex.unlock() }
                }
            }
            SESSION_SLOTS[key] ?: run {
                if (SESSION_SLOTS.size >= MAX_SESSION_SLOTS) {
                    SESSION_SLOTS.entries.sortedBy { it.value.lastUsedNanos }.firstOrNull { it.value.mutex.tryLock() }?.let { candidate ->
                        try {
                            candidate.value.retired = true
                            candidate.value.active?.session?.close()
                            candidate.value.active = null
                            McpHealthRegistry.sessionClosed(File(candidate.key.workspace), candidate.key.server, "session limit")
                            SESSION_SLOTS.remove(candidate.key)
                        } finally { candidate.value.mutex.unlock() }
                    }
                }
                check(SESSION_SLOTS.size < MAX_SESSION_SLOTS) { "MCP session limit reached; close an Agent workspace before opening another" }
                SessionSlot().also { SESSION_SLOTS[key] = it }
            }
        }

        internal suspend fun awaitIoIdleForExit() {
            while (MCP_IO_POOL.activeSessions != 0) kotlinx.coroutines.delay(25)
        }

        fun shutdownSessions(workDir: File, host: StdioProcessHost?) {
            if (host == null) return
            val workspace = runCatching { workDir.canonicalPath }.getOrElse { workDir.absolutePath }
            val removed = synchronized(SESSION_SLOTS) {
                SESSION_SLOTS.keys.filter { it.workspace == workspace && it.host === host }.mapNotNull { key ->
                    SESSION_SLOTS.remove(key)?.also { it.retired = true }?.let { key to it }
                }
            }
            removed.forEach { (key, slot) ->
                slot.active?.session?.close()
                McpHealthRegistry.sessionClosed(File(key.workspace), key.server, "Agent shutdown")
            }
        }

        private fun pruneConfiguredSessions(workspace: String, host: StdioProcessHost, servers: List<McpServer>) {
            val configured = servers.filter { it.enabled }.associate { it.name to it.configFingerprint }
            val removed = synchronized(SESSION_SLOTS) {
                SESSION_SLOTS.keys.filter { key ->
                    if (key.workspace != workspace || key.host !== host) false
                    else SESSION_SLOTS[key]?.active?.let { configured[key.server] != it.configFingerprint }
                        ?: (key.server !in configured)
                }.mapNotNull { key -> SESSION_SLOTS.remove(key)?.also { it.retired = true } }
            }
            removed.forEach { it.active?.session?.close() }
        }

        private const val MODERN_VERSION = "2026-07-28"
        private const val LEGACY_VERSION = "2025-11-25"
        private val SUPPORTED_LEGACY_VERSIONS = setOf("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25")
        private val SERVER_NAME = Regex("[A-Za-z0-9._-]{1,80}")
        private val TOOL_NAME = Regex("[A-Za-z0-9_.-]{1,128}")
        private val ERA_CACHE = ConcurrentHashMap<String, EraCacheEntry>()

        private fun putEraCache(key: String, value: EraCacheEntry, now: Long) = synchronized(ERA_CACHE) {
            ERA_CACHE.entries.removeIf { now - it.value.createdAtNanos > ERA_CACHE_TTL_NANOS }
            if (ERA_CACHE.size >= MAX_ERA_CACHE_ENTRIES && !ERA_CACHE.containsKey(key)) {
                ERA_CACHE.entries.minByOrNull { it.value.createdAtNanos }?.let { ERA_CACHE.remove(it.key, it.value) }
            }
            ERA_CACHE[key] = value
        }

        private const val ERA_CACHE_TTL_NANOS = 10L * 60L * 1_000_000_000L
        private const val MAX_ERA_CACHE_ENTRIES = 128
        private const val PERMISSION_FINGERPRINT_CHARS = 16
        private const val MAX_CONFIG_BYTES = 100_000
        private const val MAX_PROVENANCE_FILE_BYTES = 32L * 1024L * 1024L
        private const val MAX_SERVERS = 32
        private const val MAX_COMMAND_CHARS = 512
        private const val MAX_ARG_COUNT = 64
        private const val MAX_ARG_CHARS = 4_096
        private const val MAX_REQUEST_CHARS = 256_000
        private const val MAX_ARGUMENT_JSON_CHARS = 128_000
        private const val MAX_FRAME_BYTES = 1_000_000
        private const val MAX_AGENT_RESULT_CHARS = 32_000
        private const val MAX_TOOL_PAGES = 20
        private const val MAX_TOOLS = 512
        private const val MAX_CURSOR_CHARS = 4_096
        private const val MAX_UNRELATED_MESSAGES = 128
        private const val DISCOVER_TIMEOUT_MS = 5_000L
        private const val REQUEST_TIMEOUT_MS = 15_000L
        private const val TOOL_CALL_TIMEOUT_MS = AgentTimeoutPolicy.MCP_TOOL_MS
        private const val POLL_DELAY_MS = 10L
    }
}
