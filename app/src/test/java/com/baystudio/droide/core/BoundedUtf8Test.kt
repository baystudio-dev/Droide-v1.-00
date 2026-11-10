package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedUtf8Test {
    @Test fun emptyTextAndZeroByteBudgetRoundTripAndCloseStreams() = runBlocking {
        val input = TrackingInput(byteArrayOf())
        val output = TrackingOutput()
        assertEquals(0L, BoundedUtf8.encodedSize("", 0L) { throw LimitExceeded() })
        assertEquals("", BoundedUtf8.read(input, 0L) { throw LimitExceeded(it) })
        BoundedUtf8.write(output, "")
        assertTrue(input.closed)
        assertTrue(output.closed)
        assertArrayEquals(byteArrayOf(), output.toByteArray())
    }

    @Test fun exactByteBudgetsAcceptAsciiBmpAndSupplementaryText() = runBlocking {
        val fixtures = listOf("ascii\r\n", "\u00e9", "\u6f22", "\uD83D\uDE00", "a\u00e9\u6f22\uD83D\uDE00z")
        for (text in fixtures) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val input = TrackingInput(bytes)
            assertEquals(bytes.size.toLong(), BoundedUtf8.encodedSize(text, bytes.size.toLong()) { throw LimitExceeded() })
            assertEquals(text, BoundedUtf8.read(input, bytes.size.toLong()) { throw LimitExceeded(it) })
            assertTrue(input.closed)
        }
    }

    @Test fun oneByteTooSmallRejectsAsciiBmpAndSupplementaryText() {
        for (text in listOf("a", "\u00e9", "\u6f22", "\uD83D\uDE00")) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val limit = bytes.size - 1L
            assertThrows(LimitExceeded::class.java) {
                runBlocking { BoundedUtf8.encodedSize(text, limit) { throw LimitExceeded() } }
            }
            val input = TrackingInput(bytes)
            val failure = assertThrows(LimitExceeded::class.java) {
                runBlocking { BoundedUtf8.read(input, limit) { throw LimitExceeded(it) } }
            }
            assertEquals(limit + 1L, failure.total)
            assertEquals(bytes.size, input.consumed)
            assertTrue(input.closed)
        }
    }

    @Test fun malformedUtf16CountingAndWritingMatchStringToByteArray() = runBlocking {
        val fixtures = listOf(
            "\uD800", "\uDC00", "a\uD800b", "a\uDC00b", "a\uD800",
            "\uD800\uD800\uDC00", "\uDC00\uD800\uDC00", "\uD800\uD800", "\uDC00\uDC00",
            "\uD83D\uDE00\uD800\uDC00\uDC00",
        )
        for (text in fixtures) {
            val expected = text.toByteArray(Charsets.UTF_8)
            assertEquals(expected.size.toLong(), BoundedUtf8.encodedSize(text, expected.size.toLong()) { throw LimitExceeded() })
            val output = TrackingOutput()
            BoundedUtf8.write(output, text)
            assertArrayEquals(expected, output.toByteArray())
            assertTrue(output.closed)
            assertThrows(LimitExceeded::class.java) {
                runBlocking { BoundedUtf8.encodedSize(text, expected.size - 1L) { throw LimitExceeded() } }
            }
        }
    }

    @Test fun persistentWriterPreservesSurrogatePairsAcross8192CharacterBoundary() = runBlocking {
        val text = "a".repeat(8_191) + "\uD83D\uDE00" + "z".repeat(8_191) + "\uD83D\uDE00"
        val output = TrackingOutput()
        BoundedUtf8.write(output, text)
        assertArrayEquals(text.toByteArray(Charsets.UTF_8), output.toByteArray())
        assertTrue(output.closed)
    }

    @Test fun malformedSurrogatesAtChunkEdgesAndEofUseSameReplacementBytes() = runBlocking {
        val fixtures = listOf(
            "a".repeat(8_191) + "\uD800" + "z",
            "a".repeat(8_191) + "\uD800\uD800\uDC00",
            "a".repeat(8_192) + "\uDC00",
            "a".repeat(16_383) + "\uD800",
        )
        for (text in fixtures) {
            val expected = text.toByteArray(Charsets.UTF_8)
            val output = TrackingOutput()
            assertEquals(expected.size.toLong(), BoundedUtf8.encodedSize(text, expected.size.toLong()) { throw LimitExceeded() })
            BoundedUtf8.write(output, text)
            assertArrayEquals(expected, output.toByteArray())
        }
    }

    @Test fun strictDecoderRejectsMalformedOverlongSurrogateAndOutOfRangeUtf8() {
        val fixtures = listOf(
            byteArrayOf(0x80.toByte()),
            byteArrayOf(0xC3.toByte(), 0x28),
            byteArrayOf(0xC0.toByte(), 0xAF.toByte()),
            byteArrayOf(0xE0.toByte(), 0x80.toByte(), 0xAF.toByte()),
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
            byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0xFF.toByte()),
        )
        for (bytes in fixtures) {
            for (chunk in listOf(1, 2, 8_192)) {
                val input = TrackingInput(bytes, chunk)
                assertThrows(CharacterCodingException::class.java) {
                    runBlocking { BoundedUtf8.read(input, bytes.size.toLong()) { throw LimitExceeded(it) } }
                }
                assertTrue(input.closed)
            }
        }
    }

    @Test fun strictDecoderRejectsEveryTruncatedMultibyteSequenceAtEof() {
        for (text in listOf("\u00e9", "\u6f22", "\uD83D\uDE00")) {
            val complete = text.toByteArray(Charsets.UTF_8)
            for (size in 1 until complete.size) {
                val bytes = "valid prefix ".toByteArray() + complete.copyOf(size)
                val input = TrackingInput(bytes, 1)
                assertThrows(CharacterCodingException::class.java) {
                    runBlocking { BoundedUtf8.read(input, bytes.size.toLong()) { throw LimitExceeded(it) } }
                }
                assertTrue(input.closed)
            }
        }
    }

    @Test fun shortReadsPreserveMultibyteCharactersAndAllTextBytes() = runBlocking {
        val text = "\uFEFFa\u00e9\u6f22\uD83D\uDE00\r\nend\n"
        val bytes = text.toByteArray(Charsets.UTF_8)
        for (chunk in 1..7) {
            val input = TrackingInput(bytes, chunk)
            assertEquals(text, BoundedUtf8.read(input, bytes.size.toLong()) { throw LimitExceeded(it) })
            assertEquals(bytes.size, input.consumed)
            assertTrue(input.closed)
        }
    }

    @Test fun decoderPreservesEveryMultibyteSplitAcross8192ByteBoundary() = runBlocking {
        for (character in listOf("\u00e9", "\u6f22", "\uD83D\uDE00")) {
            val width = character.toByteArray(Charsets.UTF_8).size
            for (prefixBytes in 1 until width) {
                val text = "a".repeat(8_192 - prefixBytes) + character + "tail"
                val bytes = text.toByteArray(Charsets.UTF_8)
                val input = TrackingInput(bytes)
                assertEquals(text, BoundedUtf8.read(input, bytes.size.toLong()) { throw LimitExceeded(it) })
                assertTrue(input.closed)
            }
        }
    }

    @Test fun sourceGrowthIsRejectedAfterOnlyOneLookaheadByte() {
        val input = TrackingInput("ab".toByteArray(), 2)
        input.afterRead = {
            input.afterRead = null
            input.append("cdefghijklmnopqrstuvwxyz".toByteArray())
        }
        val failure = assertThrows(LimitExceeded::class.java) {
            runBlocking { BoundedUtf8.read(input, 3L) { throw LimitExceeded(it) } }
        }
        assertEquals(4L, failure.total)
        assertEquals(4, input.consumed)
        assertTrue(input.closed)
    }

    @Test fun aLongSourceCannotBeReadPastTheByteBudgetPlusOne() {
        val input = TrackingInput(ByteArray(100_000) { 'a'.code.toByte() })
        val failure = assertThrows(LimitExceeded::class.java) {
            runBlocking { BoundedUtf8.read(input, 7L) { throw LimitExceeded(it) } }
        }
        assertEquals(8L, failure.total)
        assertEquals(listOf(8), input.requestedLengths)
        assertEquals(8, input.consumed)
        assertTrue(input.closed)
    }

    @Test fun maximumSupportedReadBudgetDoesNotOverflowLookaheadArithmetic() = runBlocking {
        val input = TrackingInput("small".toByteArray(), 1)
        assertEquals("small", BoundedUtf8.read(input, Int.MAX_VALUE.toLong()) { throw LimitExceeded(it) })
        assertTrue(input.closed)
    }

    @Test fun readIoFailurePropagatesAndClosesTheSource() {
        val failure = IOException("read failure")
        var closed = false
        val input = object : InputStream() {
            override fun read(): Int = throw failure
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw failure
            override fun close() { closed = true }
        }
        val actual = assertThrows(IOException::class.java) {
            runBlocking { BoundedUtf8.read(input, 100L) { throw LimitExceeded(it) } }
        }
        assertSame(failure, actual)
        assertTrue(closed)
    }

    @Test fun cancellationDuringReadPropagatesAndClosesWithoutConsumingAllInput() {
        val job = Job()
        val input = TrackingInput("x".repeat(50_000).toByteArray(), 1)
        input.afterRead = { job.cancel(CancellationException("cancel during read")) }
        try {
            assertThrows(CancellationException::class.java) {
                runBlocking(job) { BoundedUtf8.read(input, 50_000L) { throw LimitExceeded(it) } }
            }
            assertEquals(1, input.consumed)
            assertTrue(input.closed)
        } finally { job.cancel() }
    }

    @Test fun cancellationDuringWritePropagatesAndClosesWithoutWritingAllText() {
        val job = Job()
        val text = "x".repeat(8_192 * 8)
        val output = TrackingOutput()
        output.afterWrite = { job.cancel(CancellationException("cancel during write")) }
        try {
            assertThrows(CancellationException::class.java) {
                runBlocking(job) { BoundedUtf8.write(output, text) }
            }
            assertTrue(output.size() > 0)
            assertTrue(output.size() < text.length)
            assertTrue(output.closed)
        } finally { job.cancel() }
    }

    @Test fun cancellationFromOutputCloseIsObservedBeforeWriteReturns() {
        val job = Job()
        var writeReturned = false
        val output = object : TrackingOutput() {
            override fun close() {
                super.close()
                job.cancel(CancellationException("cancel during close"))
            }
        }
        try {
            assertThrows(CancellationException::class.java) {
                runBlocking(job) {
                    BoundedUtf8.write(output, "final payload")
                    writeReturned = true
                }
            }
            assertTrue(output.closed)
            assertArrayEquals("final payload".toByteArray(Charsets.UTF_8), output.toByteArray())
            assertFalse(writeReturned)
        } finally { job.cancel() }
    }

    @Test fun encodedSizeRejectsAPreCancelledCoroutineIncludingEmptyText() {
        for (text in listOf("", "a\uD83D\uDE00")) {
            var countingStarted = false
            var countingReturned = false
            assertThrows(CancellationException::class.java) {
                runBlocking {
                    currentCoroutineContext().cancel(CancellationException("cancel before counting"))
                    countingStarted = true
                    BoundedUtf8.encodedSize(text, 100L) { throw LimitExceeded() }
                    countingReturned = true
                }
            }
            assertTrue(countingStarted)
            assertFalse(countingReturned)
        }
    }

    @Test fun outputFailurePropagatesAndClosesTheStagingStream() {
        val failure = IOException("write failure")
        val output = object : TrackingOutput() {
            private var failOnce = true
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                if (failOnce) { failOnce = false; throw failure }
                super.write(buffer, offset, length)
            }
        }
        val actual = assertThrows(IOException::class.java) {
            runBlocking { BoundedUtf8.write(output, "x".repeat(20_000)) }
        }
        assertSame(failure, actual)
        assertTrue(output.closed)
    }

    private class LimitExceeded(val total: Long = -1L) : IllegalArgumentException("UTF-8 byte limit")

    private class TrackingInput(initial: ByteArray, private val maxChunk: Int = Int.MAX_VALUE) : InputStream() {
        private var bytes = initial
        var consumed = 0
            private set
        var closed = false
            private set
        var afterRead: (() -> Unit)? = null
        val requestedLengths = mutableListOf<Int>()

        fun append(extra: ByteArray) { bytes += extra }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            requestedLengths += length
            if (length == 0) return 0
            if (consumed >= bytes.size) return -1
            val count = minOf(length, maxChunk, bytes.size - consumed)
            bytes.copyInto(buffer, offset, consumed, consumed + count)
            consumed += count
            afterRead?.invoke()
            return count
        }

        override fun close() { closed = true }
    }

    private open class TrackingOutput : ByteArrayOutputStream() {
        var closed = false
            private set
        var afterWrite: (() -> Unit)? = null

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            super.write(buffer, offset, length)
            afterWrite?.invoke()
        }

        override fun close() { closed = true; super.close() }
    }
}
