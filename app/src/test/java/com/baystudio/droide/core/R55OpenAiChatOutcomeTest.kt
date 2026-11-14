package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Exercises the actual production Chat accumulator without sockets, credentials or model calls. */
class R55OpenAiChatOutcomeTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun obj(value: String) = json.parseToJsonElement(value).jsonObject
    private fun choice(body: String) = obj(body).getValue("choices").jsonArray.first().jsonObject
    private fun message(body: String) = choice(body).getValue("message").jsonObject
    private fun complete(acc: OpenAiChatStreamAccumulator) = ProviderResponseCompletion.complete(acc.completedResponse(), json)
    private fun decision(body: String) = AgentResponseOutcome.evaluate(choice(body))
    private fun chunk(delta: JsonObject = obj("{}"), finish: String? = null, fields: Map<String, JsonElement> = emptyMap()): String =
        buildJsonObject {
            put("choices", buildJsonArray { add(buildJsonObject {
                put("index", 0); put("delta", delta); finish?.let { put("finish_reason", it) }
                fields.forEach { (key, value) -> put(key, value) }
            }) })
        }.toString()
    private fun text(events: List<LlmStreamEvent>) = events.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString("") { it.text }
    private val call = obj("""{"index":0,"id":"call_1","type":"function","function":{"name":"read_file","arguments":"{}"}}""")

    @Test fun refusalFragmentsArePublicExactlyOnceAndRetainedSeparately() {
        val acc = OpenAiChatStreamAccumulator(json)
        val events = acc.accept(chunk(obj("""{"content":null,"refusal":"I cannot "}"""))) +
            acc.accept(chunk(obj("""{"refusal":"assist."}"""))) + acc.accept(chunk(finish = "stop"))
        val body = complete(acc)
        assertEquals("I cannot assist.", text(events))
        assertEquals(JsonPrimitive("I cannot assist."), message(body)["content"])
        assertEquals(JsonPrimitive("I cannot assist."), message(body)["refusal"])
        assertEquals(AgentResponseOutcome.Kind.BLOCKED, decision(body).kind)
        assertEquals("I cannot assist.", decision(body).publicText)
    }

    @Test fun contentAndRefusalRemainDistinctWithoutExposingHiddenReasoning() {
        val acc = OpenAiChatStreamAccumulator(json)
        val events = acc.accept(chunk(obj("""{
            "content":"Public preface. ","refusal":"Cannot continue.",
            "reasoning":"PRIVATE REASONING","reasoning_content":"PRIVATE THOUGHT",
            "_droide_openai_responses_output":[{"encrypted_content":"PRIVATE CIPHERTEXT"}]
        }"""), "stop"))
        val body = complete(acc)
        assertEquals("Public preface. Cannot continue.", text(events))
        assertEquals(JsonPrimitive("Public preface. Cannot continue."), message(body)["content"])
        assertEquals(JsonPrimitive("Cannot continue."), message(body)["refusal"])
        assertFalse(body.contains("PRIVATE"))
        assertFalse(body.contains("_droide_"))
        assertEquals(AgentResponseOutcome.Kind.BLOCKED, decision(body).kind)
    }

    @Test fun mixedContentRefusalContentRetainsExactPublicStreamOrder() {
        val acc = OpenAiChatStreamAccumulator(json)
        val events = acc.accept(chunk(obj("""{"content":"Public preface. "}"""))) +
            acc.accept(chunk(obj("""{"refusal":"Cannot continue. "}"""))) +
            acc.accept(chunk(obj("""{"content":"Try a safer request."}"""), "stop"))
        val body = complete(acc)
        val expected = "Public preface. Cannot continue. Try a safer request."
        assertEquals(expected, text(events))
        assertEquals(JsonPrimitive(expected), message(body)["content"])
        assertEquals(JsonPrimitive("Cannot continue. "), message(body)["refusal"])
        assertEquals(expected, decision(body).publicText)
        assertEquals(AgentResponseOutcome.Kind.BLOCKED, decision(body).kind)
    }

    @Test fun bufferedAndStreamedRefusalHaveTheSamePublicOutcome() {
        for (finish in listOf("stop", "content_filter")) {
            val acc = OpenAiChatStreamAccumulator(json)
            acc.accept(chunk(obj("""{"refusal":"Cannot provide that."}"""), finish))
            val buffered = ProviderResponseCompletion.complete("""{"choices":[{
                "message":{"role":"assistant","content":null,"refusal":"Cannot provide that."},
                "finish_reason":"$finish"}]}""", json)
            assertEquals(decision(buffered), decision(complete(acc)))
        }
    }

    @Test fun malformedRefusalTypesFailWithoutStringifyingPrivatePayloads() {
        for (invalid in listOf(JsonPrimitive(7), JsonPrimitive(true), obj("""{"private":"PRIVATE"}"""), JsonArray(listOf(JsonPrimitive("PRIVATE"))))) {
            val acc = OpenAiChatStreamAccumulator(json)
            val error = assertThrows(IllegalStateException::class.java) {
                acc.accept(chunk(buildJsonObject { put("content", "Public"); put("refusal", invalid) }, "stop"))
            }
            assertEquals("Provider returned malformed refusal text", error.message)
            assertFalse(error.message.orEmpty().contains("PRIVATE"))
            assertEquals(JsonPrimitive(""), message(acc.completedResponse())["content"])
        }
    }

    @Test fun nullAndEmptyRefusalDoNotBlockAnOrdinaryAnswer() {
        for (refusal in listOf(JsonNull, JsonPrimitive(""))) {
            val acc = OpenAiChatStreamAccumulator(json)
            val events = acc.accept(chunk(buildJsonObject { put("content", "Done."); put("refusal", refusal) }, "stop"))
            assertEquals("Done.", text(events))
            assertFalse("refusal" in message(complete(acc)))
            assertEquals(AgentResponseOutcome.Kind.COMPLETE, decision(complete(acc)).kind)
        }
    }

    @Test fun choiceErrorAfterTextRejectsEvenAContradictorySuccessReason() {
        for (finish in listOf("error", "stop", "tool_calls")) {
            val acc = OpenAiChatStreamAccumulator(json)
            val earlier = acc.accept(chunk(obj("""{"content":"Useful partial."}""")))
            val error = assertThrows(ProviderErrorPayloadException::class.java) {
                acc.accept(chunk(obj("""{"content":"Do not admit this chunk"}"""), finish,
                    mapOf("error" to obj("""{"code":502,"message":"Provider interrupted","output":"PRIVATE"}"""))))
            }
            assertEquals("Useful partial.", text(earlier))
            assertEquals(JsonPrimitive("Useful partial."), message(acc.completedResponse())["content"])
            assertEquals(JsonPrimitive(502), error.payload.jsonObject["code"])
            assertFalse(error.payload.toString().contains("PRIVATE"))
        }
    }

    @Test fun rootErrorAfterTextRejectsWithoutAdmittingLaterTools() {
        val acc = OpenAiChatStreamAccumulator(json)
        val earlier = acc.accept(chunk(obj("""{"content":"Useful partial."}""")))
        val errorChunk = JsonObject(obj(chunk(buildJsonObject { put("tool_calls", JsonArray(listOf(call))) }, "tool_calls")) +
            ("error" to obj("""{"type":"overloaded_error","message":"Busy"}""")))
        assertThrows(ProviderErrorPayloadException::class.java) { acc.accept(errorChunk.toString()) }
        assertEquals("Useful partial.", text(earlier))
        assertFalse("tool_calls" in message(acc.completedResponse()))
    }

    @Test fun errorMarkersNeedNotContainADeltaOrFinishReason() {
        val acc = OpenAiChatStreamAccumulator(json)
        assertThrows(ProviderErrorPayloadException::class.java) {
            acc.accept("""{"choices":[{"error":{"code":429,"message":"Rate limited"}}]}""")
        }
    }

    @Test fun nullErrorsAreNotFailures() {
        val acc = OpenAiChatStreamAccumulator(json)
        acc.accept("""{"error":null,"choices":[{"error":null,"delta":{"content":"Done"},"finish_reason":"stop"}]}""")
        assertEquals(AgentResponseOutcome.Kind.COMPLETE, decision(complete(acc)).kind)
    }

    @Test fun completedToolOnlyTurnPreservesIdentityAndArguments() {
        val acc = OpenAiChatStreamAccumulator(json)
        val events = acc.accept(chunk(buildJsonObject { put("tool_calls", JsonArray(listOf(call))) }, "tool_calls"))
        val body = complete(acc)
        assertEquals(1, events.filterIsInstance<LlmStreamEvent.ToolCallDelta>().size)
        assertEquals(JsonArray(listOf(call)), message(body)["tool_calls"])
        assertEquals(AgentResponseOutcome.Kind.TOOL_TURN, decision(body).kind)
    }

    @Test fun refusalCannotAuthorizeOtherwiseCompletedTools() {
        val acc = OpenAiChatStreamAccumulator(json)
        acc.accept(chunk(buildJsonObject { put("refusal", "Cannot proceed."); put("tool_calls", JsonArray(listOf(call))) }, "tool_calls"))
        assertEquals(AgentResponseOutcome.Kind.BLOCKED, decision(complete(acc)).kind)
        assertFalse(decision(complete(acc)).accepted)
    }

    @Test fun usageOnlyTailPreservesTheFinishReasonAndLatestUsage() {
        val acc = OpenAiChatStreamAccumulator(json)
        acc.accept(chunk(obj("""{"content":"Done"}"""), "stop"))
        val usage = obj("""{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10,"completion_tokens_details":{"reasoning_tokens":2}}""")
        assertTrue(acc.accept(buildJsonObject { put("choices", JsonArray(emptyList())); put("usage", usage) }.toString()).isEmpty())
        assertTrue(acc.accept("""{"choices":[],"usage":null}""").isEmpty())
        val body = complete(acc)
        assertEquals(usage, obj(body)["usage"])
        assertEquals(JsonPrimitive("stop"), choice(body)["finish_reason"])
        assertEquals(AgentResponseOutcome.Kind.COMPLETE, decision(body).kind)
    }

    @Test fun unknownFinishCannotCertifyTextOrTools() {
        for (finish in listOf("error", "unexpected_vendor_stop")) {
            val acc = OpenAiChatStreamAccumulator(json)
            acc.accept(chunk(buildJsonObject { put("content", "Partial"); put("tool_calls", JsonArray(listOf(call))) }, finish))
            val body = complete(acc)
            assertEquals(JsonPrimitive(finish), choice(body)["finish_reason"])
            assertEquals("Partial", decision(body).publicText)
            assertEquals(AgentResponseOutcome.Kind.INVALID, decision(body).kind)
            assertFalse("tool_calls" in message(body))
        }
    }

    @Test fun missingFinishCannotBeCertifiedByAUsageOnlyTail() {
        val acc = OpenAiChatStreamAccumulator(json)
        acc.accept(chunk(obj("""{"content":"Partial"}""")))
        acc.accept("""{"choices":[],"usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}""")
        assertThrows(IllegalStateException::class.java) { complete(acc) }
        assertEquals(JsonPrimitive("Partial"), message(acc.completedResponse())["content"])
    }

    @Test fun publicNativeMetadataRemainsNonAuthoritativeAndPrivateMetadataIsDropped() {
        val acc = OpenAiChatStreamAccumulator(json)
        acc.accept(chunk(obj("""{"content":"Done"}"""), "stop", mapOf(
            "native_finish_reason" to JsonPrimitive("MAX_TOKENS"),
            "_droide_google_finish_reason" to JsonPrimitive("MAX_TOKENS"),
        )))
        val body = complete(acc)
        assertEquals(JsonPrimitive("MAX_TOKENS"), choice(body)["native_finish_reason"])
        assertFalse(body.contains("_droide_"))
        assertEquals(AgentResponseOutcome.Kind.COMPLETE, decision(body).kind)
    }
}
