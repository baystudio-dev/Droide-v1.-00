package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Isolated strict teardown tests. Does not certify Android routes or descendants. */
class OwnedProcessTerminationTest {
    @Test fun successfulCompletionIsSharedByLateAndRepeatedObservers() {
        val eachPipe = List(3) { AtomicInteger() }
        val process = DeadProcess { index -> check(eachPipe[index].incrementAndGet() == 1) { "duplicate pipe close" } }
        OwnedProcessTermination.terminate(process)
        val pool = Executors.newFixedThreadPool(8)
        try {
            (1..24).map { pool.submit { OwnedProcessTermination.terminate(process) } }
                .forEach { it.get(3, TimeUnit.SECONDS) }
            OwnedProcessTermination.terminate(process)
            assertEquals(3, process.closes.get())
            assertEquals(1, process.timedWaits.get())
        } finally { pool.shutdownNow() }
    }

    @Test fun timedWaitFailureIsNotHiddenOrRetried() {
        val original = IOException("timed wait failed")
        val process = DeadProcess()
        process.waitFailure = original
        assertSame(original, failure { OwnedProcessTermination.terminate(process) })
        assertEquals(2, process.timedWaits.get())
        assertSame(original, failure { OwnedProcessTermination.terminate(process) })
        assertEquals(2, process.timedWaits.get())
        assertEquals(0, process.closes.get())
    }

    @Test fun allPipeCloseErrorsAreReportedAndRemainSticky() {
        val errors = List(3) { IOException("pipe $it close failed") }
        val process = DeadProcess { index -> throw errors[index] }
        val result = failure { OwnedProcessTermination.terminate(process) }
        assertSame(errors[0], result)
        assertTrue(result.suppressed.contains(errors[1]))
        assertTrue(result.suppressed.contains(errors[2]))
        assertEquals(3, process.closes.get())
        assertSame(result, failure { OwnedProcessTermination.terminate(process) })
        assertEquals(3, process.closes.get())
    }

    @Test fun exactRealChildReapsSuccessfullyWithoutTouchingSibling() {
        val raw = child()
        val sibling = child()
        try {
            OwnedProcessTermination.terminate(raw)
            assertFalse(raw.isAlive)
            raw.exitValue()
            assertTrue(sibling.isAlive)
        } finally { force(raw); force(sibling) }
    }

    @Test fun signalFailureIsPreservedEvenIfForceReapsExactChild() {
        val raw = child()
        val sibling = child()
        val original = IOException("first signal failed")
        val destroyCalls = AtomicInteger()
        val forceCalls = AtomicInteger()
        val process = object : ForwardingProcess(raw) {
            override fun destroy() { destroyCalls.incrementAndGet(); throw original }
            override fun destroyForcibly(): Process {
                forceCalls.incrementAndGet()
                raw.destroyForcibly()
                return this
            }
        }
        try {
            assertSame(original, failure { OwnedProcessTermination.terminate(process) })
            assertFalse(raw.isAlive)
            assertTrue(sibling.isAlive)
            assertEquals(1, destroyCalls.get())
            assertEquals(1, forceCalls.get())
            assertSame(original, failure { OwnedProcessTermination.terminate(process) })
            assertEquals(1, forceCalls.get())
        } finally { force(raw); force(sibling) }
    }

    @Test fun deadObservationWithoutSuccessfulWaitStillFails() {
        val process = object : DeadProcess() {
            override fun waitFor(timeout: Long, unit: TimeUnit) = false
        }
        assertFalse(process.isAlive)
        assertEquals(0, process.exitValue())
        val result = failure { OwnedProcessTermination.terminate(process) }
        assertTrue(result.message.orEmpty().contains("did not confirm exit"))
        assertEquals(0, process.closes.get())
    }

    @Test fun missingExitNeverClosesPipesOrReportsSuccess() {
        val process = object : DeadProcess() {
            override fun isAlive() = true
            override fun waitFor(timeout: Long, unit: TimeUnit) = false
            override fun destroy() = Unit
            override fun destroyForcibly(): Process = this
        }
        val result = failure { OwnedProcessTermination.terminate(process) }
        assertTrue(result.message.orEmpty().contains("did not confirm exit"))
        assertEquals(0, process.closes.get())
    }

    @Test fun blockedCloseHasBoundedObserversAndOneRetainedAttempt() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val process = DeadProcess { index -> if (index == 0) { entered.countDown(); release.await() } }
        val pool = Executors.newFixedThreadPool(12)
        try {
            val ready = CountDownLatch(12)
            val go = CountDownLatch(1)
            val futures = (1..12).map {
                pool.submit<Throwable> {
                    ready.countDown(); go.await()
                    failure { OwnedProcessTermination.terminate(process) }
                }
            }
            assertTrue(ready.await(3, TimeUnit.SECONDS))
            go.countDown()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            futures.forEach { assertTrue(it.get(5, TimeUnit.SECONDS).message.orEmpty().contains("timed out")) }
            assertEquals(1, process.closes.get())
            assertTrue(failure { OwnedProcessTermination.terminate(process) }.message.orEmpty().contains("timed out"))
            assertEquals(1, process.closes.get())
            release.countDown()
            awaitSuccessfulObservation(process)
            assertEquals(3, process.closes.get())
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun interruptedObserverDoesNotInterruptOrDuplicateCleanup() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val process = DeadProcess { index -> if (index == 0) { entered.countDown(); release.await() } }
        val result = AtomicReference<Throwable>()
        val interrupted = AtomicBoolean()
        val observer = Thread {
            result.set(failure { OwnedProcessTermination.terminate(process) })
            interrupted.set(Thread.currentThread().isInterrupted)
        }
        try {
            observer.start()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            observer.interrupt()
            observer.join(1_000)
            assertFalse(observer.isAlive)
            assertTrue(result.get() is IOException)
            assertTrue(result.get().cause is InterruptedException)
            assertTrue(interrupted.get())
            assertEquals(1, process.closes.get())
            release.countDown()
            awaitSuccessfulObservation(process)
            assertEquals(3, process.closes.get())
        } finally { release.countDown(); observer.join(3_000) }
    }

    @Test fun processEqualityCannotMergeDifferentExactHandles() {
        val firstError = IOException("first owner")
        val secondError = IOException("second owner")
        fun peer(error: IOException) = object : DeadProcess({ throw error }) {
            override fun equals(other: Any?) = other is Process
            override fun hashCode() = 1
        }
        val first = peer(firstError)
        val second = peer(secondError)
        assertSame(firstError, failure { OwnedProcessTermination.terminate(first) })
        assertSame(secondError, failure { OwnedProcessTermination.terminate(second) })
        assertEquals(3, first.closes.get())
        assertEquals(3, second.closes.get())
    }

    private fun child() = ProcessBuilder("/bin/sh", "-c", "exec sleep 30").start()
    private fun force(process: Process) {
        if (process.isAlive) process.destroyForcibly()
        check(process.waitFor(3, TimeUnit.SECONDS))
    }
    private fun failure(action: () -> Unit): Throwable =
        runCatching(action).exceptionOrNull() ?: error("Cleanup unexpectedly reported success")

    private fun awaitSuccessfulObservation(process: Process) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (true) {
            val result = runCatching { OwnedProcessTermination.terminate(process) }
            if (result.isSuccess) return
            check(System.nanoTime() < deadline) { "Retained cleanup did not finish: ${result.exceptionOrNull()}" }
            Thread.sleep(5)
        }
    }

    private open class DeadProcess(private val onClose: (Int) -> Unit = {}) : Process() {
        val closes = AtomicInteger()
        val timedWaits = AtomicInteger()
        var waitFailure: Throwable? = null
        private fun close(index: Int) { closes.incrementAndGet(); onClose(index) }
        private val output = object : ByteArrayOutputStream() { override fun close() = close(0) }
        private val input = object : ByteArrayInputStream(byteArrayOf()) { override fun close() = close(1) }
        private val error = object : ByteArrayInputStream(byteArrayOf()) { override fun close() = close(2) }
        override fun getOutputStream() = output
        override fun getInputStream() = input
        override fun getErrorStream() = error
        override fun isAlive() = false
        override fun waitFor() = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            timedWaits.incrementAndGet()
            waitFailure?.let { throw it }
            return true
        }
        override fun exitValue() = 0
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
    }

    private open class ForwardingProcess(private val raw: Process) : Process() {
        override fun getOutputStream() = raw.outputStream
        override fun getInputStream() = raw.inputStream
        override fun getErrorStream() = raw.errorStream
        override fun isAlive() = raw.isAlive
        override fun waitFor() = raw.waitFor()
        override fun waitFor(timeout: Long, unit: TimeUnit) = raw.waitFor(timeout, unit)
        override fun exitValue() = raw.exitValue()
        override fun destroy() = raw.destroy()
        override fun destroyForcibly(): Process { raw.destroyForcibly(); return this }
    }
}
