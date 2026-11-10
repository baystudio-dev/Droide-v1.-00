package com.baystudio.droide.core

/** Narrow guard for the pinned native reverse mapper. Never rewrites a coordinate. */
internal object LspTomlPositionAdmission {
    const val MAX_TEXT_UNITS = 2 * 1024 * 1024
    val cursorMethods = setOf("textDocument/completion", "textDocument/hover", "textDocument/prepareRename", "textDocument/rename")
    fun validText(text: String): Boolean {
        if (text.length > MAX_TEXT_UNITS) return false
        var at = 0
        while (at < text.length) {
            val c = text[at++]
            if (c == '\u0000' || c == '\r' && text.getOrNull(at) != '\n') return false
            if (c.isHighSurrogate()) { if (at == text.length || !text[at++].isLowSurrogate()) return false }
            else if (c.isLowSurrogate()) return false
        }
        return true
    }
    /** Zero-based LSP UTF-16 coordinate, including logical EOL but never CRLF interior. */
    fun offset(text: String, line: Int, character: Int): Int? {
        if (line < 0 || character < 0 || !validText(text)) return null
        var start = 0
        repeat(line) { start = text.indexOf('\n', start).takeIf { it >= 0 }?.plus(1) ?: return null }
        val newline = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        val end = if (newline > start && text[newline - 1] == '\r') newline - 1 else newline
        if (character > end - start) return null
        val result = start + character
        if (result > start && result < end && text[result - 1].isHighSurrogate() && text[result].isLowSurrogate()) return null
        return result
    }
    fun outgoing(text: String, line: Int, character: Int): Boolean = offset(text, line, character) != null
    fun admits(method: String, text: String, line: Int, character: Int): Boolean {
        val offset = offset(text, line, character) ?: return false
        if (method !in cursorMethods) return true
        val next = text.getOrNull(offset)
        return next == null || next == '\n' || next == '\r' || next.code <= 0x7f
    }
}
