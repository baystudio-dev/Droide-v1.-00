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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalProcessSupervisorStartupBaselineTest {
    @Test(timeout = 10_000)
    fun clockInitializationFailureStillReleasesOwnedProcessAndInput() = runBlocking<Unit> {
        verifyEarlyFailure(IOException("clock unavailable"))
    }

    @Test(timeout = 10_000)
    fun initializationErrorIsPreservedAfterOwnedCleanup() = runBlocking<Unit> {
        verifyEarlyFailure(StartupResourceError())
    }

    @Test(timeout = 10_000)
    fun cleanupFailureDoesNotMaskInitializationFailure() = runBlocking<Unit> {
        verifyEarlyFailure(IOException("initialization failed"), cleanupError = IOException("lease cleanup failed"))
    }

    @Test(timeout = 10_000)
    fun receiptObserverFailureDoesNotMaskInitializationFailure() = runBlocking<Unit> {
        verifyEarlyFailure(IOException("initialization failed"), receiptError = IOException("receipt failed"))
    }

    private suspend fun verifyEarlyFailure(original: Throwable, cleanupError: Throwable? = null, receiptError: Throwable? = null) {
        val process = SupervisorStartupProcess()
        val input = ProcessInputBuffer().apply { offer("pending input") }
        val cleanups = AtomicInteger()
        val receipts = mutableListOf<Throwable?>()
        try {
            val outcome = runCatching {
                LocalProcessSupervisor.captureOwned(process, streamInput = input,
                    nowNanos = { throw original },
                    cleanup = { cleanups.incrementAndGet(); cleanupError?.let { throw it } },
                    onCleanupCompleted = { receipts.add(it); receiptError?.let { throw it } })
            }
            assertSame("Original initialization failure must survive cleanup", original, outcome.exceptionOrNull())
            assertEquals("Owned lease must be released even before any worker exists", 1, cleanups.get())
            assertEquals("Cleanup receipt must be delivered once", 1, receipts.size)
            assertSame(cleanupError, receipts.single())
            assertTrue("Queued input must be aborted", input.isAborted)
            assertFalse("Spawned process must not be abandoned", process.isAlive)
            assertEquals(1, process.destroyCalls.get())
            assertTrue(process.stdout.closed.get())
            assertTrue(process.stdinClosed.get())
            cleanupError?.let { assertTrue(original.suppressed.any { error -> error === it }) }
            receiptError?.let { assertTrue(original.suppressed.any { error -> error === it }) }
        } finally { input.abort(); process.releaseAndJoin() }
    }

    private class StartupResourceError : Error("Synthetic startup resource failure; no memory exhaustion")
}

internal class SupervisorStartupProcess : Process() {
    val stdout = HeldStartupPipe()
    val stdinClosed = AtomicBoolean()
    private val stdin = object : ByteArrayOutputStream() {
        override fun close() { stdinClosed.set(true); super.close() }
    }
    private val stderr = ByteArrayInputStream(byteArrayOf())
    private val alive = AtomicBoolean(true)
    private val workers = ConcurrentHashMap.newKeySet<Thread>()
    val destroyCalls = AtomicInteger()
    private fun recordWorker() {
        val current = Thread.currentThread()
        if (current.name.startsWith("droide-process-")) workers.add(current)
    }
    override fun getInputStream(): InputStream = stdout.also { recordWorker() }
    override fun getOutputStream() = stdin.also { recordWorker() }
    override fun getErrorStream() = stderr.also { recordWorker() }
    override fun isAlive() = alive.get()
    override fun waitFor(): Int { check(!isAlive); return 143 }
    override fun waitFor(timeout: Long, unit: TimeUnit) = !isAlive
    override fun exitValue(): Int { check(!isAlive); return 143 }
    override fun destroy() { destroyCalls.incrementAndGet(); alive.set(false) }
    override fun destroyForcibly(): Process = apply { destroy() }
    fun releaseAndJoin() {
        alive.set(false)
        stdout.close(); stdin.close(); stderr.close()
        workers.forEach { thread -> thread.join(1_000); assertFalse("Fake I/O worker remains live", thread.isAlive) }
    }
}

internal class HeldStartupPipe : InputStream() {
    val entered = CountDownLatch(1)
    val closed = AtomicBoolean()
    private val released = CountDownLatch(1)
    override fun read(): Int { entered.countDown(); released.await(); return -1 }
    override fun read(bytes: ByteArray, offset: Int, length: Int) = read()
    override fun close() { closed.set(true); released.countDown() }
}
