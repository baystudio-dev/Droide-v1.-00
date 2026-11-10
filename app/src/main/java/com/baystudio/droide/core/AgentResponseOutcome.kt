package com.baystudio.droide.core

import kotlinx.serialization.json.*

internal object AgentResponseOutcome {
    internal enum class Kind { COMPLETE, TOOL_TURN, EMPTY, OUTPUT_LIMIT, CONTEXT_LIMIT, PAUSED, BLOCKED, INVALID }
    internal data class Decision(val kind: Kind, val publicText: String, val diagnostic: String) {
        val accepted: Boolean get() = kind == Kind.COMPLETE || kind == Kind.TOOL_TURN
    }
    internal class Failure(message: String) : IllegalStateException(message)

    fun evaluate(choice: JsonObject): Decision {
        val message = choice["message"] as? JsonObject
            ?: return Decision(Kind.INVALID, "", "Provider response has no valid assistant message. No automatic retry was sent.")
        val raw = message["content"]
        val content = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val refusalRaw = message["refusal"]
        val refusal = (refusalRaw as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        // Adapters project public deltas in their original order. Refusal metadata can contain
        // noncontiguous fragments, so never append it a second time.
        val publicText = content.ifBlank { refusal }
        val callsRaw = message["tool_calls"]
        val calls = callsRaw as? JsonArray
        val finish = (choice["finish_reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val native = (choice["_droide_google_finish_reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val anthropic = (choice["_droide_anthropic_stop_reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val reason = listOf(finish, native, anthropic).filter(String::isNotBlank).distinct()
            .joinToString(" / ") { if (it.matches(Regex("[A-Za-z0-9_:-]{1,80}"))) it else "unknown" }
            .ifBlank { "missing" }
        if (raw != null && raw != JsonNull && (raw !is JsonPrimitive || !raw.isString) ||
            callsRaw != null && callsRaw != JsonNull && calls == null ||
            refusalRaw != null && refusalRaw != JsonNull && (refusalRaw !is JsonPrimitive || !refusalRaw.isString)) {
            return Decision(Kind.INVALID, publicText, "Provider returned malformed assistant content or tool data (finish: $reason). No tools or automatic retry were started.")
        }
        if (native.isNotBlank() && anthropic.isNotBlank()) return Decision(Kind.INVALID, publicText,
            "Provider response contains contradictory protocol metadata. No tools or automatic retry were started.")
        if (anthropic == "model_context_window_exceeded") return Decision(Kind.CONTEXT_LIMIT, publicText,
            "Provider stopped at its context-window limit (finish: $reason). Any public partial text was preserved. Review or compact the conversation before continuing; no automatic retry was sent.")
        if (anthropic == "pause_turn") return Decision(Kind.PAUSED, publicText,
            "Provider paused its server-side turn (finish: $reason). Any public partial text was preserved. This is not a completed answer or client-tool request; no automatic continuation was sent.")
        if (anthropic == "refusal") return Decision(Kind.BLOCKED, publicText,
            "Provider declined this response (finish: $reason). Its public explanation was preserved; no automatic retry was sent.")
        if (anthropic.isNotBlank() && anthropic !in setOf("end_turn", "stop_sequence", "tool_use", "max_tokens")) return Decision(Kind.INVALID, publicText,
            "Provider reported an unsupported terminal outcome (finish: $reason). No incomplete tools or automatic retry were started.")
        // Native metadata is retained only by native adapters. Contradictory canonical success must
        // never admit an explicitly truncated, blocked or malformed turn.
        if (finish == "length" || native == "MAX_TOKENS" || anthropic == "max_tokens") return Decision(Kind.OUTPUT_LIMIT, publicText,
            "Provider reached its output limit before a complete answer (finish: $reason). Any visible partial text was preserved. Check the model's output/reasoning settings before continuing; no automatic retry was sent.")
        if (native in setOf("MALFORMED_FUNCTION_CALL", "UNEXPECTED_TOOL_CALL", "TOO_MANY_TOOL_CALLS", "MISSING_THOUGHT_SIGNATURE", "MALFORMED_RESPONSE", "UNKNOWN", "OTHER", "FINISH_REASON_UNSPECIFIED", "CONTINUATION", "ESCALATION", "PUP_LIMITED_DISABLED")) return Decision(Kind.INVALID, publicText,
            "Provider could not complete a valid response (finish: $reason). No incomplete tools or automatic retry were started.")
        if (finish == "content_filter" || native.startsWith("PROMPT_BLOCK:") ||
            native in setOf("SAFETY", "RECITATION", "LANGUAGE", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY", "IMAGE_PROHIBITED_CONTENT", "IMAGE_OTHER", "NO_IMAGE", "IMAGE_RECITATION") || refusal.isNotBlank()) return Decision(Kind.BLOCKED, publicText,
            "Provider stopped or blocked this response (finish: $reason). Review the provider reason and request before continuing; no automatic retry was sent.")
        if (native.isNotBlank() && native != "STOP") return Decision(Kind.INVALID, publicText,
            "Provider reported an unsupported terminal outcome (finish: $reason). No incomplete tools or automatic retry were started.")
        if (anthropic in setOf("end_turn", "stop_sequence") && !calls.isNullOrEmpty() ||
            anthropic == "tool_use" && (calls.isNullOrEmpty() || finish != "tool_calls")) return Decision(Kind.INVALID, publicText,
            "Provider returned an inconsistent native tool turn (finish: $reason). No tools or automatic retry were started.")
        if (!calls.isNullOrEmpty()) return if (finish in setOf("tool_calls", "function_call")) {
            Decision(Kind.TOOL_TURN, content, "")
        } else Decision(Kind.INVALID, publicText, "Provider returned tools without a completed tool turn (finish: $reason). No tools or automatic retry were started.")
        if (finish != "stop") return Decision(Kind.INVALID, publicText,
            "Provider did not complete a usable answer (finish: $reason). No incomplete tools or automatic retry were started.")
        if (content.isBlank()) return Decision(Kind.EMPTY, "",
            "Provider completed the request without public answer text or a tool call (finish: $reason). This is not a completed answer. Review the provider/model settings before retrying; no automatic retry was sent.")
        return Decision(Kind.COMPLETE, content, "")
    }

    /** Repair only the exact old empty assistant shell in the outgoing projection. */

    fun providerHistory(history: List<JsonObject>): List<JsonObject> = history.filterNot { message ->
        message.keys == setOf("role", "content") && message["role"] == JsonPrimitive("assistant") &&
            (message["content"] == JsonNull || (message["content"] as? JsonPrimitive)?.let { it.isString && it.content.isBlank() } == true)
    }

    fun requestHistory(request: JsonObject): JsonObject {
        val messages = request["messages"] as? JsonArray ?: return request
        // Keep malformed records for the normal strict request validator, never hide them.
        if (messages.any { it !is JsonObject }) return request
        val projected = providerHistory(messages.map { it as JsonObject })
        return if (projected.size == messages.size) request else JsonObject(request + ("messages" to JsonArray(projected)))
    }
}
