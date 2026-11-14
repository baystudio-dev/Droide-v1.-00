package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.serialization.json.*

/** User-managed HLS/GHC. A discovered wrapper is not a compatible project toolchain. */
internal object LspHaskellProfile {
    const val ID = "haskell-hls"
    const val IDENTITY = "hls-user-selected-ghc-opened-workspace-v1"
    const val WRAPPER = "haskell-language-server-wrapper"
    val extensions = setOf("hs", "lhs")
    // HLS's documented worker count, not a limit on GHC/project subprocesses or memory.
    val command = listOf(WRAPPER, "--lsp", "-j", "2")
    val spec = LanguageServerSpec(ID, setOf("haskell", "literate haskell"), extensions, listOf(command))

    fun matches(candidate: LanguageServerSpec): Boolean = candidate == spec
    fun supportsPath(path: String): Boolean =
        path.replace('\\', '/').substringAfterLast('/').substringAfterLast('.', "") in extensions

    fun isRoute(candidate: LanguageServerSpec, path: String? = null): Boolean =
        candidate.id == ID || candidate.languageIds.any { it in spec.languageIds } ||
            candidate.extensions.any { it in extensions } || path?.let(::supportsPath) == true

    fun projectRoot(workspace: File, document: File): File {
        val root = workspace.canonicalFile
        require(PathSecurity.contains(root, document.canonicalFile)) { "Haskell document escaped the workspace" }
        return root
    }

    fun projectAvailable(project: File): Boolean = Files.isDirectory(project.toPath(), NOFOLLOW_LINKS)

    // Send these before didOpen as well as answering HLS's standard configuration query.
    // These upstream settings reduce eager work; they do not sandbox project compilation.
    fun settings(): JsonObject = buildJsonObject {
        put("checkProject", false)
        put("checkParents", "CheckOnSave")
        put("maxCompletions", 40)
    }

    fun configuration(): JsonObject = buildJsonObject {
        put("settings", buildJsonObject { put("haskell", settings()) })
    }

    fun clientCapabilities(base: JsonObject): JsonObject = JsonObject(base + (
        "workspace" to JsonObject(base.getValue("workspace").jsonObject + mapOf(
            "configuration" to JsonPrimitive(true),
            "didChangeConfiguration" to buildJsonObject { put("dynamicRegistration", false) },
            "didChangeWatchedFiles" to buildJsonObject { put("dynamicRegistration", false) },
        ))
    ))

    /** Constant public settings only: no file reads, URI resolution, tool probes or execution. */
    fun configurationReply(params: JsonElement?): JsonArray {
        val items = (params as? JsonObject)?.get("items") as? JsonArray
            ?: error("HLS configuration requires an items array")
        require(items.size <= 64) { "Too many HLS configuration items" }
        val values = settings()
        return buildJsonArray {
            items.forEach { item ->
                val objectItem = item as? JsonObject
                val section = objectItem?.get("section")
                add(when {
                    objectItem == null -> JsonNull
                    section == null || section == JsonNull -> buildJsonObject { put("haskell", values) }
                    section == JsonPrimitive("haskell") -> values
                    section is JsonPrimitive && section.isString && section.content.startsWith("haskell.") ->
                        values[section.content.removePrefix("haskell.")] ?: JsonNull
                    else -> JsonNull
                })
            }
        }
    }

    const val INSTALL_NOTE = "Install HLS and a compatible GHC in the shared Ubuntu workstation; " +
        "keep Cabal or Stack available when the project needs them. Put haskell-language-server-wrapper on PATH. " +
        "Use your existing tool manager (such as GHCup) to install or update the versions selected for your project. " +
        "Finding the wrapper alone does not establish GHC compatibility. Droide does not install or pin these tools."
    const val APPROVAL_EXPLANATION = "Start your installed HLS wrapper in this workspace. " +
        "It selects a server for the project's GHC and may run Cabal, Stack, hie-bios scripts, compiler plugins " +
        "and Template Haskell with guest file/network access. Project tooling may read outside the workspace or fetch dependencies. " +
        "Whole-project initial checking is requested off; opened modules and their dependencies can still compile. " +
        "The workspace boundary and worker setting are not a sandbox or a hard memory limit."
    const val FEATURE_NOTE = "Haskell and literate Haskell using your installed HLS and project GHC. " +
        "Completion, hover, navigation, diagnostics, formatting and rename depend on the running server. " +
        "Analysis starts only when enabled for the project; normal saves reuse the session. " +
        "Revoke and re-enable analysis after changing project/toolchain configuration or files from the terminal. " +
        "Cabal-file assistance, REPL, DAP and server-command execution are not included."
}
