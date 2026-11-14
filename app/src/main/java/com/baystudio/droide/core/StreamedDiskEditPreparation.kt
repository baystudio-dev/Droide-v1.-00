package com.baystudio.droide.core

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Preparation after profile/hook admission. The SAME reviewed version must reach final CAS. */
internal object StreamedDiskEditPreparation {
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
        session: OwnedResponseContentStore.Session, files: FileRepository, path: String,
        reviewed: StreamFileVersion, requireCurrent: () -> Unit): StreamedFileArgumentExtractor.Source {
        // This owner exists outside withContext: prompt cancellation on the return boundary cannot
        // lose a snapshot/candidate which the IO operation already created or sealed.
        val recovery = AtomicReference(Recovery())
        try {
            return withContext(Dispatchers.IO) {
                val task = currentCoroutineContext()
                fun current() { task.ensureActive(); requireCurrent() }
                require(prepared.tool == StreamedFileArgumentExtractor.Tool.EDIT_FILE)
                require(reviewed.existed) { "edit_file target does not exist" }
                current(); reviewed.requireMatches(files.root, path)
                session.begin(OwnedResponseContentStore.Kind.TOOL_CONTENT).use { writer ->
                    recovery.set(Recovery(snapshot = writer.recoveryHandle))
                    Files.newInputStream(files.resolveChecked(path).toPath(), NOFOLLOW_LINKS).use { input ->
                        val buffer = ByteArray(65536)
                        var read = 0L
                        while (true) {
                            current(); val count = input.read(buffer); if (count < 0) break
                            check(count > 0 && count.toLong() <= reviewed.bytes - read) { "Reviewed file changed during snapshot" }
                            writer.appendUtf8(buffer, 0, count); read += count
                        }
                        require(read == reviewed.bytes) { "Reviewed file changed during snapshot" }
                    }
                    reviewed.requireMatches(files.root, path); current()
                    val complete = writer.sealCompleted { input ->
                        val buffer = ByteArray(65536)
                        while (true) { current(); val count = input.read(buffer); if (count < 0) break; check(count > 0) }
                    }.content
                    val snapshot = StreamedFileArgumentExtractor.Source(complete.handle, complete.byteLength,
                        complete.utf16Length, complete.sha256, complete.kind)
                    require(snapshot.bytes == reviewed.bytes && snapshot.sha256 == reviewed.sha256) { "Snapshot differs from reviewed file" }
                    current(); reviewed.requireMatches(files.root, path)
                    val (scratchRoot, admission) = session.scratchResources()
                    val candidate = StreamedExactEdit.prepare(prepared, session, snapshot, session,
                        scratchRoot, admission, ::current)
                    recovery.set(recovery.get().copy(candidate = candidate.content.handle))
                    require(candidate.originalArguments === prepared.originalArguments && candidate.originalSnapshot === snapshot)
                    current(); reviewed.requireMatches(files.root, path)
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
