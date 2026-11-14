package com.baystudio.droide.ui

import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.WorkspaceDocumentSaveRequest
import com.baystudio.droide.core.WorkspaceDocumentSaveReport
import com.baystudio.droide.core.WorkspaceDocumentSaveIssue
import com.baystudio.droide.core.LspManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock

internal enum class EditorSaveIssueKind { NOT_SAVED, NEWER_EDITS, SYNCHRONIZATION_PENDING, CAPTURE_FAILED, DOCUMENT_CHANGED }

internal data class EditorSaveIssue(val path: String, val kind: EditorSaveIssueKind, val cause: Throwable)

internal data class EditorSaveReport(
    val savedPaths: List<String> = emptyList(),
    val issues: List<EditorSaveIssue> = emptyList(),
    val warnings: List<EditorSaveIssue> = emptyList(),
) {
    val ready: Boolean get() = issues.isEmpty()
    fun continuationFailure(): Pair<String, Throwable>? = issues.firstOrNull()?.let { first ->
        val more = if (issues.size > 1) " (${issues.size} items need attention)" else ""
        first.path to IllegalStateException((first.cause.message ?: first.kind.name) + more, first.cause)
    }
}

/** One editor-owner coordinator is shared by menu, shortcut, toolbar and pre-operation saves. */
internal class EditorSaveCoordinator(
    private val state: EditorWorkspaceState,
    private val files: FileRepository,
    private val bridge: ActiveEditorBridge? = null,
    private val lsp: LspManager? = null,
    private val notifySaved: suspend (path: String, savedText: String, currentText: String) -> Unit = { path, saved, current ->
        lsp?.didSaveSnapshot(path, saved, current)
    },
) {
    private val mutex get() = state.saveMutex

    suspend fun saveDocument(document: EditorDocument): EditorSaveReport = mutex.withLock {
        val captureFailure = captureFailure(document.path)
        if (captureFailure != null) return@withLock EditorSaveReport(issues = listOf(captureFailure))
        save(listOf(document), allDocuments = false)
    }

    suspend fun saveAll(): EditorSaveReport = mutex.withLock {
        val captureFailure = captureFailure("")
        if (captureFailure != null) return@withLock EditorSaveReport(issues = listOf(captureFailure))
        // A bounded pass: errors in one file do not strand unrelated dirty buffers.
        // New typing is reported at the end; it never causes an unbounded auto-retry loop.
        save(state.allDocuments().filter { it.needsSave }.sortedBy { it.path }, allDocuments = true)
    }

    /** Export saves only the explicitly selected path and descendants, never unrelated tabs. */
    suspend fun saveScope(relativePath: String): EditorSaveReport = mutex.withLock {
        val captureFailure = captureFailure(relativePath)
        if (captureFailure != null) return@withLock EditorSaveReport(issues = listOf(captureFailure))
        val scoped = state.allDocuments().filter { exportPathInScope(it.path, relativePath) }
        val report = save(scoped.filter { it.needsSave }.sortedBy { it.path }, allDocuments = false)
        // Include buffers opened or edited while a save suspended, without saving them implicitly.
        report.copy(issues = (report.issues + outstanding(state.allDocuments().filter {
            exportPathInScope(it.path, relativePath)
        })).distinctBy { it.path to it.kind })
    }

    /** Finish only work from an already persisted Save before a path mutation.
     * Never save a dirty/new buffer as a side effect of Move/Rename/Delete. One bounded
     * attempt per document, followed by a fresh scope check; the repository still owns
     * the atomic mutation guard after this preparation returns.
     */
    suspend fun finishSavedPathWork(paths: Collection<String>): EditorSaveReport = mutex.withLock {
        fun scoped() = state.allDocuments().filter { document ->
            paths.any { path -> exportPathInScope(document.path, path) }
        }
        val capture = captureFailure(paths.firstOrNull().orEmpty())
        if (capture != null) return@withLock EditorSaveReport(issues = listOf(capture))
        val issues = mutableListOf<EditorSaveIssue>()
        val warnings = mutableListOf<EditorSaveIssue>()
        val planned = scoped().sortedBy { it.path }.map { it to it.path }
        for ((document, path) in planned) {
            captureFailure(path)?.let { issues += it; return@withLock EditorSaveReport(issues = issues) }
            if (!isCurrent(document) || document.path != path) {
                issues += changed(document)
                continue
            }
            if (document.persistenceRequired || document.isSaveInProgress || document.requiresBufferCapture ||
                document.pendingRecoveryBuffer != null) continue
            if (document.saveSynchronizationPending || document.saveNotificationPending) {
                val report = save(listOf(document), allDocuments = false)
                issues += report.issues
                warnings += report.warnings
            }
        }
        captureFailure(paths.firstOrNull().orEmpty())?.let(issues::add)
        for (document in scoped()) {
            if (document.persistenceRequired || document.blocksPersistedPathMutation) issues += EditorSaveIssue(document.path,
                if (document.persistenceRequired) EditorSaveIssueKind.NEWER_EDITS
                else EditorSaveIssueKind.SYNCHRONIZATION_PENDING,
                IllegalStateException(if (document.persistenceRequired)
                    "Save or discard unsaved edits before changing this path"
                else "Saved file still has pending work; retry Save or resolve the reported synchronization error"))
        }
        EditorSaveReport(issues = issues.distinctBy { it.path to it.kind }, warnings = warnings)
    }

    suspend fun saveAllBefore(prepare: suspend () -> Unit): EditorSaveReport {
        val report = saveAll()
        if (!report.ready) return report
        prepare()
        return report.copy(issues = readiness().issues)
    }

    /** Call after the last suspending preparation (for example recovery persistence), before admission. */
    fun readiness(): EditorSaveReport {
        val captureFailure = captureFailure("")
        if (captureFailure != null) return EditorSaveReport(issues = listOf(captureFailure))
        return EditorSaveReport(issues = outstanding(state.allDocuments()))
    }

    /** No suspension between the final capture/check and a close or project handoff. */
    fun continueIfReady(document: EditorDocument, expectedPath: String = document.path, action: () -> Unit): EditorSaveReport {
        val captureFailure = captureFailure(document.path)
        val issues = captureFailure?.let(::listOf) ?: if (document.path != expectedPath) listOf(changed(document))
            else outstanding(listOf(document))
        if (issues.isEmpty()) action()
        return EditorSaveReport(issues = issues)
    }

    internal suspend fun saveVersions(
        requests: List<WorkspaceDocumentSaveRequest>,
        requireCurrent: () -> Unit,
        publicationFence: ((() -> Unit) -> Unit),
    ): WorkspaceDocumentSaveReport = mutex.withLock {
        require(requests.size in 1..100 && requests.map { it.path }.distinct().size == requests.size) {
            "Save requires 1..100 distinct explicit paths"
        }
        val caller = kotlinx.coroutines.currentCoroutineContext()
        val dispatcher = kotlin.coroutines.coroutineContext.minusKey(kotlinx.coroutines.Job)
        val saved = mutableListOf<String>()
        val issues = mutableListOf<WorkspaceDocumentSaveIssue>()
        // Account for every committed edit before the first save can suspend or be cancelled.
        for (request in requests) {
            val document = state.peek(request.path)
            if (request.agentEdit != null && document != null && document.documentId == request.expected.documentId) {
                state.pendingAgentSaves[request.path] = document.documentId
            }
        }
        for (request in requests) {
            val document = state.peek(request.path)
            try {
                fun validate() {
                    caller.ensureActive(); requireCurrent(); state.requireMutableOwner()
                    check(document != null && isCurrent(document) && document.path == request.path &&
                        document.documentId == request.expected.documentId && document.revision == request.expected.revision &&
                        document.changeVersion == request.expected.changeVersion && !document.requiresBufferCapture) {
                        "Document changed or closed; read its current version before Save"
                    }
                }
                captureFailure(request.path)?.let { throw it.cause }
                document?.nativeMutationBinding?.prepare()
                validate()
                check(request.agentEdit?.before?.dirty != true || !checkNotNull(document).persistenceRequired) {
                    "Existing user draft was preserved; automatic Save skipped. Explicitly review and save this version."
                }
                // A clean buffer can still have a stale disk baseline (external edit/delete).
                // A no-op Save must verify it before claiming this exact version is persisted.
                if (!checkNotNull(document).persistenceRequired) {
                    val disk = checkNotNull(document.savedDiskVersion) { "Saved disk version is unknown" }
                    check(files.matchesVersion(request.path, disk)) {
                        "File changed on disk; reload or review before Save. External content was preserved."
                    }
                    validate()
                }
                val report = save(listOf(checkNotNull(document)), allDocuments = false, requireCurrent = ::validate,
                    commitPublication = { publish ->
                        kotlinx.coroutines.withContext(dispatcher) {
                            captureFailure(request.path)?.let { throw it.cause }
                            document.nativeMutationBinding?.prepare()
                            validate()
                            publicationFence { validate(); publish() }
                        }
                    })
                saved += report.savedPaths
                if (report.ready && request.path !in saved) saved += request.path
                issues += report.issues.map { WorkspaceDocumentSaveIssue(it.path,
                    it.cause.message ?: it.kind.name) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                issues += WorkspaceDocumentSaveIssue(request.path,
                    failure.message ?: failure.javaClass.simpleName)
            }
        }
        // Later saves can suspend and allow typing into an earlier file. Never save that new text implicitly.
        for (request in requests) {
            val document = state.peek(request.path)
            if (request.path in saved && issues.none { it.path == request.path } && (document == null || document.documentId != request.expected.documentId ||
                document.revision != request.expected.revision || document.changeVersion != request.expected.changeVersion ||
                document.persistenceRequired || document.saveSynchronizationPending)) {
                issues += WorkspaceDocumentSaveIssue(request.path, "Saved snapshot; document changed again")
            }
        }
        for (request in requests) {
            if (request.path in saved && issues.none { it.path == request.path } &&
                state.pendingAgentSaves[request.path] == request.expected.documentId) {
                state.pendingAgentSaves.remove(request.path)
            }
        }
        WorkspaceDocumentSaveReport(saved.distinct(), issues.groupBy { it.path }.map { (path, failures) ->
            WorkspaceDocumentSaveIssue(path, failures.map { it.message }.distinct().joinToString("; "))
        })
    }

    internal suspend fun pendingAgentSavePaths(): List<String> {
        state.requireMutableOwner()
        for ((path, identity) in state.pendingAgentSaves.toMap()) {
            val document = state.peek(path)
            if (document == null || document.documentId != identity) {
                state.pendingAgentSaves.remove(path)
                continue
            }
            if (document.persistenceRequired || document.saveSynchronizationPending || document.isSaveInProgress) continue
            val disk = document.savedDiskVersion ?: continue
            val revision = document.revision
            val version = document.changeVersion
            // Manual Save/reload can resolve pending work. Being clean alone cannot: an
            // external disk conflict can leave a clean but stale editor snapshot behind.
            val matches = com.baystudio.droide.core.runSuspendCatching { files.matchesVersion(path, disk) }.getOrDefault(false)
            state.requireMutableOwner()
            if (matches && state.peek(path) === document && document.documentId == identity &&
                document.revision == revision && document.changeVersion == version && document.savedDiskVersion == disk &&
                !document.persistenceRequired && !document.saveSynchronizationPending && !document.isSaveInProgress) {
                state.pendingAgentSaves.remove(path)
            }
        }
        return state.pendingAgentSaves.keys.toList()
    }

    private suspend fun save(
        documents: List<EditorDocument>, allDocuments: Boolean,
        requireCurrent: () -> Unit = {},
        commitPublication: (suspend (() -> Unit) -> Unit)? = null,
    ): EditorSaveReport {
        val saved = mutableListOf<String>()
        val issues = mutableListOf<EditorSaveIssue>()
        val warnings = mutableListOf<EditorSaveIssue>()
        val planned = documents.map { it to it.path }
        for ((document, plannedPath) in planned) {
            if (!isCurrent(document) || document.path != plannedPath) {
                issues += changed(document)
                continue
            }
            val result = document.save(files, requireCurrent, commitPublication)
            val receipt = result.getOrNull()
            if (receipt == null) {
                val error = checkNotNull(result.exceptionOrNull())
                document.status = "Not saved · ${error.message ?: error::class.java.simpleName}"
                issues += EditorSaveIssue(document.path, EditorSaveIssueKind.NOT_SAVED, error)
                continue
            }
            if (receipt.didWrite) saved += receipt.path
            if (!isCurrent(document) || document.path != receipt.path) {
                issues += changed(document)
                continue
            }
            if (receipt.notifySaved && document.fullIntelligence) {
                try {
                    notifySaved(receipt.path, receipt.text, document.content)
                    document.completeSaveNotification()
                } catch (cancelled: CancellationException) {
                    if (document.loaded && isCurrent(document) && document.path == plannedPath) document.markSaveNotificationPending()
                    throw cancelled
                } catch (failure: Exception) {
                    // An optional language server must not block a valid local build or close.
                    if (document.loaded && isCurrent(document) && document.path == plannedPath) document.markSaveNotificationPending()
                    warnings += EditorSaveIssue(receipt.path, EditorSaveIssueKind.SYNCHRONIZATION_PENDING, failure)
                }
            }
            if (!isCurrent(document) || document.path != plannedPath) issues += changed(document)
            if (document.dirty && !document.saveSynchronizationPending && !document.saveNotificationPending) {
                document.status = "Saved snapshot · newer edits remain unsaved"
            }
            receipt.synchronizationFailure?.let { failure ->
                issues += EditorSaveIssue(receipt.path, EditorSaveIssueKind.SYNCHRONIZATION_PENDING, failure)
            }
        }
        captureFailure("")?.let(issues::add)
        issues += outstanding(if (allDocuments) state.allDocuments() else documents)
        return EditorSaveReport(saved.distinct(), issues.distinctBy { it.path to it.kind }, warnings)
    }

    private fun captureFailure(path: String): EditorSaveIssue? = runSuspendFreeCatching {
        if (bridge != null) bridge.captureForSave() else state.captureForProjectHandoff()
    }.exceptionOrNull()?.let { failure ->
        EditorSaveIssue(path, EditorSaveIssueKind.CAPTURE_FAILED,
            IllegalStateException("Editor buffer could not be captured; edits were preserved. Retry Save.", failure))
    }

    private fun outstanding(documents: List<EditorDocument>): List<EditorSaveIssue> = buildList {
        for (document in documents) {
            if (!isCurrent(document)) { add(changed(document)); continue }
            if (document.persistenceRequired) {
                add(EditorSaveIssue(document.path, EditorSaveIssueKind.NEWER_EDITS,
                    IllegalStateException("Newer edits remain unsaved; save again before continuing")))
            }
            if (document.saveSynchronizationPending) {
                add(EditorSaveIssue(document.path, EditorSaveIssueKind.SYNCHRONIZATION_PENDING,
                    IllegalStateException("File is saved locally; retry Save to finish synchronization")))
            }
        }
    }

    private fun isCurrent(document: EditorDocument): Boolean = state.peek(document.path) === document
    private fun changed(document: EditorDocument) = EditorSaveIssue(document.path, EditorSaveIssueKind.DOCUMENT_CHANGED,
        IllegalStateException("Document changed or closed during Save; review before continuing"))
}

/** Preserve cancellation and fatal failures at the synchronous editor capture boundary. */
private inline fun <T> runSuspendFreeCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}
