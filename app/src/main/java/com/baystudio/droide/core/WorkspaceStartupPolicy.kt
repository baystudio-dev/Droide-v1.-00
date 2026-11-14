package com.baystudio.droide.core

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// A mobile IDE must never leave the user behind an unbounded setup spinner.


enum class WorkspaceStartupPhase {
    IDLE,
    RUNTIME_REGISTRIES,
    PROJECT_CATALOG,
    ACTIVE_RUNTIME,
    FALLBACK_RUNTIME,
    PUBLISH_RUNTIME,
    READY,
    FAILED,
}

data class WorkspaceStartupStatus(
    val attempt: Long = 0L,
    val phase: WorkspaceStartupPhase = WorkspaceStartupPhase.IDLE,
    val projectId: String? = null,
    val detail: String? = null,
)

class WorkspaceStartupTimeoutException(
    val phase: WorkspaceStartupPhase,
    val timeoutMs: Long,
    val projectId: String? = null,
    cause: Throwable? = null,
) : IllegalStateException(
    buildString {
        append("Workspace startup timed out during ")
        append(phase.name.lowercase())
        projectId?.let { append(" for project '").append(it).append('\'') }
        append(" after ").append(timeoutMs).append(" ms")
    },
    cause,
)

private data class StartupResult<T>(val value: T)

object WorkspaceStartupPolicy {
    

    const val RUNTIME_REGISTRIES_TIMEOUT_MS = 5_000L
    const val PROJECT_CATALOG_TIMEOUT_MS = 5_000L
    const val LOCAL_RUNTIME_TIMEOUT_MS = 15_000L
    const val SAF_RUNTIME_TIMEOUT_MS = 60_000L
    const val FALLBACK_RUNTIME_TIMEOUT_MS = 15_000L
    const val PUBLISH_RUNTIME_TIMEOUT_MS = 8_000L

    fun runtimeTimeoutMs(project: DroideProject): Long =
        if (project.treeUri.isNullOrBlank()) LOCAL_RUNTIME_TIMEOUT_MS else SAF_RUNTIME_TIMEOUT_MS

    suspend fun <T> bounded(
        phase: WorkspaceStartupPhase,
        timeoutMs: Long,
        projectId: String? = null,
        block: suspend () -> T,
    ): T {
        require(timeoutMs in 1_000L..60_000L) { "Invalid workspace startup timeout: $timeoutMs" }
        // A caller's timeout must remain cancellation; only this stage's own
        // deadline is a recoverable startup failure. Box nullable results.
        val result = withTimeoutOrNull(timeoutMs) { StartupResult(block()) }
            ?: throw WorkspaceStartupTimeoutException(phase, timeoutMs, projectId)
        return result.value
    }
}

/** Keep cleanup outside withContext: prompt cancellation can discard its return value. */
internal class WorkspaceStartupResources private constructor() {
    private val cleanup = mutableListOf<suspend () -> Unit>()

    fun own(release: suspend () -> Unit) { cleanup += release }

    /** A complete runtime now owns the acquired parts, including its diagnostic binding. */
    fun consolidate(release: suspend () -> Unit) {
        cleanup.clear()
        cleanup += release
    }

    companion object {
        suspend fun <T> prepare(context: CoroutineContext, block: suspend WorkspaceStartupResources.() -> T): T {
            val owner = WorkspaceStartupResources()
            try {
                val value = withContext(context) { owner.block() }
                owner.cleanup.clear()
                return value
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    OwnedResourceCleanup.runSuspend(*owner.cleanup.asReversed().toTypedArray())
                        .exceptionOrNull()?.let { if (it !== failure) failure.addSuppressed(it) }
                }
                owner.cleanup.clear()
                throw failure
            }
        }
    }
}

/** Retain a prepared runtime across the enclosing stage deadline until publication. */
internal class WorkspaceStartupCandidate<T : Any>(private val release: suspend (T) -> Unit) {
    private var value: T? = null

    fun retain(candidate: T): T {
        check(value == null) { "Previous startup candidate is still owned" }
        value = candidate
        return candidate
    }

    suspend fun discardUnlessPublished(published: T? = null) {
        val candidate = value
        value = null
        if (candidate != null && candidate !== published) {
            withContext(NonCancellable) { release(candidate) }
        }
    }
}

/** Fixed labels only: startup diagnostics never contain paths, project names or file content. */
internal enum class WorkspaceStartupStep {
    PROCESS_RECONCILIATION, DECLARATIVE_REGISTRY, AGENT_REGISTRY, PROJECT_STORE,
    WORKSPACE_FILES, CAPABILITIES, EXTENSIONS, TERMINALS, LANGUAGE_SERVICES,
    DEBUGGER, DEBUGGER_STATE, CERTIFICATION_SERVICES, EXTENSION_PLATFORM,
    EXECUTION_SERVICES, AGENT, AGENT_PLUGINS, RUNTIME_BINDINGS, PUBLISH,
}

internal enum class WorkspaceStartupOutcome { STARTED, SUCCEEDED, FAILED, CANCELLED }

internal data class WorkspaceStartupMeasurement(
    val attempt: Long,
    val phase: WorkspaceStartupPhase,
    val step: WorkspaceStartupStep?,
    val outcome: WorkspaceStartupOutcome,
    val elapsedMs: Long,
)

internal class WorkspaceStartupTrace(
    private val attempt: Long,
    private val phase: WorkspaceStartupPhase,
    private val record: (WorkspaceStartupMeasurement) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    suspend fun <T> phase(block: suspend () -> T): T = measure(null, block)
    suspend fun <T> step(step: WorkspaceStartupStep, block: suspend () -> T): T = measure(step, block)

    private fun emit(measurement: WorkspaceStartupMeasurement) {
        // Observability is best effort and may not alter startup ownership/outcome.
        try { record(measurement) } catch (_: Exception) {} catch (_: LinkageError) {}
    }

    private suspend fun <T> measure(step: WorkspaceStartupStep?, block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val start = nanoTime()
        emit(WorkspaceStartupMeasurement(attempt, phase, step, WorkspaceStartupOutcome.STARTED, 0))
        var outcome = WorkspaceStartupOutcome.SUCCEEDED
        try {
            return block()
        } catch (cancelled: CancellationException) {
            outcome = WorkspaceStartupOutcome.CANCELLED
            throw cancelled
        } catch (failure: Throwable) {
            outcome = WorkspaceStartupOutcome.FAILED
            throw failure
        } finally {
            emit(WorkspaceStartupMeasurement(attempt, phase, step, outcome, ((nanoTime() - start) / 1_000_000L).coerceAtLeast(0)))
        }
    }
}
