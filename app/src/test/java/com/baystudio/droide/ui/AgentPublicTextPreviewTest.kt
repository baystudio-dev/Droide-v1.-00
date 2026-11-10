package com.baystudio.droide.ui

import org.junit.Assert.*
import org.junit.Test

class AgentPublicTextPreviewTest {
    @Test fun compactPreviewIsLiteralAndWhitespaceOnlyIsNormalized() {
        assertEquals("**Public** [link](https://example.test) `code`",
            agentPublicUpdatePreview("  **Public**\r\n[link](https://example.test)\t `code`  "))
    }

    @Test fun compactPreviewIsBoundedAndDoesNotSplitSurrogatePairs() {
        val source = "x".repeat(MAX_AGENT_UPDATE_PREVIEW_CHARS - 1) + "😄tail"
        val preview = agentPublicUpdatePreview(source)
        assertEquals("x".repeat(MAX_AGENT_UPDATE_PREVIEW_CHARS - 1) + "…", preview)
        assertFalse(preview.contains('\uD83D'))
        assertTrue(preview.length <= MAX_AGENT_UPDATE_PREVIEW_CHARS + 1)
    }

    @Test fun exactPreviewBoundaryDoesNotClaimShortening() {
        val source = "x".repeat(MAX_AGENT_UPDATE_PREVIEW_CHARS)
        assertEquals(source, agentPublicUpdatePreview(source))
        assertEquals("", agentPublicUpdatePreview(""))
    }

    @Test fun limitedProjectorRetainsLiteralRemainderWithoutExceedingReservedBudget() {
        val source = (1..30).joinToString("\n\n") { "## Heading $it" }
        val budget = 2
        val projector = AgentStreamingMarkdownProjector(budget)
        val live = projector.project(source, false)
        assertTrue(live.stableBlocks.sumOf { it.renderCost() } + (if (live.tail.isNotBlank()) 1 else 0) <= budget + 2)
        val completed = projector.project(source, true)
        assertTrue(completed.stableBlocks.sumOf { it.renderCost() } <= budget + 1)
        assertTrue((completed.stableBlocks.last() as AgentMarkdownBlock.Paragraph).text.contains("Heading 30"))
        assertEquals("", completed.tail)
    }

    @Test fun zeroBudgetStillKeepsTheSourceAndDefaultProjectionIsUnchanged() {
        val source = "# Title\n\nParagraph.\n\n- Item"
        val zero = AgentStreamingMarkdownProjector(0).project(source, true)
        assertEquals(listOf(AgentMarkdownBlock.Paragraph(source)), zero.stableBlocks)
        val default = AgentStreamingMarkdownProjector().project(source, true)
        assertEquals(AgentMarkdownParser.parse(source), default.stableBlocks)
    }
}
