package com.baystudio.droide.core

import kotlinx.serialization.json.*

internal data class ProviderFailureSnapshot(val publicText: String, val usage: TokenUsage)

/** One network attempt's accounting; marking precedes publication to prevent reentrant duplicates. */
internal class ProviderRequestAccounting(
    private val providerId: String,
    private val onUsage: (TokenUsage) -> Unit,
) {
    private var recorded = false
    private var callbackFailure: Throwable? = null
    fun isCallbackFailure(failure: Throwable): Boolean = callbackFailure === failure
    fun response(body: String): TokenUsage = record(TokenTracker.parseProviderUsage(providerId, body))
    fun failure(failure: Throwable): TokenUsage? {
        if (recorded) return null
        return (failure as? LlmProviderStreamException)?.publicSnapshot?.usage?.let(::record)
    }
    private fun record(sample: TokenUsage): TokenUsage {
        check(!recorded) { "Provider request usage was already recorded" }
        recorded = true
        try { onUsage(sample) } catch (failure: Throwable) {
            callbackFailure = failure
            throw failure
        }
        return sample
    }
}

/** Ephemeral request-local state. Only snapshot() may cross the exception boundary. */
internal class ProviderFailureProgress(private val providerId: String, private val json: Json) {
    private val publicText = StringBuilder()
    private var terminalPublicCandidate: String? = null
    private var usage: JsonObject? = null
    private var anthropicUsage: JsonObject? = null
    private var callbackFailure: Throwable? = null

    suspend fun emit(event: LlmStreamEvent, callback: suspend (LlmStreamEvent) -> Unit) {
        record(event)
        try { callback(event) } catch (failure: Throwable) {
            callbackFailure = failure
            throw failure
        }
    }

    fun isCallbackFailure(failure: Throwable): Boolean = callbackFailure === failure

    fun observe(protocol: ProviderWireProtocol, body: String, buffered: Boolean = false) {
        val root = runCatching { json.parseToJsonElement(ProviderPayloadLimits.checkedJson(body)) as? JsonObject }.getOrNull() ?: return
        val response = if (protocol == ProviderWireProtocol.OPENAI_RESPONSES) root["response"] as? JsonObject ?: root else root
        val report = when (protocol) {
            ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> root["usage"]?.takeUnless { it == JsonNull }
                ?: (root["x_groq"] as? JsonObject)?.get("usage")
            ProviderWireProtocol.OPENAI_RESPONSES -> response["usage"]
            ProviderWireProtocol.ANTHROPIC_MESSAGES -> if (root["type"] == JsonPrimitive("message_start"))
                (root["message"] as? JsonObject)?.get("usage") else root["usage"]
            ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> root["usageMetadata"]
        }
        if (report != null && report != JsonNull) {
            val obj = report as? JsonObject
            if (obj == null) {
                usage = null
                if (protocol == ProviderWireProtocol.ANTHROPIC_MESSAGES) anthropicUsage = null
            } else usage = when (protocol) {
                ProviderWireProtocol.ANTHROPIC_MESSAGES -> {

                    anthropicUsage = JsonObject(anthropicUsage.orEmpty() + obj)
                    AnthropicMessagesAdapter.normalizeUsage(checkNotNull(anthropicUsage))
                }
                ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> GoogleGenerateContentAdapter.normalizeUsage(obj)
                ProviderWireProtocol.OPENAI_RESPONSES -> buildJsonObject {
                    obj["input_tokens"]?.let { put("prompt_tokens", it) }
                    obj["output_tokens"]?.let { put("completion_tokens", it) }
                    obj["total_tokens"]?.let { put("total_tokens", it) }
                    obj["input_tokens_details"]?.let { put("prompt_tokens_details", it) }
                    obj["output_tokens_details"]?.let { put("completion_tokens_details", it) }
                }
                ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> obj
            }
        }
        val choiceError = ((response["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("error")
        val failed = response["error"]?.let { it != JsonNull } == true || choiceError?.let { it != JsonNull } == true || root["type"] == JsonPrimitive("response.failed")
        if (buffered || failed) reconcile(bufferedPublic(protocol, response))
        else if (protocol == ProviderWireProtocol.OPENAI_RESPONSES &&
            root["type"] in setOf(JsonPrimitive("response.completed"), JsonPrimitive("response.incomplete"))) {
            // A typed cumulative public candidate may precede a tool-validation failure. Do not pre-emit it:
            // successful accumulator events retain their existing order.
            terminalPublicCandidate = bufferedPublic(protocol, response).also { requireCapacity(it.length) }
        }
    }

    fun record(event: LlmStreamEvent) {
        when (event) {
            is LlmStreamEvent.TextDelta -> {
                requireCapacity(event.text.length, publicText.length)
                publicText.append(event.text)
            }
            is LlmStreamEvent.BufferedResponse -> reconcile(event.text)
            else -> Unit
        }
    }

    private fun requireCapacity(incoming: Int, retained: Int = 0) {
        if (incoming > LlmClient.MAX_RESPONSE_CHARS - retained) {
            // Do not retain a prefix that could end inside a credential split across events.
            publicText.setLength(0)
            terminalPublicCandidate = null
            error("Provider public output exceeds response capacity")
        }
    }

    private fun reconcile(candidate: String) {
        requireCapacity(candidate.length)
        // Never concatenate a cumulative snapshot onto already-emitted deltas. Divergent
        // buffered/failure content cannot overwrite the emitted public order.
        if (candidate.startsWith(publicText.toString())) {
            publicText.setLength(0)
            publicText.append(candidate)
        }
    }

    fun snapshot(secrets: Collection<String>): ProviderFailureSnapshot {

        val emitted = publicText.toString()
        val retained = terminalPublicCandidate?.takeIf { it.startsWith(emitted) } ?: emitted
        val safeText = ProviderFailure.redact(retained, secrets).take(LlmClient.MAX_RESPONSE_CHARS)
        val envelope = buildJsonObject { usage?.let { put("usage", it) } }.toString()
        return ProviderFailureSnapshot(safeText, TokenTracker.parseProviderUsage(providerId, envelope))
    }

    private fun string(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun bufferedPublic(protocol: ProviderWireProtocol, root: JsonObject): String = when (protocol) {
        ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> {
            val message = ((root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
            if (message == null || message["role"]?.let { it != JsonPrimitive("assistant") } == true) ""
            else {
                val text = string(message["content"]).orEmpty()
                val refusal = string(message["refusal"]).orEmpty()
                if (refusal.isBlank() || refusal in text) text else if (text.isBlank()) refusal else "$text\n$refusal"
            }
        }
        ProviderWireProtocol.OPENAI_RESPONSES -> buildString {
            (root["output"] as? JsonArray)?.forEach { raw ->
                val item = raw as? JsonObject ?: return@forEach
                if (item["type"] != JsonPrimitive("message") || item["role"] != JsonPrimitive("assistant")) return@forEach
                (item["content"] as? JsonArray)?.forEach { part ->
                    val block = part as? JsonObject ?: return@forEach
                    when (block["type"]) {
                        JsonPrimitive("output_text") -> string(block["text"])?.let(::append)
                        JsonPrimitive("refusal") -> string(block["refusal"])?.let(::append)
                        else -> Unit
                    }
                }
            }
        }
        ProviderWireProtocol.ANTHROPIC_MESSAGES -> buildString {
            (root["content"] as? JsonArray)?.forEach { raw ->
                val block = raw as? JsonObject ?: return@forEach
                if (block["type"] == JsonPrimitive("text")) string(block["text"])?.let(::append)
            }
        }
        ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> buildString {
            val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
            val content = candidate?.get("content") as? JsonObject
            (content?.get("parts") as? JsonArray)?.forEach { raw ->
                val part = raw as? JsonObject ?: return@forEach
                if (part["thought"] == null || part["thought"] == JsonPrimitive(false)) string(part["text"])?.let(::append)
            }
        }
    }
}
