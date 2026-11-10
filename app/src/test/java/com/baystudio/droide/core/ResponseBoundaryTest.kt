package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Actual production helpers; host runner doubles only unavailable transport/presentation boundaries. */
class ResponseBoundaryTest {
    private fun obj(s:String)=Json.parseToJsonElement(s).jsonObject
    @Test fun actualBudgetProjectsLegacyShellBeforeNativeEncoding() {
        val request=obj("""{"model":"gemini-r54-fixture","messages":[{"role":"user","content":"Do the work"},{"role":"assistant","content":""},{"role":"user","content":"Continue"}]}""")
        val prepared=ProviderRequestBudget.prepare(request,"gemini","gemini-r54-fixture")
        assertEquals(JsonPrimitive(8192),prepared["max_tokens"])
        assertEquals(2,prepared["messages"]!!.jsonArray.size)
        val wire=obj(GoogleGenerateContentAdapter.encodeRequest(prepared.toString(),Json))
        assertFalse(wire["contents"]!!.jsonArray.any{it.jsonObject["parts"]!!.jsonArray.isEmpty()})
        assertEquals(3,request["messages"]!!.jsonArray.size)
    }
    @Test fun actualBudgetRejectsUnresolvedToolGroupInsteadOfCleaningIt() {
        val request=obj("""{"model":"gemini-r54-fixture","messages":[{"role":"assistant","content":"","tool_calls":[{"id":"call","type":"function","function":{"name":"terminal","arguments":"{}"}}]}]}""")
        assertThrows(IllegalArgumentException::class.java){ProviderRequestBudget.prepare(request,"gemini","gemini-r54-fixture")}
    }
    @Test fun actualBudgetPreservesClosedFailedToolGroupAndExplicitOutputLimit() {
        val request=obj("""{"model":"gemini-r54-fixture","max_tokens":20000,"messages":[{"role":"user","content":"Run the existing command"},{"role":"assistant","content":"","tool_calls":[{"id":"call","type":"function","function":{"name":"terminal","arguments":"{\"command\":\"sleep 900\"}"}}]},{"role":"tool","tool_call_id":"call","content":"Failed: localhost"},{"role":"assistant","content":""},{"role":"user","content":"Continue carefully"}]}""")
        val prepared=ProviderRequestBudget.prepare(request,"gemini","gemini-r54-fixture")
        assertEquals(JsonPrimitive(20000),prepared["max_tokens"])
        assertEquals(4,prepared["messages"]!!.jsonArray.size)
        assertEquals(request["messages"]!!.jsonArray.take(3),prepared["messages"]!!.jsonArray.take(3))
    }
    @Test fun outcomeFromRealNativeNormalizationIsNeverFalseSuccess() {
        val samples=listOf(
            """{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"thought":true,"text":"private"}]}}]}""",
            """{"promptFeedback":{"blockReason":"SAFETY"}}""",
            """{"candidates":[{"finishReason":"MALFORMED_FUNCTION_CALL"}]}""",
            """{"candidates":[{"finishReason":"STOP","content":{"parts":[]}}]}"""
        )
        val kinds=samples.map{raw ->
            val normalized=obj(GoogleGenerateContentAdapter.normalizeBuffered(raw,Json))
            AgentResponseOutcome.evaluate(normalized["choices"]!!.jsonArray[0].jsonObject).also{assertFalse(it.accepted)}.kind
        }
        assertEquals(listOf(AgentResponseOutcome.Kind.OUTPUT_LIMIT,AgentResponseOutcome.Kind.BLOCKED,AgentResponseOutcome.Kind.INVALID,AgentResponseOutcome.Kind.EMPTY),kinds)
    }
    @Test fun partialSnapshotIsPublicIncompleteTextAndNeverExecutableContinuation() {
        val source=AgentStreamPart("text:4",4,AgentStreamPartKind.TEXT,AgentStreamPartState.RUNNING,
            text="Useful partial ✓",input="private-tool-arguments",finishReason="length")
        val snapshot=InterruptedAgentStream.snapshot(source,false,27)!!
        assertEquals(AgentStreamPartState.ERROR,snapshot.message.streamPart!!.state)
        assertEquals(setOf("role","content"),snapshot.apiEntry.keys)
        assertTrue(snapshot.apiEntry["content"]!!.jsonPrimitive.content.contains("Useful partial ✓"))
        assertTrue(snapshot.apiEntry["content"]!!.jsonPrimitive.content.contains("incomplete"))
        assertFalse(snapshot.apiEntry.toString().contains("private-tool-arguments"))
        assertNull(InterruptedAgentStream.snapshot(source.copy(kind=AgentStreamPartKind.TOOL),false,27))
    }
    @Test fun cancelledPartialRemainsCancelledAndDistinctFromCompletedAnswer() {
        val source=AgentStreamPart("text:4",4,AgentStreamPartKind.TEXT,AgentStreamPartState.RUNNING,text="Partial")
        val snapshot=InterruptedAgentStream.snapshot(source,true,27)!!
        assertEquals(AgentStreamPartState.CANCELLED,snapshot.message.streamPart!!.state)
        assertEquals("",snapshot.message.streamPart!!.finishReason)
        assertFalse(snapshot.apiEntry.toString().contains("tool_calls"))
    }
    @Test fun malformedAndIncompleteCompactionDoesNotBecomeCheckpointText() {
        for(reason in listOf("MAX_TOKENS","SAFETY","MALFORMED_FUNCTION_CALL")) {
            val raw="""{"candidates":[{"finishReason":"$reason","content":{"parts":[{"text":"Partial checkpoint"}]}}]}"""
            assertThrows(IllegalArgumentException::class.java){CompactionResponseText.extract(GoogleGenerateContentAdapter.normalizeBuffered(raw,Json))}
        }
    }
    @Test fun completedToolContinuationSurvivesProjectionAndNativeEncoding() {
        val request=obj("""{"model":"gemini-r54-fixture","messages":[{"role":"user","content":"Check"},{"role":"assistant","content":"","tool_calls":[{"id":"call","type":"function","function":{"name":"terminal","arguments":"{}"}}],"_droide_google_private_parts":[{"functionCall":{"id":"call","name":"terminal","args":{}},"thoughtSignature":"fixture-signature"}]},{"role":"tool","tool_call_id":"call","content":"done"}]}""")
        val prepared=ProviderRequestBudget.prepare(request,"gemini","gemini-r54-fixture")
        assertEquals(request["messages"],prepared["messages"])
        val encoded=GoogleGenerateContentAdapter.encodeRequest(prepared.toString(),Json)
        assertTrue(encoded.contains("fixture-signature")); assertTrue(encoded.contains("functionResponse"))
    }
}
