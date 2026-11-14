package com.baystudio.droide.core

import kotlinx.serialization.json.*

/** Native multiline UTF-16 token spans adapted to the existing one-line painter.
 * No lexical inference, fixed legend indices, or partial recovery of invalid data.
 */
internal object LspPowerShellSemanticTokens {
    fun parseFull(result: JsonElement, legend: LspSemanticTokenLegend, text: String): List<LspSemanticToken> =
        parseFullChecked(result, legend, text).orEmpty()

    /** Null marks malformed native data; an empty list is a valid empty response. */
    fun parseFullChecked(result: JsonElement, legend: LspSemanticTokenLegend, text: String): List<LspSemanticToken>? {
        if (result == JsonNull) return emptyList()
        if (!LspPowerShellCompletionContext.acceptsText(text) || legend.tokenTypes.isEmpty() ||
            legend.tokenTypes.size > 128 || legend.tokenModifiers.size > 128 ||
            (legend.tokenTypes + legend.tokenModifiers).any { it.isBlank() || it.length > 64 }) return null
        val data = (result as? JsonObject)?.get("data") as? JsonArray ?: return null
        if (data.size % 5 != 0 || data.size / 5 > LspSemanticTokenDecoder.MAX_TOKENS) return null
        val starts = IntArray(1 + text.count { it == '\n' })
        var nextLine = 1
        text.forEachIndexed { index, c -> if (c == '\n') starts[nextLine++] = index + 1 }
        fun lineEnd(line: Int): Int {
            var end = if (line + 1 < starts.size) starts[line + 1] - 1 else text.length
            if (end > starts[line] && text[end - 1] == '\r') end--
            return end
        }
        fun number(index: Int): Long? = (data[index] as? JsonPrimitive)
            ?.takeUnless { it.isString }?.longOrNull
        val out = ArrayList<LspSemanticToken>(minOf(data.size / 5, LspSemanticTokenDecoder.MAX_TOKENS))
        var line = 0
        var column = 0
        var previousEnd = 0
        var index = 0
        while (index < data.size) {
            val deltaLine = number(index) ?: return null
            val deltaColumn = number(index + 1) ?: return null
            val length = number(index + 2) ?: return null
            val typeIndex = number(index + 3) ?: return null
            val bits = number(index + 4) ?: return null
            if (deltaLine !in 0..Int.MAX_VALUE.toLong() || deltaColumn !in 0..Int.MAX_VALUE.toLong() ||
                length !in 1..LspSemanticTokenDecoder.MAX_TOKEN_LENGTH.toLong() ||
                typeIndex !in 0 until legend.tokenTypes.size.toLong() || bits !in 0..Int.MAX_VALUE.toLong() ||
                (bits ushr minOf(legend.tokenModifiers.size, 31)) != 0L) return null
            val newLine = line.toLong() + deltaLine
            val newColumn = if (deltaLine == 0L) column.toLong() + deltaColumn else deltaColumn
            if (newLine >= starts.size || newColumn > Int.MAX_VALUE) return null
            line = newLine.toInt()
            column = newColumn.toInt()
            if (column > lineEnd(line) - starts[line]) return null
            val start = starts[line] + column
            val endLong = start.toLong() + length
            if (endLong > text.length || start < previousEnd) return null
            val end = endLong.toInt()
            if (!LspPowerShellCompletionContext.isBoundary(text, start) ||
                !LspPowerShellCompletionContext.isBoundary(text, end)) return null
            val modifiers = legend.tokenModifiers.mapIndexedNotNull { bit, name ->
                name.takeIf { bit < 31 && (bits and (1L shl bit)) != 0L }
            }.toSet()
            val before = out.size
            var currentLine = line
            while (currentLine < starts.size && starts[currentLine] < end) {
                val segmentStart = maxOf(start, starts[currentLine])
                val segmentEnd = minOf(end, lineEnd(currentLine))
                if (segmentEnd > segmentStart) {
                    if (out.size == LspSemanticTokenDecoder.MAX_TOKENS) return null
                    out += LspSemanticToken(
                        currentLine, segmentStart - starts[currentLine], segmentEnd - segmentStart,
                        legend.tokenTypes[typeIndex.toInt()], modifiers,
                    )
                }
                currentLine++
            }
            if (out.size == before) return null // A newline-only token is not a paintable native span.
            previousEnd = end
            index += 5
        }
        return out
    }
}
