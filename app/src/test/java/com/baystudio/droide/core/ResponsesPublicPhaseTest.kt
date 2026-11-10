package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Offline native Responses fixtures: public phase is metadata, never a text heuristic. */
class ResponsesPublicPhaseTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun obj(value: String) = json.parseToJsonElement(value).jsonObject
    private fun item(text: String, phase: JsonElement? = null, refusal: Boolean = false) = buildJsonObject {
        put("type", "message"); put("role", "assistant"); put("status", "completed")
        phase?.let { put("phase", it) }
        put("content", buildJsonArray { add(buildJsonObject {
            put("type", if (refusal) "refusal" else "output_text")
            put(if (refusal) "refusal" else "text", text)
        }) })
    }
    private val reasoning = obj("""{"type":"reasoning","id":"rs-1","summary":[{"type":"summary_text","text":"PRIVATE SUMMARY final_answer"}],"encrypted_content":"OPAQUE PRIVATE"}""")
    private val call = obj("""{"type":"function_call","id":"fc-1","call_id":"call-1","name":"read_file","arguments":"{\"path\":\"README.md\"}","status":"completed"}""")
    private fun response(output: JsonArray) = buildJsonObject {
        put("id", "resp-1"); put("status", "completed"); put("output", output)
        put("usage", obj("""{"input_tokens":13,"output_tokens":7,"total_tokens":20}"""))
    }
    private fun normalized(output: JsonArray) = obj(OpenAiResponsesAdapter.normalizeBuffered(response(output).toString(), json))
    private fun message(normalized: JsonObject) = normalized["choices"]!!.jsonArray.single().jsonObject["message"]!!.jsonObject
    private fun publicText(message: JsonObject) = message["content"]!!.jsonPrimitive.content
    private fun spans(message: JsonObject) = OpenAiResponsesAdapter.publicTextSpans(message, publicText(message))
    private fun event(type: String, index: Int, item: JsonObject) = buildJsonObject {
        put("type", type); put("output_index", index); put("item", item)
    }.toString()

    @Test fun nativeMessageBoundariesPreserveExactMultilingualPublicText() {
        val update = "Проверяю 🧭\n"
        val answer = "  結果: готово.\n"
        val output = JsonArray(listOf(reasoning, item(update, JsonPrimitive("commentary")), call,
            item(answer, JsonPrimitive("final_answer"))))
        val normalized = normalized(output)
        val message = message(normalized)
        assertEquals(update + answer, publicText(message))
        assertEquals(listOf(AgentPublicTextSpan(0, update.length, ProviderPublicTextPhase.COMMENTARY),
            AgentPublicTextSpan(update.length, update.length + answer.length, ProviderPublicTextPhase.FINAL_ANSWER)), spans(message))
        assertEquals(output, message[OpenAiResponsesAdapter.PRIVATE_CONTENT_FIELD])
        assertEquals(JsonPrimitive(20), normalized["usage"]!!.jsonObject["total_tokens"])
        assertFalse(publicText(message).contains("PRIVATE"))
        assertFalse(spans(message).toString().contains("OPAQUE"))
    }

    @Test fun multipleSamePhaseItemsKeepBoundariesWithoutInventedSeparators() {
        val message = message(normalized(JsonArray(listOf(item("one", JsonPrimitive("commentary")),
            item("two", JsonPrimitive("commentary")), item("", JsonPrimitive("final_answer"))))))
        assertEquals("onetwo", publicText(message))
        assertEquals(listOf(AgentPublicTextSpan(0, 3, ProviderPublicTextPhase.COMMENTARY),
            AgentPublicTextSpan(3, 6, ProviderPublicTextPhase.COMMENTARY)), spans(message))
    }

    @Test fun missingNullUnknownAndMalformedPhasesStayNeutralWithoutPhraseInference() {
        val phases = listOf(null, JsonNull, JsonPrimitive("analysis"), JsonPrimitive("FINAL_ANSWER"),
            JsonPrimitive(1), obj("""{"value":"final_answer"}"""))
        for (phase in phases) {
            val message = message(normalized(JsonArray(listOf(item("Final answer: I will check. Готово.", phase)))))
            assertEquals(listOf(AgentPublicTextSpan(0, publicText(message).length, ProviderPublicTextPhase.UNSPECIFIED)), spans(message))
        }
    }

    @Test fun genericTopLevelPhaseCannotCreateTrustedNativeSpans() {
        val generic = obj("""{"role":"assistant","content":"Finished","phase":"final_answer"}""")
        assertTrue(OpenAiResponsesAdapter.publicTextSpans(generic, "Finished").isEmpty())
    }

    @Test fun refusalsArePublicTextWithoutChangingBlockedOutcome() {
        val normalized = normalized(JsonArray(listOf(item("Не могу помочь.", JsonPrimitive("final_answer"), refusal = true))))
        val message = message(normalized)
        assertEquals("Не могу помочь.", publicText(message))
        assertEquals(JsonPrimitive("content_filter"), normalized["choices"]!!.jsonArray.single().jsonObject["finish_reason"])
        assertEquals(ProviderPublicTextPhase.FINAL_ANSWER, spans(message).single().phase)
    }

    @Test fun truncatedEditedOrMismatchedPublicProjectionHasNoClassification() {
        val message = message(normalized(JsonArray(listOf(item("exact text", JsonPrimitive("final_answer"))))))
        for (text in listOf("exact", "exact text\n", "Exact text", "")) {
            assertTrue(OpenAiResponsesAdapter.publicTextSpans(message, text).isEmpty())
            val edited = JsonObject(message + ("content" to JsonPrimitive(text)))
            assertTrue(OpenAiResponsesAdapter.publicTextSpans(edited, text).isEmpty())
        }
    }

    @Test fun malformedNativePresentationMetadataFallsBackWithoutThrowing() {
        val valid = item("text", JsonPrimitive("final_answer"))
        val outputs = listOf<JsonElement>(JsonNull, JsonPrimitive("not array"),
            JsonArray(listOf(JsonNull)), JsonArray(listOf(JsonObject(valid + ("role" to JsonPrimitive("user"))))),
            JsonArray(listOf(JsonObject(valid + ("content" to JsonPrimitive("text"))))),
            JsonArray(listOf(obj("""{"type":"message","role":"assistant","content":[{"type":"analysis","text":"text"}]}"""))),
            JsonArray(listOf(obj("""{"type":"message","role":"assistant","content":[{"type":"output_text","text":7}]}"""))),
            JsonArray(listOf(obj("""{"type":"unrecognized","content":"text"}"""))))
        for (output in outputs) {
            val message = buildJsonObject { put("role", "assistant"); put("content", "text"); put(OpenAiResponsesAdapter.PRIVATE_CONTENT_FIELD, output) }
            assertTrue(OpenAiResponsesAdapter.publicTextSpans(message, "text").isEmpty())
        }
    }

    @Test fun spanBudgetRejectsWholeOverBudgetProjectionRatherThanClippingText() {
        for (count in listOf(128, 129)) {
            val message = message(normalized(JsonArray(List(count) { item("x", JsonPrimitive("commentary")) })))
            assertEquals(count, publicText(message).length)
            assertEquals(if (count == 128) 128 else 0, spans(message).size)
        }
    }

    @Test fun utf16OffsetsNeverSplitSurrogatePairsAcrossMessageBoundaries() {
        val split = message(normalized(JsonArray(listOf(item("\uD83D", JsonPrimitive("commentary")),
            item("\uDE00", JsonPrimitive("final_answer"))))))
        assertEquals("😀", publicText(split))
        assertTrue(spans(split).isEmpty())
        val whole = message(normalized(JsonArray(listOf(item("😀", JsonPrimitive("final_answer"))))))
        assertEquals(listOf(AgentPublicTextSpan(0, 2, ProviderPublicTextPhase.FINAL_ANSWER)), spans(whole))
    }

    @Test fun nativeReplayPreservesOriginalPhasesAndPrivateContinuationExactly() {
        val output = JsonArray(listOf(reasoning, item("Checking", JsonPrimitive("commentary")), call,
            item("Result", JsonPrimitive("final_answer")), item("neutral", JsonNull), item("future", JsonPrimitive("future_phase"))))
        val message = message(normalized(output))
        val request = buildJsonObject {
            put("model", "gpt-5.4"); put("messages", buildJsonArray {
                add(obj("""{"role":"user","content":"Check"}""")); add(message)
                add(obj("""{"role":"tool","tool_call_id":"call-1","content":"README contents"}"""))
            })
        }
        val encoded = obj(OpenAiResponsesAdapter.encodeRequest(request.toString(), false, json))
        assertEquals(output.toList(), encoded["input"]!!.jsonArray.drop(1).dropLast(1))
        assertFalse(encoded["input"]!!.jsonArray.first().jsonObject.containsKey("phase"))
        val tampered = JsonObject(message + ("content" to JsonPrimitive("Changed")))
        val changedRequest = JsonObject(request + ("messages" to JsonArray(listOf(tampered))))
        assertThrows(IllegalArgumentException::class.java) { OpenAiResponsesAdapter.encodeRequest(changedRequest.toString(), false, json) }
    }

    @Test fun streamingUsesOnlyObservedItemPhasesAndMatchesBufferedPublicBoundaries() {
        val update = item("Checking ", JsonPrimitive("commentary"))
        val answer = item("done", JsonPrimitive("final_answer"))
        val output = JsonArray(listOf(reasoning, update, answer))
        val acc = OpenAiResponsesAdapter.StreamAccumulator(json)
        assertEquals(listOf(LlmStreamEvent.Thinking), acc.accept(event("response.output_item.added", 0, reasoning)))
        assertTrue(acc.accept("""{"type":"response.reasoning_summary_text.delta","output_index":0,"delta":"PRIVATE SUMMARY"}""").isEmpty())
        val events = mutableListOf<LlmStreamEvent>()
        events += acc.accept(event("response.output_item.added", 1, JsonObject(update + ("content" to JsonArray(emptyList())))))
        events += acc.accept("""{"type":"response.output_text.delta","output_index":1,"content_index":0,"delta":"Checking "}""")
        events += acc.accept(event("response.output_item.done", 1, update))
        events += acc.accept(event("response.output_item.added", 2, JsonObject(answer + ("content" to JsonArray(emptyList())))))
        events += acc.accept("""{"type":"response.output_text.done","output_index":2,"content_index":0,"text":"done"}""")
        events += acc.accept(buildJsonObject { put("type", "response.completed"); put("response", response(output)) }.toString())
        assertEquals(listOf(LlmStreamEvent.TextDelta("Checking ", ProviderPublicTextPhase.COMMENTARY),
            LlmStreamEvent.TextDelta("done", ProviderPublicTextPhase.FINAL_ANSWER)), events)
        assertEquals(normalized(output), obj(acc.completedResponse()))
        assertEquals(spans(message(normalized(output))), spans(message(obj(acc.completedResponse()))))
    }

    @Test fun lateItemMetadataDoesNotRetroactivelyGuessEarlierChunkPhase() {
        val acc = OpenAiResponsesAdapter.StreamAccumulator(json)
        assertEquals(listOf(LlmStreamEvent.TextDelta("Done")), acc.accept("""{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"Done"}"""))
        val output = JsonArray(listOf(item("Done", JsonPrimitive("final_answer"))))
        assertTrue(acc.accept(buildJsonObject { put("type", "response.completed"); put("response", response(output)) }.toString()).isEmpty())
        assertEquals(ProviderPublicTextPhase.FINAL_ANSWER, spans(message(obj(acc.completedResponse()))).single().phase)
    }

    @Test fun nonMessageAndNonAssistantMetadataCannotTagPublicDeltas() {
        for (metadata in listOf(JsonObject(call + ("phase" to JsonPrimitive("final_answer"))),
            JsonObject(item("", JsonPrimitive("final_answer")) + ("role" to JsonPrimitive("user"))))) {
            val acc = OpenAiResponsesAdapter.StreamAccumulator(json)
            acc.accept(event("response.output_item.added", 0, metadata))
            assertEquals(listOf(LlmStreamEvent.TextDelta("public")), acc.accept("""{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"public"}"""))
        }
    }

    @Test fun reusedOutputIndexClearsStaleMessagePhaseForNonMessageItems() {
        for (replacement in listOf(reasoning, call)) {
            val acc = OpenAiResponsesAdapter.StreamAccumulator(json)
            acc.accept(event("response.output_item.added", 0, item("", JsonPrimitive("commentary"))))
            assertEquals(listOf(LlmStreamEvent.TextDelta("Before", ProviderPublicTextPhase.COMMENTARY)),
                acc.accept("""{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"Before"}"""))
            val captured = acc.accept(event("response.output_item.added", 0, replacement))
            assertFalse(captured.any { it is LlmStreamEvent.TextDelta })
            assertEquals(listOf(LlmStreamEvent.TextDelta("After")),
                acc.accept("""{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"After"}"""))
        }
    }

    @Test fun streamFailureKeepsPublicTextOnlyAndCannotProduceFinalResponse() {
        val acc = OpenAiResponsesAdapter.StreamAccumulator(json)
        acc.accept(event("response.output_item.added", 0, item("", JsonPrimitive("commentary"))))
        assertEquals(listOf(LlmStreamEvent.TextDelta("Checking", ProviderPublicTextPhase.COMMENTARY)),
            acc.accept("""{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"Checking"}"""))
        assertThrows(ProviderErrorPayloadException::class.java) {
            acc.accept("""{"type":"response.failed","response":{"status":"failed","error":{"code":"server_error","message":"fixture"}}}""")
        }
        assertThrows(IllegalStateException::class.java) { acc.completedResponse() }
    }
}
