package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GoogleResponseOutcomeTest {
    private val nativeField = GoogleGenerateContentAdapter.NATIVE_FINISH_REASON_FIELD
    private fun choice(body: String) = Json.parseToJsonElement(body).jsonObject.getValue("choices").jsonArray[0].jsonObject
    private fun message(body: String) = choice(body).getValue("message").jsonObject
    private fun response(reason: String?, parts: JsonArray = JsonArray(emptyList())) = buildJsonObject {
        put("candidates", buildJsonArray { add(buildJsonObject {
            reason?.let { put("finishReason", it) }
            put("content", buildJsonObject { put("role", "model"); put("parts", parts) })
            put("finishMessage", "untrusted provider details must not be retained")
        }) })
    }.toString()

    private fun completeGoogle(body: String, reason: String): String = ProviderResponseCompletion.google(body, reason, Json)

    @Test fun bufferedTerminalEnumsPreserveTheirIdentityWithoutChangingCanonicalReasons() {
        for (reason in listOf("STOP", "MAX_TOKENS", "SAFETY", "MALFORMED_FUNCTION_CALL", "OTHER", "UNEXPECTED_TOOL_CALL")) {
            val normalized = GoogleGenerateContentAdapter.normalizeBuffered(response(reason), Json)
            val expected = when (reason) { "STOP" -> "stop"; "MAX_TOKENS" -> "length"; else -> "content_filter" }
            assertEquals(JsonPrimitive(reason), choice(normalized)[nativeField])
            assertEquals(JsonPrimitive(expected), choice(normalized)["finish_reason"])
            assertEquals(JsonPrimitive(""), message(normalized)["content"])
            assertFalse(normalized.contains("untrusted provider details"))
            val completed = completeGoogle(normalized, reason)
            assertEquals(JsonPrimitive(reason), choice(completed)[nativeField])
            assertEquals(JsonPrimitive(expected), choice(completed)["finish_reason"])
        }
    }

    @Test fun streamedReasonsSurviveTrailingUsageAndDoNotExposeThoughtText() {
        for (reason in listOf("STOP", "MAX_TOKENS", "SAFETY", "MALFORMED_FUNCTION_CALL", "OTHER")) {
            val accumulator = GoogleGenerateContentAdapter.StreamAccumulator(Json)
            val thought = Json.parseToJsonElement("""[{"text":"private reasoning fixture","thought":true}]""").jsonArray
            assertEquals(listOf(LlmStreamEvent.Thinking), accumulator.accept(response(null, thought)))
            accumulator.accept(response(reason))
            accumulator.accept("""{"usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":0,"thoughtsTokenCount":7,"totalTokenCount":10}}""")
            val normalized = accumulator.completedResponse()
            val completed = completeGoogle(normalized, reason)
            assertEquals(JsonPrimitive(reason), choice(completed)[nativeField])
            assertEquals(JsonPrimitive(""), message(completed)["content"])
            assertFalse(completed.contains("private reasoning fixture"))
            assertFalse("tool_calls" in message(completed))
            assertEquals(JsonPrimitive(10), Json.parseToJsonElement(completed).jsonObject.getValue("usage").jsonObject["total_tokens"])
        }
    }

    @Test fun promptBlockMetadataSurvivesBufferedAndStreamingNormalization() {
        for (block in listOf("SAFETY", "OTHER", "BLOCKLIST", "PROHIBITED_CONTENT", "IMAGE_SAFETY")) {
            val raw = buildJsonObject { put("promptFeedback", buildJsonObject { put("blockReason", block) }) }.toString()
            val accumulator = GoogleGenerateContentAdapter.StreamAccumulator(Json)
            accumulator.accept(raw)
            accumulator.accept("""{"usageMetadata":{"promptTokenCount":2,"candidatesTokenCount":0,"totalTokenCount":2}}""")
            val terminal = GoogleGenerateContentAdapter.completionReason(Json.parseToJsonElement(raw).jsonObject)
            assertEquals("PROMPT_BLOCK:$block", terminal)
            for (normalized in listOf(GoogleGenerateContentAdapter.normalizeBuffered(raw, Json), accumulator.completedResponse())) {
                assertEquals(JsonPrimitive("PROMPT_BLOCK:$block"), choice(normalized)[nativeField])
                // The normalizer's pre-existing canonical null remains unchanged; the stream transport owns the
                // prompt-block completion override.
                assertEquals(JsonNull, choice(normalized)["finish_reason"])
                val completed = completeGoogle(normalized, requireNotNull(terminal))
                assertEquals(JsonPrimitive("PROMPT_BLOCK:$block"), choice(completed)[nativeField])
                assertEquals(JsonPrimitive("content_filter"), choice(completed)["finish_reason"])
                assertFalse("tool_calls" in message(completed))
            }
        }
    }

    @Test fun missingTerminalMetadataRemainsAbsentInsteadOfInventingSuccess() {
        for (raw in listOf(response(null), "{}", """{"promptFeedback":{"blockReason":"BLOCK_REASON_UNSPECIFIED"}}""")) {
            assertNull(GoogleGenerateContentAdapter.completionReason(Json.parseToJsonElement(raw).jsonObject))
            val accumulator = GoogleGenerateContentAdapter.StreamAccumulator(Json)
            accumulator.accept(raw)
            for (normalized in listOf(GoogleGenerateContentAdapter.normalizeBuffered(raw, Json), accumulator.completedResponse())) {
                assertFalse(nativeField in choice(normalized))
                assertEquals(JsonNull, choice(normalized)["finish_reason"])
                assertThrows(IllegalStateException::class.java) { ProviderResponseCompletion.complete(normalized, Json) }
            }
        }
    }

    @Test fun nativeMetadataIsAllowlistedBoundedAndNeverIncludesProviderProse() {
        val privateMarker = "provider-secret-fixture-" + "x".repeat(1000)
        for (rawReason in listOf(privateMarker, "NEW_VENDOR_REASON", "SAFETY\n$privateMarker")) {
            val normalized = GoogleGenerateContentAdapter.normalizeBuffered(response(rawReason), Json)
            assertEquals(JsonPrimitive("UNKNOWN"), choice(normalized)[nativeField])
            val completed = completeGoogle(normalized, rawReason)
            assertEquals(JsonPrimitive("UNKNOWN"), choice(completed)[nativeField])
            assertFalse(completed.contains(privateMarker))
            assertEquals(JsonPrimitive("content_filter"), choice(completed)["finish_reason"])
            val rawBlock = buildJsonObject { put("promptFeedback", buildJsonObject { put("blockReason", rawReason) }) }.toString()
            val block = GoogleGenerateContentAdapter.normalizeBuffered(rawBlock, Json)
            assertEquals(JsonPrimitive("PROMPT_BLOCK:UNKNOWN"), choice(block)[nativeField])
            assertFalse(block.contains(privateMarker))
        }
        assertNull(GoogleGenerateContentAdapter.safeNativeCompletionReason(null))
        assertNull(GoogleGenerateContentAdapter.safeNativeCompletionReason(" "))
    }

    @Test fun toolAdmissionAndPrivateContinuationStillRequireStop() {
        val parts = Json.parseToJsonElement("""[{"functionCall":{"id":"call_1","name":"read_file","args":{"path":"README.md"}},"thoughtSignature":"signature-fixture"}]""").jsonArray
        for (reason in listOf("STOP", "MAX_TOKENS", "SAFETY", "MALFORMED_FUNCTION_CALL", "OTHER")) {
            val accumulator = GoogleGenerateContentAdapter.StreamAccumulator(Json)
            accumulator.accept(response(null, parts))
            accumulator.accept(response(reason))
            for (normalized in listOf(GoogleGenerateContentAdapter.normalizeBuffered(response(reason, parts), Json), accumulator.completedResponse())) {
                val completed = completeGoogle(normalized, reason)
                assertEquals(JsonPrimitive(reason), choice(completed)[nativeField])
                if (reason == "STOP") {
                    assertEquals(JsonPrimitive("tool_calls"), choice(completed)["finish_reason"])
                    assertEquals(1, message(completed).getValue("tool_calls").jsonArray.size)
                    assertEquals(parts, message(completed)[GoogleGenerateContentAdapter.PRIVATE_CONTENT_FIELD])
                } else {
                    assertFalse("tool_calls" in message(completed))
                    assertFalse(message(completed).keys.any { it.startsWith("_droide_") })
                }
            }
        }
    }

    @Test fun compatibleBufferedJsonCannotSupplyNativeDiagnosticProse() {
        val raw = """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop","_droide_google_finish_reason":"untrusted diagnostic"}]}"""
        val normalized = ProviderResponseCompletion.complete(raw, Json)
        assertFalse(nativeField in choice(normalized))
        assertEquals(JsonPrimitive("ok"), message(normalized)["content"])
    }

    @Test fun syntheticInputTokenCountOverflowWordingIsInputSpecificAndStatusScoped() {
        // Synthetic wording regression, not a captured production incident.
        val input = "The input token count (40000) exceeds the maximum number of tokens allowed (32768)."
        assertTrue(LlmHttpException(400, input).isContextOverflow())
        assertTrue(LlmHttpException(422, """{"error":{"message":"$input","status":"INVALID_ARGUMENT"}}""").isContextOverflow())
        for (status in listOf(401, 403, 429, 500)) assertFalse(LlmHttpException(status, input).isContextOverflow())
        for (unrelated in listOf(
            "The output token count (40000) exceeds the maximum number of tokens allowed (32768).",
            "maxOutputTokens (40000) exceeds the maximum number of tokens allowed (32768).",
            "The input token count is invalid.",
            "The input token count (40000) is within the maximum number of tokens allowed (50000).",
            "GenerateContentRequest.tools[0].function_declarations[0].parameters: missing items.",
        )) assertFalse(unrelated, LlmHttpException(400, unrelated).isContextOverflow())
    }
    @Test fun compatiblePublicNativeFinishReasonRemainsNonAuthoritative() {
        for (finish in listOf("stop", "tool_calls")) {
            val body = buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject {
                put("finish_reason", finish); put("native_finish_reason", finish)
                put("message", buildJsonObject {
                    put("role", "assistant"); put("content", if (finish == "stop") "A normal routed answer" else "")
                    if (finish == "tool_calls") put("tool_calls", Json.parseToJsonElement("""[{"id":"call","type":"function","function":{"name":"read_file","arguments":"{}"}}]"""))
                })
            }) }) }.toString()
            val completed = ProviderResponseCompletion.complete(body, Json)
            assertEquals(JsonPrimitive(finish), choice(completed)["native_finish_reason"])
            assertTrue(AgentResponseOutcome.evaluate(choice(completed)).accepted)
            assertFalse(nativeField in choice(completed))
        }
    }

}
