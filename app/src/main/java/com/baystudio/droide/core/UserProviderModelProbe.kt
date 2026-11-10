package com.baystudio.droide.core

import java.net.URLEncoder
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Explicit model IDs also work on servers without GET /models. No synthetic connection receipt. */
internal object UserProviderModelProbe {
    suspend fun validate(provider: AiProvider, key: String, client: OkHttpClient): List<String> {
        require(provider.id.startsWith("user.") && provider.configuredModels.size in 1..UserProviderDefinition.MAX_MODELS)

        return provider.configuredModels.map { model ->
            ProviderModelRequestPolicy.requireConversational(provider, model)
            val request = request(provider, model)
            val target = NetworkSecurity.validateProviderTarget(provider, provider.baseUrl + request.first)
            val builder = Request.Builder().url(target.url).header("Accept", "application/json")
                .post(request.second.toString().toRequestBody("application/json".toMediaType()))
            ProviderTransportV2.applyAuth(builder, provider, key)
            client.newBuilder().dns(NetworkSecurity.pinnedDns(target)).build().newCall(builder.build()).awaitResponse().use { response ->
                val source = response.body?.source() ?: error("Provider returned an empty validation response.")
                source.request(MAX_RESPONSE_BYTES + 1)
                require(source.buffer.size <= MAX_RESPONSE_BYTES) { "Provider validation response is too large." }
                val body = source.buffer.readUtf8()
                check(response.isSuccessful) { ProviderFailure.describe(response.code, ProviderFailure.redact(body, listOf(key))) }
                require(validResponse(provider.wireProtocol, body)) { "Provider did not return a valid model response. Check the selected API protocol and model ID." }
            }
            model
        }
    }

    internal fun request(provider: AiProvider, model: String): Pair<String, JsonObject> {
        val message = buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", "Reply OK.") }) }
        return when (provider.wireProtocol) {
            ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> "/chat/completions" to buildJsonObject {
                put("model", model); put("messages", message); put("max_tokens", 16); put("stream", false)
            }
            ProviderWireProtocol.OPENAI_RESPONSES -> "/responses" to buildJsonObject {
                put("model", model); put("input", message); put("max_output_tokens", 16); put("store", false); put("stream", false)
            }
            ProviderWireProtocol.ANTHROPIC_MESSAGES -> "/messages" to buildJsonObject {
                put("model", model); put("messages", message); put("max_tokens", 16); put("stream", false)
            }
            ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> {
                val encoded = URLEncoder.encode(model.removePrefix("models/"), "UTF-8").replace("+", "%20")
                "/models/$encoded:generateContent" to buildJsonObject {
                    putJsonArray("contents") { add(buildJsonObject {
                        put("role", "user"); putJsonArray("parts") { add(buildJsonObject { put("text", "Reply OK.") }) }
                    }) }
                    putJsonObject("generationConfig") { put("maxOutputTokens", 16) }
                }
            }
        }
    }

    internal fun validResponse(protocol: ProviderWireProtocol, body: String): Boolean = runCatching {
        val root = Json.parseToJsonElement(body) as? JsonObject ?: return@runCatching false
        if (root["error"] != null && root["error"] != JsonNull) return@runCatching false
        when (protocol) {
            ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> (root["choices"] as? JsonArray).orEmpty().any {
                (it as? JsonObject)?.get("message") is JsonObject
            }
            ProviderWireProtocol.OPENAI_RESPONSES -> root["output"] is JsonArray &&
                (root["status"] as? JsonPrimitive)?.contentOrNull in setOf("completed", "incomplete")
            ProviderWireProtocol.ANTHROPIC_MESSAGES -> root["type"] == JsonPrimitive("message") && root["content"] is JsonArray
            ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> (root["candidates"] as? JsonArray).orEmpty().any {
                (it as? JsonObject)?.get("content") is JsonObject
            }
        }
    }.getOrDefault(false)

    private const val MAX_RESPONSE_BYTES = 256 * 1024L
}
