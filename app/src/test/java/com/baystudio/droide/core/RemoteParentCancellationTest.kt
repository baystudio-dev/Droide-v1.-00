package com.baystudio.droide.core

import com.flyfishxu.kadb.shell.AdbShellPacket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Real production owner with a deliberately blocked scripted read, not live ADB. */
class RemoteParentCancellationTest {
    @Test(timeout = 12_000) fun cancellationDispatchesStopBeforeBlockedReaderReturns() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val cleanupStarted = CountDownLatch(1); val calls = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val channel = object : RemoteProcessChannel {
            override fun read(): AdbShellPacket { entered.countDown(); release.await(); return AdbShellPacket.Exit(byteArrayOf(143.toByte())) }
            override fun write(bytes: ByteArray) = Unit
            override fun closeStdin() = Unit
            override fun close() = Unit
        }
        val process = RemoteHostedStdioProcess(channel, scope) { terminate ->
            assertTrue("No Exit packet has arrived at cancellation", terminate)
            calls.incrementAndGet(); cleanupStarted.countDown(); CompletableDeferred(Unit)
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            scope.cancel()
            assertTrue("Parent cancellation must request cleanup without waiting for blocked read", cleanupStarted.await(500, TimeUnit.MILLISECONDS))
            assertEquals(1L, release.count); assertTrue(process.isAlive); assertNull(process.exitCode)
            val receipt = async { process.awaitExit() }; delay(100); assertFalse(receipt.isCompleted)
            release.countDown()
            assertEquals(143, withTimeout(2_000) { receipt.await() }); assertEquals(1, calls.get())
            assertFalse(process.isAlive)
        } finally { release.countDown(); scope.cancel(); withTimeout(3_000) { scope.coroutineContext.job.join() } }
    }
    @Test(timeout = 10_000) fun naturalCompletionDetachesSentinelWithoutRequestingStop() = runBlocking {
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.IO)
        val stopCalls = AtomicInteger(); val cleanupCalls = AtomicInteger()
        val channel = object : RemoteProcessChannel {
            override fun read(): AdbShellPacket = AdbShellPacket.Exit(byteArrayOf(0))
            override fun write(bytes: ByteArray) = Unit
            override fun closeStdin() { stopCalls.incrementAndGet() }
            override fun close() = Unit
        }
        val process = RemoteHostedStdioProcess(channel, scope) { terminate ->
            assertFalse(terminate); cleanupCalls.incrementAndGet(); CompletableDeferred(Unit)
        }
        try {
            assertEquals(0, withTimeout(2_000) { process.awaitExit() })
            assertEquals("Both reader and cancellation sentinel must detach", 0, parent.children.count())
            assertTrue(parent.isActive)
            parent.cancel(); withTimeout(2_000) { parent.join() }
            process.awaitExit()
            assertEquals(0, stopCalls.get()); assertEquals(1, cleanupCalls.get())
        } finally { parent.cancel() }
    }

    @Test(timeout = 12_000) fun cancellationWithoutExitRemainsUnconfirmedAfterReadUnblocks() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val cleanupStarted = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val channel = object : RemoteProcessChannel {
            override fun read(): AdbShellPacket { entered.countDown(); release.await(); return AdbShellPacket.StdOut(byteArrayOf(65)) }
            override fun write(bytes: ByteArray) = Unit
            override fun closeStdin() = Unit
            override fun close() = Unit
        }
        val process = RemoteHostedStdioProcess(channel, scope) { terminate ->
            assertTrue(terminate); cleanupStarted.countDown(); CompletableDeferred(Unit)
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS)); scope.cancel()
            assertTrue(cleanupStarted.await(2, TimeUnit.SECONDS)); release.countDown()
            assertTrue(withTimeout(2_000) { runCatching { process.awaitExit() } }.isFailure)
            assertTrue(process.isAlive); assertNull(process.exitCode)
        } finally { release.countDown(); scope.cancel(); withTimeout(3_000) { scope.coroutineContext.job.join() } }
    }

    @Test(timeout = 12_000) fun explicitStopRacingCancellationDispatchesCleanupOnlyOnce() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val cleanupStarted = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val calls = AtomicInteger(); val stdinCloses = AtomicInteger()
        val channel = object : RemoteProcessChannel {
            override fun read(): AdbShellPacket { entered.countDown(); release.await(); return AdbShellPacket.Exit(byteArrayOf(143.toByte())) }
            override fun write(bytes: ByteArray) = Unit
            override fun closeStdin() { stdinCloses.incrementAndGet() }
            override fun close() = Unit
        }
        val process = RemoteHostedStdioProcess(channel, scope) { terminate ->
            assertTrue(terminate); calls.incrementAndGet(); cleanupStarted.countDown(); CompletableDeferred(Unit)
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val stop = Thread { process.close() }.apply { start() }
            scope.cancel(); stop.join(1_000); assertFalse(stop.isAlive)
            assertTrue(cleanupStarted.await(2, TimeUnit.SECONDS))
            release.countDown(); assertEquals(143, withTimeout(2_000) { process.awaitExit() })
            process.close(); assertEquals(1, calls.get()); assertTrue(stdinCloses.get() <= 1)
        } finally { release.countDown(); scope.cancel(); withTimeout(3_000) { scope.coroutineContext.job.join() } }
    }

    @Test(timeout = 10_000) fun alreadyCancelledParentStartsStopWithoutExplicitClose() = runBlocking {
        val parent = SupervisorJob().apply { cancel() }
        val stopStarted = CountDownLatch(1); val calls = AtomicInteger()
        val channel = object : RemoteProcessChannel {
            override fun read(): AdbShellPacket = error("Cancelled reader must not start")
            override fun write(bytes: ByteArray) = Unit
            override fun closeStdin() { stopStarted.countDown() }
            override fun close() = Unit
        }
        val process = RemoteHostedStdioProcess(channel, CoroutineScope(parent + Dispatchers.IO)) { terminate ->
            assertTrue(terminate); calls.incrementAndGet(); CompletableDeferred(Unit)
        }
        assertTrue(stopStarted.await(2, TimeUnit.SECONDS))
        assertTrue(withTimeout(2_000) { runCatching { process.awaitExit() } }.isFailure)
        assertTrue(process.isAlive); assertNull(process.exitCode); assertEquals(1, calls.get())
        withTimeout(2_000) { parent.join() }
    }

}
