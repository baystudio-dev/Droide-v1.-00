package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class TerminalOutputCollectorTest {
    @Test fun interleavedStdoutAndStderrKeepIndependentDecodersAcrossEveryByteBoundary() {
        val outText = "Build 日本語 😀 selesai\n"
        val errText = "ERROR: échec ✅\n"
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val out = TerminalOutputCollector(8192, stdout::append)
        val err = TerminalOutputCollector(8192, stderr::append)
        val a = outText.toByteArray(); val b = errText.toByteArray()
        repeat(maxOf(a.size, b.size)) { index ->
            if (index < a.size) out.append(byteArrayOf(a[index]))
            if (index < b.size) err.append(byteArrayOf(b[index]))
        }
        out.finish(); err.finish()
        assertEquals(outText, stdout.toString())
        assertEquals(errText, stderr.toString())
        assertEquals(outText, out.output)
        assertEquals(errText, err.output)
    }

    @Test fun largePacketStreamsFullyWhileStoredOutputRemainsBoundedAndTruncationExplicit() {
        val value = "x".repeat(8191) + "😀" + "日本語".repeat(10_000) + "FINAL ERROR\n"
        val streamed = StringBuilder()
        val collector = TerminalOutputCollector(4096, streamed::append)
        collector.append(value.toByteArray()); collector.finish()
        assertEquals(value, streamed.toString())
        assertTrue(collector.truncated)
        assertTrue(collector.output.toByteArray().size <= 4100)
        assertTrue(collector.output.endsWith("FINAL ERROR\n"))
    }

    @Test fun finalPartialCharacterIsReportedOnceAndConsumerFailureDoesNotAbortReading() {
        val streamed = StringBuilder()
        val collector = TerminalOutputCollector(4096) { streamed.append(it); if (it == "OK") error("UI detached") }
        collector.append("OK".toByteArray())
        collector.append(byteArrayOf(0xe2.toByte(), 0x82.toByte()))
        collector.finish(); collector.finish()
        assertEquals("OK\uFFFD", streamed.toString())
    }
}
