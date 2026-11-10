package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Offline wire-family coverage, not certification of every provider's live endpoint. */
class ProviderUsageProtocolMatrixTest {
    private val json = Json { ignoreUnknownKeys = true }
    private enum class Wire { CHAT, RESPONSES, ANTHROPIC, GEMINI }
    private fun obj(value: String) = json.parseToJsonElement(value).jsonObject
    private fun sample(body: String) = TokenTracker().add("offline-fixture", "unused request", body)

    private fun normalize(wire: Wire, streaming: Boolean, reported: JsonObject?): String {
        fun response(base: String, usageField: String) = JsonObject(obj(base) +
            if (reported == null) emptyMap() else mapOf(usageField to reported))
        return when (wire) {
            Wire.CHAT -> if (streaming) {
                val stream = LlmClient.StreamAccumulator(json)
                stream.accept("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""")
                reported?.let { stream.accept(buildJsonObject { put("choices", JsonArray(emptyList())); put("usage", it) }.toString()) }
                stream.completedResponse()
            } else {
                val body = response("""{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""", "usage").toString()
                // Exercise the real buffered admission path without creating an HTTP Call.
                LlmClient::class.java.getDeclaredMethod("checkedProviderBody", String::class.java)
                    .apply { isAccessible = true }.invoke(LlmClient(), body) as String
            }
            Wire.RESPONSES -> {
                val body = response("""{"id":"offline-response","status":"completed","output":[]}""", "usage")
                if (streaming) {
                    val stream = OpenAiResponsesAdapter.StreamAccumulator(json)
                    stream.accept("""{"type":"response.created"}""")
                    stream.accept(buildJsonObject { put("type", "response.completed"); put("response", body) }.toString())
                    stream.completedResponse()
                } else OpenAiResponsesAdapter.normalizeBuffered(body.toString(), json)
            }
            Wire.ANTHROPIC -> if (streaming) {
                val stream = AnthropicMessagesAdapter.StreamAccumulator(json)
                stream.accept(buildJsonObject {
                    put("type", "message_start")
                    put("message", buildJsonObject { reported?.let { put("usage", it) } })
                }.toString())
                stream.accept(buildJsonObject {
                    put("type", "message_delta"); put("delta", buildJsonObject { put("stop_reason", "end_turn") })
                    reported?.get("output_tokens")?.let { output -> put("usage", buildJsonObject { put("output_tokens", output) }) }
                }.toString())
                stream.completedResponse()
            } else AnthropicMessagesAdapter.normalizeBuffered(response(
                """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}""", "usage",
            ).toString(), json)
            Wire.GEMINI -> {
                val base = """{"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}]}"""
                if (streaming) {
                    val stream = GoogleGenerateContentAdapter.StreamAccumulator(json)
                    stream.accept(base)
                    reported?.let { stream.accept(buildJsonObject { put("usageMetadata", it) }.toString()) }
                    stream.completedResponse()
                } else GoogleGenerateContentAdapter.normalizeBuffered(response(base, "usageMetadata").toString(), json)
            }
        }
    }

    private fun complete(wire: Wire): JsonObject = obj(when (wire) {
        Wire.CHAT -> """{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150,"prompt_tokens_details":{"cached_tokens":40},"completion_tokens_details":{"reasoning_tokens":30}}"""
        Wire.RESPONSES -> """{"input_tokens":100,"output_tokens":50,"total_tokens":150,"input_tokens_details":{"cached_tokens":40},"output_tokens_details":{"reasoning_tokens":30}}"""
        Wire.ANTHROPIC -> """{"input_tokens":40,"output_tokens":50,"cache_read_input_tokens":40,"cache_creation_input_tokens":20}"""
        Wire.GEMINI -> """{"promptTokenCount":90,"toolUsePromptTokenCount":10,"candidatesTokenCount":20,"thoughtsTokenCount":30,"cachedContentTokenCount":40,"totalTokenCount":150}"""
    })

    @Test fun everyWireFamilyBufferedAndStreamingReachesTrackerWithInclusiveCategories() {
        for (wire in Wire.entries) for (streaming in listOf(false, true)) {
            val value = sample(normalize(wire, streaming, complete(wire)))
            val label = "$wire streaming=$streaming"
            assertEquals(label, 100, value.prompt)
            assertEquals(label, 50, value.completion)
            assertEquals(label, 150, value.total)
            assertEquals(label, 40, value.cacheRead)
            assertEquals(label, if (wire == Wire.ANTHROPIC) 20 else 0, value.cacheWrite)
            assertEquals(label, if (wire == Wire.ANTHROPIC) 0 else 30, value.reasoning)
            assertEquals(label, UsageAuthority.AUTHORITATIVE, value.tokenAuthority)
            assertEquals(label, UsageAuthority.UNKNOWN, value.costAuthority)
            assertEquals(label, 1L, value.requestCount)
            assertTrue(label, value.breakdownComplete)
            assertFalse(label, value.breakdownMismatch)
        }
    }

    @Test fun everyWireFamilyMissingUsageRemainsUnknownWithoutTextLengthEstimates() {
        for (wire in Wire.entries) for (streaming in listOf(false, true)) {
            val value = sample(normalize(wire, streaming, null))
            val label = "$wire streaming=$streaming"
            assertEquals(label, 0, value.total)
            assertEquals(label, UsageAuthority.UNKNOWN, value.tokenAuthority)
            assertEquals(label, UsageAuthority.UNKNOWN, value.costAuthority)
            assertFalse(label, value.breakdownComplete)
            assertEquals(label, 1L, value.requestCount)
        }
    }

    @Test fun supportedTotalOnlySchemasPreserveTotalWhileBreakdownIsUnknown() {

        for (wire in listOf(Wire.CHAT, Wire.RESPONSES, Wire.GEMINI)) for (streaming in listOf(false, true)) {
            val nativeTotal = if (wire == Wire.GEMINI) "totalTokenCount" else "total_tokens"
            val value = sample(normalize(wire, streaming, buildJsonObject { put(nativeTotal, 321) }))
            val label = "$wire streaming=$streaming"
            assertEquals(label, 321, value.total)
            assertEquals(label, UsageAuthority.AUTHORITATIVE, value.tokenAuthority)
            assertEquals(label, 0, value.prompt)
            assertEquals(label, 0, value.completion)
            assertFalse(label, value.breakdownComplete)
        }
    }

    @Test fun chatAndResponsesDoNotAddReasoningOrCacheSubsetsToTotalAgain() {
        for (wire in listOf(Wire.CHAT, Wire.RESPONSES)) for (streaming in listOf(false, true)) {
            val value = sample(normalize(wire, streaming, complete(wire)))
            assertEquals(150, value.total)
            assertNotEquals(150 + value.cacheRead + value.reasoning, value.total)
        }
    }

    @Test fun openRouterCompatibleCostIsAccountChargeWithoutAddingUpstreamInferenceCost() {
        val reported = JsonObject(complete(Wire.CHAT) + mapOf(
            "cost" to JsonPrimitive(0.25),
            "cost_details" to obj("""{"upstream_inference_cost":0.20}"""),
        ))
        for (streaming in listOf(false, true)) {
            val value = sample(normalize(Wire.CHAT, streaming, reported))
            assertEquals(0.25, value.costUsd, 0.0)
            assertEquals(UsageAuthority.AUTHORITATIVE, value.costAuthority)
            assertEquals(150, value.total)
        }
    }

    @Test fun compatibleExplicitZeroCostAndMissingCostStayDistinct() {
        for (streaming in listOf(false, true)) {
            val missing = sample(normalize(Wire.CHAT, streaming, complete(Wire.CHAT)))
            val zero = sample(normalize(Wire.CHAT, streaming, JsonObject(complete(Wire.CHAT) + ("cost" to JsonPrimitive(0.0)))))
            assertEquals(0.0, missing.costUsd, 0.0)
            assertEquals(0.0, zero.costUsd, 0.0)
            assertEquals(UsageAuthority.UNKNOWN, missing.costAuthority)
            assertEquals(UsageAuthority.AUTHORITATIVE, zero.costAuthority)
        }
    }

    @Test fun compatibleStreamKeepsOnlyLatestWholeRequestUsageIncludingUsageOnlyChunk() {
        val stream = LlmClient.StreamAccumulator(json)
        stream.accept("""{"choices":[{"delta":{"content":"o"},"finish_reason":null}],"usage":{"prompt_tokens":100,"completion_tokens":1,"total_tokens":101}}""")
        stream.accept("""{"choices":[{"delta":{"content":"k"},"finish_reason":"stop"}],"usage":null}""")
        stream.accept(buildJsonObject { put("choices", JsonArray(emptyList())); put("usage", complete(Wire.CHAT)) }.toString())
        val value = sample(stream.completedResponse())
        assertEquals(150, value.total)
        assertEquals(100, value.prompt)
        assertEquals(50, value.completion)
        assertEquals(1L, value.requestCount)
    }

    @Test fun allSuppliedTotalSchemasExposeMismatchWithoutOverwritingProviderTotal() {
        for (wire in listOf(Wire.CHAT, Wire.RESPONSES, Wire.GEMINI)) for (streaming in listOf(false, true)) {
            val field = if (wire == Wire.GEMINI) "totalTokenCount" else "total_tokens"
            val value = sample(normalize(wire, streaming, JsonObject(complete(wire) + (field to JsonPrimitive(999)))))
            assertEquals(999, value.total)
            assertTrue(value.breakdownMismatch)
            assertEquals(UsageAuthority.AUTHORITATIVE, value.tokenAuthority)
        }
    }
}
