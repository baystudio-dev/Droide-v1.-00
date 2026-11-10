package com.baystudio.droide.core

import java.io.IOException
import java.io.StringReader
import org.junit.Assert.*
import org.junit.Test

class BoundedProtocolLineReaderTest {
    @Test fun mediaFrameCeilingAcceptsSmallFramesWithoutAllocatingTheMaximumAndRejectsUnboundedCaps() {
        assertEquals("{}", BoundedProtocolLineReader(StringReader("{}\n").buffered(), 6_000_000).readLine())
        assertTrue(runCatching { BoundedProtocolLineReader(StringReader("").buffered(), 6_000_001) }.isFailure)
    }

    @Test fun fragmentedCrLfUnicodeAndEofKeepBufferedReaderLineSemantics() {
        val lines = BoundedProtocolLineReader(StringReader("hello\r\n\rfoo\n日本語😀").buffered(1), 8)
        assertEquals("hello", lines.readLine())
        assertEquals("", lines.readLine())
        assertEquals("foo", lines.readLine())
        assertEquals("日本語😀", lines.readLine())
        assertNull(lines.readLine())
    }

    @Test fun oversizedRecordFailsBeforeCopyingTheWholeRecordOrAcceptingAPartialResponse() {
        val input = StringReader("x".repeat(10_000) + "\nnext\n").buffered(16)
        val lines = BoundedProtocolLineReader(input, 64)
        val failure = runCatching { lines.readLine() }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(failure?.message.orEmpty().contains("64"))
    }

    @Test fun exactLimitAndEmptyLinesAreAccepted() {
        val lines = BoundedProtocolLineReader(StringReader("abcd\n\nabcd").buffered(), 4)
        assertEquals("abcd", lines.readLine())
        assertEquals("", lines.readLine())
        assertEquals("abcd", lines.readLine())
        assertNull(lines.readLine())
    }
}
