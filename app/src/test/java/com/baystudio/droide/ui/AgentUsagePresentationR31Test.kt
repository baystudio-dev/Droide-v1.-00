package com.baystudio.droide.ui

import com.baystudio.droide.core.TokenUsage
import com.baystudio.droide.core.UsageAuthority
import org.junit.Assert.*
import org.junit.Test

/** Pure presentation checks; no Android fixture, process, network or provider. */
class AgentUsagePresentationR31Test {
    private fun reported() = TokenUsage(prompt = 10, completion = 2, total = 12, costUsd = 0.25,
        tokenAuthority = UsageAuthority.AUTHORITATIVE, costAuthority = UsageAuthority.AUTHORITATIVE,
        requestCount = 1, knownTokenRequestCount = 1, knownCostRequestCount = 1, breakdownComplete = true)

    @Test fun genuineProviderZeroStaysDistinctFromUnavailableCost() {
        val usage = reported().copy(total = 0, costUsd = 0.0)
        assertEquals("0", usageTokenValue(usage))
        assertEquals("Provider-reported cost: $0.00", usageCostSummary(usage))
        assertEquals("$0.00", formatUsageCostCompact(usage))
        assertEquals("Provider-reported cost: $0.00", usageCostSummary(usage.copy(costUsd = -0.0)))
    }

    @Test fun unknownPlaceholderNeverLooksLikeExactZeroOrFree() {
        val usage = TokenUsage(requestCount = 1)
        assertEquals("?", usageTokenValue(usage))
        assertEquals("Token usage unavailable", usageTokenAuthorityLabel(usage))
        assertEquals("Cost unavailable", usageCostSummary(usage))
        assertNull(formatUsageCostCompact(usage))
        assertEquals("Token usage unavailable for 1 of 1 requests", usageTokenCoverageLabel(usage))
    }

    @Test fun legacyEstimateKeepsIndependentTokenAndCostProvenance() {
        val usage = reported().copy(tokenAuthority = UsageAuthority.ESTIMATED, costAuthority = UsageAuthority.UNKNOWN,
            knownCostRequestCount = 0)
        assertEquals("≈12", usageTokenValue(usage))
        assertEquals("Estimated tokens", usageTokenAuthorityLabel(usage))
        assertEquals("Cost unavailable", usageCostSummary(usage))
        assertNull(formatUsageCostCompact(usage))
    }

    @Test fun partialCostIsKnownSubtotalRatherThanApproximateBill() {
        val usage = reported().copy(requestCount = 2, knownTokenRequestCount = 2, costAuthority = UsageAuthority.MIXED)
        assertEquals("Known cost subtotal: $0.25", usageCostSummary(usage))
        assertEquals("Cost unavailable for 1 of 2 requests", usageCostCoverageLabel(usage))
        assertNull(formatUsageCostCompact(usage))
    }

    @Test fun entirelyKnownMixedSourcesAreNotMislabeledAsMissing() {
        val usage = reported().copy(tokenAuthority = UsageAuthority.MIXED, costAuthority = UsageAuthority.MIXED)
        assertEquals("Mixed token sources", usageTokenAuthorityLabel(usage))
        assertEquals("Mixed cost sources: $0.25", usageCostSummary(usage))
        assertNull(usageTokenCoverageLabel(usage))
        assertNull(usageCostCoverageLabel(usage))
    }

    @Test fun missingTokenReportsExposeCoverageAndLowerBoundWithoutRoundingUp() {
        val usage = reported().copy(total = 1_999, requestCount = 2, tokenAuthority = UsageAuthority.MIXED)
        assertEquals("≥1.9k", usageTokenValue(usage))
        assertEquals("≥1999", usageTokenValue(usage, compact = false))
        assertEquals("Mixed token coverage · known subtotal", usageTokenAuthorityLabel(usage))
        assertEquals("Token usage unavailable for 1 of 2 requests", usageTokenCoverageLabel(usage))
    }

    @Test fun totalOnlyDoesNotRenderStorageZerosAsInputOutput() {
        val usage = reported().copy(prompt = 0, completion = 0, total = 77, breakdownComplete = false)
        assertTrue(usageRequestDetail(usage).contains("Provider-reported tokens: 77"))
        assertTrue(usageRequestDetail(usage).contains(AGENT_USAGE_BREAKDOWN_NOTE))
        assertFalse(usageRequestDetail(usage).contains("Input 0"))
        assertFalse(usageRequestDetail(usage).contains("Output 0"))
    }

    @Test fun mismatchingProviderTotalNeverClaimsAnEquation() {
        val detail = usageRequestDetail(reported().copy(total = 15, breakdownMismatch = true))
        assertTrue(detail.contains("Provider-reported tokens: 15"))
        assertTrue(detail.contains("Input 10 · Output 2"))
        assertTrue(detail.contains("Provider total differs from input/output sum."))
        assertFalse(detail.contains("+"))
        assertFalse(detail.contains("="))
    }

    @Test fun tokenOverflowIsALowerBoundWhileIndependentCostRemainsKnown() {
        val usage = reported().copy(total = Int.MAX_VALUE, tokenOverflow = true, tokenAuthority = UsageAuthority.MIXED)
        assertEquals("≥2147483647", usageTokenValue(usage, compact = false))
        assertEquals("≥2147.4M", usageTokenValue(usage))
        assertEquals("Token count exceeds supported range", usageTokenAuthorityLabel(usage))
        assertEquals("Provider-reported cost: $0.25", usageCostSummary(usage))
    }

    @Test fun costOverflowIsNotRenderedAsAnExactOrApproximateBill() {
        val usage = reported().copy(costUsd = Double.MAX_VALUE, costOverflow = true, costAuthority = UsageAuthority.MIXED)
        assertEquals("Cost total exceeds supported range", usageCostSummary(usage))
        assertNull(formatUsageCostCompact(usage))
        assertEquals("Provider-reported tokens", usageTokenAuthorityLabel(usage))
    }

    @Test fun invalidCostIsUnavailableAndPositiveSubcentCostIsNotFree() {
        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { invalid ->
            assertEquals("Cost unavailable", usageCostSummary(reported().copy(costUsd = invalid)))
            assertNull(formatUsageCostCompact(reported().copy(costUsd = invalid)))
        }
        assertEquals("Provider-reported cost: <$0.01", usageCostSummary(reported().copy(costUsd = 0.001)))
    }

    @Test fun historyCountDisclosesCumulativeRecordsBeyondVisibleAndRetainedWindows() {
        assertEquals("1 recorded request", usageRequestCountLabel(1))
        assertEquals("Showing latest 20 of 201 recorded requests", usageHistoryLabel(20, 201))
        assertEquals("Showing all 1 recorded request", usageHistoryLabel(1, 1))
    }
}
