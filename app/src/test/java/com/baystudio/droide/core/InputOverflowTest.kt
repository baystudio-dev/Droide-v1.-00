package com.baystudio.droide.core

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic error contracts; these fixtures are not captured provider incidents. */
class InputOverflowTest {
    private fun envelope(message: String, extra: String = ""): String =
        """{"error":{"message":${JsonPrimitive(message)}$extra}}"""

    private fun accepts(vararg messages: String) {
        for (message in messages) for (status in listOf(400, 422)) {
            assertTrue("HTTP $status: $message", LlmHttpException(status, message).isContextOverflow())
            assertTrue("JSON HTTP $status: $message", LlmHttpException(status, envelope(message)).isContextOverflow())
        }
    }

    private fun rejects(vararg messages: String) {
        for (message in messages) for (status in listOf(400, 422)) {
            assertFalse("HTTP $status: $message", LlmHttpException(status, message).isContextOverflow())
            assertFalse("JSON HTTP $status: $message", LlmHttpException(status, envelope(message)).isContextOverflow())
        }
    }

    @Test fun explicitContextCodesRemainAuthoritative() {
        for (code in listOf("context_length_exceeded", "context_window_exceeded", "input_too_long", "prompt_too_long")) {
            for (field in listOf("code", "type")) {
                val body = """{"error":{"$field":"$code","param":"max_tokens","message":"Request rejected"}}"""
                assertTrue(body, LlmHttpException(400, body).isContextOverflow())
            }
        }
        accepts("context_length_exceeded")
        assertTrue(LlmHttpException(422, """{"code":"context_length_exceeded","message":"Request rejected"}""").isContextOverflow())
    }

    @Test fun inputAndPromptOverflowAreCompactionCandidates() = accepts(
        "The prompt is too long: 25000 tokens > 20000 maximum.",
        "Prompt too long",
        "Input is too long",
        "Input too long",
        "The input contains too many tokens.",
        "Too many prompt tokens.",
        "The input token count (40000) exceeds the maximum number of tokens allowed (32768).",
        "Input tokens exceed the maximum allowed tokens.",
        "Prompt length exceeds the model's context length.",
    )

    @Test fun actualContextExhaustionRemainsRecoverable() = accepts(
        "Context window exceeded.",
        "Context length has been exceeded.",
        "Request exceeds the model's context.",
        "The request is too large for the model context window.",
        "This model's maximum context length is 8192 tokens. However, you requested 8200 tokens (8100 in messages, 100 in completion).",
    )

    @Test fun combinedPromptAndOutputCanStillBeReducedByCompaction() = accepts(
        "Input tokens + max_tokens exceed the model's context window.",
        "The prompt and output tokens exceed the maximum context length.",
        "Input length and max_tokens exceed context length.",
        "Prompt tokens plus max_output_tokens must be less than the context window.",
        "Input tokens + max_tokens must be <= the context window.",
        "Output tokens + input tokens exceed the context window.",
        "Prompt tokens (8100) + max_tokens (100) exceed the context length.",
    )

    @Test fun outputOnlyParameterValidationCannotCompactHistory() = rejects(
        "max_tokens exceeds the token limit of 4096.",
        "Too many tokens requested for max_output_tokens.",
        "max_completion_tokens: tokens exceed the permitted output token limit.",
        "maxOutputTokens (40000) exceeds the maximum number of tokens allowed (32768).",
        "The output token count (40000) exceeds the maximum number of tokens allowed (32768).",
        "max_tokens exceeds the model's context window; set max_tokens below 4096.",
        "max_output_tokens exceeds the maximum context length. Input was accepted.",
        "Invalid 'max_tokens': too many tokens requested.",
        "max_tokens exceeds the context window. The documentation mentions input and output tokens.",
        "Input was accepted and output tokens exceed the context window.",
        "Output tokens and input tokens are separate; max_tokens exceeds the context window.",
    )

    @Test fun structuredOutputParameterRejectsAmbiguousContextWording() {
        for (param in listOf("max_tokens", "max_output_tokens", "max_completion_tokens", "generationConfig.maxOutputTokens")) {
            val body = envelope("Requested value exceeds the context window.", ""","param":"$param"""")
            assertFalse(body, LlmHttpException(400, body).isContextOverflow())
        }
    }

    @Test fun outputParameterDoesNotSuppressARealInputOverflow() {
        val input = envelope("Prompt is too long.", ""","param":"max_tokens"""")
        val combined = envelope("Input tokens + max_tokens exceed the model context window.", ""","param":"max_tokens"""")
        assertTrue(LlmHttpException(400, input).isContextOverflow())
        assertTrue(LlmHttpException(400, combined).isContextOverflow())
    }

    @Test fun genericTokenWordingDoesNotProveInputOverflow() = rejects(
        "Too many tokens.",
        "Token limit exceeded.",
        "Tokens exceed the maximum.",
        "Input token count is invalid.",
        "Input token count (40000) is within the maximum number of tokens allowed (50000).",
        "Read the documentation for the context window and maximum context length.",
        "Input tokens exceed the daily quota.",
    )

    @Test fun unrelatedJsonFieldsAreNeverCompactionEvidence() {
        for (body in listOf(
            """{"error":{"message":"Invalid model","docs":"context_length_exceeded"}}""",
            """{"error":{"message":"Invalid schema"},"documentation":"prompt is too long"}""",
            """{"error":{"message":"Invalid schema","details":[{"message":"context window exceeded"}]}}""",
            """{"error":{"message":"Invalid schema","param":"context_length_exceeded"}}""",
            """{"error":{"message":"Invalid schema"},"code":"context_length_exceeded"}""",
            """{"custom":{"message":"context window exceeded"}}""",
        )) assertFalse(body, LlmHttpException(400, body).isContextOverflow())
    }

    @Test fun quotedDocumentationAndEchoedValuesAreNotAssertions() = rejects(
        "Unknown error code 'context_length_exceeded'.",
        "Invalid schema: field 'context window exceeded' is not allowed.",
        "Unsupported field \"prompt is too long\".",
        "See `input token count (40000) exceeds the maximum number of tokens allowed (32768)` in the examples.",
        "The user's input was \"prompt is too long\". Invalid model.",
    )

    @Test fun boundedRecognizedPlainAndStructuredMessagesStillWork() {
        for (body in listOf(
            """{"error":"prompt is too long"}""",
            """{"message":"context window exceeded"}""",
            """"input is too long"""",
            """{"error":{"message":"Input is too long","code":400,"status":"INVALID_ARGUMENT"}}""",
        )) assertTrue(body, LlmHttpException(400, body).isContextOverflow())
    }

    @Test fun malformedOrUnrecognizedStructuredBodiesFailClosed() {
        for (body in listOf(
            """{"error":{"message":"prompt is too long"}""",
            """[{"message":"context window exceeded"}]""",
            """{"error":{"message":{"example":"prompt is too long"}}}""",
            """{"error":42,"message":"prompt is too long"}""",
            """{"error":null,"message":"prompt is too long"}""",
            """{"error":{"code":["context_length_exceeded"]}}""",
            "<html>Documentation: context window exceeded</html>",
            "",
        )) assertFalse(body, LlmHttpException(400, body).isContextOverflow())
    }

    @Test fun sizeBoundsExcludeUnboundedOrLateIncidentalEvidence() {
        val late = "x".repeat(4096) + " prompt is too long"
        assertFalse(LlmHttpException(400, late).isContextOverflow())
        assertFalse(LlmHttpException(400, envelope(late)).isContextOverflow())
        val oversized = envelope("Prompt is too long", ""","metadata":"${"x".repeat(64 * 1024)}"""")
        assertFalse(LlmHttpException(400, oversized).isContextOverflow())
    }

    @Test fun statusGuardPreventsAuthenticationRateAndServerErrorsFromCompacting() {
        for (status in listOf(200, 401, 402, 403, 404, 408, 409, 429, 500, 503, 504)) {
            for (body in listOf("prompt is too long", """{"error":{"code":"context_length_exceeded"}}""")) {
                assertFalse("HTTP $status: $body", LlmHttpException(status, body).isContextOverflow())
            }
        }
    }

    @Test fun requestEntityTooLargePreservesExistingSemantics() {
        for (body in listOf("", "request too large", "max_tokens exceeds token limit", "{malformed", "x".repeat(70000))) {
            assertTrue(LlmHttpException(413, body).isContextOverflow())
        }
    }
}
