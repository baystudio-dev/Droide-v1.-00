package com.baystudio.droide.core

import kotlinx.serialization.json.*

/** Exact managed declaration only. This object does not authenticate an arbitrary command. */
internal object LspCMakeProfile {
    const val ID = "cmake-language-server"
    const val COMMAND = "cmake-language-server"
    const val VERSION = "0.1.11"
    const val IDENTITY = "cmake-0.1.11-pygls-1.3.1-managed-utf32-v1"
    const val FULL_SYNC_POLICY = "cmake-full-sync-pygls131-v1"
    const val PRIVATE_BUILD_POLICY = "cmake-owner-private-checked-v1"
    const val CACHE_ASSOCIATION_POLICY = "cmake-home-directory-mapped-guest-v1"
    const val FORMATTER_POLICY = "DISABLED"
    const val ENVIRONMENT_POLICY = "cmake-editor-clean-env-v1"
    val extensions = setOf("cmake")
    val filenames = setOf("CMakeLists.txt")
    val rootFiles = listOf("CMakeLists.txt", "CMakePresets.json")
    fun supportsPath(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return name == "CMakeLists.txt" || name.endsWith(".cmake")
    }
    fun initializationOptions(cmakeExecutableGuestAbsolute: String, buildDirectoryGuestAbsolute: String): JsonObject {
        require(CMakeAnalysisSelections.normalizeGuestPath(cmakeExecutableGuestAbsolute) == cmakeExecutableGuestAbsolute)
        require(CMakeAnalysisSelections.normalizeGuestPath(buildDirectoryGuestAbsolute) == buildDirectoryGuestAbsolute)
        return buildJsonObject { put("cmakeExecutable", cmakeExecutableGuestAbsolute); put("buildDirectory", buildDirectoryGuestAbsolute) }
    }
    fun clientCapabilities(base: JsonObject): JsonObject = JsonObject(base + mapOf(
        "general" to buildJsonObject { put("positionEncodings", buildJsonArray { add("utf-32") }) },
        "workspace" to buildJsonObject {
            put("workspaceFolders", false); put("configuration", false); put("applyEdit", false)
        },
        "textDocument" to buildJsonObject {
            put("synchronization", buildJsonObject {
                put("dynamicRegistration", false); put("willSave", false); put("willSaveWaitUntil", false); put("didSave", false)
            })
            put("hover", buildJsonObject { put("dynamicRegistration", false); put("contentFormat", buildJsonArray { add("markdown"); add("plaintext") }) })
            put("completion", buildJsonObject {
                put("dynamicRegistration", false); put("contextSupport", true)
                put("completionItem", buildJsonObject { put("snippetSupport", false); put("commitCharactersSupport", false) })
            })
        },
    ))
    fun supportedCapabilities(native: LspNegotiatedCapabilities): LspNegotiatedCapabilities = native.copy(
        signatureHelp = false, signatureHelpTriggerCharacters = emptySet(), signatureHelpRetriggerCharacters = emptySet(),
        semanticTokens = false, inlayHint = false, definition = false, references = false, implementation = false,
        documentSymbol = false, workspaceSymbol = false, codeAction = false, codeActionResolve = false,
        callHierarchy = false, rename = false, formatting = false, pullDiagnostics = false, diagnosticIdentifier = null,
    )
    fun requireInitializeContract(result: JsonElement) {
        val native = LspNegotiatedCapabilities.fromInitialize(result, "utf-32", requireExplicitEncoding = true)
        require(native.sync.openClose && native.sync.change == LspTextSyncPolicy.ChangeKind.INCREMENTAL && native.sync.save) {
            "CMake synchronization contract changed"
        }
        require(native.completion && native.hover) { "CMake completion/hover contract unavailable" }
        require(native.completionTriggerCharacters.containsAll(setOf("{", "("))) { "CMake native completion triggers changed" }
        // An installed formatter can advertise a provider, but cannot acquire an executable client
        // route.
    }
    const val PRIVATE_BUILD_DISCLOSURE = "Builtin documentation mode selects no project build. It uses a private per-session build directory and checks that no CMake cache is present. These checks do not prevent another process from changing that directory between checks. CMake runs with the Ubuntu user's file and network access; the project root is not a sandbox. If an unexpected cache appears, CMake may execute project scripts before it is detected."
    const val APPROVAL_EXPLANATION = "CMake completion and hover run the selected CMake executable for builtin documentation. For an explicitly selected configured build, initialization and later refreshes may reconfigure that build, write CMake file-API files, and execute project or generated CMake scripts. Scripts can read, write or delete files outside this folder and use the network with the Ubuntu user's permissions. Revocation stops ordinary app-owned processes; it cannot undo file or network effects. The project root is not a sandbox. Independent external writers and deliberately escaped descendants are not contained. Formatting is disabled."
    const val INSTALLATION_DISCLOSURE = "Saving a selection only validates it. Enable analysis is separate and does not install packages. This adapter requires the exact reviewed managed server, Python, Unicode, CMake and helper identities."
    const val FEATURE_NOTE = "Managed CMake 0.1.11 completion and documentation hover only. Immediate module suggestions and explicitly selected configured-build target/cache suggestions are native and may be incomplete or stale. Protocol connected does not establish project refresh success. Save retires the session; the next supported request refreshes once. Use Refresh/reopen for external changes. Formatting, diagnostics, navigation, rename, code actions and semantic tokens are unavailable. Native/device semantics remain unverified."
}
