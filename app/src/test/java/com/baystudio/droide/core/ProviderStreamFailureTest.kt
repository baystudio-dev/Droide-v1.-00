package com.baystudio.droide.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class ProviderStreamFailureTest {
    private fun failure(error: String, secrets: List<String> = emptyList()) = ProviderStreamFailure.fromPayload(
        ProviderErrorPayloadException(Json.parseToJsonElement(error)), secrets,
    )

    @Test fun structuredStreamErrorsDistinguishRateQuotaOverloadAndAccess() {
        val cases = listOf(
            """{"type":"overloaded_error","message":"Overloaded"}""" to ProviderStreamFailureKind.TEMPORARY_SERVICE,
            """{"code":503,"status":"UNAVAILABLE","message":"busy"}""" to ProviderStreamFailureKind.TEMPORARY_SERVICE,
            """{"code":"rate_limit_exceeded","message":"per minute quota exceeded"}""" to ProviderStreamFailureKind.RATE_LIMIT,
            """{"type":"insufficient_quota","message":"Rate limit reached"}""" to ProviderStreamFailureKind.QUOTA,
            """{"code":429,"message":"See rate limits","details":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel"}]}""" to ProviderStreamFailureKind.QUOTA,
            """{"type":"authentication_error","message":"bad credential"}""" to ProviderStreamFailureKind.AUTHENTICATION,
            """{"status":"INVALID_ARGUMENT","message":"bad tool schema"}""" to ProviderStreamFailureKind.INVALID_REQUEST,
            """{"code":"new_vendor_error","message":"unknown failure"}""" to ProviderStreamFailureKind.UNKNOWN,
        )
        cases.forEach { (body, expected) ->
            val error = failure(body)
            assertEquals(body, expected, error.kind)
            assertEquals(expected == ProviderStreamFailureKind.QUOTA, error.isQuotaFailure())
            assertTrue(error.message!!.contains("inside HTTP 200"))
            assertTrue(error.message!!.contains("not replayed"))
        }
    }

    @Test fun errorMessagesAndStoredDetailsRedactCredentialsBeforeClipping() {
        val secret = "resolved-oauth-fixture-" + "s".repeat(700)
        val multiline = "private-fixture\ncredential-line"
        val error = failure(buildJsonObject {
            put("type", "overloaded_error")
            put("message", "echo $secret / $multiline / Bearer fallback-credential-fixture")
        }.toString(), listOf(secret, multiline))
        assertFalse(error.message!!.contains("resolved-oauth"))
        assertFalse(error.message!!.contains("credential-line"))
        assertFalse(error.responseBody.contains("credential-line"))
        assertFalse(error.responseBody.contains(secret.take(500)))
        assertTrue(error.responseBody.contains("[redacted]"))
        val marker = ProviderErrorPayloadException(Json.parseToJsonElement("""{"message":"$secret","output":[{"token":"must-not-retain"}]}"""))
        assertEquals("Provider returned an error payload", marker.message)
        assertFalse(marker.payload.toString().contains("must-not-retain"))
        assertNull(error.cause)
    }

    @Test fun http200ErrorCannotEnterHttpRetryPolicyEvenBeforeFirstText() = runBlocking {
        var calls = 0
        val events = mutableListOf<LlmStreamEvent>()
        val error = failure("""{"type":"overloaded_error","message":"temporary"}""")
        val thrown = runCatching {
            ProviderRetryPolicy.beforeStreamingEvents({ events += it }) {
                calls++
                throw error
            }
        }.exceptionOrNull()
        assertSame(error, thrown)
        assertEquals(1, calls)
        assertTrue(events.isEmpty())
    }

    @Test fun responseErrorNullIsNotAnErrorPayload() {
        val response = """{"error":null,"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}]}"""
        assertTrue(GoogleGenerateContentAdapter.normalizeBuffered(response, Json).contains("ok"))
    }

    @Test fun bedrockResolvedSessionTokenIsRedactedInHttpAndSseFailures() {
        // The STS token differs from both the configured credential bundle and
        // SigV4 Authorization signature. A plain message echo has no regex hint.
        val token = "resolved-bedrock-sts-fixture-" + "a".repeat(600)
        val request = Request.Builder().url("https://bedrock-runtime.us-east-1.amazonaws.com/model/fixture/invoke")
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=fixture-access-key/20261008/us-east-1/bedrock/aws4_request, SignedHeaders=host;x-amz-security-token, Signature=fixture-signature")
            .header("x-amz-security-token", token).build()
        val payload = buildJsonObject { put("type", "authentication_error"); put("message", "Rejected $token") }
        val body = buildJsonObject { put("error", payload) }.toString()

        // Invoke the unchanged production HTTP error boundary with resolved
        // request headers. This test performs no AWS request or paid inference.
        val httpBoundary = LlmClient::class.java.getDeclaredMethod("httpFailure", Integer.TYPE, String::class.java,
            Request::class.java, String::class.java, String::class.java).apply { isAccessible = true }
        val http = httpBoundary.invoke(LlmClient(), 403, body, request, null, null) as LlmHttpException
        assertFalse(http.message!!.contains(token.take(500)))
        assertFalse(http.responseBody.contains(token.take(500)))
        assertTrue(http.responseBody.contains("[redacted]"))

        val marker = assertThrows(ProviderErrorPayloadException::class.java) {
            AnthropicMessagesAdapter.StreamAccumulator(Json).accept(buildJsonObject {
                put("type", "error"); put("error", payload)
            }.toString())
        }
        val stream = ProviderStreamFailure.fromPayload(marker, ProviderFailure.requestSecrets(request::header), 200)
        assertEquals(ProviderStreamFailureKind.AUTHENTICATION, stream.kind)
        assertFalse(stream.message!!.contains(token.take(500)))
        assertFalse(stream.responseBody.contains(token.take(500)))
        assertTrue(stream.responseBody.contains("[redacted]"))
        assertNull(stream.cause)
    }
}
