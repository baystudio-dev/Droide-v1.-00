package com.baystudio.droide.ui

import org.junit.Assert.*
import org.junit.Test

class AgentMarkdownDonorBaselineTest {
    private fun cost(block: AgentMarkdownBlock): Int = when (block) {
        is AgentMarkdownBlock.ListBlock -> block.items.size
        is AgentMarkdownBlock.Table -> block.headers.size + block.rows.sumOf { it.size }
        else -> 1
    }

    @Test fun listRowsShareOneBoundedRenderBudget() {
        val source = "- x\n".repeat(514) + "- tail"
        val blocks = AgentMarkdownParser.parse(source)
        assertTrue("Rows must consume the aggregate render budget", blocks.sumOf(::cost) <= 513)
        assertTrue((blocks.last() as AgentMarkdownBlock.Paragraph).text.endsWith("- tail"))
    }

    @Test fun streamingUpdatesShareTheSameRenderBudget() {
        val projector = AgentStreamingMarkdownProjector()
        val first = "- a\n".repeat(300) + "\n"
        val second = first + "- b\n".repeat(300) + "\n"
        assertEquals(300, projector.project(first, false).stableBlocks.sumOf(::cost))
        val live = projector.project(second, false)
        assertTrue("Updates must not each receive a new node allowance", live.stableBlocks.sumOf(::cost) <= 513)
        assertTrue(projector.project(second, true).stableBlocks.sumOf(::cost) <= 513)
    }

    @Test fun formattedSelectionPreservesTableHeaderAndCellTails() {
        val header = "H".repeat(2000) + "HEADER_TAIL"
        val cell = "C".repeat(2000) + "CELL_TAIL_😄"
        val selected = AgentMessageSelection.fromMarkdown("| $header | Other |\n| --- | --- |\n| $cell | value |")
        assertTrue("Header tail must remain selectable", selected.text.contains(header))
        assertTrue("Cell tail must remain selectable", selected.text.contains(cell))
    }

    @Test fun oversizedSelectionReusesCompleteSource() {
        val source = "# heading\r\n" + "x".repeat(64_001) + "😄TAIL"
        val selected = AgentMessageSelection.fromMarkdown(source)
        assertSame("Oversized selection must not parse or copy the full source", source, selected.text)
        assertEquals(source, selected.pages.indices.joinToString("") { selected.page(it) })
    }

    @Test fun ordinaryFormattedSelectionRemainsReadable() {
        val selected = AgentMessageSelection.fromMarkdown("# **Heading**\n\n[Docs](https://example.test/docs)")
        assertEquals("Heading\n\nDocs (https://example.test/docs)", selected.text)
    }
}
