package com.baystudio.droide.core

import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class ProviderPayloadLimitsTest {
    @Test fun endlessProviderLineIsRejectedBeforeUnboundedBuffering() {
        var readBytes = 0L
        val upstream = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val count = minOf(byteCount, 8192).toInt()
                sink.write(ByteArray(count) { 'x'.code.toByte() })
                readBytes += count
                return count.toLong()
            }
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }
        upstream.buffer().use { source ->
            assertThrows(IllegalStateException::class.java) { ProviderPayloadLimits.readLine(source, 1024) }
            assertTrue("Must not drain an endless server response", readBytes <= 1024 + 8192)
        }
    }

    @Test fun boundedLineReaderPreservesCrLfUnicodeBlankLinesAndFinalLine() {
        val source = Buffer().writeUtf8("data: halo 🌏\r\n\nfinal")
        assertEquals("data: halo 🌏", ProviderPayloadLimits.readLine(source, 100))
        assertEquals("", ProviderPayloadLimits.readLine(source, 100))
        assertEquals("final", ProviderPayloadLimits.readLine(source, 100))
        assertNull(ProviderPayloadLimits.readLine(source, 100))
    }

    @Test fun exactCapacityFinalLineIsAllowedAndOneMoreByteIsRejected() {
        assertEquals("1234", ProviderPayloadLimits.readLine(Buffer().writeUtf8("1234"), 4))
        assertEquals("1234", ProviderPayloadLimits.readLine(Buffer().writeUtf8("1234\n"), 4))
        assertThrows(IllegalStateException::class.java) { ProviderPayloadLimits.readLine(Buffer().writeUtf8("12345"), 4) }
    }

    @Test fun deeplyNestedJsonFailsBeforeRecursiveDecoding() {
        val payload = "[".repeat(ProviderPayloadLimits.MAX_JSON_DEPTH + 1) + "0" + "]".repeat(ProviderPayloadLimits.MAX_JSON_DEPTH + 1)
        val failure = assertThrows(IllegalStateException::class.java) { ProviderPayloadLimits.checkedJson(payload) }
        assertTrue(failure.message.orEmpty().contains("nesting"))
        assertFalse(failure.message.orEmpty().contains(payload))
    }

    @Test fun BracketsAndEscapedQuotesInsideReasoningTextAreNotCountedAsNesting() {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("text", kotlinx.serialization.json.JsonPrimitive("\\\"" + "[{}]".repeat(10_000)))
        }.toString()
        assertSame(payload, ProviderPayloadLimits.checkedJson(payload))
    }
}
