package com.baystudio.droide.core

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

// Compatibility bundles use MCP for tool contributions.
class AgentPluginToolManager(
    private val workDir: File,
    private val plugins: AgentPluginSource = AgentPluginSource.EMPTY,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun discover(): List<AgentPluginToolDef> = AgentPluginContributions.toolFiles(plugins, workDir).flatMap { (plugin, file) ->
        runCatching {
            val root = json.parseToJsonElement(file.readText()) as? JsonObject ?: return@runCatching emptyList()
            val array = root["tools"] as? JsonArray ?: return@runCatching emptyList()
            array.take(MAX_TOOLS_PER_PLUGIN).mapNotNull { element -> parseTool(plugin, element as? JsonObject ?: return@mapNotNull null) }
        }.getOrDefault(emptyList())
    }.distinctBy { it.functionName }.take(MAX_TOOLS_TOTAL)

    suspend fun execute(def: AgentPluginToolDef, input: JsonObject, terminal: ITerminalSession): String {
        require(def.fingerprint == discover().firstOrNull { it.functionName == def.functionName }?.fingerprint) {
            "Agent plugin tool changed after approval: ${def.functionName}"
        }
        AgentPluginJsonSchema.requireValid(def.inputSchema, input)
        val payload = input.toString()
        require(payload.length <= MAX_INPUT_CHARS) { "Agent plugin tool input is too large" }
        var replaced = false
        val argv = def.argv.map { arg ->
            if (arg.contains(INPUT_PLACEHOLDER)) {
                replaced = true
                arg.replace(INPUT_PLACEHOLDER, payload)
            } else arg
        }.let { if (replaced) it else it + payload }
        val result = terminal.execArgv(argv, timeoutMs = def.timeoutMs)
        return if (result.timedOut) {
            if (result.output.isEmpty() && !result.outputIncomplete) "(timeout)"
            else "[TIMEOUT]\n${outputPreview(result)}"
        } else "exit=${result.exitCode}\n${outputPreview(result)}"
    }

    // Capture completeness and process exit are independent. Never present a bounded preview (or
    // incomplete backend capture) as the complete output.
    private fun outputPreview(result: ExecResult): String {
        val output = result.output
        if (!result.outputIncomplete && output.length <= MAX_OUTPUT_CHARS) return output
        val notice = "[DROIDE_TOOL_OUTPUT_INCOMPLETE captured_utf16_chars=${output.length} capture_complete=${!result.outputIncomplete}]\n" +
            "Output is incomplete; omitted content is unknown. Do not repeat a side-effecting operation just to recover its output.\n"
        var end = minOf(output.length, (MAX_OUTPUT_CHARS - notice.length).coerceAtLeast(0))
        if (end in 1 until output.length && output[end - 1].isHighSurrogate() && output[end].isLowSurrogate()) end--
        return notice + output.substring(0, end)
    }

    fun permissionResource(def: AgentPluginToolDef, input: JsonObject): String {
        AgentPluginJsonSchema.requireValid(def.inputSchema, input)
        val invocation = AgentPluginJsonSchema.invocationHash(input).take(24)
        return "agent-plugin:${def.pluginId}:tool:${def.name}@sha:${def.fingerprint.take(24)}:input:$invocation"
    }

    private fun parseTool(plugin: AgentPluginContributionRoot, obj: JsonObject): AgentPluginToolDef? {
        val rawName = (obj["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() ?: return null
        val local = rawName.replace(Regex("[^a-z0-9_-]+"), "_").trim('_').take(40)
        if (!local.matches(Regex("[a-z0-9][a-z0-9_-]{0,39}"))) return null
        val pluginKey = plugin.id.replace(Regex("[^a-z0-9]+"), "_").trim('_')
        // Truncated display components must not become the identity: distinct admitted tools can share
        // the same prefix within one plugin revision.
        val function = if (pluginKey.length > 20 || local.length > 26) {
            val identity = sha256(listOf(plugin.id, plugin.contentSha256, local)).take(12)
            "plugin_${pluginKey.take(20)}_${local.take(22)}_$identity"
        } else "plugin_${pluginKey}_${local}_${plugin.contentSha256.take(8)}"
        val description = ((obj["description"] as? JsonPrimitive)?.contentOrNull ?: "${plugin.displayName}: $local").take(600)
        val command = obj["command"] as? JsonArray ?: return null
        if (command.isEmpty() || command.size > MAX_ARG_COUNT) return null
        val argv = command.map { item ->
            val raw = (item as? JsonPrimitive)?.contentOrNull ?: return null
            val expanded = AgentPluginContributions.expandPluginRoot(raw, plugin)
            if (expanded.length > MAX_ARG_CHARS || expanded.any { it == '\u0000' || it == '\n' || it == '\r' }) return null
            expanded
        }
        val schema = if ("inputSchema" in obj) {
            obj["inputSchema"] as? JsonObject ?: return null
        } else buildJsonObject { put("type", JsonPrimitive("object")) }
        if (schema.toString().length > MAX_SCHEMA_CHARS || !AgentPluginJsonSchema.schemaSupported(schema)) return null
        val timeout = (obj["timeoutMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            ?.coerceIn(500L, AgentTimeoutPolicy.MAX_PLUGIN_TOOL_MS) ?: AgentTimeoutPolicy.PLUGIN_TOOL_MS
        val fingerprint = sha256(listOf(plugin.id, plugin.contentSha256, local, description, argv.joinToString("\u0000"), schema.toString(), timeout.toString()))
        return AgentPluginToolDef(plugin.id, local, function, description, argv, schema, timeout, fingerprint)
    }

    private fun sha256(parts: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { digest.update(it.toByteArray(Charsets.UTF_8)); digest.update(0) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val INPUT_PLACEHOLDER = "${'$'}{DROIDE_TOOL_INPUT_JSON}"
        private const val MAX_TOOLS_PER_PLUGIN = 24
        private const val MAX_TOOLS_TOTAL = 64
        private const val MAX_ARG_COUNT = 64
        private const val MAX_ARG_CHARS = 8_000
        private const val MAX_SCHEMA_CHARS = 24_000
        private const val MAX_INPUT_CHARS = 32_000
        private const val MAX_OUTPUT_CHARS = 12_000
    }
}
