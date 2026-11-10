package com.baystudio.droide.core

import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class WorkspaceDocumentKind { TEXT, IMAGE, BINARY, LARGE }

data class WorkspaceDocumentSnapshot(
    val path: String,
    val content: String,
    val loaded: Boolean,
    val kind: WorkspaceDocumentKind,
    val dirty: Boolean,
    val editable: Boolean,
    val revision: Int,
    val changeVersion: Int,
    val selectionStart: Int,
    val selectionEnd: Int,
    val savedContentLength: Int,
    /** Opaque identity of one live document instance. Null is legacy/unscoped, never an owner wildcard. */
    val documentId: String? = null,
) {
    init {
        require(path.isNotBlank()) { "Workspace document path is blank" }
        require(revision >= 0) { "Workspace document revision is negative" }
        require(changeVersion >= 0) { "Workspace document changeVersion is negative" }
        require(savedContentLength >= 0) { "Workspace document savedContentLength is negative" }
        if (kind == WorkspaceDocumentKind.LARGE && !editable) {

            require(selectionStart >= 0) { "selectionStart is negative" }
            require(selectionEnd >= 0) { "selectionEnd is negative" }
        } else {
            require(selectionStart in 0..content.length) { "selectionStart is outside document content" }
            require(selectionEnd in 0..content.length) { "selectionEnd is outside document content" }
        }
    }

    val divergedFromSavedBaseline: Boolean get() = dirty
}

data class WorkspaceDocumentVersion(
    val revision: Int,
    val changeVersion: Int,
    val documentId: String? = null,
) {
    init {
        require(documentId == null || documentId.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) { "Invalid document identity" }
        require(revision >= 0) { "Workspace document revision is negative" }
        require(changeVersion >= 0) { "Workspace document changeVersion is negative" }
    }
}

val WorkspaceDocumentSnapshot.version: WorkspaceDocumentVersion
    get() = WorkspaceDocumentVersion(revision, changeVersion, documentId)

data class WorkspacePersistedReconciliation(
    val refreshed: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
    val remapped: List<Pair<String, String>> = emptyList(),
    val conflicts: List<String> = emptyList(),
) {
    val changed: Boolean get() = refreshed.isNotEmpty() || removed.isNotEmpty() || remapped.isNotEmpty()
}

class WorkspaceDocumentConflictException(val paths: List<String>) :
    IllegalStateException(
        "Persisted workspace mutation conflicts with unsaved editor buffer(s) or pending editor work: " +
            paths.distinct().sorted().joinToString(", ")
    )

sealed interface WorkspaceDocumentMutationResult {
    data object NotLive : WorkspaceDocumentMutationResult
    data class ReadOnly(val current: WorkspaceDocumentSnapshot) : WorkspaceDocumentMutationResult
    data class Conflict(val current: WorkspaceDocumentSnapshot) : WorkspaceDocumentMutationResult
    data class Applied(
        val before: WorkspaceDocumentSnapshot,
        val after: WorkspaceDocumentSnapshot,
    ) : WorkspaceDocumentMutationResult
}

class WorkspaceRecoveryEditorUnavailableException(message: String) : IllegalStateException(message)

/** Generation-bound observation. Mutation support is opt-in and must hold a genuine nonblocking
 * editor barrier. Production adapters currently do not provide it. */
class WorkspaceRecoveryEditorObservation internal constructor(
    val snapshots: List<WorkspaceDocumentSnapshot>,
    val pendingPaths: Set<String>,
    private val validateOwner: suspend () -> Unit,
    private val commitUnderBarrier: (suspend (() -> Unit) -> Unit)? = null,
) {
    val canCommit: Boolean get() = commitUnderBarrier != null
    suspend fun validate() { currentCoroutineContext().ensureActive(); validateOwner(); currentCoroutineContext().ensureActive() }
    suspend fun commitClean(action: () -> Unit) {
        val commit = checkNotNull(commitUnderBarrier) { MUTATION_BLOCKED }
        check(snapshots.none { it.dirty } && pendingPaths.isEmpty()) {
            "Save or finish pending editor work before resolving this conflict"
        }
        val caller = currentCoroutineContext()
        caller.ensureActive()
        commit { caller.ensureActive(); action() }
    }
    companion object {
        const val MUTATION_BLOCKED = "Conflict resolution is unavailable until editor changes can be safely fenced without blocking the UI. Keep the conflict; you can still inspect and save retained copies."
    }
}

/** All live documents and retained editor work, observed without capture/save/commit. The
 * validator detects stale ownership; it never drains or fences writers. */
class WorkspaceEditorObservation internal constructor(
    snapshots: List<WorkspaceDocumentSnapshot>, pendingSaves: Set<String>, pendingSync: Set<String>,
    retainedPaths: Set<String>, private val validateOwner: suspend () -> Unit,
) {
    val snapshots: List<WorkspaceDocumentSnapshot> = java.util.Collections.unmodifiableList(snapshots.toList())
    val pendingSaves: Set<String> = java.util.Collections.unmodifiableSet(pendingSaves.toSet())
    val pendingSync: Set<String> = java.util.Collections.unmodifiableSet(pendingSync.toSet())
    val retainedPaths: Set<String> = java.util.Collections.unmodifiableSet(retainedPaths.toSet())
    suspend fun validate() {
        currentCoroutineContext().ensureActive()
        validateOwner()
        currentCoroutineContext().ensureActive()
    }
}

interface WorkspaceDocumentAuthority : StreamedDiskCommitAuthority {
    /** Unsupported by default. A snapshot is not a disk publication barrier. */
    override suspend fun commitDiskOnly(path: String, action: () -> Unit) {
        throw WorkspaceRecoveryEditorUnavailableException("Editor cannot fence disk-only publication")
    }

    suspend fun snapshot(path: String): WorkspaceDocumentSnapshot?
    suspend fun snapshots(): List<WorkspaceDocumentSnapshot>

    suspend fun observeRecovery(path: String): WorkspaceRecoveryEditorObservation =
        throw WorkspaceRecoveryEditorUnavailableException("Editor cannot provide generation-bound recovery admission")

    suspend fun observeAllDocuments(): WorkspaceEditorObservation =
        throw WorkspaceRecoveryEditorUnavailableException("Editor cannot provide an all-document ownership observation")

    suspend fun dirtySnapshots(): List<WorkspaceDocumentSnapshot> = snapshots().filter { it.dirty }

    /** Capture/checkpoint on the editor owner; the returned final admission must not suspend. */
    suspend fun prepareProjectHandoff(gate: WorkspaceHandoffGate = WorkspaceHandoffGate()): WorkspaceHandoffAdmission =
        error("The bound editor cannot verify project handoff readiness")

    // Search cannot safely fall back to an unbounded legacy snapshot list.
    suspend fun captureSearchSnapshots(request: WorkspaceSearchCaptureRequest): WorkspaceSearchCapture =
        WorkspaceSearchCapture(supported = false)

    /** Bounded explicit save; the final non-suspending publication must run inside publicationFence. */
    suspend fun saveDocuments(
        requests: List<WorkspaceDocumentSaveRequest>,
        requireCurrent: () -> Unit = {},
        publicationFence: ((() -> Unit) -> Unit) = { it() },
    ): WorkspaceDocumentSaveReport = WorkspaceDocumentSaveReport.unavailable(requests)

    suspend fun pendingAgentSavePaths(): List<String> = emptyList()

    // Implementations must never persist the new content to disk as a side effect.

    suspend fun replaceText(
        path: String,
        expected: WorkspaceDocumentVersion,
        content: String,
        reason: String,
    ): WorkspaceDocumentMutationResult = WorkspaceDocumentMutationResult.NotLive

    /** Optional native source publication. No disk fallback and no implicit save. The fence must wrap
     * the entire non-suspending native transaction, not just its preflight. */
    suspend fun replaceSourceText(
        path: String, expected: WorkspaceDocumentVersion, replacement: WorkspaceSourceTextReplacement,
        reason: String, requireCurrent: () -> Unit,
        onApplied: (WorkspaceSourceTextReplacement.Publication) -> Unit,
        publicationFence: ((() -> WorkspaceDocumentMutationResult) -> WorkspaceDocumentMutationResult) = { it() },
    ): WorkspaceDocumentMutationResult = throw WorkspaceRecoveryEditorUnavailableException(
        "This editor does not provide source-backed live publication")

    suspend fun validatePersistedMutation(mutation: FileRepository.Mutation) = Unit

    suspend fun persistedMutation(mutation: FileRepository.Mutation): WorkspacePersistedReconciliation =
        WorkspacePersistedReconciliation()

    suspend fun reconcilePersistedWorkspace(): WorkspacePersistedReconciliation =
        WorkspacePersistedReconciliation()

    /** Explicit post-mutation reconciliation; observation-only polling uses the method above. */
    suspend fun reconcileAfterPersistedMutation(): WorkspacePersistedReconciliation =
        reconcilePersistedWorkspace()
}

private const val COMMAND_RECONCILIATION_TIMEOUT_MS = 5_000L

internal suspend fun WorkspaceDocumentAuthority.reconcileAfterExternalCommand(): WorkspacePersistedReconciliation {
    val authority = this
    return withTimeoutOrNull(COMMAND_RECONCILIATION_TIMEOUT_MS) {
        if (authority is WorkspaceDocumentAuthorityBridge) authority.reconcileExternalCommand()
        else authority.reconcilePersistedWorkspace()
    } ?: error("Workspace refresh timed out; file changes were retained")
}

// Bindings are generation-scoped: closing an old binding can never detach a newer editor
// authority.

class WorkspaceDocumentAuthorityBridge(
    private val onPersistedChange: () -> Unit = {},
) : WorkspaceDocumentAuthority {
    private val lock = Any()
    private var generation = 0L
    private var delegate: WorkspaceDocumentAuthority? = null
    private val _persistedRevision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val persistedRevision: kotlinx.coroutines.flow.StateFlow<Long> = _persistedRevision

    class Binding internal constructor(
        private val owner: WorkspaceDocumentAuthorityBridge,
        internal val generation: Long,
    ) : AutoCloseable {
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            owner.unbind(generation)
        }
    }

    fun bind(authority: WorkspaceDocumentAuthority): Binding = synchronized(lock) {
        generation += 1L
        delegate = authority
        Binding(this, generation)
    }

    val isBound: Boolean get() = synchronized(lock) { delegate != null }

    override suspend fun snapshot(path: String): WorkspaceDocumentSnapshot? =
        currentDelegate()?.snapshot(path)

    override suspend fun snapshots(): List<WorkspaceDocumentSnapshot> =
        currentDelegate()?.snapshots() ?: emptyList()

    override suspend fun dirtySnapshots(): List<WorkspaceDocumentSnapshot> =
        currentDelegate()?.dirtySnapshots() ?: emptyList()

    override suspend fun commitDiskOnly(path: String, action: () -> Unit) {
        val captured = synchronized(lock) {
            val current = delegate ?: throw WorkspaceRecoveryEditorUnavailableException("Workspace editor is detached")
            generation to current
        }
        val caller = currentCoroutineContext()
        caller.ensureActive()
        captured.second.commitDiskOnly(path) {
            synchronized(lock) {
                caller.ensureActive()
                check(generation == captured.first && delegate === captured.second) {
                    "Editor ownership changed before disk-only publication"
                }
                action()
            }
        }
    }

    override suspend fun observeRecovery(path: String): WorkspaceRecoveryEditorObservation {
        val captured = synchronized(lock) {
            val current = delegate ?: throw WorkspaceRecoveryEditorUnavailableException(
                if (generation == 0L) "Workspace editor has not attached" else "Workspace editor is detached")
            generation to current
        }
        fun requireOwner() {
            check(generation == captured.first && delegate === captured.second) {
                "Editor ownership changed during recovery; inspect again"
            }
        }
        val observed = captured.second.observeRecovery(path)
        synchronized(lock) { requireOwner() }
        return WorkspaceRecoveryEditorObservation(observed.snapshots, observed.pendingPaths,
            validateOwner = {
                synchronized(lock) { requireOwner() }
                observed.validate()
                synchronized(lock) { requireOwner() }
            },
            commitUnderBarrier = if (!observed.canCommit) null else { action ->
                observed.commitClean {
                    synchronized(lock) { requireOwner(); action() }
                }
            })
    }

    override suspend fun observeAllDocuments(): WorkspaceEditorObservation {
        val captured = synchronized(lock) {
            val current = delegate ?: throw WorkspaceRecoveryEditorUnavailableException(
                if (generation == 0L) "Workspace editor has not attached" else "Workspace editor is detached")
            generation to current
        }
        fun requireOwner() {
            check(generation == captured.first && delegate === captured.second) {
                "Editor ownership changed during all-document observation"
            }
        }
        val observed = captured.second.observeAllDocuments()
        synchronized(lock) { requireOwner() }
        return WorkspaceEditorObservation(observed.snapshots, observed.pendingSaves, observed.pendingSync,
            observed.retainedPaths, validateOwner = {
                synchronized(lock) { requireOwner() }
                observed.validate()
                synchronized(lock) { requireOwner() }
            })
    }

    override suspend fun prepareProjectHandoff(gate: WorkspaceHandoffGate): WorkspaceHandoffAdmission {
        val captured = synchronized(lock) { generation to delegate }
        val admission = captured.second?.prepareProjectHandoff(gate) ?: WorkspaceHandoffAdmission.Empty
        return object : WorkspaceHandoffAdmission {
            private fun requireOwner() = synchronized(lock) {
                check(generation == captured.first && delegate === captured.second) {
                    "Editor ownership changed while preparing the project switch"
                }
            }
            override fun invoke() {
                try {
                    requireOwner()
                    admission()
                    requireOwner()
                } catch (failure: Throwable) {

                    admission.abort()
                    throw failure
                }
            }
            override fun commit() = admission.commit()
            override fun abort() = admission.abort()
        }
    }

    override suspend fun captureSearchSnapshots(request: WorkspaceSearchCaptureRequest): WorkspaceSearchCapture {
        val capturedDelegate = currentDelegate()
        return capturedDelegate?.captureSearchSnapshots(request) ?: WorkspaceSearchCapture()
    }

    override suspend fun replaceText(
        path: String,
        expected: WorkspaceDocumentVersion,
        content: String,
        reason: String,
    ): WorkspaceDocumentMutationResult = currentDelegate()?.replaceText(path, expected, content, reason)
        ?: WorkspaceDocumentMutationResult.NotLive

    override suspend fun saveDocuments(
        requests: List<WorkspaceDocumentSaveRequest>, requireCurrent: () -> Unit,
        publicationFence: ((() -> Unit) -> Unit),
    ): WorkspaceDocumentSaveReport {
        val plan = requests.toList()
        val captured = synchronized(lock) { generation to delegate }
        val owner = captured.second ?: return WorkspaceDocumentSaveReport.unavailable(plan)
        val caller = currentCoroutineContext()
        fun current() {
            caller.ensureActive(); requireCurrent()
            synchronized(lock) {
                check(generation == captured.first && delegate === owner) { "Editor owner changed before Save" }
            }
        }
        current()
        return owner.saveDocuments(plan, ::current) { publish ->
            synchronized(lock) { current(); publicationFence(publish) }
        }
    }

    override suspend fun pendingAgentSavePaths(): List<String> {
        val captured = synchronized(lock) { generation to delegate }
        val paths = captured.second?.pendingAgentSavePaths().orEmpty()
        synchronized(lock) {
            check(generation == captured.first && delegate === captured.second) { "Editor owner changed while checking Save readiness" }
        }
        return paths
    }

    override suspend fun replaceSourceText(
        path: String, expected: WorkspaceDocumentVersion, replacement: WorkspaceSourceTextReplacement,
        reason: String, requireCurrent: () -> Unit,
        onApplied: (WorkspaceSourceTextReplacement.Publication) -> Unit,
        publicationFence: ((() -> WorkspaceDocumentMutationResult) -> WorkspaceDocumentMutationResult),
    ): WorkspaceDocumentMutationResult {
        val captured = synchronized(lock) {
            val current = delegate ?: throw WorkspaceRecoveryEditorUnavailableException("Workspace editor is detached")
            generation to current
        }
        val caller = currentCoroutineContext()
        fun current() {
            caller.ensureActive(); requireCurrent()
            synchronized(lock) {
                check(generation == captured.first && delegate === captured.second) {
                    "Editor ownership changed before source-backed publication"
                }
            }
        }
        current()
        return captured.second.replaceSourceText(path, expected, replacement, reason, ::current, onApplied) { apply ->
            synchronized(lock) { current(); publicationFence(apply) }
        }
    }

    override suspend fun validatePersistedMutation(mutation: FileRepository.Mutation) {
        val captured = synchronized(lock) {
            check(delegate != null || generation == 0L) {
                "Editor authority is detached; reconnect the workspace editor before writing. Unsaved work was preserved."
            }
            generation to delegate
        }
        captured.second?.validatePersistedMutation(mutation)
        synchronized(lock) {
            check(generation == captured.first && delegate === captured.second) {
                "Editor ownership changed during mutation admission; review the current workspace and retry."
            }
        }
    }

    override suspend fun persistedMutation(mutation: FileRepository.Mutation): WorkspacePersistedReconciliation =
        afterPersistedChange { currentDelegate()?.persistedMutation(mutation) ?: WorkspacePersistedReconciliation() }

    override suspend fun reconcilePersistedWorkspace(): WorkspacePersistedReconciliation =
        currentDelegate()?.reconcilePersistedWorkspace() ?: WorkspacePersistedReconciliation()

    override suspend fun reconcileAfterPersistedMutation(): WorkspacePersistedReconciliation =
        afterPersistedChange { currentDelegate()?.reconcileAfterPersistedMutation() ?: WorkspacePersistedReconciliation() }

    internal suspend fun reconcileExternalCommand(): WorkspacePersistedReconciliation {
        var observedMutation = true
        return afterPersistedChange(invalidate = { observedMutation }) {
            val result = currentDelegate()?.reconcilePersistedWorkspace() ?: WorkspacePersistedReconciliation()
            observedMutation = result.changed || result.conflicts.isNotEmpty()
            result
        }
    }

    private suspend fun afterPersistedChange(
        invalidate: () -> Boolean = { true },
        action: suspend () -> WorkspacePersistedReconciliation,
    ): WorkspacePersistedReconciliation {
        var originalFailure: Throwable? = null
        try { return action() }
        catch (failure: Throwable) { originalFailure = failure; throw failure }
        finally {
            // Disk publication precedes this boundary, even if editor reconciliation fails. Preserve that
            // exact failure/cancellation if invalidation also fails.
            try {
                _persistedRevision.update { it + 1 }
                if (invalidate()) onPersistedChange()
            }
            catch (failure: Throwable) {
                if (originalFailure == null) throw failure
                if (failure !== originalFailure) originalFailure.addSuppressed(failure)
            }
        }
    }

    private fun currentDelegate(): WorkspaceDocumentAuthority? = synchronized(lock) { delegate }

    private fun unbind(bindingGeneration: Long) = synchronized(lock) {
        if (generation == bindingGeneration) delegate = null
    }
}
