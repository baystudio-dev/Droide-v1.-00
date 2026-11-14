package com.baystudio.droide.core

/** One bounded SSE event at a time. Disposable framing never consumes a lifetime quota. */
internal class ProviderSseFraming(private val maximumChars: Int = LlmClient.MAX_RESPONSE_CHARS) {
    private val data = StringBuilder()
    private var hasData = false
    init { require(maximumChars > 0) }
    fun line(raw: String): String? {
        check(raw.length <= maximumChars) { "Provider response line exceeds the supported capacity" }
        if (raw.isEmpty()) return finish()
        if (raw == "data" || raw.startsWith("data:")) {
            val value = if (raw == "data") "" else raw.substring(5).removePrefix(" ")
            val separator = if (hasData) 1 else 0
            check(value.length.toLong() + separator <= maximumChars.toLong() - data.length) {
                "Provider SSE event exceeds the supported capacity"
            }
            if (hasData) data.append('\n')
            data.append(value)
            hasData = true
        }
        return null
    }
    fun finish(): String? {
        if (!hasData) return null
        val payload = data.toString()
        data.setLength(0)
        hasData = false
        // Some compatible proxies serialize an empty flush as JSON null, or a
        // keepalive comment as data. Like an SSE comment these carry no model event;
        // do not forward them to protocol decoders or reset the event-idle deadline.
        // Keep this exact: arbitrary non-JSON data and real JSON objects still fail
        // or validate in their protocol, and [DONE] retains its existing semantics.
        if (payload.isBlank() || payload.trim() in EMPTY_PROXY_FRAMES) return null
        return ProviderPayloadLimits.checkedJson(payload, maximumChars)
    }

    private companion object {
        val EMPTY_PROXY_FRAMES = setOf("null", ": keepalive")
    }
}

/** Monotonic wait-for-next-event timeout, independent of total response lifetime. */
internal class ProviderStreamIdleGuard(
    val timeoutMillis: Long,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private val timeoutNanos = timeoutMillis * 1_000_000L
    private var lastProgress = nowNanos()
    init { require(timeoutMillis > 0 && timeoutMillis <= Long.MAX_VALUE / 1_000_000L) }
    fun deadlineNanos(): Long = lastProgress + timeoutNanos
    fun progress() { lastProgress = nowNanos() }
    fun check() {
        check(nowNanos() - lastProgress < timeoutNanos) { "Provider stream timed out waiting for the next SSE event" }
    }
}
