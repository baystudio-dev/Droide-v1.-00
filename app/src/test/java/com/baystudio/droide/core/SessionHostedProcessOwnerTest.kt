package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SessionHostedProcessOwnerTest {
    private class Peer : HostedStdioProcess {
        override val stdin = ByteArrayOutputStream()
        override val stdout = ByteArrayInputStream(byteArrayOf())
        override val stderr = ByteArrayInputStream(byteArrayOf())
        var alive = true
        var stops = 0
        var failure: Throwable? = null
        val receipt = CompletableDeferred<Unit>()
        val awaiting = CompletableDeferred<Unit>()
        override val isAlive get() = alive
        override val exitCode: Int? get() = if (alive) null else 0
        override fun close() { stops++ }
        override suspend fun awaitExit(): Int {
            awaiting.complete(Unit)
            receipt.await()
            failure?.let { throw it }
            return 0
        }
        fun finish() { alive = false; receipt.complete(Unit) }
        // Deliberately equal objects still represent different owned process handles.
        override fun equals(other: Any?) = other is Peer
        override fun hashCode() = 0
    }

    @Test(timeout = 5_000) fun stopsAllExactHandlesBeforeWaitingAndNeverTouchesOtherWorkspace() = runBlocking {
        val owner = SessionHostedProcessOwner()
        val otherOwner = SessionHostedProcessOwner()
        val a = Peer(); val b = Peer(); val other = Peer()
        owner.retain(a); owner.retain(b); otherOwner.retain(other)
        val cleanup = async { owner.stopAndAwait() }
        a.awaiting.await(); b.awaiting.await()
        assertEquals(1, a.stops); assertEquals(1, b.stops); assertEquals(0, other.stops)
        a.finish(); b.finish(); cleanup.await()
        owner.stopAndAwait()
        assertEquals(1, a.stops); assertEquals(1, b.stops)
        other.finish(); otherOwner.stopAndAwait()
    }

    @Test(timeout = 5_000) fun falseExitReceiptAndCleanupFailureKeepOnlyFailedHandlesForRetry() = runBlocking {
        val owner = SessionHostedProcessOwner()
        val live = Peer().apply { receipt.complete(Unit) }
        val failed = Peer().apply { finish(); failure = IllegalStateException("lease failed") }
        val done = Peer().apply { finish() }
        listOf(live, failed, done).forEach(owner::retain)
        assertTrue(runCatching { owner.stopAndAwait() }.isFailure)
        live.finish(); failed.failure = null
        owner.stopAndAwait()
        assertEquals(2, live.stops); assertEquals(2, failed.stops); assertEquals(1, done.stops)
    }

    @Test(timeout = 5_000) fun cancelledAwaitDoesNotForgetTheProcess() = runBlocking {
        val owner = SessionHostedProcessOwner(); val peer = Peer()
        owner.retain(peer)
        val cleanup = launch { owner.stopAndAwait() }
        peer.awaiting.await()
        cleanup.cancelAndJoin()
        assertEquals(1, peer.stops)
        peer.finish(); owner.stopAndAwait()
        // Observer cancellation does not cancel/replay the retained exact-owner attempt.
        assertEquals(1, peer.stops)
    }

    @Test(timeout = 5_000) fun closeCancelsInflightLaunchButKeepsHandleBornBeforeHandoff() = runBlocking {
        val owner = SessionHostedProcessOwner(); val peer = Peer()
        val born = CompletableDeferred<Unit>()
        val delegate = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command: String) = command
            override suspend fun pathMapper(): WorkspacePathMapper? = null
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
                currentCoroutineContext()[SessionHostedProcessOwner]!!.retain(peer)
                born.complete(Unit)
                awaitCancellation()
            }
        }
        val host = owner.bind(delegate)
        val starting = launch { host.start(listOf("fixture")) }
        born.await()
        val cleanup = async { owner.stopAndAwait() }
        peer.awaiting.await()
        starting.join()
        assertTrue(starting.isCancelled)
        assertFalse(cleanup.isCompleted)
        peer.finish(); cleanup.await()
        assertTrue(runCatching { host.start(listOf("fixture")) }.isFailure)
    }

    @Test(timeout = 10_000) fun localProcessBirthIsOwnedBeforeCancelledAdmissionCanDiscardItsHandle() = runBlocking {
        val owner = SessionHostedProcessOwner()
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val born = CompletableDeferred<Unit>()
        val cleanupEntered = java.util.concurrent.CountDownLatch(1)
        val releaseCleanup = java.util.concurrent.CountDownLatch(1)
        var process: LocalHostedProcess? = null
        val delegate = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command: String) = command
            override suspend fun pathMapper(): WorkspacePathMapper? = null
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess =
                admitLocalHostedProcess(admission = { create ->
                    create(); born.complete(Unit); awaitCancellation()
                }, create = {
                    LocalHostedProcess(ProcessBuilder("/bin/sh", "-c", "exec sleep 30").start(), processScope) {
                        cleanupEntered.countDown()
                        check(releaseCleanup.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    }.also { process = it }
                })
        }
        try {
            val starting = launch { owner.bind(delegate).start(listOf("fixture")) }
            born.await()
            val cleanup = async { owner.stopAndAwait() }
            yield()
            assertTrue(withContext(Dispatchers.IO) { cleanupEntered.await(3, java.util.concurrent.TimeUnit.SECONDS) })
            assertFalse(cleanup.isCompleted)
            releaseCleanup.countDown()
            cleanup.await(); starting.join()
            assertTrue(starting.isCancelled)
            assertFalse(checkNotNull(process).isAlive)
        } finally {
            releaseCleanup.countDown()
            process?.close(); process?.awaitExit()
            processScope.coroutineContext.job.cancelAndJoin()
        }
    }
}
