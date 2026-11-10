package com.baystudio.droide.core

import com.flyfishxu.kadb.shell.AdbShellPacket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Actual owner completion lifecycle; channel transport is scripted, not real ADB. */
class RemoteTransportCompletionTest {
    @Test(timeout = 12_000) fun cancelledParentConstructionDoesNotBlockOnTransportClose() = runBlocking {
        val scope = CoroutineScope(SupervisorJob().apply { cancel() } + Dispatchers.IO)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val constructed = CountDownLatch(1)
        val result = AtomicReference<RemoteHostedStdioProcess>(); val error = AtomicReference<Throwable>()
        val closes = AtomicInteger()
        val channel = object : RemoteProcessChannel {
            override fun read(): AdbShellPacket = error("Cancelled reader must not start")
            override fun write(bytes: ByteArray) = Unit
            override fun closeStdin() = Unit
            override fun close() { closes.incrementAndGet(); entered.countDown(); release.await() }
        }
        val caller = Thread {
            try { result.set(RemoteHostedStdioProcess(channel, scope) { CompletableDeferred(Unit) }) }
            catch (failure: Throwable) { error.set(failure) }
            finally { constructed.countDown() }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue("Already-cancelled construction must not run blocked transport close inline", constructed.await(500, TimeUnit.MILLISECONDS))
            assertNull(error.get()); val process = checkNotNull(result.get())
            val receipt = async { runCatching { process.awaitExit() } }
            delay(100); assertFalse("Transport dispatch alone is not completion", receipt.isCompleted)
            release.countDown(); assertTrue(withTimeout(2_000) { receipt.await() }.isFailure)
            process.close(); assertEquals(1, closes.get()); assertTrue(process.isAlive); assertNull(process.exitCode)
        } finally { release.countDown(); caller.join(2_000); scope.cancel() }
    }

    @Test(timeout = 12_000) fun naturalExitDetachesReaderButKeepsTransportReceiptPending() = runBlocking {
        val parent = SupervisorJob(); val scope = CoroutineScope(parent + Dispatchers.IO)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val closes = AtomicInteger()
        val channel = object : RemoteProcessChannel {
            override fun read(): AdbShellPacket = AdbShellPacket.Exit(byteArrayOf(0))
            override fun write(bytes: ByteArray) = Unit
            override fun closeStdin() = Unit
            override fun close() { closes.incrementAndGet(); entered.countDown(); release.await() }
        }
        val process = RemoteHostedStdioProcess(channel, scope) { CompletableDeferred(Unit) }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            withTimeout(500) { while (parent.children.any()) delay(5) }
            assertFalse(process.isAlive)
            val callers = (1..12).map { Thread { process.close() }.apply { start() } }
            callers.forEach { it.join(1_000); assertFalse(it.isAlive) }
            parent.cancel()
            assertEquals(1, closes.get())
            val receipt = async { process.awaitExit() }; delay(100); assertFalse(receipt.isCompleted)
            release.countDown(); assertEquals(0, withTimeout(2_000) { receipt.await() })
            assertEquals(1, closes.get())
        } finally { release.countDown(); process.close(); parent.cancel(); withTimeout(3_000) { parent.join() } }
    }
    @Test(timeout = 10_000) fun failedTransportCloseCannotProduceSuccessfulReceipt() = verifyFailure(java.io.IOException("transport close failure"))
    @Test(timeout = 10_000) fun unexpectedWorkerThrowableIsRecordedInsteadOfLeavingReceiptPending() = verifyFailure(AssertionError("unexpected transport error"))

    private fun verifyFailure(expected: Throwable) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val closes = AtomicInteger()
        val channel = object : RemoteProcessChannel {
            override fun read(): AdbShellPacket = AdbShellPacket.Exit(byteArrayOf(0))
            override fun write(bytes: ByteArray) = Unit
            override fun closeStdin() = Unit
            override fun close() { closes.incrementAndGet(); throw expected }
        }
        val process = RemoteHostedStdioProcess(channel, scope) { CompletableDeferred(Unit) }
        try {
            val failure = withTimeout(2_000) { runCatching { process.awaitExit() }.exceptionOrNull() }
            assertNotNull(failure)
            if (expected is Exception) {
                assertEquals(expected.javaClass, failure?.javaClass)
                assertEquals(expected.message, failure?.message)
            } else {
                assertTrue("Async owner observers require an Exception receipt, not a raw Error", failure is IllegalStateException)
                assertEquals("Remote transport close failed", failure?.message)
                assertTrue(generateSequence(failure) { it.cause }.any { it === expected })
            }
            assertFalse(process.isAlive)
            process.close()
            assertTrue(withTimeout(2_000) { runCatching { process.awaitExit() } }.isFailure)
            assertEquals(1, closes.get())
        } finally { scope.cancel(); withTimeout(3_000) { scope.coroutineContext.job.join() } }
    }

}
