package com.baystudio.droide.core

import kotlinx.serialization.json.*

internal object ProviderResponseCompletion {
    fun complete(body: String, json: Json): String = admit(body, json)

    /** Raw compatible buffered messages have separate public content/refusal fields. */
    fun bufferedChat(body: String, json: Json): String {
        val root = json.parseToJsonElement(complete(body, json)).jsonObject
        val choices = root.getValue("choices").jsonArray
        val choice = choices.first().jsonObject
        val message = choice.getValue("message").jsonObject
        val refusal = (message["refusal"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val content = (message["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        if (refusal.isBlank() || refusal in content) return root.toString()
        // Do not conceal malformed raw content from the common outcome validator.
        val raw = message["content"]
        if (raw != null && raw != JsonNull && (raw !is JsonPrimitive || !raw.isString)) return root.toString()
        val publicText = if (content.isBlank()) refusal else "$content\n$refusal"
        val projected = JsonObject(message + ("content" to JsonPrimitive(publicText)))
        return JsonObject(root + ("choices" to JsonArray(listOf(JsonObject(choice + ("message" to projected))) + choices.drop(1)))).toString()
    }

    fun google(body: String, nativeReason: String, json: Json): String = admit(body, json,
        finishOverride = when (nativeReason) {
            "STOP" -> null
            "MAX_TOKENS" -> "length"
            else -> "content_filter"
        },
        allowTools = nativeReason == "STOP",
        nativeFinishReason = GoogleGenerateContentAdapter.safeNativeCompletionReason(nativeReason),
    )

    /** Accept only an envelope already authored by the native Anthropic adapter. */
    fun anthropic(body: String, json: Json): String {
        val root = json.parseToJsonElement(body) as? JsonObject
        val choice = (root?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject
        val native = (choice?.get(AnthropicMessagesAdapter.NATIVE_STOP_REASON_FIELD) as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
        return admit(body, json, anthropicStopReason = AnthropicMessagesAdapter.safeNativeStopReason(native))
    }

    private fun admit(
        body: String,
        json: Json,
        finishOverride: String? = null,
        allowTools: Boolean? = null,
        nativeFinishReason: String? = null,
        anthropicStopReason: String? = null,
    ): String {
        val root = json.parseToJsonElement(body) as? JsonObject ?: throw IllegalStateException("Malformed streamed response")
        root["error"]?.takeUnless { it == JsonNull }?.let { throw ProviderErrorPayloadException(it) }
        val choices = root["choices"] as? JsonArray ?: throw IllegalStateException("Streamed response is missing choices")
        val choice = choices.firstOrNull() as? JsonObject ?: throw IllegalStateException("Streamed response has no choice")
        choice["error"]?.takeUnless { it == JsonNull }?.let { throw ProviderErrorPayloadException(it) }
        var finish = finishOverride ?: (choice["finish_reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        check(!finish.isNullOrBlank()) { "Provider stream ended before a finish reason" }
        val message = choice["message"] as? JsonObject ?: throw IllegalStateException("Streamed response is missing message")
        val rawCalls = message["tool_calls"]
        val calls = rawCalls as? JsonArray
        // Stripping a contradictory tool batch must not turn it into a successful answer.
        if (finish == "stop" && (!calls.isNullOrEmpty() || rawCalls != null && rawCalls != JsonNull && calls == null || message["function_call"]?.let { it != JsonNull } == true)) {
            finish = "invalid_tool_turn"
        }
        val safeMessage = if (allowTools ?: (finish == "tool_calls")) message else
            JsonObject(message.filterKeys { it != "tool_calls" && it != "function_call" && !it.startsWith("_droide_") })
        // Only the native adapter may author this diagnostic field. Compatible buffered JSON must not
        // smuggle arbitrary provider prose into it.
        val safeChoice = buildJsonObject {
            choice.filterKeys { it !in setOf(GoogleGenerateContentAdapter.NATIVE_FINISH_REASON_FIELD, AnthropicMessagesAdapter.NATIVE_STOP_REASON_FIELD) }.forEach { (key, value) -> put(key, value) }
            put("message", safeMessage)
            put("finish_reason", finish)
            nativeFinishReason?.let { put(GoogleGenerateContentAdapter.NATIVE_FINISH_REASON_FIELD, it) }
            anthropicStopReason?.let { put(AnthropicMessagesAdapter.NATIVE_STOP_REASON_FIELD, it) }
        }
        return JsonObject(root + ("choices" to JsonArray(listOf(safeChoice) + choices.drop(1)))).toString()
    }
}
