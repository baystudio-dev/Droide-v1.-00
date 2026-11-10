package com.baystudio.droide.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object PromptCacheSupport {
    fun systemMessage(config: AgentConfig, systemText: String, tools: JsonArray?, availableBreakpoints: Int = 1): JsonObject {
        val explicit = availableBreakpoints > 0 && tools != null && supportsExplicitCacheControl(config.providerId, config.model)
        return buildJsonObject {
            put("role", "system")
            if (explicit) {
                // Only split an exact, owned prefix.

                val prefix = if (availableBreakpoints >= 2) stablePrefix(systemText) else null
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", prefix ?: systemText)
                        put("cache_control", buildJsonObject { put("type", "ephemeral") })
                    })
                    if (prefix != null && prefix.length < systemText.length) {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", systemText.substring(prefix.length))

                            put("cache_control", buildJsonObject { put("type", "ephemeral") })
                        })
                    }
                })
            } else {
                put("content", systemText)
            }
        }
    }

    /** Count conservatively even unknown nested cache_control keys, so imported history or tool
     * metadata cannot make this optimization exceed the request-wide budget. */
    internal fun availableBreakpoints(history: List<JsonObject>, tools: JsonArray?): Int {
        val pending = java.util.ArrayDeque<JsonElement>()
        history.forEach(pending::addLast)
        tools?.let(pending::addLast)
        var remaining = 4
        while (pending.isNotEmpty() && remaining > 0) {
            when (val value = pending.removeLast()) {
                is JsonObject -> {
                    if ("cache_control" in value) remaining--
                    value.values.forEach(pending::addLast)
                }
                is JsonArray -> value.forEach(pending::addLast)
                else -> Unit
            }
        }
        return remaining
    }

    private fun stablePrefix(text: String): String? = when {
        text.startsWith(AgentSystemPrompt.STABLE_PREFIX) -> AgentSystemPrompt.STABLE_PREFIX
        text.startsWith(AgentSystemPrompt.GREETING_TEXT) -> AgentSystemPrompt.GREETING_TEXT
        else -> null
    }

    // A routed model name alone does not authorize metadata on arbitrary/custom OpenAI-compatible
    // endpoints.
    internal fun supportsExplicitCacheControl(providerId: String, model: String): Boolean {
        if (providerId.equals("anthropic", ignoreCase = true)) return true
        if (!providerId.equals("openrouter", ignoreCase = true)) return false
        return model.trim().lowercase().startsWith("anthropic/")
    }
}
