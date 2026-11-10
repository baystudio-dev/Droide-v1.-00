package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** Shell presentation only: never an execution or filesystem authority. */
internal object TerminalPromptPresentation {
    const val TITLE_PREFIX = "droide-cwd-v1:"
    const val COMPACT_PROMPT = "\$ "
    const val MAX_PATH_BYTES = 4_096

    private val readDirectoryCommand = "__droide_terminal_cwd=\$(command pwd -P 2>/dev/null && printf '.'); " +
        "__droide_terminal_cwd_status=\$?; " +
        "if [ \"\$__droide_terminal_cwd_status\" -eq 0 ]; then " +
        "__droide_terminal_cwd=\${__droide_terminal_cwd%.}; " +
        "__droide_terminal_cwd=\${__droide_terminal_cwd%?}; fi"
    private val emitDirectoryCommand = "printf '\\033]2;$TITLE_PREFIX'; printf '%s' \"\$__droide_terminal_cwd\" | base64 | tr -d '\\r\\n'; printf '\\007'"
    val directoryCommand = "$readDirectoryCommand; " +
        "if [ \"\$__droide_terminal_cwd_status\" -eq 0 ]; then $emitDirectoryCommand; fi"

    // Save $? before any presentation command. The nonce is per interactive shell, and these markers
    // never serve as execution evidence for Agent pipe commands.
    private const val activityPrefix = TerminalCommandActivityTracker.TITLE_PREFIX
    private const val activityToken = TerminalCommandActivityTracker.TOKEN_ENV
    internal val activityDirectoryCommand = "__droide_terminal_exit=\$?; $readDirectoryCommand; " +
        "if [ -n \"\${$activityToken:-}\" ]; then " +
        "printf '\\033]2;$activityPrefix%s:pulse\\007' \"\$$activityToken\"; " +
        "if [ \"\$__droide_terminal_cwd_status\" -eq 0 ]; then " +
        "printf '\\033]2;$activityPrefix%s:ready:%s\\007' \"\$$activityToken\" \"\$__droide_terminal_exit\"; " +
        "else printf '\\033]2;$activityPrefix%s:cwd-unavailable:%s\\007' \"\$$activityToken\" \"\$__droide_terminal_exit\"; fi; fi; " +
        "if [ \"\$__droide_terminal_cwd_status\" -eq 0 ]; then $emitDirectoryCommand; fi"
    private val bashPreExecutionPrompt = "\$(if [ -n \"\${$activityToken:-}\" ]; then " +
        "printf '\\033]2;$activityPrefix%s:running\\007' \"\$$activityToken\"; fi)"

    fun bashEnvironment(): Map<String, String> = mapOf(
        "PS1" to COMPACT_PROMPT,
        // Bash caches command paths. Clear only that cheap in-memory table after each user command,
        // preserving $? before the builtin runs. No rootfs scan.
        "PROMPT_COMMAND" to activityDirectoryCommand.replace("__droide_terminal_exit=\$?; ", "__droide_terminal_exit=\$?; hash -r; "),
        "PS0" to bashPreExecutionPrompt,
    )

    /** Only the user's interactive Bash loads rc. Pipe/protocol commands remain noninteractive. */
    fun bashInteractiveArgs(rootfs: File): List<String> {
        installProfile(PathSecurity.resolveWithin(rootfs, "opt/droide/terminal"), bashProfile(), "bashrc-v1.sh")
        return listOf("--noprofile", "--rcfile", "/opt/droide/terminal/bashrc-v1.sh", "-i")
    }

    // The rc profile installs tracking after user rc. Do not inject it twice or erase prompt hooks
    // that Ubuntu's system bashrc may have supplied before our profile.
    fun bashStartupEnvironment(): Map<String, String> = mapOf("PS1" to COMPACT_PROMPT)

    internal fun bashProfile(): String = """
        # Droide interactive shell; never edit or replace the user's rc file.
        if [ -r "${'$'}HOME/.bashrc" ]; then . "${'$'}HOME/.bashrc"; fi
        ${bashDefaultPrompt()}
        __droide_prompt_restore_status() { return "${'$'}1"; }
        PROMPT_COMMAND=(${shellLiteral(bashEnvironment().getValue("PROMPT_COMMAND") + "; __droide_prompt_restore_status \"\$__droide_terminal_exit\"")} "${'$'}{PROMPT_COMMAND[@]}")
        PS0=${shellLiteral(bashPreExecutionPrompt)}"${'$'}{PS0-}"
    """.trimIndent() + "\n"

    private fun bashDefaultPrompt(): String {
        // Exact defaults from the packaged Ubuntu root and skeleton rc files only. A different (even
        // empty), readonly, or hook-managed user prompt stays user-owned.
        val plain = """${'$'}{debian_chroot:+(${'$'}debian_chroot)}\u@\h:\w\${'$'} """
        val color = """${'$'}{debian_chroot:+(${'$'}debian_chroot)}\[\033[01;32m\]\u@\h\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\${'$'} """
        // Quote the whole title prefix: a chroot name must never become a case pattern.
        val title = "\"" + """\[\e]0;${'$'}{debian_chroot:+(${'$'}debian_chroot)}\u@\h: \w\a\]""" + "\""
        return """
            # Set presentation once after rc; user hooks can still choose their own prompt.
            case "${'$'}{PS1-}" in
                ${shellLiteral(plain)}|${shellLiteral(color)}|$title${shellLiteral(plain)}|$title${shellLiteral(color)})
                    if [[ ${'$'}{PS1@a} != *r* ]]; then PS1=${shellLiteral(COMPACT_PROMPT)}; fi
                    ;;
            esac
        """.trimIndent()
    }

    fun androidEnvironment(profileDirectory: File): Map<String, String> = mapOf(
        "PS1" to COMPACT_PROMPT,
        "ENV" to installProfile(profileDirectory, androidProfile()).absolutePath,
    )

    fun ashEnvironment(rootfs: File): Map<String, String> {
        installProfile(PathSecurity.resolveWithin(rootfs, "opt/droide/terminal"), ashProfile())
        return mapOf("PS1" to COMPACT_PROMPT, "ENV" to "/opt/droide/terminal/prompt-v1.sh")
    }

    internal fun androidProfile(): String = """
        # Droide-owned presentation; keep Android's original aliases and shell settings.
        if [ -r /system/etc/mkshrc ]; then . /system/etc/mkshrc; fi
        __droide_prompt_marker=${'$'}(printf '\001')
        __droide_prompt_return=${'$'}(printf '\r')
        PS1="${'$'}__droide_prompt_marker${'$'}__droide_prompt_return${'$'}__droide_prompt_marker"${shellLiteral("\$($activityDirectoryCommand)")}"${'$'}__droide_prompt_marker"'${'$'} '
        unset __droide_prompt_marker __droide_prompt_return
        export PS1
    """.trimIndent() + "\n"

    internal fun ashProfile(): String = """
        # BusyBox ash uses \[ and \] for nonprinting prompt sequences.
        PS1=${shellLiteral("\\[\$($activityDirectoryCommand)\\]\$ ")}
        export PS1
    """.trimIndent() + "\n"

    private fun shellLiteral(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    @Synchronized private fun installProfile(directory: File, content: String, name: String = "prompt-v1.sh"): File {
        check(!PathSecurity.isSymbolicLink(directory)) { "Terminal profile directory is unsafe" }
        check(directory.isDirectory || directory.mkdirs()) { "Terminal profile directory is unavailable" }
        val file = File(directory, name)
        check(!PathSecurity.isSymbolicLink(file)) { "Terminal profile is unsafe" }
        if (!file.isFile || file.readText() != content) file.writeText(content)
        return file
    }

    fun directoryFromTitle(title: String?): String? {
        if (title == null || !title.startsWith(TITLE_PREFIX)) return null
        val encoded = title.removePrefix(TITLE_PREFIX)
        if (encoded.length !in 1..5_464 || !encoded.matches(Regex("[A-Za-z0-9+/]*={0,2}"))) return null
        return runCatching {
            val bytes = Base64.getDecoder().decode(encoded)
            require(bytes.size in 1..MAX_PATH_BYTES)
            val raw = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            require(raw.startsWith('/') && '\u0000' !in raw)
            raw
        }.getOrNull()
    }

    fun displayDirectory(raw: String): String = buildString {
        raw.forEach { c ->
            when {
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                Character.isISOControl(c) || c in '\u202a'..'\u202e' || c in '\u2066'..'\u2069' ||
                    c == '\u200e' || c == '\u200f' || c == '\u061c' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
    }
}

internal class TerminalDirectoryStream(
    private val onDirectory: (String) -> Unit,
    private val onTitle: (String) -> Unit = {},
) {
    private var escape = false
    private var osc: StringBuilder? = null
    private var oscEscape = false
    private var overflow = false

    @Synchronized fun consume(chunk: String): String = buildString {
        for (c in chunk) {
            val current = osc
            if (current != null) {
                if (c == '\u0007' || oscEscape && c == '\\') {
                    if (!overflow) {
                        val payload = current.toString()
                        if (payload.startsWith("0;") || payload.startsWith("2;")) {
                            onTitle(payload.substring(2))
                            TerminalPromptPresentation.directoryFromTitle(payload.substring(2))?.let(onDirectory)
                        }
                    }
                    osc = null; oscEscape = false; overflow = false
                } else {
                    if (oscEscape && !overflow) current.append('\u001b')
                    oscEscape = c == '\u001b'
                    if (!oscEscape && !overflow) current.append(c)
                    if (current.length > 8_192) { current.setLength(0); overflow = true }
                }
            } else if (escape) {
                escape = false
                if (c == ']') osc = StringBuilder() else { append('\u001b'); append(c) }
            } else if (c == '\u001b') escape = true else if (c != '\u0001') append(c)
        }
    }
}
