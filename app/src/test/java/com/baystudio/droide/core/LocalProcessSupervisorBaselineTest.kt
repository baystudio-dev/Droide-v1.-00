package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

/** Fake-only acceptance regressions for the mechanically extracted algorithm. */

class LocalProcessSupervisorBaselineTest {
    @Test(timeout = 10_000)
    fun exitedRootKeepsActualStatusWhenCleanupCrossesRuntimeDeadline() = runBlocking {
        val stdout = HeldInput()
        val process = FakeProcess(aliveInitially = false, naturalExit = 0, stdout = stdout)
        val clock = AtomicLong(0)
        val cleanups = AtomicInteger()
        try {
            val result = withContext(Dispatchers.IO) {
                LocalProcessSupervisor.captureOwned(
                    process = process,
                    timeoutMs = 50,
                    cleanup = {
                        cleanups.incrementAndGet()

                        assertFalse(process.isAlive)
                        clock.set(60_000_000L)
                    },
                    nowNanos = clock::get,
                    pause = {
                        // A repaired drain phase can finish once cleanup has run.
                        stdout.release()
                        yield()
                    },
                )
            }
            assertEquals("A known root exit must not become a timeout exit", 0, result.exitCode)
            assertFalse("Post-exit cleanup cannot time out an exited process", result.timedOut)
            assertEquals(1, cleanups.get())
            assertEquals(0, process.destroyCalls.get())
        } finally {
            process.releaseFakeResources()
            stdout.assertReleased()
        }
    }

    @Test(timeout = 10_000)
    fun exitedRootWithInheritedPipeFinishesWithinSeparateDrainBudget() = runBlocking {
        val stdout = HeldInput()
        val process = FakeProcess(aliveInitially = false, naturalExit = 7, stdout = stdout)
        val clock = AtomicLong(0)
        val cleanups = AtomicInteger()
        try {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    LocalProcessSupervisor.captureOwned(
                        process = process,
                        timeoutMs = 0,
                        cleanup = { cleanups.incrementAndGet() },
                        nowNanos = clock::get,
                        pause = {
                            // More than the existing ACP 2-second drain budget, with no wall-clock wait or external process.

                            val elapsed = clock.addAndGet(250_000_000L)
                            if (elapsed >= DIAGNOSTIC_BOUND_NANOS) throw DiagnosticBoundReached()
                            yield()
                        },
                    )
                }
            }
            assertNull(
                "An exited root waited through 10 synthetic seconds of retained stdout: ${outcome.exceptionOrNull()}",
                outcome.exceptionOrNull(),
            )
            val result = outcome.getOrThrow()
            assertEquals(7, result.exitCode)
            assertFalse("A post-exit drain limit is not a process timeout", result.timedOut)
            assertEquals(1, cleanups.get())
            assertEquals(0, process.destroyCalls.get())
            // The repaired candidate must additionally assert outputIncomplete; that result field
            // deliberately does not exist in this baseline.
        } finally {
            process.releaseFakeResources()
            stdout.assertReleased()
        }
    }

    @Test(timeout = 10_000)
    fun finiteOutputAndNaturalFailureStatusArePreserved() = runBlocking {
        val text = "FINAL: 日本語 😀 compiler error\n"
        val process = FakeProcess(false, 7, ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        val cleanups = AtomicInteger()
        try {
            val result = withContext(Dispatchers.IO) {
                LocalProcessSupervisor.captureOwned(
                    process = process,
                    timeoutMs = 0,
                    cleanup = { cleanups.incrementAndGet() },
                    nowNanos = { 0L },
                    pause = { yield() },
                )
            }
            assertEquals(7, result.exitCode)
            assertEquals(text, result.output)
            assertFalse(result.timedOut)
            assertEquals(1, cleanups.get())
            assertEquals(0, process.destroyCalls.get())
        } finally {
            process.releaseFakeResources()
        }
    }

    @Test(timeout = 10_000)
    fun explicitDeadlineStillStopsLivingRootAndCleansExactlyOnce() = runBlocking {
        val stdout = HeldInput()
        val process = FakeProcess(true, 0, stdout)
        val clock = AtomicLong(0)
        val cleanups = AtomicInteger()
        try {
            val result = withContext(Dispatchers.IO) {
                LocalProcessSupervisor.captureOwned(
                    process = process,
                    timeoutMs = 50,
                    cleanup = { cleanups.incrementAndGet() },
                    nowNanos = clock::get,
                    pause = { clock.addAndGet(it * 1_000_000L); yield() },
                )
            }
            assertEquals(124, result.exitCode)
            assertTrue(result.timedOut)
            assertEquals(1, cleanups.get())
            assertEquals(1, process.destroyCalls.get())
            assertFalse(process.isAlive)
        } finally {
            process.releaseFakeResources()
            stdout.assertReleased()
        }
    }

    @Test(timeout = 10_000)
    fun quietLivingRootWaitingForInputSurvivesDrainIntervalsAndStillCancels() = runBlocking {
        val stdout = HeldInput()
        val process = FakeProcess(true, 0, stdout)
        val input = ProcessInputBuffer()
        val clock = AtomicLong(0)
        val cleanups = AtomicInteger()
        val reachedObservation = AtomicBoolean(false)
        try {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    LocalProcessSupervisor.captureOwned(
                        process = process,
                        timeoutMs = 0,
                        streamInput = input,
                        cleanup = { cleanups.incrementAndGet() },
                        nowNanos = clock::get,
                        pause = {
                            val elapsed = clock.addAndGet(250_000_000L)
                            if (elapsed >= DIAGNOSTIC_BOUND_NANOS) {
                                assertTrue(process.isAlive)
                                assertFalse(input.isClosed)
                                assertEquals(0, cleanups.get())
                                assertEquals(0, process.destroyCalls.get())
                                reachedObservation.set(true)
                                throw RequestedTestCancellation()
                            }
                            yield()
                        },
                    )
                }
            }
            assertTrue("Quiet input-waiting process was ended before user cancellation", reachedObservation.get())
            assertTrue(generateSequence(outcome.exceptionOrNull()) { it.cause }.any { it is RequestedTestCancellation })
            assertEquals(1, cleanups.get())
            assertEquals(1, process.destroyCalls.get())
            assertFalse(process.isAlive)
            assertTrue(input.isClosed)
            assertTrue(input.isAborted)
        } finally {
            input.abort()
            process.releaseFakeResources()
            stdout.assertReleased()
        }
    }

    private class DiagnosticBoundReached : IllegalStateException("Exited fake root still waiting at diagnostic drain bound")
    private class RequestedTestCancellation : CancellationException("Explicit fake-only test cancellation")

    private class HeldInput : InputStream() {
        private val released = CountDownLatch(1)
        private val closed = AtomicBoolean(false)
        override fun read(): Int { released.await(); return -1 }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = read()
        fun release() { released.countDown() }
        override fun close() { closed.set(true); release() }
        fun assertReleased() {
            assertTrue("Fake output was not released", released.await(1, TimeUnit.SECONDS))
            assertTrue("Fake output was not closed", closed.get())
        }
    }

    /** Process API fake only: every lifecycle and stream operation stays in this JVM. */
    private class FakeProcess(
        aliveInitially: Boolean,
        private val naturalExit: Int,
        private val stdout: InputStream,
    ) : Process() {
        private val alive = AtomicBoolean(aliveInitially)
        private val stdin = ByteArrayOutputStream()
        private val stderr = ByteArrayInputStream(byteArrayOf())
        private val workers = ConcurrentHashMap.newKeySet<Thread>()
        val destroyCalls = AtomicInteger()
        private fun recordWorker() {
            val current = Thread.currentThread()
            if (current.name == "droide-process-output" || current.name == "droide-process-input") workers.add(current)
        }
        override fun getOutputStream() = stdin.also { recordWorker() }
        override fun getInputStream() = stdout.also { recordWorker() }
        override fun getErrorStream() = stderr.also { recordWorker() }
        override fun isAlive() = alive.get()
        override fun waitFor(): Int {
            check(!alive.get()) { "Fake waitFor must not block" }
            return exitValue()
        }
        override fun waitFor(timeout: Long, unit: TimeUnit) = !alive.get()
        override fun exitValue(): Int {
            check(!alive.get()) { "Fake root is still alive" }
            return if (destroyCalls.get() == 0) naturalExit else 143
        }
        override fun destroy() { destroyCalls.incrementAndGet(); alive.set(false) }
        override fun destroyForcibly(): Process = apply { destroy() }
        fun releaseFakeResources() {
            stdout.close(); stdin.close(); stderr.close()
            workers.forEach { worker ->
                worker.join(1_000)
                assertFalse("Fake worker was not joined: ${worker.name}", worker.isAlive)
            }
        }
    }

    companion object {
        private const val DIAGNOSTIC_BOUND_NANOS = 10_000_000_000L
    }
}
