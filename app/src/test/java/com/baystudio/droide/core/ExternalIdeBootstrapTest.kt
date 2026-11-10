package com.baystudio.droide.core

import java.io.*
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Future integrated RPC hooks required. Fake NDJSON only; no real CLI/provider/native process. */
class ExternalIdeBootstrapTest {
    @get:org.junit.Rule val applicationWork: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()
    private suspend fun exercise(resume: Boolean, local: Boolean = true) {
        val root = Files.createTempDirectory("acp-command-bootstrap-").toFile()
        val mapper = WorkspacePathMapper(root, "/workspace")
        root.resolve(".droide").mkdir()
        root.resolve(".droide/mcp.json").writeText("""{"servers":{"droide-ide":{"command":"fixture-mcp","args":[]}}}""")
        val binding = ExternalIdeToolRegistry.bind("bootstrap-fixture", root, object : ExternalIdeToolRegistry.Provider {
            override val definitions = JsonArray(emptyList())
            override suspend fun call(name: String, arguments: JsonObject) = error("No tools executed during discovery")
        })
        val clientIn = PipedOutputStream(); val serverIn = PipedInputStream(clientIn, 4_096)
        val serverOut = PipedOutputStream(); val clientOut = PipedInputStream(serverOut, 4_096)
        val alive = AtomicBoolean(true)
        val failure = AtomicReference<Throwable?>()
        val requests = CopyOnWriteArrayList<String>()
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> uncaught += error })
        val approvals = ApprovalManager()
        val process = object : HostedStdioProcess {
            override val stdin = clientIn
            override val stdout = clientOut
            override val stderr = ByteArrayInputStream(byteArrayOf())
            override val isAlive get() = alive.get()
            override val exitCode get() = if (isAlive) null else 0
            override suspend fun awaitExit() = 0
            override fun close() {
                alive.set(false)
                listOf(clientIn, serverIn, serverOut, clientOut).forEach { runCatching { it.close() } }
            }
        }
        val host = object : StdioProcessHost {
            override val executionScope = if (local) ProcessExecutionScope.LOCAL_LINUX_ARM64 else ProcessExecutionScope.DEVICE_ADB
            override suspend fun resolveExecutable(command: String) = command
            override suspend fun pathMapper() = mapper
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?) = process
        }
        val io = object : AgentWorkspaceIo {
            override val checksumCommand = "sha256sum"
            override fun requirePath(path: String) = Unit
            override suspend fun refresh() = error("No workspace execution in command bootstrap fixture")
            override suspend fun shellBounded(command: String, maxOutputBytes: Int): BridgeShellResult = error("No shell in command bootstrap fixture")
            override suspend fun pull(path: String, output: File) = error("No transfer in command bootstrap fixture")
            override suspend fun push(input: File, path: String) = error("No transfer in command bootstrap fixture")
        }
        fun send(value: JsonObject) {
            serverOut.write((value.toString() + "\n").toByteArray()); serverOut.flush()
        }
        fun commands(session: String, vararg names: String) = send(buildJsonObject {
            put("jsonrpc", "2.0"); put("method", "session/update")
            put("params", buildJsonObject {
                put("sessionId", session)
                put("update", buildJsonObject {
                    put("sessionUpdate", "available_commands_update")
                    put("availableCommands", buildJsonArray { names.forEach { add(buildJsonObject { put("name", it) }) } })
                })
            })
        })
        fun respond(request: JsonObject, result: JsonObject) = send(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", request["id"]!!); put("result", result)
        })
        val worker = Thread({
            try {
                serverIn.bufferedReader().use { reader ->
                    while (alive.get()) {
                        val request = Json.parseToJsonElement(reader.readLine() ?: break).jsonObject
                        val method = request["method"]!!.jsonPrimitive.content
                        requests += method
                        when (method) {
                            "initialize" -> respond(request, buildJsonObject {
                                put("protocolVersion", 1); put("agentCapabilities", buildJsonObject { put("loadSession", true) })
                            })
                            "session/new", "session/load" -> {
                                if (resume) check(request["params"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content == "selected")
                                val servers = request["params"]!!.jsonObject["mcpServers"]!!.jsonArray.map { it.jsonObject }
                                check(servers.size == if (local) 2 else 1)
                                check(servers.single { it["name"] == JsonPrimitive("droide-ide") }["command"] == JsonPrimitive("fixture-mcp"))
                                if (local) {
                                    val builtIn = servers.single { it["name"] == JsonPrimitive("droide-ide-2") }
                                    check(builtIn == ExternalIdeProtocol.configuration(binding, "droide", "sh", "droide-ide-2"))
                                }
                                commands("wrong", "untrusted")
                                commands("selected", "superseded")
                                commands("selected")
                                commands("selected", "compact")
                                send(Json.parseToJsonElement("""{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"selected","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"unbound transcript must never replay"}}}}""").jsonObject)
                                // Wait for the pre-bind permission rejection before returning the session.
                                send(Json.parseToJsonElement("""{"jsonrpc":"2.0","id":"early-permission","method":"session/request_permission","params":{"sessionId":"selected","toolCall":{"toolCallId":"tool","title":"early permission","kind":"read"},"options":[]}}""").jsonObject)
                                val rejection = Json.parseToJsonElement(reader.readLine()).jsonObject
                                check(rejection["id"]!!.jsonPrimitive.content == "early-permission" && "error" in rejection)
                                respond(request, if (resume) JsonObject(emptyMap()) else buildJsonObject { put("sessionId", "selected") })
                            }
                            "test/late-metadata" -> { commands("selected", "help"); respond(request, JsonObject(emptyMap())) }
                            "test/revoke" -> { commands("selected"); respond(request, JsonObject(emptyMap())) }
                            else -> error("Unexpected fixture method: $method")
                        }
                    }
                }
            } catch (error: Exception) { if (alive.get()) failure.set(error) }
        }, "droide-acp-command-bootstrap-test").apply { isDaemon = true; start() }
        var rpc: AcpNdjsonConnection? = null
        try {
            withTimeout(5_000) {
                val boot = WorkspaceAgentSessionBootstrap.start(WorkspaceAgentPluginCatalog.entries.first(), root, FileRepository(root), host,
                    approvals, PermissionEngine(), scope, if (resume) "selected" else null, {}, io)
                rpc = boot.rpc
                assertEquals("selected", boot.sessionId)
                assertEquals(listOf("compact"), boot.configuration.availableCommands)
                assertEquals(listOf("compact"), boot.rpc.commands.snapshot("selected"))
                assertTrue(boot.rpc.commands.snapshot("wrong").isEmpty())
                assertNull(approvals.pending.value)

                assertNull(boot.rpc.configurationListener)
                val delivered = CopyOnWriteArrayList<String>()
                boot.rpc.updateListener = { delivered += it["sessionUpdate"]!!.jsonPrimitive.content }
                boot.rpc.request("test/late-metadata", JsonObject(emptyMap()), 2_000)
                assertEquals(listOf("help"), boot.rpc.commands.snapshot("selected"))
                boot.rpc.request("test/revoke", JsonObject(emptyMap()), 2_000)
                assertTrue(boot.rpc.commands.snapshot("selected").isEmpty())
                assertEquals(listOf("available_commands_update", "available_commands_update"), delivered)
                assertEquals(listOf("initialize", if (resume) "session/load" else "session/new", "test/late-metadata", "test/revoke"), requests)
                assertNull(failure.get()); assertTrue(uncaught.toString(), uncaught.isEmpty())
            }
        } finally {
            ExternalIdeToolRegistry.unbind(binding)
            rpc?.close(); process.close(); worker.join(2_000)
            scope.coroutineContext.job.cancelAndJoin()
            assertTrue(PathSecurity.deleteTreeNoFollow(root))
            assertFalse("Command fixture worker remains alive", worker.isAlive)
        }
    }

    @Test(timeout = 8_000) fun deviceTransportDoesNotAdvertiseAnUnreachableLocalBridge() = runBlocking { exercise(false, false) }

    @Test(timeout = 8_000) fun newSessionCommandsArriveBeforeResponseAndSurviveListenerGap() = runBlocking { exercise(false) }
    @Test(timeout = 8_000) fun loadedSessionCommandsArriveBeforeResponseWithoutReplayOrPermissionEffects() = runBlocking { exercise(true) }
}
