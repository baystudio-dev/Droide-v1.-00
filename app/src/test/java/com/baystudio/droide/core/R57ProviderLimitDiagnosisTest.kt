package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class R57ProviderLimitDiagnosisTest {
    private fun body(code:String,message:String="fixture")=buildJsonObject{put("error",buildJsonObject{put("code",code);put("message",message)})}.toString()
    private fun stream(raw:String)=ProviderStreamFailure.fromPayload(ProviderErrorPayloadException(Json.parseToJsonElement(raw)),emptyList())
    @Test fun genericRequestLimitsNeverEstablishEmptyCredit() {
        for(raw in listOf("", "RESOURCE_EXHAUSTED", "Quota exceeded", body("rate_limit_error"),body("RESOURCE_EXHAUSTED"))) {
            assertEquals(ProviderLimitDiagnosis.REQUEST_LIMIT,ProviderLimitPolicy.diagnose(429,raw))
            assertFalse(ProviderFailure.isQuotaFailure(429,raw))
            assertTrue(ProviderFailure.describe(429,raw).contains("remaining credit is unknown"))
        }
    }
    @Test fun explicitAccountQuotaIsSeparateFromCreditReport() {
        val quota=ProviderLimitPolicy.diagnose(429,body("insufficient_quota"))!!
        assertEquals(ProviderLimitDiagnosis.ACCOUNT_QUOTA,quota);assertTrue(quota.blocksImmediateRetry)
        assertEquals(ProviderLimitDiagnosis.ACCOUNT_QUOTA,ProviderLimitPolicy.diagnose(429,"""{"error":"insufficient_quota"}"""))
        assertTrue(quota.hint.contains("does not establish an empty credit balance"))
        val credit=ProviderLimitPolicy.diagnose(402,body("credit_balance_exhausted"))!!
        assertEquals(ProviderLimitDiagnosis.CREDIT,credit);assertTrue(credit.hint.startsWith("Provider reports"))
    }
    @Test fun structuredAccountProjectAndWorkspaceBudgetCodesAreNotBalances() {
        for(code in listOf("organization_spend_limit_exceeded","project_spend_limit_exceeded","billing_hard_limit_reached")) {
            val d=ProviderLimitPolicy.diagnose(429,body(code))!!;assertEquals(ProviderLimitDiagnosis.SPEND,d);assertTrue(d.hint.contains("Credit may still remain"))
        }
        val raw="""{"error":{"type":"rate_limit_error","message":"Monthly API usage threshold reached","details":{"error_code":"enforced_spend_limit_reached"}}}"""
        assertEquals(ProviderLimitDiagnosis.SPEND,ProviderLimitPolicy.diagnose(429,raw))
        assertNull(ProviderRetryPolicy.nextDelayMs(429,raw,"1",0,0))
    }
    @Test fun googleDailyQuotaAndShortRetryLimitAreDistinct() {
        val daily="""{"error":{"status":"RESOURCE_EXHAUSTED","message":"Quota exceeded","details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel"}]},{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"1s"}]}}"""
        assertEquals(ProviderLimitDiagnosis.PERIOD_QUOTA,ProviderLimitPolicy.diagnose(429,daily));assertNull(ProviderRetryPolicy.nextDelayMs(429,daily,null,0,0))
        val retry="""{"error":{"status":"RESOURCE_EXHAUSTED","message":"Quota exceeded","details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"1s"}]}}"""
        assertEquals(1000L,ProviderRetryPolicy.nextDelayMs(429,retry,null,0,0));assertEquals(ProviderLimitDiagnosis.REQUEST_LIMIT,ProviderLimitPolicy.diagnose(429,retry))
    }
    @Test fun periodicTextDoesNotBecomeFinancialAssertion() {
        for(message in listOf("daily quota reached","monthly limit exceeded","quota exceeded per day","Quota exceeded: GenerateTokensPerMonthPerProject")) {
            val d=ProviderLimitPolicy.diagnose(429,body("RESOURCE_EXHAUSTED",message))!!;assertEquals(ProviderLimitDiagnosis.PERIOD_QUOTA,d);assertTrue(d.hint.contains("separate from remaining credit"))
        }
    }
    @Test fun openRouterInFlightBudgetIsRetainedWithoutEnabling402Retries() {
        for(reason in listOf("in_flight_budget_exhausted")) {
            val raw="""{"code":402,"message":"request budget","metadata":{"limit_source":"openrouter_in_flight_budget","reason":"$reason","credential":"MUST_NOT_RETAIN"}}"""
            val e=stream(raw);assertEquals(ProviderStreamFailureKind.QUOTA,e.kind)
            assertTrue(e.message!!.contains("in-flight request spending budget"));assertFalse(e.responseBody.contains("MUST_NOT_RETAIN"))
            assertNull(ProviderRetryPolicy.nextDelayMs(402,e.responseBody,"1",0,0))
            assertEquals(ProviderFailure.limitWarning(LlmHttpException(402,e.responseBody)),ProviderFailure.limitWarning(e))
        }
    }
    @Test fun unrecognizedBudgetMetadataStaysUnknownPaymentDetails() {
        val raw="""{"error":{"metadata":{"limit_source":"custom","reason":"in_flight_budget_exhausted"}}}"""
        assertEquals(ProviderLimitDiagnosis.PAYMENT_REQUIRED,ProviderLimitPolicy.diagnose(402,raw))
        assertFalse(ProviderFailure.describe(402,raw).contains("insufficient credit"))
    }
    @Test fun authPermissionAndServiceErrorsIgnoreIncidentalFinancialText() {
        for(status in listOf(401,403,500,503)) {
            assertNull(ProviderLimitPolicy.diagnose(status,body("unknown","Read billing and daily quota docs")))
            assertFalse(ProviderFailure.describe(status,body("unknown","Read billing and daily quota docs")).substringBefore('\n').contains("quota"))
        }
        val e=stream("""{"code":429,"type":"authentication_error","message":"see quota/billing docs"}""")
        assertEquals(ProviderStreamFailureKind.AUTHENTICATION,e.kind);assertNull(ProviderFailure.limitWarning(e))
        assertTrue(ProviderFailure.describe(429,e.responseBody).contains("Authentication failed"))
        assertNull(ProviderRetryPolicy.nextDelayMs(429,e.responseBody,"1",0,0))
    }
    @Test fun unrelatedJsonFieldsCannotClassifyAQuota() {
        for(raw in listOf("""{"error":{"code":"invalid_request_error","message":"schema invalid"},"documentation":"insufficient_quota billing daily quota"}""",
            """{"error":{"message":"unknown","param":"insufficient_quota","metadata":{"help":"monthly limit"}}}""")) {
            assertNull(ProviderLimitPolicy.diagnose(400,raw));assertEquals(ProviderLimitDiagnosis.REQUEST_LIMIT,ProviderLimitPolicy.diagnose(429,raw))
        }
    }
    @Test fun malformedCodesAndUnknownCustomErrorsAreNotGuessedFinancial() {
        for(raw in listOf("{", "[]", """{"error":{"code":true,"type":[],"message":123}}""", "x".repeat(70_000))) {
            assertNull(ProviderLimitPolicy.diagnose(400,raw));assertEquals(ProviderLimitDiagnosis.REQUEST_LIMIT,ProviderLimitPolicy.diagnose(429,raw))
        }
        val e=stream("""{"type":"custom_vendor_error","message":"quota field validation failed"}""");assertEquals(ProviderStreamFailureKind.UNKNOWN,e.kind);assertNull(ProviderFailure.limitWarning(e))
    }
    @Test fun warningRequiresTypedProviderFailureInsteadOfAnyQuotaWord() {
        assertNull(ProviderFailure.limitWarning(IllegalStateException("quota/billing field invalid")))
        assertNull(ProviderFailure.limitWarning(CancellationException("billing")))
        val e=LlmHttpException(429,body("rate_limit_error"));assertEquals(ProviderLimitDiagnosis.REQUEST_LIMIT.hint,ProviderFailure.limitWarning(e))
    }
    @Test fun normalizedOutputLimitsNeverBecomeAccountWarnings() {
        for(reason in listOf("MAX_TOKENS","max_tokens","max_output_tokens","length")) {
            assertNull(ProviderLimitPolicy.diagnose(400,body(reason,"Output token limit exceeded")))
            assertNull(ProviderFailure.limitWarning(IllegalStateException(reason)))
        }
    }
    @Test fun typedStreamAndHttpHintsAgreeWithoutCreditConflation() {
        for(code in listOf("rate_limit_error","insufficient_quota","credit_balance_exhausted","project_spend_limit_exceeded")) {
            val e=stream("""{"code":"$code","message":"fixture"}""")
            val d=ProviderLimitPolicy.diagnose(e.logicalStatus?:0,e.responseBody)!!
            assertTrue(e.message!!.contains(d.hint));assertTrue(ProviderFailure.describe(e.logicalStatus?:0,e.responseBody).contains(d.hint))
        }
    }
    @Test fun failedHttp200GenerationStillCannotTriggerAutomaticRetry()=runBlocking {
        val e=stream("""{"type":"rate_limit_error","message":"quota exceeded"}""");var calls=0
        val actual=runCatching{ProviderRetryPolicy.beforeStreamingEvents({}){calls++;throw e}}.exceptionOrNull()
        assertSame(e,actual);assertEquals(1,calls)
    }
    @Test fun limitDiagnosisCannotExposeEchoedCredentialsInWarning() {
        val e=ProviderStreamFailure.fromPayload(ProviderErrorPayloadException(Json.parseToJsonElement("""{"type":"insufficient_quota","message":"echo credential-fixture-long"}""")),listOf("credential-fixture-long"))
        assertFalse(e.message!!.contains("credential-fixture-long"));assertFalse(e.responseBody.contains("credential-fixture-long"));assertFalse(ProviderFailure.limitWarning(e)!!.contains("fixture"))
    }
    @Test fun routerSingleRequestBudgetAndAdmin403CapsAreDistinctFromBalance() {
        val costly="""{"error":{"code":402,"metadata":{"limit_source":"openrouter_credits","reason":"weight_exceeds_budget"}}}"""
        assertEquals(ProviderLimitDiagnosis.REQUEST_BUDGET,ProviderLimitPolicy.diagnose(402,costly));assertNull(ProviderRetryPolicy.nextDelayMs(402,costly,"1",0,0))
        for(source in listOf("openrouter_guardrail_budget","openrouter_workspace_budget","openrouter_key_limit")) {
            val raw="""{"code":403,"message":"Budget limit exceeded","metadata":{"limit_source":"$source"}}"""
            val e=stream(raw);assertEquals(ProviderStreamFailureKind.QUOTA,e.kind);assertTrue(e.message!!.contains(ProviderLimitDiagnosis.SPEND.hint))
            assertTrue(ProviderFailure.describe(403,e.responseBody).contains(ProviderLimitDiagnosis.SPEND.hint));assertEquals(ProviderLimitDiagnosis.SPEND.hint,ProviderFailure.limitWarning(e))
        }
    }
    @Test fun organizationAssignedUsageLimitIsNotAUserConfiguredSpendingCap() {
        assertEquals(ProviderLimitDiagnosis.ACCOUNT_QUOTA,ProviderLimitPolicy.diagnose(429,body("organization_usage_limit_exceeded")))
    }
    @Test fun permissionCodeOverrides429AndBudgetMetadataWithoutQuotaWarning() {
        val raw="""{"code":429,"type":"permission_denied","message":"See billing limit docs","metadata":{"limit_source":"openrouter_workspace_budget"}}"""
        val e=stream(raw);assertEquals(ProviderStreamFailureKind.AUTHENTICATION,e.kind);assertNull(ProviderFailure.limitWarning(e))
        assertTrue(ProviderFailure.describe(429,e.responseBody).contains("permissions"));assertNull(ProviderRetryPolicy.nextDelayMs(429,e.responseBody,"1",0,0))
    }

    @Test fun allRecognizedAccessEvidenceFormsBlockRetryWithoutQuotaWarning() {
        for(raw in listOf("permission_denied: see billing", "authentication_error retry later",
            """{"error":{"details":{"error_code":"permission_denied"},"message":"rate limit billing docs"}}""",
            """{"error":{"details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"UNAUTHENTICATED"}],"message":"rate limit"}}""")) {
            assertNotNull(ProviderLimitPolicy.accessStatus(429,raw));assertNull(ProviderLimitPolicy.diagnose(429,raw))
            assertNull(ProviderRetryPolicy.nextDelayMs(429,raw,"1",0,0));assertNull(ProviderFailure.limitWarning(LlmHttpException(429,raw)))
            assertFalse(ProviderFailure.describe(429,raw).substringBefore('\n').contains("rate or quota"))
        }
    }

    @Test fun NegatedLimitsAndHelpTextCannotCertifyAPeriodOrSpendingLimit() {
        for(message in listOf("daily quota is not exhausted", "Rate limit reached. See daily quota docs", "monthly limit resets tomorrow", "spend cap is not reached", "See billing limit docs; rate limit reached")) {
            val raw=body("rate_limit_error",message)
            assertEquals(message,ProviderLimitDiagnosis.REQUEST_LIMIT,ProviderLimitPolicy.diagnose(429,raw))
            assertEquals(500L,ProviderRetryPolicy.nextDelayMs(429,raw,null,0,0))
        }
    }

}
