package com.baystudio.droide.core

import kotlinx.serialization.json.*

internal object ProviderModelCatalog {
    data class Entry(val id: String, val metadata: JsonObject?)

    fun modelsPath(provider: AiProvider): String = when {
        provider.id == "openrouter" -> "/models?supported_parameters=tools&output_modalities=text"
        provider.wireProtocol == ProviderWireProtocol.GOOGLE_GENERATE_CONTENT &&
            java.net.URI(provider.baseUrl).host == "generativelanguage.googleapis.com" -> "/models?pageSize=1000"
        else -> "/models"
    }

    fun parse(body: String, provider: AiProvider, json: Json): List<Entry> {
        val root = json.parseToJsonElement(body) as? JsonObject
            ?: throw IllegalArgumentException("Provider model catalog must be an object")
        val entries = (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray)
            ?: throw IllegalArgumentException("Provider returned no model catalog")
        return entries.mapNotNull { entry ->
            when (entry) {
                is JsonObject -> {
                    if (provider.wireProtocol == ProviderWireProtocol.GOOGLE_GENERATE_CONTENT) {
                        val actions = (entry["supportedGenerationMethods"] as? JsonArray) ?: (entry["supportedActions"] as? JsonArray)
                        if (actions != null && actions.none { (it as? JsonPrimitive)?.contentOrNull == "generateContent" }) return@mapNotNull null
                    }
                    val raw = (entry["id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                        ?: (entry["name"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return@mapNotNull null
                    val id = modelId(raw, provider) ?: return@mapNotNull null
                    Entry(id, entry)
                }
                is JsonPrimitive -> entry.takeIf { it.isString }?.let { modelId(it.content, provider)?.let { id -> Entry(id, null) } }
                else -> null
            }
        }.distinctBy { it.id }.take(1_000)
    }

    private fun modelId(raw: String, provider: AiProvider): String? {
        val canonical = ProviderModelIdentity.normalizeOrNull(raw) ?: return null
        // Only Google's catalog uses this resource prefix. Other providers may own an actual namespaced
        // model ID such as "models/company-model".
        return if (provider.wireProtocol == ProviderWireProtocol.GOOGLE_GENERATE_CONTENT) {
            ProviderModelIdentity.normalizeOrNull(canonical.removePrefix("models/"))
        } else canonical
    }
}
