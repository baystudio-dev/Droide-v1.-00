package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.serialization.json.*

internal object LspVueProfile {
    const val ID = "vue-language-server"
    const val COMMAND = "vue-language-server"
    const val IDENTITY = "vue-3.3.13-tslsp-6.0.2-ts-6.0.3-node-22.23.3-private-v1"
    const val ROOT_POLICY = "exact-opened-workspace-unavailable-parent-v1"
    val extensions = setOf("vue")
    val companionExtensions = setOf("vue", "ts", "tsx", "mts", "cts", "js", "jsx", "mjs", "cjs")
    fun supportsPath(path: String): Boolean = path.replace('\\', '/').substringAfterLast('/').endsWith(".vue")
    fun relevantPath(path: String): Boolean = path.substringAfterLast('.', "") in companionExtensions
    fun projectRoot(workspace: File, document: File): File {
        val root = workspace.absoluteFile.normalize()
        val file = document.absoluteFile.normalize()
        require(supportsPath(file.path) && PathSecurity.contains(root, file)) { "Vue document must be an original-case .vue inside the opened workspace" }
        return root // Missing root or parent never changes this recorded identity.
    }
    fun projectAvailable(root: File): Boolean = Files.isDirectory(root.toPath(), NOFOLLOW_LINKS) &&
        !Files.isSymbolicLink(root.toPath()) && Files.isReadable(root.toPath())
    fun documentAvailable(root: File, path: String): Boolean {
        val file = File(root, path).absoluteFile.normalize()
        if (!PathSecurity.contains(root, file)) return false
        var cursor = file.parentFile ?: return false
        while (true) {
            if (!projectAvailable(cursor)) return false
            if (cursor == root) return true
            cursor = cursor.parentFile ?: return false
        }
    }
    fun supportedCapabilities(native: LspNegotiatedCapabilities): LspNegotiatedCapabilities = native.copy(
        sync = native.sync.copy(save = false, saveIncludeText = false), semanticTokens = false, inlayHint = false,
        codeAction = false, codeActionResolve = false, callHierarchy = false, workspaceSymbol = false,
    )
    fun requireInitializeContract(result: JsonElement) {
        val caps = (result as? JsonObject)?.get("capabilities") as? JsonObject ?: error("Vue helper capabilities missing")
        val encoding = caps["positionEncoding"] as? JsonPrimitive
        require(encoding == null || encoding.isString && encoding.content == "utf-16") { "Vue requires UTF-16" }
        val native = LspNegotiatedCapabilities.fromInitialize(result, enableTypeDefinition = true)
        require(native.completion && native.hover && native.signatureHelp && native.definition && native.references &&
            native.typeDefinition && native.rename && native.formatting && native.documentSymbol && native.pullDiagnostics) { "Vue fixed native provider contract is unavailable" }
    }
    fun clientCapabilities(base: JsonObject): JsonObject = JsonObject(base + mapOf(
        "general" to buildJsonObject { put("positionEncodings", buildJsonArray { add("utf-16") }) },
        "window" to buildJsonObject { put("workDoneProgress", false) },
        "workspace" to buildJsonObject {
            put("configuration", true); put("workspaceFolders", true); put("applyEdit", false)
            put("didChangeWatchedFiles", buildJsonObject { put("dynamicRegistration", false) })
        },
    ))
    const val APPROVAL_EXPLANATION = "Analyze this entire opened workspace with exact Vue 3.3.13, its TypeScript plugin, private TypeScript 6.0.3 and typescript-language-server 6.0.2. This permission covers ongoing trusted project and ancestor configuration, configured plugins, ordinary child processes and schema/custom-data network effects. These may read or write outside the workspace. ATA-off and private HOME/PATH are not a sandbox or an immutable-configuration execution fence. Ordinary process cleanup cannot undo file/network effects or contain deliberately escaped descendants."
    const val INSTALLATION_DISCLOSURE = "The exact Vue bundle and independent private Node 22.23.3 helper require two explicit managed installations. Enabling analysis does not install them. Existing terminal and TypeScript selections stay unchanged."
    const val FEATURE_NOTE = "Native bounded completion from Vue and private TS-LSP (first 16 per engine, at most 32); TS hover, signature help, navigation and rename; Vue formatting and document symbols; four separately correlated diagnostic sources. Rename/navigation require captured open targets. Commands and code actions remain unavailable. Dirty configuration uses saved native configuration. Bounded workspace/ancestor observation cannot cover arbitrary external/transitive files or network resources; Refresh/reopen after those changes. No full IDE parity or native/device acceptance is claimed. Custom commands retain separate unverified behavior."
}
