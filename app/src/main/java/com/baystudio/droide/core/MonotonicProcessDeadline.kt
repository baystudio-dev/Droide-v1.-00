package com.baystudio.droide.core

/** Measures elapsed process runtime, not JVM startup or compile/test scheduler delays. */
internal class MonotonicProcessDeadline(
    private val startNanos: Long,
    timeoutMs: Long,
) {
    private val durationNanos = timeoutMs * 1_000_000L
    init { require(timeoutMs in 0L..86_400_000L) }

    fun expired(nowNanos: Long): Boolean =
        durationNanos != 0L && nowNanos - startNanos >= durationNanos
}
