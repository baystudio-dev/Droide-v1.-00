package com.baystudio.droide.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The workspace owns tasks; a panel only observes this bounded, event-driven state. */
class WorkspaceTaskController(
    parentScope: CoroutineScope,
    private val acquireLease: (String, () -> Unit) -> AutoCloseable? = { _, _ -> null },
) {
    data class State(
        val activeTask: IdeTask? = null,
        val stopping: Boolean = false,
        val message: String = "Ready",
        val error: String? = null,
        val results: Map<String, TaskResult> = emptyMap(),
    ) {
        val busy: Boolean get() = activeTask != null
        val lastResult: TaskResult? get() = results.values.lastOrNull()
    }

    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val lock = Any()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var activeJob: Job? = null
    private var closed = false

    fun start(tasks: List<IdeTask>, manager: TaskManager, beforeRun: suspend () -> Unit): Boolean = synchronized(lock) {
        require(tasks.size in 1..100 && tasks.map { it.label }.distinct().size == tasks.size) { "Invalid task selection" }
        if (closed || !lifetime.isActive || activeJob != null) return@synchronized false
        val selected = tasks.toList()
        var preparation: (suspend () -> Unit)? = beforeRun
        _state.value = _state.value.copy(activeTask = selected.first(), stopping = false,
            message = "Preparing ${selected.first().label}…", error = null,
            results = _state.value.results - selected.map { it.label }.toSet())
        val operation = scope.async(start = CoroutineStart.LAZY) {
            // Admission must precede preparation: saving/formatting can already mutate
            // the selected workspace before the subprocess is started.
            val lease = acquireLease(selected.first().label) { stop() }
            try {
                // Do not retain a disposed editor/panel through the entire build.
                try { requireNotNull(preparation).invoke() } finally { preparation = null }
                currentCoroutineContext().ensureActive()
                for (task in selected) {
                    currentCoroutineContext().ensureActive()
                    synchronized(lock) { _state.value = _state.value.copy(activeTask = task, message = "Running ${task.label}…") }
                    // User-started IDE tasks have explicit Stop, not an automatic 30-minute cutoff.
                    val result = manager.run(task, timeoutMs = 0L)
                    currentCoroutineContext().ensureActive()
                    synchronized(lock) {
                        _state.value = _state.value.copy(results = retain(_state.value.results, result))
                    }
                }
            } finally { lease?.close() }
        }
        activeJob = operation
        // Admission stays busy until coroutine and subprocess/foreground cleanup have completed.
        operation.invokeOnCompletion { failure ->
            synchronized(lock) {
                if (activeJob === operation) {
                    preparation = null
                    activeJob = null
                    val error = when (failure) {
                        null -> null
                        is CancellationException -> if (closed) "Workspace closed; tasks stopped" else "Tasks stopped"
                        else -> "Task failed: ${failure.message ?: failure.javaClass.simpleName}"
                    }
                    _state.value = _state.value.copy(activeTask = null, stopping = false,
                        message = error ?: "Tasks finished", error = error)
                }
            }
        }
        // async retains failures for the completion observer instead of an uncaught Android crash.
        operation.start()
        true
    }

    fun stop(): Boolean {
        val operation = synchronized(lock) {
            val active = activeJob ?: return false
            if (_state.value.stopping) return false
            _state.value = _state.value.copy(stopping = true, message = "Stopping tasks…")
            active
        }
        operation.cancel(CancellationException("Stopped by user"))
        return true
    }

    fun close() {
        synchronized(lock) { closed = true }
        lifetime.cancel(CancellationException("Workspace closed"))
    }

    suspend fun closeAndJoin() { close(); lifetime.join() }

    private fun retain(previous: Map<String, TaskResult>, result: TaskResult): Map<String, TaskResult> {
        val retained = LinkedHashMap(previous)
        retained.remove(result.task.label)
        var output = result.output
        if (output.length > MAX_RESULT_CHARS) {
            var start = output.length - MAX_RESULT_CHARS + 32
            if (output[start].isLowSurrogate() && output[start - 1].isHighSurrogate()) start++
            output = "[Earlier task output omitted]\n" + output.substring(start)
        }
        retained[result.task.label] = result.copy(output = output)
        var chars = retained.values.sumOf { it.output.length }
        while (retained.size > MAX_RESULTS || chars > MAX_TOTAL_CHARS) {
            val removed = retained.remove(retained.keys.first()) ?: break
            chars -= removed.output.length
        }
        return retained
    }

    companion object {
        const val MAX_RESULTS = 32
        const val MAX_RESULT_CHARS = 80_000
        const val MAX_TOTAL_CHARS = 320_000
    }
}
