package com.baystudio.droide.core

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AgentThinkingSignalBaselineTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun signalWithoutText(events: List<LlmStreamEvent>) {
        assertTrue("Explicit reasoning activity needs a content-free phase signal", events.any { it.javaClass.simpleName == "Thinking" })
        assertFalse(events.any { it is LlmStreamEvent.TextDelta })
        assertFalse(events.toString().contains("private payload"))
    }
    @Test fun responsesReasoningStartSignalsActivityWithoutExposingItsPayload() {
        signalWithoutText(OpenAiResponsesAdapter.StreamAccumulator(json).accept("""{"type":"response.output_item.added","output_index":0,"item":{"id":"r1","type":"reasoning","summary":[],"encrypted_content":"private payload"}}"""))
    }
    @Test fun anthropicThinkingStartSignalsActivityWithoutExposingItsPayload() {
        signalWithoutText(AnthropicMessagesAdapter.StreamAccumulator(json).accept("""{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"private payload","signature":"opaque"}}"""))
    }
    @Test fun googleThoughtSignalsActivityWithoutBecomingPublicResponseText() {
        signalWithoutText(GoogleGenerateContentAdapter.StreamAccumulator(json).accept("""{"candidates":[{"content":{"parts":[{"thought":true,"text":"private payload"}]}}]}"""))
    }
    @Test fun compatibleChatReasoningSignalsActivityWithoutBecomingPublicResponseText() {
        signalWithoutText(OpenAiChatStreamAccumulator(json).accept("""{"choices":[{"delta":{"reasoning_content":"private payload"}}]}"""))
    }
}
