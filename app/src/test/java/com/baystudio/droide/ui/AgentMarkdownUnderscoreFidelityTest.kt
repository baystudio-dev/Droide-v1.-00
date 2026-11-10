package com.baystudio.droide.ui

import org.junit.Assert.*
import org.junit.Test

/** Literal source must survive formatted selection; no timing, UI or external fixtures. */
class AgentMarkdownUnderscoreFidelityTest {
    @Test fun onlyCommonMarkWhitespaceCanEnableUnderscoreDelimiters() {
        // Java's broader whitespace set must not strip literal underscores at these boundaries.
        for (boundary in listOf('\u2028', '\u2029', '\u000B', '\u001C', '\u001D', '\u001E', '\u001F')) {
            val source = "a${boundary}_b_"
            val tokens = AgentMarkdownParser.parseInline(source)
            assertEquals(source, tokens.joinToString("") { it.text })
            assertTrue(tokens.all { it.kind == AgentMarkdownInlineKind.TEXT })
            assertEquals(source, AgentMessageSelection.fromMarkdown(source).text)
        }
        for (boundary in listOf('\t', '\n', '\u000C', '\r', '\u00A0')) {
            assertEquals("a${boundary}b", AgentMarkdownParser.parseInline("a${boundary}_b_").joinToString("") { it.text })
        }
        assertEquals("b", AgentMarkdownParser.parseInline("_b_").single().text)
        assertEquals("a\u00A0b", AgentMessageSelection.fromMarkdown("a\u00A0_b_").text)
    }

    @Test fun intrawordUnderscoresRemainLiteralIncludingUnicodeAndSupplementarySuffixes() {
        for (source in listOf("CELL_TAIL_😄", "foo_bar_baz", "foo__bar__baz", "пристаням_стремятся_вдаль")) {
            val tokens = AgentMarkdownParser.parseInline(source)
            assertEquals(source, tokens.joinToString("") { it.text })
            assertTrue(tokens.all { it.kind == AgentMarkdownInlineKind.TEXT })
            assertEquals(source, AgentMessageSelection.fromMarkdown(source).text)
        }
    }

    @Test fun validUnderscoreFormattingSkipsIntrawordClosingCandidates() {
        val emphasis = AgentMarkdownParser.parseInline("_foo_bar_baz_").single()
        assertEquals(AgentMarkdownInlineKind.EMPHASIS, emphasis.kind)
        assertEquals("foo_bar_baz", emphasis.text)
        val strong = AgentMarkdownParser.parseInline("__foo__bar__").single()
        assertEquals(AgentMarkdownInlineKind.STRONG, strong.kind)
        assertEquals("foo__bar", strong.text)
        assertEquals("foo_bar_baz\n\nfoo__bar", AgentMessageSelection.fromMarkdown("_foo_bar_baz_\n\n__foo__bar__").text)
    }

    @Test fun ordinaryFormattingAndPunctuationDelimitedUnderscoresStillWork() {
        val source = "_em_ __strong__ *star* **bold** foo-_(bar)_. 😄_emoji_😄"
        assertEquals("em strong star bold foo-(bar). 😄emoji😄", AgentMessageSelection.fromMarkdown(source).text)
        assertEquals("_foo_bar", AgentMessageSelection.fromMarkdown("_foo_bar").text)
        assertEquals("a _ b _ c", AgentMessageSelection.fromMarkdown("a _ b _ c").text)
    }

    @Test fun rawTableCellAndFormattedSelectionPreserveTheSameLiteralTail() {
        val literal = "CELL_TAIL_😄"
        val source = "| Header | Other |\n| --- | --- |\n| $literal | value |"
        val table = AgentMarkdownParser.parse(source).single() as AgentMarkdownBlock.Table
        assertEquals(literal, table.rows.single().first())
        val selection = AgentMessageSelection.fromMarkdown(source)
        assertFalse(selection.sourceMode)
        assertEquals("Header | Other\n$literal | value", selection.text)
        assertEquals(selection.text, selection.pages.indices.joinToString("") { selection.page(it) })
    }
}
