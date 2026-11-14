package com.baystudio.droide.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Runtime Models.dev reasoningoptions metadata is authoritative when present.


@Serializable
enum class ReasoningEffort(val label: String, val wireValue: String) {
    LOW("Low", "low"),
    HIGH("High", "high"),
    XTRA_HIGH("Xtra High", "xhigh"),
    ULTRA("Ultra", "max"),
}

 
sealed interface ReasoningWire {
    data class OpenAiCompatible(val value: String) : ReasoningWire
    data class OpenRouter(val value: String) : ReasoningWire
    data class AnthropicAdaptive(val effort: String) : ReasoningWire
    data class AnthropicManual(val budgetTokens: Int) : ReasoningWire
    data class GoogleNative(val value: String) : ReasoningWire
}

internal enum class GoogleNativeThinkingMode { BUDGET, LEVEL }

// Persisted state remains the source of truth.


object ReasoningSupport {
    fun wire(providerId: String, model: String, effort: ReasoningEffort): ReasoningWire? {
        val provider = ProviderRegistry.findById(providerId) ?: return null
        if (!ProviderModelRequestPolicy.isConversational(provider, model)) return null
        val capability = resolveCapability(providerId, model)
        if (capability?.reasoning == false) return null

        val rawAdvertised = capability?.reasoningEfforts.orEmpty()
        if (capability?.reasoningOptionsKnown == true) {
            val kinds = capability.reasoningOptionTypes
            if (kinds.isEmpty() || kinds.none { it == "effort" || it == "budget_tokens" }) return null
            if ("budget_tokens" !in kinds && rawAdvertised.isEmpty()) return null
            if ("budget_tokens" in kinds && capability.reasoningBudgetMax?.let { it < maxOf(1, capability.reasoningBudgetMin ?: 1) } == true) return null
        }
        val advertised = normalizedAdvertisedEfforts(rawAdvertised)
        if (rawAdvertised.isNotEmpty() && effort.wireValue !in advertised) return null

        val protocol = ProviderModelRequestPolicy.protocol(provider, model)
        if (capability?.reasoningOptionsKnown == true) {
            val requiredKind = when (protocol) {
                ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, ProviderWireProtocol.OPENAI_RESPONSES -> "effort"
                ProviderWireProtocol.ANTHROPIC_MESSAGES -> if (anthropicThinkingMode(model) == AnthropicThinkingMode.MANUAL) "budget_tokens" else "effort"
                ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> if (googleNativeThinkingMode(model) == GoogleNativeThinkingMode.BUDGET) "budget_tokens" else "effort"
            }
            if (requiredKind !in capability.reasoningOptionTypes) return null
        }

        // It is still constrained by the reviewed wire protocol so catalog metadata can never invent a serializer.

        if (rawAdvertised.isNotEmpty()) {
            return catalogWire(providerId, model, effort, protocol)
        }

        return fallbackWire(providerId, model, effort, protocol)
    }

    fun supported(providerId: String, model: String): List<ReasoningEffort> =
        ReasoningEffort.entries.filter { wire(providerId, model, it) != null }

    fun defaultFor(providerId: String, model: String): ReasoningEffort? {
        val supported = supported(providerId, model)
        return when {
            ReasoningEffort.HIGH in supported -> ReasoningEffort.HIGH
            ReasoningEffort.LOW in supported -> ReasoningEffort.LOW
            else -> null
        }
    }

     
    fun apply(
        builder: kotlinx.serialization.json.JsonObjectBuilder,
        providerId: String,
        model: String,
        effort: ReasoningEffort?,
    ) {
        if (effort == null) return
        val resolved = requireNotNull(wire(providerId, model, effort)) {
            "${effort.label} reasoning is not supported by $providerId / $model. Select a supported level; the request was not sent."
        }
        when (resolved) {
            is ReasoningWire.OpenAiCompatible -> builder.put("reasoning_effort", resolved.value)
            is ReasoningWire.OpenRouter -> builder.put("reasoning", buildJsonObject { put("effort", resolved.value) })
            is ReasoningWire.AnthropicAdaptive -> {
                builder.put("thinking", buildJsonObject { put("type", "adaptive") })
                builder.put("output_config", buildJsonObject { put("effort", resolved.effort) })
            }
            is ReasoningWire.AnthropicManual -> builder.put("thinking", buildJsonObject {
                put("type", "enabled")
                put("budget_tokens", resolved.budgetTokens)
            })
            // The raw field never reaches Google's native API.

            is ReasoningWire.GoogleNative -> builder.put("reasoning_effort", resolved.value)
        }
    }

    private fun catalogWire(
        providerId: String,
        model: String,
        effort: ReasoningEffort,
        protocol: ProviderWireProtocol,
    ): ReasoningWire? = when (protocol) {
        ProviderWireProtocol.ANTHROPIC_MESSAGES -> anthropicWire(providerId, model, effort, catalogAdvertised = true)
        ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> googleNativeWire(model, effort)
        ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, ProviderWireProtocol.OPENAI_RESPONSES -> {
            if (protocol == ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS && providerId.equals("openrouter", ignoreCase = true)) ReasoningWire.OpenRouter(effort.wireValue)
            else ReasoningWire.OpenAiCompatible(effort.wireValue)
        }
    }

    private fun fallbackWire(
        providerId: String,
        model: String,
        effort: ReasoningEffort,
        protocol: ProviderWireProtocol,
    ): ReasoningWire? = when (protocol) {
        ProviderWireProtocol.ANTHROPIC_MESSAGES -> anthropicWire(providerId, model, effort, catalogAdvertised = false)
        ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> googleNativeWire(model, effort)
        ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, ProviderWireProtocol.OPENAI_RESPONSES -> {
            val allowed = openAiCompatibleFallbackEfforts(providerId, model)
            if (effort.wireValue !in allowed) null
            else if (protocol == ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS && providerId.equals("openrouter", ignoreCase = true)) ReasoningWire.OpenRouter(effort.wireValue)
            else ReasoningWire.OpenAiCompatible(effort.wireValue)
        }
    }


    private fun anthropicWire(providerId: String, model: String, effort: ReasoningEffort, catalogAdvertised: Boolean): ReasoningWire? {
        return when (anthropicThinkingMode(model)) {
            AnthropicThinkingMode.ADAPTIVE -> {
                val allowed = if (catalogAdvertised) setOf(effort.wireValue) else anthropicFallbackEfforts(model)
                if (effort.wireValue !in allowed) null else ReasoningWire.AnthropicAdaptive(effort.wireValue)
            }
            AnthropicThinkingMode.MANUAL -> {
                val preferred = when (effort) {
                    ReasoningEffort.LOW -> 2_048
                    ReasoningEffort.HIGH -> 8_192
                    ReasoningEffort.XTRA_HIGH, ReasoningEffort.ULTRA -> return null
                }
                // These are derived presets; leave at least 1024 output tokens within a known model cap.
                val cap = modelOutputLimit(providerId, model)
                val capability = resolveCapability(providerId, model)
                val maximum = minOf(capability?.reasoningBudgetMax ?: Int.MAX_VALUE, cap?.minus(1_024) ?: Int.MAX_VALUE)
                val minimum = maxOf(1_024, capability?.reasoningBudgetMin ?: 1_024)
                if (maximum < minimum) null else ReasoningWire.AnthropicManual(preferred.coerceIn(minimum, maximum))
            }
            null -> null
        }
    }

    internal enum class AnthropicThinkingMode { ADAPTIVE, MANUAL }

    internal fun anthropicThinkingMode(model: String): AnthropicThinkingMode? {
        val m = normalizedModelId(model)
        if (!m.contains("claude-")) return null
        if (Regex("(?:^|/)claude-(?:opus|sonnet|haiku|fable|mythos)-5(?:[-.]|$)").containsMatchIn(m)) {
            return AnthropicThinkingMode.ADAPTIVE
        }
        val v4 = Regex("(?:^|/)claude-(?:opus|sonnet|haiku)-4[.-](\\d{1,2})(?=[-.]|$)").find(m)
        if (v4 != null) {
            val minor = v4.groupValues[1].toIntOrNull() ?: return null
            return if (minor >= 6) AnthropicThinkingMode.ADAPTIVE else AnthropicThinkingMode.MANUAL
        }
        if (Regex("(?:^|/)claude-(?:opus|sonnet|haiku)-4(?:-\\d{8})?(?:-latest)?$").containsMatchIn(m)) return AnthropicThinkingMode.MANUAL
        if (m.contains("claude-3-7-") || m.contains("claude-3.7-")) return AnthropicThinkingMode.MANUAL
        return null
    }

    private fun anthropicFallbackEfforts(model: String): Set<String> {
        val m = normalizedModelId(model)
        if (Regex("(?:^|/)claude-(?:opus|sonnet|haiku|fable|mythos)-5(?:[-.]|$)").containsMatchIn(m)) {
            return setOf("low", "high", "xhigh", "max")
        }
        val adaptive4 = Regex("(?:^|/)claude-(?:opus|sonnet|haiku)-4[.-](\\d{1,2})(?=[-.]|$)").find(m)
        if (adaptive4 != null) {
            val minor = adaptive4.groupValues[1].toIntOrNull() ?: return emptySet()
            return if (minor >= 7) setOf("low", "high", "xhigh", "max")
            else if (minor == 6) setOf("low", "high", "max")
            else emptySet()
        }
        return setOf("low", "high", "max")
    }

    private fun googleNativeWire(model: String, effort: ReasoningEffort): ReasoningWire? {
        val serializerSupported = googleNativeFallbackEfforts(model)
        if (effort.wireValue !in serializerSupported) return null
        // Catalog metadata may narrow support, never widen the reviewed native serializer.
        return ReasoningWire.GoogleNative(effort.wireValue)
    }

    private fun googleNativeFallbackEfforts(model: String): Set<String> {
        return if (googleNativeThinkingMode(model) != null) setOf("low", "high") else emptySet()
    }

    internal fun googleNativeThinkingMode(model: String): GoogleNativeThinkingMode? {
        if (ProviderModelRequestPolicy.isGoogleSpeechModel(model)) return null
        // Gemini 2.5 accepts token budgets. thinkingLevel is supported only by Gemini 3+.
        // Unversioned aliases cannot determine which native thinking contract to serialize.
        val match = Regex("(?:^|/)gemini-(\\d+)(?:\\.(\\d+))?(?=[-.]|$)")
            .find(normalizedModelId(model)) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull()
        return when {
            major == 2 && minor == 5 -> GoogleNativeThinkingMode.BUDGET
            major >= 3 -> GoogleNativeThinkingMode.LEVEL
            else -> null
        }
    }


    private fun openAiCompatibleFallbackEfforts(
        providerId: String,
        model: String,
    ): Set<String> {
        val m = normalizedModelId(model)

        openAiFamilyEfforts(m).takeIf(Set<String>::isNotEmpty)?.let { return it }
        grokFamilyEfforts(m).takeIf(Set<String>::isNotEmpty)?.let { return it }

        if (googleNativeThinkingMode(m) != null) return setOf("low", "high")
        if (anthropicThinkingMode(m) != null) return setOf("low", "high")
        if (isGlm52(m)) return setOf("high", "max")

        val id = providerId.lowercase()
        if (id == "groq" && groqReasoningModel(m)) return setOf("low", "high")
        if (id == "fireworks" && fireworksReasoningModel(m)) return setOf("low", "high")


        // Reasoning capability alone does not prove that named effort parameters are supported.
        return emptySet()
    }

    private fun openAiFamilyEfforts(model: String): Set<String> {
        val m = normalizedModelId(model)
        val openAiFamily = Regex("(?:^|/)(?:gpt-5(?:[.-]|$)|gpt-6(?:[.-]|$)|o1(?:[-.]|$)|o3(?:[-.]|$)|o4(?:[-.]|$))")
        if (!openAiFamily.containsMatchIn(m)) return emptySet()
        if (Regex("(?:^|/)o1-(?:mini|preview)(?:-|$)").containsMatchIn(m)) return emptySet()
        if (Regex("(?:^|/)o[13]-pro(?:-|$)").containsMatchIn(m)) return setOf("high")
        val pro = Regex("(?:^|/)gpt-5(?:[.-](\\d{1,2}))?[.-]?pro(?=[-.]|$)").find(m)
        if (pro != null) return if (pro.groupValues[1] == "2") setOf("high", "xhigh") else setOf("high")

        val version = Regex("(?:^|/)gpt-5[.-](\\d{1,2})(?=[-.]|$)").find(m)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return when {
            version != null && version >= 6 || Regex("(?:^|/)gpt-6(?=[.-]|$)").containsMatchIn(m) -> setOf("low", "high", "xhigh", "max")
            version != null && version >= 2 -> setOf("low", "high", "xhigh")
            else -> setOf("low", "high")
        }
    }

    private fun grokFamilyEfforts(model: String): Set<String> {
        val m = normalizedModelId(model)
        if (!m.contains("grok-")) return emptySet()
        return when {
            Regex("(?:^|/)grok-4[.-](?:[6-9]|\\d{2})(?=[-.]|$)").containsMatchIn(m) -> setOf("low", "high", "xhigh")
            Regex("(?:^|/)grok-4[.-][35](?=[-.]|$)").containsMatchIn(m) -> setOf("low", "high")
            m.contains("grok-3-mini") -> setOf("low", "high")
            else -> emptySet()
        }
    }

    private fun normalizedAdvertisedEfforts(raw: Set<String>): Set<String> = raw.mapNotNull { value ->
        when (value.trim().lowercase().replace('_', '-')) {
            "low" -> "low"
            "high" -> "high"
            "xhigh", "x-high", "extra-high", "extrahigh" -> "xhigh"
            "max", "maximum", "ultra" -> "max"
            else -> null 
        }
    }.toSet()

    private fun resolveCapability(providerId: String, model: String): ModelCapabilities? {
        ModelCapabilityRegistry.resolve(providerId, model)?.let { return it }
        

        if (providerId.equals("gemini", ignoreCase = true)) {
            return ModelCapabilityRegistry.resolve("google", model)
        }
        return null
    }

    internal fun modelOutputLimit(providerId: String, model: String): Int? =
        resolveCapability(providerId, model)?.outputTokens?.takeIf { it > 0 }

    private fun normalizedModelId(model: String): String = model.trim().lowercase()

    private fun isGlm52(model: String): Boolean =
        listOf("glm-5.2", "glm-5-2", "glm-5p2").any(model::contains)

    private fun groqReasoningModel(model: String): Boolean =
        "gpt-oss" in model || "qwen3.8" in model || "qwen-3.8" in model

    private fun fireworksReasoningModel(model: String): Boolean =
        "qwen3" in model || "qwen-3" in model || "deepseek" in model || "kimi" in model

}
