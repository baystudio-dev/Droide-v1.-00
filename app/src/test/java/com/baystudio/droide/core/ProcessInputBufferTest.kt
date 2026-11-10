package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class ProcessInputBufferTest {
    @Test fun gracefulCloseFlushesBufferedStdinBeforeEof() {
        val input = ProcessInputBuffer()
        input.offer("first\n")
        input.offer("second\n")
        input.close()
        val out = ByteArrayOutputStream()
        input.drain(out) { true }
        assertEquals("first\nsecond\n", out.toString("UTF-8"))
        assertTrue(input.isClosed)
        assertTrue(runCatching { input.offer("late") }.isFailure)
    }

    @Test fun abortDiscardsPendingStdinWithoutReplayingCommands() {
        val input = ProcessInputBuffer()
        input.offer("must-not-run\n")
        input.offer("must-not-run-either\n")
        input.abort()
        input.abort()
        val out = ByteArrayOutputStream()
        input.drain(out) { true }
        assertEquals("", out.toString("UTF-8"))
        assertTrue(input.isClosed)
        assertTrue(runCatching { input.offer("after-stop") }.isFailure)
    }

    @Test fun abortWakesBlockedDrainerWithinPollingBound() {
        val input = ProcessInputBuffer()
        val ready = CountDownLatch(1)
        val out = ByteArrayOutputStream()
        val worker = thread(name = "test-process-input-drain", isDaemon = true) {
            input.drain(out) {
                ready.countDown()
                true
            }
        }
        try {
            assertTrue("Drainer did not start", ready.await(2, TimeUnit.SECONDS))
            input.abort()
            worker.join(1000)
            assertFalse("Stopped drainer remained alive", worker.isAlive)
            assertEquals(0, out.size())
        } finally {
            input.abort()
            worker.join(1000)
        }
    }

    @Test fun partialPipeWriteFailureAbortsQueuedInputAndRejectsAnyRetry() {
        val input = ProcessInputBuffer()
        input.offer("first command")
        input.offer("second command must not run")
        var acceptedBytes = 0
        val failing = object : java.io.OutputStream() {
            override fun write(byteValue: Int) {
                acceptedBytes++
                if (acceptedBytes >= 3) throw java.io.IOException("partial pipe write")
            }
        }
        val error = runCatching { input.drain(failing) { true } }.exceptionOrNull()
        assertTrue(error is java.io.IOException)
        assertEquals(3, acceptedBytes)
        assertTrue(input.isClosed)
        assertTrue(runCatching { input.offer("replay") }.isFailure)
        val later = ByteArrayOutputStream()
        input.drain(later) { true }
        assertEquals(0, later.size())
    }

    @Test fun emptyInputAndOverflowStayBounded() {
        val input = ProcessInputBuffer()
        input.offer("")
        repeat(8) { input.offer("x") }
        assertTrue(runCatching { input.offer("overflow") }.isFailure)
        assertTrue(runCatching { input.offer("x".repeat(65_537)) }.isFailure)
        input.abort()
        assertTrue(input.isClosed)
    }
    @Test fun ptyBindingFlushesOldChunksThenSharesImmediateAdmissionAndOneEof() {
        val input = ProcessInputBuffer()
        val writes = mutableListOf<String>()
        var eof = 0
        input.offer("queued1"); input.offer("queued2")
        input.bindPty({ writes += String(it); true }, { eof++; true })
        input.offer("next")
        input.close(); input.close()
        assertEquals(listOf("queued1", "queued2", "next"), writes)
        assertEquals(1, eof)
        input.abort()
        assertTrue(runCatching { input.offer("old") }.isFailure)
    }

    @Test fun closedBeforeBindingDeliversQueuedInputThenEofAndFailedBindingNeverReplays() {
        val input = ProcessInputBuffer()
        val writes = mutableListOf<String>()
        input.offer("queued"); input.close()
        input.bindPty({ writes += String(it); true }, { writes += "EOF"; true })
        assertEquals(listOf("queued", "EOF"), writes)
        val failed = ProcessInputBuffer()
        failed.offer("possiblySent"); failed.offer("mustNotReplay")
        assertTrue(runCatching { failed.bindPty({ false }, { true }) }.isFailure)
        assertTrue(failed.isAborted)
        assertTrue(runCatching { failed.bindPty({ fail("No replay"); true }, { true }) }.isFailure)
    }

    @Test fun failedPtyAdmissionDoesNotAcknowledgeOrReplayRejectedChunk() {
        val input = ProcessInputBuffer()
        var calls = 0
        input.bindPty({ calls++; false }, { false })
        assertTrue(runCatching { input.offer("rejected") }.isFailure)
        assertEquals(1, calls)
        input.abort()
        assertTrue(runCatching { input.offer("late") }.isFailure)
        assertEquals(1, calls)
    }

}
