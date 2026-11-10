package com.baystudio.droide.core

/** Captured native PSES context, selected only by the exact built-in manager policy. This is a
 * UTF-16 adapter, not a PowerShell parser or permission to start a runspace. */
internal object LspPowerShellCompletionContext {
    const val MAX_TEXT_CHARS = 2_000_000
    const val UI_WATCHDOG_MS = 10_000L
    private val nativeTriggers = setOf('.', '-', ':', '\\', '$', ' ')

    data class Context(
        val prefix: String,
        val triggerCharacter: String?,
        val requestCaret: LspCompletionCaret,
    )

    fun capture(text: String, line1: Int, col1: Int): Context? {
        if (line1 < 1 || col1 < 1 || !acceptsText(text)) return null
        var start = 0
        repeat(line1 - 1) {
            val newline = text.indexOf('\n', start)
            if (newline < 0) return null
            start = newline + 1
        }
        val newline = text.indexOf('\n', start)
        var end = if (newline < 0) text.length else newline
        if (newline >= 0 && end > start && text[end - 1] == '\r') end--
        if (col1 - 1 > end - start) return null
        val offset = start + col1 - 1
        if (!isBoundary(text, offset)) return null
        var prefixStart = offset
        // Exactly the existing PowerShell local-prefix rule. Space and backslash are trigger hints only;
        // neither widens an identifier or replacement.
        while (prefixStart > start && text[prefixStart - 1].let {
                it.isLetterOrDigit() || it == '_' || it == '$' || it == '-'
            }) prefixStart--
        return Context(
            prefix = text.substring(prefixStart, offset),
            triggerCharacter = text.getOrNull(offset - 1)?.takeIf { offset > start && it in nativeTriggers }?.toString(),
            requestCaret = LspCompletionCaret(line1, col1),
        )
    }

    /** Native ScriptFile coordinates are admitted only for exact LF/CRLF UTF-16. */
    fun acceptsText(text: String): Boolean {
        if (text.length > MAX_TEXT_CHARS) return false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\u0000' -> return false
                c == '\r' && text.getOrNull(i + 1) != '\n' -> return false
                c.isHighSurrogate() -> {
                    if (text.getOrNull(i + 1)?.isLowSurrogate() != true) return false
                    i++
                }
                c.isLowSurrogate() -> return false
            }
            i++
        }
        return true
    }

    internal fun isBoundary(text: String, offset: Int): Boolean =
        offset in 0..text.length && (offset == 0 || offset == text.length ||
            !(text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate()) &&
            !(text[offset - 1] == '\r' && text[offset] == '\n'))
}
