package com.baystudio.droide.core

import com.termux.terminal.TerminalRow
import org.junit.Assert.*
import org.junit.Test

/** Requires reviewed source classes: the explicit TEST-ONLY host JAR or a rebuilt, provenance-checked AAR. */
class TerminalRowBoundsTest {
    private fun text(row: TerminalRow) = String(row.mText, 0, row.spaceUsed)

    @Test fun repeatedCombiningOutputCannotOverflowTheRowOrGrowWithoutLimit() {
        val row = TerminalRow(80, 0L)
        row.setChar(0, 'a'.code, 0L)
        repeat(40_000) { row.setChar(0, 0x0301, 0L) }
        assertEquals(80 + 63, row.spaceUsed)
        assertTrue(row.mText.size <= 240)
        assertTrue(text(row).startsWith("a" + "\u0301".repeat(63)))
        row.setChar(1, 'b'.code, 0L)
        assertEquals('b', row.mText[row.findStartOfColumn(1)])
    }

    @Test fun wideRowLengthDoesNotNarrowToASignedShort() {
        val row = TerminalRow(40_000, 0L)
        assertEquals(40_000, row.spaceUsed)
        row.setChar(39_999, 'a'.code, 0L)
        row.setChar(39_999, 0x0301, 0L)
        assertEquals(40_001, row.spaceUsed)
        assertTrue(text(row).endsWith("a\u0301"))
        row.clear(0L)
        assertEquals(40_000, row.spaceUsed)
    }

    @Test fun ordinaryCombiningTextRemainsExact() {
        val row = TerminalRow(12, 0L)
        row.setChar(0, 'e'.code, 0L)
        row.setChar(0, 0x0301, 0L)
        row.setChar(0, 0x0323, 0L)
        row.setChar(1, 'x'.code, 0L)
        assertEquals("e\u0301\u0323x" + " ".repeat(10), text(row))
    }

    @Test fun wideBaseCharacterKeepsItsSurrogatePairAndNextCell() {
        val row = TerminalRow(12, 0L)
        row.setChar(0, 0x1F600, 0L)
        repeat(100) { row.setChar(0, 0x0301, 0L) }
        assertTrue(text(row).startsWith("😀" + "\u0301".repeat(62)))
        assertEquals(64, row.findStartOfColumn(2))
        row.setChar(2, 'x'.code, 0L)
        assertEquals('x', row.mText[row.findStartOfColumn(2)])
    }

    @Test fun rejectedSupplementaryCombiningMarkNeverLeavesHalfASurrogatePair() {
        val row = TerminalRow(12, 0L)
        row.setChar(0, 'a'.code, 0L)
        repeat(100) { row.setChar(0, 0xE0100, 0L) }
        val expected = "a" + String(Character.toChars(0xE0100)).repeat(31)
        assertEquals(expected + " ".repeat(11), text(row))
        assertEquals(63, row.findStartOfColumn(1))
        row.setChar(0, 0x0301, 0L)
        assertEquals(64, row.findStartOfColumn(1))
    }

    @Test fun overwritingAndClearingSaturatedCellsRestoresOrdinaryEditing() {
        val row = TerminalRow(12, 0L)
        row.setChar(0, 'a'.code, 0L)
        repeat(100) { row.setChar(0, 0x0301, 0L) }
        row.setChar(0, 'z'.code, 0L)
        assertEquals("z" + " ".repeat(11), text(row))
        row.clear(0L)
        row.setChar(0, 'e'.code, 0L)
        row.setChar(0, 0x0301, 0L)
        assertEquals("e\u0301" + " ".repeat(11), text(row))
    }
    @Test fun wideOverwriteRemovesTheWholeSaturatedAdjacentCell() {
        val row = TerminalRow(12, 0L)
        row.setChar(1, 'x'.code, 0L)
        repeat(63) { row.setChar(1, 0x0301, 0L) }
        row.setChar(2, 'z'.code, 0L)
        row.setChar(0, 0x1F600, 0L)
        assertEquals("😀z" + " ".repeat(9), text(row))
        assertEquals(2, row.findStartOfColumn(2))
        assertEquals('z', row.mText[row.findStartOfColumn(2)])
    }

    @Test fun clearingReleasesEnlargedCharacterStorage() {
        val row = TerminalRow(12, 0L)
        repeat(63) { row.setChar(0, 0x0301, 0L) }
        assertTrue(row.mText.size > 18)
        row.clear(0L)
        assertEquals(18, row.mText.size)
        assertEquals(12, row.spaceUsed)
    }

}
