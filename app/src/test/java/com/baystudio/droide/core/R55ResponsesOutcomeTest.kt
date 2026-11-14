package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class R55ResponsesOutcomeTest {
    private fun obj(s:String)=Json.parseToJsonElement(s).jsonObject
    private fun response(status:String="completed", itemStatus:String?=null, tool:Boolean=false, reason:String?=null):String = buildJsonObject {
        put("status",status)
        reason?.let{put("incomplete_details",buildJsonObject{put("reason",it)})}
        put("output",buildJsonArray { add(buildJsonObject {
            itemStatus?.let{put("status",it)}
            if(tool){put("type","function_call");put("call_id","call-1");put("name","read_file");put("arguments","{}")}
            else {put("type","message");put("role","assistant");put("content",buildJsonArray{add(buildJsonObject{put("type","output_text");put("text","Public partial")})})}
        }) })
        put("usage",buildJsonObject{put("input_tokens",7);put("output_tokens",3);put("total_tokens",10)})
    }.toString()
    private fun decision(raw:String)=AgentResponseOutcome.evaluate(obj(OpenAiResponsesAdapter.normalizeBuffered(raw,Json))["choices"]!!.jsonArray[0].jsonObject)
    @Test fun unfinishedItemsCannotCertifyCompletedEnvelopeOrTools() {
        for(status in listOf("incomplete","in_progress","unknown")) for(tool in listOf(false,true)) {
            val raw=response(itemStatus=status,tool=tool)
            assertThrows(IllegalArgumentException::class.java){OpenAiResponsesAdapter.normalizeBuffered(raw,Json)}
            val accumulator=OpenAiResponsesAdapter.StreamAccumulator(Json)
            assertThrows(IllegalArgumentException::class.java){accumulator.accept("""{"type":"response.completed","response":$raw}""")}
        }
    }
    @Test fun missingOrCompletedItemStatusPreservesNormalAnswersAndTools() {
        for(status in listOf(null,"completed")) {
            assertEquals(AgentResponseOutcome.Kind.COMPLETE,decision(response(itemStatus=status)).kind)
            assertEquals(AgentResponseOutcome.Kind.TOOL_TURN,decision(response(itemStatus=status,tool=true)).kind)
        }
    }
    @Test fun terminalEventMustAgreeWithNestedStatus() {
        for(pair in listOf("response.incomplete" to response(),"response.completed" to response("incomplete",reason="max_output_tokens"))) {
            assertThrows(IllegalArgumentException::class.java){OpenAiResponsesAdapter.StreamAccumulator(Json).accept("""{"type":"${pair.first}","response":${pair.second}}""")}
        }
    }
    @Test fun legitimateIncompletePublicTextRemainsOutputLimitWithUsage() {
        val raw=response("incomplete","incomplete",reason="max_output_tokens")
        val normalized=obj(OpenAiResponsesAdapter.normalizeBuffered(raw,Json))
        val d=AgentResponseOutcome.evaluate(normalized["choices"]!!.jsonArray[0].jsonObject)
        assertEquals(AgentResponseOutcome.Kind.OUTPUT_LIMIT,d.kind);assertEquals("Public partial",d.publicText)
        assertEquals(JsonPrimitive(10),normalized["usage"]!!.jsonObject["total_tokens"])
        assertThrows(IllegalArgumentException::class.java){CompactionResponseText.extract(normalized.toString())}
    }
    @Test fun incompleteToolsAreSuppressedEvenWithValidJsonArguments() {
        val normalized=obj(OpenAiResponsesAdapter.normalizeBuffered(response("incomplete","incomplete",true,"max_output_tokens"),Json))
        val message=normalized["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
        assertFalse(message.containsKey("tool_calls"));assertFalse(message.containsKey(OpenAiResponsesAdapter.PRIVATE_CONTENT_FIELD))
        assertFalse(AgentResponseOutcome.evaluate(normalized["choices"]!!.jsonArray[0].jsonObject).accepted)
    }
    @Test fun refusalPublicExplanationSurvivesWithoutCompletingAnswer() {
        val raw="""{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"refusal","refusal":"Public refusal explanation"}]}]}"""
        val d=decision(raw);assertEquals(AgentResponseOutcome.Kind.BLOCKED,d.kind);assertEquals("Public refusal explanation",d.publicText)
    }
    @Test fun failureAfterTextDoesNotEmitAnyExecutableTools() {
        val accumulator=OpenAiResponsesAdapter.StreamAccumulator(Json)
        val text=accumulator.accept("""{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"Already visible"}""")
        assertEquals(listOf(LlmStreamEvent.TextDelta("Already visible")),text)
        assertThrows(ProviderErrorPayloadException::class.java){accumulator.accept("""{"type":"response.failed","response":{"status":"failed","error":{"code":"server_error","message":"fixture"}}}""")}
        assertThrows(IllegalStateException::class.java){accumulator.completedResponse()}
    }
    @Test fun unknownOrMissingTerminalStaysFailClosed() {
        for(status in listOf("failed","in_progress","unknown")) assertThrows(IllegalStateException::class.java){OpenAiResponsesAdapter.normalizeBuffered(response(status),Json)}
        assertThrows(IllegalStateException::class.java){OpenAiResponsesAdapter.StreamAccumulator(Json).completedResponse()}
    }
}
