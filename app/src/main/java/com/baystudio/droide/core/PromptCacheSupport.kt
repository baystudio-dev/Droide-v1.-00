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
                // Only split an exact, owned prefix. Project/profile/mode/capability text
                // remains in its original order after it, without an extra separator.
                // Changing that suffix must not invalidate the stable instruction prefix.
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
                            // Preserve the previous whole-system cache boundary too.
                            put("cache_control", buildJsonObject { put("type", "ephemeral") })
                        })
                    }
                })
            } else {
                put("content", systemText)
            }
        }
    }

    /** Anthropic and its documented OpenRouter route allow four request breakpoints.
     * Count conservatively even unknown nested cache_control keys, so imported history
     * or tool metadata cannot make this optimization exceed the request-wide budget.
     * Iterative traversal avoids adding a recursive walk to request preparation.
     */
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
        else -> null // Unknown call sites keep their existing single-block behavior.
    }

    // Retain the existing explicit-provider allowlist. A routed model name alone
    // does not authorize metadata on arbitrary/custom OpenAI-compatible endpoints.
    // OpenRouter explicitly supports Anthropic cache_control on Chat Completions;
    // native Anthropic transport is not a requirement for that known proxy route.
    internal fun supportsExplicitCacheControl(providerId: String, model: String): Boolean {
        if (providerId.equals("anthropic", ignoreCase = true)) return true
        if (!providerId.equals("openrouter", ignoreCase = true)) return false
        return model.trim().lowercase().startsWith("anthropic/")
    }
}
