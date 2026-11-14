package com.baystudio.droide.core

import java.util.concurrent.atomic.AtomicReference

enum class ModelCapabilitySource { MODELS_DEV, RUNTIME_PROVIDER, PROTOCOL_FALLBACK }

data class ModelCapabilities(
    val providerId: String,
    val modelId: String,
    val displayName: String,
    val attachment: Boolean?,
    val reasoning: Boolean?,
    val toolCall: Boolean?,
    val structuredOutput: Boolean?,
    val temperature: Boolean?,
    val inputModalities: Set<String>,
    val outputModalities: Set<String>,
    val reasoningEfforts: Set<String>,
    val contextTokens: Int?,
    val outputTokens: Int?,
    val source: ModelCapabilitySource,
    val supportedEndpoints: Set<String> = emptySet(),
    val reasoningOptionsKnown: Boolean = false,
    val reasoningOptionTypes: Set<String> = emptySet(),
    val reasoningBudgetMin: Int? = null,
    val reasoningBudgetMax: Int? = null,
    val transportCompatible: Boolean? = null,
    val inputTokens: Int? = null,
)

// Catalog metadata may disable a feature but never invents a wire implementation.


object ModelCapabilityRegistry {
    private val catalog = AtomicReference<Map<String, ModelCapabilities>>(emptyMap())
    private val runtime = AtomicReference<Map<String, ModelCapabilities>>(emptyMap())

    fun installCatalog(entries: Collection<ModelCapabilities>) {
        val next = LinkedHashMap<String, ModelCapabilities>(entries.size)
        entries.forEach { capability ->
            val provider = capability.providerId.trim().lowercase()
            val model = capability.modelId.trim()
            if (provider.isNotEmpty() && model.isNotEmpty()) {
                next[key(provider, model)] = capability.copy(providerId = provider, modelId = model)
            }
        }
        catalog.set(next.toMap())
    }

    fun resolve(providerId: String, modelId: String): ModelCapabilities? {
        val id = key(providerId, modelId)
        val live = runtime.get()[id]
        val saved = catalog.get()[id]
            ?: catalog.get()[key(catalogIdentity(providerId), modelId)]
        if (live == null) return saved
        if (saved == null) return live
        // Live discovery is often partial; absent fields must not erase known model limits.
        return merge(saved, live)
    }

    private fun merge(saved: ModelCapabilities, live: ModelCapabilities): ModelCapabilities = live.copy(
            attachment = live.attachment ?: saved.attachment,
            reasoning = live.reasoning ?: saved.reasoning,
            toolCall = live.toolCall ?: saved.toolCall,
            structuredOutput = live.structuredOutput ?: saved.structuredOutput,
            temperature = live.temperature ?: saved.temperature,
            inputModalities = live.inputModalities.ifEmpty { saved.inputModalities },
            outputModalities = live.outputModalities.ifEmpty { saved.outputModalities },
            reasoningEfforts = if (live.reasoningOptionsKnown) live.reasoningEfforts else live.reasoningEfforts.ifEmpty { saved.reasoningEfforts },
            contextTokens = live.contextTokens ?: saved.contextTokens,
            outputTokens = live.outputTokens ?: saved.outputTokens,
            inputTokens = live.inputTokens ?: saved.inputTokens,
            supportedEndpoints = live.supportedEndpoints.ifEmpty { saved.supportedEndpoints },
            transportCompatible = live.transportCompatible ?: saved.transportCompatible,
            reasoningOptionsKnown = live.reasoningOptionsKnown || saved.reasoningOptionsKnown,
            reasoningOptionTypes = if (live.reasoningOptionsKnown) live.reasoningOptionTypes else saved.reasoningOptionTypes,
            reasoningBudgetMin = if (live.reasoningOptionsKnown) live.reasoningBudgetMin else saved.reasoningBudgetMin,
            reasoningBudgetMax = if (live.reasoningOptionsKnown) live.reasoningBudgetMax else saved.reasoningBudgetMax,
        )

    fun installRuntime(capability: ModelCapabilities) {
        require(capability.source == ModelCapabilitySource.RUNTIME_PROVIDER) { "Runtime capability must identify its source" }
        val provider = capability.providerId.trim().lowercase()
        val model = capability.modelId.trim()
        require(provider.isNotEmpty() && model.isNotEmpty()) { "Runtime capability identity is invalid" }
        val normalized = capability.copy(providerId = provider, modelId = model)
        val k = key(provider, model)
        while (true) {
            val current = runtime.get()
            val next = current.toMutableMap().apply { put(k, current[k]?.let { merge(it, normalized) } ?: normalized) }.toMap()
            if (runtime.compareAndSet(current, next)) break
        }
    }

    fun revokeRuntime(providerId: String, modelId: String? = null): Boolean {
        val provider = providerId.trim().lowercase()
        val model = modelId?.trim()
        ModelContextRegistry.forget(provider, model)
        while (true) {
            val current = runtime.get()
            val next = if (model == null) current.filterKeys { !it.startsWith(provider + "\u0000") }
            else current - key(provider, model)
            if (next.size == current.size) return false
            if (runtime.compareAndSet(current, next)) return true
        }
    }

    fun catalogModels(providerId: String): List<String> {
        val prefixes = setOf(providerId.trim().lowercase(), catalogIdentity(providerId)).map { it + "\u0000" }
        return catalog.get().entries.asSequence()
            .filter { entry -> prefixes.any { entry.key.startsWith(it) } }
            .map { it.value.modelId }
            .distinct()
            .sorted()
            .toList()
    }

    // Explicit false is authoritative.
    fun allowsToolUse(providerId: String, modelId: String): Boolean {
        val declared = resolve(providerId, modelId)?.toolCall
        if (declared != null) return declared
        // These first-generation o1 variants explicitly do not support function calling.
        if (providerId.equals("openai", ignoreCase = true) &&
            Regex("^o1-(?:mini|preview)(?:-\\d{4}-\\d{2}-\\d{2})?$").matches(modelId)) return false
        return true
    }

    fun allowsImageInput(providerId: String, modelId: String): Boolean? {
        val model = resolve(providerId, modelId) ?: return null
        if (model.inputModalities.isNotEmpty()) return "image" in model.inputModalities
        return model.attachment
    }

    fun allowsReasoning(providerId: String, modelId: String): Boolean? = resolve(providerId, modelId)?.reasoning

    fun advertisedReasoningEfforts(providerId: String, modelId: String): Set<String> =
        resolve(providerId, modelId)?.reasoningEfforts.orEmpty()

    private fun catalogIdentity(providerId: String): String = when (val id = providerId.trim().lowercase()) {
        "gemini" -> "google"
        "together" -> "togetherai"
        "fireworks" -> "fireworks-ai"
        else -> id
    }

    private fun key(providerId: String, modelId: String): String =
        providerId.trim().lowercase() + "\u0000" + modelId.trim()
}
