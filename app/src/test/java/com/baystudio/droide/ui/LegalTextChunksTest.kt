package com.baystudio.droide.ui

import org.junit.Assert.*
import org.junit.Test

class LegalTextChunksTest {
    @Test fun largeLicenseRemainsCompleteWithBoundedLayouts() {
        val text = "Copyright notice\n" + "License paragraph.\n\n".repeat(60_000)
        val chunks = legalTextChunks(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length in 1..4096 })
    }

    @Test fun longLinesNeverSplitUnicodePairsOrLoseWhitespace() {
        val text = "ab🧪   ".repeat(9_000) + "\n\nEnd\n"
        val chunks = legalTextChunks(text, 17)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length in 1..17 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
        assertEquals(emptyList<String>(), legalTextChunks(""))
    }
}
