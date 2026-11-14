package com.baystudio.droide.core

import java.util.ArrayDeque
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel

/**
 * One-owner FIFO. Character weights cover pending AND in-flight payloads.
 * Graceful close and overflow drain accepted events; neither evicts their data.
 * Unlike ProtocolEventMailbox this admits events before the consumer starts.
 * Channel wake operations occur outside the lock: even inline dispatch cannot
 * run the consumer's action while a producer holds the accounting monitor.
 */
internal class RetainedEventChannel<T>(
    private val capacity: Int,
    private val maxRetainedChars: Long,
) {
    enum class Admission { ACCEPTED, OVERFLOW, CLOSED }

    private inner class Entry(val value: T, val chars: Long) {
        var released = false
        fun releaseLocked() {
            if (!released) {
                released = true
                retained -= chars
                count--
            }
        }
    }

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var retained = 0L
    private var count = 0
    private var closed = false
    private var consuming = false

    init { require(capacity > 0 && maxRetainedChars > 0) }

    internal val retainedChars: Long get() = synchronized(lock) { retained }
    internal val retainedCount: Int get() = synchronized(lock) { count }

    fun tryOffer(value: T, chars: Long): Admission {
        require(chars >= 0L)
        val result = synchronized(lock) {
            when {
                closed -> Admission.CLOSED
                count >= capacity || chars > maxRetainedChars - retained -> {
                    // Latch the first gap: no later event can be admitted past it.
                    closed = true
                    Admission.OVERFLOW
                }
                else -> {
                    entries.addLast(Entry(value, chars))
                    retained += chars
                    count++
                    Admission.ACCEPTED
                }
            }
        }
        when (result) {
            Admission.ACCEPTED -> wake.trySend(Unit)
            Admission.OVERFLOW -> wake.close()
            Admission.CLOSED -> Unit
        }
        return result
    }

    suspend fun consume(action: suspend (T) -> Unit) {
        synchronized(lock) {
            check(!consuming) { "Event channel already has a consumer" }
            consuming = true
        }
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = synchronized(lock) {
                    if (entries.isEmpty() && closed) return
                    entries.pollFirst()
                }
                if (entry == null) { wake.receiveCatching(); continue }
                try { action(entry.value) }
                finally { synchronized(lock) { entry.releaseLocked() } }
            }
        } finally {
            synchronized(lock) {
                consuming = false
                discardPendingLocked()
            }
            wake.close()
        }
    }

    /** Stop producers; accepted values remain available to the owner. */
    fun close() {
        synchronized(lock) { closed = true }
        wake.close()
    }

    /** Owner teardown: discard pending work, while in-flight work stays charged until it returns. */
    fun cancel() {
        synchronized(lock) { discardPendingLocked() }
        wake.close()
    }

    private fun discardPendingLocked() {
        closed = true
        while (entries.isNotEmpty()) entries.removeFirst().releaseLocked()
    }
}
