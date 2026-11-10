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
import kotlinx.serialization.json.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Finite requests belonging to one exact managed ELP owner, never a generic scheduler. */
internal class LspElpRequests(
    managerScope: CoroutineScope,
    private val onDeadline: (methodKey: String, totalDeadlineNanos: Long) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    companion object {
        const val FEATURE_MS = 15_000L
        const val RETIREMENT_MS = 5_000L
        const val MAX_RECORDS = 16
        private const val NANOS_PER_MS = 1_000_000L
        private val latestFamilies = setOf("textDocument/completion", "textDocument/hover", "textDocument/signatureHelp")
        private val methods = latestFamilies + setOf("textDocument/documentSymbol", "workspace/symbol", "textDocument/definition", "textDocument/typeDefinition", "textDocument/implementation", "textDocument/references", "textDocument/semanticTokens/full", "textDocument/inlayHint", "textDocument/codeAction", "codeAction/resolve", "textDocument/rename", "textDocument/prepareCallHierarchy", "callHierarchy/incomingCalls", "callHierarchy/outgoingCalls")
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
        val familyKey: String,
        val totalDeadlineNanos: Long,
        val isCurrent: () -> Boolean,
        val propagateFailure: Boolean,
        val block: suspend (Operation) -> Any?,
    ) {
        val deadlineNanos = totalDeadlineNanos - RETIREMENT_MS * NANOS_PER_MS
        val waiter = CompletableDeferred<Any?>(ownerJob)
        var worker: Job? = null
        var started = false
        var attached = true
        var latest = true
        var dispatched = false
    }

    class NotAdmitted : Exception("ELP request no longer admitted before the next wire phase")

    /** One absolute budget owns preparation, mutex wait, ordinary native sync and feature drain. */
    inner class Operation internal constructor(private val record: Record) {
        val deadlineNanos: Long get() = record.deadlineNanos
        val totalDeadlineNanos: Long get() = record.totalDeadlineNanos
        val hasDispatched: Boolean get() = synchronized(lock) { record.dispatched }
        fun isDemandCurrent(): Boolean = record.isCurrent() && synchronized(lock) {
            !closed && record in records && record.attached && record.latest && ownerJob.isActive && nanoTime() < record.deadlineNanos
        }
        fun remainingMs(): Long {
            val remaining = record.deadlineNanos - nanoTime()
            if (remaining <= 0) throw NotAdmitted()
            return ceilMillis(remaining)
        }
        /** Called at the existing serialized writer admission, never at mutex acquisition. */

        fun markDispatched() {
            if (!record.isCurrent()) throw NotAdmitted()
            synchronized(lock) {
                if (closed || record !in records || !record.attached || !record.latest || !ownerJob.isActive ||
                    nanoTime() >= record.deadlineNanos) throw NotAdmitted()
                record.dispatched = true
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> owned(methodKey: String, isCurrent: () -> Boolean, propagateFailure: Boolean = true, documentKey: String = "", block: suspend (Operation) -> T): T? {
        require(methodKey in methods) { "Unsupported fixed ELP request: $methodKey" }
        currentCoroutineContext().ensureActive()
        val registeredAt = nanoTime()
        val familyKey = "$methodKey\u0000$documentKey"
        var replaced: Record? = null
        val record = synchronized(lock) {
            if (closed || !ownerJob.isActive) return@synchronized null
            val latestFamily = methodKey in latestFamilies
            if (latestFamily) {
                replaced = queued.remove(familyKey)
                replaced?.let { records.remove(it) }
            }
            if (records.size >= MAX_RECORDS) return@synchronized null
            Record(methodKey, familyKey, registeredAt + FEATURE_MS * NANOS_PER_MS, isCurrent, propagateFailure, { operation -> block(operation) }).also { item ->
                records.add(item)
                if (latestFamily && active.containsKey(familyKey)) {
                    active[familyKey]?.latest = false
                    queued[familyKey] = item
                } else {
                    if (latestFamily) active[familyKey] = item
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
    fun earliestDispatchedDeadlineNanos(): Long? = synchronized(lock) { records.filter { it.dispatched }.minOfOrNull { it.totalDeadlineNanos } }

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
                    if (nanoTime() - record.deadlineNanos >= 0L) { if (record.dispatched) failOwner(record) }
                    else if (record.isCurrent()) result = outcome.value
                }
                Outcome.Unavailable -> Unit
                null -> if (record.dispatched) failOwner(record)
            }
        } catch (timeout: TimeoutCancellationException) {
            // A nested transport timeout is an owner failure only at our own deadline.
            if (record.dispatched && nanoTime() - record.deadlineNanos >= 0L) failOwner(record)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            if (record.propagateFailure && failure !is NotAdmitted) record.waiter.completeExceptionally(failure)
            // Completed native errors are unavailable; guarded transaction validation can still report a
            // rejection.
        } finally {
            finish(record, result)
        }
    }

    private fun detach(record: Record) {
        synchronized(lock) {
            record.attached = false
            if (queued[record.familyKey] === record) {
                queued.remove(record.familyKey)
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
            if (queued[record.familyKey] === record) queued.remove(record.familyKey)
            if (active[record.familyKey] === record) {
                active.remove(record.familyKey)
                if (!closed && ownerJob.isActive) {
                    next = queued.remove(record.familyKey)
                    next?.let { active[record.familyKey] = it; it.worker = newWorker(it) }
                }
            }
            !closed && record.attached && record.latest && ownerJob.isActive
        }
        record.waiter.complete(if (publish) result else null)
        next?.let(::start)
    }

    /** Expired live demand owns the same failure boundary even before its RPC is dispatched. */

    private fun expireBeforeDispatch(record: Record): Boolean {
        if (record.deadlineNanos - nanoTime() > 0L) return false

        return true
    }

    private fun failOwner(record: Record, undispatched: Record? = null) {
        val pending = synchronized(lock) {
            if (closed || !ownerJob.isActive || (undispatched != null &&
                (undispatched !in records || !undispatched.attached || !undispatched.latest))) return
            closed = true
            deadlinePublicationPending = true
            records.toList().also { records.clear(); active.clear(); queued.clear() }
        }
        // The callback may acquire its lifetime lock and close this coordinator, so never invoke it
        // under our lock.
        try { onDeadline(record.method, record.totalDeadlineNanos) }
        finally {
            pending.forEach { it.waiter.complete(null) }
            synchronized(lock) { deadlinePublicationPending = false }
            ownerJob.cancel(CancellationException("ELP owned request deadline"))
        }
    }

    fun close() {
        val (pending, cancelOwner) = synchronized(lock) {
            closed = true
            records.toList().also { records.clear(); active.clear(); queued.clear() } to !deadlinePublicationPending
        }
        pending.forEach { it.waiter.complete(null) }
        // A deadline callback may reenter close after fencing the manager. Its finally still owns
        // pending unavailable publication before cancellation of their parent.
        if (cancelOwner) ownerJob.cancel(CancellationException("ELP request owner closed"))
    }

    private fun ceilMillis(nanos: Long): Long = (nanos - 1L) / NANOS_PER_MS + 1L
}
