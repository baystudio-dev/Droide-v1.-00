package com.baystudio.droide.core

/** Editor syntax does not establish that an executable runtime is installed. */
object LanguageRuntimePresentation {
    enum class Kind { DATA_OR_DOCUMENT, LINUX_SHELL, SEPARATE_TOOL }

    // A missing runtime mapping alone is not evidence that a language needs no tools.
    private val dataOrDocumentLanguages = setOf(
        "json", "yaml", "toml", "ini", "properties", "markdown", "html", "css", "graphql",
    )

    fun kind(languageId: String): Kind = when (languageId) {
        "shell" -> Kind.LINUX_SHELL
        in dataOrDocumentLanguages -> Kind.DATA_OR_DOCUMENT
        else -> Kind.SEPARATE_TOOL
    }

    fun description(languageId: String, name: String): String = if (languageId == "batch") {
        "Built-in Batch syntax, snippets and local completion. Running .bat/.cmd needs Windows cmd.exe; Ubuntu's sh/Bash cannot execute these scripts. No Windows runtime or language server is installed by Droide."
    } else when (kind(languageId)) {
        Kind.DATA_OR_DOCUMENT -> "Editor syntax support for $name. No standalone runtime or compiler is required for editing these files. Language servers and formatting tools are separate, optional extensions."
        Kind.LINUX_SHELL -> "Editor syntax support for Shell. Scripts run with the shell in the Linux terminal; Ubuntu provides sh and Bash. Language servers and shell analysis tools are separate, optional extensions."
        Kind.SEPARATE_TOOL -> "Editor syntax support for $name. Runtime, compiler and language server are installed separately."
    }

    fun listStatus(languageId: String): String = when (kind(languageId)) {
        Kind.DATA_OR_DOCUMENT -> "No standalone runtime / compiler required"
        Kind.LINUX_SHELL -> "Uses the Linux terminal shell"
        Kind.SEPARATE_TOOL -> "Runtime / compiler installed separately"
    }

    fun syntaxStatus(languageId: String): String = when (kind(languageId)) {
        Kind.DATA_OR_DOCUMENT -> "Editor syntax available; no standalone runtime / compiler required"
        Kind.LINUX_SHELL -> "Editor syntax available; Linux shell status checked separately"
        Kind.SEPARATE_TOOL -> "Editor syntax available; runtime / compiler installed separately"
    }

    data class ShellStatus(val shPath: String?, val bashPath: String?) {
        // Run File uses sh; Bash by itself does not establish that Run File can launch.
        val runFileShellDetected: Boolean get() = shPath != null

        val detail: String get() = when {
            shPath != null -> "sh detected: $shPath"
            bashPath != null -> "Bash detected; sh not detected"
            else -> "Not detected in local Ubuntu"
        }

        val guidance: String get() = if (shPath != null) {
            "Shell scripts run in the Linux terminal using sh. " +
                if (bashPath != null) "Bash is also detected: $bashPath. No separate shell installation is needed."
                else "No separate sh installation is needed; Bash availability is checked separately."
        } else {
            "Open Terminal → + Linux to prepare or check Ubuntu, then verify shell status. Run File requires sh; syntax support alone does not provide it."
        }
    }

    fun shellStatus(localLinuxExecutables: Map<String, String>): ShellStatus {
        fun path(command: String) = localLinuxExecutables[command]?.takeIf {
            it.startsWith('/') && it.none { character -> character == '\n' || character == '\r' || character == '\u0000' }
        }
        return ShellStatus(path("sh"), path("bash"))
    }
}
