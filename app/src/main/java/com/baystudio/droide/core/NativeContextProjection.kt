package com.baystudio.droide.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Accounting-only view; never mutates or replaces persisted/transported history. */
internal object NativeContextProjection {
    fun forAccounting(message: JsonObject, transport: ProviderTransportSpec?): JsonObject {
        if (message["role"] != JsonPrimitive("assistant") || transport == null) return message
        // No provider-name or model-name inference. Native signatures stay in the
        // selected private field, and unrelated fields are conservatively counted.
        val native = try {
            when (transport.protocol) {
                ProviderWireProtocol.ANTHROPIC_MESSAGES -> AnthropicMessagesAdapter.validatedNativeContent(message, Json)
                ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> GoogleGenerateContentAdapter.validatedNativeContent(message, Json)
                ProviderWireProtocol.OPENAI_RESPONSES -> OpenAiResponsesAdapter.validatedNativeContent(message, Json)
                ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> null
            }
        } catch (_: IllegalArgumentException) {
            // Invalid native data cannot earn a lower estimate. Real encoding still
            // rejects it according to its own adapter contract; no repair is applied.
            return message
        } catch (_: IllegalStateException) {
            return message
        }
        return if (native == null) message else JsonObject(message - "content" - "tool_calls")
    }
}
