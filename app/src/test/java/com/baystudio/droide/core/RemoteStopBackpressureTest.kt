package com.baystudio.droide.core

import com.flyfishxu.kadb.shell.AdbShellPacket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Uses the production bounded Java pipes; only the remote packet transport is scripted. */
class RemoteStopBackpressureTest {
    @Test(timeout = 15_000) fun stopBreaksStdoutBackpressureBeforeWaitingForTransport() = verify(false)
    @Test(timeout = 15_000) fun stopBreaksStderrBackpressureBeforeWaitingForTransport() = verify(true)

    @Test(timeout = 15_000) fun stdinFailureAfterBackpressureReleaseRemainsFailedReceipt() = verify(false, true)

    private fun verify(stderr: Boolean, failStdin: Boolean = false) = runBlocking {
        val packetCount = if (stderr) 2 else 3
        val index = AtomicInteger(); val exitRead = CountDownLatch(1); val stdinEntered = CountDownLatch(1)
        val reader = AtomicReference<Thread>(); val stdinCalls = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val channel = object : RemoteProcessChannel {
            override fun read(): AdbShellPacket {
                reader.set(Thread.currentThread())
                return if (index.getAndIncrement() < packetCount) {
                    val bytes = ByteArray(64 * 1024) { 65 }
                    if (stderr) AdbShellPacket.StdError(bytes) else AdbShellPacket.StdOut(bytes)
                } else {
                    check(stdinEntered.await(8, TimeUnit.SECONDS)) { "Exit delivery waits for transport stdin close" }
                    exitRead.countDown(); AdbShellPacket.Exit(byteArrayOf(0))
                }
            }
            override fun write(bytes: ByteArray) = Unit
            override fun closeStdin() {
                stdinCalls.incrementAndGet(); stdinEntered.countDown()
                check(exitRead.await(8, TimeUnit.SECONDS)) { "Scripted transport waits for its reader" }
                if (failStdin) throw java.io.IOException("Scripted stdin close failed")
            }
            override fun close() = Unit
        }
        val process = RemoteHostedStdioProcess(channel, scope) { CompletableDeferred(Unit) }
        try {
            withTimeout(2_000) {
                while (reader.get()?.stackTrace?.any { it.className == "java.io.PipedInputStream" &&
                        it.methodName == "awaitSpace" } != true || index.get() < packetCount) delay(5)
            }
            assertEquals("Exit packet must still be behind the saturated pipe", 1L, exitRead.count)
            process.close()
            val result = withTimeout(2_000) { runCatching { process.awaitExit() } }
            if (failStdin) {
                assertTrue(result.exceptionOrNull() is java.io.IOException)
                assertEquals("Scripted stdin close failed", result.exceptionOrNull()?.message)
            } else assertEquals("Stop must break the dependency cycle without a consumer drain", 0, result.getOrThrow())
            assertFalse(process.isAlive); assertEquals(0L, exitRead.count)
            assertEquals(1, stdinCalls.get())
        } finally {
            // Exact fixture teardown only; the successful path must not need this drain/unblock.
            process.stdout.close(); process.stderr.close(); stdinEntered.countDown(); exitRead.countDown(); process.close()
            scope.cancel(); withTimeout(3_000) { scope.coroutineContext.job.join() }
        }
    }
    @Test(timeout = 10_000) fun naturalExitKeepsUnreadOutputAvailable() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val packets = java.util.concurrent.LinkedBlockingQueue<AdbShellPacket>().apply {
            put(AdbShellPacket.StdOut("normal output".toByteArray()))
            put(AdbShellPacket.StdError("normal error".toByteArray()))
            put(AdbShellPacket.Exit(byteArrayOf(7)))
        }
        val channel = object : RemoteProcessChannel {
            override fun read(): AdbShellPacket = packets.poll(3, TimeUnit.SECONDS) ?: error("Fixture packet timeout")
            override fun write(bytes: ByteArray) = Unit
            override fun closeStdin() = error("Natural exit must not request stdin close")
            override fun close() = Unit
        }
        val process = RemoteHostedStdioProcess(channel, scope) { terminate ->
            assertFalse(terminate); CompletableDeferred(Unit)
        }
        try {
            assertEquals(7, withTimeout(2_000) { process.awaitExit() })
            assertEquals("normal output", process.stdout.readBytes().toString(Charsets.UTF_8))
            assertEquals("normal error", process.stderr.readBytes().toString(Charsets.UTF_8))
        } finally { process.close(); scope.cancel() }
    }

}
