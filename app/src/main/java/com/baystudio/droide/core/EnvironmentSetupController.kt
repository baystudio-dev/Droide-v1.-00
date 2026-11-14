package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal enum class EnvironmentSetupPhase {
    CHECKING, AVAILABLE, PREPARING, DOWNLOADING, VERIFYING, EXTRACTING, CONFIGURING, CANCELLING, READY, FAILED,
}

internal data class EnvironmentSetupProgress(
    val phase: EnvironmentSetupPhase,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
) {
    val fraction: Float? get() = totalBytes?.takeIf { it > 0 }?.let {
        (downloadedBytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat()
    }
}

internal data class EnvironmentSetupState(
    val progress: EnvironmentSetupProgress = EnvironmentSetupProgress(EnvironmentSetupPhase.CHECKING),
    val presented: Boolean = true,
    val enteredWorkspace: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val phase get() = progress.phase
}

internal data class EnvironmentSetupInspection(val existingEnvironment: Boolean, val deferred: Boolean = false)

/** The process owns one install; observing this controller never starts or restarts it. */
internal class EnvironmentSetupController(
    private val scope: CoroutineScope,
    enabled: Boolean,
    private val inspect: suspend () -> EnvironmentSetupInspection,
    private val install: suspend ((EnvironmentSetupProgress) -> Unit) -> Unit,
    private val defer: suspend () -> Unit,
    private val admit: () -> Boolean = { true },
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(if (enabled) EnvironmentSetupState() else
        EnvironmentSetupState(EnvironmentSetupProgress(EnvironmentSetupPhase.READY), false, true))
    val state: StateFlow<EnvironmentSetupState> = mutableState
    private var operation: Job? = null
    private var generation = 0L
    private var closed = !enabled
    private var showRequested = false
    // Publishing the guest directory does not prove that activation and health checks finished.
    private var installationUnconfirmed = false

    init { if (enabled) inspect(show = false) }

    fun show() = inspect(show = true)

    private fun inspect(show: Boolean) {
        synchronized(lock) {
            if (closed || !admit()) return
            if (operation?.isCompleted == false) {
                if (show) {
                    showRequested = true
                    mutableState.value = mutableState.value.copy(presented = true)
                }
                return
            }
            showRequested = show
            val id = ++generation
            operation = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val found = inspect()
                    publish(id) { old ->
                        if (installationUnconfirmed) old.copy(presented = old.presented || showRequested)
                        else if (found.existingEnvironment) old.copy(
                            progress = EnvironmentSetupProgress(EnvironmentSetupPhase.READY),
                            presented = false, enteredWorkspace = true, error = null,
                        ) else old.copy(
                            progress = EnvironmentSetupProgress(EnvironmentSetupPhase.AVAILABLE),
                            presented = showRequested || !found.deferred,
                            enteredWorkspace = old.enteredWorkspace || (found.deferred && !showRequested), error = null,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    failed(id, failure)
                }
            }
            operation!!.start()
        }
    }

    fun installFromUser() {
        synchronized(lock) {
            val current = mutableState.value
            if (closed || !admit() || operation?.isCompleted == false || !current.presented ||
                current.phase !in setOf(EnvironmentSetupPhase.AVAILABLE, EnvironmentSetupPhase.FAILED)) return
            val id = ++generation
            mutableState.value = current.copy(progress = EnvironmentSetupProgress(EnvironmentSetupPhase.PREPARING),
                busy = true, error = null)
            operation = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val found = inspect()
                    val needsValidation = synchronized(lock) {
                        val required = !found.existingEnvironment || installationUnconfirmed ||
                            current.phase == EnvironmentSetupPhase.FAILED
                        if (required) installationUnconfirmed = true
                        required
                    }
                    if (needsValidation) install { progress ->
                        publish(id) { old ->
                            if (old.phase == EnvironmentSetupPhase.CANCELLING) old else old.copy(progress = progress)
                        }
                    }
                    check(inspect().existingEnvironment) { "Environment setup did not finish. Your existing data was kept." }
                    publish(id) {
                        installationUnconfirmed = false
                        it.copy(progress = EnvironmentSetupProgress(EnvironmentSetupPhase.READY),
                            presented = false, enteredWorkspace = true, busy = false, error = null)
                    }
                } catch (cancelled: CancellationException) {
                    publish(id) { it.copy(progress = EnvironmentSetupProgress(EnvironmentSetupPhase.AVAILABLE),
                        busy = false, error = "Installation paused. You can continue when you are ready.") }
                    throw cancelled
                } catch (failure: Exception) {
                    failed(id, failure)
                } catch (failure: LinkageError) {
                    failed(id, failure)
                }
            }
            operation!!.start()
        }
    }

    fun later() {
        synchronized(lock) {
            if (closed || !admit() || operation?.isCompleted == false || mutableState.value.busy) return
            val id = ++generation
            operation = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    defer()
                    publish(id) { it.copy(presented = false, enteredWorkspace = true) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) { failed(id, failure) }
            }
            operation!!.start()
        }
    }

    fun cancel() = synchronized(lock) {
        if (!mutableState.value.busy) return@synchronized
        mutableState.value = mutableState.value.copy(progress = EnvironmentSetupProgress(EnvironmentSetupPhase.CANCELLING))
        operation?.cancel()
    }

    suspend fun closeAndJoin() {
        val pending = synchronized(lock) { closed = true; generation++; operation }
        pending?.cancelAndJoin()
    }

    private fun failed(id: Long, failure: Throwable) = publish(id) { it.copy(
        progress = EnvironmentSetupProgress(EnvironmentSetupPhase.FAILED), busy = false,
        presented = it.presented || showRequested,
        error = failure.message?.take(600) ?: "Environment setup could not finish. Please try again.",
    ) }

    private fun publish(id: Long, update: (EnvironmentSetupState) -> EnvironmentSetupState) = synchronized(lock) {
        if (!closed && generation == id && admit()) mutableState.value = update(mutableState.value)
    }
}
