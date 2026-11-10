package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class McpStdioIoPoolTest {
    @Test(timeout = 10_000)
    fun cancelledQueuedWriteIsRemovedWithoutTransmittingAStaleFrame() = runBlocking {
        withPool(2) { pool, scope, own ->
            val blocked = ProcessFixture(blockWrite = true)
            val first = own(blocked)
            val writing = scope.async { first.write("first", deadline()) }
            assertTrue(blocked.writeEntered.await(1, TimeUnit.SECONDS))

            val queued = ProcessFixture()
            val second = own(queued)
            val waiting = scope.async { second.write("must not be sent", deadline()) }
            eventually { pool.queuedWrites == 1 }
            waiting.cancel()
            withTimeout(500) { waiting.join() }
            eventually { pool.queuedWrites == 0 && pool.activeSessions == 1 }
            assertEquals(0, queued.writes.get())
            assertEquals(1, queued.closes.get())

            blocked.releaseWrite.countDown()
            writing.await()
            assertEquals("Removing the queued frame must not close an unrelated owner", 0, blocked.closes.get())
            assertEquals(0, queued.writes.get())
        }
    }

    @Test(timeout = 10_000)
    fun blockedWriterDeadlineReturnsButKeepsAdmissionUntilTheWriterReallyExits() = runBlocking {
        withPool(1) { pool, scope, own ->
            val process = ProcessFixture(blockWrite = true)
            val io = own(process)
            val writing = scope.async { runCatching { io.write("blocked", deadline(150)) } }
            assertTrue(process.writeEntered.await(1, TimeUnit.SECONDS))
            val failure = withTimeout(1_000) { writing.await().exceptionOrNull() }
            assertTrue("Deadline must escape an uninterruptible stream", failure is McpTimeoutException)
            assertTrue(process.closeEntered.await(1, TimeUnit.SECONDS))
            assertEquals(1, pool.activeSessions)
            var spawned = false
            val rejected = runCatching { pool.start { spawned = true; ProcessFixture() } }.exceptionOrNull()
            assertNotNull(rejected)
            assertFalse("Saturation must reject before invoking the process host", spawned)

            process.releaseWrite.countDown()
            eventually { pool.activeSessions == 0 }
            val recovered = own(ProcessFixture())
            recovered.write("new owner", deadline())
        }
    }

    @Test(timeout = 10_000)
    fun blockingCleanupReturnsPromptlyAndRetainsItsReservation() = runBlocking {
        withPool(1) { pool, scope, own ->
            val process = ProcessFixture(blockClose = true)
            val io = own(process)
            withTimeout(500) { scope.async { io.close() }.await() }
            assertTrue(process.closeEntered.await(1, TimeUnit.SECONDS))
            assertEquals(1, pool.activeSessions)
            var spawned = false
            assertTrue(runCatching { pool.start { spawned = true; ProcessFixture() } }.isFailure)
            assertFalse(spawned)
            process.releaseClose.countDown()
            eventually { pool.activeSessions == 0 }
        }
    }

    @Test(timeout = 10_000)
    fun queuedWriterDeadlineUsesItsOriginalBudget() = runBlocking {
        withPool(2) { pool, scope, own ->
            val process = ProcessFixture(blockWrite = true)
            val first = own(process)
            val writing = scope.async { first.write("occupy sole writer", deadline()) }
            assertTrue(process.writeEntered.await(1, TimeUnit.SECONDS))
            val queued = ProcessFixture()
            val second = own(queued)
            val failure = withTimeout(1_000) { runCatching { second.write("expired", deadline(120)) }.exceptionOrNull() }
            assertTrue(failure is McpTimeoutException)
            eventually { pool.queuedWrites == 0 }
            process.releaseWrite.countDown()
            writing.await()
            assertEquals(0, queued.writes.get())
        }
    }

    @Test(timeout = 10_000)
    fun startFailureReturnsTheAdmissionAndSuccessfulSlowWriteDoesNotCloseTheOwner() = runBlocking {
        withPool(1) { pool, scope, own ->
            assertTrue(runCatching { pool.start { throw IOException("fixture spawn failure") } }.isFailure)
            assertEquals(0, pool.activeSessions)
            val process = ProcessFixture(blockWrite = true)
            val io = own(process)
            val writing = scope.async { io.write("legitimate write", deadline()) }
            assertTrue(process.writeEntered.await(1, TimeUnit.SECONDS))
            delay(180)
            process.releaseWrite.countDown()
            writing.await()
            assertEquals(1, process.writes.get())
            assertEquals(0, process.closes.get())
            assertTrue(process.isAlive)
        }
    }

    @Test(timeout = 10_000)
    fun streamAcquisitionFailureStillClosesTheSpawnedProcessAndReturnsAdmission() = runBlocking {
        withPool(1) { pool, _, own ->
            val broken = ProcessFixture(throwFirstStdoutRead = true)
            assertTrue(runCatching { own(broken) }.isFailure)
            eventually { pool.activeSessions == 0 }
            assertEquals(1, broken.closes.get())
            own(ProcessFixture()).write("healthy replacement", deadline())
        }
    }

    private suspend fun withPool(
        capacity: Int,
        block: suspend (McpStdioIoPool, CoroutineScope, suspend (ProcessFixture) -> McpStdioIo) -> Unit,
    ) {
        val pool = McpStdioIoPool(capacity, blockingWorkers = 1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val processes = mutableListOf<ProcessFixture>()
        val sessions = mutableListOf<McpStdioIo>()
        try {
            block(pool, scope) { process ->
                processes += process
                pool.start { process }.also { sessions += it }
            }
        } finally {
            processes.forEach { it.releaseWrite.countDown(); it.releaseClose.countDown() }
            sessions.forEach { it.close() }
            scope.cancel()
            withTimeout(2_000) { scope.coroutineContext[Job]!!.join() }
            eventually { pool.activeSessions == 0 }
            pool.close()
        }
    }

    private fun deadline(ms: Long = 5_000) = System.nanoTime() + ms * 1_000_000L
    private suspend fun eventually(condition: () -> Boolean) = withTimeout(2_000) {
        while (!condition()) delay(5)
    }

    private class ProcessFixture(
        blockWrite: Boolean = false,
        blockClose: Boolean = false,
        throwFirstStdoutRead: Boolean = false,
    ) : HostedStdioProcess {
        val writeEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(if (blockWrite) 1 else 0)
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(if (blockClose) 1 else 0)
        val writes = AtomicInteger()
        val closes = AtomicInteger()
        private val alive = AtomicBoolean(true)
        override val stdin = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                writeEntered.countDown()
                awaitUninterruptibly(releaseWrite)
                if (!alive.get()) throw IOException("closed")
                writes.incrementAndGet()
            }
        }
        private val failStdout = AtomicBoolean(throwFirstStdoutRead)
        private val stdoutBytes = ByteArrayInputStream(byteArrayOf())
        override val stdout: ByteArrayInputStream get() {
            if (failStdout.compareAndSet(true, false)) throw IOException("fixture stream acquisition failure")
            return stdoutBytes
        }
        override val stderr = ByteArrayInputStream(byteArrayOf())
        override val isAlive: Boolean get() = alive.get()
        override val exitCode: Int? get() = if (alive.get()) null else 143
        override suspend fun awaitExit() = 143
        override fun close() {
            closes.incrementAndGet()
            closeEntered.countDown()
            awaitUninterruptibly(releaseClose)
            alive.set(false)
            // Deliberately do not releaseWrite: prove a returned close is not mistaken for a terminated Java
            // writer, even if the provider's close cannot unblock its stream.
        }
        private fun awaitUninterruptibly(latch: CountDownLatch) {
            while (true) try { latch.await(); return } catch (_: InterruptedException) { }
        }
    }
}
