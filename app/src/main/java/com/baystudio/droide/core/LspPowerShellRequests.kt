package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement

/**
 * Feature lifetimes for one exact, already-authorized built-in PSES owner. This is
 * deliberately not a transport or a scheduler for other language-server families.
 * The manager repeats its route, grant, source and lifetime checks in [request]'s
 * isCurrent callback, and the transport still checks admission at the actual write.
 */
internal class LspPowerShellRequests(
    managerScope: CoroutineScope,
    private val onDeadline: (methodKey: String) -> Unit,
    private val nanoTime: () -> Long
) {
    constructor(managerScope: CoroutineScope, onDeadline: (methodKey: String) -> Unit) :
        this(managerScope, onDeadline, System::nanoTime)

    companion object {
        const val MAX_RECORDS = 16
        const val COMPLETION_NATIVE_MS = 4_000L
        const val COMPLETION_TOTAL_MS = 9_000L
        private const val NANOS_PER_MS = 1_000_000L
    }

    private val ownerJob = SupervisorJob(managerScope.coroutineContext[Job])
    private val ownerScope = CoroutineScope(managerScope.coroutineContext + ownerJob)
    private val lock = Any()
    private val records = LinkedHashSet<Record>()
    private var closed = false
    private var activeCompletion: Record? = null
    private var latestCompletion: Record? = null

    private inner class Record(
        val methodKey: String,
        val timeoutMs: Long,
        val completion: Boolean,
        val deadlineNanos: Long,
        val isCurrent: () -> Boolean,
        val block: suspend () -> JsonElement
    ) {
        // The independent waiter cannot cancel the worker or acquire its parentage.
        val waiter = CompletableDeferred<JsonElement?>(ownerJob)
        var job: Job? = null
        var started = false
        var attached = true
        var dispatched = false
    }

    private sealed interface Outcome {
        data class Result(val value: JsonElement) : Outcome
        data object Unavailable : Outcome
        data object OwnDeadline : Outcome
    }

    /**
     * Null is bounded unavailable: busy, superseded, stale, expired before dispatch,
     * or a completed feature error. A caller's cancellation is rethrown unchanged.
     * [block] must be only the actual RPC, using the same full [timeoutMs] budget;
     * it must not swallow errors or perform document preparation inside the timer.
     */
    suspend fun request(
        methodKey: String,
        timeoutMs: Long,
        completion: Boolean = false,
        isCurrent: () -> Boolean,
        block: suspend () -> JsonElement
    ): JsonElement? {
        require(methodKey.isNotBlank() && methodKey.length <= 256)
        require(timeoutMs in 1..300_000)
        require(!completion || timeoutMs == COMPLETION_NATIVE_MS)
        currentCoroutineContext().ensureActive()
        val registeredAt = nanoTime()
        var replaced: Record? = null
        val record = synchronized(lock) {
            if (closed || !ownerJob.isActive) return@synchronized null
            // Replacement frees precisely its own slot, even when the owner is full.
            if (completion && latestCompletion != null) {
                replaced = latestCompletion
                records.remove(replaced)
                latestCompletion = null
            }
            if (records.size >= MAX_RECORDS) return@synchronized null
            Record(methodKey, timeoutMs, completion,
                registeredAt + (if (completion) COMPLETION_TOTAL_MS else timeoutMs) * NANOS_PER_MS,
                isCurrent, block).also { created ->
                records.add(created)
                if (completion && activeCompletion != null) latestCompletion = created
                else {
                    if (completion) activeCompletion = created
                    created.job = newJob(created)
                }
            }
        }
        replaced?.let {
            it.waiter.complete(null)
        }
        if (record == null) return null
        start(record)
        try {
            val result = record.waiter.await()
            currentCoroutineContext().ensureActive()
            return result?.takeIf { record.isCurrent() }
        } catch (cancel: CancellationException) {
            detach(record)
            throw cancel
        } catch (interrupted: InterruptedException) {
            detach(record)
            throw interrupted
        }
    }

    // Queued demands retain only one bounded record, not a launched coroutine per
    // keystroke. Promotion creates its worker; supersession cannot build a backlog
    // of canceled dispatch jobs retaining captured source texts.
    private fun newJob(record: Record): Job =
        ownerScope.launch(start = CoroutineStart.LAZY) { runRecord(record) }

    private fun start(record: Record) {
        val job = synchronized(lock) {
            if (record.started || record.job == null) null else {
                record.started = true
                record.job
            }
        } ?: return
        // Also releases a record canceled before its lazy body ever starts.
        job.invokeOnCompletion { finish(record, null) }
        job.start()
    }

    private suspend fun runRecord(record: Record) {
        var value: JsonElement? = null
        try {
            val remaining = remainingNanos(record)
            val outcome = if (remaining <= 0L) Outcome.Unavailable else {
                // This deadline never resets when a queued demand is promoted.
                withTimeoutOrNull(ceilMillis(remaining)) {
                    dispatch(record)
                } ?: if (record.dispatched) Outcome.OwnDeadline else Outcome.Unavailable
            }
            when (outcome) {
                is Outcome.Result -> {
                    if (record.isCurrent()) value = outcome.value
                }
                Outcome.OwnDeadline -> failOwner(record.methodKey)
                Outcome.Unavailable -> Unit
            }
        } catch (cancel: CancellationException) {
            // Owner close/revoke cancels workers. An arbitrary external cancellation
            // is never evidence that this owner's request deadline elapsed.
            throw cancel
        } catch (_: Exception) {
            // Completed remote errors are generic exceptions in JsonRpcProcess.
            // Transport death is retired independently by the manager/transport.
        } finally {
            finish(record, value)
        }
    }

    private suspend fun dispatch(record: Record): Outcome {
        if (!synchronized(lock) { !closed && record in records && record.attached }) return Outcome.Unavailable
        if (!record.isCurrent()) return Outcome.Unavailable
        val remaining = remainingNanos(record)
        // Queue or preparation time must never truncate a completion's native 4s.
        if (remaining <= 0L || record.completion && remaining < record.timeoutMs * NANOS_PER_MS) {
            return Outcome.Unavailable
        }
        currentCoroutineContext().ensureActive()
        record.dispatched = true
        val nativeDeadline = nanoTime() + record.timeoutMs * NANOS_PER_MS
        return try {
            withTimeoutOrNull(record.timeoutMs) {
                Outcome.Result(record.block())
            } ?: Outcome.OwnDeadline
        } catch (timeout: TimeoutCancellationException) {
            // A nested RPC timeout can win the timer race. It counts only when this
            // independently-owned native deadline has actually elapsed. Unrelated
            // shorter timeouts/cancellations do not become owner-failure signals.
            if (nanoTime() - nativeDeadline >= 0L) Outcome.OwnDeadline else Outcome.Unavailable
        }
    }

    private fun detach(record: Record) {
        val queued = synchronized(lock) {
            record.attached = false
            if (latestCompletion !== record) false else {
                latestCompletion = null
                records.remove(record)
                true
            }
        }
        record.waiter.cancel()
        // A started native task is deliberately allowed to drain to its original
        // deadline. Only a demand which is still queued is removed/canceled here.
        if (queued) record.job?.cancel(CancellationException("PowerShell queued waiter detached"))
    }

    private fun finish(record: Record, value: JsonElement?) {
        var next: Record? = null
        val attached = synchronized(lock) {
            if (!records.remove(record)) return
            if (latestCompletion === record) latestCompletion = null
            if (activeCompletion === record) {
                activeCompletion = null
                if (!closed) {
                    next = latestCompletion
                    latestCompletion = null
                    activeCompletion = next
                    next?.let { it.job = newJob(it) }
                }
            }
            record.attached
        }
        // Completing deferreds can resume user code; never do so under our lock.
        record.waiter.complete(if (attached) value else null)
        next?.let(::start)
    }

    private fun failOwner(methodKey: String) {
        val pending = synchronized(lock) {
            if (closed || !ownerJob.isActive) return
            closed = true
            records.toList().also {
                records.clear()
                activeCompletion = null
                latestCompletion = null
            }
        }
        pending.forEach { it.waiter.complete(null) }
        try {
            // Exact-state FAILED/tombstone retirement belongs to the manager. No
            // callback, process close or other I/O occurs under the registry lock.
            onDeadline(methodKey)
        } finally {
            ownerJob.cancel(CancellationException("PowerShell owned request deadline"))
        }
    }

    /** Retire this registry only; it has no map lookup that could affect a successor. */
    fun close() {
        synchronized(lock) {
            closed = true
            records.clear()
            activeCompletion = null
            latestCompletion = null
        }
        ownerJob.cancel(CancellationException("PowerShell request owner closed"))
    }

    private fun remainingNanos(record: Record): Long = record.deadlineNanos - nanoTime()
    private fun ceilMillis(nanos: Long): Long = (nanos - 1L) / NANOS_PER_MS + 1L
}
