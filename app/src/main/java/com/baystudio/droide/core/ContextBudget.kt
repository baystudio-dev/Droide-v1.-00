package com.baystudio.droide.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.ConcurrentHashMap

object ModelContextRegistry {
    private val contextTokens = ConcurrentHashMap<String, Int>()

    fun remember(providerId: String, modelId: String, tokens: Int?) {
        val safe = validLimit(tokens)
        if (safe == null) contextTokens.remove(key(providerId, modelId))
        else contextTokens[key(providerId, modelId)] = safe
    }

    /** Shared input + output window only. Input-only discovery must not revive a legacy cached window. */
    fun resolve(providerId: String, modelId: String): Int? {
        val capability = ModelCapabilityRegistry.resolve(providerId, modelId)
        validLimit(capability?.contextTokens)?.let { return it }
        if (validLimit(capability?.inputTokens) != null) return null
        return contextTokens[key(providerId, modelId)]
    }

    fun resolveInput(providerId: String, modelId: String): Int? =
        validLimit(ModelCapabilityRegistry.resolve(providerId, modelId)?.inputTokens)

    internal fun forget(providerId: String, modelId: String? = null) {
        if (modelId != null) contextTokens.remove(key(providerId, modelId))
        else contextTokens.keys.removeAll { it.startsWith(providerId.trim().lowercase() + "\u0000") }
    }

    internal fun contextLimitFromModel(model: JsonObject): Int? = minimum(
        model["context_length"], model["context_window"], model["max_context_length"],
        (model["top_provider"] as? JsonObject)?.get("context_length"),
        (model["limit"] as? JsonObject)?.get("context"),
    )

    internal fun inputLimitFromModel(model: JsonObject): Int? = minimum(
        model["max_input_tokens"], model["inputTokenLimit"], model["input_token_limit"],
        (model["limit"] as? JsonObject)?.get("input"),
        (model["top_provider"] as? JsonObject)?.get("max_input_tokens"),
    )

    internal fun outputLimitFromModel(model: JsonObject): Int? = minimum(
        model["outputTokenLimit"], model["output_token_limit"], model["max_output_tokens"],
        model["max_completion_tokens"], (model["limit"] as? JsonObject)?.get("output"),
        (model["top_provider"] as? JsonObject)?.get("max_completion_tokens"),
    )

    private fun minimum(vararg values: JsonElement?): Int? = values.mapNotNull {
        validLimit((it as? JsonPrimitive)?.intOrNull)
    }.minOrNull()

    internal fun validLimit(tokens: Int?): Int? = tokens?.takeIf { it in MIN_CONTEXT_TOKENS..MAX_CONTEXT_TOKENS }

    private fun key(providerId: String, modelId: String) = "${providerId.trim().lowercase()}\u0000${modelId.trim()}"

    private const val MIN_CONTEXT_TOKENS = 1
    private const val MAX_CONTEXT_TOKENS = 16_000_000
}

data class ContextEstimate(
    val approximateTokens: Int,
    val serializedChars: Int,
    val knownContextLimit: Int?,
    val triggerTokens: Int?,
    val knownInputLimit: Int? = null,
    val reservedOutputTokens: Int = 0,
) {
    val shouldCompact: Boolean
        get() = triggerTokens?.let { approximateTokens >= it }
            ?.let { it || serializedChars >= ContextBudget.MOBILE_COMPACT_CHARS }
            ?: (serializedChars >= ContextBudget.UNKNOWN_LIMIT_COMPACT_CHARS)
}

object ContextBudget {

    const val KEEP_RECENT_TOKENS = 75_000

    const val BUFFER_TOKENS = 20_000
    // Unknown catalogs never get a fabricated token limit.

    const val MOBILE_COMPACT_CHARS = 1_200_000
    const val UNKNOWN_LIMIT_COMPACT_CHARS = MOBILE_COMPACT_CHARS
    const val UNKNOWN_OVERFLOW_KEEP_TOKENS = 4_000
    // Approximate image allowance only. Providers tokenize resized image patches differently; base64
    // wire bytes are never a token count. Actual usage still comes from the response.
    private const val ESTIMATED_IMAGE_TOKENS = 4_096

    fun estimate(
        systemText: String,
        history: List<JsonObject>,
        tools: JsonArray?,
        providerId: String,
        modelId: String,
        transport: ProviderTransportSpec? = null,
    ): ContextEstimate = estimate(systemText, history, tools, providerId, modelId, transport,
        ProviderRequestBudget.defaultOutputTokens(providerId, modelId))

    fun estimate(
        systemText: String,
        history: List<JsonObject>,
        tools: JsonArray?,
        providerId: String,
        modelId: String,
        transport: ProviderTransportSpec?,
        reservedOutputTokens: Int,
    ): ContextEstimate {
        require(reservedOutputTokens > 0)
        var imageCount = 0
        var projectedHistoryBytes = 0L
        val historyChars = history.sumOf { message ->
            val content = message["content"] as? JsonArray
            val normalized = if (content == null) message else JsonObject(message + ("content" to JsonArray(content.map { raw ->
                val part = raw as? JsonObject
                when ((part?.get("type") as? JsonPrimitive)?.contentOrNull) {
                    "droide_image_ref", "image_url", "input_image" -> {
                        imageCount++
                        JsonPrimitive("[image input]")
                    }
                    else -> raw
                }
            })))
            projectedHistoryBytes += serializedUtf8Bytes(NativeContextProjection.forAccounting(normalized, transport)).toLong()
            serializedChars(normalized).toLong()
        }
        val chars = (systemText.length.toLong() + historyChars +
            (tools?.let(::serializedChars) ?: 0) + 1_024).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        // Keep the original representation for the independent mobile working-set guard. Only token
        // accounting uses the matching adapter's validated native selection.
        val projectedBytes = (utf8Bytes(systemText).toLong() + projectedHistoryBytes +
            (tools?.let(::serializedUtf8Bytes) ?: 0) + 1_024).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val tokens = (estimateTokensFromChars(projectedBytes).toLong() + imageCount.toLong() * ESTIMATED_IMAGE_TOKENS)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val limit = ModelContextRegistry.resolve(providerId, modelId)
        val inputLimit = ModelContextRegistry.resolveInput(providerId, modelId)
        val reserve = reservedOutputTokens
        val available = effectiveInputTokens(providerId, modelId, reserve)
        val trigger = available?.let { (it - effectiveBufferTokens(it)).coerceAtLeast(0) }
        return ContextEstimate(tokens, chars, limit, trigger, inputLimit, reserve)
    }

    /** Subtract output once from a shared window, never from an input-only ceiling. */
    fun effectiveInputTokens(providerId: String, modelId: String, outputTokens: Int): Int? {
        require(outputTokens >= 0)
        val shared = ModelContextRegistry.resolve(providerId, modelId)?.let { (it - outputTokens).coerceAtLeast(0) }
        val input = ModelContextRegistry.resolveInput(providerId, modelId)
        return listOfNotNull(shared, input).minOrNull()
    }

    fun recentKeepTokens(providerId: String, modelId: String): Int =
        recentKeepTokens(providerId, modelId, ProviderRequestBudget.defaultOutputTokens(providerId, modelId))

    fun recentKeepTokens(providerId: String, modelId: String, reservedOutputTokens: Int): Int {
        val available = effectiveInputTokens(providerId, modelId, reservedOutputTokens)
            ?: return KEEP_RECENT_TOKENS
        val trigger = (available - effectiveBufferTokens(available)).coerceAtLeast(0)
        val mobileInputTokens = MOBILE_COMPACT_CHARS / 4
        return (minOf(trigger, mobileInputTokens) / 2).coerceAtLeast(1)
    }

    fun overflowRecoveryKeepTokens(providerId: String, modelId: String): Int =
        overflowRecoveryKeepTokens(providerId, modelId, ProviderRequestBudget.defaultOutputTokens(providerId, modelId))

    fun overflowRecoveryKeepTokens(providerId: String, modelId: String, reservedOutputTokens: Int): Int =
        if (effectiveInputTokens(providerId, modelId, 0) == null) UNKNOWN_OVERFLOW_KEEP_TOKENS
        else (recentKeepTokens(providerId, modelId, reservedOutputTokens) / 2).coerceAtLeast(1)

    private fun effectiveBufferTokens(inputLimit: Int): Int =
        BUFFER_TOKENS.coerceAtMost((inputLimit / 4).coerceAtLeast(1))

    // Coarse UTF-8 bytes/4 estimate; this is not a tokenizer upper bound.

    fun estimateTokens(text: String): Int = estimateTokensFromChars(utf8Bytes(text))
    fun estimateTokensFromChars(chars: Int): Int = if (chars <= 0) 0 else ((chars.toLong() + 3) / 4).toInt()

    /** Count JSON text without allocating a second full serialized conversation for each model step. */
    internal fun serializedChars(value: JsonElement): Int = serializedSize(value, utf8 = false)

    internal fun serializedUtf8Bytes(value: JsonElement): Int = serializedSize(value, utf8 = true)

    internal fun utf8Bytes(text: String): Int {
        var bytes = 0L
        var index = 0
        while (index < text.length) {
            val ch = text[index++]
            bytes += when {
                ch.code < 0x80 -> 1
                ch.code < 0x800 -> 2
                ch.isHighSurrogate() && index < text.length && text[index].isLowSurrogate() -> { index++; 4 }
                ch.isSurrogate() -> 1
                else -> 3
            }
        }
        return bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun serializedSize(value: JsonElement, utf8: Boolean): Int {
        val pending = ArrayDeque<Pair<JsonElement, Int>>()
        pending.add(value to 0)
        var count = 0L
        fun stringChars(text: String): Long = 2L + (if (utf8) utf8Bytes(text) else text.length) + text.sumOf { ch -> when {
            ch == '"' || ch == '\\' -> 1L
            ch < ' ' -> if (ch in "\b\n\r\t\u000C") 1L else 5L
            else -> 0L
        } }
        while (pending.isNotEmpty()) {
            val (element, depth) = pending.removeLast()
            if (depth > ProviderPayloadLimits.MAX_JSON_DEPTH) return Int.MAX_VALUE
            when (element) {
                is JsonObject -> {
                    count += 2L + (element.size - 1).coerceAtLeast(0)
                    element.forEach { (key, item) -> count += stringChars(key) + 1; pending.add(item to depth + 1) }
                }
                is JsonArray -> {
                    count += 2L + (element.size - 1).coerceAtLeast(0)
                    element.forEach { pending.add(it to depth + 1) }
                }
                is JsonPrimitive -> count += if (element.isString) stringChars(element.content) else element.content.length.toLong()
            }
            if (count >= Int.MAX_VALUE) return Int.MAX_VALUE
        }
        return count.toInt()
    }
}
