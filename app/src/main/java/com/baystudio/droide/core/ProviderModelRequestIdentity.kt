package com.baystudio.droide.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object ProviderModelRequestIdentity {
    data class CanonicalRequest(
        val modelId: String,
        val body: String,
    )

    fun canonicalize(body: String, json: Json): CanonicalRequest {
        val parsed = json.parseToJsonElement(ProviderPayloadLimits.checkedRequest(body)) as? JsonObject
            ?: throw IllegalArgumentException("LLM request body must be a JSON object")
        val root = ProviderToolSchema.normalizeRequest(parsed)
        val raw = root["model"] as? JsonPrimitive
            ?: throw IllegalArgumentException("Provider request is missing model")
        require(raw.isString) { "Provider request model must be a string" }
        val canonical = ProviderModelIdentity.normalize(raw.content)
        if (raw.content == canonical) return CanonicalRequest(canonical, if (root == parsed) body else root.toString())
        val next = root.toMutableMap().apply { put("model", JsonPrimitive(canonical)) }
        return CanonicalRequest(canonical, JsonObject(next).toString())
    }
}
