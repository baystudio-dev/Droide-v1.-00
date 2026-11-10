package com.baystudio.droide.ui

import com.baystudio.droide.core.TokenUsage
import com.baystudio.droide.core.UsageAuthority
import java.util.Locale

internal const val AGENT_USAGE_SCOPE = "This Agent since reset"
internal const val AGENT_USAGE_CONTEXT_NOTE = "Cumulative recorded requests, not current context."
internal const val AGENT_USAGE_BREAKDOWN_NOTE = "Input/output breakdown unavailable"
internal const val AGENT_USAGE_TOTAL_NOTE = "Provider total may include tokens outside the input/output breakdown."

internal fun usageRequestCountLabel(count: Long): String =
    "$count recorded ${if (count == 1L) "request" else "requests"}"

internal fun usageHistoryLabel(visibleCount: Int, requestCount: Long): String =
    if (visibleCount.toLong() < requestCount) "Showing latest $visibleCount of $requestCount recorded requests"
    else "Showing all ${usageRequestCountLabel(requestCount)}"

internal fun usageHasKnownTokens(usage: TokenUsage): Boolean =
    usage.tokenAuthority != UsageAuthority.UNKNOWN && usage.total >= 0

internal fun usageHasKnownCost(usage: TokenUsage): Boolean =
    usage.costAuthority != UsageAuthority.UNKNOWN && usage.costUsd.isFinite() && usage.costUsd >= 0.0

private fun missingTokenRequests(usage: TokenUsage): Long =
    (usage.requestCount - usage.knownTokenRequestCount).coerceAtLeast(0L)

private fun missingCostRequests(usage: TokenUsage): Long =
    (usage.requestCount - usage.knownCostRequestCount).coerceAtLeast(0L)

internal fun usageTokenValue(usage: TokenUsage, compact: Boolean = true): String {
    if (!usageHasKnownTokens(usage)) return "?"
    val prefix = when {
        usage.tokenOverflow || missingTokenRequests(usage) > 0 -> "≥"
        usage.tokenAuthority == UsageAuthority.ESTIMATED || usage.tokenAuthority == UsageAuthority.MIXED -> "≈"
        else -> ""
    }
    val count = when {
        !compact -> usage.total.toString()
        prefix == "≥" -> formatUsageTokenLowerBound(usage.total)
        else -> formatUsageTokenCount(usage.total.toLong())
    }
    return prefix + count
}

internal fun usageTokenAuthorityLabel(usage: TokenUsage): String = when {
    !usageHasKnownTokens(usage) -> "Token usage unavailable"
    usage.tokenOverflow -> "Token count exceeds supported range"
    missingTokenRequests(usage) > 0 -> "Mixed token coverage · known subtotal"
    usage.tokenAuthority == UsageAuthority.AUTHORITATIVE -> "Provider-reported tokens"
    usage.tokenAuthority == UsageAuthority.ESTIMATED -> "Estimated tokens"
    else -> "Mixed token sources"
}

internal fun usageTokenCoverageLabel(usage: TokenUsage): String? =
    missingTokenRequests(usage).takeIf { it > 0L }?.let {
        "Token usage unavailable for $it of ${usage.requestCount} requests"
    }

internal fun usageCostCoverageLabel(usage: TokenUsage): String? =
    missingCostRequests(usage).takeIf { it > 0L }?.let {
        "Cost unavailable for $it of ${usage.requestCount} requests"
    }

internal fun usageCostSummary(usage: TokenUsage): String = when {
    !usageHasKnownCost(usage) -> "Cost unavailable"
    usage.costOverflow -> "Cost total exceeds supported range"
    missingCostRequests(usage) > 0 -> "Known cost subtotal: ${formatUsageMoney(usage.costUsd)}"
    usage.costAuthority == UsageAuthority.AUTHORITATIVE -> "Provider-reported cost: ${formatUsageMoney(usage.costUsd)}"
    usage.costAuthority == UsageAuthority.ESTIMATED -> "Estimated cost: ${formatUsageMoney(usage.costUsd)}"
    else -> "Mixed cost sources: ${formatUsageMoney(usage.costUsd)}"
}

// The narrow badge cannot explain missing coverage. Keep partial costs in the menu/sheet.
internal fun formatUsageCostCompact(usage: TokenUsage): String? {
    if (!usageHasKnownCost(usage) || usage.costOverflow || missingCostRequests(usage) > 0) return null
    val prefix = if (usage.costAuthority == UsageAuthority.AUTHORITATIVE) "" else "≈"
    return prefix + formatUsageMoney(usage.costUsd)
}

private fun formatUsageMoney(amount: Double): String = when {
    amount > 0.0 && amount < 0.01 -> "<$0.01"
    else -> "$" + String.format(Locale.US, "%.2f", if (amount == 0.0) 0.0 else amount)
}

private fun formatUsageTokenLowerBound(tokens: Int): String = when {
    tokens >= 1_000_000 -> String.format(Locale.US, "%.1fM", (tokens / 100_000) / 10.0)
    tokens >= 1_000 -> String.format(Locale.US, "%.1fk", (tokens / 100) / 10.0)
    else -> tokens.toString()
}

internal fun formatUsageTokenCount(tokens: Long): String = when {
    tokens >= 1_000_000 -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
    tokens >= 1_000 -> String.format(Locale.US, "%.1fk", tokens / 1_000.0)
    else -> tokens.toString()
}

internal fun usageRequestDetail(usage: TokenUsage): String = buildList {
    add(if (usageHasKnownTokens(usage)) "${usageTokenAuthorityLabel(usage)}: ${usageTokenValue(usage, compact = false)}"
        else usageTokenAuthorityLabel(usage))
    if (usage.breakdownComplete) add("Input ${usage.prompt} · Output ${usage.completion}")
    else add(AGENT_USAGE_BREAKDOWN_NOTE)
    if (usage.breakdownMismatch) add("Provider total differs from input/output sum.")
    add(usageCostSummary(usage))
}.joinToString("\n")
