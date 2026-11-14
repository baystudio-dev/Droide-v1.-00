package com.baystudio.droide.ui

import com.baystudio.droide.core.WorkspaceFailureReason
import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.DiskFileVersion
import com.baystudio.droide.core.WorkspaceDocumentSaveRequest
import com.baystudio.droide.core.WorkspaceDocumentSaveReport
import com.baystudio.droide.core.LspManager
import com.baystudio.droide.core.WorkspaceDocumentAuthority
import com.baystudio.droide.core.WorkspaceSearchCapture
import com.baystudio.droide.core.WorkspaceSearchCaptureRequest
import com.baystudio.droide.core.WorkspaceDocumentConflictException
import com.baystudio.droide.core.WorkspaceDocumentKind
import com.baystudio.droide.core.WorkspaceDocumentMutationResult
import com.baystudio.droide.core.WorkspaceDocumentSnapshot
import com.baystudio.droide.core.WorkspaceDocumentVersion
import com.baystudio.droide.core.WorkspacePersistedReconciliation
import com.baystudio.droide.core.runSuspendCatching
import com.baystudio.droide.core.version
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive








internal class EditorWorkspaceDocumentAuthority(
    private val editorState: EditorWorkspaceState,
    private val editorDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val files: FileRepository? = null,
    private val lsp: LspManager? = null,
    private val recoveryStore: com.baystudio.droide.core.EditorStateStore? = null,
    private val preservationScope: kotlinx.coroutines.CoroutineScope? = null,
    private val reportPreservationFailure: (WorkspaceFailureReason, Throwable, () -> Boolean) -> Unit = { _, _, _ -> },
) : WorkspaceDocumentAuthority {

    constructor(
        editorState: EditorWorkspaceState,
        files: FileRepository,
        lsp: LspManager,
        editorDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
        recoveryStore: com.baystudio.droide.core.EditorStateStore? = null,
        preservationScope: kotlinx.coroutines.CoroutineScope? = null,
        reportPreservationFailure: (WorkspaceFailureReason, Throwable, () -> Boolean) -> Unit = { _, _, _ -> },
    ) : this(editorState, editorDispatcher, files, lsp, recoveryStore, preservationScope, reportPreservationFailure)

    /** Only the repository's final checks + native rename belong in action. Stream copies,
     * hashing, fsync, journaling and reconciliation must finish outside this Main-owned turn. */
    override suspend fun commitDiskOnly(path: String, action: () -> Unit) = withContext(editorDispatcher) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        editorState.commitDiskOnlyChecked(path,
            files?.let { repository -> { editorPath: String -> EditorDiskPublicationPaths.requireUnaliased(repository.root, editorPath, path) } },
            action)
    }

    override suspend fun prepareProjectHandoff(gate: com.baystudio.droide.core.WorkspaceHandoffGate):
        com.baystudio.droide.core.WorkspaceHandoffAdmission = withContext(editorDispatcher) {
        prepareEditorProjectHandoff(editorState, recoveryStore, gate, preservationScope, reportPreservationFailure)
    }

    override suspend fun snapshot(path: String): WorkspaceDocumentSnapshot? = withContext(editorDispatcher) {
        val document = editorState.peek(path) ?: return@withContext null
        if (document.pendingRecoveryBuffer != null) {
            val repository = files ?: error("Recovery document requires file authority")
            document.ensureLoaded(repository)
            check(document.pendingRecoveryBuffer == null) { document.loadError ?: "Recovery buffer is unavailable" }
        }
        document.toAuthoritySnapshot()
    }

    override suspend fun snapshots(): List<WorkspaceDocumentSnapshot> = withContext(editorDispatcher) {
        editorState.allDocuments().map(EditorDocument::toAuthoritySnapshot)
    }

    private val saves by lazy { files?.let { EditorSaveCoordinator(editorState, it, lsp = lsp) } }

    override suspend fun saveDocuments(
        requests: List<WorkspaceDocumentSaveRequest>, requireCurrent: () -> Unit,
        publicationFence: ((() -> Unit) -> Unit),
    ): WorkspaceDocumentSaveReport {
        val plan = requests.toList()
        return withContext(editorDispatcher) {
            saves?.saveVersions(plan, requireCurrent, publicationFence)
                ?: WorkspaceDocumentSaveReport.unavailable(plan)
        }
    }

    override suspend fun pendingAgentSavePaths(): List<String> = withContext(editorDispatcher) {
        saves?.pendingAgentSavePaths().orEmpty()
    }

    override suspend fun dirtySnapshots(): List<WorkspaceDocumentSnapshot> = withContext(editorDispatcher) {
        editorState.dirtyDocuments().map(EditorDocument::toAuthoritySnapshot)
    }

    override suspend fun observeRecovery(path: String): com.baystudio.droide.core.WorkspaceRecoveryEditorObservation {
        val observed = observeDocuments(path)
        return com.baystudio.droide.core.WorkspaceRecoveryEditorObservation(observed.snapshots,
            observed.pendingSaves + observed.pendingSync + observed.retainedPaths, validateOwner = observed::validate)
    }

    override suspend fun observeAllDocuments(): com.baystudio.droide.core.WorkspaceEditorObservation =
        observeDocuments(null)

    private data class PendingStamp(
        val saving: Boolean,
        val bufferCapture: Boolean,
        val recoveryBuffer: com.baystudio.droide.core.EditorRecoveryBuffer?,
        val synchronization: Boolean,
        val notification: Boolean,
    )

    // Shared producer for existing path recovery and all-document review. Never settle native
    // buffers or save here. Each pending flag is compared, not merely their merged path set.
    private suspend fun observeDocuments(path: String?): com.baystudio.droide.core.WorkspaceEditorObservation =
        withContext(editorDispatcher) {
            editorState.requireMutableOwner()
            fun included(candidate: String) = path == null || candidate == path ||
                candidate.startsWith("$path/") || path.startsWith("$candidate/")
            fun currentDocuments() = editorState.allDocuments().filter { included(it.path) }.sortedBy { it.path }
            fun pendingStamp(document: EditorDocument) = PendingStamp(
                saving = document.isSaveInProgress, bufferCapture = document.requiresBufferCapture,
                recoveryBuffer = document.pendingRecoveryBuffer, synchronization = document.saveSynchronizationPending,
                notification = document.saveNotificationPending)
            val authority = editorState.observeRecoveryAuthority()
            if (path == null && !authority.initialized) throw com.baystudio.droide.core.WorkspaceRecoveryEditorUnavailableException(
                "Editor recovery has not initialized; all-document evidence is unavailable")
            val documents = currentDocuments()
            val snapshots = documents.map(EditorDocument::toAuthoritySnapshot)
            val pending = documents.map(::pendingStamp)
            val retainedPaths = authority.retainedPaths.filter(::included).toSet()
            val diskVersions = documents.map { it.savedDiskVersion }
            val nativeBindings = documents.map { it.nativeMutationBinding }
            com.baystudio.droide.core.WorkspaceEditorObservation(snapshots,
                documents.filter { it.isSaveInProgress || it.requiresBufferCapture || it.pendingRecoveryBuffer != null }.map { it.path }.toSet(),
                documents.filter { it.saveSynchronizationPending || it.saveNotificationPending }.map { it.path }.toSet(),
                retainedPaths, validateOwner = {
                    withContext(editorDispatcher) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        editorState.requireMutableOwner()
                        check(editorState.observeRecoveryAuthority() == authority) {
                            "Editor lifecycle or retained recovery ownership changed; inspect again"
                        }
                        val current = currentDocuments()
                        check(current.size == documents.size && current.indices.all { current[it] === documents[it] } &&
                            current.map(EditorDocument::toAuthoritySnapshot) == snapshots &&
                            current.map { it.savedDiskVersion } == diskVersions &&
                            current.map { it.nativeMutationBinding } == nativeBindings &&
                            current.map(::pendingStamp) == pending) {
                            "Editor version or pending work changed during observation; inspect again"
                        }
                    }
                })
        }

    override suspend fun captureSearchSnapshots(request: WorkspaceSearchCaptureRequest): WorkspaceSearchCapture =
        withContext(editorDispatcher) { editorState.captureSearchSnapshots(request) }


    override suspend fun replaceText(
        path: String,
        expected: WorkspaceDocumentVersion,
        content: String,
        reason: String,
    ): WorkspaceDocumentMutationResult = withContext(editorDispatcher) {
        replaceTextNow(path, expected, content, reason)
    }

    private fun replaceTextNow(path: String, expected: WorkspaceDocumentVersion, content: String,
        reason: String, requireAdmission: () -> Unit = {},
        enteringMutation: () -> Unit = {},
        published: ((WorkspaceDocumentMutationResult.Applied) -> Unit)? = null,
    ): WorkspaceDocumentMutationResult {
        val document = editorState.peek(path) ?: return WorkspaceDocumentMutationResult.NotLive
        val initial = document.toAuthoritySnapshot()
        if (!initial.loaded || initial.kind != WorkspaceDocumentKind.TEXT) {
            return WorkspaceDocumentMutationResult.NotLive
        }
        if (!initial.editable) return WorkspaceDocumentMutationResult.ReadOnly(initial)
        val native = document.nativeMutationBinding
        // Settle/capture the actual editor before validating the reviewed version.
        // The rest of this Main-owner transaction never suspends.
        native?.prepare()
        check(editorState.peek(path) === document && document.nativeMutationBinding === native) {
            "Editor ownership changed before workspace mutation"
        }
        val before = document.toAuthoritySnapshot()
        if (!before.loaded || before.kind != WorkspaceDocumentKind.TEXT) {
            return WorkspaceDocumentMutationResult.NotLive
        }
        if (!before.editable) return WorkspaceDocumentMutationResult.ReadOnly(before)
        // Full owner identity plus counters is checked here, after native settlement and
        // before the non-suspending apply. Missing identity is never a wildcard.
        if (before.version != expected) return WorkspaceDocumentMutationResult.Conflict(before)
        requireAdmission()
        var result: WorkspaceDocumentMutationResult.Applied? = null
        fun publish() {
            document.applyWorkspaceText(content, reason)
            WorkspaceDocumentMutationResult.Applied(before, document.toAuthoritySnapshot()).also {
                result = it
                published?.invoke(it)
            }
        }
        enteringMutation()
        if (native == null) publish() else native.apply(before.content, content, ::publish)
        return requireNotNull(result)
    }

    override suspend fun replaceSourceText(
        path: String, expected: WorkspaceDocumentVersion,
        replacement: com.baystudio.droide.core.WorkspaceSourceTextReplacement,
        reason: String, requireCurrent: () -> Unit,
        onApplied: (com.baystudio.droide.core.WorkspaceSourceTextReplacement.Publication) -> Unit,
        publicationFence: ((() -> WorkspaceDocumentMutationResult) -> WorkspaceDocumentMutationResult),
    ): WorkspaceDocumentMutationResult = withContext(editorDispatcher) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive(); requireCurrent()
        require(expected.documentId != null) { "A source write requires the reviewed document identity" }
        require(expected.revision < Int.MAX_VALUE && expected.changeVersion < Int.MAX_VALUE) { "Document version counters are exhausted; no source write was attempted" }
        replacement.requireUnused()
        publicationFence {
            requireCurrent()
            replacement.attempt {
                replaceTextNow(path, expected, replacement.text, reason, requireCurrent, replacement::enteringMutation) {
                    replacement.published(path, expected, it)
                    onApplied(requireNotNull(replacement.publication))
                }
            }
        }
    }

    override suspend fun validatePersistedMutation(mutation: FileRepository.Mutation) {
        val conflicts = withContext(editorDispatcher) {
            when (mutation) {
                is FileRepository.Mutation.Write -> editorState.peek(mutation.path)
                    ?.takeIf { it.dirty && (it.pendingRecoveryBuffer != null || it.requiresBufferCapture ||
                        (it.loaded && (mutation.text == null || it.content != mutation.text))) }
                    ?.let { listOf(it.path) }.orEmpty()
                is FileRepository.Mutation.Delete -> editorState.documentsAtOrUnder(mutation.path)
                    .filter { it.blocksPersistedPathMutation }.map { it.path }
                is FileRepository.Mutation.Move -> (
                    editorState.documentsAtOrUnder(mutation.from).filter { it.blocksPersistedPathMutation }.map { it.path } +
                        editorState.documentsAtOrUnder(mutation.to).map { it.path }
                    ).distinct()
                is FileRepository.Mutation.CreateDirectory, is FileRepository.Mutation.Sync -> emptyList()
            }
        }
        if (conflicts.isNotEmpty()) throw WorkspaceDocumentConflictException(conflicts)
    }

    override suspend fun persistedMutation(mutation: FileRepository.Mutation): WorkspacePersistedReconciliation {
        val result = when (mutation) {
            is FileRepository.Mutation.Write -> reconcileWrite(mutation.path, mutation.text)
            is FileRepository.Mutation.CreateDirectory -> WorkspacePersistedReconciliation()
            is FileRepository.Mutation.Delete -> reconcileDelete(mutation.path)
            is FileRepository.Mutation.Move -> reconcileMove(mutation.from, mutation.to)
            is FileRepository.Mutation.Sync -> reconcilePersisted(mutation.path, failOnConflict = true)
        }
        if (result.conflicts.isNotEmpty()) throw WorkspaceDocumentConflictException(result.conflicts)
        return result
    }

    override suspend fun reconcilePersistedWorkspace(): WorkspacePersistedReconciliation =
        reconcilePersisted(path = null, failOnConflict = false)

    private suspend fun reconcileWrite(path: String, text: String?): WorkspacePersistedReconciliation {
        val diskVersion = text?.let { withContext(Dispatchers.IO) { DiskFileVersion.fromText(it) } }
        val actions = mutableListOf<EditorLspAction>()
        val result = withContext(editorDispatcher) {
            val document = editorState.peek(path) ?: return@withContext WorkspacePersistedReconciliation()
            if (!document.loaded) return@withContext WorkspacePersistedReconciliation()
            if (text != null && document.kind == EditorDocumentKind.TEXT) {
                if (document.requiresBufferCapture || (document.dirty || document.isSaveInProgress) && document.content != text) {
                    return@withContext WorkspacePersistedReconciliation(conflicts = listOf(path))
                }
                // Save owns its final acknowledgement. The callback runs before its
                // publication receipt is verified; matching text alone cannot clear intent.
                if (document.isSaveInProgress) return@withContext WorkspacePersistedReconciliation()
                if (document.content == text) {
                    val baselineChanged = document.dirty
                    val wasLspDocument = document.fullIntelligence
                    document.acceptPersistedBaseline(text, if (baselineChanged) "Saved" else document.status, checkNotNull(diskVersion))
                    if (wasLspDocument != document.fullIntelligence) actions.recordTransition(wasLspDocument, document)
                    return@withContext WorkspacePersistedReconciliation(
                        refreshed = if (baselineChanged) listOf(path) else emptyList(),
                    )
                }
                val wasLspDocument = document.fullIntelligence
                document.acceptPersistedText(text, "Reloaded after persisted workspace write", checkNotNull(diskVersion))
                actions.recordTransition(wasLspDocument, document)
                return@withContext WorkspacePersistedReconciliation(refreshed = listOf(path))
            }
            if (document.dirty) {
                return@withContext WorkspacePersistedReconciliation(conflicts = listOf(path))
            }
            val repository = files ?: return@withContext WorkspacePersistedReconciliation()
            val wasLspDocument = document.fullIntelligence
            document.reload(repository)
            actions.recordTransition(wasLspDocument, document)
            WorkspacePersistedReconciliation(refreshed = listOf(path))
        }
        runLspActions(actions)
        return result
    }

    private suspend fun reconcileDelete(path: String): WorkspacePersistedReconciliation {
        val actions = mutableListOf<EditorLspAction>()
        val result = withContext(editorDispatcher) {
            val affected = editorState.documentsAtOrUnder(path)
            val conflicts = affected.filter { it.blocksPersistedPathMutation }.map { it.path }
            if (conflicts.isNotEmpty()) return@withContext WorkspacePersistedReconciliation(conflicts = conflicts)
            val removed = editorState.removePersistedPath(path)
            removed.filter { it.fullIntelligence }
                .forEach { actions += EditorLspAction.Close(it.path) }
            WorkspacePersistedReconciliation(removed = removed.map { it.path })
        }
        runLspActions(actions)
        return result
    }

    private suspend fun reconcileMove(from: String, to: String): WorkspacePersistedReconciliation {
        val actions = mutableListOf<EditorLspAction>()
        val result = withContext(editorDispatcher) {
            val source = editorState.documentsAtOrUnder(from)
            val destination = editorState.documentsAtOrUnder(to)
            val conflicts = (source.filter { it.blocksPersistedPathMutation }.map { it.path } + destination.map { it.path }).distinct()
            if (conflicts.isNotEmpty()) return@withContext WorkspacePersistedReconciliation(conflicts = conflicts)
            val textByOldPath = source.filter { it.fullIntelligence }.associate { it.path to it.content }
            val remapped = editorState.remapPersistedPath(from, to)
            remapped.forEach { (oldPath, newPath) ->
                textByOldPath[oldPath]?.let { content ->
                    actions += EditorLspAction.Close(oldPath)
                    actions += EditorLspAction.Open(newPath, content)
                }
            }
            WorkspacePersistedReconciliation(remapped = remapped)
        }
        runLspActions(actions)
        return result
    }

    private data class ReconcileBaseline(
        val path: String,
        val version: WorkspaceDocumentVersion,
        val dirty: Boolean,
        val saveInProgress: Boolean,
        val pendingPathWork: Boolean,
        val document: EditorDocument,
        val diskVersion: DiskFileVersion?,
        val kind: EditorDocumentKind,
        val loaded: Boolean,
    )

    private sealed interface DiskState {
        data object Missing : DiskState
        data class Text(val content: String, val version: DiskFileVersion) : DiskState
        data object Other : DiskState
    }

    private suspend fun reconcilePersisted(path: String?, failOnConflict: Boolean): WorkspacePersistedReconciliation {
        val repository = files ?: return WorkspacePersistedReconciliation()
        val baselines = withContext(editorDispatcher) {
            val docs = if (path == null) editorState.allDocuments() else editorState.documentsAtOrUnder(path)
            docs.map {
                ReconcileBaseline(it.path, it.toAuthoritySnapshot().version, it.dirty, it.isSaveInProgress,
                    it.blocksPersistedPathMutation, it, it.savedDiskVersion, it.kind, it.loaded)
            }
        }
        if (baselines.isEmpty()) return WorkspacePersistedReconciliation()

        val disk = linkedMapOf<String, DiskState>()
        for (baseline in baselines) {
            disk[baseline.path] = when {
                !repository.exists(baseline.path) -> DiskState.Missing
                else -> runCatching {
                    val text = repository.readTextForEdit(baseline.path)
                    val version = withContext(Dispatchers.IO) { DiskFileVersion.fromText(text) }
                    DiskState.Text(text, version) as DiskState
                }
                    .getOrDefault(DiskState.Other)
            }
        }

        val actions = mutableListOf<EditorLspAction>()
        val result = withContext(editorDispatcher) {
            val conflicts = mutableListOf<String>()
            for (baseline in baselines) {
                val current = editorState.peek(baseline.path) ?: continue
                if (current !== baseline.document || current.toAuthoritySnapshot().version != baseline.version ||
                    current.savedDiskVersion != baseline.diskVersion || current.isSaveInProgress != baseline.saveInProgress ||
                    current.blocksPersistedPathMutation != baseline.pendingPathWork) {
                    conflicts += baseline.path
                    continue
                }
                if (baseline.pendingPathWork) {
                    val persisted = disk.getValue(baseline.path)
                    val observed = when (persisted) {
                        is DiskState.Text -> persisted.version
                        DiskState.Missing -> DiskFileVersion.fromText(null)
                        DiskState.Other -> null
                    }
                    if (baseline.diskVersion == null || baseline.diskVersion != observed) conflicts += baseline.path
                }
            }
            if (conflicts.isNotEmpty() && failOnConflict) {
                return@withContext WorkspacePersistedReconciliation(conflicts = conflicts.distinct())
            }

            val refreshed = mutableListOf<String>()
            val removed = mutableListOf<String>()
            for (baseline in baselines) {
                if (baseline.path in conflicts || baseline.pendingPathWork) continue
                val current = editorState.peek(baseline.path) ?: continue
                // A previous preview reload may have suspended after the first pass.
                if (current !== baseline.document || current.toAuthoritySnapshot().version != baseline.version ||
                    current.savedDiskVersion != baseline.diskVersion || current.isSaveInProgress != baseline.saveInProgress ||
                    current.blocksPersistedPathMutation != baseline.pendingPathWork) {
                    conflicts += baseline.path
                    continue
                }
                when (val persisted = disk.getValue(baseline.path)) {
                    DiskState.Missing -> {
                        // Removing an ancestor also removes live descendants. A clean
                        // ancestor must not erase a descendant's pending work.
                        val blocked = editorState.documentsAtOrUnder(baseline.path)
                            .filter { it.blocksPersistedPathMutation }.map { it.path }
                        if (blocked.isNotEmpty()) {
                            conflicts += blocked
                            continue
                        }
                        val removedDocs = editorState.removePersistedPath(baseline.path)
                        removedDocs.filter { it.fullIntelligence }
                            .forEach { actions += EditorLspAction.Close(it.path) }
                        removed += removedDocs.map { it.path }
                    }
                    is DiskState.Text -> {
                        if (current.loaded && current.kind == EditorDocumentKind.TEXT) {
                            if (current.content != persisted.content || current.savedContent != persisted.content) {
                                val wasLspDocument = current.fullIntelligence
                                current.acceptPersistedText(persisted.content, "Reloaded after external workspace change", persisted.version)
                                actions.recordTransition(wasLspDocument, current)
                                refreshed += current.path
                            } else if (current.savedDiskVersion?.existed == false &&
                                persisted.version.existed && persisted.content.isEmpty()) {
                                // Clean text stayed empty, but the observed file now exists.
                                // Adopt only this known-absence transition, without an LSP text change.
                                current.acceptPersistedBaseline(persisted.content, "Reloaded after external workspace change", persisted.version)
                                refreshed += current.path
                            }
                        } else {
                            val wasLspDocument = current.fullIntelligence
                            val published = current.reloadForPersistedChange(repository) {
                                editorState.peek(baseline.path) === current && !current.blocksPersistedPathMutation &&
                                    current.savedDiskVersion == baseline.diskVersion
                            }
                            if (!published) { conflicts += baseline.path; continue }
                            actions.recordTransition(wasLspDocument, current)
                            refreshed += current.path
                        }
                    }
                    DiskState.Other -> {
                        val wasLspDocument = current.fullIntelligence
                        val published = current.reloadForPersistedChange(repository) {
                            editorState.peek(baseline.path) === current && !current.blocksPersistedPathMutation &&
                                current.savedDiskVersion == baseline.diskVersion
                        }
                        if (!published) { conflicts += baseline.path; continue }
                        actions.recordTransition(wasLspDocument, current)
                        refreshed += current.path
                    }
                }
            }
            WorkspacePersistedReconciliation(
                refreshed = refreshed.distinct(),
                removed = removed.distinct(),
                conflicts = conflicts.distinct(),
            )
        }
        runLspActions(actions)
        return result
    }

    private fun MutableList<EditorLspAction>.recordTransition(wasEnabled: Boolean, document: EditorDocument) {
        editorLspTransition(wasEnabled, document.fullIntelligence, document.path, document.content)?.let(::add)
    }

    private suspend fun runLspActions(actions: List<EditorLspAction>) {
        val manager = lsp ?: return
        actions.forEach { action ->
            runSuspendCatching { manager.applyEditorLspAction(action) }
        }
    }
}

private fun EditorDocument.toAuthoritySnapshot(): WorkspaceDocumentSnapshot = WorkspaceDocumentSnapshot(
    path = path,
    content = content,
    loaded = loaded,
    kind = when (kind) {
        EditorDocumentKind.TEXT -> if (largeFileOptimized) WorkspaceDocumentKind.LARGE else WorkspaceDocumentKind.TEXT
        EditorDocumentKind.IMAGE -> WorkspaceDocumentKind.IMAGE
        EditorDocumentKind.BINARY -> WorkspaceDocumentKind.BINARY
        EditorDocumentKind.DOCUMENT -> WorkspaceDocumentKind.BINARY
        EditorDocumentKind.LARGE -> WorkspaceDocumentKind.LARGE
    },
    dirty = dirty,
    editable = agentEditable,
    revision = revision,
    changeVersion = changeVersion,
    selectionStart = if (loaded && pendingRecoveryBuffer == null) selectionStart else 0,
    selectionEnd = if (loaded && pendingRecoveryBuffer == null) selectionEnd else 0,
    savedContentLength = savedContent.length,
    documentId = documentId,
)
