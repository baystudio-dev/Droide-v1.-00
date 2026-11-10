package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class ProviderFailureTest {
    @Test fun screenshotFailureIdentifiesToolSchemaAndKeepsTheFieldPath() {
        val body = """{"error":{"code":400,"message":"GenerateContentRequest.tools[0].function_declarations[30].parameters.properties[todos].items: missing field.","status":"INVALID_ARGUMENT"}}"""
        val result = ProviderFailure.describe(400, body)
        assertTrue(result.contains("tool schema"))
        assertTrue(result.contains("properties[todos].items"))
        assertFalse(result.contains("Authentication failed"))
    }

    @Test fun authorizationAndModelErrorsHaveDifferentRecoveryInstructions() {
        assertTrue(ProviderFailure.describe(401, "bad key").contains("Authentication"))
        assertTrue(ProviderFailure.describe(403, "access denied").contains("permissions"))
        assertTrue(ProviderFailure.describe(404, "model not found").contains("Refresh models"))
        assertTrue(ProviderFailure.describe(504, "gateway").contains("timed out"))
    }

    @Test fun ordinaryRequestLimitAndAccountQuotaAreDistinct() {
        assertFalse(ProviderFailure.isQuotaFailure(429, """{"error":{"message":"Rate limit reached","type":"rate_limit_exceeded"}}"""))
        assertFalse(ProviderFailure.isQuotaFailure(429, "RESOURCE_EXHAUSTED: quota exceeded requests per minute"))
        assertTrue(ProviderFailure.describe(429, "too many requests").contains("Wait before retrying"))
        assertTrue(ProviderFailure.isQuotaFailure(429, "insufficient_quota"))
        assertTrue(ProviderFailure.isQuotaFailure(402, "balance required"))
    }

    @Test fun definitiveBillingAndDailyLimitsWinOverIncidentalRateLimitAdvice() {
        val permanentBodies = listOf(
            """{"error":{"type":"insufficient_quota","message":"Rate limit reached; check billing."}}""",
            """{"error":{"type":"billing_error","message":"Rate limit reached."}}""",
            """{"error":{"type":"rate_limit_error","message":"Your monthly spend cap was reached; try reducing requests per minute."}}""",
            """{"error":{"status":"RESOURCE_EXHAUSTED","message":"Quota exceeded. See rate limits.","details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}]}}""",
        )
        permanentBodies.forEach { body ->
            assertTrue(body, ProviderFailure.isQuotaFailure(429, body))
            assertTrue(ProviderFailure.describe(429, body).contains(requireNotNull(ProviderLimitPolicy.diagnose(429, body)).hint))
        }
        assertTrue(ProviderFailure.isQuotaFailure(400, "Organization spend limit reached"))
        assertFalse(ProviderFailure.isQuotaFailure(400, "Invalid tool schema near quota limit field"))
        assertFalse(ProviderFailure.isQuotaFailure(429, "Quota exceeded: GenerateRequestsPerMinutePerProjectPerModel"))
        assertFalse(ProviderFailure.isQuotaFailure(429, "Quota exceeded requests per second"))
        assertFalse(ProviderFailure.isQuotaFailure(429, "Rate limit reached. View rate usage on the billing dashboard."))
    }

    @Test fun secretsAreRemovedIncludingQuotedJsonAndEscapedCredentialMaterial() {
        val secret = "fixture-token-12345"
        assertFalse(ProviderFailure.redact("echo=$secret", listOf(secret)).contains(secret))
        assertFalse(ProviderFailure.redact("""{"api_key":"$secret"}""").contains(secret))
        assertFalse(ProviderFailure.redact("Bearer $secret").contains(secret))
        val multiline = "fixture\ncredential"
        val body = """{"echo":"fixture\ncredential"}"""
        assertFalse(ProviderFailure.redact(body, listOf(multiline)).contains("credential"))
    }

    @Test fun jsonMessagesAreReadableAndHtmlFallbackIsBounded() {
        val json = ProviderFailure.describe(500, """{"error":{"message":"service unavailable"}}""")
        assertTrue(json.endsWith("service unavailable"))
        assertFalse(json.contains("\"error\""))
        assertTrue(ProviderFailure.describe(502, "x".repeat(5000)).length < 700)
    }

    @Test fun continuationSignatureFailuresHaveSpecificRecovery() {
        assertTrue(ProviderFailure.describe(400, "function call missing thought_signature").contains("continuation signature"))
    }

    @Test fun toolSchemaErrorCannotTriggerContextCompactionRetry() {
        assertFalse(LlmHttpException(400, "function_declarations parameters schema: missing items").isContextOverflow())
        assertFalse(LlmHttpException(401, "invalid key context window").isContextOverflow())
        assertTrue(LlmHttpException(400, "context_length_exceeded").isContextOverflow())
        assertTrue(LlmHttpException(413, "request too large").isContextOverflow())
    }

    @Test fun resolvedOAuthAndNativeApiCredentialsAreRedactedFromResponseEchoes() {
        val headers = mapOf("Authorization" to "Bearer resolved-token-fixture", "x-goog-api-key" to "google-key-fixture", "api-key" to "azure-key-fixture", "x-api-key" to "anthropic-key-fixture")
        val secrets = ProviderFailure.requestSecrets(headers::get)
        val safe = ProviderFailure.redact("resolved-token-fixture google-key-fixture azure-key-fixture anthropic-key-fixture", secrets)
        assertFalse(safe.contains("fixture"))
        assertTrue(safe.contains("[redacted]"))
    }
}
