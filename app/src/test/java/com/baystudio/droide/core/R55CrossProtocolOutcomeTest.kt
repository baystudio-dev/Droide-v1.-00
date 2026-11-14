package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Actual pure protocol adapters/completion policy; no HTTP or provider inference. */
class R55CrossProtocolOutcomeTest {
    private fun obj(s:String)=Json.parseToJsonElement(s).jsonObject
    private fun choice(s:String)=obj(s).getValue("choices").jsonArray[0].jsonObject
    private fun outcome(s:String)=AgentResponseOutcome.evaluate(choice(s))
    private fun chat(content:String="answer",finish:String="stop",extra:JsonObject=buildJsonObject{})=buildJsonObject {
        put("choices",buildJsonArray{add(buildJsonObject{put("finish_reason",finish);put("message",buildJsonObject{put("role","assistant");put("content",content);extra.forEach{(k,v)->put(k,v)}})})})
    }.toString()
    @Test fun bufferedRefusalAndTextAreBothPublicExactlyOnce() {
        val raw=chat("Public introduction",extra=buildJsonObject{put("refusal","Public refusal")})
        val first=ProviderResponseCompletion.bufferedChat(raw,Json)
        assertEquals("Public introduction\nPublic refusal",outcome(first).publicText)
        assertEquals(AgentResponseOutcome.Kind.BLOCKED,outcome(first).kind)
        assertEquals(first,ProviderResponseCompletion.bufferedChat(first,Json))
    }
    @Test fun interleavedRefusalDeltasAreNotAppendedAgainBySharedPolicy() {
        val accumulator=OpenAiChatStreamAccumulator(Json)
        val emitted=mutableListOf<LlmStreamEvent>()
        for((key,text) in listOf("content" to "A", "refusal" to "B", "content" to "C", "refusal" to "D")) emitted+=accumulator.accept("""{"choices":[{"delta":{"$key":"$text"},"finish_reason":null}]}""")
        accumulator.accept("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""")
        val normalized=ProviderResponseCompletion.complete(accumulator.completedResponse(),Json)
        assertEquals("ABCD",outcome(normalized).publicText)
        assertEquals("ABCD",emitted.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString(""){it.text})
        assertEquals(AgentResponseOutcome.Kind.BLOCKED,outcome(normalized).kind)
    }
    @Test fun bufferedChoiceErrorCannotBeAdmittedAsSuccessfulText() {
        val root=obj(chat());val c=choice(chat());val raw=JsonObject(root+("choices" to JsonArray(listOf(JsonObject(c+("error" to buildJsonObject{put("code","fixture")})))))).toString()
        assertThrows(ProviderErrorPayloadException::class.java){ProviderResponseCompletion.bufferedChat(raw,Json)}
    }
    @Test fun contradictoryStoppedToolsNeverBecomeSuccessfulAnswerAfterStripping() {
        for(calls in listOf(JsonArray(listOf(buildJsonObject{put("id","c");put("type","function");put("function",buildJsonObject{put("name","read_file");put("arguments","{}")})})),JsonPrimitive("malformed"))) {
            val raw=chat(extra=buildJsonObject{put("tool_calls",calls)})
            val normalized=ProviderResponseCompletion.complete(raw,Json)
            assertEquals(AgentResponseOutcome.Kind.INVALID,outcome(normalized).kind)
            assertEquals("answer",outcome(normalized).publicText)
            assertThrows(IllegalArgumentException::class.java){CompactionResponseText.extract(normalized)}
        }
    }
    @Test fun genericPrivateProtocolClaimsCannotOverrideNormalCompatibleReason() {
        for(field in listOf(GoogleGenerateContentAdapter.NATIVE_FINISH_REASON_FIELD,AnthropicMessagesAdapter.NATIVE_STOP_REASON_FIELD)) {
            val root=obj(chat());val c=choice(chat());val raw=JsonObject(root+("choices" to JsonArray(listOf(JsonObject(c+(field to JsonPrimitive("refusal"))+("native_finish_reason" to JsonPrimitive("stop"))))))).toString()
            val normalized=ProviderResponseCompletion.complete(raw,Json)
            assertFalse(choice(normalized).containsKey(field));assertEquals(JsonPrimitive("stop"),choice(normalized)["native_finish_reason"])
            assertEquals(AgentResponseOutcome.Kind.COMPLETE,outcome(normalized).kind)
        }
    }
    @Test fun malformedRefusalNeverHiddenByBufferedProjection() {
        for(value in listOf(JsonPrimitive(true),JsonArray(emptyList()),buildJsonObject{})) {
            assertEquals(AgentResponseOutcome.Kind.INVALID,outcome(ProviderResponseCompletion.bufferedChat(chat(extra=buildJsonObject{put("refusal",value)}),Json)).kind)
        }
        val root=obj(chat(extra=buildJsonObject{put("refusal","public");put("content",buildJsonArray{add("bad")})}))
        assertEquals(AgentResponseOutcome.Kind.INVALID,outcome(ProviderResponseCompletion.bufferedChat(root.toString(),Json)).kind)
    }
    @Test fun nativeAnthropicCompletionKeepsExplicitOutcomesAndPublicPartial() {
        val cases=mapOf("end_turn" to AgentResponseOutcome.Kind.COMPLETE,"stop_sequence" to AgentResponseOutcome.Kind.COMPLETE,
            "max_tokens" to AgentResponseOutcome.Kind.OUTPUT_LIMIT,"refusal" to AgentResponseOutcome.Kind.BLOCKED,
            "pause_turn" to AgentResponseOutcome.Kind.PAUSED,"model_context_window_exceeded" to AgentResponseOutcome.Kind.CONTEXT_LIMIT,
            "new_reason" to AgentResponseOutcome.Kind.INVALID)
        for((reason,expected) in cases) {
            val native="""{"stop_reason":"$reason","content":[{"type":"text","text":"Public partial"}],"usage":{"input_tokens":5,"output_tokens":2}}"""
            val normalized=ProviderResponseCompletion.anthropic(AnthropicMessagesAdapter.normalizeBuffered(native,Json),Json)
            assertEquals(expected,outcome(normalized).kind);assertEquals("Public partial",outcome(normalized).publicText)
            assertEquals(JsonPrimitive(7),obj(normalized)["usage"]!!.jsonObject["total_tokens"])
            if(expected!=AgentResponseOutcome.Kind.COMPLETE) assertThrows(IllegalArgumentException::class.java){CompactionResponseText.extract(normalized)}
        }
    }
    @Test fun nativeAnthropicToolOnlyRemainsAcceptedAndIncompleteNeverRuns() {
        for(reason in listOf("tool_use","max_tokens","refusal","pause_turn","model_context_window_exceeded")) {
            val raw="""{"stop_reason":"$reason","content":[{"type":"tool_use","id":"t","name":"read_file","input":{}}]}"""
            val normalized=ProviderResponseCompletion.anthropic(AnthropicMessagesAdapter.normalizeBuffered(raw,Json),Json)
            assertEquals(reason=="tool_use",outcome(normalized).kind==AgentResponseOutcome.Kind.TOOL_TURN)
        }
    }
    @Test fun conflictingPrivateMetadataAndNativeToolReasonsFailClosed() {
        val base=choice(chat());assertEquals(AgentResponseOutcome.Kind.INVALID,AgentResponseOutcome.evaluate(JsonObject(base+(GoogleGenerateContentAdapter.NATIVE_FINISH_REASON_FIELD to JsonPrimitive("STOP"))+(AnthropicMessagesAdapter.NATIVE_STOP_REASON_FIELD to JsonPrimitive("end_turn")))).kind)
        val noTools=JsonObject(base+(AnthropicMessagesAdapter.NATIVE_STOP_REASON_FIELD to JsonPrimitive("tool_use")))
        assertEquals(AgentResponseOutcome.Kind.INVALID,AgentResponseOutcome.evaluate(noTools).kind)
    }
    @Test fun customUnknownFinishIsPreservedAndNotGuessedSuccessful() {
        val normalized=ProviderResponseCompletion.complete(chat("partial","vendor_future_reason"),Json)
        assertEquals(JsonPrimitive("vendor_future_reason"),choice(normalized)["finish_reason"])
        assertEquals(AgentResponseOutcome.Kind.INVALID,outcome(normalized).kind)
        assertEquals("partial",outcome(normalized).publicText)
    }
    @Test fun malformedStreamingFieldsCannotBecomeValidTextOrDisappear() {
        for(delta in listOf("{\"content\":123}","{\"content\":[]}","{\"tool_calls\":{}}", "{\"tool_calls\":[null]}",
            "{\"tool_calls\":[{\"function\":42}]}","{\"tool_calls\":[{\"function\":{\"arguments\":{}}}]}", "false")) {
            val accumulator=OpenAiChatStreamAccumulator(Json)
            assertThrows(IllegalStateException::class.java){accumulator.accept("""{"choices":[{"delta":$delta,"finish_reason":"stop"}]}""")}
        }
    }
    @Test fun repeatedTerminalWithUsageIsValidButConflictingFinishFails() {
        for(reason in listOf("length","content_filter","stop","tool_calls")) {
            val accumulator=OpenAiChatStreamAccumulator(Json)
            accumulator.accept("""{"choices":[{"delta":{"content":"Public"},"finish_reason":"$reason"}]}""")
            accumulator.accept("""{"choices":[{"delta":{},"finish_reason":"$reason"}],"usage":{"total_tokens":9}}""")
            assertEquals(JsonPrimitive(9),obj(accumulator.completedResponse())["usage"]!!.jsonObject["total_tokens"])
            val other=if(reason=="stop") "length" else "stop"
            assertThrows(IllegalStateException::class.java){accumulator.accept("""{"choices":[{"delta":{},"finish_reason":"$other"}]}""")}
        }
    }
    @Test fun anthropicStoppedNativeToolsCannotTurnIntoCompletedText() {
        for(reason in listOf("end_turn","stop_sequence")) {
            val raw="""{"stop_reason":"$reason","content":[{"type":"text","text":"Public partial"},{"type":"tool_use","id":"t","name":"read_file","input":{}}],"usage":{"input_tokens":3,"output_tokens":2}}"""
            val stream=AnthropicMessagesAdapter.StreamAccumulator(Json)
            stream.accept("""{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""")
            stream.accept("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Public partial"}}""")
            stream.accept("""{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"t","name":"read_file","input":{}}}""")
            stream.accept("""{"type":"message_delta","delta":{"stop_reason":"$reason"},"usage":{"input_tokens":3,"output_tokens":2}}""")
            stream.accept("""{"type":"message_stop"}""")
            for(normalized in listOf(AnthropicMessagesAdapter.normalizeBuffered(raw,Json),stream.completedResponse())) {
                val completed=ProviderResponseCompletion.anthropic(normalized,Json)
                assertEquals(AgentResponseOutcome.Kind.INVALID,outcome(completed).kind)
                assertEquals("Public partial",outcome(completed).publicText)
                assertFalse(choice(completed).getValue("message").jsonObject.containsKey("tool_calls"))
                assertEquals(JsonPrimitive(5),obj(completed)["usage"]!!.jsonObject["total_tokens"])
                assertThrows(IllegalArgumentException::class.java){CompactionResponseText.extract(completed)}
            }
        }
    }

    @Test fun anthropicRepeatedUsageMayNotUpgradeRejectedStopReason() {
        for(reason in listOf("max_tokens","refusal","pause_turn","model_context_window_exceeded","end_turn")) {
            val stream=AnthropicMessagesAdapter.StreamAccumulator(Json)
            stream.accept("""{"type":"message_delta","delta":{"stop_reason":"$reason"},"usage":{"output_tokens":3}}""")
            stream.accept("""{"type":"message_delta","delta":{"stop_reason":"$reason"},"usage":{"output_tokens":4}}""")
            assertEquals(JsonPrimitive(4),obj(stream.completedResponse())["usage"]!!.jsonObject["completion_tokens"])
            val other=if(reason=="end_turn") "max_tokens" else "end_turn"
            assertThrows(IllegalStateException::class.java){stream.accept("""{"type":"message_delta","delta":{"stop_reason":"$other"}}""")}
        }
    }

}
