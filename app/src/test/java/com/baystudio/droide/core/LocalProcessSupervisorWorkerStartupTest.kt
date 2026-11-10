package com.baystudio.droide.core

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalProcessSupervisorWorkerStartupTest {
    @Test(timeout = 10_000)
    fun firstWorkerStartFailureReleasesProcessWithoutStartingAnotherWorker() = runBlocking<Unit> {
        verifyWorkerFailure(failAt = 1, startBeforeFailure = false)
    }

    @Test(timeout = 10_000)
    fun secondWorkerStartFailureReleasesProcessAndJoinsStartedReader() = runBlocking<Unit> {
        verifyWorkerFailure(failAt = 2, startBeforeFailure = false)
    }

    @Test(timeout = 10_000)
    fun readerStartedBeforeStarterThrowsIsStillOwnedAndJoined() = runBlocking<Unit> {
        verifyWorkerFailure(failAt = 1, startBeforeFailure = true)
    }

    @Test(timeout = 10_000)
    fun writerStartedBeforeStarterThrowsCannotEscapeCleanup() = runBlocking<Unit> {
        verifyWorkerFailure(failAt = 2, startBeforeFailure = true)
    }

    private suspend fun verifyWorkerFailure(failAt: Int, startBeforeFailure: Boolean) {
        val process = SupervisorStartupProcess()
        val input = ProcessInputBuffer().apply { offer("pending input") }
        val original = IOException("Worker $failAt could not be started")
        val cleanups = AtomicInteger()
        val receipts = mutableListOf<Throwable?>()
        val workers = mutableListOf<Thread>()
        try {
            val outcome = runCatching {
                LocalProcessSupervisor.captureOwned(process,
                    startWorker = { thread ->
                        workers.add(thread)
                        val failing = workers.size == failAt
                        if (!failing || startBeforeFailure) {
                            thread.start()
                            if (workers.size == 1) assertTrue("Reader did not reach its held pipe",
                                process.stdout.entered.await(2, TimeUnit.SECONDS))
                        }
                        if (failing) throw original
                    },
                    streamInput = input, nowNanos = { 0L },
                    cleanup = { cleanups.incrementAndGet() },
                    onCleanupCompleted = { receipts.add(it) })
            }
            assertSame("Worker-start failure must not turn into an ExecResult", original, outcome.exceptionOrNull())
            assertEquals("No later worker may start after startup failure", failAt, workers.size)
            assertEquals(1, cleanups.get())
            assertEquals(1, receipts.size)
            assertNull("Cleanup should be confirmed after owned workers settle", receipts.single())
            assertTrue(input.isAborted)
            assertFalse("Owned process was left alive", process.isAlive)
            assertEquals(1, process.destroyCalls.get())
            assertTrue(process.stdout.closed.get())
            assertTrue(process.stdinClosed.get())
            assertTrue("Every partially started worker must settle before return", workers.none { it.isAlive })
        } finally {
            input.abort(); process.releaseAndJoin()
            workers.forEach { thread -> thread.join(1_000); assertFalse(thread.isAlive) }
        }
    }
}
