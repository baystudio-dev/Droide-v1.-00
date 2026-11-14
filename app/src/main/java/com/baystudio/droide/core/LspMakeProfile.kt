package com.baystudio.droide.core

/** Exact make-ls 0.1.25 client policy. No Make recipe or substitute parser is run. */
internal object LspMakeProfile {
    val extensions = setOf("mk", "mak")
    val filenames = setOf("Makefile", "makefile", "GNUmakefile")
    const val IDENTITY = "make-ls-0.1.25-project-read-full-sync-masked-providers-v1"

    fun supportsPath(path: String): Boolean {
        val leaf = path.replace('\\', '/').substringAfterLast('/')
        return leaf in filenames || leaf.substringAfterLast('.', "") in extensions
    }

    // Merged include nodes lose ownership in hover/symbols; the released formatter
    // can rewrite CRLF continuations and define bodies. Never apply guessed repairs.
    fun supportedCapabilities(native: LspNegotiatedCapabilities): LspNegotiatedCapabilities =
        native.copy(hover = false, documentSymbol = false, formatting = false)

    fun unavailableOperation(operation: String): String? = when (operation) {
        "hover", "documentSymbol", "formatting", "formatDocument", "format" ->
            "Make LS 0.1.25: hover, document symbols and formatting are unavailable because of upstream include-origin and formatting defects."
        else -> null
    }

    const val FEATURE_NOTE = "Hover, document symbols and formatting are unavailable for this pin because of upstream correctness defects. Completion, definitions/references, heuristic diagnostics and selected-diagnostic .PHONY edits are supported within client guards. Unversioned diagnostics do not certify current Make/build validity; CRLF continuation parsing is partial. Included files with unrelated extensions or another project root are not fully synchronized. IDE writes retire indexes; the next supported request replays current buffers. External changes require reopen. No Make recipe, build, compiler, rename or workspace-symbol adapter is added."
}
