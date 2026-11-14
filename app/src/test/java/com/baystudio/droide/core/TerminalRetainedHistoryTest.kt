package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class TerminalRetainedHistoryTest {
    @Test fun capturesExactImmutableTextIncludingWhitespaceNewlinesAndEmoji() {
        val input = "  retained\r\n日本語 😀 e\u0301\t\n"
        val snapshot = TerminalRetainedHistory.capture(input)
        assertEquals(input, snapshot.text)
        assertEquals("", TerminalRetainedHistory.capture("").text)
    }

    @Test fun admitsMaximumSnapshotAndRejectsOversizeWithoutSilentlyTrimming() {
        val input = "x".repeat(TerminalRetainedHistory.MAX_TEXT_UTF16 - 2) + "😀"
        assertEquals(input, TerminalRetainedHistory.capture(input).text)
        expectThrows<IllegalArgumentException> { TerminalRetainedHistory.capture(input + "x") }
    }

    @Test fun rejectsMalformedSnapshotAndQueryWithoutExposingTheirContentsInErrors() {
        val snapshot = TerminalRetainedHistory.capture("valid 😀 text")
        for (malformed in listOf("\uD800", "\uDC00", "x\uD800x", "x\uDC00x", "\uD800\uD800")) {
            val input = "private-terminal-content:$malformed"
            val captureError = expectThrows<IllegalArgumentException> { TerminalRetainedHistory.capture(input) }
            val queryError = expectThrows<IllegalArgumentException> { snapshot.search(input) }
            assertFalse(captureError.message.orEmpty().contains("private-terminal-content"))
            assertFalse(queryError.message.orEmpty().contains("private-terminal-content"))
        }
    }

    @Test fun emptyQueryAndMissingOrLongerQueryReturnEmptyUncappedResults() {
        val snapshot = TerminalRetainedHistory.capture("abc")
        for (query in listOf("", "missing", "abcd")) {
            val result = snapshot.search(query)
            assertTrue(result.matches.isEmpty())
            assertFalse(result.capped)
        }
        assertTrue(TerminalRetainedHistory.capture("").search("x").matches.isEmpty())
    }

    @Test fun literalSearchDoesNotInterpretRegexSyntaxAndMatchesAcrossNewlines() {
        val snapshot = TerminalRetainedHistory.capture("start .*+[a]?\nnext .*+[a]? end")
        val literal = snapshot.search(".*+[a]?")
        assertEquals(listOf(6, 19), literal.matches.map { it.startUtf16 })
        assertEquals(listOf(13, 26), literal.matches.map { it.endUtf16Exclusive })
        val newline = snapshot.search("?\nnext")
        assertEquals(12, newline.matches.single().startUtf16)
        assertEquals("?\nnext", snapshot.text.substring(12, newline.matches.single().endUtf16Exclusive))
    }

    @Test fun ignoreCasePreservesOriginalUtf16OffsetsAndText() {
        val snapshot = TerminalRetainedHistory.capture("🙂 One\n🙂 ONE\n🙂 one")
        val sensitive = snapshot.search("one")
        assertEquals(listOf(17), sensitive.matches.map { it.startUtf16 })
        val insensitive = snapshot.search("one", ignoreCase = true)
        assertEquals(listOf(3, 10, 17), insensitive.matches.map { it.startUtf16 })
        assertEquals(listOf(6, 13, 20), insensitive.matches.map { it.endUtf16Exclusive })
        assertEquals(listOf(0, 7, 14), snapshot.search("🙂").matches.map { it.startUtf16 })
        assertTrue(insensitive.matches.first().snippet.contains("One\n"))
        assertEquals(listOf(0, 2), TerminalRetainedHistory.capture("É é").search("é", true).matches.map { it.startUtf16 })
    }

    @Test fun matchesAreNonOverlappingAndBoundaryMatchesAreIncluded() {
        val snapshot = TerminalRetainedHistory.capture("aaaaa")
        assertEquals(listOf(0, 2), snapshot.search("aa").matches.map { it.startUtf16 })
        assertEquals(0, snapshot.search("aaaaa").matches.single().startUtf16)
        assertEquals(5, snapshot.search("aaaaa").matches.single().endUtf16Exclusive)
    }

    @Test fun queryCapIsInUtf16UnitsAndIncludesCompleteEmojiAtItsBoundary() {
        val query = "a".repeat(TerminalRetainedHistory.MAX_QUERY_UTF16 - 2) + "😀"
        val snapshot = TerminalRetainedHistory.capture(query + " tail")
        assertEquals(query.length, snapshot.search(query).matches.single().endUtf16Exclusive)
        expectThrows<IllegalArgumentException> { snapshot.search(query + "x") }
    }

    @Test fun exactlyFiveHundredMatchesAreNotReportedAsCapped() {
        val snapshot = TerminalRetainedHistory.capture("a ".repeat(500) + "tail without further occurrences")
        // Use the two-unit token so the prose tail cannot add another match.
        val result = snapshot.search("a ")
        assertEquals(500, result.matches.size)
        assertEquals(998, result.matches.last().startUtf16)
        assertFalse(result.capped)
    }

    @Test fun fiveHundredAndOneMatchesAreHonestlyCappedAtFiveHundred() {
        val result = TerminalRetainedHistory.capture("a ".repeat(501)).search("a ")
        assertEquals(500, result.matches.size)
        assertTrue(result.capped)
        val error = expectThrows<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (result.matches as MutableList<TerminalRetainedHistory.Match>).clear()
        }
        assertNotNull(error)
        assertEquals(500, result.matches.size)
    }

    @Test fun denseMaximumSnapshotKeepsResultsAndSnippetsBounded() {
        val result = TerminalRetainedHistory.capture("x".repeat(TerminalRetainedHistory.MAX_TEXT_UTF16)).search("x")
        assertEquals(500, result.matches.size)
        assertTrue(result.capped)
        assertTrue(result.matches.all { it.snippet.length <= TerminalRetainedHistory.MAX_SNIPPET_UTF16 })
    }

    @Test fun snippetsKeepWholeMatchesAndNeverSplitBoundaryPairs() {
        val query = "Q".repeat(TerminalRetainedHistory.MAX_QUERY_UTF16)
        // Each 64-unit context boundary would land in the middle of an emoji.
        val input = "x😀" + "p".repeat(63) + query + "s".repeat(63) + "😀x"
        val match = TerminalRetainedHistory.capture(input).search(query).matches.single()
        assertEquals(66, match.startUtf16)
        assertEquals(322, match.endUtf16Exclusive)
        assertEquals(3, match.snippetStartUtf16)
        assertEquals("p".repeat(63) + query + "s".repeat(63), match.snippet)
        assertTrue(match.snippet.length <= TerminalRetainedHistory.MAX_SNIPPET_UTF16)
        assertEquals(match.snippet, TerminalRetainedHistory.capture(match.snippet).text)
        val localStart = match.startUtf16 - match.snippetStartUtf16
        assertEquals(query, match.snippet.substring(localStart, localStart + query.length))
    }

    @Test fun snippetsAtStartAndEndRemainExactAndPairSafe() {
        val input = "😀" + "x".repeat(400) + "😀"
        val result = TerminalRetainedHistory.capture(input).search("😀")
        assertEquals(listOf(0, 402), result.matches.map { it.startUtf16 })
        assertEquals(0, result.matches.first().snippetStartUtf16)
        for (match in result.matches) {
            assertEquals(match.snippet, TerminalRetainedHistory.capture(match.snippet).text)
            assertEquals(match.snippet, input.substring(match.snippetStartUtf16, match.snippetStartUtf16 + match.snippet.length))
        }
    }

    @Test fun captureCancellationIsCheckedDuringLongPairValidationAndKeepsIdentity() {
        val cancelled = CancellationException("cancel validation")
        var checks = 0
        val actual = expectThrows<CancellationException> {
            TerminalRetainedHistory.capture("x" + "😀".repeat(100_000)) {
                if (++checks == 4) throw cancelled
            }
        }
        assertSame(cancelled, actual)
        assertEquals(4, checks)
    }

    @Test fun noMatchSearchChecksCancellationInsideItsBoundedScan() {
        val snapshot = TerminalRetainedHistory.capture("a".repeat(TerminalRetainedHistory.MAX_TEXT_UTF16))
        val cancelled = CancellationException("cancel search")
        var checks = 0
        val actual = expectThrows<CancellationException> {
            snapshot.search("a".repeat(255) + "b") {
                if (++checks == 5) throw cancelled
            }
        }
        assertSame(cancelled, actual)
        assertEquals(5, checks)
    }

    @Test fun cancellationAlsoAppliesToEmptyQueryAndEmptyExport() {
        val snapshot = TerminalRetainedHistory.capture("")
        val cancelled = CancellationException("cancel empty operation")
        assertSame(cancelled, expectThrows<CancellationException> { snapshot.search("") { throw cancelled } })
        assertSame(cancelled, expectThrows<CancellationException> { snapshot.writeUtf8(ByteArrayOutputStream()) { throw cancelled } })
    }

    @Test fun exportIsExactUtf8WithoutMarkupBomOrAddedNewlineAndLeavesStreamOpen() {
        val input = "  <tag>\r\n日本語 😀 e\u0301\u0000\tend"
        val output = TrackingOutputStream()
        val bytes = TerminalRetainedHistory.capture(input).writeUtf8(output)
        assertArrayEquals(input.toByteArray(Charsets.UTF_8), output.toByteArray())
        assertEquals(output.size(), bytes)
        assertFalse(output.closed)
        assertFalse(output.flushed)
    }

    @Test fun exportChunkBoundaryDoesNotSplitEmojiOrChangeAnyByte() {
        val input = "x".repeat(4095) + "😀" + "日".repeat(9000) + "\n"
        val output = TrackingOutputStream()
        TerminalRetainedHistory.capture(input).writeUtf8(output)
        assertArrayEquals(input.toByteArray(Charsets.UTF_8), output.toByteArray())
        assertTrue(output.chunkSizes.size > 1)
        assertTrue(output.chunkSizes.all { it <= 4096 * 3 })
    }

    @Test fun maximumUtf8ExportHonorsExactByteBound() {
        val input = "日".repeat(TerminalRetainedHistory.MAX_TEXT_UTF16)
        val output = TrackingOutputStream()
        val bytes = TerminalRetainedHistory.capture(input).writeUtf8(output)
        assertEquals(TerminalRetainedHistory.MAX_UTF8_BYTES, bytes)
        assertArrayEquals(input.toByteArray(Charsets.UTF_8), output.toByteArray())
        assertTrue(output.chunkSizes.all { it <= 4096 * 3 })
    }

    @Test fun emptyExportWritesNothingAndDoesNotFlushOrClose() {
        val output = TrackingOutputStream()
        assertEquals(0, TerminalRetainedHistory.capture("").writeUtf8(output))
        assertEquals(0, output.size())
        assertTrue(output.chunkSizes.isEmpty())
        assertFalse(output.closed)
        assertFalse(output.flushed)
    }

    @Test fun exportCancellationAfterAChunkPreservesIdentityAndCallerOwnership() {
        val snapshot = TerminalRetainedHistory.capture("x".repeat(20_000))
        val output = TrackingOutputStream()
        val cancelled = CancellationException("cancel export")
        val actual = expectThrows<CancellationException> {
            snapshot.writeUtf8(output) { if (output.size() > 0) throw cancelled }
        }
        assertSame(cancelled, actual)
        assertEquals(4096, output.size())
        assertFalse(output.closed)
        assertFalse(output.flushed)
    }

    @Test fun outputFailurePreservesExactExceptionAndDoesNotCloseCallerStream() {
        val failure = IOException("fixture output failure")
        var closed = false
        val output = object : OutputStream() {
            override fun write(value: Int) { throw failure }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { throw failure }
            override fun close() { closed = true }
        }
        assertSame(failure, expectThrows<IOException> { TerminalRetainedHistory.capture("text").writeUtf8(output) })
        assertFalse(closed)
    }

    @Test fun cancellationRaisedByOutputIsNotConvertedIntoAnIoFailure() {
        val cancelled = CancellationException("output cancellation")
        val output = object : OutputStream() {
            override fun write(value: Int) { throw cancelled }
        }
        assertSame(cancelled, expectThrows<CancellationException> {
            TerminalRetainedHistory.capture("text").writeUtf8(output)
        })
    }

    private class TrackingOutputStream : ByteArrayOutputStream() {
        var closed = false
        var flushed = false
        val chunkSizes = mutableListOf<Int>()
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            chunkSizes += length
            super.write(bytes, offset, length)
        }
        override fun close() { closed = true; super.close() }
        override fun flush() { flushed = true; super.flush() }
    }

    private inline fun <reified T : Throwable> expectThrows(action: () -> Unit): T {
        try {
            action()
        } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}
