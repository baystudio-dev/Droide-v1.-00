package com.baystudio.droide.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class ExitProcessStopFanoutTest {
    @Test(timeout = 15_000) fun pendingReceiptCannotPreventOtherOwnedChildrenReceivingStop() = runBlocking {
        val registry = ExitHostedProcessOwners(isOpen = { false }, isCurrentOpen = { false })
        val release = CompletableDeferred<Unit>()
        val closed = CountDownLatch(3)
        val children = (1..3).map { ProcessBuilder("/bin/sh", "-c", "exec sleep 30").start() }
        val peers = children.map { child -> object : HostedStdioProcess {
            override val stdin get() = child.outputStream
            override val stdout get() = child.inputStream
            override val stderr get() = child.errorStream
            override val isAlive get() = child.isAlive
            override val exitCode: Int? get() = runCatching { child.exitValue() }.getOrNull()
            override fun close() { child.destroyForcibly(); closed.countDown() }
            override suspend fun awaitExit(): Int { release.await(); return withContext(Dispatchers.IO) { child.waitFor() } }
        } }
        peers.forEach(registry::retain)
        val exit = async(Dispatchers.IO) { registry.stopAndAwaitAll() }
        try {
            assertTrue("Every exact child must receive stop before any pending completion is awaited", closed.await(2, TimeUnit.SECONDS))
            children.forEach { assertTrue(it.waitFor(2, TimeUnit.SECONDS)) }
            assertFalse("Unfinished receipts must keep Exit pending", exit.isCompleted)
            release.complete(Unit)
            withTimeout(3_000) { exit.await() }
            registry.stopAndAwaitAll()
        } finally {
            release.complete(Unit)
            children.forEach { it.destroyForcibly(); it.waitFor(3, TimeUnit.SECONDS) }
            withTimeout(3_000) { exit.join() }
            children.forEach { it.outputStream.close(); it.inputStream.close(); it.errorStream.close() }
        }
    }
    @Test(timeout = 10_000) fun stopAndCompletionFailuresAreAllReportedWithoutSkippingOtherStops() = runBlocking {
        val registry = ExitHostedProcessOwners(isOpen = { false }, isCurrentOpen = { false })
        val closeA = IllegalStateException("close A")
        val closeB = LinkageError("close B")
        val awaitC = IllegalArgumentException("await C")
        val calls = AtomicInteger()
        listOf(closeA, closeB, null).forEach { closeFailure ->
            registry.retain(object : StubPeer() {
                override fun close() { calls.incrementAndGet(); closeFailure?.let { throw it } }
                override suspend fun awaitExit(): Int = throw awaitC
            })
        }
        val failure = runCatching { registry.stopAndAwaitAll() }.exceptionOrNull()
        assertEquals(3, calls.get())
        fun all(error: Throwable): List<Throwable> = listOf(error) + error.suppressed.flatMap(::all)
        val errors = all(checkNotNull(failure))
        assertTrue(errors.any { it === closeA }); assertTrue(errors.any { it === closeB })
        assertTrue(errors.any { it === awaitC })
    }

    @Test(timeout = 10_000) fun observationTimeoutAutomaticallyFinishesWithoutReplayingPendingShutdown() = runBlocking {
        val registry = ExitHostedProcessOwners(isOpen = { false }, isCurrentOpen = { false })
        val release = CompletableDeferred<Unit>()
        val stops = AtomicInteger()
        val peer = object : StubPeer() {
            override val isAlive get() = !release.isCompleted
            override fun close() { stops.incrementAndGet() }
            override suspend fun awaitExit(): Int { release.await(); return 0 }
        }
        registry.retain(peer)
        val sequence = ApplicationExitSequence(this,
            listOf(ApplicationExitSequence.Step("Processes", registry::stopAndAwaitAll)), observationMs = 100)
        try {
            val exiting = sequence.request()
            withTimeout(1_000) { sequence.state.first { it.stage.contains("still finishing") } }
            assertFalse(sequence.state.value.completed); assertNull(sequence.state.value.failure)
            assertTrue(sequence.state.value.running)
            assertEquals(1, stops.get())
            assertSame(exiting, sequence.request())
            assertFalse(sequence.state.value.completed); assertEquals(1, stops.get())
            release.complete(Unit)
            withTimeout(1_000) { exiting.join() }
            assertTrue(sequence.state.value.completed); assertEquals(1, stops.get())
        } finally { release.complete(Unit) }
    }

    @Test(timeout = 10_000) fun staleExitCompletionCannotEraseNewRetainedObservation() = runBlocking {
        val registry = ExitHostedProcessOwners(isOpen = { false }, isCurrentOpen = { false })
        val entered = List(3) { CompletableDeferred<Unit>() }
        val release = List(3) { CompletableDeferred<Unit>() }
        val observations = AtomicInteger()
        val peer = object : StubPeer() {
            override suspend fun awaitExit(): Int {
                val index = observations.getAndIncrement()
                if (index < 3) { entered[index].complete(Unit); release[index].await() }
                return 0
            }
        }
        registry.retain(peer); entered[0].await()
        val exit = async(Dispatchers.IO) { runCatching { registry.stopAndAwaitAll() } }
        try {
            entered[1].await()
            registry.retain(peer); entered[2].await()
            release[0].complete(Unit); release[1].complete(Unit)
            assertTrue("New retained receipt must still prevent successful Exit", withTimeout(2_000) { exit.await() }.isFailure)
        } finally { release.forEach { it.complete(Unit) }; exit.join() }
    }

    private open class StubPeer : HostedStdioProcess {
        override val stdin = java.io.ByteArrayOutputStream()
        override val stdout = java.io.ByteArrayInputStream(byteArrayOf())
        override val stderr = java.io.ByteArrayInputStream(byteArrayOf())
        override val isAlive = false
        override val exitCode: Int? = 0
        override fun close() = Unit
        override suspend fun awaitExit(): Int = 0
    }

}
