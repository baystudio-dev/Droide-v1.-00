package com.baystudio.droide.core

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ThreadLocalRandom
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/**
 * Only retries an explicit non-2xx HTTP response before streaming emits anything.
 * Never retries network ambiguity, partially streamed output or Agent tool execution.
 */
internal object ProviderRetryPolicy {
    const val MAX_RETRIES = 2
    const val MAX_TOTAL_WAIT_MS = 8_000L
    private const val BASE_MS = 500L
    private const val MAX_JITTER_MS = 200L
    private const val BEYOND_WAIT_BUDGET_MS = MAX_TOTAL_WAIT_MS + 1
    private val json = Json { ignoreUnknownKeys = true }

    fun nextDelayMs(
        status: Int,
        body: String,
        retryAfter: String?,
        retriesMade: Int,
        waitedMs: Long,
        jitterMs: Long = 0L,
        nowMs: Long = System.currentTimeMillis(),
        retryAfterMs: String? = null,
    ): Long? {
        if (retriesMade !in 0 until MAX_RETRIES || waitedMs !in 0..MAX_TOTAL_WAIT_MS) return null
        if (status !in setOf(429, 503, 529)) return null
        if (ProviderLimitPolicy.accessStatus(status, body) != null) return null
        if (ProviderLimitPolicy.diagnose(status, body)?.blocksImmediateRetry == true) return null
        require(jitterMs in 0..MAX_JITTER_MS)
        val serverMs = listOfNotNull(
            parseRetryAfterMs(retryAfter, nowMs),
            parseDecimalDelay(retryAfterMs, BigDecimal.ONE),
            googleRetryInfoMs(body),
        ).maxOrNull()
        val fallbackMs = BASE_MS * (1L shl retriesMade)
        val waitMs = (serverMs ?: fallbackMs) + jitterMs
        if (waitMs > MAX_TOTAL_WAIT_MS - waitedMs) return null
        return waitMs
    }

    private fun parseRetryAfterMs(raw: String?, nowMs: Long): Long? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.length > 96) return BEYOND_WAIT_BUDGET_MS
        parseDecimalDelay(value, BigDecimal(1000))?.let { return it }
        return runCatching {
            val date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
            // A saturated/overflowing date must never become a shorter fallback delay.
            val delay = try { Math.subtractExact(date.toInstant().toEpochMilli(), nowMs) }
                catch (_: ArithmeticException) { return@runCatching BEYOND_WAIT_BUDGET_MS }
            delay.coerceIn(0L, BEYOND_WAIT_BUDGET_MS)
        }.getOrNull()
    }

    private fun parseDecimalDelay(raw: String?, multiplier: BigDecimal): Long? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.length > 96) return BEYOND_WAIT_BUDGET_MS
        if (!Regex("[0-9]+(?:\\.[0-9]+)?").matches(value)) return null
        val ms = value.toBigDecimal().multiply(multiplier)
        if (ms > BigDecimal(MAX_TOTAL_WAIT_MS)) return BEYOND_WAIT_BUDGET_MS
        // Fractional milliseconds round up so the advertised minimum is respected.
        return ms.setScale(0, RoundingMode.CEILING).toLong()
    }

    private fun googleRetryInfoMs(body: String): Long? {
        if (body.length > 65_536) return null
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val error = root["error"] as? JsonObject ?: return null
        val details = error["details"] as? JsonArray ?: return null
        return details.mapNotNull { raw ->
            val info = raw as? JsonObject ?: return@mapNotNull null
            if ((info["@type"] as? JsonPrimitive)?.contentOrNull != "type.googleapis.com/google.rpc.RetryInfo") return@mapNotNull null
            val delay = (info["retryDelay"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            if (!delay.endsWith("s")) return@mapNotNull null
            parseDecimalDelay(delay.dropLast(1), BigDecimal(1000))
        }.maxOrNull()
    }

    suspend fun <T> beforeStreamingEvents(
        onEvent: suspend (LlmStreamEvent) -> Unit,
        request: suspend (suspend (LlmStreamEvent) -> Unit) -> T,
    ): T {
        var retriesMade = 0
        var waitedMs = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            var emitted = false
            try {
                return request { event ->
                    emitted = true
                    onEvent(event)
                }
            } catch (failure: LlmHttpException) {
                // An exception from an event listener must not trigger a request replay.
                if (emitted) throw failure
                val jitter = ThreadLocalRandom.current().nextLong(MAX_JITTER_MS + 1L)
                val waitMs = nextDelayMs(
                    failure.statusCode, failure.responseBody, failure.retryAfter,
                    retriesMade, waitedMs, jitter,
                    retryAfterMs = failure.retryAfterMs,
                ) ?: throw failure
                onEvent(LlmStreamEvent.RetryScheduled(retriesMade + 1, failure.statusCode, waitMs))
                retriesMade++
                waitedMs += waitMs
                delay(waitMs) // cancellable, no busy waiting or new coroutine launches
            }
        }
    }
}
