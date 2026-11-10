package com.baystudio.droide.core

data class TextRangeEdit(
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    val newText: String,
)

object TextEditApplier {
    fun apply(text: String, edits: List<TextRangeEdit>): String {
        if (edits.isEmpty()) return text
        data class OffsetEdit(val start: Int, val end: Int, val newText: String, val inputIndex: Int)
        val offsetEdits = edits.mapIndexed { inputIndex, edit ->
            require(validUtf16(edit.newText)) { "Text edit replacement contains an incomplete UTF-16 character" }
            require(edit.startLine >= 1 && edit.startColumn >= 1 && edit.endLine >= 1 && edit.endColumn >= 1) { "Invalid text edit range" }
            val start = offset(text, edit.startLine, edit.startColumn)
            val end = offset(text, edit.endLine, edit.endColumn)
            require(start <= end) { "Text edit range is reversed" }
            OffsetEdit(start, end, edit.newText, inputIndex)

        }.sortedWith(compareByDescending<OffsetEdit> { it.start }.thenByDescending { it.end }
            .thenByDescending { it.inputIndex })

        for (i in 0 until offsetEdits.lastIndex) {
            val later = offsetEdits[i]
            val earlier = offsetEdits[i + 1]
            require(earlier.end <= later.start) { "Overlapping text edits are not safe to apply" }
        }

        val out = StringBuilder(text)
        offsetEdits.forEach { e -> out.replace(e.start, e.end, e.newText) }
        return out.toString()
    }

    private fun offset(text: String, line: Int, column: Int): Int {
        var currentLine = 1
        var lineStart = 0

        var index = 0
        while (currentLine < line && index < text.length) {
            when (text[index++]) {
                '\r' -> {
                    if (index < text.length && text[index] == '\n') index++
                    currentLine++
                    lineStart = index
                }
                '\n' -> {
                    currentLine++
                    lineStart = index
                }
            }
        }
        require(currentLine == line) { "Text edit line is outside the document" }
        var lineEnd = lineStart
        while (lineEnd < text.length && text[lineEnd] != '\r' && text[lineEnd] != '\n') lineEnd++
        val result = lineStart + (column - 1)
        require(result in lineStart..lineEnd) { "Text edit column is outside the document" }
        require(result == 0 || result == text.length || !text[result - 1].isHighSurrogate() || !text[result].isLowSurrogate()) {
            "Text edit position splits a UTF-16 character"
        }
        return result
    }

    private fun validUtf16(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            if (char.isHighSurrogate()) {
                if (index >= text.length || !text[index++].isLowSurrogate()) return false
            } else if (char.isLowSurrogate()) return false
        }
        return true
    }
}
