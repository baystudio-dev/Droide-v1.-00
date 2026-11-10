package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProviderBufferedCompletionTest {
    private fun choice(body: String) = Json.parseToJsonElement(body).jsonObject["choices"]!!.jsonArray[0].jsonObject

    @Test fun googleIncompleteOrFilteredFunctionCallsCannotBeExecutedOrReplayed() {
        for (finish in listOf("MAX_TOKENS", "SAFETY", "MALFORMED_FUNCTION_CALL", "OTHER", null)) {
            val response = buildJsonObject {
                put("candidates", buildJsonArray { add(buildJsonObject {
                    finish?.let { put("finishReason", it) }
                    put("content", buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject {
                        put("functionCall", buildJsonObject { put("name", "read_file"); put("args", buildJsonObject { put("path", "a") }) })
                    }) }) })
                }) })
            }
            val result = choice(GoogleGenerateContentAdapter.normalizeBuffered(response.toString(), Json))
            val msg = result["message"]!!.jsonObject
            assertFalse("tool_calls" in msg)
            assertFalse(GoogleGenerateContentAdapter.PRIVATE_CONTENT_FIELD in msg)
            assertNotEquals(JsonPrimitive("tool_calls"), result["finish_reason"])
        }
    }

    @Test fun anthropicIncompleteOrRefusedToolBlocksCannotBeExecutedOrReplayed() {
        for (finish in listOf("max_tokens", "refusal", "end_turn", null)) {
            val response = buildJsonObject {
                finish?.let { put("stop_reason", it) }
                put("content", buildJsonArray {
                    add(buildJsonObject { put("type", "thinking"); put("thinking", "plan"); put("signature", "sig") })
                    add(buildJsonObject { put("type", "tool_use"); put("id", "call1"); put("name", "read_file"); put("input", buildJsonObject { put("path", "a") }) })
                })
            }
            val result = choice(AnthropicMessagesAdapter.normalizeBuffered(response.toString(), Json))
            val msg = result["message"]!!.jsonObject
            assertFalse("tool_calls" in msg)
            assertFalse(AnthropicMessagesAdapter.PRIVATE_CONTENT_FIELD in msg)
            assertNotEquals(JsonPrimitive("tool_calls"), result["finish_reason"])
        }
    }
}
