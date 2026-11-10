package com.baystudio.droide.ui

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun interface WorkspaceDocumentWatchFactory {
    /** Watch one active document's parent/nearest existing ancestor, never a recursive tree. */
    fun open(path: String, invalidate: (rebind: Boolean) -> Unit): AutoCloseable?
}

/** Main-owned lifecycle, bounded thread-safe event inbox. Idle waits are suspended receives, not
 * polling. Events are hints; focus/resume reconciliation remains the recovery fallback. */
internal class VisibleDocumentChangeObserver(
    private val scope: CoroutineScope,
    private val factory: WorkspaceDocumentWatchFactory,
    private val changed: (String) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val coalesceMillis: Long = 150,
) : AutoCloseable {
    private var path: String? = null
    private var generation = 0L
    private var closed = false
    private data class Request(val path: String, val generation: Long, val reconcileAfterRegistration: Boolean)
    private val requests = MutableStateFlow<Request?>(null)
    init { require(coalesceMillis in 0..1_000) }
    private val job = scope.launch {
        // collectLatest cancels AND joins the previous binding before starting another. This also holds
        // when several path/hide/show changes arrive before dispatch.
        requests.collectLatest { request ->
            if (request == null) return@collectLatest
            val next = request.path
            val admitted = request.generation
            val events = Channel<Unit>(Channel.CONFLATED)
            val binding = AtomicLong()
            val rebind = AtomicBoolean()
            var handle: AutoCloseable? = null
            suspend fun release() {
                binding.incrementAndGet() // Late callbacks cannot affect the next binding.
                val old = handle
                handle = null
                if (old != null) withContext(NonCancellable + io) {
                    try { old.close() } catch (_: Exception) {   }
                }
            }
            suspend fun bind() {
                val token = binding.incrementAndGet()
                try {
                    // Assign within IO: cancellation at withContext's return must still close it.
                    withContext(io) {
                        handle = factory.open(next) { structural ->
                            if (binding.get() == token) {
                                if (structural) rebind.set(true)
                                events.trySend(Unit)
                            }
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { release() }
            }
            try {
                bind()
                currentCoroutineContext().ensureActive()

                if (request.reconcileAfterRegistration && !closed && generation == admitted && path == next) changed(next)
                while (true) {
                    events.receive()
                    delay(coalesceMillis)
                    // The conflated inbox holds at most one hint; never spin while writers run.
                    events.tryReceive()
                    currentCoroutineContext().ensureActive()
                    if (closed || generation != admitted || path != next) break
                    if (rebind.getAndSet(false)) { release(); bind() }
                    currentCoroutineContext().ensureActive()
                    if (!closed && generation == admitted && path == next) changed(next)
                }
            } finally {
                events.close()
                release()
            }
        }
    }

    fun update(next: String?) = update(next, reconcileAfterRegistration = false)

    fun update(next: String?, reconcileAfterRegistration: Boolean) {
        if (closed || next == path) return
        path = next
        val admitted = ++generation
        requests.value = next?.let { Request(it, admitted, reconcileAfterRegistration) }
    }

    override fun close() {
        if (closed) return
        closed = true
        generation++
        path = null
        requests.value = null
        job.cancel()
    }
}
