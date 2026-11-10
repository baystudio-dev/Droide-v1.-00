package com.baystudio.droide.core

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** Production coordinator with authored coroutines only; no original server/compiler/runtime runs. */
class LspNimRequestsTest {
    private class Fixture(clock: () -> Long = System::nanoTime) : AutoCloseable {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        val failures = AtomicInteger()
        val deadline = CompletableDeferred<String>()
        val requests = LspNimRequests(scope, { failures.incrementAndGet(); deadline.complete(it) }, clock)
        override fun close() { requests.close(); job.cancel() }
        suspend fun request(method: String = "textDocument/definition", current: () -> Boolean = { true }, block: suspend () -> JsonElement) =
            requests.request(method, current, block)
    }
    private suspend fun until(test: () -> Boolean) = withTimeout(3_000) { while (!test()) delay(2) }

    @Test(timeout = 5000) fun liveDeadlineBeforeLazyDispatchRetiresButDetachedDemandDoesNot() = runBlocking {
        for (detach in listOf(false, true)) {
            val pending = ArrayDeque<Runnable>()
            val dispatcher = object : CoroutineDispatcher() {
                override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { pending.add(block) }
            }
            val job = SupervisorJob(); val now = AtomicLong(); val failures = AtomicInteger()
            val requests = LspNimRequests(CoroutineScope(job + dispatcher), { failures.incrementAndGet() }, now::get)
            try {
                val caller = async(start = CoroutineStart.UNDISPATCHED) {
                    requests.request("textDocument/definition", { true }) { fail("Expired demand must not dispatch"); JsonPrimitive(0) }
                }
                assertTrue(pending.isNotEmpty())
                if (detach) { caller.cancelAndJoin() }
                now.set(10_000_000_000L)
                while (pending.isNotEmpty()) pending.removeFirst().run()
                if (!detach) assertNull(caller.await())
                assertEquals(if (detach) 0 else 1, failures.get())
            } finally {
                requests.close(); job.cancel()
                while (pending.isNotEmpty()) pending.removeFirst().run()
            }
        }
    }

    @Test(timeout = 5000) fun currentOwnerAndMethodAdmissionAreCheckedBeforeDispatchAndConsumption() = runBlocking {
        Fixture().use { fixture ->
            assertNull(fixture.request(current = { false }) { fail("Stale must not dispatch"); JsonPrimitive(1) })
            val current = AtomicBoolean(true)
            assertNull(fixture.request(current = current::get) { current.set(false); JsonPrimitive(1) })
            assertTrue(runCatching { fixture.request("workspace/executeCommand") { JsonPrimitive(1) } }.isFailure)
            assertEquals(JsonPrimitive(2), fixture.request { JsonPrimitive(2) })
            fixture.requests.close()
            assertNull(fixture.request { fail("Closed owner must not dispatch"); JsonPrimitive(1) })
        }
    }

    @Test(timeout = 10000) fun allThreeLatestFamiliesKeepOnlyActiveAndNewestAndSuppressOldResults() = runBlocking {
        for (family in listOf("textDocument/completion", "textDocument/signatureHelp", "textDocument/hover")) {
            Fixture().use { fixture ->
                val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                val calls = AtomicInteger()
                val active = async(start = CoroutineStart.UNDISPATCHED) {
                    fixture.request(family) { calls.incrementAndGet(); entered.complete(Unit); release.await(); JsonPrimitive("old") }
                }
                entered.await()
                val superseded = async(start = CoroutineStart.UNDISPATCHED) {
                    fixture.request(family) { fail("Old queued request cannot dispatch"); JsonPrimitive(0) }
                }
                val latest = async(start = CoroutineStart.UNDISPATCHED) {
                    fixture.request(family) { calls.incrementAndGet(); JsonPrimitive("latest") }
                }
                assertNull(superseded.await())
                assertEquals(1, calls.get())
                release.complete(Unit)
                assertNull(active.await())
                assertEquals(JsonPrimitive("latest"), latest.await())
                assertEquals(2, calls.get())
            }
        }
    }

    @Test(timeout = 5000) fun independentLatestFamiliesDoNotBlockEachOther() = runBlocking {
        Fixture().use { fixture ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val completion = async { fixture.request("textDocument/completion") { entered.complete(Unit); release.await(); JsonPrimitive(1) } }
            entered.await()
            assertEquals(JsonPrimitive(2), fixture.request("textDocument/hover") { JsonPrimitive(2) })
            assertEquals(JsonPrimitive(3), fixture.request("textDocument/signatureHelp") { JsonPrimitive(3) })
            release.complete(Unit); assertEquals(JsonPrimitive(1), completion.await())
        }
    }

    @Test(timeout = 5000) fun cancelledQueuedDemandsDoNotAccumulateWorkersOrDispatch() = runBlocking {
        Fixture().use { fixture ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val first = launch { fixture.request("textDocument/completion") { entered.complete(Unit); release.await(); JsonPrimitive(1) } }
            entered.await(); first.cancelAndJoin()
            repeat(300) {
                val cancel = launch(start = CoroutineStart.UNDISPATCHED) {
                    fixture.request("textDocument/completion") { fail("Canceled queue dispatched"); JsonPrimitive(2) }
                }
                cancel.cancelAndJoin()
            }
            val last = async(start = CoroutineStart.UNDISPATCHED) { fixture.request("textDocument/completion") { JsonPrimitive("latest") } }
            assertTrue(fixture.job.children.single().children.count() <= 2)
            release.complete(Unit)
            assertEquals(JsonPrimitive("latest"), last.await())
        }
    }

    @Test(timeout = 5000) fun callerCancellationPreservesItsObjectAndStartedWorkStillOwnsCapacity() = runBlocking {
        Fixture().use { fixture ->
            val entered = AtomicInteger(); val drained = AtomicInteger(); val release = CompletableDeferred<Unit>()
            val caught = CompletableDeferred<CancellationException>()
            val callers = (1..16).map { number -> launch(start = CoroutineStart.UNDISPATCHED) {
                try { fixture.request { entered.incrementAndGet(); release.await(); drained.incrementAndGet(); JsonPrimitive(number) } }
                catch (cancel: CancellationException) { if (number == 1) caught.complete(cancel); throw cancel }
            } }
            until { entered.get() == 16 }
            val reason = CancellationException("popup dismissed")
            callers.first().cancel(reason); callers.first().join()
            assertSame(reason, caught.await())
            callers.drop(1).forEach { it.cancelAndJoin() }
            assertNull(fixture.request { fail("All sixteen slots remain natively owned"); JsonPrimitive(17) })
            release.complete(Unit); until { drained.get() == 16 }
            until { fixture.job.children.single().children.none() }
            assertEquals(JsonPrimitive("free"), fixture.request { JsonPrimitive("free") })
        }
    }

    @Test(timeout = 5000) fun replacingQueueAtSixteenRecordsFreesOnlyThatQueueSlot() = runBlocking {
        Fixture().use { fixture ->
            val entered = AtomicInteger(); val release = CompletableDeferred<Unit>()
            val ordinary = (1..14).map { async { fixture.request { entered.incrementAndGet(); release.await(); JsonPrimitive(it) } } }
            val active = async { fixture.request("textDocument/hover") { entered.incrementAndGet(); release.await(); JsonPrimitive("old") } }
            until { entered.get() == 15 }
            val queued = async(start = CoroutineStart.UNDISPATCHED) { fixture.request("textDocument/hover") { fail("Superseded"); JsonPrimitive(0) } }
            val latest = async(start = CoroutineStart.UNDISPATCHED) { fixture.request("textDocument/hover") { JsonPrimitive("latest") } }
            assertNull(queued.await())
            assertNull(fixture.request { fail("Seventeenth record dispatched"); JsonPrimitive(17) })
            release.complete(Unit); ordinary.awaitAll(); assertNull(active.await())
            assertEquals(JsonPrimitive("latest"), latest.await())
        }
    }

    @Test(timeout = 5000) fun expiredQueueDoesNotReceiveANewBudgetOrDispatch() = runBlocking {
        val time = AtomicLong(0)
        Fixture(time::get).use { fixture ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val first = async { fixture.request("textDocument/hover") { entered.complete(Unit); release.await(); JsonPrimitive(1) } }
            entered.await()
            val queued = async(start = CoroutineStart.UNDISPATCHED) { fixture.request("textDocument/hover") { fail("Expired queue dispatched"); JsonPrimitive(2) } }
            time.set(10_000_000_001)
            release.complete(Unit); assertNull(first.await()); assertNull(queued.await())
            assertEquals(1, fixture.failures.get())
        }
    }

    @Test(timeout = 5000) fun promotionUsesRemainingAbsoluteBudgetAndRetiresOnlyExactOwner() = runBlocking {
        val time = AtomicLong(0)
        Fixture(time::get).use { old -> Fixture().use { replacement ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
            val first = async { old.request("textDocument/completion") { entered.complete(Unit); release.await(); JsonPrimitive(1) } }
            entered.await(); time.set(1_000_000_000)
            val latest = async(start = CoroutineStart.UNDISPATCHED) {
                old.request("textDocument/completion") { try { awaitCancellation() } finally { cancelled.complete(Unit) } }
            }
            time.set(9_990_000_000); release.complete(Unit)
            assertNull(first.await()); assertNull(withTimeout(2500) { latest.await() })
            assertEquals("textDocument/completion", withTimeout(1000) { old.deadline.await() })
            cancelled.await(); assertEquals(1, old.failures.get())
            assertEquals(JsonPrimitive("new"), replacement.request { JsonPrimitive("new") })
        } }
    }

    @Test(timeout = 5000) fun completedResultAfterAbsoluteDeadlineCannotPublish() = runBlocking {
        val time = AtomicLong(0)
        Fixture(time::get).use { fixture ->
            assertNull(fixture.request { time.set(10_000_000_000); JsonPrimitive("late") })
            assertEquals(1, fixture.failures.get())
            assertNull(fixture.request { fail("Expired owner dispatched"); JsonPrimitive(1) })
        }
    }

    @Test(timeout = 13000) fun realTenSecondDeadlineEndsDetachedWorkAndFailsOwnerExactlyOnce() = runBlocking {
        Fixture().use { fixture ->
            val entered = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
            val caller = launch {
                fixture.request("textDocument/definition") {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                }
            }
            entered.await(); caller.cancelAndJoin()
            assertEquals("textDocument/definition", withTimeout(11_000) { fixture.deadline.await() })
            cancelled.await(); assertEquals(1, fixture.failures.get())
            assertNull(fixture.request { fail("Expired owner dispatched"); JsonPrimitive(1) })
            fixture.requests.close(); assertEquals(1, fixture.failures.get())
        }
    }

    @Test(timeout = 5000) fun completedErrorsAndUnrelatedNestedTimeoutDoNotInventOwnerDeadlines() = runBlocking {
        Fixture().use { fixture ->
            assertNull(fixture.request { throw IllegalStateException("authored remote error") })
            assertNull(fixture.request { withTimeout(5) { awaitCancellation() } })
            assertNull(fixture.request { throw CancellationException("foreign cancellation") })
            assertEquals(0, fixture.failures.get())
            assertEquals(JsonPrimitive("live"), fixture.request { JsonPrimitive("live") })
        }
    }

    @Test(timeout = 5000) fun closeCancelsExactActiveAttemptAndQueuedRecordsWithoutTouchingSuccessor() = runBlocking {
        Fixture().use { old -> Fixture().use { next ->
            val entered = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
            val active = async { old.request("textDocument/hover") { entered.complete(Unit); try { awaitCancellation() } finally { cancelled.complete(Unit) } } }
            entered.await()
            val queued = async(start = CoroutineStart.UNDISPATCHED) { old.request("textDocument/hover") { fail("Closed queue dispatched"); JsonPrimitive(1) } }
            old.requests.close()
            assertNull(active.await()); assertNull(queued.await()); cancelled.await()
            assertEquals(0, old.failures.get())
            assertEquals(JsonPrimitive(1), next.request { JsonPrimitive(1) })
        } }
    }

    @Test(timeout = 5000) fun parentCancellationClosesAttemptsWithoutInvokingDeadline() = runBlocking {
        Fixture().use { fixture ->
            val entered = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
            val caller = async {
                try { fixture.request { entered.complete(Unit); try { awaitCancellation() } finally { cancelled.complete(Unit) } } }
                catch (_: CancellationException) { null }
            }
            entered.await(); fixture.job.cancel(); cancelled.await(); assertNull(caller.await())
            assertEquals(0, fixture.failures.get())
            assertNull(fixture.request { fail("Canceled owner dispatched"); JsonPrimitive(1) })
        }
    }

    private fun deadlinePublicationControl(reentrant: Boolean) = runBlocking {
        val time = AtomicLong(0); val parent = SupervisorJob(); val scope = CoroutineScope(parent + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>(); val release = java.util.concurrent.CountDownLatch(1)
        val fenced = AtomicBoolean(false); val writes = AtomicInteger()
        lateinit var coordinator: LspNimRequests
        coordinator = LspNimRequests(scope, {
            if (reentrant) coordinator.close()
            entered.complete(Unit)
            check(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
            fenced.set(true)
        }, time::get)
        val caller = async { coordinator.request("textDocument/definition", { true }) {
            writes.incrementAndGet(); time.set(10_000_000_000L); JsonPrimitive("late")
        } }
        try {
            withTimeout(2000) { entered.await() }
            // This is a declared scheduler boundary at the real deadline callback, not a timing-only race.
            delay(100)
            assertFalse("Unavailable waiter escaped before the manager fence", caller.isCompleted)
            assertFalse(fenced.get())
            assertNull(coordinator.request("textDocument/definition", { true }) { writes.incrementAndGet(); JsonPrimitive("unwanted") })
            assertEquals(1, writes.get())
            release.countDown(); assertNull(withTimeout(2000) { caller.await() }); assertTrue(fenced.get())
        } finally { release.countDown(); caller.cancelAndJoin(); coordinator.close(); parent.cancelAndJoin() }
    }
    @Test(timeout=5000) fun deadlineFenceCompletesBeforeUnavailableWaiterPublication() = deadlinePublicationControl(false)
    @Test(timeout=5000) fun reentrantCloseCannotReleaseWaiterBeforeDeadlineFenceCompletes() = deadlinePublicationControl(true)
    @Test(timeout=5000) fun throwingDeadlineFenceStillReleasesOwnershipWithoutDispatchingMoreWork() = runBlocking {
        val time=AtomicLong(0); val job=SupervisorJob(); val failures=AtomicInteger(); val writes=AtomicInteger()
        val coordinator=LspNimRequests(CoroutineScope(job+Dispatchers.Default),{failures.incrementAndGet();error("authored fence failure")},time::get)
        try {
            assertNull(withTimeout(2000){coordinator.request("textDocument/definition",{true}){writes.incrementAndGet();time.set(10_000_000_000L);JsonPrimitive("late")}})
            withTimeout(2000){while(failures.get()!=1)yield()}
            assertNull(coordinator.request("textDocument/definition",{true}){writes.incrementAndGet();JsonPrimitive("unwanted")})
            assertEquals(1,writes.get())
        } finally {coordinator.close();job.cancelAndJoin()}
    }
    @Test(timeout=5000) fun expiredUndispatchedDemandNeverWritesAndFencesExactlyOnce() = runBlocking {
        val tasks=ArrayDeque<Runnable>(); val dispatcher=object:CoroutineDispatcher(){override fun dispatch(context:kotlin.coroutines.CoroutineContext,block:Runnable){tasks.add(block)}}
        val clock=AtomicLong(0); val failures=AtomicInteger(); val writes=AtomicInteger(); val job=SupervisorJob()
        val coordinator=LspNimRequests(CoroutineScope(job+dispatcher),{failures.incrementAndGet()},clock::get)
        val caller=async(start=CoroutineStart.UNDISPATCHED){coordinator.request("textDocument/definition",{true}){writes.incrementAndGet();JsonPrimitive("unwanted")}}
        try {clock.set(10_000_000_000L);while(tasks.isNotEmpty())tasks.removeFirst().run();assertNull(caller.await());assertEquals(0,writes.get());assertEquals(1,failures.get())}
        finally {coordinator.close();job.cancel();while(tasks.isNotEmpty())tasks.removeFirst().run()}
    }
}
