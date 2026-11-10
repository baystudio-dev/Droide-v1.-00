package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProviderNestedArgumentsTest {
    private val arguments = "{\"nested\":" + "[".repeat(2000) + "0" + "]".repeat(2000) + "}"
    private fun call() = buildJsonObject {
        put("id", "call1"); put("type", "function")
        put("function", buildJsonObject { put("name", "read_file"); put("arguments", arguments) })
    }

    @Test fun responsesBufferedToolArgumentStringCannotBypassEnvelopeDepthGuard() {
        val response = buildJsonObject {
            put("id", "r1"); put("status", "completed"); put("model", "gpt-5")
            put("output", buildJsonArray { add(buildJsonObject {
                put("type", "function_call"); put("id", "item1"); put("call_id", "call1")
                put("name", "read_file"); put("arguments", arguments)
            }) })
        }.toString()
        assertSame(response, ProviderPayloadLimits.checkedJson(response))
        val failure = assertThrows(IllegalStateException::class.java) { OpenAiResponsesAdapter.normalizeBuffered(response, Json) }
        assertTrue(failure.message.orEmpty().contains("nesting"))
    }

    @Test fun plannerConvertsOverdeepArgumentsToAFailedCallWithoutDispatchAuthority() {
        val prepared = AgentToolTurnPlanner.prepare(JsonArray(listOf(call())), Json, 1, 128_000, "", 0).single()
        assertNotNull(prepared.argumentError)
        assertTrue(prepared.args.isEmpty())
        assertTrue(prepared.argumentError!!.contains("No tool or lifecycle hook was executed"))
    }

    @Test fun nativeRequestReplayChecksNestedArgumentStringsAcrossAllNativeAdapters() {
        fun body(model: String) = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "user"); put("content", "read file") })
                add(buildJsonObject { put("role", "assistant"); put("content", ""); put("tool_calls", JsonArray(listOf(call()))) })
                add(buildJsonObject { put("role", "tool"); put("tool_call_id", "call1"); put("content", "done") })
                add(buildJsonObject { put("role", "user"); put("content", "continue") })
            })
        }.toString()
        assertThrows(Exception::class.java) { OpenAiResponsesAdapter.encodeRequest(body("gpt-5"), false, Json) }
        assertThrows(Exception::class.java) { AnthropicMessagesAdapter.encodeRequest(body("claude-sonnet-4-5"), false, Json) }
        assertThrows(Exception::class.java) { GoogleGenerateContentAdapter.encodeRequest(body("gemini-2.5-flash"), Json) }
    }
}
