package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.serialization.json.*

/** User-installed asm-lsp; upstream positional/update limitations are explicit. */
internal object LspAsmProfile {
    const val ID = "asm-lsp"
    const val IDENTITY = "asm-user-managed-readonly-snapshot-v1"
    val extensions = setOf("asm", "s", "S")

    const val COMMAND = "asm-lsp"
    val command = listOf(COMMAND)
    val spec = LanguageServerSpec(ID, setOf("assembly"), extensions, listOf(command))

    fun matches(candidate: LanguageServerSpec): Boolean = candidate == spec

    fun supportsPath(path: String): Boolean =
        path.replace('\\', '/').substringAfterLast('/').substringAfterLast('.', "") in extensions

    fun isRoute(candidate: LanguageServerSpec, path: String? = null): Boolean =
        candidate.id == ID || "assembly" in candidate.languageIds ||
            candidate.extensions.any { it in extensions } || path?.let(::supportsPath) == true

    /** The opened workspace is the Assembly session boundary; no recursive scan or tool probe. */
    fun projectRoot(workspace: File, document: File): File {
        val root = workspace.canonicalFile
        require(PathSecurity.contains(root, document.canonicalFile)) { "Assembly document escaped the workspace" }
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

    fun acceptsPositionFeatures(text: String): Boolean = text.all { it.code in 1..127 }

    fun supportedCapabilities(native: LspNegotiatedCapabilities): LspNegotiatedCapabilities = native.copy(
        sync = native.sync.copy(save = false),
        definition = false, typeDefinition = false, references = false, implementation = false,
        workspaceSymbol = false, codeAction = false, codeActionResolve = false,
        callHierarchy = false, rename = false, formatting = false,
        semanticTokens = false, inlayHint = false, pullDiagnostics = false, diagnosticIdentifier = null,
    )

    const val INSTALL_NOTE = "Install/update asm-lsp through your chosen upstream release or Cargo in shared Ubuntu. " +
        "Use asm-lsp gen-config in Terminal when you need a .asm-lsp.toml for the intended architecture/assembler. " +
        "Native ARM64 builds default to ARM64 in the reviewed server. A discovered command does not prove compatibility. " +
        "Droide does not install or pin versions; compiler and debugger tools remain separate."
    const val APPROVAL_EXPLANATION = "Start your installed asm-lsp with this workspace as root. " +
        "It reads project/global .asm-lsp.toml and compilation databases, and may inspect compiler/include paths with guest file/network access. " +
        "Droide does not request compilation diagnostics or send save triggers from this profile. " +
        "Configuration and selected executables remain user-controlled; the project scope is not a sandbox."
    const val FEATURE_NOTE = "User-installed assembly completion, hover, signatures and document symbols when advertised. " +
        "Native position features currently require ASCII buffer text because the reviewed upstream mixes byte and UTF-16 positions; " +
        "Unicode editing and local completion remain available. Changes refresh the document snapshot when needed without restarting the server. " +
        "Compiler diagnostics, cross-file navigation, formatting, rename and DAP are not part of this profile. " +
        "Run/build uses the separately installed native compiler. Revoke/re-enable after configuration or external changes; native/device acceptance is unverified."
}
