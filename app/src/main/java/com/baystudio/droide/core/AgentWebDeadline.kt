package com.baystudio.droide.core

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

internal class AgentWebDeadlineExceeded(val timeoutMs: Long) : IOException("Web request timed out after $timeoutMs ms of active work")

private class AgentWebDeadlineCancellation(val token: Any) : CancellationException("Web active-work deadline reached")

/** A scoped active-work budget. Waiting for authorization is excluded, but caller cancellation is not. */
internal suspend fun <T> withAgentWebDeadline(
    timeoutMs: Long,
    nanoTime: () -> Long = System::nanoTime,
    block: suspend (AgentWebDeadline) -> T,
): T {
    require(timeoutMs >= 0) { "Web deadline must not be negative" }
    val token = Any()
    val expired = AgentWebDeadlineCancellation(token)
    try {
        return coroutineScope {
            val deadline = AgentWebDeadline(timeoutMs, nanoTime, expired)
            val owner = currentCoroutineContext().job
            val timer = launch {
                if (deadline.awaitExpiry()) owner.cancel(expired)
            }
            try {
                currentCoroutineContext().ensureActive()
                deadline.checkActive()
                val result = coroutineScope { block(deadline) }
                currentCoroutineContext().ensureActive()
                // Completion and expiration share the same state lock; expired work cannot win a race with the
                // timer.
                deadline.complete()
                result
            } finally {
                deadline.stop()
                timer.cancel()
            }
        }
    } catch (cancelled: AgentWebDeadlineCancellation) {
        currentCoroutineContext().ensureActive()
        if (cancelled.token !== token) throw cancelled
        throw AgentWebDeadlineExceeded(timeoutMs)
    }
}

internal class AgentWebDeadline internal constructor(
    timeoutMs: Long,
    private val nanoTime: () -> Long,
    private val expiration: CancellationException,
) {
    private val lock = Any()
    private val changed = Channel<Unit>(Channel.CONFLATED)
    private var remaining = timeoutMs.coerceAtMost(Long.MAX_VALUE / NANOS_PER_MS) * NANOS_PER_MS
    private var activeSince = nanoTime()
    private var pauses = 0
    private var expired = false
    private var completed = false

    suspend fun <T> withoutTime(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            charge()
            if (expired) throw expiration
            check(!completed) { "Web deadline is complete" }
            pauses++
            changed.trySend(Unit)
        }
        try {
            return block().also { currentCoroutineContext().ensureActive() }
        } finally {
            synchronized(lock) {
                pauses--
                if (pauses == 0) activeSince = nanoTime()
                changed.trySend(Unit)
            }
        }
    }

    internal fun checkActive() = synchronized(lock) {
        charge()
        if (expired) throw expiration
    }

    internal fun complete() = synchronized(lock) {
        charge()
        if (expired) throw expiration
        completed = true
        changed.trySend(Unit)
    }

    internal fun stop() = synchronized(lock) {
        completed = true
        changed.trySend(Unit)
    }

    // Called only under lock, so entering a pause cannot revive an already consumed budget.
    private fun charge() {
        if (completed || expired || pauses > 0) return
        val now = nanoTime()
        val elapsed = (now - activeSince).coerceAtLeast(0)
        remaining = (remaining - elapsed).coerceAtLeast(0)
        activeSince = now
        if (remaining == 0L) expired = true
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    internal suspend fun awaitExpiry(): Boolean {
        while (true) {
            val waitMs = synchronized(lock) {
                charge()
                if (expired) return true
                if (completed) return false
                if (pauses > 0) null else remaining / NANOS_PER_MS + if (remaining % NANOS_PER_MS == 0L) 0 else 1
            }
            if (waitMs == null) changed.receive()
            else select<Unit> {
                changed.onReceive { }
                onTimeout(waitMs) { }
            }
        }
    }

    private companion object { const val NANOS_PER_MS = 1_000_000L }
}
