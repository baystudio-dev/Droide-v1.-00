package com.baystudio.droide.core

import kotlinx.serialization.json.*

/** A local allocation policy, never a tokenizer or an invented model-context claim. */
internal object ProviderRequestBudget {
    const val DEFAULT_OUTPUT_TOKENS = 8_192
    private val outputFields = setOf("max_tokens", "max_completion_tokens", "max_output_tokens")

    // Fallback allocations, not claims about an undiscovered model's token limits.
    private const val REASONING_OUTPUT_TOKENS = 32_768
    private const val EXTENDED_REASONING_OUTPUT_TOKENS = 65_536

    fun defaultOutputTokens(providerId: String, modelId: String): Int = automaticOutputTokens(
        providerId, modelId,
        if (ModelCapabilityRegistry.resolve(providerId, modelId)?.reasoning == true) REASONING_OUTPUT_TOKENS
        else DEFAULT_OUTPUT_TOKENS,
    )

    private fun automaticOutputTokens(providerId: String, modelId: String, fallback: Int): Int = minOf(
        ModelContextRegistry.validLimit(ModelCapabilityRegistry.resolve(providerId, modelId)?.outputTokens) ?: fallback,
        // Retain input room in a shared context window. Input-only limits are not output caps.
        ModelContextRegistry.resolve(providerId, modelId)?.let { (it / 4).coerceAtLeast(1) } ?: Int.MAX_VALUE,
    )

    private fun reasoningOutputTokens(root: JsonObject, providerId: String, modelId: String): Int {
        val reasoning = root["reasoning"] as? JsonObject
        val thinking = root["thinking"] as? JsonObject
        val effort = ((root["reasoning_effort"] ?: reasoning?.get("effort") ?:
            (root["output_config"] as? JsonObject)?.get("effort")) as? JsonPrimitive)?.contentOrNull?.lowercase()
        val fallback = when {
            effort in setOf("xhigh", "max", "ultra") -> EXTENDED_REASONING_OUTPUT_TOKENS
            effort != null && effort !in setOf("none", "minimal") -> REASONING_OUTPUT_TOKENS
            thinking?.get("type") == JsonPrimitive("adaptive") -> REASONING_OUTPUT_TOKENS
            else -> return defaultOutputTokens(providerId, modelId)
        }
        return automaticOutputTokens(providerId, modelId, fallback)
    }

    /** All accepted canonical aliases describe the same total output reservation. */
    fun explicitOutputTokens(root: JsonObject): Int? {
        val values = outputFields.mapNotNull { field ->
            root[field]?.let { raw ->
                val value = (raw as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                require(value != null && value > 0) { "$field must be a positive integer" }
                value
            }
        }
        require(values.distinct().size <= 1) { "Conflicting provider output limits" }
        return values.firstOrNull()
    }

    fun outputTokens(root: JsonObject, providerId: String, modelId: String,
        provider: AiProvider? = ProviderRegistry.findById(providerId),
    ): Int {
        val explicit = explicitOutputTokens(root)
        val cap = ModelContextRegistry.validLimit(ModelCapabilityRegistry.resolve(providerId, modelId)?.outputTokens)
        require(explicit == null || cap == null || explicit <= cap) {
            "Model '$modelId' output limit $explicit exceeds the advertised output limit ($cap)"
        }
        val thinking = (root["thinking"] as? JsonObject)?.takeIf { it["type"] == JsonPrimitive("enabled") }
        val thinkingBudget = (thinking?.get("budget_tokens") as? JsonPrimitive)?.intOrNull
        val googleBudget = if (provider?.let { ProviderModelRequestPolicy.protocol(it, modelId) } == ProviderWireProtocol.GOOGLE_GENERATE_CONTENT) {
            (root["reasoning_effort"] as? JsonPrimitive)?.contentOrNull?.let {
                GoogleGenerateContentAdapter.thinkingBudget(providerId, modelId, it)
            }
        } else null
        val preferred = if (explicit != null) explicit.toLong() else maxOf(
            reasoningOutputTokens(root, providerId, modelId).toLong(),
            (thinkingBudget ?: googleBudget)?.let { it.toLong() + 4_096L } ?: 0L,
        )
        val resolved = if (explicit == null && cap != null) minOf(preferred, cap.toLong()) else preferred
        require(resolved in 1..Int.MAX_VALUE.toLong()) { "Provider output reservation is too large" }
        require(thinkingBudget == null || thinkingBudget < resolved) {
            "Thinking budget $thinkingBudget must be below the output reservation ($resolved); select another model or reduce reasoning"
        }
        return resolved.toInt()
    }

    fun withOutputLimit(root: JsonObject, providerId: String, modelId: String,
        provider: AiProvider? = ProviderRegistry.findById(providerId),
    ): JsonObject {
        val protocol = provider?.let { ProviderModelRequestPolicy.protocol(it, modelId) }
        val field = when (protocol) {
            ProviderWireProtocol.OPENAI_RESPONSES -> "max_output_tokens"
            ProviderWireProtocol.ANTHROPIC_MESSAGES, ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> "max_tokens"
            else -> if (providerId.equals("openai", true) || root.containsKey("max_completion_tokens"))
                "max_completion_tokens" else "max_tokens"
        }
        return JsonObject(root.filterKeys { it !in outputFields } + (field to JsonPrimitive(outputTokens(root, providerId, modelId, provider))))
    }

    /** Called before native conversion/network use, including subagents and manual/automatic compaction. */
    fun prepare(root: JsonObject, providerId: String, modelId: String,
        provider: AiProvider? = ProviderRegistry.findById(providerId),
    ): JsonObject {
        require((root["model"] as? JsonPrimitive)?.contentOrNull == modelId) { "Provider budget model identity differs from the request" }
        val prepared = AgentResponseOutcome.requestHistory(withOutputLimit(root, providerId, modelId, provider))
        val messages = (prepared["messages"] as? JsonArray).orEmpty().map { raw ->
            raw as? JsonObject ?: throw IllegalArgumentException("Provider message must be an object")
        }
        AgentHistory.requireCompleteGroups(messages)
        val tools = prepared["tools"] as? JsonArray
        val reserve = checkNotNull(explicitOutputTokens(prepared))
        val transport = provider?.let { ProviderTransportV2.spec(it, modelId) }
        val estimate = ContextBudget.estimate("", messages, tools, providerId, modelId, transport, reserve)
        val options = JsonObject(prepared.filterKeys { it != "messages" && it != "tools" })
        val optionTokens = ContextBudget.estimateTokensFromChars(ContextBudget.serializedUtf8Bytes(options))
        val inputEstimate = estimate.approximateTokens.toLong() + optionTokens
        val available = ContextBudget.effectiveInputTokens(providerId, modelId, reserve) ?: return prepared
        val margin = minOf(256, (available / 16).coerceAtLeast(1))
        if (inputEstimate + margin > available) {
            val fixed = messages.filter { (it["role"] as? JsonPrimitive)?.contentOrNull in setOf("system", "developer") } +
                listOfNotNull(messages.lastOrNull { it["role"] == JsonPrimitive("user") })
            val fixedEstimate = ContextBudget.estimate("", fixed, tools, providerId, modelId, transport, reserve).approximateTokens.toLong() + optionTokens + margin
            val next = if (fixedEstimate > available) {
                "Mandatory instructions, tools and the admitted user prompt do not fit. Select a model with a larger input window."
            } else "Compact older conversation history or select a model with a larger input window."
            throw ProviderContextOverflowException(estimate.serializedChars,
                "Estimated provider input is $inputEstimate tokens plus $margin safety tokens; available input is $available " +
                    "after reserving $reserve output tokens. $next The request was preserved; token counts are approximate.")
        }
        return prepared
    }
}

internal class ProviderContextOverflowException(chars: Int, detail: String) : LlmRequestTooLargeException(chars, detail)
