package com.baystudio.droide.core

import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Bounded producer queue; UI/Agent callers never block on a child's stdin pipe. */
class ProcessInputBuffer {
    private val queue = ArrayBlockingQueue<ByteArray>(8)
    @Volatile private var closed = false
    @Volatile private var aborted = false
    private var directSink: ((ByteArray) -> Boolean)? = null
    private var directEof: (() -> Boolean)? = null
    val isClosed: Boolean get() = closed
    internal val isAborted: Boolean get() = aborted

    internal enum class OfferRejection { CLOSED, TOO_LARGE, PIPE_QUEUE_FULL }
    internal sealed class OfferAdmission {
        data object Accepted : OfferAdmission()
        data object Empty : OfferAdmission()
        data class Rejected(val reason: OfferRejection) : OfferAdmission()
        fun requireAccepted() {
            if (this is Rejected) when (reason) {
                OfferRejection.CLOSED -> throw IllegalStateException("Command stdin is closed")
                OfferRejection.TOO_LARGE -> throw IllegalArgumentException("Input exceeds 64 KiB")
                OfferRejection.PIPE_QUEUE_FULL -> throw IllegalStateException("Command input queue is full or closed; input was not sent. Check the terminal before retrying")
            }
        }
    }

    @Synchronized fun offer(text: String) {
        offerWithEvidence(text).requireAccepted()
    }

    /** Only internal preconditions/pipe queue rejection prove no admission for THIS offer. An
     * arbitrary PTY callback false or throw is never converted to a known rejection. */
    @Synchronized internal fun offerWithEvidence(text: String): OfferAdmission {
        if (closed) return OfferAdmission.Rejected(OfferRejection.CLOSED)
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > 65_536) return OfferAdmission.Rejected(OfferRejection.TOO_LARGE)
        if (bytes.isEmpty()) return OfferAdmission.Empty
        val sink = directSink
        if (sink != null) {
            check(sink(bytes)) {
                "Command input queue is full or closed; input was not sent. Check the terminal before retrying"
            }
        } else if (!queue.offer(bytes)) {
            return OfferAdmission.Rejected(OfferRejection.PIPE_QUEUE_FULL)
        }
        return OfferAdmission.Accepted
    }

    @Synchronized fun close() {
        if (closed) return
        closed = true
        directEof?.let { eof -> check(eof()) { "Interactive EOF was not sent; check the terminal before retrying" } }
    }

    @Synchronized internal fun bindPty(sink: (ByteArray) -> Boolean, eof: () -> Boolean) {
        check(!aborted && directSink == null) { "Command stdin cannot be bound" }
        directSink = sink
        directEof = eof
        try {
            while (true) {
                val bytes = queue.poll() ?: break
                check(sink(bytes)) { "Queued interactive input was not sent; check the terminal before retrying" }
            }
            if (closed) check(eof()) { "Interactive EOF was not sent; check the terminal before retrying" }
        } catch (failure: Throwable) { abort(); throw failure }
    }

    /** Forced cancellation: pending stdin may contain more commands. Discard it instead of feeding it
     * to a process after the user pressed Stop. */
    @Synchronized fun abort() {
        closed = true
        aborted = true
        queue.clear()
        directSink = null
        directEof = null
    }

    internal fun drain(output: OutputStream, isAlive: () -> Boolean) {
        while (!aborted && isAlive()) {
            val bytes = queue.poll(50, TimeUnit.MILLISECONDS)
            if (aborted) break
            if (bytes != null) {
                try {
                    output.write(bytes)
                    output.flush()
                } catch (failure: Throwable) {
                    // Bytes may already have reached the process. Never replay pending text after a failed or
                    // partially successful write.
                    abort()
                    throw failure
                }
            } else if (closed) break
        }
    }
}
