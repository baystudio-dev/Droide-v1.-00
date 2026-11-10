package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Native usage normalization only: no provider calls or newly introduced production API. */
class NativeProviderUsageTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun obj(value: String) = json.parseToJsonElement(value).jsonObject
    private fun usage(response: String) = obj(response).getValue("usage").jsonObject
    private fun promptDetails(usage: JsonObject) = usage.getValue("prompt_tokens_details").jsonObject
    private fun completionDetails(usage: JsonObject) = usage.getValue("completion_tokens_details").jsonObject
    private fun count(usage: JsonObject, field: String, expected: Int) =
        assertEquals(field, JsonPrimitive(expected), usage[field])

    private fun anthropic(native: String) = usage(AnthropicMessagesAdapter.normalizeBuffered(
        """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":$native}""", json,
    ))

    private fun google(native: String) = usage(GoogleGenerateContentAdapter.normalizeBuffered(
        """{"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}],"usageMetadata":$native}""", json,
    ))

    @Test fun anthropicBufferedCacheCategoriesBecomeInclusivePromptExactlyOnce() {
        val value = anthropic("""{"input_tokens":100,"output_tokens":20,"cache_read_input_tokens":900,"cache_creation_input_tokens":200}""")
        count(value, "prompt_tokens", 1_200)
        count(value, "completion_tokens", 20)
        count(value, "total_tokens", 1_220)
        count(promptDetails(value), "cached_tokens", 900)
        count(promptDetails(value), "cache_write_tokens", 200)
        assertFalse("Native uncached input must not be an alternate parser route", "input_tokens" in value)
        assertFalse("output_tokens" in value)
    }

    @Test fun anthropicSplitStreamMergesCumulativeFieldsWithoutAddingSnapshots() {
        val stream = AnthropicMessagesAdapter.StreamAccumulator(json)
        stream.accept("""{"type":"message_start","message":{"usage":{"input_tokens":100,"output_tokens":1,"cache_read_input_tokens":900,"cache_creation_input_tokens":200}}}""")
        stream.accept("""{"type":"message_delta","delta":{},"usage":{"output_tokens":5}}""")
        stream.accept("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":20}}""")
        val value = usage(stream.completedResponse())
        count(value, "prompt_tokens", 1_200)
        count(value, "completion_tokens", 20)
        count(value, "total_tokens", 1_220)
        count(promptDetails(value), "cached_tokens", 900)
        count(promptDetails(value), "cache_write_tokens", 200)
    }

    @Test fun anthropicOutputOnlyStreamDoesNotInventInputOrTotal() {
        val stream = AnthropicMessagesAdapter.StreamAccumulator(json)
        stream.accept("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":7}}""")
        val value = usage(stream.completedResponse())
        count(value, "completion_tokens", 7)
        assertFalse("prompt_tokens" in value)
        assertFalse("Missing native input must not be manufactured as zero", "input_tokens" in value)
        assertFalse("total_tokens" in value)
    }

    @Test fun anthropicEmptyUsageStaysEmptyAndExplicitZeroRemainsKnown() {
        val stream = AnthropicMessagesAdapter.StreamAccumulator(json)
        stream.accept("""{"type":"message_start","message":{"usage":{}}}""")
        stream.accept("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""")
        val empty = usage(stream.completedResponse())
        assertFalse("input_tokens" in empty)
        assertFalse("output_tokens" in empty)
        assertFalse("prompt_tokens" in empty)
        assertFalse("completion_tokens" in empty)
        assertFalse("total_tokens" in empty)
        val zero = anthropic("""{"input_tokens":0,"output_tokens":0}""")
        count(zero, "prompt_tokens", 0)
        count(zero, "completion_tokens", 0)
        count(zero, "total_tokens", 0)
    }

    @Test fun anthropicOmittedOptionalCacheCountsDoNotMakeCompleteUsageUnknown() {
        val value = anthropic("""{"input_tokens":10,"output_tokens":2}""")
        count(value, "prompt_tokens", 10)
        count(value, "completion_tokens", 2)
        count(value, "total_tokens", 12)
        assertFalse("Do not manufacture reported cache details", "prompt_tokens_details" in value)
    }

    @Test fun anthropicNegativeOrOverflowingCacheCannotBecomeValidInclusivePrompt() {
        val negative = anthropic("""{"input_tokens":100,"output_tokens":20,"cache_read_input_tokens":-1}""")
        assertFalse("prompt_tokens" in negative)
        assertFalse("input_tokens" in negative)
        assertFalse("total_tokens" in negative)
        count(promptDetails(negative), "cached_tokens", -1)
        val overflow = anthropic("""{"input_tokens":2147483647,"output_tokens":20,"cache_read_input_tokens":1}""")
        assertFalse("prompt_tokens" in overflow)
        assertFalse("input_tokens" in overflow)
        assertFalse("total_tokens" in overflow)
        val invalid = anthropic("""{"input_tokens":"100","output_tokens":20}""")
        assertFalse("prompt_tokens" in invalid)
        assertFalse("input_tokens" in invalid)
    }

    @Test fun anthropicMissingOutputAndOverflowingTotalAreNotFabricated() {
        val partial = anthropic("""{"input_tokens":10}""")
        count(partial, "prompt_tokens", 10)
        assertFalse("completion_tokens" in partial)
        assertFalse("total_tokens" in partial)
        val overflow = anthropic("""{"input_tokens":2147483647,"output_tokens":1}""")
        count(overflow, "prompt_tokens", Int.MAX_VALUE)
        count(overflow, "completion_tokens", 1)
        assertFalse("A derived total must not overflow", "total_tokens" in overflow)
    }

    @Test fun geminiIncludesThinkingAndToolPromptButDoesNotAddCacheAgain() {
        val value = google("""{"promptTokenCount":100,"toolUsePromptTokenCount":10,"candidatesTokenCount":20,"thoughtsTokenCount":30,"cachedContentTokenCount":40,"totalTokenCount":160}""")
        count(value, "prompt_tokens", 110)
        count(value, "completion_tokens", 50)
        count(value, "total_tokens", 160)
        count(promptDetails(value), "cached_tokens", 40)
        count(promptDetails(value), "tool_use_prompt_tokens", 10)
        count(completionDetails(value), "reasoning_tokens", 30)
    }

    @Test fun geminiStreamRetainsLatestUsageAndAcceptsUsageAfterFinish() {
        val stream = GoogleGenerateContentAdapter.StreamAccumulator(json)
        stream.accept("""{"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":2,"thoughtsTokenCount":3,"totalTokenCount":105}}""")
        stream.accept("""{"usageMetadata":{"promptTokenCount":100,"toolUsePromptTokenCount":10,"candidatesTokenCount":20,"thoughtsTokenCount":30,"cachedContentTokenCount":40,"totalTokenCount":160}}""")
        val value = usage(stream.completedResponse())
        count(value, "prompt_tokens", 110)
        count(value, "completion_tokens", 50)
        count(value, "total_tokens", 160)
        count(completionDetails(value), "reasoning_tokens", 30)
    }

    @Test fun geminiOmittedOptionalCategoriesAndExplicitZeroRemainUsable() {
        val value = google("""{"promptTokenCount":10,"candidatesTokenCount":2,"totalTokenCount":12}""")
        count(value, "prompt_tokens", 10)
        count(value, "completion_tokens", 2)
        count(value, "total_tokens", 12)
        assertFalse("prompt_tokens_details" in value)
        assertFalse("completion_tokens_details" in value)
        val zero = google("""{"promptTokenCount":0,"candidatesTokenCount":0,"totalTokenCount":0}""")
        count(zero, "prompt_tokens", 0)
        count(zero, "completion_tokens", 0)
        count(zero, "total_tokens", 0)
    }

    @Test fun geminiTotalOnlyPreservesReportedTotalWithoutInventingBreakdown() {
        val value = google("""{"totalTokenCount":123}""")
        count(value, "total_tokens", 123)
        assertFalse("prompt_tokens" in value)
        assertFalse("completion_tokens" in value)
        val partial = google("""{"promptTokenCount":10,"totalTokenCount":123}""")
        count(partial, "prompt_tokens", 10)
        count(partial, "total_tokens", 123)
        assertFalse("completion_tokens" in partial)
    }

    @Test fun geminiReportedTotalAndMalformedTotalAreNotSilentlyRecomputed() {
        val mismatch = google("""{"promptTokenCount":100,"toolUsePromptTokenCount":10,"candidatesTokenCount":20,"thoughtsTokenCount":30,"totalTokenCount":999}""")
        count(mismatch, "prompt_tokens", 110)
        count(mismatch, "completion_tokens", 50)
        count(mismatch, "total_tokens", 999)
        val malformed = google("""{"promptTokenCount":10,"candidatesTokenCount":2,"totalTokenCount":-1}""")
        count(malformed, "total_tokens", -1)
    }

    @Test fun geminiInvalidOrOverflowingCategoriesCannotNormalizeAsKnownCoreCounts() {
        val negative = google("""{"promptTokenCount":100,"toolUsePromptTokenCount":-1,"candidatesTokenCount":20,"thoughtsTokenCount":-2,"totalTokenCount":120}""")
        assertFalse("prompt_tokens" in negative)
        assertFalse("completion_tokens" in negative)
        count(promptDetails(negative), "tool_use_prompt_tokens", -1)
        count(completionDetails(negative), "reasoning_tokens", -2)
        count(negative, "total_tokens", 120)
        val overflow = google("""{"promptTokenCount":2147483647,"toolUsePromptTokenCount":1,"candidatesTokenCount":2147483647,"thoughtsTokenCount":1}""")
        assertFalse("prompt_tokens" in overflow)
        assertFalse("completion_tokens" in overflow)
        val invalid = google("""{"promptTokenCount":"100","candidatesTokenCount":20}""")
        assertFalse("prompt_tokens" in invalid)
    }
}
