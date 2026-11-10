package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class CustomToolMetadataReadTest {
    @Test fun prefixRejectionReadsAtMostOneSmallChunk() = withInput("x".repeat(100_000)) { manager, input ->
        assertEquals("Custom tool demo", manager.discover().single().description)
        assertTrue(input.bytes in 1..512); assertTrue(input.closed); assertTrue(input.maxRequested <= 512)
    }

    @Test fun shortHeaderDoesNotReadTheScriptBody() = withInput("# desc: hello\n" + "x".repeat(100_000)) { manager, input ->
        assertEquals("hello", manager.discover().single().description)
        assertTrue(input.bytes <= 512); assertTrue(input.closed)
    }

    @Test fun oversizedHeaderStopsAtTheByteLimitAndCloses() = withInput("# desc:" + "x".repeat(100_000)) { manager, input ->
        assertTrue(manager.discover().single().description.startsWith("[Description omitted:"))
        assertEquals(8_193, input.bytes); assertTrue(input.closed); assertTrue(input.maxRequested <= 512)
    }

    @Test fun oneByteReadsPreserveMultibyteTextAndCloseOnEof() = withInput("# desc: 日本語🙂", chunk = 1) { manager, input ->
        assertEquals("日本語🙂", manager.discover().single().description)
        assertEquals("# desc: 日本語🙂".toByteArray().size, input.bytes); assertTrue(input.closed)
    }

    @Test fun zeroProgressFallsBackWithoutSpinning() = withSpecialInput(object : InputStream() {
        override fun read(): Int = 0
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = 0
    }) { manager, state ->
        assertEquals("Custom tool demo", manager.discover().single().description)
        assertEquals(1, state.calls); assertTrue(state.closed)
    }

    @Test fun readFailureFallsBackAndClosesTheStream() = withSpecialInput(object : InputStream() {
        override fun read(): Int = throw IOException("metadata failed")
    }) { manager, state ->
        assertEquals("Custom tool demo", manager.discover().single().description)
        assertTrue(state.closed)
    }

    @Test fun cancellationPropagatesAndClosesTheStream() = withSpecialInput(object : InputStream() {
        override fun read(): Int = throw CancellationException("stop metadata read")
    }) { manager, state ->
        assertThrows(CancellationException::class.java) { manager.discover() }
        assertTrue(state.closed)
    }

    @Test fun growingInputCannotExpandTheMetadataBudget() {
        val prefix = "# desc:".toByteArray()
        var cursor = 0
        withSpecialInput(object : InputStream() {
            override fun read(): Int = if (cursor < prefix.size) prefix[cursor++].toInt() else 'x'.code
        }) { manager, state ->
            assertTrue(manager.discover().single().description.startsWith("[Description omitted:"))
            assertEquals(8_193, state.bytes); assertTrue(state.closed)
        }
    }

    private class ObservedInput(private val source: InputStream, private val chunk: Int = Int.MAX_VALUE) : InputStream() {
        var bytes = 0; var calls = 0; var maxRequested = 0; var closed = false
        override fun read(): Int = error("Expected bounded byte reads")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            calls++; maxRequested = maxOf(maxRequested, length)
            val n = source.read(buffer, offset, minOf(length, chunk))
            if (n > 0) bytes += n
            return n
        }
        override fun close() { closed = true; source.close() }
    }
    private fun withInput(text: String, chunk: Int = Int.MAX_VALUE, block: (CustomToolManager, ObservedInput) -> Unit) =
        withSpecialInput(ByteArrayInputStream(text.toByteArray()), chunk, block)
    private fun withSpecialInput(input: InputStream, chunk: Int = Int.MAX_VALUE, block: (CustomToolManager, ObservedInput) -> Unit) {
        val root = Files.createTempDirectory("droide-custom-read-").toFile()
        try {
            root.resolve(".droide/tools/demo.sh").apply { parentFile.mkdirs(); writeText("true") }
            val observed = ObservedInput(input, chunk)
            block(CustomToolManager(root) { observed }, observed)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
