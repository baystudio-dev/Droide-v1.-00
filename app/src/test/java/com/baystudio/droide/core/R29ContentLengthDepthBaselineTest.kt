package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** Standalone R29 baseline: copy into a disposable test overlay only after review. */
class R29ContentLengthDepthBaselineTest {
    @Test fun arrayDepth128IsAccepted() {
        val payload = arrays(128)
        assertEquals(payload, readFrame(payload))
    }

    @Test fun arrayDepth129IsRejectedBeforeParsing() {
        assertNestingRejected(arrays(129))
    }

    @Test fun mixedObjectAndArrayDepth128IsAccepted() {
        val payload = mixed(128)
        assertEquals(payload, readFrame(payload))
    }

    @Test fun mixedObjectAndArrayDepth129IsRejectedBeforeParsing() {
        assertNestingRejected(mixed(129))
    }

    @Test fun quotedBracketsEscapedQuotesAndBackslashesDoNotCountAsNesting() {
        // More than 128 opening brackets are inside this one string. JsonPrimitive
        // supplies valid escaping; no untrusted payload is parsed in this test.
        val text = "\"" + "[{".repeat(70) + "\\" + "}]".repeat(70) + "\""
        val payload = "{\"text\":" + JsonPrimitive(text).toString() + "}"
        assertEquals(payload, readFrame(payload))
    }

    @Test fun evenBackslashesDoNotHideAClosingQuoteOrExcessiveNesting() {
        // The JSON string contains a single backslash, encoded as two. Its
        // closing quote must end quoted mode before the following array opens.
        val payload = "{\"text\":\"\\\\\",\"nested\":" + arrays(128) + "}"
        assertNestingRejected(payload) // One envelope object + 128 arrays.
    }

    private fun arrays(depth: Int): String = "[".repeat(depth) + "0" + "]".repeat(depth)

    private fun mixed(depth: Int): String = buildString {
        repeat(depth) { index -> append(if (index % 2 == 0) "{\"x\":" else "[") }
        append('0')
        for (index in depth - 1 downTo 0) append(if (index % 2 == 0) '}' else ']')
    }

    private fun assertNestingRejected(payload: String) {
        val failure = assertThrows(IllegalStateException::class.java) { readFrame(payload) }
        assertTrue("Expected a nesting-limit failure", failure.message.orEmpty().contains("nesting", ignoreCase = true))
        assertFalse("Failure must not echo the payload", failure.message.orEmpty().contains(payload))
    }

    private fun readFrame(payload: String): String? {
        assertTrue("Every payload must stay below 1 KiB", payload.toByteArray(Charsets.UTF_8).size < 1024)
        val output = ByteArrayOutputStream()
        ContentLengthProtocol.writeMessage(output, payload)
        assertTrue("The entire test frame must stay below 1 KiB", output.size() < 1024)
        return ContentLengthProtocol.readMessage(ByteArrayInputStream(output.toByteArray()))
    }
}
