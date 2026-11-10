package com.baystudio.droide.core

import com.flyfishxu.kadb.shell.AdbShellPacket
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** A scripted shell transport, not a real Android/ADB test. */
class RemoteHostedProcessReceiptTest {
    private class Channel : RemoteProcessChannel {
        val packets = LinkedBlockingQueue<Any>()
        val stdinClosed = java.util.concurrent.CountDownLatch(1)
        override fun read(): AdbShellPacket = when (val next = packets.poll(3, TimeUnit.SECONDS)) {
            is AdbShellPacket -> next
            is Exception -> throw next
            else -> error("Fixture packet timeout")
        }
        override fun write(bytes: ByteArray) = Unit
        override fun closeStdin() { stdinClosed.countDown() }
        override fun close() = Unit
    }

    @Test(timeout = 5_000) fun stopCommandSuccessDoesNotSubstituteForRemoteExit() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val channel = Channel()
        val process = RemoteHostedStdioProcess(channel, scope) { CompletableDeferred(Unit) }
        try {
            process.close()
            assertTrue(channel.stdinClosed.await(2, TimeUnit.SECONDS))
            assertTrue(process.isAlive)
            assertNull(process.exitCode)
            val receipt = async { process.awaitExit() }
            yield(); assertFalse(receipt.isCompleted)
            channel.packets.put(AdbShellPacket.Exit(byteArrayOf(143.toByte())))
            assertEquals(143, receipt.await())
            assertFalse(process.isAlive)
        } finally { scope.cancel() }
    }

    @Test(timeout = 5_000) fun transportFailureDoesNotManufactureExitCode() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val channel = Channel()
        val process = RemoteHostedStdioProcess(channel, scope) { CompletableDeferred(Unit) }
        try {
            channel.packets.put(IOException("connection lost"))
            assertTrue(runCatching { process.awaitExit() }.isFailure)
            assertTrue(process.isAlive)
            assertNull(process.exitCode)
        } finally { scope.cancel() }
    }

    @Test(timeout = 5_000) fun remoteExitStillWaitsForLeaseCleanupAndReportsItsFailure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val channel = Channel(); val cleanup = CompletableDeferred<Unit>()
        val process = RemoteHostedStdioProcess(channel, scope) { cleanup }
        try {
            val receipt = async { runCatching { process.awaitExit() } }
            channel.packets.put(AdbShellPacket.Exit(byteArrayOf(0)))
            yield(); assertFalse(receipt.isCompleted)
            cleanup.completeExceptionally(IOException("lease cleanup failed"))
            assertTrue(receipt.await().isFailure)
            assertFalse(process.isAlive)
        } finally { scope.cancel() }
    }

    @Test(timeout = 5_000) fun cancelledReaderBeforeStartupProducesUnconfirmedReceipt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob().apply { cancel() } + Dispatchers.IO)
        val process = RemoteHostedStdioProcess(Channel(), scope) { CompletableDeferred(Unit) }
        assertTrue(runCatching { process.awaitExit() }.isFailure)
        assertTrue(process.isAlive)
        assertNull(process.exitCode)
    }
}
