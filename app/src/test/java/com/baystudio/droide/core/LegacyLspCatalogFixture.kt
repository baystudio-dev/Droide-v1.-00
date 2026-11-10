package com.baystudio.droide.core

internal object LegacyLspCatalogFixture {
    private val workspaceProfileIds = listOf(
        "python-pyright",
        "python-pylsp",
        "typescript",
        "kotlin",
        "gopls",
        "rust-analyzer",
        "clangd",
        "jdtls",
        "ruby-lsp",
        "intelephense",
        "lua-language-server",
        "bash-language-server",
        "yaml-language-server",
        "vscode-json-language-server",
        "vscode-html-language-server",
        "vscode-css-language-server",
        "sourcekit-lsp",
        "dart",
        "dockerfile-language-server",
    )

    fun workspaceProfiles(): List<LanguageServerSpec> = workspaceProfileIds.map { id ->
        LanguageServerRegistry.builtIns.single { it.id == id }
    }
}
