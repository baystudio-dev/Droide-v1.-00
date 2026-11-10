package com.baystudio.droide.core

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ProviderRetryPolicyTest {
    @Test fun boundedBackoffAndRetryCountAreDeterministic() {
        val p = ProviderRetryPolicy
        assertEquals(500L, p.nextDelayMs(503, "busy", null, 0, 0, 0))
        assertEquals(1_200L, p.nextDelayMs(503, "busy", null, 1, 500, 200))
        assertNull(p.nextDelayMs(503, "busy", null, 2, 1_500, 0))
        assertNull(p.nextDelayMs(503, "busy", null, 0, 7_700, 0))
        assertEquals(500L, p.nextDelayMs(529, "overloaded", null, 0, 0, 0))
    }

    @Test fun retryAfterSecondsAndHttpDatesAreHonoredWithoutShortening() {
        val p = ProviderRetryPolicy
        assertEquals(3_000L, p.nextDelayMs(429, "rate limit", "3", 0, 0, 0))
        assertNull(p.nextDelayMs(429, "rate limit", "99999", 0, 0, 0))
        assertEquals(500L, p.nextDelayMs(429, "rate limit", "-1", 0, 0, 0))
        assertEquals(500L, p.nextDelayMs(429, "rate limit", "bad", 0, 0, 0))
        val now = Instant.parse("2026-10-08T08:00:00Z")
        val future = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(5).atZone(ZoneOffset.UTC))
        assertEquals(5_000L, p.nextDelayMs(503, "busy", future, 0, 0, 0, now.toEpochMilli()))
        val distant = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(25).atZone(ZoneOffset.UTC))
        assertNull(p.nextDelayMs(503, "busy", distant, 0, 0, 0, now.toEpochMilli()))
    }

    @Test fun billingAndPermanentErrorsNeverRetry() {
        val p = ProviderRetryPolicy
        assertNull(p.nextDelayMs(429, "insufficient_quota", null, 0, 0))
        assertNull(p.nextDelayMs(429, "credit_balance_exhausted", null, 0, 0))
        assertNull(p.nextDelayMs(429, "organization_usage_limit_exceeded", null, 0, 0))
        assertNull(p.nextDelayMs(429, "monthly spend cap reached", null, 0, 0))
        assertNull(p.nextDelayMs(403, "invalid key", null, 0, 0))
        assertNull(p.nextDelayMs(401, "bad auth", null, 0, 0))
        assertNull(p.nextDelayMs(400, "invalid tools", null, 0, 0))
        assertNull(p.nextDelayMs(500, "internal error", null, 0, 0))
    }

    @Test fun providerSpecificWaitHintsAreMinimumsAndNeverShortenedToFallback() {
        val p = ProviderRetryPolicy
        val retryInfo = """{"error":{"details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"1.500000001s"}]}}"""
        assertEquals(1_501L, p.nextDelayMs(429, retryInfo, null, 0, 0))
        assertEquals(2_501L, p.nextDelayMs(503, "busy", null, 0, 0, retryAfterMs = "2500.1"))
        assertEquals(3_000L, p.nextDelayMs(503, retryInfo, "3", 0, 0, retryAfterMs = "1000"))
        assertNull(p.nextDelayMs(429, retryInfo.replace("1.500000001s", "60s"), null, 0, 0))
        assertNull(p.nextDelayMs(503, "busy", "92233720368547758080", 0, 0))
        assertNull(p.nextDelayMs(503, "busy", null, 0, 0, retryAfterMs = "9".repeat(120)))
        assertEquals(500L, p.nextDelayMs(503, "busy", null, 0, 0, retryAfterMs = "invalid"))
        // An ordinary error field must not be mistaken for Google's typed RetryInfo.
        val untyped = retryInfo.replace("google.rpc.RetryInfo", "other.RetryInfo")
        assertEquals(500L, p.nextDelayMs(429, untyped, null, 0, 0))
    }

    @Test fun httpDateArithmeticCannotWrapIntoAnEarlyRetry() {
        val date = "Thu, 8 Oct 2026 08:00:00 GMT"
        assertNull(ProviderRetryPolicy.nextDelayMs(503, "busy", date, 0, 0, nowMs = Long.MIN_VALUE))
        assertEquals(0L, ProviderRetryPolicy.nextDelayMs(503, "busy", date, 0, 0, nowMs = Long.MAX_VALUE))
    }

    @Test fun explicitBillingAndDailyQuotaNeverScheduleAnotherCall() = runBlocking {
        listOf("insufficient_quota rate limit", "quota exceeded: GenerateRequestsPerDayPerProjectPerModel rate limit").forEach { body ->
            var attempts = 0
            val events = mutableListOf<LlmStreamEvent>()
            val failure = runCatching {
                ProviderRetryPolicy.beforeStreamingEvents({ events += it }) {
                    attempts++
                    throw LlmHttpException(429, body, "0")
                }
            }.exceptionOrNull()
            assertTrue(failure is LlmHttpException)
            assertEquals(1, attempts)
            assertTrue(events.isEmpty())
        }
    }

    @Test fun stoppingFromTheRetryNotificationDoesNotIssueAnotherRequest() = runBlocking {
        var attempts = 0
        val failure = runCatching {
            ProviderRetryPolicy.beforeStreamingEvents({ throw CancellationException("Stop") }) {
                attempts++
                throw LlmHttpException(503, "busy", "0")
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(1, attempts)
    }

    @Test fun noReplayAfterStreamEventEvenIfCallbackThrowsHttpException() = runBlocking {
        var attempts = 0
        assertThrows(LlmHttpException::class.java) {
            runBlocking {
                ProviderRetryPolicy.beforeStreamingEvents({}) { event ->
                    attempts++
                    event(LlmStreamEvent.TextDelta("partial"))
                    throw LlmHttpException(503, "callback failed")
                }
            }
        }
        assertEquals(1, attempts)
    }

    @Test fun scheduledRetriesAreObservableAndNeverCountAsPartialModelOutput() = runBlocking {
        val events = mutableListOf<LlmStreamEvent>()
        var attempts = 0
        val result = ProviderRetryPolicy.beforeStreamingEvents({ events += it }) { onEvent ->
            attempts++
            if (attempts == 1) throw LlmHttpException(503, "overloaded", "0")
            onEvent(LlmStreamEvent.TextDelta("recovered"))
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, attempts)
        assertEquals(1, events.filterIsInstance<LlmStreamEvent.RetryScheduled>().size)
        assertEquals(1, events.filterIsInstance<LlmStreamEvent.TextDelta>().size)
        assertEquals(503, events.filterIsInstance<LlmStreamEvent.RetryScheduled>().single().statusCode)
    }

    @Test fun cancellationInterruptsLongServerBackoffWithoutSecondCall() = runBlocking {
        var attempts = 0
        try {
            withTimeout(100) {
                ProviderRetryPolicy.beforeStreamingEvents({}) {
                    attempts++
                    throw LlmHttpException(503, "busy", "6")
                }
            }
            fail("Cancellation expected")
        } catch (_: TimeoutCancellationException) {
            assertEquals(1, attempts)
        }
    }
}
