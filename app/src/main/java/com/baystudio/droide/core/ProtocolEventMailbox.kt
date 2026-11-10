package com.baystudio.droide.core

import java.util.ArrayDeque
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** One protocol owner, with byte-accounted pending, replay, and in-flight events. */
internal class ProtocolEventMailbox<T>(
    private val capacity: Int,
    private val replay: Int,
    private val maxRetainedBytes: Long,
    private val terminalReserveBytes: Long = 4_096,
) {
    enum class Admission { ACCEPTED, DISCARDED, OVERFLOW, CLOSED }
    data class Terminal<T>(val value: T, val wireBytes: Long)

    private class Entry<T>(
        val sequence: Long,
        val value: T,
        val wireBytes: Long,
        var deliveredSuccessfully: Boolean = false,
    )
    private class Owner(var cursor: Long)
    private sealed interface Poll<out T> {
        data class Value<T>(val entry: Entry<T>) : Poll<T>
        data class Last<T>(val value: T) : Poll<T>
        data object Wait : Poll<Nothing>
        data object Done : Poll<Nothing>
    }

    private val lock = Any()
    private val entries = ArrayDeque<Entry<T>>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var owner: Owner? = null
    private var inFlight: Entry<T>? = null
    private var nextSequence = 0L
    private var retained = 0L
    private var finished = false
    private var terminal: Terminal<T>? = null

    init {
        require(capacity > 0 && replay in 0..capacity)
        require(maxRetainedBytes > 0 && terminalReserveBytes > 0)
    }

    internal val retainedBytes: Long get() = synchronized(lock) { retained }
    internal val terminalBytes: Long get() = synchronized(lock) { terminal?.wireBytes ?: 0L }

    val events: Flow<T> = flow {
        // Register before entering the finally: an unadmitted second collector must never detach or
        // release the actual owner's in-flight event.
        val admitted = synchronized(lock) {
            check(owner == null) { "Protocol event mailbox supports one active collector" }
            Owner(replayFloor()).also { owner = it }
        }
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                when (val next = synchronized(lock) { poll(admitted) }) {
                    is Poll.Value -> {
                        var delivered = false
                        try {
                            emit(next.entry.value)
                            delivered = true
                        } finally {
                            synchronized(lock) {
                                // Cancellation (including first/take abort) does not prove the downstream finished delivery.

                                if (delivered) next.entry.deliveredSuccessfully = true
                                if (owner === admitted && inFlight === next.entry) inFlight = null
                                prune()
                            }
                        }
                    }
                    is Poll.Last -> {
                        emit(next.value)
                        return@flow
                    }
                    Poll.Done -> return@flow
                    Poll.Wait -> wake.receiveCatching()
                }
            }
        } finally {
            synchronized(lock) {
                if (owner === admitted) {
                    owner = null
                    inFlight = null
                    prune()
                }
            }
        }
    }

    fun tryOffer(value: T, wireBytes: Long): Admission {
        require(wireBytes > 0) { "Protocol event byte weight must be positive" }
        val result = synchronized(lock) {
            when {
                finished -> Admission.CLOSED
                owner == null && replay == 0 -> Admission.DISCARDED
                nextSequence == Long.MAX_VALUE -> Admission.OVERFLOW
                else -> {
                    // Calculate the post-append replay eviction without mutating old entries: a rejected event must
                    // not consume replay.
                    val proposedNext = nextSequence + 1L
                    val proposedFloor = (proposedNext - replay.toLong()).coerceAtLeast(0L)
                    var count = 1L
                    var removableBytes = 0L
                    for (entry in entries) {
                        val needed = entry.sequence >= proposedFloor ||
                            owner?.let { entry.sequence >= it.cursor } == true
                        if (needed) count++
                        else if (entry !== inFlight) removableBytes += entry.wireBytes
                    }
                    var remainingBytes = retained - removableBytes
                    val pressureEvictions = ArrayList<Entry<T>>()
                    if (wireBytes > maxRetainedBytes - remainingBytes) {
                        for (entry in entries) {
                            // Only already-delivered replay may yield to byte pressure. Pending or in-flight data is never
                            // lost.
                            val pending = owner?.let { entry.sequence >= it.cursor } == true
                            if (entry.sequence >= proposedFloor && !pending && entry !== inFlight &&
                                entry.deliveredSuccessfully) {
                                pressureEvictions += entry
                                remainingBytes -= entry.wireBytes
                                count--
                                if (wireBytes <= maxRetainedBytes - remainingBytes) break
                            }
                        }
                    }
                    if (count > capacity.toLong() || wireBytes > maxRetainedBytes - remainingBytes) {
                        Admission.OVERFLOW
                    } else {
                        val entry = Entry(nextSequence, value, wireBytes)
                        nextSequence = proposedNext
                        prune()
                        for (old in pressureEvictions) {
                            entries.remove(old)
                            retained -= old.wireBytes
                        }
                        entries.addLast(entry)
                        retained += wireBytes
                        Admission.ACCEPTED
                    }
                }
            }
        }
        if (result == Admission.ACCEPTED) wake.trySend(Unit)
        return result
    }

    fun finish(terminal: Terminal<T>? = null) {
        val won = synchronized(lock) {
            if (finished) false else {
                require(terminal == null || terminal.wireBytes in 1..terminalReserveBytes) {
                    "Protocol terminal notice exceeded its reserved capacity"
                }
                finished = true
                this.terminal = terminal
                prune()
                true
            }
        }
        if (won) wake.close()
    }

    // All helpers below require lock. They never invoke a downstream callback.
    private fun replayFloor(): Long = (nextSequence - replay.toLong()).coerceAtLeast(0L)

    private fun poll(admitted: Owner): Poll<T> {
        check(owner === admitted) { "Protocol event collector lost ownership" }
        if (finished) return terminal?.let { Poll.Last(it.value) } ?: Poll.Done
        val entry = entries.firstOrNull { it.sequence >= admitted.cursor } ?: return Poll.Wait
        admitted.cursor = entry.sequence + 1L
        inFlight = entry
        prune()
        return Poll.Value(entry)
    }

    private fun prune() {
        val floor = replayFloor()
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val keep = entry === inFlight || (!finished &&
                (entry.sequence >= floor || owner?.let { entry.sequence >= it.cursor } == true))
            if (!keep) {
                iterator.remove()
                retained -= entry.wireBytes
            }
        }
    }
}
