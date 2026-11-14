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

/** Finite requests belonging to one exact managed Vue owner, never a generic scheduler. */
internal class LspVueRequests(
    managerScope: CoroutineScope,
    private val onDeadline: (methodKey: String) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    companion object {
        const val FEATURE_MS = 10_000L
        const val MAX_RECORDS = 16
        private const val NANOS_PER_MS = 1_000_000L
        private val latestFamilies = setOf("textDocument/completion", "textDocument/signatureHelp", "textDocument/hover")
        private val methods = latestFamilies + setOf(
            "textDocument/definition", "textDocument/declaration", "textDocument/typeDefinition", "textDocument/references",
            "textDocument/documentSymbol", "textDocument/rename", "textDocument/prepareRename",
            "textDocument/formatting", "textDocument/diagnostic", "textDocument/implementation",
        )
    }

    private val ownerJob = SupervisorJob(managerScope.coroutineContext[Job])
    private val ownerScope = CoroutineScope(managerScope.coroutineContext + ownerJob)
    private val lock = Any()
    private val records = LinkedHashSet<Record>()
    private val active = HashMap<String, Record>()
    private val queued = HashMap<String, Record>()
    private var closed = false
    private var deadlinePublicationPending = false // close defers cancellation until the deadline fence publishes

    internal inner class Record(
        val method: String,
        val deadlineNanos: Long,
        val isCurrent: () -> Boolean,
        val propagateFailure: Boolean,
        val block: suspend (Operation) -> Any?,
    ) {
        val waiter = CompletableDeferred<Any?>(ownerJob)
        var worker: Job? = null
        var started = false
        var attached = true
        var latest = true
        var dispatched = false
    }

    class NotAdmitted : Exception("Vue request no longer admitted before the next wire phase")

    /** One absolute budget owns preparation, mutex wait, complete-set sync and feature drain. */
    inner class Operation internal constructor(private val record: Record) {
        val hasDispatched: Boolean get() = synchronized(lock) { record.dispatched }
        fun isDemandCurrent(): Boolean = record.isCurrent() && synchronized(lock) {
            !closed && record in records && record.attached && record.latest && ownerJob.isActive && nanoTime() < record.deadlineNanos
        }
        fun remainingMs(): Long {
            val remaining = record.deadlineNanos - nanoTime()
            if (remaining <= 0) throw NotAdmitted()
            return ceilMillis(remaining)
        }
        /** Called at the existing serialized writer admission, never at mutex acquisition.
         * A rejection here follows the transport's failed-write cleanup, not an inert pre-RPC drop.
         * Keep the manager's pure current check outside our lock (manager lifetime lock comes first).
         */
        fun markDispatched() {
            check(record.isCurrent()) { "Vue demand lost its current capture at serialized admission" }
            synchronized(lock) {
                check(!closed && record in records && record.attached && record.latest && ownerJob.isActive &&
                    nanoTime() < record.deadlineNanos) { "Vue demand detached, superseded or expired at serialized admission" }
                record.dispatched = true
            }
        }
    }

    suspend fun request(methodKey: String, isCurrent: () -> Boolean, block: suspend () -> JsonElement): JsonElement? =
        owned(methodKey, isCurrent, propagateFailure = false) { operation -> operation.markDispatched(); block() }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> owned(methodKey: String, isCurrent: () -> Boolean, propagateFailure: Boolean = true, block: suspend (Operation) -> T): T? {
        require(methodKey in methods) { "Unsupported fixed Vue request: $methodKey" }
        currentCoroutineContext().ensureActive()
        val registeredAt = nanoTime()
        var replaced: Record? = null
        val record = synchronized(lock) {
            if (closed || !ownerJob.isActive) return@synchronized null
            val latestFamily = methodKey in latestFamilies
            if (latestFamily) {
                replaced = queued.remove(methodKey)
                replaced?.let { records.remove(it) }
            }
            if (records.size >= MAX_RECORDS) return@synchronized null
            Record(methodKey, registeredAt + (if (methodKey == "textDocument/completion") 8_000L else FEATURE_MS) * NANOS_PER_MS, isCurrent, propagateFailure, { operation -> block(operation) }).also { item ->
                records.add(item)
                if (latestFamily && active.containsKey(methodKey)) {
                    active[methodKey]?.latest = false
                    queued[methodKey] = item
                } else {
                    if (latestFamily) active[methodKey] = item
                    item.worker = newWorker(item)
                }
            }
        }
        replaced?.waiter?.complete(null)
        if (record == null) return null
        start(record)
        try {
            val result = record.waiter.await()
            currentCoroutineContext().ensureActive()
            return result?.takeIf {
                record.isCurrent() && synchronized(lock) { !closed && record.attached && record.latest && ownerJob.isActive }
            } as T?
        } catch (cancel: CancellationException) {
            detach(record)
            throw cancel
        } catch (interrupted: InterruptedException) {
            detach(record)
            throw interrupted
        }
    }

    fun isIdle(): Boolean = synchronized(lock) { records.isEmpty() }

    private fun newWorker(record: Record): Job = ownerScope.launch(start = CoroutineStart.LAZY) { runRecord(record) }

    private fun start(record: Record) {
        val worker = synchronized(lock) {
            if (record.started || record.worker == null) null else {
                record.started = true
                record.worker
            }
        } ?: return
        // Lazy cancellation must release exactly this record even if its body never ran.
        worker.invokeOnCompletion { finish(record, null) }
        worker.start()
    }

    private sealed interface Outcome {
        data class Result(val value: Any?) : Outcome
        data object Unavailable : Outcome
    }

    private suspend fun runRecord(record: Record) {
        var result: Any? = null
        try {
            val remaining = record.deadlineNanos - nanoTime()
            if (remaining <= 0L) { expireBeforeDispatch(record); return }
            val outcome = withTimeoutOrNull(ceilMillis(remaining)) {
                if (!synchronized(lock) { !closed && record in records && record.attached && record.latest } ||
                    !record.isCurrent()) Outcome.Unavailable
                else if (expireBeforeDispatch(record)) Outcome.Unavailable
                else {
                    currentCoroutineContext().ensureActive()
                    Outcome.Result(record.block(Operation(record)))
                }
            }
            when (outcome) {
                is Outcome.Result -> {
                    // Even a non-suspending completion cannot publish beyond its original budget.
                    if (nanoTime() - record.deadlineNanos >= 0L) { if (record.dispatched) failOwner(record.method) }
                    else if (record.isCurrent()) result = outcome.value
                }
                Outcome.Unavailable -> Unit
                null -> if (record.dispatched) failOwner(record.method)
            }
        } catch (timeout: TimeoutCancellationException) {
            // A nested transport timeout is an owner failure only at our own deadline.
            if (record.dispatched && nanoTime() - record.deadlineNanos >= 0L) failOwner(record.method)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            if (record.propagateFailure && failure !is NotAdmitted) record.waiter.completeExceptionally(failure)
            // Completed native errors are unavailable; guarded transaction validation can still report a rejection.
        } finally {
            finish(record, result)
        }
    }

    private fun detach(record: Record) {
        synchronized(lock) {
            record.attached = false
            if (queued[record.method] === record) {
                queued.remove(record.method)
                records.remove(record)
            }
        }
        record.waiter.cancel()
        // The active native attempt is independently owned and still consumes its slot.
    }

    private fun finish(record: Record, result: Any?) {
        var next: Record? = null
        val publish = synchronized(lock) {
            if (!records.remove(record)) return
            if (queued[record.method] === record) queued.remove(record.method)
            if (active[record.method] === record) {
                active.remove(record.method)
                if (!closed && ownerJob.isActive) {
                    next = queued.remove(record.method)
                    next?.let { active[record.method] = it; it.worker = newWorker(it) }
                }
            }
            !closed && record.attached && record.latest && ownerJob.isActive
        }
        record.waiter.complete(if (publish) result else null)
        next?.let(::start)
    }

    /** Expired live demand owns the same failure boundary even before its RPC is dispatched.
     * Detached, replaced or invalidated records remain inert drops. Never invoke the
     * manager callback while holding this coordinator lock.
     */
    private fun expireBeforeDispatch(record: Record): Boolean {
        if (record.deadlineNanos - nanoTime() > 0L) return false
        // Expired undispatched work is an inert drop; no native write needs cleanup.
        return true
    }

    private fun failOwner(methodKey: String, undispatched: Record? = null) {
        val pending = synchronized(lock) {
            if (closed || !ownerJob.isActive || (undispatched != null &&
                (undispatched !in records || !undispatched.attached || !undispatched.latest))) return
            closed = true
            deadlinePublicationPending = true
            records.toList().also { records.clear(); active.clear(); queued.clear() }
        }
        // Fence the manager's held-result and wire admission before releasing any caller.
        // The callback may acquire its lifetime lock and close this coordinator, so never
        // invoke it under our lock. Always release pending callers even if it throws.
        try { onDeadline(methodKey) }
        finally {
            pending.forEach { it.waiter.complete(null) }
            synchronized(lock) { deadlinePublicationPending = false }
            ownerJob.cancel(CancellationException("Vue owned request deadline"))
        }
    }

    fun close() {
        val (pending, cancelOwner) = synchronized(lock) {
            closed = true
            records.toList().also { records.clear(); active.clear(); queued.clear() } to !deadlinePublicationPending
        }
        pending.forEach { it.waiter.complete(null) }
        // A deadline callback may reenter close after fencing the manager. Its finally
        // still owns pending unavailable publication before cancellation of their parent.
        if (cancelOwner) ownerJob.cancel(CancellationException("Vue request owner closed"))
    }

    private fun ceilMillis(nanos: Long): Long = (nanos - 1L) / NANOS_PER_MS + 1L
}
