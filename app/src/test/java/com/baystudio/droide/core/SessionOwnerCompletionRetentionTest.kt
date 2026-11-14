package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SessionOwnerCompletionRetentionTest {
    private open class Peer : HostedStdioProcess {
        override val stdin = ByteArrayOutputStream()
        override val stdout = ByteArrayInputStream(byteArrayOf())
        override val stderr = ByteArrayInputStream(byteArrayOf())
        @Volatile var alive = true
        @Volatile var failure: Throwable? = null
        val receipt = CompletableDeferred<Unit>()
        val observing = CompletableDeferred<Unit>()
        val closes = AtomicInteger()
        override val isAlive get() = alive
        override val exitCode get() = if (alive) null else 0
        override fun close() { closes.incrementAndGet(); finish() }
        override suspend fun awaitExit(): Int {
            observing.complete(Unit)
            receipt.await()
            failure?.let { throw it }
            return 0
        }
        fun finish() { alive = false; receipt.complete(Unit) }
        // Value equality must not merge distinct process handles.
        override fun equals(other: Any?) = other is Peer
        override fun hashCode() = 0
    }

    private fun retained(owner: SessionHostedProcessOwner): Int {
        val monitor = SessionHostedProcessOwner::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(owner)
        val records = SessionHostedProcessOwner::class.java.getDeclaredField("owned").apply { isAccessible = true }
        return synchronized(monitor) { (records.get(owner) as IdentityHashMap<*, *>).size }
    }

    private suspend fun awaitRetained(owner: SessionHostedProcessOwner, count: Int) = withTimeout(3_000) {
        while (retained(owner) != count) delay(5)
    }

    @Test(timeout = 10_000)
    fun completedTerminalsDoNotAccumulateAndSurvivingHandlesCloseOnlyOnce() = runBlocking {
        val owner = SessionHostedProcessOwner()
        val live = Peer()
        val finished = List(512) { Peer() }
        owner.retain(live)
        try {
            finished.forEach { owner.retain(it); it.finish() }
            awaitRetained(owner, 1)
            finished.forEach(owner::retain) // Late handoffs of the same identities are harmless.
            assertEquals(1, retained(owner))
            assertTrue("Natural completion leaves output available to its readers", finished.all { it.closes.get() == 0 })
            owner.stopAndAwait()
            assertEquals(0, retained(owner))
            assertEquals(1, live.closes.get())
            assertTrue(finished.all { it.closes.get() == 1 })
            finished.forEach(owner::retain)
            owner.stopAndAwait()
            assertTrue(finished.all { it.closes.get() == 1 })
        } finally {
            finished.forEach(Peer::finish); live.finish(); owner.stopAndAwait()
        }
    }

    @Test(timeout = 10_000)
    fun successfulStopDoesNotKeepTheCompletedHandleReachable() = runBlocking {
        val owner = SessionHostedProcessOwner()
        suspend fun stopOne(): WeakReference<Peer> {
            val peer = Peer()
            owner.retain(peer)
            owner.stopAndAwait()
            return WeakReference(peer)
        }
        val handle = stopOne()
        withTimeout(5_000) {
            while (handle.get() != null) { System.gc(); delay(20) }
        }
        assertEquals(0, retained(owner))
        owner.stopAndAwait()
    }

    @Test(timeout = 10_000)
    fun naturalCompletionDoesNotKeepTheHandleReachableInALongRunningSession() = runBlocking {
        val owner = SessionHostedProcessOwner()
        fun finishOne(): WeakReference<Peer> {
            val peer = Peer()
            owner.retain(peer)
            peer.finish()
            return WeakReference(peer)
        }
        val handle = finishOne()
        awaitRetained(owner, 0)
        withTimeout(5_000) { while (handle.get() != null) { System.gc(); delay(20) } }
        assertFalse(owner.isClosing)
        owner.stopAndAwait()
    }

    @Test(timeout = 10_000)
    fun shutdownRechecksCleanupStartedAfterNaturalCompletion() = runBlocking {
        val owner = SessionHostedProcessOwner()
        val peer = Peer()
        owner.retain(peer); peer.finish()
        awaitRetained(owner, 0)
        peer.failure = IllegalStateException("stream cleanup failed after natural exit")
        assertTrue(runCatching { owner.stopAndAwait() }.isFailure)
        assertEquals(1, retained(owner))
        peer.failure = null
        owner.stopAndAwait()
        assertEquals(0, retained(owner))
    }

    @Test(timeout = 10_000)
    fun deadHandleWithPendingOrFailedReceiptRemainsOwnedUntilExplicitCleanup() = runBlocking {
        val owner = SessionHostedProcessOwner()
        val peer = Peer().apply { alive = false }
        owner.retain(peer)
        peer.observing.await()
        assertEquals(1, retained(owner))
        peer.failure = IllegalStateException("cleanup receipt unavailable")
        peer.receipt.complete(Unit)
        assertTrue(runCatching { owner.stopAndAwait() }.isFailure)
        assertEquals(1, retained(owner))
        peer.failure = null
        owner.stopAndAwait()
        assertEquals(0, retained(owner))
        assertEquals(2, peer.closes.get())
    }

    @Test(timeout = 10_000)
    fun naturalExitCannotAcknowledgeABlockedOrFailedClose() = runBlocking {
        val owner = SessionHostedProcessOwner()
        val closeEntered = CountDownLatch(1)
        val closeGate = CountDownLatch(1)
        val peer = object : Peer() {
            override fun close() {
                if (closes.incrementAndGet() == 1) {
                    closeEntered.countDown()
                    check(closeGate.await(5, TimeUnit.SECONDS))
                    throw IllegalStateException("first close failed after process exit")
                }
            }
        }
        owner.retain(peer)
        val cleanup = async { runCatching { owner.stopAndAwait() } }
        try {
            assertTrue(withContext(Dispatchers.IO) { closeEntered.await(2, TimeUnit.SECONDS) })
            peer.finish()
            peer.observing.await()
            yield()
            assertEquals(1, retained(owner))
            assertFalse(cleanup.isCompleted)
            closeGate.countDown()
            assertTrue(cleanup.await().isFailure)
            assertEquals(1, retained(owner))
            owner.stopAndAwait()
            assertEquals(0, retained(owner))
            assertEquals(2, peer.closes.get())
        } finally { closeGate.countDown(); peer.finish(); cleanup.await() }
    }
}
