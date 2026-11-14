package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class R54AgentResponseOutcomeTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun choice(text: JsonElement = JsonPrimitive(""), finish: String? = "stop", native: String? = null,
        calls: JsonElement? = null, refusal: String? = null) = buildJsonObject {
        put("message", buildJsonObject {
            put("role", "assistant"); put("content", text)
            calls?.let { put("tool_calls", it) }; refusal?.let { put("refusal", it) }
        })
        finish?.let { put("finish_reason", it) }; native?.let { put("_droide_google_finish_reason", it) }
    }
    private fun outcome(c: JsonObject) = AgentResponseOutcome.evaluate(c)
    private val tool = obj("""{"id":"call-1","type":"function","function":{"name":"terminal","arguments":"{\"command\":\"sleep 900\"}"}}""")

    @Test fun completePublicAnswerIsAcceptedExactly() {
        val answer = "  Selesai ✓\nUnicode: 日本語  "
        val result = outcome(choice(JsonPrimitive(answer)))
        assertEquals(AgentResponseOutcome.Kind.COMPLETE, result.kind)
        assertTrue(result.accepted); assertEquals(answer, result.publicText); assertEquals("", result.diagnostic)
    }
    @Test fun emptyStopIsExplicitFailureWithoutContextGuess() {
        for (text in listOf(JsonPrimitive(""), JsonPrimitive(" \n\t"), JsonNull)) {
            val result = outcome(choice(text))
            assertEquals(AgentResponseOutcome.Kind.EMPTY, result.kind); assertFalse(result.accepted)
            assertFalse(result.diagnostic.contains("context", true)); assertTrue(result.diagnostic.contains("no automatic retry"))
        }
    }
    @Test fun reasoningOnlyMaxTokensIsOutputLimitNotSuccess() {
        val result = outcome(choice(JsonPrimitive(""), "length", "MAX_TOKENS"))
        assertEquals(AgentResponseOutcome.Kind.OUTPUT_LIMIT, result.kind)
        assertFalse(result.accepted); assertTrue(result.diagnostic.contains("MAX_TOKENS")); assertEquals("", result.publicText)
    }
    @Test fun outputLimitedPublicPartialIsPreserved() {
        val result = outcome(choice(JsonPrimitive("Useful partial text"), "length"))
        assertEquals("Useful partial text", result.publicText); assertFalse(result.accepted)
    }
    @Test fun nativePromptBlockWithNullCanonicalReasonIsBlocked() {
        val result = outcome(choice(JsonNull, null, "PROMPT_BLOCK:SAFETY"))
        assertEquals(AgentResponseOutcome.Kind.BLOCKED, result.kind); assertTrue(result.diagnostic.contains("PROMPT_BLOCK:SAFETY"))
    }
    @Test fun safetyAndRecitationAreExplicitProviderStops() {
        for (reason in listOf("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT")) {
            val result = outcome(choice(JsonPrimitive(""), "content_filter", reason))
            assertEquals(AgentResponseOutcome.Kind.BLOCKED, result.kind); assertFalse(result.accepted)
        }
    }
    @Test fun malformedAndUnknownNativeReasonsAreNotMislabelledAsSafety() {
        for (reason in listOf("MALFORMED_FUNCTION_CALL", "UNEXPECTED_TOOL_CALL", "MISSING_THOUGHT_SIGNATURE", "OTHER", "UNKNOWN")) {
            val result = outcome(choice(JsonPrimitive(""), "content_filter", reason))
            assertEquals(AgentResponseOutcome.Kind.INVALID, result.kind); assertTrue(result.diagnostic.contains(reason))
        }
    }
    @Test fun missingOrUnknownTerminationDoesNotFinishAnswer() {
        for (reason in listOf(null, "", "unexpected")) {
            val result = outcome(choice(JsonPrimitive("Already streamed"), reason))
            assertEquals(AgentResponseOutcome.Kind.INVALID, result.kind); assertEquals("Already streamed", result.publicText)
        }
    }
    @Test fun refusalRemainsPublicButNotSuccessfulAnswer() {
        val result = outcome(choice(JsonNull, "stop", refusal = "I cannot provide that."))
        assertEquals(AgentResponseOutcome.Kind.BLOCKED, result.kind); assertEquals("I cannot provide that.", result.publicText)
    }
    @Test fun completedToolOnlyTurnStillContinuesNormally() {
        val result = outcome(choice(JsonNull, "tool_calls", calls = JsonArray(listOf(tool))))
        assertEquals(AgentResponseOutcome.Kind.TOOL_TURN, result.kind); assertTrue(result.accepted)
    }
    @Test fun incompleteToolTurnCannotExecute() {
        for (reason in listOf("length", "stop", "content_filter", null)) {
            val result = outcome(choice(JsonPrimitive("Planning"), reason, calls = JsonArray(listOf(tool))))
            assertFalse(result.accepted)
        }
    }
    @Test fun contradictoryNativeTerminalCannotAdmitToolsOrPublicCompletion() {
        for (native in listOf("MAX_TOKENS", "MALFORMED_FUNCTION_CALL", "PROMPT_BLOCK:SAFETY", "SAFETY", "OTHER", "UNKNOWN")) {
            assertFalse(outcome(choice(JsonNull, "tool_calls", native, JsonArray(listOf(tool)))).accepted)
            assertFalse(outcome(choice(JsonPrimitive("Looks complete"), "stop", native)).accepted)
        }
    }
    @Test fun terminalToolReasonWithoutToolsIsNotCompletion() {
        assertEquals(AgentResponseOutcome.Kind.INVALID, outcome(choice(finish = "tool_calls")).kind)
    }
    @Test fun malformedContentAndCallsFailClosed() {
        for (text in listOf(JsonPrimitive(7), JsonPrimitive(true), JsonArray(emptyList()), obj("{}"))) {
            assertEquals(AgentResponseOutcome.Kind.INVALID, outcome(choice(text)).kind)
        }
        assertEquals(AgentResponseOutcome.Kind.INVALID, outcome(choice(calls = obj("{}"))).kind)
        assertEquals(AgentResponseOutcome.Kind.INVALID, outcome(obj("{}" )).kind)
    }
    @Test fun rawReasonProseIsNotEchoed() {
        val result = outcome(choice(finish = "secret key=veryprivate"))
        assertFalse(result.diagnostic.contains("veryprivate")); assertTrue(result.diagnostic.contains("unknown"))
    }
    @Test fun privateReasoningIsNeverPublishedAsPublicText() {
        val c = choice().toMutableMap()
        c["message"] = obj("""{"role":"assistant","content":"","reasoning_content":"SECRET THOUGHT","_droide_google_private_parts":[{"thought":true,"text":"SECRET THOUGHT"}]}""")
        val result = outcome(JsonObject(c)); assertEquals("", result.publicText)
        assertFalse(result.diagnostic.contains("SECRET")); assertFalse(result.accepted)
    }
    @Test fun historyProjectionRemovesOnlyExactLegacyShellsWithoutMutatingInput() {
        val user = obj("""{"role":"user","content":"Continue"}""")
        val empty = obj("""{"role":"assistant","content":""}""")
        val blank = obj("""{"role":"assistant","content":" \n"}""")
        val nul = obj("""{"role":"assistant","content":null}""")
        val original = listOf(user, empty, blank, nul, user)
        val snapshot = original.toString()
        assertEquals(listOf(user, user), AgentResponseOutcome.providerHistory(original))
        assertEquals(snapshot, original.toString()); assertEquals(5, original.size)
    }
    @Test fun historyProjectionPreservesPrivateToolsAndUnknownMetadata() {
        val messages = listOf(
            obj("""{"role":"assistant","content":"","_droide_google_private_parts":[{"thoughtSignature":"sig"}]}"""),
            obj("""{"role":"assistant","content":"","tool_calls":[]}"""),
            obj("""{"role":"assistant","content":"","new_private_field":"keep"}"""),
            obj("""{"role":"assistant"}"""), obj("""{"role":"assistant","content":4}"""),
            obj("""{"role":"tool","content":"","tool_call_id":"call-1"}"""),
            obj("""{"role":"user","content":""}"""), obj("""{"role":"assistant","content":"(tidak ada respons)"}""")
        )
        assertEquals(messages, AgentResponseOutcome.providerHistory(messages))
    }
    @Test fun failedToolGroupAndUserIntentRemainInProjectedRequest() {
        val request = obj("""{"model":"custom-label","max_tokens":8192,"messages":[{"role":"user","content":"Run agy"},{"role":"assistant","content":"","tool_calls":[{"id":"c1","type":"function","function":{"name":"terminal","arguments":"{}"}}]},{"role":"tool","tool_call_id":"c1","content":"Failed to resolve localhost"},{"role":"assistant","content":""},{"role":"user","content":"Continue"}]}""")
        val projected = AgentResponseOutcome.requestHistory(request)
        assertEquals(4, projected["messages"]!!.jsonArray.size)
        assertEquals(request["model"], projected["model"]); assertEquals(request["max_tokens"], projected["max_tokens"])
        assertEquals(request["messages"]!!.jsonArray[2], projected["messages"]!!.jsonArray[2])
        assertEquals(5, request["messages"]!!.jsonArray.size)
    }
    @Test fun malformedHistoryRemainsForStrictValidation() {
        val request = obj("""{"messages":[null,{"role":"assistant","content":""}]}""")
        assertSame(request, AgentResponseOutcome.requestHistory(request))
    }
    @Test fun repeatedProjectionIsIdempotent() {
        val request = obj("""{"messages":[{"role":"assistant","content":""},{"role":"user","content":"Continue"}]}""")
        val once = AgentResponseOutcome.requestHistory(request)
        assertSame(once, AgentResponseOutcome.requestHistory(once))
    }
    @Test fun consecutiveUsersRemainEncodableAfterLegacyOmission() {
        val request = obj("""{"model":"gemini-test","messages":[{"role":"user","content":"Original intent"},{"role":"assistant","content":""},{"role":"user","content":"Continue"}]}""")
        val encoded = obj(GoogleGenerateContentAdapter.encodeRequest(AgentResponseOutcome.requestHistory(request).toString(), Json))
        val contents = encoded["contents"]!!.jsonArray
        assertTrue(contents.all { it.jsonObject["role"] == JsonPrimitive("user") })
        val texts = contents.flatMap { it.jsonObject["parts"]!!.jsonArray }.map { it.jsonObject["text"]!!.jsonPrimitive.content }
        assertEquals(listOf("Original intent", "Continue"), texts)
        assertFalse(contents.any { it.jsonObject["parts"]!!.jsonArray.isEmpty() })
    }
}
