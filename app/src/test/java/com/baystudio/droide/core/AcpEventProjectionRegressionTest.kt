package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Tiny source-only proposal. No production before/after claim. */
class AcpEventProjectionRegressionTest {
    @Test(timeout = 10_000) fun messageIdsSeparateMessagesButDoNotSplitChunks() {
        val boundary = AcpMessageBoundary(4)
        fun project(id: String?, text: String) = boundary.prefix(id, text) + text
        assertEquals("Checking", project("a", "Checking"))
        assertEquals(" now", project("a", " now"))
        assertEquals("\n\nDone", project("b", "Done"))
        assertEquals("!", project(null, "!"))
        assertEquals("\n\nNext", project("c", "Next"))
    }

    @Test(timeout = 10_000) fun emptyNewMessageChunkDoesNotSwallowTheNextBoundary() {
        val boundary = AcpMessageBoundary(4)
        assertEquals("", boundary.prefix("a", "first"))
        assertEquals("", boundary.prefix("b", ""))
        assertEquals("\n\n", boundary.prefix("b", "second"))
    }

    @Test(timeout = 10_000) fun absentIdsAndIndependentThoughtStreamRemainCompatible() {
        val answer = AcpMessageBoundary(4)
        val thought = AcpMessageBoundary(4)
        assertEquals("", answer.prefix(null, "a"))
        assertEquals("", answer.prefix(null, "b"))
        assertEquals("", answer.prefix("a", "c"))
        assertEquals("", thought.prefix("b", "think"))
        assertEquals("", answer.prefix("a", "d"))
        assertEquals("\n\n", thought.prefix("c", "more"))
    }

    @Test(timeout = 10_000) fun oversizeIdentifierFailsExplicitlyWithoutTruncatingIntoCollisions() {
        val boundary = AcpMessageBoundary(2)
        boundary.prefix("ab", "first")
        try { boundary.prefix("abc", "second"); fail("Expected identifier limit") }
        catch (_: IllegalArgumentException) { }
        assertEquals("", boundary.prefix("ab", "still first"))
    }

    @Test(timeout = 10_000) fun boundedDiagnosticPreservesSmallJsonAndSignalsClipping() {
        val small = buildJsonObject { put("a", "x\n\"y"); put("n", 2) }
        assertEquals(small.toString(), boundedJsonDiagnostic(small, 80))
        val source = buildJsonObject { put("a", "abcdefghijklmnop"); put("b", "tail") }
        val clipped = boundedJsonDiagnostic(source, 16)
        assertTrue(clipped.length <= 16)
        assertTrue(clipped.endsWith("[truncated]"))
        assertFalse(clipped.contains("tail"))
    }

    @Test(timeout = 10_000) fun diagnosticBoundsDepthAndSupplementaryCharacterCuts() {
        val deep = buildJsonObject { put("a", buildJsonArray { add(buildJsonArray { add("z") }) }) }
        assertTrue(boundedJsonDiagnostic(deep, 40, 1).endsWith("[truncated]"))
        val unicode = JsonPrimitive("a\uD83D\uDE00bcdef")
        for (limit in 1..12) {
            val result = boundedJsonDiagnostic(unicode, limit)
            assertTrue(result.length <= limit)
            for (index in result.indices) {
                if (result[index].isHighSurrogate()) assertTrue(index + 1 < result.length && result[index + 1].isLowSurrogate())
                if (result[index].isLowSurrogate()) assertTrue(index > 0 && result[index - 1].isHighSurrogate())
            }
        }
    }
    @Test(timeout = 10_000) fun clippedDiagnosticLabelPreservesSurrogatePairsAndSignalsProjection() {
        assertEquals("abc", boundedDiagnosticText("abc", 3))
        assertEquals("a…", boundedDiagnosticText("a\uD83D\uDE00bc", 3))
        assertEquals("…", boundedDiagnosticText("abc", 1))
    }

}
