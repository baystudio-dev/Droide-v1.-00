package com.baystudio.droide.core

/** Terminal capabilities must be injected after a guest launcher resets its environment. */
internal object TerminalPtyEnvironment {
    val variables = mapOf(
        "TERM" to "xterm-256color",
        "COLORTERM" to "truecolor",
        "TERM_PROGRAM" to "Droide",
    )
}
