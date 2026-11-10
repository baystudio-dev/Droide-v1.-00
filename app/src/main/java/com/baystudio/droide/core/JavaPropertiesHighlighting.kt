package com.baystudio.droide.core

/** Lexical colors from the Java Properties.load(Reader) format, not schema validation. Works on
 * UTF-16 offsets in the current buffer; no loading, decoding or rewriting keys. */
internal object JavaPropertiesHighlighting {
    private enum class Phase { KEY, SEPARATOR, VALUE }
    private fun whitespace(c: Char) = c == ' ' || c == '\t' || c == '\u000c'

    fun highlight(text: String, maxSpans: Int): List<DroideHighlightSpan> {
        if (text.length > DroideSyntaxSemanticEngine.MAX_HIGHLIGHT_CHARS || maxSpans <= 0) return emptyList()
        val out = ArrayList<DroideHighlightSpan>(minOf(maxSpans, 128))
        var continued = false
        var phase = Phase.KEY
        for ((lineIndex, line) in text.split('\n').withIndex()) {
            if (out.size >= maxSpans) break
            fun add(start: Int, end: Int, kind: DroideHighlightKind) {
                if (end > start && out.size < maxSpans) out += DroideHighlightSpan(lineIndex, start, end, kind)
            }
            fun naturalLine(start: Int, end: Int) {
                var cursor = start
                while (cursor < end && whitespace(line[cursor])) cursor++
                if (!continued) {
                    phase = Phase.KEY
                    if (cursor < end && line[cursor] in "#!") {
                        add(cursor, end, DroideHighlightKind.COMMENT)
                        return // Comments never continue, even if they end with a backslash.
                    }
                }
                var slashes = 0
                var last = end - 1
                while (last >= start && line[last] == '\\') { slashes++; last-- }
                val continues = slashes % 2 == 1
                // Join semantics precede key/value parsing: the final continuation slash is not a value or key
                // character and cannot consume a separator.
                val contentEnd = if (continues) end - 1 else end
                if (phase == Phase.KEY) {
                    val keyStart = cursor
                    while (cursor < contentEnd) {
                        val c = line[cursor]
                        if (c == '\\') cursor = minOf(contentEnd, cursor + 2)
                        else if (c == '=' || c == ':' || whitespace(c)) break
                        else cursor++
                    }
                    add(keyStart, cursor, DroideHighlightKind.PROPERTY)
                    if (cursor < contentEnd) phase = Phase.SEPARATOR
                }
                if (phase == Phase.SEPARATOR) {
                    while (cursor < contentEnd && whitespace(line[cursor])) cursor++
                    if (cursor < contentEnd && line[cursor] in "=:") {
                        add(cursor, cursor + 1, DroideHighlightKind.OPERATOR)
                        cursor++
                        phase = Phase.VALUE
                    } else if (cursor < contentEnd) {
                        phase = Phase.VALUE
                    }
                    while (cursor < contentEnd && whitespace(line[cursor])) cursor++
                }
                if (phase == Phase.VALUE) add(cursor, contentEnd, DroideHighlightKind.STRING)
                if (continues) add(contentEnd, end, DroideHighlightKind.OPERATOR)
                continued = continues
            }

            var start = 0
            do {
                val cr = line.indexOf('\r', start)
                val end = if (cr < 0) line.length else cr
                naturalLine(start, end)
                if (cr < 0 || out.size >= maxSpans) break
                start = cr + 1
            } while (start < line.length)
        }
        return out
    }
}
