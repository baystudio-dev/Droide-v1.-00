package com.baystudio.droide.core

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Preparation after profile/hook admission. The SAME reviewed version must reach final CAS. */
internal object StreamedLiveEditPreparation {
    /** Handles are informational recovery candidates, not executable results or completeness claims. */
    data class Recovery(val snapshot: OwnedResponseContentStore.Handle? = null,
        val candidate: OwnedResponseContentStore.Handle? = null)
    class Failure(cause: Exception, val recovery: Recovery) :
        IllegalStateException("Source edit preparation failed; the tool body has not modified the project", cause)
    class Cancelled(cause: Exception, val recovery: Recovery) :
        java.util.concurrent.CancellationException("Source edit preparation cancelled before project mutation") {
        init { initCause(cause) }
    }

    suspend fun prepare(prepared: StreamedFileArgumentExtractor.Prepared,
        session: OwnedResponseContentStore.Session, reviewed: WorkspaceDocumentSnapshot,
        requireCurrent: () -> Unit): StreamedFileArgumentExtractor.Source {
        // This owner exists outside withContext: prompt cancellation on the return boundary cannot
        // lose a snapshot/candidate which the IO operation already created or sealed.
        val recovery = AtomicReference(Recovery())
        try {
            return withContext(Dispatchers.IO) {
                val task = currentCoroutineContext()
                fun current() { task.ensureActive(); requireCurrent() }
                require(prepared.tool == StreamedFileArgumentExtractor.Tool.EDIT_FILE)
                require(reviewed.loaded && reviewed.editable && reviewed.kind == WorkspaceDocumentKind.TEXT && reviewed.documentId != null)
                current()
                session.begin(OwnedResponseContentStore.Kind.TOOL_CONTENT).use { writer ->
                    recovery.set(Recovery(snapshot = writer.recoveryHandle))
                    // Immutable snapshot is the authoritative unsaved buffer, never the saved file.
                    var offset = 0
                    while (offset < reviewed.content.length) {
                        current()
                        var end = minOf(reviewed.content.length, offset + 8192)
                        if (end < reviewed.content.length && reviewed.content[end - 1].isHighSurrogate()) end--
                        writer.append(reviewed.content.substring(offset, end)); offset = end
                    }
                    current()
                    val complete = writer.sealCompleted { input ->
                        val buffer = ByteArray(65536)
                        while (true) { current(); val count = input.read(buffer); if (count < 0) break; check(count > 0) }
                    }.content
                    val snapshot = StreamedFileArgumentExtractor.Source(complete.handle, complete.byteLength,
                        complete.utf16Length, complete.sha256, complete.kind)
                    require(snapshot.utf16Units == reviewed.content.length.toLong()) { "Snapshot differs from reviewed buffer" }
                    current()
                    val (scratchRoot, admission) = session.scratchResources()
                    val candidate = StreamedExactEdit.prepare(prepared, session, snapshot, session,
                        scratchRoot, admission, ::current)
                    recovery.set(recovery.get().copy(candidate = candidate.content.handle))
                    require(candidate.originalArguments === prepared.originalArguments && candidate.originalSnapshot === snapshot)
                    current()
                    candidate.content
                }
            }
        } catch (failure: Exception) {
            val derived = when (failure) {
                is StreamedExactEdit.PreparationFailure -> failure.recoveryHandle
                is StreamedExactEdit.PreparationCancelled -> failure.recoveryHandle
                else -> null
            }
            if (derived != null) recovery.set(recovery.get().copy(candidate = derived))
            if (failure is java.util.concurrent.CancellationException) throw Cancelled(failure, recovery.get())
            throw Failure(failure, recovery.get())
        }
    }
}
