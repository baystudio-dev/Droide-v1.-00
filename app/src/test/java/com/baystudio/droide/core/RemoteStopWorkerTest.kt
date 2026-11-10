package com.baystudio.droide.core

import com.flyfishxu.kadb.shell.AdbShellPacket
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Scripted transport exercising the real remote owner, not a device or ADB claim. */
class RemoteStopWorkerTest {
    private class Channel(private val closeInput: () -> Unit) : RemoteProcessChannel {
        val packets = LinkedBlockingQueue<AdbShellPacket>()
        val closeCalls = AtomicInteger()
        override fun read(): AdbShellPacket = packets.poll(5, TimeUnit.SECONDS) ?: error("Fixture packet timeout")
        override fun write(bytes: ByteArray) = Unit
        override fun closeStdin() { closeCalls.incrementAndGet(); closeInput() }
        override fun close() = Unit
    }

    @Test(timeout = 12_000) fun blockedStdinCloseDoesNotBlockStopCallerOrBecomeSuccessfulReceipt() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val returned = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val channel = Channel { entered.countDown(); release.await() }
        val process = RemoteHostedStdioProcess(channel, scope) { CompletableDeferred(Unit) }
        val caller = Thread { try { process.close() } finally { returned.countDown() } }.apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue("A stalled transport close must not block the stop caller", returned.await(500, TimeUnit.MILLISECONDS))
            process.close(); assertEquals(1, channel.closeCalls.get())
            channel.packets.put(AdbShellPacket.Exit(byteArrayOf(143.toByte())))
            val receipt = async { process.awaitExit() }
            delay(100)
            assertFalse("Remote Exit alone cannot acknowledge unfinished local stop", receipt.isCompleted)
            release.countDown()
            assertEquals(143, withTimeout(2_000) { receipt.await() })
            process.close(); assertEquals(1, channel.closeCalls.get())
        } finally {
            release.countDown(); channel.packets.offer(AdbShellPacket.Exit(byteArrayOf(143.toByte())))
            caller.join(2_000); scope.cancel()
        }
    }
    @Test(timeout = 12_000) fun concurrentCloseAndCancelledAwaitNeverReplayOwnedStop() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val channel = Channel { entered.countDown(); release.await() }
        val process = RemoteHostedStdioProcess(channel, scope) { CompletableDeferred(Unit) }
        try {
            val callers = (1..12).map { Thread { process.close() }.apply { start() } }
            callers.forEach { it.join(1_000); assertFalse(it.isAlive) }
            assertTrue(entered.await(2, TimeUnit.SECONDS)); assertEquals(1, channel.closeCalls.get())
            channel.packets.put(AdbShellPacket.Exit(byteArrayOf(0)))
            val cancelled = launch { process.awaitExit() }
            delay(50); cancelled.cancelAndJoin()
            assertEquals(1L, release.count)
            val next = async { process.awaitExit() }; delay(50); assertFalse(next.isCompleted)
            release.countDown(); assertEquals(0, withTimeout(2_000) { next.await() })
            assertEquals(1, channel.closeCalls.get())
        } finally { release.countDown(); channel.packets.offer(AdbShellPacket.Exit(byteArrayOf(0))); scope.cancel() }
    }

    @Test(timeout = 10_000) fun closeFailureRemainsFailedAfterActualExitPacket() = runBlocking {
        val entered = CountDownLatch(1)
        val expected = IOException("scripted stdin close failure")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val channel = Channel { entered.countDown(); throw expected }
        val process = RemoteHostedStdioProcess(channel, scope) { CompletableDeferred(Unit) }
        try {
            process.close(); assertTrue(entered.await(2, TimeUnit.SECONDS))
            channel.packets.put(AdbShellPacket.Exit(byteArrayOf(0)))
            val failure = withTimeout(2_000) { runCatching { process.awaitExit() }.exceptionOrNull() }
            assertNotNull(failure)
            assertEquals(expected.message, failure?.message)
            process.close(); assertEquals(1, channel.closeCalls.get())
            assertTrue(runCatching { process.awaitExit() }.isFailure)
        } finally { channel.packets.offer(AdbShellPacket.Exit(byteArrayOf(0))); scope.cancel() }
    }

    @Test(timeout = 10_000) fun stoppedParentDoesNotPreventIndependentStopWorker() = runBlocking {
        val entered = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob().apply { cancel() } + Dispatchers.IO)
        val channel = Channel { entered.countDown() }
        val process = RemoteHostedStdioProcess(channel, scope) { CompletableDeferred(Unit) }
        process.close()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        assertTrue("Canceled reader cannot manufacture an Exit packet", runCatching { process.awaitExit() }.isFailure)
        assertTrue(process.isAlive); assertNull(process.exitCode)
        process.close(); assertEquals(1, channel.closeCalls.get())
    }

    @Test(timeout = 12_000) fun exitFanoutStartsEveryRemoteStopWhileTransportClosesAreBlocked() = runBlocking {
        val entered = CountDownLatch(3); val release = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val registry = ExitHostedProcessOwners(isOpen = { false }, isCurrentOpen = { false })
        val channels = (1..3).map { Channel { entered.countDown(); release.await() } }
        val processes = channels.map { RemoteHostedStdioProcess(it, scope) { CompletableDeferred(Unit) } }
        processes.forEach(registry::retain)
        val exit = async(Dispatchers.IO) { registry.stopAndAwaitAll() }
        try {
            assertTrue("A blocked close cannot prevent later owned stop workers", entered.await(2, TimeUnit.SECONDS))
            channels.forEach { it.packets.put(AdbShellPacket.Exit(byteArrayOf(0))) }
            delay(100); assertFalse(exit.isCompleted)
            release.countDown(); withTimeout(3_000) { exit.await() }
            registry.stopAndAwaitAll()
            channels.forEach { assertEquals(1, it.closeCalls.get()) }
        } finally {
            release.countDown(); channels.forEach { it.packets.offer(AdbShellPacket.Exit(byteArrayOf(0))) }
            withTimeout(3_000) { exit.join() }; scope.cancel()
        }
    }

}
