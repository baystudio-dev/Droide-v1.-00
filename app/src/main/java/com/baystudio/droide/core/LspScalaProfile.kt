package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.serialization.json.*

/** Upstream user-installed launcher; no runtime provisioning or exact-version admission. */
internal object LspScalaProfile {
    const val ID = "metals"
    const val IDENTITY = "metals-user-managed-initial-import-v1"
    const val COMMAND = "metals"
    val extensions = setOf("scala", "sc", "sbt")
    val command = listOf(COMMAND)
    val spec = LanguageServerSpec(ID, setOf("scala"), extensions, listOf(command))

    fun matches(candidate: LanguageServerSpec): Boolean = candidate == spec
    fun supportsPath(path: String): Boolean =
        path.replace('\\', '/').substringAfterLast('/').substringAfterLast('.', "") in extensions
    fun isRoute(candidate: LanguageServerSpec, path: String? = null): Boolean =
        candidate.id == ID || "scala" in candidate.languageIds || candidate.extensions.any { it in extensions } ||
            path?.let(::supportsPath) == true

    fun projectRoot(workspace: File, document: File): File {
        val root = workspace.canonicalFile
        require(PathSecurity.contains(root, document.canonicalFile)) { "Scala document escaped the workspace" }
        return root
    }
    fun projectAvailable(project: File): Boolean = Files.isDirectory(project.toPath(), NOFOLLOW_LINKS)

    // Metals pulls these settings before starting build imports in WorkspaceLspService.
    fun settings(): JsonObject = buildJsonObject {
        put("autoImportBuilds", "initial")
        put("startMcpServer", false)
        put("superMethodLensesEnabled", false)
        put("scalafixLintEnabled", false)
        put("enableSemanticHighlighting", false)
    }
    fun initializationOptions(): JsonObject = buildJsonObject {
        listOf("debuggingProvider", "runProvider", "executeClientCommandProvider", "inputBoxProvider",
            "quickPickProvider", "readClipboardProvider", "treeViewProvider", "testExplorerProvider",
            "didFocusProvider", "isVirtualDocumentSupported", "isHttpEnabled", "openFilesOnRenameProvider",
            "openNewWindowProvider", "copyWorksheetOutputProvider").forEach { put(it, false) }
        put("statusBarProvider", "off")
        put("isExitOnShutdown", true)
    }
    fun configuration(): JsonObject = buildJsonObject {
        put("settings", buildJsonObject { put("metals", settings()) })
    }
    fun clientCapabilities(base: JsonObject): JsonObject = JsonObject(base + (
        "workspace" to JsonObject(base.getValue("workspace").jsonObject + mapOf(
            "configuration" to JsonPrimitive(true),
            "didChangeConfiguration" to buildJsonObject { put("dynamicRegistration", false) },
            "didChangeWatchedFiles" to buildJsonObject { put("dynamicRegistration", false) },
        ))
    ))
    /** Fixed non-secret per-server settings. A supplied URI never opens a file or runs a tool. */
    fun configurationReply(params: JsonElement?): JsonArray {
        val items = (params as? JsonObject)?.get("items") as? JsonArray
            ?: error("Metals configuration requires an items array")
        require(items.size <= 64) { "Too many Metals configuration items" }
        val values = settings()
        return buildJsonArray {
            items.forEach { item ->
                val objectItem = item as? JsonObject
                val section = objectItem?.get("section")
                add(when {
                    objectItem == null -> JsonNull
                    section == null || section == JsonNull -> buildJsonObject { put("metals", values) }
                    section == JsonPrimitive("metals") -> values
                    section is JsonPrimitive && section.isString && section.content.startsWith("metals.") ->
                        values[section.content.removePrefix("metals.")] ?: JsonNull
                    else -> JsonNull
                })
            }
        }
    }

    const val INSTALL_NOTE = "Install/update Metals and a compatible JDK using your normal terminal/tool manager in shared Ubuntu. " +
        "Put the metals launcher on PATH; configure its JVM options and project build tool yourself. " +
        "Launcher discovery does not establish runtime compatibility. Droide does not install or pin a Scala/JDK/compiler version."
    const val APPROVAL_EXPLANATION = "Start your installed Metals in this workspace. Initial build import is enabled: " +
        "Metals may execute project build definitions, create .metals/.bsp state, download Scala/compiler dependencies, " +
        "start BSP/Bloop/Scala CLI processes and compile/index the project. These processes have guest file/network access, " +
        "are not a sandbox and have no hard mobile memory guarantee. Optional HTTP/MCP/client-command interfaces are requested off. " +
        "A mixed-build workspace may need an existing BSP selection or user target-build-tool setting; Droide never answers arbitrary dialogs automatically."
    const val FEATURE_NOTE = "Scala, scripts and sbt definitions through user-installed Metals. Standard completion, hover, navigation, " +
        "diagnostics, formatting and reviewed edits depend on the server and imported build. Connected does not mean indexing finished. " +
        "Normal saves reuse the session. Import after build changes may require terminal preparation and revoke/re-enable analysis. " +
        "Use user JVM/build settings for mobile resource limits. Metals-specific views, worksheet evaluation, test explorer and DAP are not implemented. " +
        "Build/run/debug tools remain user-managed; native Ubuntu/Android acceptance is unverified."
}
