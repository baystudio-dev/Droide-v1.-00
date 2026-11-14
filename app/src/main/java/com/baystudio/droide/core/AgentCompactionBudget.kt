package com.baystudio.droide.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Budget for the history that will be installed, after reserving the next request's prefix. */
internal data class AgentCompactionBudget(val historyTokens: Int, val keepRecentTokens: Int) {
    init { require(historyTokens > 0 && keepRecentTokens > 0) }

    companion object {
        fun forModel(config: AgentConfig, keepRecentTokens: Int): AgentCompactionBudget {
            val estimate = ContextBudget.estimate("", emptyList(), null, config.providerId, config.model, null,
                AgentRequestSupport.outputTokens(config))
            val limit = (estimate.triggerTokens ?: ContextBudget.MOBILE_COMPACT_CHARS / 4) - estimate.approximateTokens - 1
            requireRoom(limit)
            return AgentCompactionBudget(limit, minOf(keepRecentTokens, (limit / 2).coerceAtLeast(1)))
        }

        fun forRequest(config: AgentConfig, system: String, tools: JsonArray?): AgentCompactionBudget {
            val prefix = ContextBudget.estimate(system, emptyList(), tools, config.providerId, config.model, null,
                AgentRequestSupport.outputTokens(config))
            // Trigger early at the soft threshold, but admit against the real input ceiling.
            // A large fixed prefix alone can exceed the soft threshold without filling the window.
            val ceiling = ContextBudget.effectiveInputTokens(config.providerId, config.model, prefix.reservedOutputTokens)
                ?: ContextBudget.MOBILE_COMPACT_CHARS / 4
            val limit = ceiling - prefix.approximateTokens - 256
            requireRoom(limit)
            return AgentCompactionBudget(limit, minOf(ContextBudget.recentKeepTokens(config.providerId, config.model, prefix.reservedOutputTokens),
                (limit / 2).coerceAtLeast(1)))
        }

        // A provider sample can correct a cheap character estimate. Never use cumulative
        // billing, subtract cached input, or reuse a sample from another session/model/window.
        fun calibrated(estimate: ContextEstimate, previous: AgentContextUsage?, sessionId: String,
            providerId: String, model: String): ContextEstimate = estimate.copy(approximateTokens =
            kotlin.math.ceil(estimate.approximateTokens * scale(previous, sessionId, providerId, model))
                .coerceAtMost(Int.MAX_VALUE.toDouble()).toInt())

        private fun scale(previous: AgentContextUsage?, sessionId: String, providerId: String, model: String): Double {
            val sample = previous?.forSelection(providerId, model)?.takeIf {
                it.sessionId == sessionId && !it.isEstimated && it.estimate.approximateTokens > 0
            } ?: return 1.0
            return maxOf(1.0, sample.reportedInputTokens!!.toDouble() / sample.estimate.approximateTokens)
        }

        fun historyTokens(history: List<JsonObject>): Int =
            (ContextBudget.estimate("", history, null, "", "").approximateTokens - 256).coerceAtLeast(0)

        private fun requireRoom(limit: Int) = require(limit >= 256) {
            "System instructions, tools and reserved output leave insufficient context for a checkpoint. " +
                "Existing conversation was preserved; select a model with a larger input window"
        }
    }
}
