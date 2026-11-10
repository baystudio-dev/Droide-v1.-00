package com.baystudio.droide.ui

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class AgentResponseSelectionSafetyTest {
    @Test fun sourceModeBeginsOnlyAboveTheFormattedThreshold() {
        val atLimit = "x".repeat(AgentMessageSelection.MAX_FORMATTED_SELECTION_CHARS)
        assertFalse(AgentMessageSelection.fromMarkdown(atLimit).sourceMode)
        val source = atLimit + "TAIL"
        val selection = AgentMessageSelection.fromMarkdown(source)
        assertTrue(selection.sourceMode)
        assertSame(source, selection.text)
        assertEquals(source, selection.pages.indices.joinToString("") { selection.page(it) })
    }

    @Test fun sourcePageBoundariesRemainContiguousAndUnicodeSafe() {
        val source = "x".repeat(AgentMessageSelection.PAGE_CHARS - 1) + "😄" + "z".repeat(40_001) + "😄"
        val selection = AgentMessageSelection.fromMarkdown(source)
        assertTrue(selection.sourceMode)
        var consumed = 0
        selection.pages.forEachIndexed { index, range ->
            assertEquals(consumed, range.first)
            val page = selection.page(index)
            assertTrue(page.length <= AgentMessageSelection.PAGE_CHARS)
            assertFalse(page.first().isLowSurrogate())
            assertFalse(page.last().isHighSurrogate())
            consumed += page.length
        }
        assertEquals(source.length, consumed)
        assertEquals(source, selection.pages.indices.joinToString("") { selection.page(it) })
    }

    @Test fun pageLocalNewlinePreferenceNeverRescansOrSplitsThePrefix() {
        val source = "a".repeat(13_000) + "\n" + "b".repeat(40_000)
        val selection = AgentMessageSelection.fromText(source)
        assertEquals(13_001, selection.page(0).length)
        assertEquals(AgentMessageSelection.PAGE_CHARS, selection.page(1).length)
        assertEquals(source, selection.pages.indices.joinToString("") { selection.page(it) })
    }

    @Test fun cancellationStopsPaginationBeforePublication() {
        val source = "x".repeat(AgentMessageSelection.MAX_FORMATTED_SELECTION_CHARS + 1)
        var checks = 0
        val failure = assertThrows(CancellationException::class.java) {
            AgentMessageSelection.fromMarkdown(source) {
                if (++checks == 3) throw CancellationException("selection closed")
            }
        }
        assertEquals("selection closed", failure.message)
        assertEquals(3, checks)
    }

    @Test fun cancellationAlsoGuardsTheEmptyDocumentAndFinalResult() {
        var checks = 0
        assertThrows(CancellationException::class.java) {
            AgentMessageSelection.fromMarkdown("") {
                if (++checks == 2) throw CancellationException("closed")
            }
        }
        assertEquals(2, checks)
        assertEquals("", AgentMessageSelection.fromText("").page(0))
    }
}
