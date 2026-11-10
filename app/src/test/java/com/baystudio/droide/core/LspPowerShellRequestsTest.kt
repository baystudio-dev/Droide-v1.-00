package com.baystudio.droide.core

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class LspPowerShellRequestsTest {
    private class Fixture(clock: () -> Long = System::nanoTime) : AutoCloseable {
        val managerJob = SupervisorJob()
        val managerScope = CoroutineScope(managerJob + Dispatchers.Default)
        val failures = AtomicInteger()
        val deadline = CompletableDeferred<String>()
        val requests = LspPowerShellRequests(managerScope, {
            failures.incrementAndGet()
            deadline.complete(it)
        }, clock)
        override fun close() { requests.close(); managerJob.cancel() }
    }

    private suspend fun Fixture.request(
        method: String = "textDocument/hover",
        timeout: Long = 2_000,
        current: () -> Boolean = { true },
        block: suspend () -> JsonElement
    ) = requests.request(method, timeout, false, current, block)

    private suspend fun Fixture.complete(
        current: () -> Boolean = { true },
        block: suspend () -> JsonElement
    ) = requests.request("textDocument/completion", 4_000, true, current, block)

    private suspend fun until(test: () -> Boolean) = withTimeout(3_000) {
        while (!test()) delay(2)
    }

    @Test(timeout = 10_000) fun threeSecondCompletionOutlivesOldUiBudget() = runBlocking {
        Fixture().use { f ->
            assertEquals(JsonPrimitive("native"), f.complete { delay(3_000); JsonPrimitive("native") })
            assertEquals(0, f.failures.get())
        }
    }

    @Test(timeout = 10_000) fun callerCancellationKeepsExactObjectAndNativeTaskAlive() = runBlocking {
        Fixture().use { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val drained = CompletableDeferred<Unit>()
            val caught = CompletableDeferred<CancellationException>()
            val caller = launch {
                try { f.request { entered.complete(Unit); release.await(); drained.complete(Unit); JsonPrimitive(1) } }
                catch (cancel: CancellationException) { caught.complete(cancel); throw cancel }
            }
            entered.await()
            val cancellation = CancellationException("popup replaced")
            caller.cancel(cancellation)
            caller.join()
            assertSame(cancellation, caught.await())
            assertFalse(drained.isCompleted)
            assertEquals(0, f.failures.get())
            release.complete(Unit)
            withTimeout(1_000) { drained.await() }
            assertEquals(JsonPrimitive(2), f.request { JsonPrimitive(2) })
        }
    }

    @Test(timeout = 10_000) fun detachedNativeTaskStillReachesOriginalDeadline() = runBlocking {
        Fixture().use { f ->
            val entered = CompletableDeferred<Unit>()
            val canceled = CompletableDeferred<Unit>()
            val caller = launch {
                f.request(timeout = 150) {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { canceled.complete(Unit) }
                }
            }
            entered.await()
            caller.cancelAndJoin()
            assertEquals("textDocument/hover", withTimeout(2_000) { f.deadline.await() })
            withTimeout(1_000) { canceled.await() }
            assertEquals(1, f.failures.get())
            assertNull(f.request { fail("Retired owner cannot dispatch"); JsonPrimitive(1) })
        }
    }

    @Test(timeout = 10_000) fun actualFourSecondNativeRpcTimeoutFailsOnlyThisOwnerOnce() = runBlocking {
        Fixture().use { old -> Fixture().use { replacement ->
            assertNull(old.complete { withTimeout(4_000) { awaitCancellation() } })
            assertEquals("textDocument/completion", withTimeout(1_000) { old.deadline.await() })
            assertEquals(1, old.failures.get())
            old.requests.close()
            assertEquals(JsonPrimitive("replacement"), replacement.complete { JsonPrimitive("replacement") })
            assertEquals(0, replacement.failures.get())
        } }
    }

    @Test(timeout = 10_000) fun completedRemoteErrorAndUnrelatedTimeoutPreserveOwner() = runBlocking {
        Fixture().use { f ->
            assertNull(f.request { throw IllegalStateException("authored remote feature error") })
            assertNull(f.request { withTimeout(10) { delay(1_000); JsonPrimitive(1) } })
            assertNull(f.request { throw CancellationException("foreign feature cancellation") })
            assertEquals(JsonPrimitive("still live"), f.request { JsonPrimitive("still live") })
            assertEquals(0, f.failures.get())
        }
    }

    @Test(timeout = 10_000) fun completionKeepsOneActiveAndOnlyNewestQueuedCapture() = runBlocking {
        Fixture().use { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                f.complete { calls.incrementAndGet(); entered.complete(Unit); release.await(); JsonPrimitive("first") }
            }
            entered.await()
            val replaced = async(start = CoroutineStart.UNDISPATCHED) {
                f.complete { fail("Superseded completion must never dispatch"); JsonPrimitive("old") }
            }
            val latest = async(start = CoroutineStart.UNDISPATCHED) {
                f.complete { calls.incrementAndGet(); JsonPrimitive("latest capture") }
            }
            assertNull(replaced.await())
            assertEquals(1, calls.get())
            release.complete(Unit)
            assertEquals(JsonPrimitive("first"), first.await())
            assertEquals(JsonPrimitive("latest capture"), latest.await())
            assertEquals(2, calls.get())
            assertEquals(0, f.failures.get())
        }
    }

    @Test(timeout = 10_000) fun hundredsOfPopupCancellationsDoNotStartOrAccumulateQueuedWorkers() = runBlocking {
        Fixture().use { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val first = launch {
                f.complete { calls.incrementAndGet(); entered.complete(Unit); release.await(); JsonPrimitive("drained") }
            }
            entered.await()
            first.cancelAndJoin()
            repeat(300) {
                val canceled = launch(start = CoroutineStart.UNDISPATCHED) {
                    f.complete { calls.incrementAndGet(); JsonPrimitive("must not dispatch") }
                }
                canceled.cancelAndJoin()
            }
            val latest = async(start = CoroutineStart.UNDISPATCHED) {
                f.complete { calls.incrementAndGet(); JsonPrimitive("latest") }
            }
            val owned = f.managerJob.children.single()
            // Active worker + latest waiter. Detached active waiter is already canceled.
            assertTrue("Queued callbacks must not launch 300 waiting workers", owned.children.count() <= 2)
            release.complete(Unit)
            assertEquals(JsonPrimitive("latest"), latest.await())
            assertEquals(2, calls.get())
            assertEquals(0, f.failures.get())
        }
    }

    @Test(timeout = 12_000) fun slowPredecessorAndLatestBothKeepTheirFullNativeBudget() = runBlocking {
        Fixture().use { f ->
            val entered = CompletableDeferred<Unit>()
            val first = async {
                f.complete { entered.complete(Unit); delay(3_000); JsonPrimitive("first after 3s") }
            }
            entered.await()
            val latest = async(start = CoroutineStart.UNDISPATCHED) {
                f.complete { delay(3_000); JsonPrimitive("latest after another 3s") }
            }
            assertEquals(JsonPrimitive("first after 3s"), first.await())
            assertEquals(JsonPrimitive("latest after another 3s"), latest.await())
            assertEquals(0, f.failures.get())
        }
    }

    @Test(timeout = 10_000) fun detachedOrdinaryRequestsStillCountTowardSixteenRecordCap() = runBlocking {
        Fixture().use { f ->
            val entered = AtomicInteger()
            val drained = AtomicInteger()
            val release = CompletableDeferred<Unit>()
            val callers = (1..16).map {
                launch(start = CoroutineStart.UNDISPATCHED) {
                    f.request {
                        entered.incrementAndGet()
                        release.await()
                        drained.incrementAndGet()
                        JsonPrimitive(it)
                    }
                }
            }
            until { entered.get() == 16 }
            callers.forEach { it.cancelAndJoin() }
            assertNull(f.request { fail("Detached native tasks still own all 16 slots"); JsonPrimitive(17) })
            release.complete(Unit)
            until { drained.get() == 16 }
            // Wait for bookkeeping completion, not just the native block's return.
            until { f.managerJob.children.single().children.none() }
            assertEquals(JsonPrimitive("capacity released"), f.request { JsonPrimitive("capacity released") })
            assertEquals(0, f.failures.get())
        }
    }

    @Test(timeout = 10_000) fun canceledQueueIsRemovedAndNeverSent() = runBlocking {
        Fixture().use { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val first = async { f.complete { entered.complete(Unit); release.await(); JsonPrimitive(1) } }
            entered.await()
            val queued = launch(start = CoroutineStart.UNDISPATCHED) {
                f.complete { fail("Canceled queued demand must not dispatch"); JsonPrimitive(2) }
            }
            queued.cancelAndJoin()
            release.complete(Unit)
            assertEquals(JsonPrimitive(1), first.await())
            assertEquals(JsonPrimitive(3), f.complete { JsonPrimitive(3) })
            assertEquals(0, f.failures.get())
        }
    }

    @Test(timeout = 10_000) fun queueExpiryPreservesOwnerAndNeverTruncatesNativeBudget() = runBlocking {
        val clock = AtomicLong(0L)
        Fixture(clock::get).use { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val first = async { f.complete { entered.complete(Unit); release.await(); JsonPrimitive(1) } }
            entered.await()
            // Inject pre-dispatch scheduling/admission delay, after the predecessor has completed, without
            // spending wall-clock time in this one test.
            val expired = async(start = CoroutineStart.UNDISPATCHED) {
                f.complete(current = { clock.set(6_000_000_000L); true }) {
                    fail("Only 3s remain; native 4s must not be truncated")
                    JsonPrimitive(2)
                }
            }
            release.complete(Unit)
            assertEquals(JsonPrimitive(1), first.await())
            assertNull(expired.await())
            assertEquals(0, f.failures.get())
            assertEquals(JsonPrimitive(3), f.complete { JsonPrimitive(3) })
        }
    }

    @Test(timeout = 10_000) fun capSixteenIncludesActiveAndQueuedCompletionAndAllowsQueueReplacement() = runBlocking {
        Fixture().use { f ->
            val release = CompletableDeferred<Unit>()
            val started = AtomicInteger()
            val ordinary = (1..14).map {
                async(start = CoroutineStart.UNDISPATCHED) {
                    f.request { started.incrementAndGet(); release.await(); JsonPrimitive(it) }
                }
            }
            val active = async(start = CoroutineStart.UNDISPATCHED) {
                f.complete { started.incrementAndGet(); release.await(); JsonPrimitive("active") }
            }
            until { started.get() == 15 }
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                f.complete { fail("Old queue was replaced"); JsonPrimitive("old") }
            }
            assertNull(f.request { fail("Seventeenth record must not launch"); JsonPrimitive(17) })
            val latest = async(start = CoroutineStart.UNDISPATCHED) { f.complete { JsonPrimitive("latest") } }
            assertNull(queued.await())
            assertNull(f.request { fail("Replacement must retain its counted slot"); JsonPrimitive(17) })
            release.complete(Unit)
            ordinary.forEach { assertNotNull(it.await()) }
            assertEquals(JsonPrimitive("active"), active.await())
            assertEquals(JsonPrimitive("latest"), latest.await())
            assertEquals(0, f.failures.get())
        }
    }

    @Test(timeout = 10_000) fun staleCaptureIsCheckedAtDispatchAndAtReturn() = runBlocking {
        Fixture().use { f ->
            assertNull(f.request(current = { false }) { fail("Stale capture cannot send"); JsonPrimitive(1) })
            val current = AtomicBoolean(true)
            assertNull(f.request(current = current::get) { current.set(false); JsonPrimitive("stale result") })
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val first = async { f.complete { entered.complete(Unit); release.await(); JsonPrimitive(1) } }
            entered.await()
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                f.complete(current = current::get) { fail("Stale queue cannot send"); JsonPrimitive(2) }
            }
            release.complete(Unit)
            first.await()
            assertNull(queued.await())
            assertEquals(0, f.failures.get())
        }
    }

    @Test(timeout = 10_000) fun closeCancelsActiveNativeAndQueuedWaitersWithoutFailure() = runBlocking {
        Fixture().use { f ->
            val entered = CompletableDeferred<Unit>()
            val canceled = CompletableDeferred<Unit>()
            val active = async {
                f.complete { entered.complete(Unit); try { awaitCancellation() } finally { canceled.complete(Unit) } }
            }
            entered.await()
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                f.complete { fail("Closed queue cannot dispatch"); JsonPrimitive(1) }
            }
            f.requests.close()
            assertTrue(runCatching { active.await() }.exceptionOrNull() is CancellationException)
            assertTrue(runCatching { queued.await() }.exceptionOrNull() is CancellationException)
            withTimeout(1_000) { canceled.await() }
            assertEquals(0, f.failures.get())
            assertNull(f.request { fail("Closed owner cannot dispatch"); JsonPrimitive(1) })
        }
    }

    @Test(timeout = 10_000) fun managerShutdownCancelsAllOwnedRecordsWithoutDeadline() = runBlocking {
        Fixture().use { f ->
            val entered = CompletableDeferred<Unit>()
            val caller = async { f.request { entered.complete(Unit); awaitCancellation() } }
            entered.await()
            f.managerJob.cancel()
            assertTrue(runCatching { caller.await() }.exceptionOrNull() is CancellationException)
            assertEquals(0, f.failures.get())
        }
    }
}
