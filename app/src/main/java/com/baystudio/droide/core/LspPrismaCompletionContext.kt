package com.baystudio.droide.core

/** Finite, client-authored context for the built-in legacy Prisma route only.
 * Positions and replacement ranges are one-based UTF-16, like the editor LSP API.
 * This is not a schema parser. Unknown insertion/token forms need a native textEdit.
 */
internal object LspPrismaCompletionContext {
    data class Context(
        val prefix: String,
        val triggerCharacter: String?,
        val replacementRange: TextRangeEdit?,
        val requestCaret: LspCompletionCaret,
        private val attributeSigns: Int,
        private val dottedLeaf: Boolean,
    ) {
        /** Preserve native explicit edits, text and filterText; never repair a malformed edit. */
        fun withInferredRange(item: LspCompletionItem): LspCompletionItem? {
            val coordinates = listOf(item.editStartLine, item.editStartColumn, item.editEndLine, item.editEndColumn)
            if (coordinates.any { it != null }) return item.takeIf { coordinates.all { it != null } }
            val range = replacementRange ?: return null
            if (!knownInsertion(item)) return null
            val primary = range.copy(newText = item.insertText)
            if (item.additionalTextEdits.any { overlaps(primary, it) }) return null
            return item.copy(
                editStartLine = range.startLine, editStartColumn = range.startColumn,
                editEndLine = range.endLine, editEndColumn = range.endColumn,
            )
        }

        private fun knownInsertion(item: LspCompletionItem): Boolean {
            val insertion = item.insertText
            if (insertion.isEmpty()) return false
            var start = 0
            while (start < insertion.length && insertion[start] == '@') start++
            if (attributeSigns > 0) {
                if (start !in 1..2) return false
                // A block-attribute prefix must not silently become a field attribute.
                if (attributeSigns == 2 && start != 2) return false
            } else if (start > 0) return false
            var end = start
            while (end < insertion.length && identifier(insertion[end])) end++
            if (end == start) return false
            if (end == insertion.length) return true
            return when (insertion[end]) {
                '(' -> true // Native leaf/attribute call; argument text stays unchanged.
                '$' -> item.isSnippet // Native datasource name or following tab stop.
                ':' -> !dottedLeaf && attributeSigns == 0 // Named argument.
                ' ', '\t', '\n', '\r' -> !dottedLeaf && attributeSigns == 0 && item.isSnippet
                else -> false // In particular, never infer a full qualified insertion.
            }
        }
    }

    fun capture(fullText: String, line1: Int, col1: Int): Context? {
        if (line1 < 1 || col1 < 1) return null
        var start = 0
        repeat(line1 - 1) {
            val next = fullText.indexOf('\n', start)
            if (next < 0) return null
            start = next + 1
        }
        val newline = fullText.indexOf('\n', start)
        var end = if (newline < 0) fullText.length else newline
        if (newline >= 0 && end > start && fullText[end - 1] == '\r') end--
        val local = forLine(fullText.substring(start, end), col1 - 1) ?: return null
        return local.copy(
            replacementRange = local.replacementRange?.copy(startLine = line1, endLine = line1),
            requestCaret = LspCompletionCaret(line1, col1),
        )
    }

    fun forLine(line: String, column0: Int): Context? {
        if (column0 !in 0..line.length || line.any { it == '\n' || it == '\r' }) return null
        if (column0 > 0 && column0 < line.length &&
            line[column0 - 1].isHighSurrogate() && line[column0].isLowSurrogate()) return null
        var start = column0
        while (start > 0 && leaf(line[start - 1])) start--
        var end = column0
        while (end < line.length && leaf(line[end])) end++
        var signsStart = start
        while (signsStart > 0 && line[signsStart - 1] == '@') signsStart--
        val signs = start - signsStart
        if (signs > 0) start = signsStart
        val ambiguous = signs > 2 || line.getOrNull(column0) == '@' ||
            line.getOrNull(start - 1)?.let(::unknownIdentifier) == true ||
            line.getOrNull(end)?.let(::unknownIdentifier) == true
        val range = if (ambiguous) null else TextRangeEdit(1, start + 1, 1, end + 1, "")
        return Context(
            prefix = line.substring(start, column0),
            triggerCharacter = line.getOrNull(column0 - 1)?.takeIf { it == '.' || it == '@' || it == '"' }?.toString(),
            replacementRange = range,
            requestCaret = LspCompletionCaret(1, column0 + 1),
            attributeSigns = signs,
            dottedLeaf = line.getOrNull(start - 1) == '.',
        )
    }

    private fun identifier(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'
    private fun leaf(c: Char): Boolean = identifier(c) || c == '$'
    private fun unknownIdentifier(c: Char): Boolean = c.code > 127 &&
        (c.isLetterOrDigit() || c.isSurrogate() || Character.getType(c) in setOf(
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.CONNECTOR_PUNCTUATION.toInt()))

    private fun overlaps(a: TextRangeEdit, b: TextRangeEdit): Boolean {
        fun compare(al: Int, ac: Int, bl: Int, bc: Int) = if (al == bl) ac.compareTo(bc) else al.compareTo(bl)
        if (a.startLine == b.startLine && a.startColumn == b.startColumn) return true
        return compare(a.startLine, a.startColumn, b.endLine, b.endColumn) < 0 &&
            compare(b.startLine, b.startColumn, a.endLine, a.endColumn) < 0
    }
}
