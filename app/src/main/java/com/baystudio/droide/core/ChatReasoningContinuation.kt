package com.baystudio.droide.core

import kotlinx.serialization.json.*

internal object ChatReasoningContinuation {
    const val PRIVATE_CONTENT_FIELD = "_droide_chat_reasoning"
    private val wireFields = setOf("reasoning_content", "reasoning", "reasoning_details")

    fun capture(body: String, provider: AiProvider, model: String, json: Json): String {
        val root = json.parseToJsonElement(body).jsonObject
        val choices = root.getValue("choices").jsonArray
        val choice = choices.first().jsonObject
        val message = choice.getValue("message").jsonObject
        val retained = if (AgentResponseOutcome.evaluate(choice).accepted) fields(message) else JsonObject(emptyMap())
        val clean = message.filterKeys { it !in wireFields && !it.startsWith("_droide_") }
        val projected = buildJsonObject {
            clean.forEach { (key, value) -> put(key, value) }
            if (retained.isNotEmpty()) put(PRIVATE_CONTENT_FIELD, buildJsonObject {
                put("connection", ProviderConnectionIdentity.fingerprint(provider))
                put("model", model)
                put("fields", retained)
            })
        }
        return JsonObject(root + ("choices" to JsonArray(listOf(JsonObject(choice + ("message" to projected))) + choices.drop(1)))).toString()
    }

    fun restore(message: JsonObject, provider: AiProvider?, model: String): JsonObject {
        val clean = JsonObject(message.filterKeys { !it.startsWith("_droide_") })
        val retained = message[PRIVATE_CONTENT_FIELD] as? JsonObject ?: return clean
        if (provider == null || message["role"] != JsonPrimitive("assistant") ||
            retained["connection"] != JsonPrimitive(ProviderConnectionIdentity.fingerprint(provider)) ||
            retained["model"] != JsonPrimitive(model)) return clean
        val raw = retained["fields"] as? JsonObject ?: error("Invalid retained chat reasoning")
        require(raw.keys.all { it in wireFields }) { "Unsupported retained chat reasoning field" }
        return JsonObject(clean + fields(raw))
    }

    private fun fields(message: JsonObject): JsonObject = buildJsonObject {
        for (key in wireFields) {
            val value = message[key]?.takeUnless { it == JsonNull } ?: continue
            require(if (key == "reasoning_details") value is JsonArray && value.all { it is JsonObject }
                else value is JsonPrimitive && value.isString) { "Invalid provider $key" }
            put(key, value)
        }
    }
}
