package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.serialization.json.*

/** User-installed server used by Nomic Foundation's VS Code extension; no bundle/version admission. */
internal object LspSolidityProfile {
    const val ID = "nomicfoundation-solidity-language-server"
    const val IDENTITY = "solidity-user-managed-initial-telemetry-off-v1"
    const val COMMAND = ID
    val extensions = setOf("sol")
    val command = listOf(COMMAND, "--stdio")
    val spec = LanguageServerSpec(ID, setOf("solidity"), extensions, listOf(command))

    fun matches(candidate: LanguageServerSpec): Boolean = candidate == spec

    fun supportsPath(path: String): Boolean =
        path.replace('\\', '/').substringAfterLast('/').substringAfterLast('.', "") in extensions

    fun isRoute(candidate: LanguageServerSpec, path: String? = null): Boolean =
        candidate.id == ID || "solidity" in candidate.languageIds ||
            candidate.extensions.any { it in extensions } || path?.let(::supportsPath) == true

    /** The opened workspace is the Solidity session boundary; no recursive scan or tool probe. */
    fun projectRoot(workspace: File, document: File): File {
        val root = workspace.canonicalFile
        require(PathSecurity.contains(root, document.canonicalFile)) { "Solidity document escaped the workspace" }
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

    // Consumed by onInitialize before indexing or compiler/framework startup. No machine ID.
    fun initializationOptions(): JsonObject = buildJsonObject {
        put("telemetryEnabled", false)
    }
    const val INSTALL_NOTE = "Install/update @nomicfoundation/solidity-language-server with npm in shared Ubuntu, then expose " +
        "nomicfoundation-solidity-language-server on PATH. Use a Node version and Linux ARM64 native dependencies supported by your chosen release. " +
        "The inspected release requires Node >=22.15.1; this is documentation, not a version whitelist. " +
        "Launcher discovery does not establish platform compatibility. Extensions can install/update the publisher package; Droide does not pin its version."
    const val APPROVAL_EXPLANATION = "Start your selected Solidity language server for this workspace. It scans Solidity/project files " +
        "including dependencies, loads Hardhat/Foundry and other supported project settings, and may execute build configuration, " +
        "create temporary state, fork workers, download compiler metadata/compilers and compile on open or after edits. " +
        "Guest file/network access is available; project scope is not a sandbox or hard RAM/CPU limit. " +
        "Telemetry is requested off before initialization. Opening a smaller project root reduces indexing scope."
    const val FEATURE_NOTE = "Solidity completion, hover, navigation, diagnostics, formatting and reviewed edits through your installed Nomic Foundation server. " +
        "Compiler versions come from project configuration/pragma and upstream support, not a Droide pin. " +
        "Initial indexing runs in the background; ordinary saves reuse the session. The reviewed server debounces analysis/validation at 240/250 ms, " +
        "but large builds can still be expensive. Unversioned diagnostics are advisory. External project/config changes require revoke/re-enable. " +
        "Hardhat is the primary supported framework; other frameworks are upstream-experimental. " +
        "No debugger, deployment, wallet actions or VS Code-specific project UI is added; compile/test/deploy stay explicit terminal tasks. " +
        "Original server, Linux ARM64/PRoot and Android acceptance remain unverified."
}
