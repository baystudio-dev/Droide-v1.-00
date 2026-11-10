package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProviderRetainedStreamBudgetTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun message(body: String) = json.parseToJsonElement(body).jsonObject["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
    private fun padded(event: String): String = JsonObject(json.parseToJsonElement(event).jsonObject +
        ("discarded_metadata" to JsonPrimitive("m".repeat(300)))).toString()
    private fun exercise(payload: String, accept: (String) -> Any) {
        assertTrue(payload.length.toLong() * 10_000 > 2_000_000)
        repeat(10_000) { accept(payload) }
    }

    @Test fun chatFragmentationDoesNotConsumeWireLifetimeBudget() {
        val a = OpenAiChatStreamAccumulator(json)
        exercise(padded("""{"choices":[{"delta":{"content":"x"}}]}"""), a::accept)
        a.accept("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""")
        assertEquals("x".repeat(10_000), message(a.completedResponse())["content"]!!.jsonPrimitive.content)
    }

    @Test fun anthropicFragmentationDoesNotConsumeWireLifetimeBudget() {
        val a = AnthropicMessagesAdapter.StreamAccumulator(json)
        exercise(padded("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"x"}}"""), a::accept)
        a.accept("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""")
        assertEquals("x".repeat(10_000), message(a.completedResponse())["content"]!!.jsonPrimitive.content)
    }

    @Test fun responsesFragmentationDoesNotConsumeWireLifetimeBudget() {
        val a = OpenAiResponsesAdapter.StreamAccumulator(json)
        exercise(padded("""{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"x"}"""), a::accept)
        val output = buildJsonArray { add(buildJsonObject {
            put("type", "message"); put("role", "assistant"); put("status", "completed")
            put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", "x".repeat(10_000)) }) })
        }) }
        a.accept(buildJsonObject {
            put("type", "response.completed"); put("response", buildJsonObject {
                put("id", "r"); put("model", "gpt-5"); put("status", "completed"); put("output", output)
            })
        }.toString())
        assertEquals("x".repeat(10_000), message(a.completedResponse())["content"]!!.jsonPrimitive.content)
    }

    @Test fun googlePlainTokenFragmentsAreCoalescedWithoutDroppingSignedParts() {
        val a = GoogleGenerateContentAdapter.StreamAccumulator(json)
        val payload = padded("""{"candidates":[{"content":{"parts":[{"text":"x"}]}}]}""")

        repeat(40_000) { a.accept(payload) }
        a.accept("""{"candidates":[{"content":{"parts":[{"text":"signed","thoughtSignature":"sig"},{"functionCall":{"id":"c","name":"test","args":{}}}]},"finishReason":"STOP"}]}""")
        val result = message(a.completedResponse())
        assertEquals("x".repeat(40_000) + "signed", result["content"]!!.jsonPrimitive.content)
        val parts = result[GoogleGenerateContentAdapter.PRIVATE_CONTENT_FIELD]!!.jsonArray
        assertEquals(3, parts.size)
        assertEquals("x".repeat(40_000), parts[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("sig", parts[1].jsonObject["thoughtSignature"]!!.jsonPrimitive.content)
    }

    @Test fun repeatedMetadataSnapshotsAreReplacementsNotLifetimeAccumulation() {
        val a = OpenAiChatStreamAccumulator(json)
        val payload = buildJsonObject { put("model", "m".repeat(100_000)); put("choices", buildJsonArray {}) }.toString()
        repeat(100) { a.accept(payload) }
        assertEquals(100_000, json.parseToJsonElement(a.completedResponse()).jsonObject["model"]!!.jsonPrimitive.content.length)
    }

    @Test fun oversizedRetainedTextFailsInsteadOfUnboundedGrowth() {
        val a = OpenAiChatStreamAccumulator(json)
        val payload = """{"choices":[{"delta":{"content":"${"x".repeat(100_000)}"}}]}"""
        assertThrows(IllegalStateException::class.java) { repeat(100) { a.accept(payload) } }
    }

    @Test fun emptyToolStructuresCannotGrowWithoutBound() {
        val a = OpenAiChatStreamAccumulator(json)
        assertThrows(IllegalStateException::class.java) {
            repeat(40_000) { a.accept("""{"choices":[{"delta":{"tool_calls":[{"index":$it}]}}]}""") }
        }
    }
}
