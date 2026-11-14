package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Independent exact-handle registry lifecycle tests. Run with kotlinx.coroutines.io.parallelism=1. */
class ExitHostedLifecycleIndependentF153Test {
    private fun registry() = ExitHostedProcessOwners(isOpen = { false }, isCurrentOpen = { false })
    private open class Peer : HostedStdioProcess {
        override val stdin = ByteArrayOutputStream()
        override val stdout = ByteArrayInputStream(byteArrayOf())
        override val stderr = ByteArrayInputStream(byteArrayOf())
        override val isAlive = false
        override val exitCode: Int? = 0
        override fun close() = Unit
        override suspend fun awaitExit(): Int = 0
    }
    private suspend fun eventually(message: String, condition: () -> Boolean) {
        withTimeout(3_000) { while (!condition()) delay(5) }
        assertTrue(message, condition())
    }

    @Test(timeout = 12_000) fun passiveCompletionCannotForgetHeldCloseOrPermitDuplicateStop() = runBlocking {
        val owners = registry()
        val entered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val releaseReceipt = CompletableDeferred<Unit>()
        val receipts = AtomicInteger()
        val stops = AtomicInteger()
        val peer = object : Peer() {
            override fun close() {
                stops.incrementAndGet(); entered.countDown()
                check(releaseClose.await(6, TimeUnit.SECONDS))
            }
            override suspend fun awaitExit(): Int { releaseReceipt.await(); receipts.incrementAndGet(); return 0 }
        }
        owners.retain(peer)
        val first = async(Dispatchers.Default) { runCatching { owners.stopAndAwaitAll() } }
        var next: Deferred<Result<Unit>>? = null
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            releaseReceipt.complete(Unit)
            eventually("The natural receipt must run while close remains held") { receipts.get() > 0 }
            first.cancel()
            next = async(Dispatchers.Default) { runCatching { owners.stopAndAwaitAll() } }
            delay(150)
            assertFalse("Natural completion cannot make Exit successful while its owned close is held", next.isCompleted)
            assertEquals("A cancelled observer cannot replay the pending exact close", 1, stops.get())
        } finally {
            releaseClose.countDown(); releaseReceipt.complete(Unit)
            withTimeout(3_000) { first.join(); next?.join() }
        }
        withTimeout(3_000) { owners.stopAndAwaitAll() }
        assertEquals(1, stops.get())
    }

    @Test(timeout = 10_000) fun cancellationDuringReceiptKeepsOneStopAttempt() = runBlocking {
        val owners = registry()
        val receipt = CompletableDeferred<Unit>()
        val stops = AtomicInteger()
        val peer = object : Peer() {
            override fun close() { stops.incrementAndGet() }
            override suspend fun awaitExit(): Int { receipt.await(); return 0 }
        }
        owners.retain(peer)
        val first = async(Dispatchers.Default) { runCatching { owners.stopAndAwaitAll() } }
        var next: Deferred<Result<Unit>>? = null
        try {
            eventually("The exact close must dispatch") { stops.get() == 1 }
            first.cancelAndJoin()
            next = async(Dispatchers.Default) { runCatching { owners.stopAndAwaitAll() } }
            delay(150)
            assertEquals("Receipt observation cancellation must not replay close", 1, stops.get())
            assertFalse(next.isCompleted)
            receipt.complete(Unit)
            assertTrue(withTimeout(3_000) { next.await() }.isSuccess)
        } finally { receipt.complete(Unit); withTimeout(3_000) { first.join(); next?.join() } }
    }

    @Test(timeout = 10_000) fun failedCloseCannotBeErasedByLaterPassiveCompletion() = runBlocking {
        val owners = registry()
        val receipt = CompletableDeferred<Unit>()
        val receipts = AtomicInteger()
        val stops = AtomicInteger()
        val exactFailure = IllegalStateException("first exact close failed")
        val peer = object : Peer() {
            override fun close() { if (stops.incrementAndGet() == 1) throw exactFailure }
            override suspend fun awaitExit(): Int { receipt.await(); receipts.incrementAndGet(); return 0 }
        }
        owners.retain(peer)
        try {
            val failed = withTimeout(3_000) { runCatching { owners.stopAndAwaitAll() } }
            assertTrue(all(checkNotNull(failed.exceptionOrNull())).any { it === exactFailure })
            receipt.complete(Unit)
            eventually("Natural completion must happen before explicit retry") { receipts.get() > 0 }
            delay(100)
            assertEquals("No implicit failure retry", 1, stops.get())
            withTimeout(3_000) { owners.stopAndAwaitAll() }
            assertEquals("Failure remains owned until a new explicit stop succeeds", 2, stops.get())
        } finally { receipt.complete(Unit) }
    }

    @Test(timeout = 10_000) fun failedAwaitRetriesOnlyOnNewRequestAndSuccessDoesNotReplay() = runBlocking {
        val owners = registry()
        val stops = AtomicInteger()
        val failure = IllegalArgumentException("exact receipt failed")
        val permit = java.util.concurrent.atomic.AtomicBoolean(false)
        val peer = object : Peer() {
            override fun close() { stops.incrementAndGet() }
            override suspend fun awaitExit(): Int { if (!permit.get()) throw failure; return 0 }
        }
        owners.retain(peer)
        val first = withTimeout(3_000) { runCatching { owners.stopAndAwaitAll() } }
        assertTrue(all(checkNotNull(first.exceptionOrNull())).any { it === failure })
        delay(100); assertEquals(1, stops.get())
        val second = withTimeout(3_000) { runCatching { owners.stopAndAwaitAll() } }
        assertTrue(second.isFailure); assertEquals(2, stops.get())
        permit.set(true)
        withTimeout(3_000) { owners.stopAndAwaitAll() }
        assertEquals(3, stops.get())
        withTimeout(3_000) { owners.stopAndAwaitAll() }
        assertEquals(3, stops.get())
    }

    @Test(timeout = 12_000) fun rejectedAdmissionAndExitSharePendingExactClose() = runBlocking {
        val owners = registry()
        val stops = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val receipt = CompletableDeferred<Unit>()
        val peer = object : Peer() {
            override fun close() {
                stops.incrementAndGet(); entered.countDown()
                check(release.await(6, TimeUnit.SECONDS)); receipt.complete(Unit)
            }
            override suspend fun awaitExit(): Int { receipt.await(); return 0 }
        }
        val rejected = async(Dispatchers.Default) { runCatching { owners.admit(peer, "expired") } }
        var exit: Deferred<Result<Unit>>? = null
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            exit = async(Dispatchers.Default) { runCatching { owners.stopAndAwaitAll() } }
            rejected.cancel()
            delay(150)
            assertEquals("Rejected handoff and global Exit cannot concurrently close one exact handle", 1, stops.get())
            assertFalse("Rejected handoff cleanup remains retained after caller cancellation", rejected.isCompleted)
            assertFalse(exit.isCompleted)
            release.countDown()
            withTimeout(3_000) { rejected.join(); exit.await().getOrThrow() }
        } finally { release.countDown(); receipt.complete(Unit); withTimeout(3_000) { rejected.join(); exit?.join() } }
        owners.stopAndAwaitAll(); assertEquals(1, stops.get())
    }

    @Test(timeout = 12_000) fun newerSameHandleObservationSurvivesStaleStopCompletion() = runBlocking {
        val owners = registry()
        val entered = List(3) { CompletableDeferred<Unit>() }
        val release = List(3) { CompletableDeferred<Unit>() }
        val sequence = AtomicInteger()
        val stops = AtomicInteger()
        val peer = object : Peer() {
            override fun close() { stops.incrementAndGet() }
            override suspend fun awaitExit(): Int {
                val index = sequence.getAndIncrement()
                if (index < 3) { entered[index].complete(Unit); release[index].await() }
                return 0
            }
        }
        owners.retain(peer); withTimeout(3_000) { entered[0].await() }
        val first = async(Dispatchers.Default) { runCatching { owners.stopAndAwaitAll() } }
        try {
            withTimeout(3_000) { entered[1].await() }
            owners.retain(peer); withTimeout(3_000) { entered[2].await() }
            release[0].complete(Unit); release[1].complete(Unit)
            assertTrue("An old stop cannot retire the newer exact observation", withTimeout(3_000) { first.await() }.isFailure)
            assertEquals(1, stops.get())
            release[2].complete(Unit)
            withTimeout(3_000) { while (runCatching { owners.stopAndAwaitAll() }.isFailure) delay(5) }
            assertEquals(1, stops.get())
        } finally { release.forEach { it.complete(Unit) }; withTimeout(3_000) { first.join() } }
    }

    @Test(timeout = 12_000) fun rejectedAdmissionCannotEraseNewerRetainedReceipt() = runBlocking {
        val owners = registry()
        val entered = List(3) { CompletableDeferred<Unit>() }
        val release = List(3) { CompletableDeferred<Unit>() }
        val sequence = AtomicInteger()
        val peer = object : Peer() {
            override suspend fun awaitExit(): Int {
                val index = sequence.getAndIncrement()
                if (index < 3) { entered[index].complete(Unit); release[index].await() }
                return 0
            }
        }
        val rejected = async(Dispatchers.Default) { runCatching { owners.admit(peer, "expired") } }
        try {
            withTimeout(3_000) { entered[0].await(); entered[1].await() }
            owners.retain(peer); withTimeout(3_000) { entered[2].await() }
            release[0].complete(Unit); release[1].complete(Unit)
            assertTrue(withTimeout(3_000) { rejected.await() }.exceptionOrNull() is CancellationException)
            val next = async(Dispatchers.Default) { runCatching { owners.stopAndAwaitAll() } }
            delay(100)
            assertFalse("Rejected cleanup cannot erase a newer unconfirmed observation",
                next.isCompleted && next.await().isSuccess)
            release[2].complete(Unit)
            withTimeout(3_000) { next.join() }
            withTimeout(3_000) { while (runCatching { owners.stopAndAwaitAll() }.isFailure) delay(5) }
        } finally { release.forEach { it.complete(Unit) }; withTimeout(3_000) { rejected.join() } }
    }

    @Test(timeout = 10_000) fun userReopenCreatesUsableEpochAfterSuccessfulExit() = runBlocking {
        var nextEpoch = 0
        var persisted: ApplicationWorkAdmissionState.Snapshot? = null
        val admission = ApplicationWorkAdmissionState(read = { persisted }, write = { persisted = it },
            newEpoch = { (++nextEpoch).toString(16).padStart(32, '0') })
        admission.initialize()
        val owners = ExitHostedProcessOwners({ admission.isOpen }, admission::isCurrentOpen)
        class EpochPeer : Peer() {
            val receipt = CompletableDeferred<Unit>()
            val stops = AtomicInteger()
            override fun close() { stops.incrementAndGet(); receipt.complete(Unit) }
            override suspend fun awaitExit(): Int { receipt.await(); return 0 }
        }
        val first = EpochPeer(); val second = EpochPeer()
        try {
            val epoch1 = admission.reopenFromUser()
            assertSame(first, owners.admit(first, epoch1))
            admission.closeForExit(); withTimeout(3_000) { owners.stopAndAwaitAll() }
            val epoch2 = admission.reopenFromUser()
            assertNotEquals(epoch1, epoch2)
            assertSame(second, owners.admit(second, epoch2))
            delay(100)
            assertEquals("A previous Exit must not inherit permanently closing session state", 0, second.stops.get())
            admission.closeForExit(); withTimeout(3_000) { owners.stopAndAwaitAll() }
            assertEquals(1, first.stops.get()); assertEquals(1, second.stops.get())
        } finally { first.receipt.complete(Unit); second.receipt.complete(Unit) }
    }

    @Test(timeout = 12_000) fun rejectedEpochCheckReusesStopAlreadyCompletedDuringCheck() = runBlocking {
        val checking = CountDownLatch(1)
        val releaseCheck = CountDownLatch(1)
        val owners = ExitHostedProcessOwners(isOpen = { false }, isCurrentOpen = {
            checking.countDown()
            check(releaseCheck.await(6, TimeUnit.SECONDS))
            false
        })
        val receipt = CompletableDeferred<Unit>()
        val stops = AtomicInteger()
        val peer = object : Peer() {
            override fun close() { stops.incrementAndGet(); receipt.complete(Unit) }
            override suspend fun awaitExit(): Int { receipt.await(); return 0 }
        }
        val rejected = async(Dispatchers.Default) { runCatching { owners.admit(peer, "expired") } }
        try {
            assertTrue(checking.await(2, TimeUnit.SECONDS))
            withTimeout(3_000) { owners.stopAndAwaitAll() }
            assertEquals(1, stops.get())
            releaseCheck.countDown()
            assertTrue(withTimeout(3_000) { rejected.await() }.exceptionOrNull() is CancellationException)
            assertEquals("A retired successful attempt remains the rejected handoff receipt", 1, stops.get())
        } finally { releaseCheck.countDown(); receipt.complete(Unit); withTimeout(3_000) { rejected.join() } }
    }

    @Test(timeout = 12_000) fun lateDistinctHandleCannotDisappearBehindEarlierSnapshot() = runBlocking {
        val owners = registry()
        val firstReceipt = CompletableDeferred<Unit>()
        val lateReceipt = CompletableDeferred<Unit>()
        val firstStops = AtomicInteger(); val lateStops = AtomicInteger()
        val first = object : Peer() {
            override fun close() { firstStops.incrementAndGet() }
            override suspend fun awaitExit(): Int { firstReceipt.await(); return 0 }
        }
        val late = object : Peer() {
            override fun close() { lateStops.incrementAndGet() }
            override suspend fun awaitExit(): Int { lateReceipt.await(); return 0 }
        }
        owners.retain(first)
        val exit = async(Dispatchers.Default) { runCatching { owners.stopAndAwaitAll() } }
        try {
            eventually("First stop is issued") { firstStops.get() == 1 }
            owners.retain(late)
            firstReceipt.complete(Unit)
            assertTrue("Earlier snapshot must report retained late work", withTimeout(3_000) { exit.await() }.isFailure)
            val retry = async(Dispatchers.Default) { runCatching { owners.stopAndAwaitAll() } }
            eventually("Explicit retry dispatches the late exact handle") { lateStops.get() == 1 }
            assertFalse(retry.isCompleted)
            lateReceipt.complete(Unit); withTimeout(3_000) { retry.await().getOrThrow() }
            assertEquals(1, firstStops.get()); assertEquals(1, lateStops.get())
        } finally { firstReceipt.complete(Unit); lateReceipt.complete(Unit); withTimeout(3_000) { exit.join() } }
    }

    @Test(timeout = 12_000) fun equalButDistinctHandlesRequireSeparateReceipts() = runBlocking {
        val owners = registry()
        class EqualPeer : Peer() {
            val receipt = CompletableDeferred<Unit>()
            val stops = AtomicInteger()
            override fun equals(other: Any?) = other is EqualPeer
            override fun hashCode() = 7
            override fun close() { stops.incrementAndGet() }
            override suspend fun awaitExit(): Int { receipt.await(); return 0 }
        }
        val first = EqualPeer(); val second = EqualPeer()
        owners.retain(first); owners.retain(second)
        val exit = async(Dispatchers.Default) { runCatching { owners.stopAndAwaitAll() } }
        try {
            eventually("Both exact objects must receive close") { first.stops.get() == 1 && second.stops.get() == 1 }
            first.receipt.complete(Unit); delay(100)
            assertFalse("Equal handles cannot substitute receipts", exit.isCompleted)
            second.receipt.complete(Unit); withTimeout(3_000) { exit.await().getOrThrow() }
        } finally { first.receipt.complete(Unit); second.receipt.complete(Unit); withTimeout(3_000) { exit.join() } }
    }

    @Test(timeout = 12_000) fun suspendedReceiptsDoNotRetainNativeCloseThreads() = runBlocking {
        val owners = registry()
        val closeThreads = CopyOnWriteArrayList<Thread>()
        val receipt = CompletableDeferred<Unit>()
        repeat(12) { owners.retain(object : Peer() {
            override fun close() { closeThreads += Thread.currentThread() }
            override suspend fun awaitExit(): Int { receipt.await(); return 0 }
        }) }
        val exit = async(Dispatchers.Default) { runCatching { owners.stopAndAwaitAll() } }
        try {
            eventually("Every synchronous close must run with IO parallelism one") { closeThreads.size == 12 }
            eventually("Normal close workers must end while receipts remain suspended") { closeThreads.none { it.isAlive } }
            assertFalse(exit.isCompleted)
            receipt.complete(Unit); withTimeout(3_000) { exit.await().getOrThrow() }
        } finally { receipt.complete(Unit); withTimeout(3_000) { exit.join() } }
    }

    @Test fun productionOriginsAreFreshlyCompiled() {
        listOf(ExitHostedProcessOwners::class.java, WorkspaceProcessWriteAdmission::class.java,
            ApplicationWorkAdmissionState::class.java, OwnedResourceCleanup::class.java).forEach {
            println("ORIGIN ${it.name} ${it.protectionDomain?.codeSource?.location}")
        }
    }

    private fun all(error: Throwable): List<Throwable> = listOf(error) +
        error.suppressed.flatMap(::all) + (error.cause?.let(::all) ?: emptyList())
}
