package com.baystudio.droide.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** A form edits argv, never a shell command line. Unknown configuration fields survive edits. */
data class McpServerDraft(
    val name: String,
    val command: String,
    val arguments: String = "[]",
    val protocol: McpProtocolPreference = McpProtocolPreference.AUTO,
    val enabled: Boolean = true,
) {
    internal fun entry(previous: JsonObject?): JsonObject {
        require(Regex("[A-Za-z0-9._-]{1,80}").matches(name)) {
            "Use 1–80 letters, digits, dots, underscores or hyphens for the server name"
        }
        require(command.isNotBlank()) { "Enter an executable, such as node or npx" }
        require(arguments.length <= McpWorkspaceConfiguration.MAX_BYTES) { "Arguments are too large" }
        val args = try { Json.parseToJsonElement(arguments) as? JsonArray } catch (_: Exception) { null }
        require(args != null && args.all { it is JsonPrimitive && it.isString }) {
            "Arguments must be a JSON array of strings, for example [\"server.js\"]"
        }
        return JsonObject(previous.orEmpty() + mapOf(
            "command" to JsonPrimitive(command.trim()), "args" to args,
            "protocol" to JsonPrimitive(when (protocol) {
                McpProtocolPreference.AUTO -> "auto"
                McpProtocolPreference.MODERN_2026 -> "modern-2026"
                McpProtocolPreference.LEGACY_2025 -> "legacy-2025"
            }), "enabled" to JsonPrimitive(enabled),
        ))
    }

    companion object {
        fun from(server: McpServer): McpServerDraft = McpServerDraft(
            server.name, server.argv.first(), JsonArray(server.argv.drop(1).map(::JsonPrimitive)).toString(),
            server.protocol, server.enabled,
        )
    }
}

class McpConfigurationSnapshot internal constructor(
    internal val owner: McpWorkspaceConfiguration,
    internal val document: JsonObject,
    internal val version: DiskFileVersion,
    val servers: List<McpServer>,
)

/** Uses the same guarded writer as the editor, including dirty-document and disk-version checks. */
internal class McpWorkspaceConfiguration(
    private val files: FileRepository,
    private val onChanged: () -> Unit = {},
) {
    private val json = Json { prettyPrint = true }
    private val validator = McpManager(files.root)

    suspend fun load(): McpConfigurationSnapshot = withContext(Dispatchers.IO) {
        val text = if (files.exists(PATH)) files.readTextForEdit(PATH, MAX_BYTES.toLong()) else null
        val document = if (text == null) buildJsonObject { put("servers", JsonObject(emptyMap())) }
        else try { json.parseToJsonElement(text).jsonObject } catch (_: Exception) {
            error("Invalid .droide/mcp.json. Open the configuration to correct it, then reload.")
        }
        snapshot(document, DiskFileVersion.fromText(text))
    }

    suspend fun save(snapshot: McpConfigurationSnapshot, draft: McpServerDraft, originalName: String?): McpConfigurationSnapshot {
        val entries = entries(snapshot).toMutableMap()
        val previous = originalName?.let { name ->
            requireNotNull(entries[name] as? JsonObject) { "Server no longer exists. Reload the configuration." }
        }
        require(draft.name == originalName || draft.name !in entries) { "A server with this name already exists" }
        val entry = draft.entry(previous)
        if (originalName != null) entries.remove(originalName)
        entries[draft.name] = entry
        return commit(snapshot, entries)
    }

    suspend fun setEnabled(snapshot: McpConfigurationSnapshot, name: String, enabled: Boolean): McpConfigurationSnapshot {
        val entries = entries(snapshot).toMutableMap()
        val previous = requireNotNull(entries[name] as? JsonObject) { "Server no longer exists. Reload the configuration." }
        entries[name] = JsonObject(previous + ("enabled" to JsonPrimitive(enabled)))
        return commit(snapshot, entries)
    }

    suspend fun remove(snapshot: McpConfigurationSnapshot, name: String): McpConfigurationSnapshot {
        val entries = entries(snapshot).toMutableMap()
        require(entries.remove(name) != null) { "Server no longer exists. Reload the configuration." }
        return commit(snapshot, entries)
    }

    private fun entries(snapshot: McpConfigurationSnapshot): JsonObject {
        check(snapshot.owner === this) { "MCP configuration belongs to another workspace view. Reload it." }
        return snapshot.document.getValue("servers").jsonObject
    }

    private suspend fun commit(before: McpConfigurationSnapshot, entries: Map<String, JsonElement>): McpConfigurationSnapshot =
        withContext(Dispatchers.IO) {
            val document = JsonObject(before.document + ("servers" to JsonObject(entries)))
            val text = json.encodeToString(JsonObject.serializer(), document) + "\n"
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "MCP configuration is too large" }
            val result = snapshot(document, DiskFileVersion.fromText(text)) // Validate before touching disk.
            try {
                files.writeTextIfUnchanged(PATH, text, before.version)
            } finally {
                // A cancelled writer may already have committed. Reconcile without starting any server.
                withContext(NonCancellable + Dispatchers.IO) { onChanged() }
            }
            result
        }

    private fun snapshot(document: JsonObject, version: DiskFileVersion) =
        McpConfigurationSnapshot(this, document, version, validator.parseWorkspaceConfiguration(document))

    companion object {
        const val PATH = ".droide/mcp.json"
        const val MAX_BYTES = 100_000
    }
}
