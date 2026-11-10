package com.baystudio.droide.core

import kotlinx.serialization.json.*

/** Exact original native csharp-ls route. Settings are immutable for one admitted owner. */
internal object LspCSharpProfile {
    const val ID = "csharp-ls"
    const val COMMAND = "csharp-ls"
    const val IDENTITY = "csharp-ls-0.28.0-sdk-10.0.402-runtime-10.0.13-helper-22.23.3-native-v1"
    const val INITIALIZE_MS = 60_000L
    const val PREPARATION_MS = 120_000L
    val extensions = setOf("cs")
    fun supportsPath(path: String): Boolean = path.endsWith(".cs")
    fun section(solutionGuest: String?): JsonObject = buildJsonObject {
        put("analyzersEnabled", false); put("useMetadataUris", false); put("razorSupport", false)
        put("applyFormattingOptions", false); put("debug", buildJsonObject { put("debugMode", false) })
        solutionGuest?.let { put("solutionPathOverride", it) }
    }
    fun settings(section: JsonObject): JsonObject = buildJsonObject { put("settings", buildJsonObject { put("csharp", section) }) }
    fun configuration(params: JsonElement?, rootUri: String, section: JsonObject): JsonArray {
        val items = (params as? JsonObject)?.get("items") as? JsonArray ?: error("Unsupported C# configuration request")
        require(items.size <= 32) { "Unsupported C# configuration request" }
        return JsonArray(items.map { raw ->
            val item = raw as? JsonObject ?: error("Unsupported C# configuration item")
            val name = (item["section"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val scope = item["scopeUri"]
            if (name == "csharp" && (scope == null || scope == JsonNull || scope == JsonPrimitive(rootUri))) section else JsonNull
        })
    }
    fun clientCapabilities(): JsonObject {
        val base = LspProtocolContract.clientCapabilities()
        val original = base["textDocument"] as? JsonObject ?: JsonObject(emptyMap())
        val td = JsonObject(original.filterKeys { it !in setOf("semanticTokens", "inlayHint", "codeAction", "callHierarchy", "declaration") } +
            ("completion" to buildJsonObject {
                put("dynamicRegistration", false)
                put("completionItem", buildJsonObject { put("snippetSupport", false); put("commitCharactersSupport", false) })
            }))
        return JsonObject(base + mapOf(
            "general" to buildJsonObject { put("positionEncodings", buildJsonArray { add("utf-16") }) },
            "window" to buildJsonObject { put("workDoneProgress", false) },
            "workspace" to buildJsonObject { put("configuration", true); put("workspaceFolders", true); put("applyEdit", false) },
            "textDocument" to JsonObject(td + mapOf("typeDefinition" to buildJsonObject { put("dynamicRegistration", false) })),
        ))
    }
    fun capabilities(result: JsonElement): LspNegotiatedCapabilities {
        val caps = (result as? JsonObject)?.get("capabilities") as? JsonObject ?: error("C# capabilities missing")
        require(caps["positionEncoding"] == null || caps["positionEncoding"] == JsonPrimitive("utf-16")) { "C# requires UTF-16" }
        val native = LspNegotiatedCapabilities.fromInitialize(result, enableTypeDefinition = true)
        require(native.sync.openClose && native.sync.change in setOf(LspTextSyncPolicy.ChangeKind.FULL, LspTextSyncPolicy.ChangeKind.INCREMENTAL)) { "C# ordinary native document synchronization is required" }
        return native.copy(semanticTokens = false, inlayHint = false, codeAction = false, codeActionResolve = false, callHierarchy = false)
    }
    const val APPROVAL_EXPLANATION = "Run original csharp-ls 0.28.0 with .NET SDK 10.0.402 for the saved analysis selection. Native MSBuild project loading may execute design-time targets, SDK resolvers, source generators and credential providers; read ancestor/imported files outside this root; and use network access and credentials. Analyzer diagnostics are initially off; this does not disable native source generators. The selected directory, private state and process limits are not a filesystem or network sandbox. No client restore, SDK installation, build or test is requested. Revoke cannot undo file/network effects."
    const val INSTALLATION_DISCLOSURE = "Requires three separately admitted original artifacts: csharp-ls 0.28.0, private SDK 10.0.402/runtime 10.0.13 and private Node helper 22.23.3. Save/Enable never installs them or replaces terminal tool selections."
    const val FEATURE_NOTE = "Native UTF-16 completion, hover, signature help, source navigation, symbols, formatting, rename and current-document pull diagnostics. Project completeness, restore and build readiness remain unverified. Uncaptured targets, resource edits, commands, Razor and metadata navigation are unavailable. Selected-item completion documentation resolve UI is deferred."
}
