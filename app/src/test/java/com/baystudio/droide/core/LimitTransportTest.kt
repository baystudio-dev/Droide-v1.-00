package com.baystudio.droide.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real loopback HTTP-200 failures through all production wire adapters. Authored, not run. */
class LimitTransportTest {
    @Test(timeout = 120_000)
    fun generic429IsANeutralRequestLimitAcrossStreamingAndBufferedTransports() {
        everyTransport { fixture, mode ->
            fixture.error = privateError("""{"code":429,"type":"rate_limit_error","message":"Quota exceeded"}""")
            val failure = failedOnce(fixture, mode)

            assertEquals(429, failure.logicalStatus)
            assertEquals(ProviderStreamFailureKind.RATE_LIMIT, failure.kind)
            assertFalse(failure.isQuotaFailure())
            assertDiagnosis(ProviderLimitDiagnosis.REQUEST_LIMIT, failure)
            assertTrue(failure.message!!.contains("remaining credit is unknown"))
            assertFalse(failure.message!!.contains(ProviderLimitDiagnosis.CREDIT.hint))
        }
    }

    @Test(timeout = 120_000)
    fun anthropicNestedSpendCodeOverridesGeneric429AndMonthlyWording() {
        everyTransport { fixture, mode ->
            fixture.error = privateError("""{"code":429,"type":"rate_limit_error","message":"Monthly API usage threshold reached","details":{"error_code":"enforced_spend_limit_reached"}}""")
            val failure = failedOnce(fixture, mode)

            assertEquals(429, failure.logicalStatus)
            assertEquals(ProviderStreamFailureKind.QUOTA, failure.kind)
            assertTrue(failure.isQuotaFailure())
            assertDiagnosis(ProviderLimitDiagnosis.SPEND, failure)
            assertTrue(failure.message!!.contains("Credit may still remain"))
            assertFalse(failure.message!!.contains(ProviderLimitDiagnosis.CREDIT.hint))
            val retained = Json.parseToJsonElement(failure.responseBody).jsonObject["error"]!!.jsonObject
            assertEquals("enforced_spend_limit_reached", retained["details"]!!.jsonObject["error_code"]!!.jsonPrimitive.content)
        }
    }

    @Test(timeout = 120_000)
    fun openRouter402RetainsOnlyBudgetIdentityAndDistinguishesActualCreditEvidence() {
        val cases = listOf(
            ProviderLimitDiagnosis.IN_FLIGHT_BUDGET to """{"code":402,"message":"Request cannot start","metadata":{"limit_source":"openrouter_in_flight_budget","reason":"in_flight_budget_exhausted"}}""",
            ProviderLimitDiagnosis.CREDIT to """{"code":402,"type":"insufficient_credits","message":"Request cannot start","metadata":{"limit_source":"openrouter_credits","reason":"insufficient_credits"}}""",
        )
        cases.forEach { (diagnosis, raw) ->
            everyTransport { fixture, mode ->
                fixture.error = privateError(raw)
                val failure = failedOnce(fixture, mode)

                assertEquals(402, failure.logicalStatus)
                assertEquals(ProviderStreamFailureKind.QUOTA, failure.kind)
                assertDiagnosis(diagnosis, failure)
                val originalMetadata = Json.parseToJsonElement(raw).jsonObject["metadata"]!!.jsonObject
                val retainedMetadata = Json.parseToJsonElement(failure.responseBody).jsonObject["error"]!!.jsonObject["metadata"]!!.jsonObject
                assertEquals(setOf("limit_source", "reason"), retainedMetadata.keys)
                assertEquals(originalMetadata, retainedMetadata)
                if (diagnosis == ProviderLimitDiagnosis.IN_FLIGHT_BUDGET) {
                    assertTrue(failure.message!!.contains("Credit may remain"))
                    assertFalse(failure.message!!.contains(ProviderLimitDiagnosis.CREDIT.hint))
                } else {
                    assertTrue(failure.message!!.contains("Droide has not measured its balance"))
                    assertFalse(failure.message!!.contains(ProviderLimitDiagnosis.IN_FLIGHT_BUDGET.hint))
                }
            }
        }
    }

    @Test(timeout = 120_000)
    fun explicitAuthenticationAndPermissionOverrideLimitCodesAndBudgetMetadata() {
        listOf("authentication_error" to 401, "permission_denied" to 403).forEach { (type, accessStatus) ->
            everyTransport { fixture, mode ->
                fixture.error = privateError("""{"code":429,"type":"$type","message":"See billing and quota documentation","details":{"error_code":"enforced_spend_limit_reached"},"metadata":{"limit_source":"openrouter_workspace_budget","reason":"in_flight_budget_exhausted"}}""")
                val failure = failedOnce(fixture, mode)

                assertEquals(accessStatus, failure.logicalStatus)
                assertEquals(ProviderStreamFailureKind.AUTHENTICATION, failure.kind)
                assertFalse(failure.isQuotaFailure())
                assertNull(ProviderLimitPolicy.diagnose(failure.logicalStatus ?: 0, failure.responseBody))
                assertNull(ProviderFailure.limitWarning(failure))
                assertTrue(failure.message!!.contains("Provider denied authentication or access"))
                assertFalse(failure.message!!.contains(ProviderLimitDiagnosis.CREDIT.hint))
                assertFalse(failure.message!!.contains(ProviderLimitDiagnosis.SPEND.hint))
            }
        }
    }

    private enum class ResponseMode { SSE, BUFFERED, BUFFERED_TO_STREAM }

    private fun everyTransport(block: (ProviderStreamFixture, ResponseMode) -> Unit) {
        ProviderWireProtocol.entries.forEach { protocol ->
            ResponseMode.entries.forEach { mode ->
                withProviderStreamFixture(protocol) { fixture ->
                    fixture.forceBufferedResponse = mode != ResponseMode.SSE
                    block(fixture, mode)
                }
            }
        }
    }

    private fun failedOnce(fixture: ProviderStreamFixture, mode: ResponseMode): LlmProviderStreamException {
        val label = "${fixture.protocol}/$mode"
        val events = mutableListOf<LlmStreamEvent>()
        val failure = assertThrows(LlmProviderStreamException::class.java) {
            runBlocking {
                withTimeout(4_000) {
                    if (mode == ResponseMode.BUFFERED) fixture.buffered() else fixture.stream(events)
                }
            }
        }
        assertEquals("HTTP-200 envelope must remain typed: $label", 200, failure.httpStatus)
        assertEquals("Failed HTTP-200 generation must never replay: $label", 1, fixture.requests.size)
        assertFalse("No scheduled retry for failed HTTP-200 generation: $label", events.any { it is LlmStreamEvent.RetryScheduled })
        assertTrue("Error before text cannot deliver successful output: $label", events.isEmpty())
        assertNull("An unsafe upstream cause must not escape: $label", failure.cause)
        val publicText = listOfNotNull(
            failure.message,
            failure.responseBody,
            ProviderFailure.limitWarning(failure),
            ProviderFailure.describe(failure.logicalStatus ?: 0, failure.responseBody),
        ).joinToString("\n")
        listOf(PROVIDER_STREAM_SECRET, PRIVATE_METADATA, PRIVATE_ACCOUNT, PRIVATE_OUTPUT).forEach { privateValue ->
            assertFalse("Private provider data escaped: $label", publicText.contains(privateValue))
        }
        val retained = Json.parseToJsonElement(failure.responseBody).jsonObject["error"]!!.jsonObject
        assertFalse(retained.containsKey("private_output"))
        val metadata = retained["metadata"]!!.jsonObject
        assertTrue("Only budget identity metadata may survive: $label", metadata.keys.all { it in setOf("limit_source", "reason") })
        assertTrue("Echoed resolved credential must be visibly redacted: $label", retained["message"]!!.jsonPrimitive.content.contains("[redacted]"))
        return failure
    }

    private fun assertDiagnosis(diagnosis: ProviderLimitDiagnosis, failure: LlmProviderStreamException) {
        assertEquals(diagnosis, ProviderLimitPolicy.diagnose(failure.logicalStatus ?: 0, failure.responseBody))
        assertEquals(diagnosis.hint, ProviderFailure.limitWarning(failure))
        assertTrue(failure.message!!.contains(diagnosis.hint))
    }

    private fun privateError(raw: String): String {
        val fields = Json.parseToJsonElement(raw).jsonObject
        return JsonObject(fields + mapOf(
            "message" to JsonPrimitive(fields["message"]!!.jsonPrimitive.content + "; echo " + PROVIDER_STREAM_SECRET),
            "private_output" to JsonPrimitive(PRIVATE_OUTPUT),
            "metadata" to JsonObject((fields["metadata"] as? JsonObject).orEmpty() + mapOf(
                "credential" to JsonPrimitive(PROVIDER_STREAM_SECRET),
                "private_debug" to JsonPrimitive(PRIVATE_METADATA),
                "account_id" to JsonPrimitive(PRIVATE_ACCOUNT),
                "headers" to buildJsonObject { put("authorization", "Bearer $PROVIDER_STREAM_SECRET") },
            )),
        )).toString()
    }

    private companion object {
        const val PRIVATE_METADATA = "r57-private-debug-metadata-marker"
        const val PRIVATE_ACCOUNT = "r57-private-account-identifier-marker"
        const val PRIVATE_OUTPUT = "r57-private-native-output-marker"
    }
}
