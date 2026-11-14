package com.baystudio.droide.core

/** Coalesces scrollback copies; the terminal emulator and its screen updates remain immediate. */
internal class TerminalTranscriptPublisher(
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val capture: () -> String,
    private val publish: (String) -> Unit,
    private val intervalMs: Long = 100L,
) {
    private class Pending(val generation: Long) { var cancel: (() -> Unit)? = null }
    private var generation = 0L
    private var pending: Pending? = null
    private var closed = false
    private var finished = false

    init { require(intervalMs > 0L) }

    @Synchronized fun request() {
        if (closed || pending != null) return
        val ticket = Pending(generation)
        pending = ticket
        ticket.cancel = schedule(intervalMs) {
            synchronized(this) {
                if (!closed && pending === ticket && ticket.generation == generation) {
                    pending = null
                    publish(capture())
                }
            }
        }
    }

    /** A demand capture consumes pending work so its older callback cannot overwrite the result. */
    @Synchronized fun flush() {
        cancelPending()
        if (!closed) publish(capture())
    }

    @Synchronized fun clear() {
        cancelPending()
        publish("")
    }

    @Synchronized fun cancelPending() {
        generation++
        pending?.cancel?.invoke()
        pending = null
    }

    /** Close stops publication timers while retaining the current transcript. */
    @Synchronized fun close() {
        cancelPending()
        if (closed) return
        closed = true
        publish(capture())
    }

    /** A process can emit final bytes while closing. Capture those once at the finish callback. */
    @Synchronized fun finish() {
        cancelPending()
        closed = true
        if (finished) return
        finished = true
        publish(capture())
    }
}
