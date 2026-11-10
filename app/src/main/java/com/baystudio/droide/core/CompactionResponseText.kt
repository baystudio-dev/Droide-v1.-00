package com.baystudio.droide.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** A checkpoint must be complete and nonblank before it may replace historical evidence. */
internal object CompactionResponseText {
    private val json = Json { ignoreUnknownKeys = true }

    fun extract(response: String): String {
        val root = json.parseToJsonElement(response) as? JsonObject ?: error("Invalid compaction response")
        val choices = root["choices"] as? JsonArray ?: error("Compaction response missing choices")
        val first = choices.firstOrNull() as? JsonObject ?: error("Compaction response missing choice")
        val decision = AgentResponseOutcome.evaluate(first)
        require(decision.kind == AgentResponseOutcome.Kind.COMPLETE) {
            if (decision.kind == AgentResponseOutcome.Kind.EMPTY) {
                "Compaction model returned an empty checkpoint; existing conversation was preserved"
            } else {
                "Compaction response is incomplete (${decision.kind}); existing conversation was preserved. ${decision.diagnostic}"
            }
        }
        return decision.publicText
    }
}
