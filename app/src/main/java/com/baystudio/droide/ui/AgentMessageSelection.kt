package com.baystudio.droide.ui

/** One immutable selection snapshot. Large responses reuse their original source; only one page is laid out. */
internal data class AgentMessageSelection(
    val text: String,
    val pages: List<IntRange>,
    val sourceMode: Boolean = false,
) {
    fun page(index: Int): String = text.substring(pages[index].first, pages[index].last + 1)

    companion object {
        const val PAGE_CHARS = 24_000
        const val MAX_FORMATTED_SELECTION_CHARS = 64_000

        fun fromMarkdown(markdown: String, checkCancellation: () -> Unit = {}): AgentMessageSelection {
            checkCancellation()
            // The display parser is not a storage format.

            if (markdown.length > MAX_FORMATTED_SELECTION_CHARS)
                return fromText(markdown, sourceMode = true, checkCancellation = checkCancellation)
            val text = AgentMarkdownParser.parse(markdown).joinToString("\n\n") { block ->
                checkCancellation()
                when (block) {
                    is AgentMarkdownBlock.Paragraph -> plainInline(block.text)
                    is AgentMarkdownBlock.Heading -> plainInline(block.text)
                    is AgentMarkdownBlock.Quote -> plainInline(block.text)
                    is AgentMarkdownBlock.ListBlock -> block.items.joinToString("\n") { item ->
                        val marker = when (item.task) {
                            true -> "☑ "
                            false -> "☐ "
                            null -> if (item.ordered) "${item.ordinal ?: 1}. " else "• "
                        }
                        marker + plainInline(item.text)
                    }
                    is AgentMarkdownBlock.Table -> (listOf(block.headers) + block.rows)
                        .joinToString("\n") { row -> row.joinToString(" | ") { plainInline(it) } }
                    is AgentMarkdownBlock.CodeBlock -> block.code
                    AgentMarkdownBlock.Rule -> "―"
                }
            }
            return fromText(text, checkCancellation = checkCancellation)
        }

        internal fun fromText(
            text: String,
            sourceMode: Boolean = false,
            checkCancellation: () -> Unit = {},
        ): AgentMessageSelection {
            val pages = ArrayList<IntRange>()
            var start = 0
            while (start < text.length) {
                checkCancellation()
                var end = minOf(start + PAGE_CHARS, text.length)
                if (end < text.length) {
                    // Search only this page's latter half. An unbounded lastIndexOf would repeatedly rescan the
                    // whole prefix of a long one-line response.
                    val minimum = start + PAGE_CHARS / 2
                    var newline = end - 1
                    while (newline > minimum && text[newline] != '\n') newline--
                    if (newline > minimum) end = newline + 1
                    if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
                }
                pages += start until end
                start = end
            }
            if (pages.isEmpty()) pages += 0 until 0
            checkCancellation()
            return AgentMessageSelection(text, pages, sourceMode)
        }

        private fun plainInline(text: String, depth: Int = 0): String {
            if (depth >= 8) return text
            return AgentMarkdownParser.parseInline(text).joinToString("") { token ->
                when (token.kind) {
                    AgentMarkdownInlineKind.STRONG, AgentMarkdownInlineKind.EMPHASIS, AgentMarkdownInlineKind.STRIKE ->
                        plainInline(token.text, depth + 1)
                    AgentMarkdownInlineKind.LINK -> {
                        val label = if (token.text == token.url) token.text else plainInline(token.text, depth + 1)
                        if (label == token.url) label else "$label (${token.url})"
                    }
                    else -> token.text
                }
            }
        }
    }
}
