package com.baystudio.droide.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Reproduces the reported HTTP 200 keepalive failure through the production HTTP/SSE client. */
class ProviderHeartbeatTransportTest {
    @Test(timeout = 10000) fun responsesGatewayKeepaliveDoesNotAbortAnHttp200Turn() {
        withProviderStreamFixture(ProviderWireProtocol.OPENAI_RESPONSES) { fixture ->
            fixture.response = { _, _, _ -> fixture.event("""{"type":"keepalive"}""") + fixture.prefix() + finish(fixture) }
            val result = runBlocking { withTimeout(4000) { fixture.stream(mutableListOf()) } }
            assertTrue(result.responseBody.contains("public partial"))
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test(timeout = 30000) fun interleavedHeartbeatsAndProxyFlushesPreserveAnswersAndCompleteToolCalls() {
        ProviderWireProtocol.entries.forEach { protocol -> withProviderStreamFixture(protocol) { fixture ->
            val heartbeat = controls(fixture)
            fixture.response = { _, _, _ -> heartbeat +
                fixture.prefix().replace("\n\n", "\n\n$heartbeat") + finish(fixture) + heartbeat }
            val events = mutableListOf<LlmStreamEvent>()
            val result = runBlocking { withTimeout(4000) { fixture.stream(events) } }
            val choice = Json.parseToJsonElement(result.responseBody).jsonObject["choices"]!!.jsonArray.single().jsonObject
            val message = choice["message"]!!.jsonObject
            assertEquals(JsonPrimitive("tool_calls"), choice["finish_reason"])
            assertEquals(JsonPrimitive("public partial"), message["content"])
            val call = message["tool_calls"]!!.jsonArray.single().jsonObject
            assertEquals(JsonPrimitive("pending-call"), call["id"])
            assertEquals(JsonPrimitive("write_file"), call["function"]!!.jsonObject["name"])
            val arguments = Json.parseToJsonElement(call["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content).jsonObject
            assertEquals(JsonPrimitive("pending.txt"), arguments["path"])
            assertEquals("public partial", events.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString("") { it.text })
            assertFalse(events.any { it is LlmStreamEvent.RetryScheduled })
            assertEquals(1, fixture.requests.size)
        } }
    }

    @Test(timeout = 30000) fun heartbeatThenEofCannotFinishOrReplayPartialTools() {
        ProviderWireProtocol.entries.forEach { protocol -> withProviderStreamFixture(protocol) { fixture ->
            fixture.response = { _, _, _ -> fixture.prefix() + controls(fixture) }
            val events = mutableListOf<LlmStreamEvent>()
            assertThrows(LlmProviderStreamException::class.java) { runBlocking { withTimeout(4000) { fixture.stream(events) } } }
            assertEquals("public partial", events.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString("") { it.text })
            assertEquals(1, fixture.requests.size)
            assertFalse(events.any { it is LlmStreamEvent.RetryScheduled })
        } }
    }

    @Test(timeout = 30000) fun heartbeatsNeverHideAProviderErrorOrCauseARetry() {
        ProviderWireProtocol.entries.forEach { protocol -> withProviderStreamFixture(protocol) { fixture ->
            fixture.response = { _, _, _ -> fixture.prefix() + controls(fixture) + fixture.errorEvent() }
            val events = mutableListOf<LlmStreamEvent>()
            val failure = assertThrows(LlmProviderStreamException::class.java) { runBlocking { fixture.stream(events) } }
            assertEquals(ProviderStreamFailureKind.TEMPORARY_SERVICE, failure.kind)
            assertFalse(failure.message.orEmpty().contains(PROVIDER_STREAM_SECRET))
            assertEquals(1, fixture.requests.size)
            assertFalse(events.any { it is LlmStreamEvent.RetryScheduled })
        } }
    }

    @Test(timeout = 10000) fun errorDisguisedAsAResponsesHeartbeatIsStillTypedAndRedacted() {
        withProviderStreamFixture(ProviderWireProtocol.OPENAI_RESPONSES) { fixture ->
            fixture.response = { _, _, _ -> fixture.event("""{"type":"keepalive","error":${fixture.error}}""") }
            val failure = assertThrows(LlmProviderStreamException::class.java) { runBlocking { fixture.stream(mutableListOf()) } }
            assertEquals(ProviderStreamFailureKind.TEMPORARY_SERVICE, failure.kind)
            assertFalse(failure.message.orEmpty().contains(PROVIDER_STREAM_SECRET))
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test(timeout = 30000) fun heartbeatOnlyAndNullBufferedBodiesCannotInventAnAnswer() {
        ProviderWireProtocol.entries.forEach { protocol -> withProviderStreamFixture(protocol) { fixture ->
            fixture.response = { _, _, _ -> controls(fixture) }
            assertThrows(LlmProviderStreamException::class.java) { runBlocking { fixture.stream(mutableListOf()) } }
            fixture.forceBufferedResponse = true
            fixture.response = { _, _, _ -> "null" }
            assertThrows(LlmProviderStreamException::class.java) { runBlocking { fixture.stream(mutableListOf()) } }
            assertEquals(2, fixture.requests.size)
        } }
    }

    private fun controls(fixture: ProviderStreamFixture) =
        ": ordinary SSE comment\n\n" + fixture.event("null") + fixture.event(": keepalive") +
            listOf("keepalive", "ping", "heartbeat").joinToString("") {
                fixture.event("""{"type":"$it","timestamp":1794200000,"sequence_number":42}""")
            }

    private fun finish(fixture: ProviderStreamFixture): String = when (fixture.protocol) {
        ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> fixture.event("""{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"}"}}]},"finish_reason":"tool_calls"}]}""") + fixture.event("[DONE]")
        ProviderWireProtocol.OPENAI_RESPONSES -> fixture.event(Json.parseToJsonElement("""{"type":"response.completed","response":{"status":"completed","output":[
            {"type":"message","id":"m1","role":"assistant","content":[{"type":"output_text","text":"public partial"}]},
            {"type":"function_call","id":"item1","call_id":"pending-call","name":"write_file","arguments":"{\"path\":\"pending.txt\"}","status":"completed"}
        ]}}""").toString())
        ProviderWireProtocol.ANTHROPIC_MESSAGES ->
            fixture.event("""{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"}"}}""") +
                fixture.event("""{"type":"content_block_stop","index":1}""") +
                fixture.event("""{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""") + fixture.event("""{"type":"message_stop"}""")
        ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> fixture.event("""{"candidates":[{"finishReason":"STOP"}]}""")
    }
}
