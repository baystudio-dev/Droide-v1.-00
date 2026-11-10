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

/** Finite requests belonging to one exact managed Nim owner, never a generic scheduler. */
internal class LspNimRequests(
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
            "textDocument/documentSymbol", "workspace/symbol", "textDocument/rename", "extension/status",
        )
    }

    private val ownerJob = SupervisorJob(managerScope.coroutineContext[Job])
    private val ownerScope = CoroutineScope(managerScope.coroutineContext + ownerJob)
    private val lock = Any()
    private val records = LinkedHashSet<Record>()
    private val active = HashMap<String, Record>()
    private val queued = HashMap<String, Record>()
    private var closed = false
    private var deadlinePublicationPending = false

    private inner class Record(
        val method: String,
        val deadlineNanos: Long,
        val isCurrent: () -> Boolean,
        val block: suspend () -> JsonElement,
    ) {
        val waiter = CompletableDeferred<JsonElement?>(ownerJob)
        var worker: Job? = null
        var started = false
        var attached = true
        var latest = true
        var dispatched = false
    }

    /** The 10-second budget begins before admission and includes queue time. [block] performs only
     * the actual RPC, with transport write admission still manager-owned. */
    suspend fun request(methodKey: String, isCurrent: () -> Boolean, block: suspend () -> JsonElement): JsonElement? {
        require(methodKey in methods) { "Unsupported fixed Nim request: $methodKey" }
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
            Record(methodKey, registeredAt + FEATURE_MS * NANOS_PER_MS, isCurrent, block).also { item ->
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
            }
        } catch (cancel: CancellationException) {
            detach(record)
            throw cancel
        } catch (interrupted: InterruptedException) {
            detach(record)
            throw interrupted
        }
    }

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
        data class Result(val value: JsonElement) : Outcome
        data object Unavailable : Outcome
    }

    private suspend fun runRecord(record: Record) {
        var result: JsonElement? = null
        try {
            val remaining = record.deadlineNanos - nanoTime()
            if (remaining <= 0L) { expireBeforeDispatch(record); return }
            val outcome = withTimeoutOrNull(ceilMillis(remaining)) {
                if (!synchronized(lock) { !closed && record in records && record.attached && record.latest } ||
                    !record.isCurrent()) Outcome.Unavailable
                else if (expireBeforeDispatch(record)) Outcome.Unavailable
                else {
                    currentCoroutineContext().ensureActive()
                    record.dispatched = true
                    Outcome.Result(record.block())
                }
            }
            when (outcome) {
                is Outcome.Result -> {
                    // Even a non-suspending completion cannot publish beyond its original budget.
                    if (nanoTime() - record.deadlineNanos >= 0L) failOwner(record.method)
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
        } catch (_: Exception) {
            // Completed feature errors are unavailable. Transport death is separately owner-retired.
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

    private fun finish(record: Record, result: JsonElement?) {
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

    /** Expired live demand owns the same failure boundary even before its RPC is dispatched. */

    private fun expireBeforeDispatch(record: Record): Boolean {
        if (record.deadlineNanos - nanoTime() > 0L) return false
        if (record.isCurrent()) failOwner(record.method, record)
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
        // The callback may acquire its lifetime lock and reenter close, so invoke it outside this lock.

        try { onDeadline(methodKey) }
        finally {
            pending.forEach { it.waiter.complete(null) }
            synchronized(lock) { deadlinePublicationPending = false }
            ownerJob.cancel(CancellationException("Nim owned request deadline"))
        }
    }

    fun close() {
        val (pending, cancelOwner) = synchronized(lock) {
            closed = true
            records.toList().also { records.clear(); active.clear(); queued.clear() } to !deadlinePublicationPending
        }
        pending.forEach { it.waiter.complete(null) }
        // A reentrant deadline callback still owns the fence-before-publication order.
        if (cancelOwner) ownerJob.cancel(CancellationException("Nim request owner closed"))
    }

    private fun ceilMillis(nanos: Long): Long = (nanos - 1L) / NANOS_PER_MS + 1L
}
