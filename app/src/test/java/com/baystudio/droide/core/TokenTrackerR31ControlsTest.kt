package com.baystudio.droide.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Tiny offline accounting controls. No provider, image decoder, filesystem or stress workload. */
class TokenTrackerR31ControlsTest {
    private fun response(usage: String) = """{"usage":$usage}"""
    private val known = response("""{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}""")

    @Test fun emptyUnknownAndReportedZeroAreDistinctAndResetTogether() {
        val tracker = TokenTracker()
        assertEquals(0L, tracker.usage.value.requestCount)
        tracker.add("fixture", "{}")
        assertEquals(1L, tracker.usage.value.requestCount)
        assertEquals(0L, tracker.usage.value.knownTokenRequestCount)
        assertEquals(UsageAuthority.UNKNOWN, tracker.usage.value.tokenAuthority)
        val zero = tracker.add("fixture", response("""{"prompt_tokens":0,"completion_tokens":0,"total_tokens":0,"cost":0}"""))
        assertEquals(UsageAuthority.AUTHORITATIVE, zero.tokenAuthority)
        assertEquals(UsageAuthority.AUTHORITATIVE, zero.costAuthority)
        assertTrue(zero.breakdownComplete)
        val aggregate = tracker.usage.value
        assertEquals(2L, aggregate.requestCount)
        assertEquals(1L, aggregate.knownTokenRequestCount)
        assertEquals(1L, aggregate.knownCostRequestCount)
        assertEquals(UsageAuthority.MIXED, aggregate.tokenAuthority)
        assertEquals(UsageAuthority.MIXED, aggregate.costAuthority)
        assertFalse(aggregate.breakdownComplete)
        tracker.warnQuota("fixture quota")
        tracker.reset()
        assertEquals(TokenTrackerSnapshot(), tracker.snapshot.value)
        assertEquals(TokenUsage(), tracker.usage.value)
        assertTrue(tracker.history.value.isEmpty())
        assertNull(tracker.warning.value)
    }

    @Test fun totalOnlyAndMismatchedBreakdownsPreserveReportedTotal() {
        val tracker = TokenTracker()
        val totalOnly = tracker.add("fixture", response("""{"total_tokens":123}"""))
        assertEquals(123, totalOnly.total)
        assertEquals(UsageAuthority.AUTHORITATIVE, totalOnly.tokenAuthority)
        assertEquals(1L, totalOnly.knownTokenRequestCount)
        assertFalse(totalOnly.breakdownComplete)
        assertFalse(totalOnly.breakdownMismatch)
        val mismatch = tracker.add("fixture", response("""{"prompt_tokens":7,"completion_tokens":3,"total_tokens":12}"""))
        assertEquals(12, mismatch.total)
        assertTrue(mismatch.breakdownComplete)
        assertTrue(mismatch.breakdownMismatch)
        assertEquals(135, tracker.usage.value.total)
        assertEquals(UsageAuthority.AUTHORITATIVE, tracker.usage.value.tokenAuthority)
        assertFalse(tracker.usage.value.breakdownComplete)
        assertTrue(tracker.usage.value.breakdownMismatch)
    }

    @Test fun absentOptionalDetailsAreValidButPresentInvalidDetailsDoNotEraseTotal() {
        val plain = TokenTracker().add("fixture", known)
        assertTrue(plain.breakdownComplete)
        assertEquals(0, plain.reasoning)
        assertEquals(0, plain.cacheRead)
        val invalid = TokenTracker().add("fixture", response("""{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10,"completion_tokens_details":{"reasoning_tokens":-1}}"""))
        assertFalse(invalid.breakdownComplete)
        assertEquals(10, invalid.total)
        assertEquals(UsageAuthority.AUTHORITATIVE, invalid.tokenAuthority)
    }

    @Test fun impossibleSubsetsInvalidateOnlyBreakdownWithoutAssumingDisjointCaches() {
        val invalidDetails = listOf(
            "\"completion_tokens_details\":{\"reasoning_tokens\":4}",
            "\"prompt_tokens_details\":{\"cached_tokens\":8}",
            "\"prompt_tokens_details\":{\"cache_write_tokens\":8}",
        )
        for (detail in invalidDetails) {
            val sample = TokenTracker().add("fixture", response("""{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10,$detail}"""))
            assertFalse(detail, sample.breakdownComplete)
            assertEquals(10, sample.total)
            assertEquals(UsageAuthority.AUTHORITATIVE, sample.tokenAuthority)
        }
        val overlapping = TokenTracker().add("fixture", response("""{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10,"prompt_tokens_details":{"cached_tokens":7,"cache_write_tokens":7}}"""))
        assertTrue(overlapping.breakdownComplete)
    }

    @Test fun invalidCanonicalFieldsCannotFallThroughToAliases() {
        val sample = TokenTracker().add("fixture", response("""{"prompt_tokens":null,"input_tokens":7,"completion_tokens":3,"cost":-1,"cost_usd":0.01}"""))
        assertEquals(UsageAuthority.UNKNOWN, sample.tokenAuthority)
        assertEquals(UsageAuthority.UNKNOWN, sample.costAuthority)
        assertFalse(sample.breakdownComplete)
        assertEquals(0, sample.total)
        assertEquals(0.0, sample.costUsd, 0.0)
    }

    @Test fun invalidSuppliedTotalDoesNotBecomeDerivedTotal() {
        val sample = TokenTracker().add("fixture", response("""{"prompt_tokens":7,"completion_tokens":3,"total_tokens":-1}"""))
        assertEquals(UsageAuthority.UNKNOWN, sample.tokenAuthority)
        assertEquals(0L, sample.knownTokenRequestCount)
        assertEquals(0, sample.total)
        assertTrue(sample.breakdownComplete)
    }

    @Test fun absentTotalDerivesOnlyFromCompleteRepresentableCoreCounts() {
        val valid = TokenTracker().add("fixture", response("""{"input_tokens":7,"output_tokens":3}"""))
        assertEquals(10, valid.total)
        assertEquals(UsageAuthority.AUTHORITATIVE, valid.tokenAuthority)
        val tooLarge = TokenTracker().add("fixture", response("""{"prompt_tokens":2147483647,"completion_tokens":1}"""))
        assertEquals(UsageAuthority.UNKNOWN, tooLarge.tokenAuthority)
        assertEquals(0, tooLarge.total)
    }

    @Test fun aggregateOverflowIsExplicitAndResettable() {
        val tracker = TokenTracker()
        val large = response("""{"prompt_tokens":1500000000,"completion_tokens":0,"total_tokens":1500000000}""")
        repeat(2) { tracker.add("fixture", large) }
        assertEquals(Int.MAX_VALUE, tracker.usage.value.total)
        assertTrue(tracker.usage.value.tokenOverflow)
        assertFalse(tracker.usage.value.breakdownComplete)
        assertEquals(2L, tracker.usage.value.knownTokenRequestCount)
        assertEquals(UsageAuthority.MIXED, tracker.usage.value.tokenAuthority)
        tracker.reset()
        assertFalse(tracker.usage.value.tokenOverflow)
    }

    @Test fun clippedComponentsDoNotInvalidateAnIndependentlyExactTotal() {
        val tracker = TokenTracker()
        val inconsistent = response("""{"prompt_tokens":1500000000,"completion_tokens":0,"total_tokens":1}""")
        repeat(2) { tracker.add("fixture", inconsistent) }
        assertEquals(2, tracker.usage.value.total)
        assertEquals(UsageAuthority.AUTHORITATIVE, tracker.usage.value.tokenAuthority)
        assertFalse(tracker.usage.value.tokenOverflow)
        assertFalse(tracker.usage.value.breakdownComplete)
        assertTrue(tracker.usage.value.breakdownMismatch)
    }

    @Test fun costOverflowRemainsFiniteAndDoesNotTaintTokenTotals() {
        val tracker = TokenTracker()
        val expensive = response("""{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10,"cost":1e308}""")
        repeat(2) { tracker.add("fixture", expensive) }
        assertTrue(tracker.usage.value.costUsd.isFinite())
        assertEquals(Double.MAX_VALUE, tracker.usage.value.costUsd, 0.0)
        assertTrue(tracker.usage.value.costOverflow)
        assertEquals(UsageAuthority.MIXED, tracker.usage.value.costAuthority)
        assertEquals(UsageAuthority.AUTHORITATIVE, tracker.usage.value.tokenAuthority)
        assertEquals(2L, tracker.usage.value.knownCostRequestCount)
    }

    @Test fun missingCostAndReportedZeroAreOrderIndependent() {
        val zeroCost = response("""{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10,"cost":0}""")
        fun aggregate(first: String, second: String) = TokenTracker().apply {
            add("fixture", first); add("fixture", second)
        }.usage.value
        val unknownFirst = aggregate(known, zeroCost)
        val knownFirst = aggregate(zeroCost, known)
        assertEquals(knownFirst, unknownFirst)
        assertEquals(1L, knownFirst.knownCostRequestCount)
        assertEquals(UsageAuthority.MIXED, knownFirst.costAuthority)
    }

    @Test fun recentHistoryLimitDoesNotEraseRequestCoverage() {
        val tracker = TokenTracker()
        repeat(201) { tracker.add("fixture", known) }
        assertEquals(200, tracker.history.value.size)
        assertEquals(201L, tracker.usage.value.requestCount)
        assertEquals(201L, tracker.usage.value.knownTokenRequestCount)
        assertEquals(2_010, tracker.usage.value.total)
    }

    @Test fun compatibilityRequestArgumentIsIgnoredAndHistoryIsBoundedMetadataOnly() {
        val tracker = TokenTracker()
        tracker.add("fixture\nmodel", "PRIVATE_REQUEST_SENTINEL", known)
        val title = tracker.history.value.single().first
        assertEquals("Request 1 · fixture model", title)
        assertFalse(title.contains("PRIVATE_REQUEST_SENTINEL"))
        assertFalse(title.contains("prompt_tokens"))
        assertFalse(title.contains('\n'))
    }

    @Test fun cumulativeTotalsDoNotCreateContextPressureWarnings() {
        val tracker = TokenTracker()
        tracker.add("fixture", response("""{"prompt_tokens":500001,"completion_tokens":0,"total_tokens":500001}"""))
        assertNull(tracker.warning.value)
        tracker.warnQuota("provider quota")
        tracker.add("fixture", known)
        assertEquals("provider quota", tracker.snapshot.value.warning)
        tracker.clearWarning()
        assertNull(tracker.warning.value)
    }

    @Test(timeout = 5_000) fun snapshotObserverCanResetWithoutRepublishingOldHistory() = runBlocking {
        val tracker = TokenTracker()
        val observer = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            tracker.snapshot.drop(1).take(1).collect { observed ->
                assertEquals(1L, observed.usage.requestCount)
                assertEquals(1, observed.history.size)
                tracker.reset()
            }
        }
        try {
            tracker.add("fixture", known)
            assertEquals(TokenTrackerSnapshot(), tracker.snapshot.value)
            assertEquals(TokenUsage(), tracker.usage.value)
            assertTrue(tracker.history.value.isEmpty())
        } finally { observer.cancel() }
    }

    @Test(timeout = 5_000) fun twoConcurrentAddsKeepBothSamples() {
        val tracker = TokenTracker()
        val ready = CountDownLatch(2)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val workers = List(2) {
            Thread {
                try {
                    ready.countDown()
                    check(release.await(1, TimeUnit.SECONDS))
                    tracker.add("fixture", known)
                } catch (error: Throwable) { failure.compareAndSet(null, error) }
            }.apply { isDaemon = true; start() }
        }
        try {
            assertTrue(ready.await(1, TimeUnit.SECONDS))
            release.countDown()
            workers.forEach { it.join(1_000) }
            assertTrue(workers.none { it.isAlive })
            failure.get()?.let { throw AssertionError("Concurrent fixture failed", it) }
            val snapshot = tracker.snapshot.value
            assertEquals(2L, snapshot.usage.requestCount)
            assertEquals(2L, snapshot.usage.knownTokenRequestCount)
            assertEquals(20, snapshot.usage.total)
            assertEquals(2, snapshot.history.size)
            assertEquals(setOf("Request 1 · fixture", "Request 2 · fixture"), snapshot.history.map { it.first }.toSet())
        } finally {
            release.countDown()
            workers.forEach { it.interrupt(); it.join(100) }
        }
    }
}
