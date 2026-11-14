package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Real cleanup classes, bounded fake peers, and exact-handle JVM shells only. */
class ProcessExitOwnershipR64Test {
    @Test(timeout = 10_000) fun destroyBeforeExitStillWaitsForRetiredInteractiveCleanup() = runBlocking {
        val cleanupEntered = CountDownLatch(1)
        val cleanupRelease = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val terminal = InteractiveTerminalProcess(scope,
            { ProcessBuilder("/bin/sh", "-c", "exit 0").start() },
            { cleanupEntered.countDown(); check(cleanupRelease.await(5, TimeUnit.SECONDS)) }, {})
        try {
            terminal.start()
            assertTrue(cleanupEntered.await(3, TimeUnit.SECONDS))
            terminal.destroy()
            val exiting = async { terminal.destroyAndJoinForExit() }
            delay(50)
            assertFalse("Exit must retain the worker cleared by ordinary destroy", exiting.isCompleted)
            cleanupRelease.countDown()
            withTimeout(3_000) { exiting.await() }
        } finally { cleanupRelease.countDown(); terminal.destroy(); scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test(timeout = 10_000) fun restartCannotLosePriorGenerationCleanupReceipt() = runBlocking {
        val firstCleanup = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = AtomicBoolean(true)
        val terminal = InteractiveTerminalProcess(scope,
            { ProcessBuilder("/bin/sh", "-c", "exit 0").start() },
            { if (first.compareAndSet(true, false)) { firstCleanup.countDown(); check(releaseFirst.await(5, TimeUnit.SECONDS)) } }, {})
        try {
            terminal.start(); assertTrue(firstCleanup.await(3, TimeUnit.SECONDS))
            terminal.restart()
            val exiting = async { terminal.destroyAndJoinForExit() }
            delay(50); assertFalse(exiting.isCompleted)
            releaseFirst.countDown(); withTimeout(3_000) { exiting.await() }
        } finally { releaseFirst.countDown(); terminal.destroy(); scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test(timeout = 10_000) fun exitClosesBothBuildAndStopAdmissionWithoutCancellingRetiredCaller() = runBlocking {
        val gate = BuildRunDebugOperationGate<String>()
        val ready = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val caller = launch {
            assertEquals("done", gate.run("build") { "done" })
            ready.complete(Unit)
            release.await()
        }
        ready.await(); gate.closeAndJoin()
        assertTrue(caller.isActive)
        assertTrue(runCatching { gate.run("late") { error("late work") } }.isFailure)
        assertTrue(runCatching { gate.stopDebug("debug") { error("late cleanup") } }.isFailure)
        release.complete(Unit); caller.join()
    }

    @Test(timeout = 10_000) fun rejectedHostedHandoffRetainsCleanupDespiteCallerCancellation() = runBlocking {
        val registry = ExitHostedProcessOwners(isOpen = { false }, isCurrentOpen = { false })
        val peer = ReceiptPeer()
        val caller = launch { registry.admit(peer, "fixture") }
        peer.closeStarted.await(); caller.cancel()
        delay(50); assertFalse(caller.isCompleted)
        peer.alive = false; peer.release.complete(Unit)
        withTimeout(3_000) { caller.join() }
        registry.stopAndAwaitAll()
    }

    @Test(timeout = 10_000) fun hostedRegistryRejectsFalseCompletionAndKeepsExactHandleForRetry() = runBlocking {
        var open = true
        val registry = ExitHostedProcessOwners(isOpen = { open }, isCurrentOpen = { open && it == "fixture" })
        val peer = ReceiptPeer().apply { release.complete(Unit) }
        registry.admit(peer, "fixture")
        open = false
        assertTrue(runCatching { registry.stopAndAwaitAll() }.isFailure)
        peer.alive = false
        registry.stopAndAwaitAll()
    }

    @Test(timeout = 10_000) fun hostedProcessDoesNotReportDeadMerelyBecauseCloseWasRequested() = runBlocking {
        val peer = StubbornProcess()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val hosted = LocalHostedProcess(peer, scope) { }
        hosted.close()
        assertTrue(hosted.isAlive)
        assertTrue(runCatching { withTimeout(3_000) { hosted.awaitExit() } }.isFailure)
        assertTrue(hosted.isAlive)
        peer.alive.set(false); scope.coroutineContext.job.cancelAndJoin()
    }

    @Test(timeout = 10_000) fun boundedCaptureReportsUnfinishedIoToExitOwner() = runBlocking {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val peer = object : StubbornProcess() {
            // Root has exited; only its inherited I/O worker remains unfinished.
            // Keep liveness, exitValue and BOTH wait methods consistent.
            init { alive.set(false) }
            override fun getInputStream() = object : InputStream() {
                override fun read(): Int { entered.countDown(); release.await(); return -1 }
                override fun close() = Unit
            }
        }
        assertFalse(peer.isAlive)
        assertEquals(0, peer.exitValue())
        assertTrue(peer.waitFor(1, TimeUnit.MILLISECONDS))
        assertEquals(0, peer.waitFor())
        var now = 0L
        var receipt: Throwable? = null
        try {
            val capture = async(Dispatchers.IO) {
                LocalProcessSupervisor.captureOwned(peer, nowNanos = { now }, pause = { now += 3_000_000_000L; yield() },
                    onCleanupCompleted = { receipt = it })
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val result = withTimeout(3_000) { capture.await() }
            assertTrue(result.outputIncomplete)
            assertEquals("The reader must remain held when its failed receipt is observed", 1L, release.count)
            assertNotNull("Exit needs a failed receipt even when bounded ordinary capture returns", receipt)
        } finally { release.countDown() }
    }

    @Test(timeout = 10_000) fun retiredObservationCannotEraseNewCloseReceipt() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val firstRelease = CompletableDeferred<Unit>()
        val closeRelease = CompletableDeferred<Unit>()
        val observations = java.util.concurrent.atomic.AtomicInteger()
        val peer = object : HostedStdioProcess {
            override val stdin = ByteArrayOutputStream()
            override val stdout = ByteArrayInputStream(byteArrayOf())
            override val stderr = ByteArrayInputStream(byteArrayOf())
            override val isAlive = false
            override val exitCode: Int? = 0
            override fun close() = Unit
            override suspend fun awaitExit(): Int {
                if (observations.incrementAndGet() == 1) { firstEntered.complete(Unit); firstRelease.await() }
                else { secondEntered.complete(Unit); closeRelease.await() }
                return 0
            }
        }
        val registry = ExitHostedProcessOwners(isOpen = { false }, isCurrentOpen = { false })
        registry.retain(peer); firstEntered.await()
        registry.retain(peer); secondEntered.await()
        firstRelease.complete(Unit)
        delay(50)
        val exit = async { registry.stopAndAwaitAll() }
        delay(50)
        assertFalse("An older natural-exit observation cannot erase a newer pipe-close receipt", exit.isCompleted)
        closeRelease.complete(Unit)
        withTimeout(3_000) { exit.await() }
    }

    private class ReceiptPeer : HostedStdioProcess {
        @Volatile var alive = true
        val closeStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override val stdin = ByteArrayOutputStream()
        override val stdout = ByteArrayInputStream(byteArrayOf())
        override val stderr = ByteArrayInputStream(byteArrayOf())
        override val isAlive get() = alive
        override val exitCode: Int? = 0
        override fun close() { closeStarted.complete(Unit) }
        override suspend fun awaitExit(): Int { release.await(); return 0 }
    }

    private open class StubbornProcess : Process() {
        val alive = AtomicBoolean(true)
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun isAlive() = alive.get()
        override fun waitFor(): Int { while (alive.get()) Thread.sleep(10); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit) = !alive.get()
        override fun exitValue() = if (alive.get()) error("still live") else 0
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
    }
}
