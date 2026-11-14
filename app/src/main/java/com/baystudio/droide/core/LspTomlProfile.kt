package com.baystudio.droide.core

import kotlinx.serialization.json.*
import java.io.File
import java.net.URI

/** Only the exact managed original Taplo WASM route receives this profile. */
internal object LspTomlProfile {
    const val ID = "taplo-toml-wasm"
    const val COMMAND = "droide-taplo-lsp"
    const val IDENTITY = "taplo-lsp-0.8.0-core-0.2.0-node-22.23.3-snapshot-v1"
    const val SECTION = "evenBetterToml"
    const val INITIALIZE_MS = 30_000L
    val extensions = setOf("toml")
    fun supportsPath(path: String): Boolean = path.endsWith(".toml", ignoreCase = true)
    fun rootUri(root: String): String {
        require(root.startsWith('/') && root != "/" && !root.contains('\\') && root.split('/').none { it == "." || it == ".." })
        return URI("file", "", root.trimEnd('/') + "/", null).toASCIIString()
    }
    fun requireOwnedUri(uri: String, root: String): String {
        val u = URI(uri)
        require(u.scheme == "file" && u.rawAuthority.isNullOrEmpty() && u.rawQuery == null && u.rawFragment == null)
        require(u.rawPath != null && !Regex("(?i)%2f|%5c").containsMatchIn(u.rawPath))
        val path = u.path ?: error("TOML URI has no local path")
        require(path.startsWith('/') && !path.contains('\\') && path.split('/').drop(1).none { it.isEmpty() || it == "." || it == ".." })
        require(path.startsWith(root.trimEnd('/') + "/")) { "TOML URI is outside the actual workspace" }
        require(URI("file", "", path, null).toASCIIString() == uri) { "TOML URI is not the exact canonical spelling" }
        return path
    }
    fun initializationOptions(): JsonObject = buildJsonObject { put("cachePath", JsonNull); put("configurationSection", SECTION) }
    fun settings(): JsonObject = buildJsonObject {
        put("taplo", buildJsonObject { put("configFile", buildJsonObject { put("enabled", true); put("path", JsonNull) }) })
        put("schema", buildJsonObject { put("enabled", true); put("associations", buildJsonObject {}); put("catalogs", buildJsonArray {}); put("links", false) })
        put("completion", buildJsonObject { put("maxKeys", 5) })
        put("syntax", buildJsonObject { put("semanticTokens", true) })
        put("formatter", buildJsonObject {}); put("rules", buildJsonArray {})
    }
    fun configuration(params: JsonElement?, rootUri: String): JsonArray {
        val root = params as? JsonObject ?: error("Malformed TOML configuration request")
        require(root.keys == setOf("items"))
        val items = root["items"] as? JsonArray ?: error("TOML configuration items missing")
        require(items.size == 2) { "TOML requires exactly global and owned-root configuration" }
        items.forEachIndexed { index, raw ->
            val item = raw as? JsonObject ?: error("Malformed TOML configuration item")
            require(item.keys.all { it in setOf("section", "scopeUri") } && item["section"] == JsonPrimitive(SECTION))
            require(if (index == 0) item["scopeUri"] == null || item["scopeUri"] == JsonNull else item["scopeUri"] == JsonPrimitive(rootUri))
        }
        return JsonArray(listOf(settings(), settings()))
    }
    fun clientCapabilities(): JsonObject = buildJsonObject {
        put("general", buildJsonObject { put("positionEncodings", buildJsonArray { add("utf-16") }) })
        put("workspace", buildJsonObject { put("configuration", true); put("workspaceFolders", true); put("applyEdit", false) })
        put("textDocument", buildJsonObject {
            put("synchronization", buildJsonObject { put("dynamicRegistration", false); put("didSave", false); put("willSave", false) })
            put("completion", buildJsonObject { put("dynamicRegistration", false); put("completionItem", buildJsonObject {
                put("snippetSupport", true); put("commitCharactersSupport", false); put("insertReplaceSupport", true)
                put("documentationFormat", buildJsonArray { add("markdown"); add("plaintext") })
            }) })
            put("hover", buildJsonObject { put("contentFormat", buildJsonArray { add("markdown"); add("plaintext") }) })
            put("rename", buildJsonObject { put("prepareSupport", true) })
            put("documentSymbol", buildJsonObject { put("hierarchicalDocumentSymbolSupport", true) })
            put("semanticTokens", buildJsonObject {
                put("requests", buildJsonObject { put("full", true) }); put("tokenTypes", buildJsonArray { add("tomlArrayKey"); add("tomlTableKey") })
                put("tokenModifiers", buildJsonArray { add("readonly") }); put("formats", buildJsonArray { add("relative") })
                put("overlappingTokenSupport", false); put("multilineTokenSupport", false)
            })
        })
    }
    fun capabilities(result: JsonElement): LspNegotiatedCapabilities {
        val raw = (result as? JsonObject)?.get("capabilities") as? JsonObject ?: error("TOML capabilities missing")
        require(raw["positionEncoding"] == null || raw["positionEncoding"] == JsonPrimitive("utf-16")) { "TOML requires legacy or explicit UTF-16" }
        // The pinned source advertises the numeric FULL form; reject reinterpretation of incremental sync.
        require(raw["textDocumentSync"] == JsonPrimitive(1)) { "Taplo requires its native numeric FULL synchronization" }
        val caps = LspNegotiatedCapabilities.fromInitialize(result)
        require(caps.sync.openClose && caps.sync.change == LspTextSyncPolicy.ChangeKind.FULL)
        if (caps.semanticTokens) require(caps.semanticTokenLegend.tokenTypes == listOf("tomlArrayKey", "tomlTableKey") && caps.semanticTokenLegend.tokenModifiers == listOf("readonly"))
        return caps.copy(sync = caps.sync.copy(save = false, saveIncludeText = false), signatureHelp = false, signatureHelpTriggerCharacters = emptySet(),
            signatureHelpRetriggerCharacters = emptySet(), inlayHint = false, definition = false, references = false, implementation = false,
            typeDefinition = false, workspaceSymbol = false, codeAction = false, codeActionResolve = false, callHierarchy = false, pullDiagnostics = false)
    }
    const val APPROVAL_EXPLANATION = "Run the original Taplo WASM language server with one actual workspace root and immutable read-only local config/schema snapshots, including unsaved editor buffers. Remote schema fetches, project modules and project writes are blocked on this reviewed route. Exact file observations are cooperative, not an OS sandbox or protection against hostile same-user changes."
    const val INSTALLATION_DISCLOSURE = "Requires separately admitted original @taplo/lsp 0.8.0, @taplo/core 0.2.0 and private Node 22.23.3. Enabling never installs them or changes terminal Node selection."
    const val FEATURE_NOTE = "Native schema completion/hover, formatting, symbols, limited table/array highlighting and same-document key rename. Unversioned native advisory diagnostics have unverified computation revision. No definition, references, cross-file rename or code actions. Uppercase .TOML may be excluded by native default globs unless actual config includes it."
    const val RENAME_GUIDANCE = "Rename this TOML key within this document; collisions are not checked by this server. Use an exact bare ASCII key or an explicitly quoted key; no automatic quoting or trimming."
    const val DIAGNOSTIC_QUALIFICATION = "unversioned native advisory; exact computation revision unverified"
}
