package com.baystudio.droide.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A short checkpoint still needs room for the selected model's reasoning before public text. */
internal object TypedCompactionRequestPolicy {
    fun summaryOptions(config: AgentConfig, provider: AiProvider): JsonObject {
        require(config.providerId == provider.id) { "Compaction request provider differs from the selected provider" }
        val model = ProviderModelIdentity.requireCanonical(config.model)
        val options = buildJsonObject {
            put("model", model)
            ReasoningSupport.apply(this, provider.id, model, config.reasoningEffort)
            put("tool_choice", "none")
        }
        // Checkpoint text/storage limits are enforced separately. They are not a cap
        // on hidden reasoning, and compaction must not silently change selected effort.
        return ProviderRequestBudget.withOutputLimit(options, provider.id, model, provider)
    }
}
