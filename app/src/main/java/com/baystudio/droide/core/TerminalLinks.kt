package com.baystudio.droide.core

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth
import java.net.URI
import java.net.URISyntaxException

/** HTTP links in terminal output. Detection never normalizes or decodes authentication URLs. */
object TerminalLinks {
    private const val MAX_CHARS = 16 * 1024
    private const val MAX_ROWS = 256

    /** Inspect only the whitespace-delimited token at [offset], not the whole transcript. */
    fun findAt(text: String, offset: Int): String? {
        if (offset !in text.indices || isDelimiter(text[offset])) return null
        var start = offset
        var end = offset + 1
        while (start > 0 && !isDelimiter(text[start - 1])) {
            if (end - start >= MAX_CHARS) return null
            start--
        }
        while (end < text.length && !isDelimiter(text[end])) {
            if (end - start >= MAX_CHARS) return null
            end++
        }

        // Prefixes such as "URL:" or an opening bracket are not part of the destination.
        var scheme = start
        while (scheme <= offset) {
            val https = text.regionMatches(scheme, "https://", 0, 8, ignoreCase = true)
            val http = text.regionMatches(scheme, "http://", 0, 7, ignoreCase = true)
            if (https || http) {
                val candidateEnd = trimProseSuffix(text, scheme, end)
                if (offset !in scheme until candidateEnd) return null
                val candidate = text.substring(scheme, candidateEnd)
                return if (isHttpUrl(candidate)) candidate else null
            }
            scheme++
        }
        return null
    }

    /**
     * [row] uses the emulator's coordinates (negative rows are scrollback). The caller must own
     * the emulator thread or its lock. Full-width underlined CLI continuations also join.
     * Termux does not expose the wrap bit of an evicted row: the oldest retained logical group
     * can already have lost its prefix. Detection uses retained text only and cannot reconstruct
     * or authenticate that missing history (including a URL embedded in a discarded query).
     */
    fun atCell(emulator: TerminalEmulator, column: Int, row: Int, explicitSelection: Boolean = false): String? {
        if (!explicitSelection && emulator.isMouseTrackingActive) return null
        val screen = emulator.screen
        val columns = emulator.mColumns
        val firstAvailable = -screen.activeTranscriptRows
        val lastAvailable = emulator.mRows - 1
        if (columns > MAX_CHARS || column !in 0 until columns || row !in firstAvailable..lastAvailable) {
            return null
        }
        emulator.getHyperlinkAt(column, row)?.let { return it.takeIf(::isHttpUrl) }
        fun continuesAfter(current: Int): Boolean {
            if (screen.getLineWrap(current)) return true
            if (current == lastAvailable) return false
            val style = screen.getStyleAt(current, columns - 1)
            if (TextStyle.decodeEffect(style) and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE == 0 ||
                style != screen.getStyleAt(current + 1, 0)) return false
            val line = screen.getSelectedText(0, current, columns - 1, current)
            val next = screen.getSelectedText(0, current + 1, columns - 1, current + 1)
            if (next.isEmpty() || isDelimiter(next[0]) || next.startsWith("http://", true) ||
                next.startsWith("https://", true)) return false
            val edge = offsetAtColumn(line, columns - 1) ?: return false
            return !isDelimiter(line[edge])
        }
        var first = row
        var last = row
        while (first > firstAvailable && continuesAfter(first - 1)) {
            if (last - first + 1 >= MAX_ROWS) return null
            first--
        }
        while (continuesAfter(last)) {
            // Returning an incomplete URL would silently corrupt an authentication request.
            if (last == lastAvailable || last - first + 1 >= MAX_ROWS) return null
            last++
        }

        val logicalLine = StringBuilder()
        var tappedOffset = -1
        for (current in first..last) {
            val line = screen.getSelectedText(0, current, columns - 1, current, true, false)
            if (line.length > MAX_CHARS - logicalLine.length) return null
            if (current == row) {
                val inRow = offsetAtColumn(line, column) ?: return null
                tappedOffset = logicalLine.length + inRow
            }
            logicalLine.append(line)
        }
        return findAt(logicalLine.toString(), tappedOffset)
    }

    private fun offsetAtColumn(text: String, column: Int): Int? {
        var index = 0
        var cell = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val width = WcWidth.width(codePoint).coerceAtLeast(0)
            if (width > 0 && column in cell until cell + width) return index
            cell += width
            index += Character.charCount(codePoint)
        }
        return null // Trailing screen padding is not a link.
    }

    private fun isDelimiter(char: Char): Boolean =
        char.isWhitespace() || char.isISOControl() || char == '<' || char == '>' || char == '"' || char == '`'

    private fun trimProseSuffix(text: String, start: Int, end: Int): Int {
        var result = end
        val hasQueryOrFragment = (start until end).any { text[it] == '?' || text[it] == '#' }
        var wrapper = start
        while (wrapper > 0 && closingWrapper(text[wrapper - 1]) != null) wrapper--
        val counts = IntArray(6)
        for (i in start until end) {
            when (text[i]) {
                '(' -> counts[0]++
                ')' -> counts[1]++
                '[' -> counts[2]++
                ']' -> counts[3]++
                '{' -> counts[4]++
                '}' -> counts[5]++
            }
        }
        while (result > start) {
            val char = text[result - 1]
            val matchesWrapper = wrapper < start && closingWrapper(text[wrapper]) == char
            val remove = when (char) {
                ')' -> counts[1] > counts[0] && (!hasQueryOrFragment || matchesWrapper)
                ']' -> counts[3] > counts[2] && (!hasQueryOrFragment || matchesWrapper)
                '}' -> counts[5] > counts[4] && (!hasQueryOrFragment || matchesWrapper)
                // A quoted URL is common in CLI help. Interior apostrophes remain unchanged.
                '\'' -> matchesWrapper
                '.', ',', ';', '!', ':' -> !hasQueryOrFragment
                else -> false
            }
            if (!remove) break
            if (matchesWrapper) wrapper++
            when (char) {
                ')' -> counts[1]--
                ']' -> counts[3]--
                '}' -> counts[5]--
            }
            result--
        }
        return result
    }

    private fun closingWrapper(char: Char): Char? = when (char) {
        '(' -> ')'
        '[' -> ']'
        '{' -> '}'
        '\'' -> '\''
        else -> null
    }

    fun isHttpUrl(value: String): Boolean = try {
        val uri = URI(value)
        value.length <= MAX_CHARS &&
            (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) &&
            !uri.host.isNullOrEmpty() && (uri.port == -1 || uri.port in 0..65535)
    } catch (_: URISyntaxException) {
        false
    }
}
