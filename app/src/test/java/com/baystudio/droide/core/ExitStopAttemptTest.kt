package com.baystudio.droide.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Registry observation is cancellable; exact process cleanup ownership is not. */
class ExitStopAttemptTest {
    @Test(timeout = 15_000) fun cancelledObserversCannotReplayPendingCloseOrTrustNaturalReceipt() = runBlocking {
        val registry = ExitHostedProcessOwners({ false }, { false })
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val naturalDone = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val peer = object : Peer() {
            override fun close() {
                calls.incrementAndGet(); closeEntered.countDown()
                check(releaseClose.await(8, TimeUnit.SECONDS))
            }
            override suspend fun awaitExit(): Int { naturalDone.await(); return 0 }
        }
        registry.retain(peer)
        val first = async { registry.stopAndAwaitAll() }
        try {
            withContext(Dispatchers.IO) { assertTrue(closeEntered.await(2, TimeUnit.SECONDS)) }
            naturalDone.complete(Unit)
            first.cancelAndJoin()
            repeat(2) {
                assertNull("A natural receipt does not confirm the still-held close", withTimeoutOrNull(100) { registry.stopAndAwaitAll(); true })
                assertEquals("The retained pending stop must remain single-flight", 1, calls.get())
            }
            releaseClose.countDown()
            withTimeout(3_000) { registry.stopAndAwaitAll() }
            assertEquals(1, calls.get())
        } finally { naturalDone.complete(Unit); releaseClose.countDown(); first.cancelAndJoin() }
    }

    @Test(timeout = 10_000) fun explicitRetryReplaysOnlyFailedCloseAndKeepsItsFailure() = runBlocking {
        val registry = ExitHostedProcessOwners({ false }, { false })
        val failedCalls = AtomicInteger()
        val goodCalls = AtomicInteger()
        val failedDone = CompletableDeferred<Unit>()
        val goodDone = CompletableDeferred<Unit>()
        val expected = LinkageError("exact failed close")
        registry.retain(object : Peer() {
            override fun close() {
                if (failedCalls.incrementAndGet() == 1) throw expected
                failedDone.complete(Unit)
            }
            override suspend fun awaitExit(): Int { failedDone.await(); return 0 }
        })
        registry.retain(object : Peer() {
            override fun close() { goodCalls.incrementAndGet(); goodDone.complete(Unit) }
            override suspend fun awaitExit(): Int { goodDone.await(); return 0 }
        })
        val failure = runCatching { registry.stopAndAwaitAll() }.exceptionOrNull()
        assertSame(expected, failure)
        assertEquals(1, failedCalls.get()); assertEquals(1, goodCalls.get())
        withTimeout(3_000) { registry.stopAndAwaitAll() }
        registry.stopAndAwaitAll()
        assertEquals(2, failedCalls.get()); assertEquals(1, goodCalls.get())
    }

    @Test(timeout = 10_000) fun closeWorkerStartFailureDoesNotSkipOtherDispatchesAndCanRetry() = runBlocking {
        val dispatches = AtomicInteger()
        val closes = AtomicInteger()
        val expected = LinkageError("fixture worker start failed")
        val registry = ExitHostedProcessOwners({ false }, { false }, dispatchClose = { action ->
            if (dispatches.incrementAndGet() == 1) throw expected
            Thread(action, "droide-exit-test-stop").apply { isDaemon = true }.start()
        })
        repeat(3) {
            val done = CompletableDeferred<Unit>()
            registry.retain(object : Peer() {
                override fun close() { closes.incrementAndGet(); done.complete(Unit) }
                override suspend fun awaitExit(): Int { done.await(); return 0 }
            })
        }
        assertSame(expected, runCatching { registry.stopAndAwaitAll() }.exceptionOrNull())
        assertEquals(3, dispatches.get()); assertEquals(2, closes.get())
        withTimeout(3_000) { registry.stopAndAwaitAll() }
        assertEquals(4, dispatches.get()); assertEquals(3, closes.get())
    }

    @Test(timeout = 10_000) fun fatalCloseErrorIsRetainedAndReportedWithoutSkippingSibling() = runBlocking {
        val registry = ExitHostedProcessOwners({ false }, { false })
        val expected = AssertionError("fixture fatal close")
        val done = CompletableDeferred<Unit>()
        val failedDone = CompletableDeferred<Unit>()
        val failedCalls = AtomicInteger()
        val calls = AtomicInteger()
        registry.retain(object : Peer() {
            override fun close() {
                if (failedCalls.incrementAndGet() == 1) throw expected
                failedDone.complete(Unit)
            }
            override suspend fun awaitExit(): Int { failedDone.await(); return 0 }
        })
        registry.retain(object : Peer() {
            override fun close() { calls.incrementAndGet(); done.complete(Unit) }
            override suspend fun awaitExit(): Int { done.await(); return 0 }
        })
        val failure = runCatching { registry.stopAndAwaitAll() }.exceptionOrNull()
        assertSame(expected, failure?.cause)
        assertEquals(1, calls.get())
        registry.stopAndAwaitAll()
        assertEquals(2, failedCalls.get()); assertEquals(1, calls.get())
    }

    @Test(timeout = 15_000) fun suspendedExitReceiptsDoNotKeepNativeCloseThreadsOrReplayStops() = runBlocking {
        val registry = ExitHostedProcessOwners({ false }, { false })
        val release = CompletableDeferred<Unit>()
        val workers = java.util.concurrent.ConcurrentLinkedQueue<Thread>()
        val stopped = CountDownLatch(48)
        repeat(48) {
            registry.retain(object : Peer() {
                override fun close() { workers.add(Thread.currentThread()); stopped.countDown() }
                override suspend fun awaitExit(): Int { release.await(); return 0 }
            })
        }
        val first = async { registry.stopAndAwaitAll() }
        try {
            withContext(Dispatchers.IO) { assertTrue(stopped.await(3, TimeUnit.SECONDS)) }
            withTimeout(3_000) { while (workers.any { it.isAlive }) delay(10) }
            assertEquals(48, workers.size)
            assertTrue(workers.all { it.name == "droide-exit-stop" && it.isDaemon })
            assertFalse(first.isCompleted)
            first.cancelAndJoin()
            assertNull(withTimeoutOrNull(100) { registry.stopAndAwaitAll(); true })
            assertEquals(48, workers.size)
            release.complete(Unit)
            withTimeout(3_000) { registry.stopAndAwaitAll() }
            assertEquals(48, workers.size)
        } finally { release.complete(Unit); first.cancelAndJoin() }
    }

    @Test(timeout = 10_000) fun failedReceiptRetriesExactHandleWithoutReplayingSuccessfulSibling() = runBlocking {
        val registry = ExitHostedProcessOwners({ false }, { false })
        val badCalls = AtomicInteger()
        val goodCalls = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val good = CompletableDeferred<Unit>()
        val expected = IllegalStateException("fixture exit receipt")
        registry.retain(object : Peer() {
            override fun close() { badCalls.incrementAndGet(); started.complete(Unit) }
            override suspend fun awaitExit(): Int {
                started.await()
                if (badCalls.get() < 2) throw expected
                return 0
            }
        })
        registry.retain(object : Peer() {
            override fun close() { goodCalls.incrementAndGet(); good.complete(Unit) }
            override suspend fun awaitExit(): Int { good.await(); return 0 }
        })
        assertSame(expected, runCatching { registry.stopAndAwaitAll() }.exceptionOrNull())
        registry.stopAndAwaitAll()
        assertEquals(2, badCalls.get()); assertEquals(1, goodCalls.get())
    }

    private open class Peer : HostedStdioProcess {
        override val stdin = java.io.ByteArrayOutputStream()
        override val stdout = java.io.ByteArrayInputStream(byteArrayOf())
        override val stderr = java.io.ByteArrayInputStream(byteArrayOf())
        override val isAlive = false
        override val exitCode: Int? = 0
        override fun close() = Unit
        override suspend fun awaitExit(): Int = 0
    }
}
