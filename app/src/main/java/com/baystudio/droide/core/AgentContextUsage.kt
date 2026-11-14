package com.baystudio.droide.core

/** Last model request in this conversation, independent of the cumulative billing ledger. */
data class AgentContextUsage(
    val sessionId: String,
    val providerId: String,
    val model: String,
    val estimate: ContextEstimate,
    val reportedInputTokens: Int? = null,
    val reportedOutputTokens: Int? = null,
) {
    val isEstimated: Boolean get() = reportedInputTokens == null || reportedOutputTokens == null
    val usedTokens: Long get() = if (isEstimated) estimate.approximateTokens.coerceAtLeast(0).toLong()
        else reportedInputTokens!!.toLong() + reportedOutputTokens!!.toLong()
    val contextLimit: Int? get() = estimate.knownContextLimit?.takeIf { it > 0 }

    // A total alone can include provider-specific categories. Use a validated input/output
    // breakdown; cache and reasoning are subsets, never extra tokens to add or subtract.
    internal fun withReport(sample: TokenUsage): AgentContextUsage =
        if (sample.tokenAuthority == UsageAuthority.AUTHORITATIVE && sample.breakdownComplete &&
            !sample.tokenOverflow && sample.prompt >= 0 && sample.completion >= 0)
            copy(reportedInputTokens = sample.prompt, reportedOutputTokens = sample.completion)
        else this

    fun forSelection(providerId: String, model: String): AgentContextUsage? =
        takeIf { this.providerId == providerId && this.model == model }
}
