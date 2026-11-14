package com.baystudio.droide.core

/** Positions in exact source text. No newline normalization, clamping, or token inference. */
internal object LspGraphqlPositionCodec {
    data class Position(val line: Int, val character: Int)
    fun validText(text: String): Boolean {
        var at = 0
        while (at < text.length) {
            val c = text[at++]
            if (c.isHighSurrogate()) { if (at == text.length || !text[at++].isLowSurrogate()) return false }
            else if (c.isLowSurrogate()) return false
        }
        return true
    }
    fun hasBareCr(text: String): Boolean = text.indices.any { text[it] == '\r' && text.getOrNull(it + 1) != '\n' }
    fun boundary(text: String, offset: Int): Boolean = offset in 0..text.length &&
        !(offset > 0 && offset < text.length && (text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate() || text[offset - 1] == '\r' && text[offset] == '\n'))
    fun offset(text: String, line: Int, character: Int): Int? {
        if (line < 0 || character < 0 || !validText(text)) return null
        var row = 0; var start = 0; var at = 0
        while (at < text.length && row < line) {
            when (text[at++]) { '\r' -> { if (text.getOrNull(at) == '\n') at++; row++; start = at }; '\n' -> { row++; start = at } }
        }
        if (row != line) return null
        var end = start; while (end < text.length && text[end] != '\r' && text[end] != '\n') end++
        if (character > end - start) return null
        return (start + character).takeIf { boundary(text, it) }
    }
    fun position(text: String, offset: Int): Position? {
        if (!validText(text) || !boundary(text, offset)) return null
        var row = 0; var start = 0; var at = 0
        while (at < offset) {
            when (text[at++]) { '\r' -> { if (text.getOrNull(at) == '\n') at++; row++; start = at }; '\n' -> { row++; start = at } }
        }
        return Position(row, offset - start)
    }
    fun cursor(text: String, line: Int, character: Int): Int? = if (hasBareCr(text)) null else offset(text, line, character)
}
