package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AnthropicOutcomeTest {
    private val nativeField = AnthropicMessagesAdapter.NATIVE_STOP_REASON_FIELD
    private val reasons = listOf("end_turn", "stop_sequence", "max_tokens", "tool_use", "refusal",
        "pause_turn", "model_context_window_exceeded")
    private fun obj(body: String) = Json.parseToJsonElement(body).jsonObject
    private fun choice(body: String) = obj(body).getValue("choices").jsonArray[0].jsonObject
    private fun message(body: String) = choice(body).getValue("message").jsonObject
    private fun usage(body: String) = obj(body).getValue("usage").jsonObject
    private fun response(reason: String?, content: JsonArray = JsonArray(emptyList())) = buildJsonObject {
        reason?.let { put("stop_reason", it) }
        put("content", content)
        put("usage", buildJsonObject { put("input_tokens", 3); put("output_tokens", 7) })
        put("stop_details", buildJsonObject { put("message", "untrusted provider details") })
    }.toString()
    private fun finish(stream: AnthropicMessagesAdapter.StreamAccumulator, reason: String?) {
        stream.accept(buildJsonObject {
            put("type", "message_delta")
            put("delta", buildJsonObject { reason?.let { put("stop_reason", it) } })
            put("usage", buildJsonObject { put("output_tokens", 7) })
        }.toString())
        stream.accept("""{"type":"message_stop"}""")
    }
    private fun streamStart() = AnthropicMessagesAdapter.StreamAccumulator(Json).also {
        it.accept("""{"type":"message_start","message":{"content":[],"stop_reason":null,"usage":{"input_tokens":3,"output_tokens":1}}}""")
    }
    private fun addText(stream: AnthropicMessagesAdapter.StreamAccumulator, text: String): List<LlmStreamEvent> {
        stream.accept("""{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""")
        return stream.accept(buildJsonObject {
            put("type", "content_block_delta"); put("index", 0)
            put("delta", buildJsonObject { put("type", "text_delta"); put("text", text) })
        }.toString()).also { stream.accept("""{"type":"content_block_stop","index":0}""") }
    }

    @Test fun bufferedAndStreamedStopEnumsRemainDistinctWithoutCanonicalChanges() {
        for (reason in reasons) {
            val text = "Public response fixture"
            val content = buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) }
            val stream = streamStart()
            assertEquals(listOf(LlmStreamEvent.TextDelta(text)), addText(stream, text))
            finish(stream, reason)
            for (normalized in listOf(AnthropicMessagesAdapter.normalizeBuffered(response(reason, content), Json), stream.completedResponse())) {
                val canonical = when (reason) {
                    "end_turn", "stop_sequence" -> "stop"
                    "tool_use" -> "tool_calls"
                    "max_tokens" -> "length"
                    else -> reason
                }
                assertEquals(JsonPrimitive(reason), choice(normalized)[nativeField])
                assertEquals(JsonPrimitive(canonical), choice(normalized)["finish_reason"])
                assertEquals(JsonPrimitive(text), message(normalized)["content"])
                assertEquals(JsonPrimitive(10), usage(normalized)["total_tokens"])
                assertFalse(normalized.contains("untrusted provider details"))
            }
        }
    }

    @Test fun missingStopReasonIsNotInventedAndUnknownDiagnosticsAreAllowlisted() {
        assertNull(AnthropicMessagesAdapter.safeNativeStopReason(null))
        assertNull(AnthropicMessagesAdapter.safeNativeStopReason(" \n"))
        for (reason in listOf(null, "", " ", "NEW_VENDOR_REASON", "provider-secret-fixture\n" + "x".repeat(1_000))) {
            val stream = streamStart()
            finish(stream, reason)
            for (normalized in listOf(AnthropicMessagesAdapter.normalizeBuffered(response(reason), Json), stream.completedResponse())) {
                if (reason.isNullOrBlank()) assertFalse(nativeField in choice(normalized))
                else assertEquals(JsonPrimitive("UNKNOWN"), choice(normalized)[nativeField])
                // Existing canonical mapping is deliberately unchanged. Only the private diagnostic field is
                // newly authored and allowlisted here.
                assertEquals(reason?.let(::JsonPrimitive) ?: JsonNull, choice(normalized)["finish_reason"])
            }
        }
    }

    @Test fun nativePayloadCannotAuthorOrOverrideThePrivateDiagnosticField() {
        for (reason in listOf(null, "refusal", "model_context_window_exceeded")) {
            val injected = JsonObject(obj(response(reason)) + (nativeField to JsonPrimitive("end_turn")))
            val stream = streamStart()
            stream.accept(buildJsonObject {
                put("type", "message_delta"); put(nativeField, "end_turn")
                put("delta", buildJsonObject {
                    reason?.let { put("stop_reason", it) }
                    put(nativeField, "end_turn")
                })
            }.toString())
            for (normalized in listOf(AnthropicMessagesAdapter.normalizeBuffered(injected.toString(), Json), stream.completedResponse())) {
                if (reason == null) assertFalse(nativeField in choice(normalized))
                else assertEquals(JsonPrimitive(reason), choice(normalized)[nativeField])
            }
        }
    }

    @Test fun toolOnlyTurnsRetainExactPrivateContinuationOnlyForToolUse() {
        val blocks = Json.parseToJsonElement("""[
            {"type":"thinking","thinking":"private plan fixture","signature":"signature-fixture"},
            {"type":"redacted_thinking","data":"encrypted-fixture"},
            {"type":"tool_use","id":"call_1","name":"read_file","input":{"path":"README.md"}}
        ]""").jsonArray
        for (reason in reasons + listOf(null, "unknown")) {
            val stream = streamStart()
            blocks.forEachIndexed { index, block ->
                val events = stream.accept(buildJsonObject {
                    put("type", "content_block_start"); put("index", index); put("content_block", block)
                }.toString())
                assertTrue(events.none { it is LlmStreamEvent.TextDelta || it is LlmStreamEvent.BufferedResponse })
                stream.accept(buildJsonObject { put("type", "content_block_stop"); put("index", index) }.toString())
            }
            finish(stream, reason)
            for (normalized in listOf(AnthropicMessagesAdapter.normalizeBuffered(response(reason, blocks), Json), stream.completedResponse())) {
                val msg = message(normalized)
                assertEquals(JsonPrimitive(""), msg["content"])
                if (reason == "tool_use") {
                    assertEquals(1, msg.getValue("tool_calls").jsonArray.size)
                    assertEquals(blocks, msg[AnthropicMessagesAdapter.PRIVATE_CONTENT_FIELD])
                    val request = buildJsonObject {
                        put("model", "claude-sonnet-4-5")
                        put("messages", buildJsonArray {
                            add(msg)
                            add(obj("""{"role":"tool","tool_call_id":"call_1","content":"ok"}"""))
                        })
                    }
                    val replay = obj(AnthropicMessagesAdapter.encodeRequest(request.toString(), false, Json))
                    assertEquals(blocks, replay.getValue("messages").jsonArray[0].jsonObject["content"])
                } else {
                    assertFalse("tool_calls" in msg)
                    assertFalse(msg.keys.any { it.startsWith("_droide_") })
                    assertFalse(normalized.contains("private plan fixture"))
                    assertFalse(normalized.contains("encrypted-fixture"))
                }
            }
        }
    }

    @Test fun plainToolOnlyTurnsNeedNoPrivateThinkingAndUsageOnlyIsNotAnAnswer() {
        val blocks = Json.parseToJsonElement("""[{"type":"tool_use","id":"call_1","name":"read_file","input":{}}]""").jsonArray
        val tool = AnthropicMessagesAdapter.normalizeBuffered(response("tool_use", blocks), Json)
        assertEquals(AgentResponseOutcome.Kind.TOOL_TURN, AgentResponseOutcome.evaluate(choice(tool)).kind)
        assertFalse(AnthropicMessagesAdapter.PRIVATE_CONTENT_FIELD in message(tool))
        val stream = streamStart()
        finish(stream, "end_turn")
        for (normalized in listOf(AnthropicMessagesAdapter.normalizeBuffered(response("end_turn"), Json), stream.completedResponse())) {
            assertEquals(AgentResponseOutcome.Kind.EMPTY, AgentResponseOutcome.evaluate(choice(normalized)).kind)
            assertEquals(JsonPrimitive(10), usage(normalized)["total_tokens"])
        }
    }

    @Test fun errorsAfterPublicTextStillThrowInsteadOfReturningASuccessfulTurn() {
        val stream = streamStart()
        val events = addText(stream, "Already streamed public text")
        assertEquals(listOf(LlmStreamEvent.TextDelta("Already streamed public text")), events)
        assertThrows(ProviderErrorPayloadException::class.java) {
            stream.accept("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded fixture"}}""")
        }
        assertThrows(ProviderErrorPayloadException::class.java) {
            AnthropicMessagesAdapter.normalizeBuffered("""{"stop_reason":"end_turn","content":[{"type":"text","text":"partial"}],"error":{"type":"overloaded_error"}}""", Json)
        }
    }

    @Test fun rejectedIncompletePrivateBlocksDoNotDiscardPublicTextOrReportedUsage() {
        val blocks = Json.parseToJsonElement("""[
            {"type":"text","text":"Public partial fixture"},
            {"type":"thinking","thinking":"incomplete private fixture"},
            {"type":"tool_use","id":"call_1","name":"read_file","input":null}
        ]""").jsonArray
        for (reason in listOf("max_tokens", "refusal", "pause_turn", "model_context_window_exceeded")) {
            val stream = streamStart()
            addText(stream, "Public partial fixture")
            stream.accept("""{"type":"content_block_start","index":1,"content_block":{"type":"thinking","thinking":"incomplete private fixture"}}""")
            stream.accept("""{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"call_1","name":"read_file","input":{}}}""")
            stream.accept("""{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"path\":"}}""")
            finish(stream, reason)
            for (normalized in listOf(AnthropicMessagesAdapter.normalizeBuffered(response(reason, blocks), Json), stream.completedResponse())) {
                assertEquals(JsonPrimitive("Public partial fixture"), message(normalized)["content"])
                assertEquals(JsonPrimitive(reason), choice(normalized)[nativeField])
                assertEquals(JsonPrimitive(10), usage(normalized)["total_tokens"])
                assertFalse("tool_calls" in message(normalized))
                assertFalse(message(normalized).keys.any { it.startsWith("_droide_") })
                assertFalse(normalized.contains("incomplete private fixture"))
                assertFalse(AgentResponseOutcome.evaluate(choice(normalized)).accepted)
                assertThrows(IllegalArgumentException::class.java) { CompactionResponseText.extract(normalized) }
            }
        }
        // A claimed successful native tool turn still has to validate the exact private signature and
        // tool payload before it can be replayed.
        assertThrows(IllegalArgumentException::class.java) {
            AnthropicMessagesAdapter.normalizeBuffered(response("tool_use", blocks), Json)
        }
    }

    @Test fun malformedNativeJsonAndIncompleteSuccessfulToolInputStillFailClosed() {
        assertThrows(IllegalStateException::class.java) { streamStart().accept("{") }
        assertThrows(IllegalStateException::class.java) { AnthropicMessagesAdapter.normalizeBuffered("[]", Json) }
        val stream = streamStart()
        stream.accept("""{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"call_1","name":"read_file","input":{}}}""")
        stream.accept("""{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"path\":"}}""")
        finish(stream, "tool_use")
        assertThrows(IllegalStateException::class.java) { stream.completedResponse() }
        val malformedPrivate = Json.parseToJsonElement("""[
            {"type":"thinking","thinking":"private plan fixture","signature":"signature-fixture"},
            {"type":"tool_use","id":"call_1","name":"read_file","input":null}
        ]""").jsonArray
        assertThrows(IllegalArgumentException::class.java) {
            AnthropicMessagesAdapter.normalizeBuffered(response("tool_use", malformedPrivate), Json)
        }
    }

    @Test fun bufferedSuccessfulToolsNeverInventEmptyArgumentsForMalformedNativeInput() {
        val emptyObject = buildJsonObject {}
        val inputs = listOf<JsonElement?>(null, JsonNull, JsonArray(emptyList()), JsonPrimitive("{}"), JsonPrimitive(7), JsonPrimitive(false), emptyObject)
        for (input in inputs) {
            val blocks = buildJsonArray { add(buildJsonObject {
                put("type", "tool_use"); put("id", "call_1"); put("name", "read_file")
                input?.let { put("input", it) }
            }) }
            if (input == emptyObject) {
                val normalized = AnthropicMessagesAdapter.normalizeBuffered(response("tool_use", blocks), Json)
                val call = message(normalized).getValue("tool_calls").jsonArray.single().jsonObject
                assertEquals(JsonPrimitive("{}"), call.getValue("function").jsonObject["arguments"])
                assertEquals(AgentResponseOutcome.Kind.TOOL_TURN, AgentResponseOutcome.evaluate(choice(normalized)).kind)
            } else {
                assertThrows(IllegalArgumentException::class.java) {
                    AnthropicMessagesAdapter.normalizeBuffered(response("tool_use", blocks), Json)
                }
            }
            for (reason in listOf("max_tokens", "refusal", "pause_turn", "model_context_window_exceeded")) {
                val normalized = AnthropicMessagesAdapter.normalizeBuffered(response(reason, blocks), Json)
                assertFalse("tool_calls" in message(normalized))
                assertEquals(JsonPrimitive(10), usage(normalized)["total_tokens"])
            }
        }
        val missingSignature = Json.parseToJsonElement("""[
            {"type":"thinking","thinking":"incomplete private fixture"},
            {"type":"tool_use","id":"call_1","name":"read_file","input":{}}
        ]""").jsonArray
        assertThrows(IllegalArgumentException::class.java) {
            AnthropicMessagesAdapter.normalizeBuffered(response("tool_use", missingSignature), Json)
        }
    }

    @Test fun streamedToolStartInputIsValidatedOnlyWhenSuccessfulToolUseIsCertified() {
        val emptyObject = buildJsonObject {}
        val inputs = listOf<JsonElement?>(null, JsonNull, JsonArray(emptyList()), JsonPrimitive("{}"), JsonPrimitive(7), JsonPrimitive(false), emptyObject)
        for (input in inputs) {
            fun seeded(reason: String): AnthropicMessagesAdapter.StreamAccumulator {
                val stream = streamStart()
                addText(stream, "Public partial fixture")
                stream.accept(buildJsonObject {
                    put("type", "content_block_start"); put("index", 1)
                    put("content_block", buildJsonObject {
                        put("type", "tool_use"); put("id", "call_1"); put("name", "read_file")
                        input?.let { put("input", it) }
                    })
                }.toString())
                stream.accept("""{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"README.md\"}"}}""")
                stream.accept("""{"type":"content_block_stop","index":1}""")
                finish(stream, reason)
                return stream
            }
            if (input == emptyObject) {
                val normalized = seeded("tool_use").completedResponse()
                val call = message(normalized).getValue("tool_calls").jsonArray.single().jsonObject
                assertEquals(JsonPrimitive("{\"path\":\"README.md\"}"), call.getValue("function").jsonObject["arguments"])
                assertEquals(AgentResponseOutcome.Kind.TOOL_TURN, AgentResponseOutcome.evaluate(choice(normalized)).kind)
            } else {
                assertThrows(IllegalStateException::class.java) { seeded("tool_use").completedResponse() }
            }
            for (reason in listOf("max_tokens", "refusal", "pause_turn", "model_context_window_exceeded")) {
                val normalized = seeded(reason).completedResponse()
                assertEquals(JsonPrimitive("Public partial fixture"), message(normalized)["content"])
                assertEquals(JsonPrimitive(10), usage(normalized)["total_tokens"])
                assertFalse("tool_calls" in message(normalized))
                assertFalse(message(normalized).keys.any { it.startsWith("_droide_") })
            }
        }
    }
}
