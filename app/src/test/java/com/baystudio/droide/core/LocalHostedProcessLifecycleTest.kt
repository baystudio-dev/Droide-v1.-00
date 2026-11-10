package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Production hosted lifecycle with JVM peers; Android/PRoot startup is not simulated. */
class LocalHostedProcessLifecycleTest {
    @Test fun returnsStreamsBeforeExitAndWaitsWithoutPollingIdlePeer() = runBlocking {
        val process = WaitingProcess()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val cleanups = AtomicInteger()
        val hosted = withTimeout(3_000) {
            withContext(Dispatchers.IO) { LocalHostedProcess(process, scope) { cleanups.incrementAndGet() } }
        }
        try {
            withTimeout(3_000) { process.waitStarted.await() }
            assertTrue(hosted.isAlive)
            hosted.stdin.write("request".toByteArray())
            assertEquals("request", process.received.toString("UTF-8"))
            val checks = process.livenessChecks.get()
            delay(100)
            assertEquals("A quiet protocol process must not be polled", checks, process.livenessChecks.get())
            process.finish(7)
            assertEquals(7, withTimeout(3_000) { hosted.awaitExit() })
            assertEquals(7, hosted.exitCode)
            assertEquals(1, cleanups.get())
        } finally { hosted.close(); scope.cancel(); process.finish(143) }
    }

    @Test fun realPipePeerCanReadRequestWhileExitWaiterIsActive() = runBlocking {
        val child = ProcessBuilder("/bin/sh", "-c", "read line; printf 'reply:%s\\n' \"\$line\"; exit 7").start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val cleanups = AtomicInteger()
        val hosted = withTimeout(3_000) {
            withContext(Dispatchers.IO) { LocalHostedProcess(child, scope) { cleanups.incrementAndGet() } }
        }
        try {
            assertTrue(hosted.isAlive)
            hosted.stdin.write("日本語 😀\n".toByteArray(Charsets.UTF_8))
            hosted.stdin.flush()
            val reply = withTimeout(3_000) {
                withContext(Dispatchers.IO) { hosted.stdout.bufferedReader().readLine() }
            }
            assertEquals("reply:日本語 😀", reply)
            assertEquals(7, withTimeout(3_000) { hosted.awaitExit() })
            assertEquals(1, cleanups.get())
            assertFalse(child.isAlive)
        } finally { hosted.close(); scope.cancel(); LocalProcessSupervisor.terminate(child) }
    }

    @Test fun repeatedCloseReapsOwnedPeerExactlyOnceAndLeavesUnrelatedProcessAlive() = runBlocking {
        val child = ProcessBuilder("/bin/sh", "-c", "exec sleep 30").start()
        val unrelated = ProcessBuilder("/bin/sh", "-c", "exec sleep 30").start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val cleanups = AtomicInteger()
        val hosted = LocalHostedProcess(child, scope) { cleanups.incrementAndGet() }
        try {
            coroutineScope { (1..20).map { launch(Dispatchers.Default) { hosted.close() } }.joinAll() }
            assertEquals(143, withTimeout(3_000) { hosted.awaitExit() })
            assertFalse(child.isAlive)
            assertFalse(hosted.isAlive)
            assertTrue(unrelated.isAlive)
            assertEquals(1, cleanups.get())
        } finally {
            hosted.close(); scope.cancel()
            LocalProcessSupervisor.terminate(child); LocalProcessSupervisor.terminate(unrelated)
        }
    }

    @Test fun cancelledParentBeforeWaiterStartsStillReapsCreatedPeer() = runBlocking {
        val child = ProcessBuilder("/bin/sh", "-c", "exec sleep 30").start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val cleanups = AtomicInteger()
        scope.cancel()
        val hosted = LocalHostedProcess(child, scope) { cleanups.incrementAndGet() }
        try {
            assertEquals(143, withTimeout(3_000) { hosted.awaitExit() })
            assertFalse(child.isAlive)
            assertEquals(1, cleanups.get())
        } finally { hosted.close(); LocalProcessSupervisor.terminate(child) }
    }

    @Test fun cancellingOwnerReapsBlockedProtocolPeer() = runBlocking {
        val process = WaitingProcess()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val cleanups = AtomicInteger()
        val hosted = LocalHostedProcess(process, scope) { cleanups.incrementAndGet() }
        try {
            withTimeout(3_000) { process.waitStarted.await() }
            scope.cancel()
            assertEquals(143, withTimeout(3_000) { hosted.awaitExit() })
            assertFalse(process.isAlive)
            assertEquals(1, cleanups.get())
        } finally { hosted.close(); scope.cancel(); process.finish(143) }
    }

    @Test fun cleanupFailureIsReportedToOwnerWithoutUncaughtCoroutineFailure() = runBlocking {
        val process = WaitingProcess()
        val uncaught = AtomicInteger()
        val cleanups = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> uncaught.incrementAndGet() })
        val hosted = LocalHostedProcess(process, scope) {
            cleanups.incrementAndGet()
            throw IOException("lease reaper unavailable")
        }
        try {
            withTimeout(3_000) { process.waitStarted.await() }
            process.finish(0)
            val failure = withTimeout(3_000) { runCatching { hosted.awaitExit() }.exceptionOrNull() }
            assertTrue(failure is IllegalStateException)
            // Coroutine stack-trace recovery may insert a copy of the wrapper.
            assertTrue(generateSequence(failure) { it.cause }.any {
                it is IOException && it.message == "lease reaper unavailable"
            })
            assertEquals(0, hosted.exitCode)
            assertFalse(process.isAlive)
            assertTrue(scope.isActive)
            assertEquals(0, uncaught.get())
            hosted.close()
            assertEquals(1, cleanups.get())
        } finally { hosted.close(); scope.cancel(); process.finish(143) }
    }

    @Test fun waitFailureReapsPeerAndIsReportedWithoutUncaughtCoroutineFailure() = runBlocking {
        val process = WaitingProcess(failWait = true)
        val uncaught = AtomicInteger()
        val cleanups = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> uncaught.incrementAndGet() })
        val hosted = LocalHostedProcess(process, scope) { cleanups.incrementAndGet() }
        try {
            val failure = withTimeout(3_000) { runCatching { hosted.awaitExit() }.exceptionOrNull() }
            assertTrue(failure is IllegalStateException)
            assertTrue(generateSequence(failure) { it.cause }.any {
                it is IllegalStateException && it.message == "peer wait failed"
            })
            assertEquals(-1, hosted.exitCode)
            assertFalse(process.isAlive)
            assertTrue(scope.isActive)
            assertEquals(0, uncaught.get())
            assertEquals(1, cleanups.get())
        } finally { hosted.close(); scope.cancel(); process.finish(143) }
    }

    private class WaitingProcess(private val failWait: Boolean = false) : Process() {
        val waitStarted = CompletableDeferred<Unit>()
        val livenessChecks = AtomicInteger()
        val received = ByteArrayOutputStream()
        private val alive = AtomicBoolean(true)
        private val exited = CountDownLatch(1)
        @Volatile private var result = 143
        override fun getOutputStream() = received
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun isAlive(): Boolean { livenessChecks.incrementAndGet(); return alive.get() }
        override fun waitFor(): Int {
            waitStarted.complete(Unit)
            if (failWait) throw IllegalStateException("peer wait failed")
            exited.await()
            return result
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = exited.await(timeout, unit)
        override fun exitValue(): Int {
            check(!alive.get()) { "Process is alive" }
            return result
        }
        override fun destroy() = finish(143)
        override fun destroyForcibly(): Process = apply { finish(137) }
        fun finish(code: Int) { result = code; alive.set(false); exited.countDown() }
    }
}
