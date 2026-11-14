package com.baystudio.droide.core

data class LanguageServerSpec(
    val id: String,
    val languageIds: Set<String>,
    val extensions: Set<String>,
    val commands: List<List<String>>,
    val source: String = "built-in",
    val filenames: Set<String> = emptySet(),
)

 
object LanguageServerRegistry {
    val builtIns = listOf(
        GooseLanguage.server,
        LanguageServerSpec("mdx-language-server", setOf("mdx"), setOf("mdx"), listOf(listOf("mdx-language-server", "--stdio"))),
        LspSolidityProfile.spec,
        LspScalaProfile.spec,
        LspAsmProfile.spec,
        LspElixirProfile.spec,
        LspHaskellProfile.spec,
        LspRProfile.spec,
        LanguageServerSpec(LspElpProfile.ID, setOf("erlang"), LspElpProfile.extensions, listOf(listOf(LspElpProfile.COMMAND))),
        LanguageServerSpec(LspGraphqlProfile.ID, setOf("graphql"), LspGraphqlProfile.extensions, listOf(listOf(LspGraphqlProfile.COMMAND))),
        LanguageServerSpec(LspTomlProfile.ID, setOf("toml"), LspTomlProfile.extensions, listOf(listOf(LspTomlProfile.COMMAND))),
        LanguageServerSpec(LspCSharpProfile.ID, setOf("csharp"), LspCSharpProfile.extensions, listOf(listOf(LspCSharpProfile.COMMAND))),
        LanguageServerSpec(LspVueProfile.ID, setOf("vue"), LspVueProfile.extensions, listOf(listOf(LspVueProfile.COMMAND))),
        LanguageServerSpec(LspNimProfile.ID, setOf("nim"), LspNimProfile.extensions, listOf(listOf(LspNimProfile.COMMAND))),
        LanguageServerSpec(LspCMakeProfile.ID, setOf("cmake"), LspCMakeProfile.extensions, listOf(listOf(LspCMakeProfile.COMMAND)), filenames = LspCMakeProfile.filenames),
        LanguageServerSpec(LspSvelteProfile.ID, setOf("svelte"), LspSvelteProfile.extensions, listOf(listOf(LspSvelteProfile.COMMAND, "--stdio"))),
        LanguageServerSpec("python-pyright", setOf("python"), setOf("py", "pyi"), listOf(listOf("pyright-langserver", "--stdio"))),
        LanguageServerSpec("python-pylsp", setOf("python"), setOf("py", "pyi"), listOf(listOf("pylsp"))),
        LanguageServerSpec("typescript", setOf("javascript", "typescript", "react"), setOf("js", "jsx", "mjs", "cjs", "ts", "tsx", "mts", "cts"), listOf(listOf("typescript-language-server", "--stdio"))),
        LanguageServerSpec("kotlin", setOf("kotlin"), setOf("kt", "kts"), listOf(listOf("kotlin-language-server"))),
        LanguageServerSpec("gopls", setOf("go"), setOf("go"), listOf(listOf("gopls"))),
        LanguageServerSpec("rust-analyzer", setOf("rust"), setOf("rs"), listOf(listOf("rust-analyzer"))),
        LanguageServerSpec("clangd", setOf("c", "cpp", "objectivec"), setOf("c", "h", "cc", "cpp", "cxx", "c++", "hpp", "hh", "hxx", "h++", "m", "mm"), listOf(listOf("clangd", "--background-index"))),
        LanguageServerSpec("jdtls", setOf("java"), setOf("java"), listOf(listOf("jdtls"))),
        LanguageServerSpec("ruby-lsp", setOf("ruby"), setOf("rb"), listOf(listOf("ruby-lsp"))),
        LanguageServerSpec("intelephense", setOf("php"), setOf("php"), listOf(listOf("intelephense", "--stdio"))),
        LanguageServerSpec("lua-language-server", setOf("lua"), setOf("lua"), listOf(listOf("lua-language-server"))),
        LanguageServerSpec("bash-language-server", setOf("shell"), setOf("sh", "bash", "zsh"), listOf(listOf("bash-language-server", "start"))),
        LanguageServerSpec("yaml-language-server", setOf("yaml"), setOf("yaml", "yml"), listOf(listOf("yaml-language-server", "--stdio"))),
        LanguageServerSpec("vscode-json-language-server", setOf("json", "jsonc"), setOf("json", "jsonc"), listOf(listOf("vscode-json-language-server", "--stdio"))),
        LanguageServerSpec("vscode-html-language-server", setOf("html"), setOf("html", "htm"), listOf(listOf("vscode-html-language-server", "--stdio"))),
        LanguageServerSpec("vscode-css-language-server", setOf("css", "scss", "less"), setOf("css", "scss", "less"), listOf(listOf("vscode-css-language-server", "--stdio"))),
        LanguageServerSpec("sourcekit-lsp", setOf("swift"), setOf("swift"), listOf(listOf("sourcekit-lsp"))),
        LanguageServerSpec("dart", setOf("dart"), setOf("dart"), listOf(listOf("dart", "language-server", "--protocol=lsp"))),
        LanguageServerSpec("dockerfile-language-server", setOf("dockerfile"), setOf("dockerfile"), listOf(listOf("docker-langserver", "--stdio")), filenames = setOf("dockerfile")),
        LanguageServerSpec("vim-language-server", setOf("vim"), setOf("vim"), listOf(listOf("vim-language-server", "--stdio")), filenames = setOf(".vimrc", "_vimrc")),
        LanguageServerSpec(LspPowerShellProfile.ID, setOf("powershell"), LspPowerShellProfile.extensions, listOf(listOf(LspPowerShellProfile.ID))),
        LanguageServerSpec(LspSqruffProfile.ID, setOf("sql"), LspSqruffProfile.extensions, listOf(listOf(LspSqruffProfile.ID, "lsp"))),
        LanguageServerSpec(LspPrismaProfile.ID, setOf("prisma"), LspPrismaProfile.extensions, listOf(listOf(LspPrismaProfile.ID, "--stdio"))),
        LanguageServerSpec("clojure-lsp", setOf("clojure"), LspClojureProfile.extensions, listOf(listOf("clojure-lsp"))),
        LanguageServerSpec("make-ls", setOf("makefile"), LspMakeProfile.extensions, listOf(listOf("make-ls")), filenames = LspMakeProfile.filenames),
        LanguageServerSpec("lemminx", setOf("xml", "dtd"), LspLemMinxProfile.extensions, listOf(listOf("lemminx"))),
        LanguageServerSpec("zls", setOf("zig"), setOf("zig", "zon"), listOf(listOf("zls"))),
        LanguageServerSpec("buf", setOf("proto"), setOf("proto"), listOf(listOf("buf", "lsp", "serve"))),
        LanguageServerSpec("terraform-ls", setOf("terraform", "terraform-vars"), setOf("tf", "tfvars"), listOf(listOf("terraform-ls", "serve"))),
        LanguageServerSpec("texlab", setOf("latex", "bibtex"), setOf("tex", "sty", "cls", "lco", "bib", "bibtex"), listOf(listOf("texlab"))),
        LanguageServerSpec("marksman", setOf("markdown"), setOf("md", "markdown"), listOf(listOf("marksman", "server"))),
        LanguageServerSpec("perlnavigator", setOf("perl"), setOf("pl", "pm"), listOf(listOf("perlnavigator", "--stdio"))),
    )

    fun forPath(path: String, extra: List<LanguageServerSpec> = emptyList()): List<LanguageServerSpec> {
        val originalLeaf = path.replace('\\', '/').substringAfterLast('/')
        val leaf = originalLeaf.lowercase()
        val ext = leaf.substringAfterLast('.', "")
        val language = LanguageRegistry.forFile(path)?.id
        return (extra + builtIns).distinctBy(LanguageServerSpec::id).filter {
            // MDX retains Markdown editor syntax, but Marksman does not parse MDX.
            if (it.source == "built-in" && it.id == LspSolidityProfile.ID) LspSolidityProfile.supportsPath(path)
            else if (it.source == "built-in" && it.id == LspScalaProfile.ID) LspScalaProfile.supportsPath(path)
            else if (it.source == "built-in" && it.id == LspAsmProfile.ID) LspAsmProfile.supportsPath(path)
            else if (it.source == "built-in" && it.id == LspElixirProfile.ID) LspElixirProfile.supportsPath(path)
            else if (it.source == "built-in" && it.id == LspHaskellProfile.ID) LspHaskellProfile.supportsPath(path)
            else if (it.source == "built-in" && it.id == LspRProfile.ID) LspRProfile.supportsPath(path)
            else if (it.source == "built-in" && it.id == LspElpProfile.ID) LspElpProfile.supportsPath(path)
            else if (it.source == "built-in" && it.id == LspGraphqlProfile.ID) LspGraphqlProfile.supportsPath(path)
            else if (it.source == "built-in" && it.id == LspTomlProfile.ID) LspTomlProfile.supportsPath(path)
            else if (it.source == "built-in" && it.id == LspCSharpProfile.ID) LspCSharpProfile.supportsPath(path)
            else if (it.source == "built-in" && it.id == LspVueProfile.ID) LspVueProfile.supportsPath(path)
            else if (it.source == "built-in" && it.id == LspNimProfile.ID) LspNimProfile.supportsPath(path)
            else if (it.source == "built-in" && it.id == LspCMakeProfile.ID) LspCMakeProfile.supportsPath(path)
            else if (it.source == "built-in" && it.id == LspSvelteProfile.ID) LspSvelteProfile.supportsPath(path)
            else if (it.source == "built-in" && it.id == LspPowerShellProfile.ID) LspPowerShellProfile.supportsPath(path)
            else if (it.source == "built-in" && it.id == LspPrismaProfile.ID) LspPrismaProfile.supportsPath(path)
            else if (it.source == "built-in" && it.id == "clojure-lsp") LspClojureProfile.supportsPath(path)
            else if (it.source == "built-in" && it.id == "make-ls") LspMakeProfile.supportsPath(path)
            else if (it.source == "built-in" && it.id == "lemminx") originalLeaf.substringAfterLast('.', "") in LspLemMinxProfile.extensions
            else if (it.source == "built-in" && it.id == "zls") originalLeaf.endsWith(".zig") || originalLeaf.endsWith(".zon")
            else if (it.source == "built-in" && it.id == "buf") originalLeaf.endsWith(".proto")
            else if (it.source == "built-in" && it.id == "terraform-ls") originalLeaf.endsWith(".tf") || originalLeaf.endsWith(".tfvars")
            else if (it.source == "built-in" && it.id == "marksman") ext in it.extensions
            else ext in it.extensions || leaf in it.filenames || (language != null && language in it.languageIds)
        }
    }

    /** Editor display families are not necessarily Language Server Protocol language IDs. */
    fun protocolLanguageId(path: String): String {
        val leaf = path.replace('\\', '/').substringAfterLast('/')
        val extension = leaf.substringAfterLast('.', "")
        if (extension == "C" || extension == "H") return "cpp"
        return when (extension.lowercase()) {
            "mdx" -> "mdx"
            "asm", "s" -> "asm"
            "lhs" -> "literate haskell"
            "jsx" -> "javascriptreact"
            "tsx" -> "typescriptreact"
            "m" -> "objective-c"
            "mm" -> "objective-cpp"
            "sh", "bash", "zsh" -> "shellscript"
            "bat", "cmd" -> "bat"
            "bib", "bibtex" -> "bibtex"
            "tfvars" -> "terraform-vars"
            "zon" -> "zig"
            "dtd" -> "dtd"
            "ps1", "psm1", "psd1" -> "powershell"
            else -> LanguageRegistry.forFile(path)?.id ?: extension.lowercase().ifBlank { "plaintext" }
        }
    }

    /** These formats retain syntax support; no unrelated server is substituted. */
    fun unavailableReason(path: String): String? = LocalDocumentIntelligence.status(path)
        ?: if (path.substringAfterLast('.', "").equals("makefile", ignoreCase = true) && !LspMakeProfile.supportsPath(path))
            "Make syntax support only for .makefile suffixes. The built-in Make server accepts Makefile, GNUmakefile, .mk and .mak; other names need a custom server."
        else null
}

