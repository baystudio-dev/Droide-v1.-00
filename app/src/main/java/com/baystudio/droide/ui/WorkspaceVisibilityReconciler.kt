package com.baystudio.droide.ui

import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.WorkspaceDocumentAuthority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/** Event-driven refresh of the visible document; never scans the project or terminal output. */
internal class WorkspaceVisibilityReconciler(
    private val scope: CoroutineScope,
    private val documents: WorkspaceDocumentAuthority,
    private val captureEditor: () -> Unit,
    private val refreshExplorer: () -> Unit,
    private val onFailure: (String, Exception) -> Unit,
    watchFactory: WorkspaceDocumentWatchFactory? = null,
    watchIo: CoroutineDispatcher = Dispatchers.IO,
    watchCoalesceMillis: Long = 150,
) : AutoCloseable {
    data class Visibility(
        val resumed: Boolean,
        val editor: Boolean,
        val explorer: Boolean,
        val activePath: String,
        val editorFocused: Boolean = false,
    )

    private var previous: Visibility? = null
    private var generation = 0L
    private var job: Job? = null
    private var closed = false
    private val reconcileLane = Mutex()
    private val watch = watchFactory?.let { factory ->
        VisibleDocumentChangeObserver(scope, factory, changed = { path ->
            previous?.takeIf { it.resumed && it.editor && it.activePath == path }?.let(::refresh)
        }, io = watchIo, coalesceMillis = watchCoalesceMillis)
    }

    // Called on Main by the owning workspace's committed Compose state.
    fun update(visibility: Visibility) {
        if (closed || visibility == previous) return
        val before = previous
        previous = visibility
        val watchedPath = visibility.activePath.takeIf { visibility.resumed && visibility.editor && it.isNotBlank() }
        val previousWatchedPath = before?.activePath?.takeIf { before.resumed && before.editor && it.isNotBlank() }
        val waitingForRegistration = watch != null && watchedPath != null && watchedPath != previousWatchedPath
        watch?.update(watchedPath, reconcileAfterRegistration = true)
        if (!visibility.resumed || (!visibility.editor && !visibility.explorer)) {
            generation++
            job?.cancel()
            job = null
            return
        }
        val shouldRefresh = before == null || !before.resumed ||
            (visibility.editor && !before.editor) || (visibility.explorer && !before.explorer) ||
            (visibility.editorFocused && !before.editorFocused) || visibility.activePath != before.activePath
        if (!shouldRefresh) return
        if (waitingForRegistration) {
            generation++
            job?.cancel()
            job = null
        } else refresh(visibility)
    }

    private fun refresh(visibility: Visibility) {
        if (closed) return
        val admitted = ++generation
        job?.cancel()
        job = scope.launch {
            // Pane/focus/resume events in the same UI turn share the latest request.
            yield()
            reconcileLane.withLock {
                fun ownsRequest() = !closed && generation == admitted
                if (!ownsRequest()) return@launch
                val path = visibility.activePath
                try {
                    captureEditor()
                    coroutineContext.ensureActive()
                    if (path.isNotBlank() && ownsRequest()) {
                        // Direct authority reconciliation observes disk only; FileRepository's
                        // outbound SAF mutation callback must not run for a visibility refresh.
                        documents.persistedMutation(FileRepository.Mutation.Sync(path))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (ownsRequest()) onFailure(path, failure)
                } finally {
                    coroutineContext.ensureActive()
                    if (ownsRequest() && visibility.explorer) refreshExplorer()
                }
            }
        }
    }

    override fun close() {
        closed = true
        watch?.close()
        generation++
        job?.cancel()
        job = null
    }
}
