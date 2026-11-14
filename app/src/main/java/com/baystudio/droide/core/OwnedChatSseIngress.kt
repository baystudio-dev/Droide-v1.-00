package com.baystudio.droide.core

import okhttp3.Response
import okio.BufferedSource

/** Actual HTTP-response ingress seam. Caller retains normal request auth/network policy and must
 * cancel its OkHttp Call to interrupt a blocked read. No request is retried or tool executed here.
 * Only Chat SSE is migrated; buffered responses and other protocols use their explicit routes.
 */
internal object OwnedChatSseIngress {
    fun readResponse(
        response: Response,
        accumulator: OwnedChatStreamAccumulator,
        idleMillis: Long = 30L * 60L * 1000L,
        checkCancelled: () -> Unit = {},
        progress: (OwnedChatStreamAccumulator.Progress) -> Unit = {},
    ): OwnedChatResult = try { response.use {
        require(response.isSuccessful) { "Typed provider HTTP request was unsuccessful (${response.code})" }
        require(response.header("Content-Type").orEmpty().substringBefore(';').trim().equals("text/event-stream", true)) {
            "Typed Chat ingress requires an SSE response"
        }
        val source = requireNotNull(response.body?.source()) { "Missing typed Chat response body" }
        read(source, accumulator, idleMillis, checkCancelled, progress)
    } } catch (failure: Exception) {
        accumulator.transportFailed()
        throw failure
    }

    fun read(
        source: BufferedSource,
        accumulator: OwnedChatStreamAccumulator,
        idleMillis: Long = 30L * 60L * 1000L,
        checkCancelled: () -> Unit = {},
        progress: (OwnedChatStreamAccumulator.Progress) -> Unit = {},
    ): OwnedChatResult = try {
        readInternal(source, accumulator, idleMillis, checkCancelled, progress)
    } catch (failure: Exception) {
        accumulator.transportFailed()
        throw failure
    }

    private fun readInternal(
        source: BufferedSource,
        accumulator: OwnedChatStreamAccumulator,
        idleMillis: Long = 30L * 60L * 1000L,
        checkCancelled: () -> Unit = {},
        progress: (OwnedChatStreamAccumulator.Progress) -> Unit = {},
    ): OwnedChatResult {
        val framing = ProviderSseFraming()
        val idle = ProviderStreamIdleGuard(idleMillis)
        fun dispatch(payload: String): Boolean {
            checkCancelled()
            accumulator.accept(payload).forEach(progress)
            // Disk/backpressure/UI callback time is not time waiting for the provider.
            idle.progress()
            return payload.trim() == "[DONE]"
        }
        while (true) {
            checkCancelled(); idle.check()
            val line = readLine(source, idle)
            if (line == null) {
                val last = framing.finish()
                if (last != null && dispatch(last)) return accumulator.finishAtTransportEnd(OwnedChatStreamAccumulator.TransportEnd.DONE_MARKER)
                checkCancelled()
                return accumulator.finishAtTransportEnd(OwnedChatStreamAccumulator.TransportEnd.CLEAN_EOF)
            }
            val payload = framing.line(line)
            if (payload != null && dispatch(payload)) return accumulator.finishAtTransportEnd(OwnedChatStreamAccumulator.TransportEnd.DONE_MARKER)
        }
    }
    private fun readLine(source: BufferedSource, idle: ProviderStreamIdleGuard): String? {
        val timeout = source.timeout()
        val hadDeadline = timeout.hasDeadline()
        val previousDeadline = if (hadDeadline) timeout.deadlineNanoTime() else 0L
        val previousTimeout = timeout.timeoutNanos()
        val now = System.nanoTime()
        val idleDeadline = idle.deadlineNanos()
        val effective = if (hadDeadline && previousDeadline - now < idleDeadline - now) previousDeadline else idleDeadline
        return try {
            // Match the tested provider transport: body event-idle policy owns this read,
            // while an earlier explicit caller deadline remains authoritative.
            timeout.timeout(idle.timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
            timeout.deadlineNanoTime(effective)
            ProviderPayloadLimits.readLine(source)
        } finally {
            if (hadDeadline) timeout.deadlineNanoTime(previousDeadline) else timeout.clearDeadline()
            timeout.timeout(previousTimeout, java.util.concurrent.TimeUnit.NANOSECONDS)
        }
    }

}
