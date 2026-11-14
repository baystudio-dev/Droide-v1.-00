package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.serialization.json.*

/** User-installed R/languageserver. Discovery is not package or platform certification. */
internal object LspRProfile {
    const val ID = "r-languageserver"
    const val IDENTITY = "r-user-installed-vanilla-bounded-index-v1"
    val extensions = setOf("r", "R")

    // Fixed program only: never interpolate a filename, project setting, or server response.
    // Upstream options() override LSP configuration. Older versions may ignore new cache/index
    // settings; these are preferences, not OS memory/CPU containment or a version whitelist.
    const val PROGRAM = "options(languageserver.index_mode='off', " +
        "languageserver.max_completions=100L, languageserver.parse_delay=0.25, " +
        "languageserver.diagnostics_delay=1, languageserver.parse_cache_max_mb=32L, " +
        "languageserver.diagnostics_cache_max_mb=8L); languageserver::run()"
    val command = listOf("R", "--vanilla", "--no-echo", "-e", PROGRAM)
    val spec = LanguageServerSpec(ID, setOf("r"), extensions, listOf(command))

    fun matches(candidate: LanguageServerSpec): Boolean = candidate == spec

    fun supportsPath(path: String): Boolean =
        path.replace('\\', '/').substringAfterLast('/').substringAfterLast('.', "") in extensions

    fun isRoute(candidate: LanguageServerSpec, path: String? = null): Boolean =
        candidate.id == ID || "r" in candidate.languageIds ||
            candidate.extensions.any { it in extensions } || path?.let(::supportsPath) == true

    /** The opened workspace is the R session boundary; no recursive scan or tool probe. */
    fun projectRoot(workspace: File, document: File): File {
        val root = workspace.canonicalFile
        require(PathSecurity.contains(root, document.canonicalFile)) { "R document escaped the workspace" }
        return root
    }

    fun projectAvailable(project: File): Boolean = Files.isDirectory(project.toPath(), NOFOLLOW_LINKS)

    fun clientCapabilities(base: JsonObject): JsonObject = JsonObject(base + (
        "workspace" to JsonObject(base.getValue("workspace").jsonObject + mapOf(
            "configuration" to JsonPrimitive(false),
            "didChangeConfiguration" to buildJsonObject { put("dynamicRegistration", false) },
            "didChangeWatchedFiles" to buildJsonObject { put("dynamicRegistration", false) },
        ))
    ))

    const val INSTALL_NOTE = "Install R and the R package 'languageserver' in the shared Ubuntu workstation. " +
        "In R, use install.packages('languageserver', repos='https://cloud.r-project.org'). " +
        "Updates use the same command or your project's package manager. Droide does not install or pin R packages automatically. " +
        "An R executable alone does not prove that languageserver or optional lintr/formatting packages are installed."
    const val APPROVAL_EXPLANATION = "Start the selected Ubuntu R and its installed languageserver package. " +
        "The server reads project/package files; package code, lintr configuration and formatting tools may execute " +
        "with guest file/network access. --vanilla skips R startup profiles and saved sessions. " +
        "Workspace-wide background indexing is requested off; opened files, package sources and their static dependencies may still be analyzed. " +
        "The opened workspace is a client scope, not a sandbox. Selected versions and executable provenance are unverified."
    const val FEATURE_NOTE = "User-installed R language server; features are negotiated at startup. " +
        "Completion, hover, signatures, navigation, symbols, formatting and rename depend on server capabilities and installed packages. " +
        "Indexing is limited by upstream settings, not a hard process memory limit. R Markdown is not routed as R source. " +
        "Unversioned diagnostics are advisory. Server commands, DAP, REPL and completion-resolve UI are not added. " +
        "Refresh/reopen analysis after external terminal changes; native Ubuntu/PRoot behavior remains unverified."
}
