package com.baystudio.droide.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Review-only desired-invariant candidates against current APIs; NOT executed. */
class TokenTrackerR31FailingBaselines {
    private fun response(usage: String) = """{"usage":$usage}"""
    private val known = """{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}"""

    @Test fun missingProviderUsageMustRemainUnknownForTinyImageWireJson() {
        val request = """{"messages":[{"content":[{"type":"image_url","image_url":{"url":"data:image/png;base64,${"A".repeat(64)}"}}]}]}"""
        val sample = TokenTracker().add("fixture", request, "{}")
        assertEquals(UsageAuthority.UNKNOWN, sample.tokenAuthority)
        assertEquals(0, sample.total)
    }

    @Test fun validTotalOnlyUsageMustSurviveWithoutInventingABreakdown() {
        val sample = TokenTracker().add("fixture", "tiny", response("""{"total_tokens":123}"""))
        assertEquals(123, sample.total)
        assertEquals(UsageAuthority.AUTHORITATIVE, sample.tokenAuthority)
        // Add breakdownComplete=false assertion only after the new API is approved.
    }

    @Test fun missingCompletionMustNotBePromotedToAuthoritativeZeroOutput() {
        val sample = TokenTracker().add("fixture", "tiny", response("""{"prompt_tokens":7}"""))
        assertEquals(UsageAuthority.UNKNOWN, sample.tokenAuthority)
    }

    @Test fun negativeReportedCountMustNotBeClampedIntoAuthoritativeUsage() {
        val sample = TokenTracker().add("fixture", "tiny", response("""{"prompt_tokens":-7,"completion_tokens":3}"""))
        assertEquals(UsageAuthority.UNKNOWN, sample.tokenAuthority)
    }

    @Test fun unknownAndKnownCostsMustHaveOrderIndependentMixedProvenance() {
        val withoutCost = response(known)
        val withCost = response("""{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10,"cost":0.01}""")
        fun aggregate(first: String, second: String): TokenUsage = TokenTracker().apply {
            add("fixture", "first", first)
            add("fixture", "second", second)
        }.usage.value
        val unknownFirst = aggregate(withoutCost, withCost)
        val knownFirst = aggregate(withCost, withoutCost)
        assertEquals(UsageAuthority.MIXED, unknownFirst.costAuthority)
        assertEquals(unknownFirst.costAuthority, knownFirst.costAuthority)
        assertEquals(knownFirst.costUsd, unknownFirst.costUsd, 0.000001)
    }

    @Test fun invalidProviderCostsMustRemainUnknown() {
        for (rawCost in listOf("-0.01", "1e309", "\"NaN\"")) {
            val sample = TokenTracker().add("fixture", "tiny", response("""{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10,"cost":$rawCost}"""))
            assertEquals(rawCost, UsageAuthority.UNKNOWN, sample.costAuthority)
            assertEquals(rawCost, 0.0, sample.costUsd, 0.0)
            assertEquals(UsageAuthority.AUTHORITATIVE, sample.tokenAuthority)
        }
    }

    @Test fun validProviderCostMustSurviveMissingTokenFields() {
        val sample = TokenTracker().add("fixture", "tiny", response("""{"cost":0.01}"""))
        assertEquals(UsageAuthority.AUTHORITATIVE, sample.costAuthority)
        assertEquals(0.01, sample.costUsd, 0.000001)
        assertEquals(UsageAuthority.UNKNOWN, sample.tokenAuthority)
    }

    @Test fun cumulativeIntCounterMustSaturateRatherThanWrapNegative() {
        val tracker = TokenTracker()
        val largeCount = response("""{"prompt_tokens":1500000000,"completion_tokens":0,"total_tokens":1500000000}""")
        repeat(2) { tracker.add("fixture", "tiny", largeCount) }
        assertEquals(Int.MAX_VALUE, tracker.usage.value.prompt)
        assertEquals(Int.MAX_VALUE, tracker.usage.value.total)
        assertNotEquals(UsageAuthority.AUTHORITATIVE, tracker.usage.value.tokenAuthority)
    }

    @Test(timeout = 5_000) fun resetFromUsageObserverMustNotLeaveOrphanedHistory() = runBlocking {
        val tracker = TokenTracker()
        val observer = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            tracker.usage.drop(1).take(1).collect { tracker.reset() }
        }
        try {
            tracker.add("fixture", "tiny", response(known))
            assertEquals(TokenUsage(), tracker.usage.value)
            assertTrue(tracker.history.value.isEmpty())
        } finally {
            observer.cancel()
        }
    }
}
