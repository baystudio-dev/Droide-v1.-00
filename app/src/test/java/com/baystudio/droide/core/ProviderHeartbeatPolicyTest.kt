package com.baystudio.droide.core

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ProviderHeartbeatPolicyTest {
    @Test fun heartbeatsDoNotEmitOrRetainContentOrGrantCompletion() {
        val stream = OpenAiResponsesAdapter.StreamAccumulator(Json)
        val revision = stream.retainedRevision
        repeat(10000) {
            assertTrue(stream.accept("""{"type":"keepalive","timestamp":$it}""").isEmpty())
        }
        assertEquals(revision, stream.retainedRevision)
        assertThrows(IllegalStateException::class.java) { stream.completedResponse() }
    }

    @Test fun controlNamesNeverSwallowResponsePayloadsOrUnknownEvents() {
        for (payload in listOf(
            """{"type":"keepalive","delta":"lost text"}""",
            """{"type":"ping","response":{"status":"completed","output":[]}}""",
            """{"type":"heartbeat","tool_calls":[]}""",
        )) assertThrows(IllegalArgumentException::class.java) { OpenAiResponsesAdapter.StreamAccumulator(Json).accept(payload) }
        assertThrows(IllegalStateException::class.java) { OpenAiResponsesAdapter.StreamAccumulator(Json).accept("""{"type":"new.unsupported.event"}""") }
        assertThrows(IllegalStateException::class.java) { OpenAiResponsesAdapter.StreamAccumulator(Json).accept("{broken-json}") }
    }

    @Test fun completedResponseSurvivesTrailingHeartbeatWithoutAllowingNewContent() {
        val stream = OpenAiResponsesAdapter.StreamAccumulator(Json)
        stream.accept("""{"type":"response.completed","response":{"status":"completed","output":[]}}""")
        val completed = stream.completedResponse()
        listOf("ping", "keepalive", "heartbeat").forEach { assertTrue(stream.accept("""{"type":"$it"}""").isEmpty()) }
        assertEquals(completed, stream.completedResponse())
        assertThrows(IllegalArgumentException::class.java) { stream.accept("""{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"late"}""") }
    }

    @Test fun emptyProxyFramesAreIgnoredExactlyWithoutChangingRealMessagesOrDone() {
        val framing = ProviderSseFraming(256)
        for (empty in listOf("null", " null ", ": keepalive", "", "   ")) {
            assertNull(framing.line("data: $empty")); assertNull(framing.line(""))
        }
        for (actual in listOf("[DONE]", "{broken-json}", "{\"type\":\"keepalive\"}", "{\"text\":\": keepalive\"}", ": keepalive with unexpected content")) {
            assertNull(framing.line("data: $actual")); assertEquals(actual, framing.line(""))
        }
        assertThrows(IllegalStateException::class.java) { framing.line("data: " + " ".repeat(300)) }
    }
}
