package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SessionOwnerIndependentReviewTest {
    private open class Peer : HostedStdioProcess {
        override val stdin = ByteArrayOutputStream()
        override val stdout = ByteArrayInputStream(byteArrayOf())
        override val stderr = ByteArrayInputStream(byteArrayOf())
        @Volatile var alive = true
        val calls = AtomicInteger()
        val stopped = CompletableDeferred<Unit>()
        override val isAlive get() = alive
        override val exitCode get() = if (alive) null else 0
        override fun close() { calls.incrementAndGet(); alive = false; stopped.complete(Unit) }
        override suspend fun awaitExit(): Int { stopped.await(); return 0 }
    }

    @Test(timeout = 8_000)
    fun lateRealChildIsStoppedEvenWhenIoParallelismIsExhaustedByFirstClose() = runBlocking {
        assertEquals("Run this probe with one IO lane", "1", System.getProperty("kotlinx.coroutines.io.parallelism"))
        val owner = SessionHostedProcessOwner()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocked = object : Peer() {
            override fun close() {
                calls.incrementAndGet(); entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                alive = false; stopped.complete(Unit)
            }
        }
        val child = ProcessBuilder("/bin/sh", "-c", "exec sleep 60").start()
        val lateStopped = CompletableDeferred<Unit>()
        val lateCalls = AtomicInteger()
        val late = object : HostedStdioProcess {
            override val stdin get() = child.outputStream
            override val stdout get() = child.inputStream
            override val stderr get() = child.errorStream
            override val isAlive get() = child.isAlive
            override val exitCode get() = if (child.isAlive) null else child.exitValue()
            override fun close() { lateCalls.incrementAndGet(); child.destroy(); lateStopped.complete(Unit) }
            override suspend fun awaitExit(): Int { while (child.isAlive) delay(5); return child.exitValue() }
        }
        try {
            owner.retain(blocked); owner.closeAdmission()
            assertTrue("First synchronous close never entered", entered.await(2, TimeUnit.SECONDS))
            owner.retain(late)
            assertTrue("A blocking close exhausted IO and starved late child's stop", withTimeoutOrNull(750) { lateStopped.await(); true } == true)
            assertTrue("The actual late child remained alive", child.waitFor(2, TimeUnit.SECONDS))
            val cancelledObserver = launch { owner.stopAndAwait() }
            yield(); cancelledObserver.cancelAndJoin()
            assertEquals(1, blocked.calls.get())
            release.countDown()
            withTimeout(2_000) { owner.stopAndAwait() }
            owner.retain(blocked); owner.retain(late)
            withTimeout(2_000) { owner.stopAndAwait() }
            assertEquals(1, blocked.calls.get()); assertEquals(1, lateCalls.get())
        } finally {
            release.countDown(); child.destroyForcibly(); child.waitFor(2, TimeUnit.SECONDS)
            child.outputStream.close(); child.inputStream.close(); child.errorStream.close()
            runCatching { withTimeout(2_000) { owner.stopAndAwait() } }
        }
    }

    @Test(timeout = 5_000)
    fun closeFailureOfLiveChildIsRetryableRatherThanIndefinitelyPending() = runBlocking {
        val owner = SessionHostedProcessOwner()
        val peer = object : Peer() {
            override fun close() {
                if (calls.incrementAndGet() == 1) throw java.io.IOException("deliberate first stop failure")
                alive = false; stopped.complete(Unit)
            }
        }
        owner.retain(peer)
        val failed = runCatching { withTimeout(750) { owner.stopAndAwait() } }.exceptionOrNull()
        assertNotNull(failed)
        assertFalse("Close failure must be terminal and explicitly retryable", failed is TimeoutCancellationException)
        assertTrue(peer.alive)
        withTimeout(1_000) { owner.stopAndAwait() }
        assertFalse(peer.alive); assertEquals(2, peer.calls.get())
        owner.retain(peer); owner.closeAdmission(); owner.stopAndAwait()
        assertEquals(2, peer.calls.get())
    }
}
