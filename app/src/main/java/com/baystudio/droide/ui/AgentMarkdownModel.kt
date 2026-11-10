package com.baystudio.droide.ui

import com.baystudio.droide.core.TerminalLinks
import java.net.URI
import java.net.URISyntaxException

// Keep untrusted input and output bounded.

internal const val MAX_AGENT_UPDATE_PREVIEW_CHARS = 220

/** Bounded literal preview only: disclosure must not parse or compose hidden Markdown. */
internal fun agentPublicUpdatePreview(text: String): String {
    var end = minOf(text.length, MAX_AGENT_UPDATE_PREVIEW_CHARS)
    if (end > 0 && end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
    val preview = buildString(end) {
        var space = false
        for (index in 0 until end) {
            val character = text[index]
            if (character.isWhitespace()) {
                if (isNotEmpty()) space = true
            } else {
                if (space) append(' ')
                append(character)
                space = false
            }
        }
    }
    return preview + if (end < text.length) "…" else ""
}

internal sealed interface AgentMarkdownBlock {
    data class Paragraph(val text: String) : AgentMarkdownBlock
    data class Heading(val level: Int, val text: String) : AgentMarkdownBlock
    data class Quote(val text: String) : AgentMarkdownBlock
    data class ListBlock(val items: List<AgentMarkdownListItem>) : AgentMarkdownBlock
    data class Table(
        val headers: List<String>,
        val aligns: List<AgentMarkdownTableAlign>,
        val rows: List<List<String>>,
    ) : AgentMarkdownBlock
    data class CodeBlock(val language: String, val code: String) : AgentMarkdownBlock
    data object Rule : AgentMarkdownBlock
}

internal enum class AgentMarkdownTableAlign { START, CENTER, END }

internal fun AgentMarkdownBlock.renderCost(): Int = when (this) {
    is AgentMarkdownBlock.ListBlock -> items.size
    is AgentMarkdownBlock.Table -> headers.size + rows.sumOf { it.size }
    else -> 1
}

internal data class AgentMarkdownPreview(val text: String, val shortened: Boolean)

internal const val MAX_AGENT_MARKDOWN_PREVIEW_CHARS = 64_000
internal const val MAX_AGENT_MARKDOWN_PREVIEW_LINES = 256

/** Rendering only. Copy, selection, history and the provider continue to use the full source. */
internal fun agentMarkdownPreview(markdown: String): AgentMarkdownPreview {
    var end = minOf(markdown.length, MAX_AGENT_MARKDOWN_PREVIEW_CHARS)
    var lines = 1
    for (index in 0 until end) {
        val char = markdown[index]
        if (char == '\r' || char == '\n' && (index == 0 || markdown[index - 1] != '\r')) {
            if (lines >= MAX_AGENT_MARKDOWN_PREVIEW_LINES) { end = index; break }
            lines++
        }
    }
    if (end == markdown.length) return AgentMarkdownPreview(markdown, false)
    if (end > 0 && markdown[end - 1].isHighSurrogate() && markdown[end].isLowSurrogate()) end--
    return AgentMarkdownPreview(markdown.substring(0, end), true)
}

internal data class AgentStreamingMarkdownProjection(
    val stableBlocks: List<AgentMarkdownBlock>,
    val tail: String,
    val completed: Boolean,
)

internal class AgentStreamingMarkdownProjector(maxRenderNodes: Int = AgentMarkdownParser.MAX_RENDER_NODES) {

    private val nodeLimit = maxRenderNodes.coerceIn(0, AgentMarkdownParser.MAX_RENDER_NODES)
    private var source = ""
    private var stableChars = 0
    private val stable = ArrayList<AgentMarkdownBlock>()

    fun project(markdown: String, completed: Boolean): AgentStreamingMarkdownProjection {
        if (!markdown.startsWith(source)) reset()
        source = markdown
        if (completed) {
            val all = AgentMarkdownParser.parse(markdown, nodeLimit)
            stable.clear()
            stable.addAll(all)
            stableChars = markdown.length
            return AgentStreamingMarkdownProjection(stable.toList(), "", true)
        }

        val nextStable = AgentMarkdownParser.stableStreamingPrefixLength(markdown)
        if (nextStable < stableChars) reset()
        val remainingNodes = nodeLimit - stable.sumOf { it.renderCost() }
        if (nextStable > stableChars && remainingNodes > 0) {
            val chunk = markdown.substring(stableChars, nextStable)
            stable += AgentMarkdownParser.parse(chunk, remainingNodes)
            stableChars = nextStable
        }
        return AgentStreamingMarkdownProjection(
            stableBlocks = stable.toList(),
            tail = markdown.substring(stableChars),
            completed = false,
        )
    }

    private fun reset() {
        source = ""
        stableChars = 0
        stable.clear()
    }
}

internal data class AgentMarkdownListItem(
    val ordered: Boolean,
    val ordinal: Int?,
    val text: String,
    val task: Boolean? = null,
)

internal enum class AgentMarkdownInlineKind { TEXT, STRONG, EMPHASIS, STRIKE, CODE, LINK }

internal data class AgentMarkdownInline(
    val text: String,
    val kind: AgentMarkdownInlineKind = AgentMarkdownInlineKind.TEXT,
    val url: String? = null,
)

internal object AgentMarkdownParser {
    const val MAX_BLOCKS = 512
    const val MAX_RENDER_NODES = 512
    const val MAX_INLINE_TOKENS = 768

    private val heading = Regex("""^ {0,3}(#{1,6})[\t ]+(.+?)\s*#*\s*$""")
    private val unordered = Regex("""^\s{0,3}[-+*][\t ]+(.+)$""")
    private val ordered = Regex("""^\s{0,3}(\d{1,9})[.)][\t ]+(.+)$""")
    private val quote = Regex("""^\s{0,3}>[\t ]?(.*)$""")
    private val task = Regex("""^\[([ xX])][\t ]+(.+)$""")
    private val horizontalRule = Regex("""^\s{0,3}(?:(?:\*\s*){3,}|(?:-\s*){3,}|(?:_\s*){3,})$""")

    fun parse(markdown: String, maxRenderNodes: Int = MAX_RENDER_NODES): List<AgentMarkdownBlock> {
        if (markdown.isBlank()) return emptyList()
        val normalized = markdown.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n')
        val out = ArrayList<AgentMarkdownBlock>(minOf(lines.size, 32))
        var index = 0
        var renderedNodes = 0
        fun add(block: AgentMarkdownBlock) {
            out += block
            renderedNodes += block.renderCost()
        }

        while (index < lines.size) {
            if (out.size >= MAX_BLOCKS || renderedNodes >= maxRenderNodes) {
                val remainder = lines.subList(index, lines.size).joinToString("\n")
                if (remainder.isNotBlank()) out += AgentMarkdownBlock.Paragraph(remainder)
                break
            }
            val line = lines[index]
            if (line.isBlank()) {
                index++
                continue
            }

            val fence = parseFence(line)
            if (fence != null) {
                val body = StringBuilder()
                index++
                while (index < lines.size) {
                    val candidate = lines[index]
                    if (isFenceClose(candidate, fence.marker, fence.length)) {
                        index++
                        break
                    }
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(candidate)
                    index++
                }

                add(AgentMarkdownBlock.CodeBlock(fence.language, body.toString()))
                continue
            }

            val headingMatch = heading.matchEntire(line)
            if (headingMatch != null) {
                add(AgentMarkdownBlock.Heading(headingMatch.groupValues[1].length, headingMatch.groupValues[2]))
                index++
                continue
            }
            if (horizontalRule.matches(line)) {
                add(AgentMarkdownBlock.Rule)
                index++
                continue
            }

            val parsedTable = parseTable(lines, index)
            if (parsedTable != null) {
                if (parsedTable.block.renderCost() <= maxRenderNodes - renderedNodes) add(parsedTable.block)
                else add(AgentMarkdownBlock.Paragraph(lines.subList(index, parsedTable.nextIndex).joinToString("\n")))
                index = parsedTable.nextIndex
                continue
            }

            if (quote.matches(line)) {
                val body = StringBuilder()
                while (index < lines.size) {
                    val match = quote.matchEntire(lines[index]) ?: break
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(match.groupValues[1])
                    index++
                }
                add(AgentMarkdownBlock.Quote(body.toString()))
                continue
            }

            val firstListItem = parseListItem(line)
            if (firstListItem != null) {
                val items = ArrayList<AgentMarkdownListItem>()
                var current: AgentMarkdownListItem? = firstListItem
                while (current != null && index < lines.size && items.size < maxRenderNodes - renderedNodes) {
                    items += current
                    index++
                    current = if (index < lines.size) parseListItem(lines[index]) else null
                }
                add(AgentMarkdownBlock.ListBlock(items))
                continue
            }

            val paragraph = StringBuilder(line)
            index++
            while (index < lines.size && lines[index].isNotBlank() && !startsBlock(lines[index])) {
                paragraph.append('\n').append(lines[index])
                index++
            }
            add(AgentMarkdownBlock.Paragraph(paragraph.toString()))
        }
        return out
    }

    fun parseInline(source: String): List<AgentMarkdownInline> {
        if (source.isEmpty()) return emptyList()
        val out = ArrayList<AgentMarkdownInline>()
        val plain = StringBuilder()
        val scan = AgentMarkdownInlineScan(source)

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                out += AgentMarkdownInline(plain.toString())
                plain.setLength(0)
            }
        }
        fun add(text: String, kind: AgentMarkdownInlineKind, url: String? = null) {
            if (text.isEmpty()) return
            flushPlain()
            out += AgentMarkdownInline(text, kind, url)
        }

        fun underscoreClose(length: Int, start: Int): Int {
            var close = scan.findRun('_', length, start)
            while (close >= 0 && !underscoreDelimiterAllowed(source, close, length, opening = false)) {
                close = scan.findRun('_', length, close + length)
            }
            return close
        }

        var i = 0
        while (i < source.length) {
            if (out.size >= MAX_INLINE_TOKENS || scan.exhausted) {
                plain.append(source.substring(i))
                break
            }
            val c = source[i]
            if (c == '\\' && i + 1 < source.length && source[i + 1] in "\\`*_{}[]()#+-.!~>") {
                plain.append(source[i + 1])
                i += 2
                continue
            }

            if (c == '`') {
                val ticks = scan.runLength(i, '`')
                if (scan.exhausted) { plain.append(source, i, source.length); break }
                val close = scan.findRun('`', ticks, i + ticks)
                if (close >= 0) {
                    add(source.substring(i + ticks, close).trimSinglePaddingSpace(), AgentMarkdownInlineKind.CODE)
                    i = close + ticks
                    continue
                }
                // An unmatched delimiter run is literal as a whole. Do not rescan every suffix or allocate a new
                // repeated-delimiter string for each of its characters.
                plain.append(source, i, i + ticks)
                i += ticks
                continue
            }

            val strongMarker = when {
                source.startsWith("**", i) -> "**"
                source.startsWith("__", i) && underscoreDelimiterAllowed(source, i, 2, opening = true) -> "__"
                else -> null
            }
            if (strongMarker != null) {
                val close = if (strongMarker == "__") underscoreClose(2, i + 2)
                    else scan.findRun(strongMarker[0], 2, i + 2)
                if (close > i + 2) {
                    add(source.substring(i + 2, close), AgentMarkdownInlineKind.STRONG)
                    i = close + 2
                    continue
                }
            }

            if (source.startsWith("~~", i)) {
                val close = scan.findRun('~', 2, i + 2)
                if (close > i + 2) {
                    add(source.substring(i + 2, close), AgentMarkdownInlineKind.STRIKE)
                    i = close + 2
                    continue
                }
            }

            if ((c == '*' || c == '_' && underscoreDelimiterAllowed(source, i, 1, opening = true)) &&
                (i == 0 || source[i - 1] != c)) {
                val close = if (c == '_') underscoreClose(1, i + 1) else scan.find(c, i + 1)
                if (close > i + 1) {
                    add(source.substring(i + 1, close), AgentMarkdownInlineKind.EMPHASIS)
                    i = close + 1
                    continue
                }
            }

            if (source.startsWith("![", i)) {
                val labelEnd = scan.find(']', i + 2)
                if (labelEnd > i + 2 && labelEnd + 1 < source.length && source[labelEnd + 1] == '(') {
                    val urlEnd = scan.find(')', labelEnd + 2)
                    if (urlEnd > labelEnd + 2) {
                        val alt = source.substring(i + 2, labelEnd)
                        plain.append("[image: ").append(alt).append(']')
                        i = urlEnd + 1
                        continue
                    }
                }
            }

            if (c == '[') {
                val labelEnd = scan.find(']', i + 1)
                if (labelEnd > i + 1 && labelEnd + 1 < source.length && source[labelEnd + 1] == '(') {
                    val urlEnd = scan.linkTargetEnd(labelEnd + 2)
                    if (urlEnd > labelEnd + 2) {
                        val label = source.substring(i + 1, labelEnd)
                        val url = source.substring(labelEnd + 2, urlEnd).trim().substringBefore(' ').trim('<', '>')
                        if (isSafeHttpUrl(url)) add(label, AgentMarkdownInlineKind.LINK, url)
                        else { flushPlain(); out += AgentMarkdownInline(label) }
                        i = urlEnd + 1
                        continue
                    }
                }
            }

            if (c == '<') {
                val close = scan.find('>', i + 1)
                if (close > i + 1) {
                    val url = source.substring(i + 1, close)
                    if (isSafeHttpUrl(url)) {
                        add(url, AgentMarkdownInlineKind.LINK, url)
                        i = close + 1
                        continue
                    }
                }
            }

            if ((source.regionMatches(i, "https://", 0, 8, ignoreCase = true) ||
                    source.regionMatches(i, "http://", 0, 7, ignoreCase = true)) &&
                (i == 0 || !source[i - 1].isLetterOrDigit())
            ) {
                // Share the terminal's bounded, query-preserving HTTP token detection.
                val url = TerminalLinks.findAt(source, i)
                if (url != null && isSafeHttpUrl(url)) {
                    add(url, AgentMarkdownInlineKind.LINK, url)
                    i += url.length
                    continue
                }
            }

            plain.append(c)
            i++
        }
        flushPlain()
        return out
    }

    private fun underscoreDelimiterAllowed(source: String, index: Int, length: Int, opening: Boolean): Boolean {
        val before = if (index == 0) -1 else Character.codePointBefore(source, index)
        val after = if (index + length == source.length) -1 else Character.codePointAt(source, index + length)
        fun whitespace(codePoint: Int) = codePoint < 0 ||
            Character.getType(codePoint) == Character.SPACE_SEPARATOR.toInt() ||
            codePoint == 0x09 || codePoint == 0x0A || codePoint == 0x0C || codePoint == 0x0D
        fun punctuation(codePoint: Int): Boolean = codePoint >= 0 && when (Character.getType(codePoint)) {
            Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(), Character.MATH_SYMBOL.toInt(),
            Character.CURRENCY_SYMBOL.toInt(), Character.MODIFIER_SYMBOL.toInt(), Character.OTHER_SYMBOL.toInt() -> true
            else -> false
        }
        val beforeSpace = whitespace(before)
        val afterSpace = whitespace(after)
        val beforePunctuation = punctuation(before)
        val afterPunctuation = punctuation(after)
        val leftFlanking = !afterSpace && (!afterPunctuation || beforeSpace || beforePunctuation)
        val rightFlanking = !beforeSpace && (!beforePunctuation || afterSpace || afterPunctuation)
        return if (opening) leftFlanking && (!rightFlanking || beforePunctuation)
            else rightFlanking && (!leftFlanking || afterPunctuation)
    }

    fun stableStreamingPrefixLength(markdown: String): Int {
        if (markdown.isEmpty()) return 0
        var offset = 0
        var stable = 0
        var fence: Fence? = null
        while (offset < markdown.length) {
            val newline = markdown.indexOf('\n', offset)
            if (newline < 0) break
            val line = markdown.substring(offset, newline)
            val next = newline + 1
            val active = fence
            if (active != null) {
                if (isFenceClose(line, active.marker, active.length)) {
                    fence = null
                    stable = next
                }
            } else {
                val opened = parseFence(line)
                if (opened != null) {
                    fence = opened
                } else if (line.isBlank()) {
                    stable = next
                }
            }
            offset = next
        }
        return stable.coerceIn(0, markdown.length)
    }

    fun isSafeHttpUrl(url: String?): Boolean {
        val value = url ?: return false
        if (value.length !in 8..16_384 || value.any { it.isWhitespace() || it.isISOControl() }) return false
        return try {
            val uri = URI(value)
            (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) &&
                !uri.host.isNullOrEmpty() && (uri.port == -1 || uri.port in 0..65535)
        } catch (_: URISyntaxException) {
            false
        }
    }

    private data class Fence(val marker: Char, val length: Int, val language: String)

    private fun parseFence(line: String): Fence? {
        val trimmed = line.dropWhile { it == ' ' }.takeIf { line.length - it.length <= 3 } ?: return null
        if (trimmed.length < 3 || (trimmed[0] != '`' && trimmed[0] != '~')) return null
        val marker = trimmed[0]
        val length = trimmed.countRun(0, marker)
        if (length < 3) return null
        val info = trimmed.substring(length).trim()
        val language = info.substringBefore(' ').substringBefore('{').trim().take(40)
        return Fence(marker, length, language)
    }

    private fun isFenceClose(line: String, marker: Char, minimumLength: Int): Boolean {
        val trimmed = line.trimStart()
        if (line.length - trimmed.length > 3 || trimmed.isEmpty() || trimmed[0] != marker) return false
        val length = trimmed.countRun(0, marker)
        return length >= minimumLength && trimmed.substring(length).isBlank()
    }

    private data class ParsedTable(val block: AgentMarkdownBlock.Table, val nextIndex: Int)

    private fun parseTable(lines: List<String>, start: Int): ParsedTable? {
        if (start + 1 >= lines.size) return null
        val headers = splitTableRow(lines[start]) ?: return null
        if (headers.size !in 2..12) return null
        val separators = splitTableRow(lines[start + 1]) ?: return null
        if (separators.size != headers.size) return null
        val aligns = separators.map { raw ->
            val cell = raw.trim()
            if (!Regex("^:?-{3,}:?$").matches(cell)) return null
            when {
                cell.startsWith(':') && cell.endsWith(':') -> AgentMarkdownTableAlign.CENTER
                cell.endsWith(':') -> AgentMarkdownTableAlign.END
                else -> AgentMarkdownTableAlign.START
            }
        }
        val rows = ArrayList<List<String>>()
        var index = start + 2
        while (index < lines.size && rows.size < 64) {
            val line = lines[index]
            if (line.isBlank()) break
            val cells = splitTableRow(line) ?: break
            if (cells.size < 2) break
            // Rendering already receives a bounded response preview. Keep complete cells here so the
            // selection projection never silently drops a cell's tail.
            rows += List(headers.size) { column -> cells.getOrNull(column).orEmpty() }
            index++
        }
        return ParsedTable(
            AgentMarkdownBlock.Table(
                headers = headers,
                aligns = aligns,
                rows = rows,
            ),
            index,
        )
    }

    private fun splitTableRow(line: String): List<String>? {
        if ('|' !in line) return null
        val cells = ArrayList<String>()
        val cell = StringBuilder()
        var escaped = false
        var ticks = 0
        for (char in line.trim()) {
            if (escaped) {
                cell.append(char)
                escaped = false
                continue
            }
            if (char == '\\') {
                escaped = true
                cell.append(char)
                continue
            }
            if (char == '`') {
                ticks = if (ticks == 0) 1 else 0
                cell.append(char)
                continue
            }
            if (char == '|' && ticks == 0) {
                cells += cell.toString().trim()
                cell.setLength(0)
            } else {
                cell.append(char)
            }
        }
        cells += cell.toString().trim()
        if (cells.firstOrNull().isNullOrEmpty()) cells.removeAt(0)
        if (cells.lastOrNull().isNullOrEmpty() && cells.isNotEmpty()) cells.removeAt(cells.lastIndex)
        return cells.takeIf { it.size >= 2 }
    }

    private fun parseListItem(line: String): AgentMarkdownListItem? {
        unordered.matchEntire(line)?.let { match ->
            val raw = match.groupValues[1]
            task.matchEntire(raw)?.let { taskMatch ->
                return AgentMarkdownListItem(false, null, taskMatch.groupValues[2], taskMatch.groupValues[1].isNotBlank())
            }
            return AgentMarkdownListItem(false, null, raw)
        }
        ordered.matchEntire(line)?.let { match ->
            return AgentMarkdownListItem(true, match.groupValues[1].toIntOrNull(), match.groupValues[2])
        }
        return null
    }

    private fun startsBlock(line: String): Boolean =
        parseFence(line) != null || heading.matches(line) || horizontalRule.matches(line) ||
            quote.matches(line) || parseListItem(line) != null

    private fun String.countRun(start: Int, char: Char): Int {
        var i = start
        while (i < length && this[i] == char) i++
        return i - start
    }

    private fun String.trimSinglePaddingSpace(): String =
        if (length >= 2 && first() == ' ' && last() == ' ' && any { it != ' ' }) substring(1, length - 1) else this

}
