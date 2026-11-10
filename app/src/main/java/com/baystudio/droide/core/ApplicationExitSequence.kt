package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

/** One explicit user operation. Completed stages are never replayed against replacement owners. */
internal class ApplicationExitSequence(
    private val scope: CoroutineScope,
    private val steps: List<Step>,
    private val observationMs: Long = 30_000,
) {
    data class Step(val label: String, val run: suspend () -> Unit)
    data class State(
        val started: Boolean = false,
        val running: Boolean = false,
        val completed: Boolean = false,
        val stage: String = "",
        val completedStages: Int = 0,
        val failure: String? = null,
    )
    private val lock = Any()
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var next = 0
    private var pending: Deferred<Result<Unit>>? = null

    /** Called from Main. A failed stage must be idempotent; retry keeps its exact captured owner. */
    fun request(): Job = synchronized(lock) {
        job?.takeIf { it.isActive }?.let { return@synchronized it }
        if (mutable.value.completed) return@synchronized checkNotNull(job)
        scope.launch(start = CoroutineStart.LAZY) {
            mutable.value = State(started = true, running = true, completedStages = next)
            try {
                while (next < steps.size) {
                    val step = steps[next]
                    mutable.value = mutable.value.copy(stage = step.label, failure = null)
                    // A UI wait budget is not a cancellation deadline for a save or cleanup. Keep joining the exact
                    // operation: a slow stage is progress, not failure.
                    val operation = pending ?: scope.async { runCatching { step.run() } }.also { pending = it }
                    val result = withTimeoutOrNull(observationMs) { operation.await() }
                        ?: run {
                            mutable.value = mutable.value.copy(stage = "${step.label} — still finishing")
                            operation.await()
                        }
                    if (result.isFailure) pending = null
                    result.getOrThrow()
                    pending = null
                    next++
                    mutable.value = mutable.value.copy(completedStages = next)
                }
                mutable.value = mutable.value.copy(running = false, completed = true, stage = "Stopped")
            } catch (cancelled: CancellationException) {
                mutable.value = mutable.value.copy(running = false, failure = "Exit was interrupted. Retained drafts and owners have not been discarded.")
                throw cancelled
            } catch (failure: Exception) {
                // Only a bounded description reaches UI. No arguments, transcripts, or credentials are logged.
                mutable.value = mutable.value.copy(running = false,
                    failure = failure.message?.take(500) ?: failure.javaClass.simpleName)
            } finally {
                if (mutable.value.running) mutable.value = mutable.value.copy(running = false)
            }
        }.also { job = it; it.start() }
    }
}
