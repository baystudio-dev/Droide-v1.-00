package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProviderWholePipelineCapacityTest {
    @Test fun everyAccumulatorRejectsManyIndividuallyValidPayloadsAtAggregateCapacity() {
        val consumers = listOf<Pair<(String) -> List<LlmStreamEvent>, String>>(
            LlmClient.StreamAccumulator(Json)::accept to "\"choices\":[]",
            AnthropicMessagesAdapter.StreamAccumulator(Json)::accept to "\"type\":\"ping\"",
            GoogleGenerateContentAdapter.StreamAccumulator(Json)::accept to "\"candidates\":[]",
            OpenAiResponsesAdapter.StreamAccumulator(Json)::accept to "\"type\":\"response.created\"",
        )
        for ((accept, fields) in consumers) {
            val payload = "{$fields,\"padding\":\"${"x".repeat(100000)}\"}"
            val admitted = LlmClient.MAX_RESPONSE_CHARS / (payload.length + 1)
            repeat(admitted) { accept(payload) }
            val failure = assertThrows(IllegalStateException::class.java) { accept(payload) }
            assertTrue(failure.message.orEmpty().contains("response capacity"))
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
        val capacity = ProviderPayloadLimits.AccumulationBudget(8)
        capacity.accept("{}")
        capacity.accept("{}")
        assertThrows(IllegalStateException::class.java) { capacity.accept("{}") }
    }
}
