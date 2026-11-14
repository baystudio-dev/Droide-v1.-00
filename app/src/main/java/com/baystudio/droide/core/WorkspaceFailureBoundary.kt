package com.baystudio.droide.core

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow

/** One failed supervised operation must not become Android's uncaught process exception. */
internal class WorkspaceFailureBoundary(
    private val report: (Throwable) -> Unit,
) {
    val handler = CoroutineExceptionHandler { _, failure ->
        when (failure) {
            is CancellationException -> Unit
            is Exception, is LinkageError -> report(failure)
            // A VM failure is not recoverable by keeping the same runtime alive.
            else -> throw failure
        }
    }
}

internal enum class WorkspaceFailureReason(val message: String) {
    GENERIC("A background operation failed. Retry the operation if needed."),
    EDITOR_PRESERVATION_RETRY("Unsaved edits from the previous project remain in memory and have not been saved to recovery. Switch projects again to retry preserving them."),
    EDITOR_CAPTURE_BLOCKED("The previous editor could not be fully captured. Pending unsaved edits remain retained, and project switching is blocked. Keep the app open."),
}

/** Identity, reason and owner validity only; never retain an exception or its private details. */
internal class WorkspaceFailureEvent(
    val id: Long,
    val reason: WorkspaceFailureReason,
    val isCurrent: () -> Boolean = { true },
) {
    val message: String get() = reason.message
}

/** Main-owner publication, including the retained handoff predicate. */
internal fun MutableStateFlow<WorkspaceFailureEvent?>.publishWorkspaceFailure(event: WorkspaceFailureEvent) {
    if (!event.isCurrent()) return
    val pending = value
    if (event.reason == WorkspaceFailureReason.GENERIC && pending != null &&
        pending.reason != WorkspaceFailureReason.GENERIC && pending.isCurrent()) return
    value = event
}

internal fun MutableStateFlow<WorkspaceFailureEvent?>.consumeWorkspaceFailure(event: WorkspaceFailureEvent) {
    // WorkspaceFailureEvent deliberately keeps reference equality, including for equal IDs.
    compareAndSet(event, null)
}

/** Recheck immediately before UI delivery; even a stale event is consumed by its exact identity. */
internal fun WorkspaceFailureEvent.deliverWorkspaceFailure(
    showMessage: (String) -> Unit,
    consume: (WorkspaceFailureEvent) -> Unit,
) {
    try {
        if (isCurrent()) showMessage(message)
    } finally {
        consume(this)
    }
}

/** Single bounded UI event; no response bodies, terminal output, paths, or exception messages. */
internal class WorkspaceFailureNotice(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var previousAt: Long? = null
    private val sequence = AtomicLong()

    @Synchronized fun next(
        reason: WorkspaceFailureReason = WorkspaceFailureReason.GENERIC,
        isCurrent: () -> Boolean = { true },
    ): WorkspaceFailureEvent? {
        if (reason == WorkspaceFailureReason.GENERIC) {
            val now = nanoTime()
            val previous = previousAt
            if (previous != null && now - previous < 10_000_000_000L) return null
            previousAt = now
        }
        // Preservation is deduplicated by its exact handoff attempt, never by unrelated failures.
        return WorkspaceFailureEvent(sequence.incrementAndGet(), reason, isCurrent)
    }
}
