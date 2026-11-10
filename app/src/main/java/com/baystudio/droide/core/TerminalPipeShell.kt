package com.baystudio.droide.core

/** A pipe is a command channel, never a controlling TTY. Keep one shell/cwd/environment. */
internal object TerminalPipeShell {
    const val MAX_COMMAND_CHARS = 64_000
    const val MAX_FRAME_CHARS = MAX_COMMAND_CHARS * 4 + 4_096
    const val MAX_QUEUED_FRAME_CHARS = 256_000 * 4 + 32 * 4_096
    val environment = mapOf("TERM" to "dumb")

    fun args(interactiveArgs: List<String>): List<String> = interactiveArgs.mapNotNull { arg ->
        when {
            arg == "--interactive" -> null
            arg.startsWith('-') && !arg.startsWith("--") && arg.length > 1 && 'i' in arg ->
                arg.filterNot { it == 'i' }.takeIf { it != "-" }
            else -> arg
        }
    } + "-s"

    fun command(command: String): String {
        require(command.length <= MAX_COMMAND_CHARS && '\u0000' !in command) { "Terminal command is too large or invalid" }
        val token = TerminalCommandActivityTracker.TOKEN_ENV
        val prefix = TerminalCommandActivityTracker.TITLE_PREFIX
        return "__droide_pipe_previous=\$?; printf '\\033]2;$prefix%s:running\\007' \"\$$token\"; " +
            "__droide_pipe_restore \"\$__droide_pipe_previous\"; " +
            "eval -- ${literal(command)} </dev/null\n" + ready()
    }

    fun ready(): String = TerminalPromptPresentation.bashEnvironment().getValue("PROMPT_COMMAND") +
        "; printf '%s' '\$ '; __droide_pipe_restore() { return \"\$1\"; }; " +
        "__droide_pipe_restore \"\$__droide_terminal_exit\""

    private fun literal(text: String): String = "'" + text.replace("'", "'\\''") + "'"
}
