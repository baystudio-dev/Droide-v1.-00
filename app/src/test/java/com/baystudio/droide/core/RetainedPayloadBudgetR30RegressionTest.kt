package com.baystudio.droide.core

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Tiny resource-lifetime contracts; no subprocess and no production before/after claim. */
class RetainedPayloadBudgetR30RegressionTest {
    @Test(timeout = 10_000) fun aggregateAdmissionAndIdempotentReleaseUseTinyWeights() {
        val budget = RetainedPayloadBudget(8, 2)
        val first = requireNotNull(budget.tryAcquire(5))
        assertNull(budget.tryAcquire(4))
        val second = requireNotNull(budget.tryAcquire(3))
        assertEquals(8L, budget.retainedChars)
        first.release(); first.release()
        assertEquals(3L, budget.retainedChars)
        assertNull(budget.tryAcquire(Long.MAX_VALUE))
        second.release()
        assertEquals(0L, budget.retainedChars)
    }

    @Test(timeout = 10_000) fun cancelledLazyJobReleasesWithoutEnteringBody() = runBlocking {
        val budget = RetainedPayloadBudget(4, 1)
        val lease = requireNotNull(budget.tryAcquire(4))
        var entered = false
        val job = launch(start = CoroutineStart.LAZY) { entered = true }
        job.invokeOnCompletion { lease.release() }
        job.cancelAndJoin()
        assertFalse(entered)
        assertEquals(0L, budget.retainedChars)
    }

    @Test(timeout = 10_000) fun mapRemovalAndCancelDoNotReleaseStillUnwindingJobOrRemoveReplacement() = runBlocking {
        val budget = RetainedPayloadBudget(8, 2)
        val calls = ConcurrentHashMap<String, Job>()
        val entered = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val firstLease = requireNotNull(budget.tryAcquire(5))
        val first = launch(start = CoroutineStart.LAZY) {
            try { entered.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) { cleanupEntered.complete(Unit); releaseCleanup.await() } }
        }
        first.invokeOnCompletion { calls.remove("same", first); firstLease.release() }
        calls["same"] = first
        first.start()
        var replacement: Job? = null
        try {
            withTimeout(1_000) { entered.await() }
            assertTrue(calls.remove("same", first))
            first.cancel()
            withTimeout(1_000) { cleanupEntered.await() }
            assertEquals(5L, budget.retainedChars)
            assertNull(budget.tryAcquire(4))
            val nextLease = requireNotNull(budget.tryAcquire(3))
            val next = launch(start = CoroutineStart.LAZY) { awaitCancellation() }
            replacement = next
            next.invokeOnCompletion { calls.remove("same", next); nextLease.release() }
            calls["same"] = next
            next.start()
            releaseCleanup.complete(Unit)
            withTimeout(1_000) { first.join() }
            assertSame(next, calls["same"])
            assertEquals(3L, budget.retainedChars)
        } finally {
            releaseCleanup.complete(Unit)
            first.cancelAndJoin()
            replacement?.cancelAndJoin()
        }
        assertTrue(calls.isEmpty())
        assertEquals(0L, budget.retainedChars)
    }

    @Test(timeout = 10_000) fun completionHookCleansNormalFailureAndDuplicateBeforeStart() = runBlocking {
        val budget = RetainedPayloadBudget(8, 2)
        supervisorScope {
            val normalLease = requireNotNull(budget.tryAcquire(3))
            val normal = launch(start = CoroutineStart.LAZY) { }
            normal.invokeOnCompletion { normalLease.release() }
            normal.start(); normal.join()
            assertEquals(0L, budget.retainedChars)
            val failedLease = requireNotNull(budget.tryAcquire(3))
            val failed = async(start = CoroutineStart.LAZY) { error("expected") }
            failed.invokeOnCompletion { failedLease.release() }
            failed.start()
            try { failed.await(); fail("Expected failure") } catch (_: IllegalStateException) { }
            assertEquals(0L, budget.retainedChars)
            val duplicateLease = requireNotNull(budget.tryAcquire(3))
            val duplicate = launch(start = CoroutineStart.LAZY) { fail("Duplicate must never start") }
            duplicate.invokeOnCompletion { duplicateLease.release() }
            duplicate.cancelAndJoin()
            assertEquals(0L, budget.retainedChars)
        }
    }
    @Test(timeout = 10_000) fun removedButStillOwnedPayloadKeepsTheTinyCountReservation() {
        val budget = RetainedPayloadBudget(8, 1)
        val old = requireNotNull(budget.tryAcquire(1))
        // Removal from a request map does not release this job-owned lease.
        assertNull(budget.tryAcquire(0))
        assertEquals(1, budget.retainedCount)
        old.release(); old.release()
        assertEquals(0, budget.retainedCount)
        val replacement = requireNotNull(budget.tryAcquire(1))
        replacement.release()
        assertEquals(0L, budget.retainedChars)
    }

}
