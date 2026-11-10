package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProviderWholePipelineCapacityTest {
    @Test fun everyAccumulatorRejectsUnboundedRetainedText() {
        val text = "x".repeat(100_000)
        val consumers = listOf<Pair<(String) -> List<LlmStreamEvent>, String>>(
            LlmClient.StreamAccumulator(Json)::accept to """{"choices":[{"delta":{"content":"$text"}}]}""",
            AnthropicMessagesAdapter.StreamAccumulator(Json)::accept to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$text"}}""",
            GoogleGenerateContentAdapter.StreamAccumulator(Json)::accept to """{"candidates":[{"content":{"parts":[{"text":"$text"}]}}]}""",
            OpenAiResponsesAdapter.StreamAccumulator(Json)::accept to """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"$text"}""",
        )
        for ((accept, payload) in consumers) {
            val failure = assertThrows(IllegalStateException::class.java) { repeat(100) { accept(payload) } }
            assertTrue(failure.message.orEmpty().contains("capacity"))
        }
    }

    @Test fun directAdapterAndRequestEntryPointsRejectDeepJsonBeforeRecursiveParsing() {
        val deep = "[".repeat(ProviderPayloadLimits.MAX_JSON_DEPTH + 1) + "0" + "]".repeat(ProviderPayloadLimits.MAX_JSON_DEPTH + 1)
        val decoders = listOf<(String) -> Any>(
            { ProviderModelRequestIdentity.canonicalize(it, Json) },
            { AnthropicMessagesAdapter.normalizeBuffered(it, Json) },
            { GoogleGenerateContentAdapter.normalizeBuffered(it, Json) },
            { OpenAiResponsesAdapter.normalizeBuffered(it, Json) },
            { LlmClient.StreamAccumulator(Json).accept(it) },
            { AnthropicMessagesAdapter.StreamAccumulator(Json).accept(it) },
            { GoogleGenerateContentAdapter.StreamAccumulator(Json).accept(it) },
            { OpenAiResponsesAdapter.StreamAccumulator(Json).accept(it) },
        )
        for (decode in decoders) {
            val failure = assertThrows(IllegalStateException::class.java) { decode(deep) }
            assertTrue(failure.message.orEmpty().contains("nesting"))
        }
    }

    @Test fun requestAndResponseCapacityAreIndependentAndCountersUseLongArithmetic() {
        val requestSized = " ".repeat(LlmClient.MAX_RESPONSE_CHARS + 1)
        assertSame(requestSized, ProviderPayloadLimits.checkedRequest(requestSized))
        assertThrows(IllegalStateException::class.java) { ProviderPayloadLimits.checkedJson(requestSized) }
        val capacity = ProviderPayloadLimits.RetainedBudget(8)
        capacity.reserve(3)
        capacity.reserve(3)
        assertThrows(IllegalStateException::class.java) { capacity.reserve(3) }
        assertThrows(IllegalStateException::class.java) { capacity.reserve(Long.MAX_VALUE) }
        capacity.reserve(2)
        assertThrows(IllegalStateException::class.java) { capacity.reserve(1) }
    }
}
