package com.baystudio.droide.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Prompt metadata is only an invalidation hint. It never proves tool presence or grants authority. */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
internal class TerminalReadinessInvalidation(
    scope: CoroutineScope,
    private val snapshots: StateFlow<TerminalManager.Snapshot>,
    private val debounceMs: Long = 350L,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val observationScope = CoroutineScope(scope.coroutineContext + owner)
    private val hints = MutableStateFlow(0L)
    private val mutableRevision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = mutableRevision.asStateFlow()

    init {
        require(debounceMs in 1..5_000)
        observationScope.launch {
            hints.filter { it != 0L }.debounce(debounceMs).collect {
                currentCoroutineContext().ensureActive()
                synchronized(lock) { if (!closed && owner.isActive) mutableRevision.value++ }
            }
        }
        observationScope.launch {
            snapshots.map { it.tabs.map { tab -> tab.id to tab.session } }.distinctUntilChanged().collectLatest { sessions ->
                hint()
                coroutineScope {
                    sessions.forEach { (_, session) ->
                        val activity = (session as? TerminalCommandActivitySession)?.commandActivity ?: return@forEach
                        val initial = activity.value
                        launch {
                            var retired = initial.phase == TerminalCommandPhase.CLOSED
                            var lastCompleted = if (initial.phase == TerminalCommandPhase.READY) initial.generation else Long.MIN_VALUE
                            activity.collect { state ->
                                currentCoroutineContext().ensureActive()
                                if (retired) return@collect
                                if (state.phase == TerminalCommandPhase.CLOSED) {
                                    retired = true
                                    if (snapshots.value.tabs.any { it.session === session }) hint()
                                }
                                if (state.phase == TerminalCommandPhase.READY && state.lastExitCode != null && state.generation != lastCompleted) {
                                    lastCompleted = state.generation
                                    // A retired/replaced session cannot invalidate the current workspace.
                                    if (snapshots.value.tabs.any { it.session === session }) hint()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun hint() = synchronized(lock) {
        if (!closed && owner.isActive) hints.value++
    }

    override fun close() {
        synchronized(lock) { closed = true }
        owner.cancel()
    }
}
