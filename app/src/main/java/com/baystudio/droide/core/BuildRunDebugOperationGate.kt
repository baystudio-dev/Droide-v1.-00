package com.baystudio.droide.core

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class BuildRunDebugOperationGate<K>(
    // Factory must return an unstarted thread and must never run the action itself.
    private val cancellationThread: (Runnable) -> Thread = { action ->
        Thread(action, "droide-build-cancel").apply { isDaemon = true }
    },
    private val onUnoccupied: (stopping: Boolean) -> Unit = {},
) {
    private open class Owner(val job: Job) {
        var cancellation: Cancellation? = null
    }
    private class Active<K>(val kind: K, job: Job) : Owner(job)
    private class Stop(job: Job) : Owner(job) {
        val completed = CompletableDeferred<Unit>()
    }

    /** Job.cancel invokes transport cancellation handlers synchronously. Its return is a separate
     * receipt from coroutine completion: either can precede the other. */
    private class Cancellation(val owner: Owner, private val reason: String) {
        class Attempt {
            val returned = CompletableDeferred<Outcome>()
            @Volatile var definitelyNotStarted = false
        }
        class Outcome(val result: Result<Unit>, val definitelyNotStarted: Boolean = false)
        private val latest = AtomicReference<Attempt?>()

        fun start(threadFactory: (Runnable) -> Thread, onReturned: (Cancellation, Boolean) -> Unit): Attempt {
            val attempt: Attempt
            while (true) {
                val previous = latest.get()
                if (previous != null && !previous.definitelyNotStarted) return previous
                val next = Attempt()
                if (latest.compareAndSet(previous, next)) { attempt = next; break }
            }
            var worker: Thread? = null
            try {
                // A blocked transport must not consume Main, the admission monitor, or the last shared IO worker
                // needed to cancel a different owner.
                worker = threadFactory(Runnable {
                    val outcome = runCatching { owner.job.cancel(CancellationException(reason)) }
                    attempt.returned.complete(Outcome(outcome))
                    onReturned(this, outcome.isSuccess)
                })
                worker.start()
            } catch (failure: Throwable) {
                // Retry is safe only if no worker could have executed cancellation.
                val unstarted = worker == null || worker.state == Thread.State.NEW
                attempt.definitelyNotStarted = unstarted
                attempt.returned.complete(Outcome(Result.failure(failure), unstarted))
                onReturned(this, false)
            }
            return attempt
        }

        suspend fun await(attempt: Attempt, waitForUnstarted: Boolean = false) {
            val outcome = attempt.returned.await()
            // Exit can report a known dispatch failure without falsely certifying owner completion. Accepted
            // Stop still joins startup before touching its adapter.
            if (!outcome.definitelyNotStarted || waitForUnstarted) owner.job.join()
            outcome.result.getOrThrow()
        }
    }

    private val admission = Any()
    private var active: Active<K>? = null
    private var stopping: Stop? = null
    private var closed = false
    private val owners = mutableSetOf<Owner>()
    private val cancellations = mutableSetOf<Cancellation>()
    private var exitCancellations: List<Cancellation>? = null
    private var exitStop: Stop? = null

    suspend fun <T> run(kind: K, block: suspend () -> T): T = coroutineScope {
        // Never store the caller Job: cancellation may be dispatched after this call returns and that
        // caller has already moved on to unrelated work.
        val operation = Active(kind, currentCoroutineContext().job())
        synchronized(admission) {
            check(!closed) { "Build/Run/Debug is stopped for Exit" }
            check(active == null && stopping == null) { "Another Build/Run/Debug operation is already active" }
            active = operation
            owners.add(operation)
        }
        observeCompletion(operation)
        try {

            coroutineScope { block() }
        } finally {
            finishActive(operation)
        }
    }

    suspend fun stopDebug(debugKind: K, stop: suspend () -> Unit): Unit = coroutineScope {
        val owner = Stop(currentCoroutineContext().job())
        var startup: Cancellation? = null
        var ownsStop = false
        val request = synchronized(admission) {
            check(!closed) { "Build/Run/Debug is stopped for Exit" }
            stopping ?: owner.also {
                // Reserve admission before dispatching startup cancellation. An unrelated build may coexist with
                // a running debug session and must remain alive.
                stopping = it
                owners.add(it)
                startup = active?.takeIf { it.kind == debugKind }?.let {
                    requestCancellation(it, "Debug startup stopped")
                }
                ownsStop = true
            }
        }
        if (!ownsStop) {
            request.completed.await()
            return@coroutineScope
        }
        observeCompletion(request)
        val startupAttempt = startup?.let(::startCancellation)
        // Once accepted, Stop is cleanup: cancellation must not release admission while the old startup
        // or adapter is still running.
        withContext(NonCancellable) {
            val result = finishAll(listOf(
                { if (synchronized(admission) { active == null }) onUnoccupied(true) },
                { startup?.await(requireNotNull(startupAttempt), waitForUnstarted = true) },
                { stop() },
                { finishStop(request) },
            ))
            // Publish completion only after admission and workspace state are settled.
            result.fold(request.completed::complete, request.completed::completeExceptionally)
            result.getOrThrow()
        }
        currentCoroutineContext().ensureActive()
    }

    suspend fun closeAndJoin() {
        val requests = synchronized(admission) {
            closed = true
            exitCancellations ?: run {
                exitStop = stopping
                // Include cancellation callbacks whose operation has already retired.
                (owners.map { requestCancellation(it, "Droide Exit") } + cancellations)
                    .distinct().also { exitCancellations = it }
            }
        }
        // Start every target before awaiting any receipt. A timeout only cancels this observer; later
        // callers join the same requests and accepted Stop cleanup.
        val attempts = requests.map { it to startCancellation(it) }
        finishAll(attempts.map { (request, attempt) -> suspend { request.await(attempt) } } +
            listOf<suspend () -> Unit>({ exitStop?.completed?.await() })).getOrThrow()
    }

    fun cancelActive(reason: String): Boolean {
        val request = synchronized(admission) {
            val owner = active ?: stopping ?: return false
            requestCancellation(owner, reason.take(200))
        }
        startCancellation(request)
        return true
    }

    private fun requestCancellation(owner: Owner, reason: String): Cancellation =
        owner.cancellation ?: Cancellation(owner, reason).also {
            owner.cancellation = it
            cancellations.add(it)
        }

    private fun startCancellation(request: Cancellation): Cancellation.Attempt =
        request.start(cancellationThread) { returned, succeeded ->
            // Failed cancellation remains visible to Exit. Successful cancellation's unfinished coroutine
            // remains in owners until its own completion receipt.
            if (succeeded) synchronized(admission) { cancellations.remove(returned) }
        }

    private fun observeCompletion(owner: Owner) {
        owner.job.invokeOnCompletion { synchronized(admission) { owners.remove(owner) } }
    }

    private fun finishActive(operation: Active<K>) {
        try {
            while (true) {
                val isStopping = synchronized(admission) { stopping != null }
                // Keep the active reservation while publishing, but never call external code under admission.
                // Stop can enter or finish during publication.
                onUnoccupied(isStopping)
                if (synchronized(admission) {
                        if ((stopping != null) == isStopping) {
                            if (active === operation) active = null
                            true
                        } else false
                    }) return
            }
        } finally {
            synchronized(admission) { if (active === operation) active = null }
        }
    }

    private fun finishStop(request: Stop) {
        // A new operation cannot enter while this Stop still owns admission. If an unrelated active
        // build retires concurrently, its finalizer publishes idle.
        val publishIdle = synchronized(admission) {
            if (active == null) true
            else {
                if (stopping === request) stopping = null
                false
            }
        }
        if (publishIdle) {
            try { onUnoccupied(false) }
            finally { synchronized(admission) { if (stopping === request) stopping = null } }
        }
    }

    /** A failed cancellation callback must not skip another accepted cleanup target. */
    private suspend fun finishAll(steps: List<suspend () -> Unit>): Result<Unit> {
        var failure: Throwable? = null
        for (step in steps) {
            try { step() } catch (next: Throwable) {
                currentCoroutineContext().ensureActive()
                if (failure == null) failure = next
                else if (failure !== next) failure.addSuppressed(next)
            }
        }
        return failure?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    private fun kotlin.coroutines.CoroutineContext.job(): Job =
        this[Job] ?: error("Build/Run/Debug operation has no coroutine Job")
}
