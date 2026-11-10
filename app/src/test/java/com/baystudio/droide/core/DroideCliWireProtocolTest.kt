package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DroideCliWireProtocolTest {
    @Test fun progressIsOptInAndLegacyRequestRemainsFinalOnly() {
        val args = listOf("pkg", "install", "runtime.python")
        for (stream in listOf(false, true)) {
            val bytes = ByteArrayOutputStream().also { DroideCliWireProtocol.writeRequest(it, args, stream) }.toByteArray()
            val decoded = DroideCliWireProtocol.readRequestEnvelope(ByteArrayInputStream(bytes))
            assertEquals(args, decoded.arguments)
            assertEquals(stream, decoded.streamProgress)
            assertEquals(if (stream) "DRQ2" else "DRQ1", String(bytes.copyOfRange(0, 4)))
        }
    }

    @Test fun progressFramesAndFinalFailureKeepTheirOrderAndMeaning() {
        val final = DroideCliExecutionResult(4, stderr = "Health check failed")
        val bytes = ByteArrayOutputStream().also {
            DroideCliWireProtocol.writeProgress(it, "Unpacking 日本語\n")
            DroideCliWireProtocol.writeResponse(it, final)
        }.toByteArray()
        val input = ByteArrayInputStream(bytes)
        assertEquals(DroideCliWireProtocol.ResponseFrame.Progress("Unpacking 日本語\n"), DroideCliWireProtocol.readResponseFrame(input))
        assertEquals(final, DroideCliWireProtocol.readResponse(input))
        assertEquals(-1, input.read())
        assertThrows(IllegalArgumentException::class.java) {
            DroideCliWireProtocol.readResponse(ByteArrayInputStream(bytes))
        }
    }

    @Test fun progressIsBoundedAndTruncationPreservesUtf8() {
        val bytes = ByteArrayOutputStream().also { DroideCliWireProtocol.writeProgress(it, "🙂".repeat(10_000)) }.toByteArray()
        val progress = DroideCliWireProtocol.readResponseFrame(ByteArrayInputStream(bytes)) as DroideCliWireProtocol.ResponseFrame.Progress
        assertTrue(progress.stderr.toByteArray().size <= DroideCliWireProtocol.MAX_PROGRESS_BYTES)
        assertTrue(progress.stderr.endsWith("[output truncated]\n"))
    }

    @Test fun progressCannotCarryStdoutOrExitCodeAndRejectsOversizedBodyBeforeRead() {
        for ((code, out, err) in listOf(Triple(1, 0, 0), Triple(0, 1, 0), Triple(0, 0, 16_385))) {
            val bytes = ByteArrayOutputStream().also {
                val data = java.io.DataOutputStream(it)
                data.writeBytes("DRP1"); data.writeInt(code); data.writeInt(out); data.writeInt(err)
            }.toByteArray()
            assertThrows(IllegalArgumentException::class.java) { DroideCliWireProtocol.readResponseFrame(ByteArrayInputStream(bytes)) }
        }
    }

    @Test fun malformedProgressUtf8IsRejected() {
        val bytes = ByteArrayOutputStream().also {
            val data = java.io.DataOutputStream(it)
            data.writeBytes("DRP1"); data.writeInt(0); data.writeInt(0); data.writeInt(2)
            data.write(byteArrayOf(0xC3.toByte(), 0x28))
        }.toByteArray()
        assertThrows(Exception::class.java) { DroideCliWireProtocol.readResponseFrame(ByteArrayInputStream(bytes)) }
    }

    @Test fun requestRoundTripPreservesUnicodeAndQuotingAsLiteralArgv() {
        val expected = listOf("pkg", "info", "runtime.swift@6.4.0 β", "literal ' quote")
        val bytes = ByteArrayOutputStream().also { DroideCliWireProtocol.writeRequest(it, expected) }.toByteArray()
        assertEquals(expected, DroideCliWireProtocol.readRequest(ByteArrayInputStream(bytes)))
    }

    @Test fun structuredResponseRoundTripPreservesExitAndStreams() {
        val expected = DroideCliExecutionResult(4, stdout = "progress ✓\n", stderr = "transaction gagal β\n")
        val bytes = ByteArrayOutputStream().also { DroideCliWireProtocol.writeResponse(it, expected) }.toByteArray()
        assertEquals(expected, DroideCliWireProtocol.readResponse(ByteArrayInputStream(bytes)))
    }

    @Test fun malformedUtf8IsRejected() {
        val bytes = byteArrayOf(
            'D'.code.toByte(), 'R'.code.toByte(), 'Q'.code.toByte(), '1'.code.toByte(),
            0, 0, 0, 1,
            0, 0, 0, 2,
            0xC3.toByte(), 0x28,
        )
        assertThrows(Exception::class.java) { DroideCliWireProtocol.readRequest(ByteArrayInputStream(bytes)) }
    }

    @Test fun oversizedRequestIsRejectedBeforeAllocationEscapesProtocolLimit() {
        val huge = "x".repeat(DroideCliWireProtocol.MAX_ARGUMENT_BYTES)
        val args = listOf("pkg", huge, huge, huge, huge)
        assertThrows(IllegalArgumentException::class.java) {
            DroideCliWireProtocol.writeRequest(ByteArrayOutputStream(), args)
        }
    }

    @Test fun oversizedOutputIsUtf8SafeAndBounded() {
        val value = "🙂".repeat(30_000)
        val bytes = ByteArrayOutputStream().also {
            DroideCliWireProtocol.writeResponse(it, DroideCliExecutionResult(0, stdout = value))
        }.toByteArray()
        val decoded = DroideCliWireProtocol.readResponse(ByteArrayInputStream(bytes))
        assertTrue(decoded.stdout.toByteArray(Charsets.UTF_8).size <= DroideCliWireProtocol.MAX_STREAM_BYTES)
        assertTrue(decoded.stdout.endsWith("[output truncated]\n"))
    }
}
