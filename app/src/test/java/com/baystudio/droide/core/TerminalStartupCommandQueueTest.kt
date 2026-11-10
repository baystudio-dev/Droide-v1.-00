package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class TerminalStartupCommandQueueTest {
    @Test fun startupRawInputPreservesUnicodeControlBytesAndNewlinesExactlyOnce() {
        val queue = TerminalStartupCommandQueue()
        val output = mutableListOf<String>()
        queue.sendRaw("printf '日本語 😀'", 0, output::add)
        queue.sendRaw("\r\u0003\u0000", 0, output::add)
        queue.send("next", 0, output::add)
        assertTrue(output.isEmpty())
        queue.flush(101, output::add)
        queue.flush(101, output::add)
        assertEquals(listOf("printf '日本語 😀'", "\r\u0003\u0000", "next\n"), output)
    }

    @Test fun rawInputOnClosedOrExitedTerminalIsRejectedInsteadOfSilentlyDiscarded() {
        val queue = TerminalStartupCommandQueue()
        assertTrue(runCatching { queue.sendRaw("late", -1) { fail("Must not write") } }.isFailure)
        queue.sendRaw("old", 0) { fail("Startup is queued") }
        queue.close()
        queue.flush(10) { fail("Never replay closed input") }
        assertTrue(runCatching { queue.sendRaw("late", 10) { fail("Must not write") } }.isFailure)
        assertTrue(runCatching { TerminalStartupCommandQueue().sendRaw("x".repeat(65_537), 0) {} }.isFailure)
    }

    @Test fun pidZeroQueuesInputUntilRealChildAndFlushesInOrder() {
        val queue = TerminalStartupCommandQueue()
        val output = mutableListOf<String>()
        queue.send("first", 0, output::add)
        queue.send("second", 0, output::add)
        queue.flush(0, output::add)
        assertTrue(output.isEmpty())
        queue.send("third", 101, output::add)
        assertEquals(listOf("first\n", "second\n", "third\n"), output)
        queue.flush(101, output::add)
        assertEquals(3, output.size)
    }

    @Test fun exitClosesAndClearsQueueBeforeLaterCallbacks() {
        val queue = TerminalStartupCommandQueue()
        val output = mutableListOf<String>()
        queue.send("must not run", 0, output::add)
        queue.flush(-1, output::add)
        queue.flush(123, output::add)
        assertTrue(output.isEmpty())
        assertTrue(runCatching { queue.send("late", 0, output::add) }.isFailure)
        assertTrue(runCatching { queue.send("late", 123, output::add) }.isFailure)
    }

    @Test fun failedPtyWriteClosesStartupQueueAndNeverReplaysAmbiguousInput() {
        val queue = TerminalStartupCommandQueue()
        queue.send("possibly partial", 0) {}
        queue.send("must never replay", 0) {}
        var delivered = 0
        val failure = java.io.IOException("PTY write failed after partial bytes")
        val thrown = runCatching {
            queue.flush(123) {
                delivered++
                throw failure
            }
        }.exceptionOrNull()
        assertSame(failure, thrown)
        assertEquals(1, delivered)
        queue.flush(123) { fail("Never replay an ambiguous PTY write") }
        assertTrue(runCatching { queue.send("late", 123) {} }.isFailure)
    }

    @Test fun boundedQueueAndDestroyBeforePtyStartRejectInput() {
        val queue = TerminalStartupCommandQueue()
        repeat(32) { queue.send("$it", 0) {} }
        assertTrue(runCatching { queue.send("overflow", 0) {} }.isFailure)
        queue.close()
        queue.flush(123) { fail("Closed queue must never write") }
        assertTrue(runCatching { queue.send("late", 0) {} }.isFailure)
    }
}
