package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
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

/** Production supervisor, in-JVM Process/pipe fakes only; no launcher or guest. */
class LocalProcessSupervisorDrainTest {
    @Test(timeout = 10_000)
    fun postExitDrainExpiresAtTwoSecondsAndNotOneMillisecondEarlier() = runBlocking {
        val pipe = HeldPipe()
        val process = FakeProcess(pipe, exit = 7)
        val clock = AtomicLong(0)
        val pauses = AtomicInteger()
        val cleanups = AtomicInteger()
        try {
            val result = withContext(Dispatchers.IO) {
                LocalProcessSupervisor.captureOwned(process, timeoutMs = 0,
                    cleanup = { cleanups.incrementAndGet() }, nowNanos = clock::get,
                    pause = {
                        assertFalse("Drain cutoff closed a pipe too early", pipe.closed.get())
                        when (pauses.incrementAndGet()) {
                            1 -> clock.set(1_999_000_000L)
                            2 -> clock.set(2_000_000_000L)
                            else -> error("The exact two-second drain boundary was not honored")
                        }
                        yield()
                    })
            }
            assertEquals(2_000L, LocalProcessSupervisor.OUTPUT_DRAIN_MS)
            assertEquals(2, pauses.get())
            assertEquals(7, result.exitCode)
            assertFalse(result.timedOut)
            assertTrue(result.outputIncomplete)
            assertTrue(result.output.contains("output incomplete"))
            assertEquals(1, cleanups.get())
            assertEquals(0, process.destroys.get())
        } finally { pipe.release(); process.releaseAndJoin() }
    }

    @Test(timeout = 10_000)
    fun delayedNaturalExitOutputDrainsWithoutLosingUnicodeOrExitStatus() = runBlocking {
        val expected = "FINAL: 日本語 😀 failed\n"
        val pipe = HeldPipe(expected.toByteArray(Charsets.UTF_8))
        val process = FakeProcess(pipe, exit = 7)
        val clock = AtomicLong(0)
        val cleanups = AtomicInteger()
        try {
            val result = withContext(Dispatchers.IO) {
                LocalProcessSupervisor.captureOwned(process, timeoutMs = 0,
                    cleanup = { cleanups.incrementAndGet() }, nowNanos = clock::get,
                    pause = {
                        clock.set(1_000_000_000L)
                        pipe.release()
                        assertTrue("Fake reader did not finish", pipe.finished.await(2, TimeUnit.SECONDS))
                        yield()
                    })
            }
            assertEquals(7, result.exitCode)
            assertEquals(expected, result.output)
            assertFalse(result.timedOut)
            assertFalse(result.outputIncomplete)
            assertEquals(1, cleanups.get())
        } finally { pipe.release(); process.releaseAndJoin() }
    }

    @Test(timeout = 10_000)
    fun cancellationDuringPostExitDrainReleasesOwnedResourcesExactlyOnce() = runBlocking {
        val pipe = HeldPipe()
        val process = FakeProcess(pipe, exit = 0)
        val input = ProcessInputBuffer()
        val cleanups = AtomicInteger()
        val cancelled = DrainCancellation()
        try {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    LocalProcessSupervisor.captureOwned(process, timeoutMs = 0, streamInput = input,
                        cleanup = { cleanups.incrementAndGet() }, nowNanos = { 0L },
                        pause = { throw cancelled })
                }
            }
            assertTrue(generateSequence(outcome.exceptionOrNull()) { it.cause }.any { it is DrainCancellation })
            assertEquals(1, cleanups.get())
            assertEquals(0, process.destroys.get())
            assertTrue(input.isAborted)
            assertTrue(pipe.closed.get())
        } finally { input.abort(); pipe.release(); process.releaseAndJoin() }
    }

    @Test(timeout = 10_000)
    fun lateBytesAfterDrainCutoffCannotReachOutputCallback() = runBlocking {
        val readingLate = CountDownLatch(1)
        val releaseLate = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val lateReturned = AtomicBoolean(false)
        val pipe = object : InputStream() {
            override fun read(): Int = error("Use buffered fake reads")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                val chunk = if (first.compareAndSet(true, false)) "prefix" else {
                    if (lateReturned.get()) return -1
                    readingLate.countDown()
                    releaseLate.await()
                    lateReturned.set(true)
                    "LATE"
                }.toByteArray(Charsets.UTF_8)
                chunk.copyInto(bytes, offset)
                return chunk.size
            }

            override fun close() = Unit
        }
        val process = FakeProcess(pipe, exit = 0)
        val clock = AtomicLong(0)
        val observed = java.util.Collections.synchronizedList(mutableListOf<String>())
        val cleanups = AtomicInteger()
        try {
            val result = withContext(Dispatchers.IO) {
                LocalProcessSupervisor.captureOwned(process, timeoutMs = 0,
                    cleanup = { cleanups.incrementAndGet() }, nowNanos = clock::get,
                    onOutput = { observed.add(it) }, pause = {
                        assertTrue(readingLate.await(2, TimeUnit.SECONDS))
                        clock.set(2_000_000_000L)
                    })
            }
            assertEquals(0, result.exitCode)
            assertFalse(result.timedOut)
            assertTrue(result.outputIncomplete)
            assertTrue(result.output.startsWith("prefix"))
            assertFalse(result.output.contains("LATE"))
            assertEquals(listOf("prefix"), observed.toList())
            releaseLate.countDown()
            process.releaseAndJoin()
            assertTrue(lateReturned.get())
            assertEquals("A read completed after freeze must not dispatch", listOf("prefix"), observed.toList())
            assertEquals(1, cleanups.get())
        } finally { releaseLate.countDown(); process.releaseAndJoin() }
    }

    @Test(timeout = 10_000)
    fun enteredObserverMayFinishLaterButNoFollowingChunkIsDelivered() = runBlocking {
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val callbackFinished = AtomicBoolean(false)
        val calls = AtomicInteger()

        val chunks = ArrayDeque(listOf("first".toByteArray(), "second".toByteArray()))
        val pipe = object : InputStream() {
            override fun read(): Int = error("Use buffered fake reads")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                val chunk = chunks.removeFirstOrNull() ?: return -1
                chunk.copyInto(bytes, offset)
                return chunk.size
            }
        }
        val process = FakeProcess(pipe, exit = 0)
        val clock = AtomicLong(0)
        try {
            val result = withContext(Dispatchers.IO) {
                LocalProcessSupervisor.captureOwned(process, timeoutMs = 0, nowNanos = clock::get,
                    onOutput = {
                        calls.incrementAndGet()
                        callbackEntered.countDown()
                        releaseCallback.await()
                        callbackFinished.set(true)
                    }, pause = {
                        assertTrue(callbackEntered.await(2, TimeUnit.SECONDS))
                        clock.set(2_000_000_000L)
                    })
            }
            assertEquals(0, result.exitCode)
            assertFalse(result.timedOut)
            assertTrue(result.outputIncomplete)
            assertFalse("The supervisor must not claim to stop an entered observer", callbackFinished.get())
            assertTrue(result.output.startsWith("first"))
            assertFalse(result.output.contains("second"))
            releaseCallback.countDown()
            process.releaseAndJoin()
            assertTrue(callbackFinished.get())
            assertEquals(1, calls.get())
        } finally { releaseCallback.countDown(); process.releaseAndJoin() }
    }

    @Test(timeout = 10_000)
    fun naturalExitObservedAtDeadlineWinsOverInventedTimeout() = runBlocking {
        val process = FakeProcess(ByteArrayInputStream(byteArrayOf()), exit = 7, initiallyAlive = true)
        val clockCalls = AtomicInteger()
        val cleanups = AtomicInteger()
        try {
            val result = withContext(Dispatchers.IO) {
                LocalProcessSupervisor.captureOwned(process, timeoutMs = 50,
                    cleanup = { cleanups.incrementAndGet() }, nowNanos = {
                        if (clockCalls.incrementAndGet() == 1) 0L else {
                            process.finish()
                            50_000_000L
                        }
                    }, pause = { yield() })
            }
            assertEquals(7, result.exitCode)
            assertFalse(result.timedOut)
            assertFalse(result.outputIncomplete)
            assertEquals(1, cleanups.get())
            assertEquals(0, process.destroys.get())
        } finally { process.releaseAndJoin() }
    }

    @Test(timeout = 10_000)
    fun cleanupFailureIsStillObservableAndIsNotRetriedAsTimeout() = runBlocking {
        val pipe = HeldPipe()
        val process = FakeProcess(pipe, exit = 0)
        val cleanups = AtomicInteger()
        try {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    LocalProcessSupervisor.captureOwned(process, timeoutMs = 50,
                        cleanup = { cleanups.incrementAndGet(); throw IOException("fake lease cleanup failed") },
                        nowNanos = { 0L }, pause = { yield() })
                }
            }
            assertTrue(generateSequence(outcome.exceptionOrNull()) { it.cause }.any {
                it is IOException && it.message == "fake lease cleanup failed"
            })
            assertEquals(1, cleanups.get())
            assertEquals(0, process.destroys.get())
            assertTrue(pipe.closed.get())
        } finally { pipe.release(); process.releaseAndJoin() }
    }

    @Test(timeout = 10_000)
    fun rootExitAndInputAbortDoNotHidePriorGenuineReaderFailure() = runBlocking {
        val readAttempted = CountDownLatch(1)
        val pipe = object : InputStream() {
            override fun read(): Int {
                readAttempted.countDown()
                throw IOException("genuine fake read failure")
            }
        }
        val process = FakeProcess(pipe, exit = 0)
        val input = ProcessInputBuffer()
        val cleanups = AtomicInteger()
        try {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    LocalProcessSupervisor.captureOwned(process, timeoutMs = 0, streamInput = input,
                        cleanup = {
                            cleanups.incrementAndGet()
                            assertTrue(input.isAborted)
                            assertTrue(readAttempted.await(2, TimeUnit.SECONDS))
                            process.joinOutputWorker()
                        }, nowNanos = { 0L }, pause = { yield() })
                }
            }
            assertTrue(generateSequence(outcome.exceptionOrNull()) { it.cause }.any {
                it is IOException && it.message == "genuine fake read failure"
            })
            assertEquals(1, cleanups.get())
            assertEquals(0, process.destroys.get())
            assertTrue(input.isAborted)
        } finally { input.abort(); process.releaseAndJoin() }
    }

    private class DrainCancellation : CancellationException("Fake user cancelled output draining")

    private class HeldPipe(content: ByteArray = byteArrayOf()) : InputStream() {
        private val gate = CountDownLatch(1)
        private val data = ByteArrayInputStream(content)
        val closed = AtomicBoolean(false)
        val finished = CountDownLatch(1)
        override fun read(): Int { gate.await(); return data.read() }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            gate.await()
            return data.read(bytes, offset, length)
        }
        fun release() { gate.countDown() }
        override fun close() { closed.set(true); release(); data.close(); finished.countDown() }
    }

    private class FakeProcess(
        private val stdout: InputStream,
        private val exit: Int,
        initiallyAlive: Boolean = false,
    ) : Process() {
        private val alive = AtomicBoolean(initiallyAlive)
        private val stdin = ByteArrayOutputStream()
        private val stderr = ByteArrayInputStream(byteArrayOf())
        private val workers = ConcurrentHashMap.newKeySet<Thread>()
        val destroys = AtomicInteger()
        private fun recordWorker() {
            val worker = Thread.currentThread()
            if (worker.name == "droide-process-output" || worker.name == "droide-process-input") workers.add(worker)
        }
        override fun getInputStream() = stdout.also { recordWorker() }
        override fun getOutputStream() = stdin.also { recordWorker() }
        override fun getErrorStream() = stderr.also { recordWorker() }
        override fun isAlive() = alive.get()
        override fun exitValue(): Int { check(!alive.get()); return if (destroys.get() == 0) exit else 143 }
        override fun waitFor(): Int = exitValue()
        override fun waitFor(timeout: Long, unit: TimeUnit) = !alive.get()
        override fun destroy() { destroys.incrementAndGet(); alive.set(false) }
        override fun destroyForcibly(): Process = apply { destroy() }
        fun finish() { alive.set(false) }
        fun joinOutputWorker() {
            val reader = workers.single { it.name == "droide-process-output" }
            reader.join(1_000)
            assertFalse("Fake reader did not record its failure", reader.isAlive)
        }
        fun releaseAndJoin() {
            stdout.close(); stdin.close(); stderr.close()
            workers.forEach { worker ->
                worker.join(1_000)
                assertFalse("Fake worker remained alive: ${worker.name}", worker.isAlive)
            }
        }
    }
}
