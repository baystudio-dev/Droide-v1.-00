package com.baystudio.droide.core

object GooseLanguage {
    // Keep the compiler distinct from unrelated AI tools also named goose.
    const val COMMAND = "goose-lang"
    const val DOCUMENTATION = "https://aardappel.github.io/goose/"
    const val INSTALL_NOTE = "Install the aardappel/goose compiler in Ubuntu when needed, and expose it as goose-lang on PATH. Keep its stdlib beside the executable or set GOOSE_STDLIB. Linux ARM64 currently requires an upstream source build; x64 and macOS binaries are incompatible. No compiler is bundled or downloaded when a file opens."
    const val KEYWORDS = "as block break const continue defer else enum export extern false fn for guard if import in let loop match namespace null pad recursive return reusable root self simd struct thread_fn true type var while"
    const val TEMPLATE = "fn main() {\n    print(\"Hello from Droide\");\n}\n"

    val server = LanguageServerSpec("goose", setOf("goose"), setOf("goose"),
        listOf(listOf(COMMAND, "lsp")))

    fun runPlan(path: String) = RunPlan(listOf(listOf(COMMAND, "run", path)), "$COMMAND run $path")
}
