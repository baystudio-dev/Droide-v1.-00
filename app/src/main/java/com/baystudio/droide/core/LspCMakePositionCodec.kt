package com.baystudio.droide.core

import kotlinx.serialization.json.*

/** Exact captured text, UTF-16 editor coordinates and Unicode-scalar wire coordinates. */
internal object LspCMakePositionCodec {
    const val POLICY_ID = "cmake-utf32-scalar-v1"
    const val MAX_TEXT_CHARS = 2_000_000
    data class Position(val line: Int, val character: Int)

    class Snapshot internal constructor(val text: String, private val starts: IntArray, private val ends: IntArray) {
        val lineCount: Int get() = starts.size
        fun lineStart(line1: Int): Int? = starts.getOrNull(line1 - 1)
        fun lineEnd(line1: Int): Int? = ends.getOrNull(line1 - 1)
        fun offset(line1: Int, column1: Int): Int? {
            val start = lineStart(line1) ?: return null
            val end = lineEnd(line1) ?: return null
            if (column1 < 1 || column1 - 1 > end - start) return null
            return (start + column1 - 1).takeIf { boundary(text, it) }
        }
        fun toWire(line1: Int, column1: Int): Position? {
            val offset = offset(line1, column1) ?: return null
            return Position(line1 - 1, text.codePointCount(starts[line1 - 1], offset))
        }
        fun toEditor(line0: Int, scalarColumn: Int): LspCompletionCaret? {
            if (line0 !in starts.indices || scalarColumn < 0) return null
            var index = starts[line0]
            val end = ends[line0]
            var remaining = scalarColumn
            while (remaining > 0 && index < end) {
                index += Character.charCount(text.codePointAt(index))
                remaining--
            }
            if (remaining != 0) return null
            return LspCompletionCaret(line0 + 1, index - starts[line0] + 1)
        }
        fun positionParams(uri: String, line1: Int, column1: Int): JsonObject? {
            val position = toWire(line1, column1) ?: return null
            return buildJsonObject {
                put("textDocument", buildJsonObject { put("uri", uri) })
                put("position", buildJsonObject { put("line", position.line); put("character", position.character) })
            }
        }
        fun hoverRange(value: JsonElement?): TextRangeEdit? {
            val range = value as? JsonObject ?: return null
            fun point(key: String): LspCompletionCaret? {
                val p = range[key] as? JsonObject ?: return null
                fun number(k: String): Int? = (p[k] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                return toEditor(number("line") ?: return null, number("character") ?: return null)
            }
            val start = point("start") ?: return null
            val end = point("end") ?: return null
            if (end.line != start.line || end.column <= start.column) return null
            return TextRangeEdit(start.line, start.column, end.line, end.column, "")
        }
        /** No incremental ranges: pinned pygls 1.3.1's UTF-32 incremental path is unsuitable. */
        fun fullChanges(): JsonArray = buildJsonArray { add(buildJsonObject { put("text", text) }) }
    }

    fun capture(text: String): Snapshot? {
        if (!acceptsText(text)) return null
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        starts.add(0)
        text.forEachIndexed { index, c ->
            if (c == '\n') { ends.add(if (index > 0 && text[index - 1] == '\r') index - 1 else index); starts.add(index + 1) }
        }
        ends.add(text.length)
        return Snapshot(text, starts.toIntArray(), ends.toIntArray())
    }
    fun acceptsText(text: String): Boolean {
        if (text.length > MAX_TEXT_CHARS) return false
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            when {
                c == '\u0000' -> return false
                c == '\r' && text.getOrNull(i) != '\n' -> return false
                c.isHighSurrogate() -> if (text.getOrNull(i++)?.isLowSurrogate() != true) return false
                c.isLowSurrogate() -> return false
            }
        }
        return true
    }
    private fun boundary(text: String, offset: Int): Boolean = offset in 0..text.length &&
        (offset == 0 || offset == text.length || !(text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate()))
}
