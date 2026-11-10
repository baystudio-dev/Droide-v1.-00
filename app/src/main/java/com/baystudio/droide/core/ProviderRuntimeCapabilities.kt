package com.baystudio.droide.core

import kotlinx.serialization.json.*

// Retain only explicitly advertised live capabilities; missing metadata is not a capability
// claim.
internal object ProviderRuntimeCapabilities {
    fun parse(provider: AiProvider, entry: ProviderModelCatalog.Entry): ModelCapabilities? {
        val metadata = entry.metadata ?: return null
        val capabilities = metadata["capabilities"] as? JsonObject
        val architecture = metadata["architecture"] as? JsonObject
        val modalities = metadata["modalities"] as? JsonObject
        val parameters = strings(metadata["supported_parameters"])
        val reasoning = reasoningOptions(provider, metadata)
        val input = strings(metadata["input_modalities"]) ?: strings(architecture?.get("input_modalities")) ?: strings(modalities?.get("input"))
        val output = strings(metadata["output_modalities"]) ?: strings(architecture?.get("output_modalities")) ?: strings(modalities?.get("output"))
        return ModelCapabilities(
            providerId = provider.id, modelId = entry.id,
            displayName = ((metadata["displayName"] as? JsonPrimitive)?.contentOrNull
                ?: (metadata["name"] as? JsonPrimitive)?.contentOrNull ?: entry.id).take(160),
            attachment = bool(metadata, "attachment") ?: bool(capabilities, "attachment"),
            reasoning = bool(metadata, "reasoning") ?: bool(metadata, "thinking") ?: bool(capabilities, "reasoning")
                ?: true.takeIf { provider.id.equals("openrouter", true) && metadata["reasoning"] is JsonObject }
                ?: parameters?.any { it in setOf("reasoning", "reasoning_effort", "thinking") },
            toolCall = bool(metadata, "tool_call") ?: bool(metadata, "toolCall") ?: bool(capabilities, "tool_call")
                ?: bool(capabilities, "tools") ?: parameters?.contains("tools"),
            structuredOutput = bool(metadata, "structured_output") ?: bool(capabilities, "structured_output")
                ?: parameters?.any { it in setOf("response_format", "structured_outputs") },
            temperature = bool(metadata, "temperature") ?: parameters?.contains("temperature"),
            inputModalities = input.orEmpty(), outputModalities = output.orEmpty(),
            reasoningEfforts = reasoning.efforts,
            contextTokens = ModelContextRegistry.contextLimitFromModel(metadata),
            inputTokens = ModelContextRegistry.inputLimitFromModel(metadata),
            outputTokens = ModelContextRegistry.outputLimitFromModel(metadata),
            source = ModelCapabilitySource.RUNTIME_PROVIDER,
            supportedEndpoints = (strings(metadata["supported_endpoints"]) ?: strings(capabilities?.get("endpoints"))).orEmpty(),
            reasoningOptionsKnown = reasoning.known,
            reasoningOptionTypes = reasoning.types,
            reasoningBudgetMin = reasoning.minimum,
            reasoningBudgetMax = reasoning.maximum,
        )
    }

    private data class ReasoningOptions(
        val known: Boolean,
        val efforts: Set<String>,
        val types: Set<String>,
        val minimum: Int? = null,
        val maximum: Int? = null,
    )

    private fun reasoningOptions(provider: AiProvider, metadata: JsonObject): ReasoningOptions {
        val gateway = (metadata["reasoning"] as? JsonObject)?.takeIf { provider.id.equals("openrouter", true) }
        if (gateway != null) {
            val efforts = when (val declared = gateway["supported_efforts"]) {
                JsonNull -> setOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
                else -> strings(declared).orEmpty()
            }
            val types = buildSet {
                if ("supported_efforts" in gateway) add("effort")
                if (bool(gateway, "supports_max_tokens") == true) add("budget_tokens")
            }
            return ReasoningOptions(true, efforts, types)
        }
        val raw = metadata["reasoning_options"]
        val named = strings(metadata["reasoning_efforts"]) ?: strings(raw)
            ?: strings((raw as? JsonObject)?.get("efforts"))
        val structured = (raw as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val budgets = structured.filter { it["type"] == JsonPrimitive("budget_tokens") }
        val types = structured.mapNotNull { (it["type"] as? JsonPrimitive)?.contentOrNull }.toSet()
        return ReasoningOptions(
            known = named != null || raw is JsonArray || raw is JsonObject,
            efforts = named ?: structured.filter { it["type"] == JsonPrimitive("effort") }
                .flatMap { strings(it["values"]).orEmpty() }.toSet(),
            types = if (named != null) types + "effort" else types,
            minimum = budgets.mapNotNull { (it["min"] as? JsonPrimitive)?.intOrNull }.maxOrNull(),
            maximum = budgets.mapNotNull { (it["max"] as? JsonPrimitive)?.intOrNull }.minOrNull(),
        )
    }

    private fun bool(root: JsonObject?, key: String): Boolean? =
        (root?.get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    private fun strings(raw: JsonElement?): Set<String>? {
        val values = raw as? JsonArray ?: return null
        if (values.any { it !is JsonPrimitive || !it.isString }) return null
        return values.map { it.jsonPrimitive.content.trim().lowercase() }.toSet()
    }

}
