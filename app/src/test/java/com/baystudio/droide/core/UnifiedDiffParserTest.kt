package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedDiffParserTest {
    @Test fun invalidNumericHunksRemainVisibleAndLaterValidHunksStillParse() {
        val invalid = "@@ -99999999999999999999 +1 @@"
        val parsed = UnifiedDiffParser.parse("--- a/Main.kt\n+++ b/Main.kt\n$invalid\n-old\n+new\n@@ -2 +2 @@\n-after\n+fixed").single()
        assertTrue(parsed.headers.contains(invalid))
        assertEquals(1, parsed.hunks.size)
        assertEquals(2, parsed.hunks.single().newStart)
        assertEquals("fixed", parsed.hunks.single().lines.last().text)
    }

    @Test fun lineNumbersNeverWrapToNegativeWhenToolOutputOverflowsRange() {
        val lines = UnifiedDiffParser.parse("--- a/Main.kt\n+++ b/Main.kt\n@@ -2147483647,2 +2147483647,2 @@\n first\n second").single().hunks.single().lines
        assertEquals(Int.MAX_VALUE, lines.first().oldLine)
        assertEquals(Int.MAX_VALUE, lines.first().newLine)
        assertNull(lines.last().oldLine)
        assertNull(lines.last().newLine)
        assertEquals("second", lines.last().text)
    }
}
