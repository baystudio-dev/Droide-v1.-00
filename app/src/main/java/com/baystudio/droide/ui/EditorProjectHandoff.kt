package com.baystudio.droide.ui

import com.baystudio.droide.core.DiskFileVersion
import com.baystudio.droide.core.EditorRecoverySnapshot
import com.baystudio.droide.core.EditorStateStore
import com.baystudio.droide.core.WorkspaceFailureReason
import com.baystudio.droide.core.WorkspaceHandoffAdmission
import com.baystudio.droide.core.WorkspaceHandoffGate
import com.baystudio.droide.core.WorkspaceHandoffRetention
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal interface EditorNativeHandoffLease {
    fun commit()
    fun abort()
}

/** Exact view, not whichever pane happens to be active when a delayed callback returns. */
internal class EditorNativeHandoffBinding(
    val owner: Any,
    val begin: ((Throwable?) -> Unit) -> EditorNativeHandoffLease,
    val requireReady: () -> Unit,
)

private data class HandoffDocument(
    val document: EditorDocument,
    val path: String,
    val revision: Int,
    val changeVersion: Int,
    val viewRevision: Long,
    val diskVersion: DiskFileVersion?,
)

/** Preparation may suspend. Only the final invocation seals this exact Main-owned view/model/store. */
internal suspend fun prepareEditorProjectHandoff(
    state: EditorWorkspaceState,
    store: EditorStateStore?,
    gate: WorkspaceHandoffGate = WorkspaceHandoffGate(),
    preservationScope: CoroutineScope? = null,
    reportPreservationFailure: (WorkspaceFailureReason, Throwable, () -> Boolean) -> Unit = { _, _, _ -> },
): WorkspaceHandoffAdmission {
    gate.requireClear()
    fun requireReady() {
        state.captureForProjectHandoff()
        check(state.allDocuments().none {
            it.persistenceRequired || it.requiresBufferCapture || it.isSaveInProgress || it.saveSynchronizationPending
        }) { "Save or discard unsaved editor changes and finish pending saves before switching projects" }
    }
    fun documents() = state.allDocuments().map {
        HandoffDocument(it, it.path, it.revision, it.changeVersion, it.viewStateRevision, it.savedDiskVersion)
    }
    if (store != null) check(state.recoveryInitialized) {
        "Editor recovery is not ready; the previous checkpoint was preserved"
    }
    requireReady()
    val navigation = state.navigation
    val navigationRevision = state.navigationRevision
    val capturedDocuments = documents()
    val captureOwner = state.projectHandoffOwner()
    val native = state.nativeForProjectHandoff()
    fun snapshot() = state.recoverySnapshot(navigation?.openFiles.orEmpty(), navigation?.activeFile.orEmpty())
        ?: state.emptyRecoverySnapshot()
    if (store != null) store.save(snapshot())
    fun validate() {
        check(state.projectHandoffOwner() == captureOwner) {
            "Native editor ownership changed while preparing the project switch; switch again"
        }
        requireReady()
        check(state.navigationRevision == navigationRevision && state.navigation == navigation &&
            documents() == capturedDocuments) {
            "Editor state changed while preparing the project switch; review and switch again"
        }
        native?.requireReady?.invoke()
    }
    validate()
    return object : WorkspaceHandoffAdmission, WorkspaceHandoffRetention {
        private val owner = Any()
        private var token: EditorStateStore.RetirementToken? = null
        private var nativeLease: EditorNativeHandoffLease? = null
        private var admitted = false
        private var committed = false
        private var aborted = false
        private var released = false
        private var releaseFailure: Throwable? = null
        private var pendingSnapshot: EditorRecoverySnapshot? = null
        private var preservationRunning = false
        private var preservationFailure: Throwable? = null
        private var reportedReason: WorkspaceFailureReason? = null

        override fun invoke() {
            check(!aborted && !committed) { "Editor admission is no longer tentative" }
            if (admitted) { validate(); return }
            try {
                gate.requireClear()
                validate()
                if (store != null) token = checkNotNull(store.tryRetireWrites()) {
                    "Editor recovery write is still in progress; switch again after it finishes"
                }
                state.sealForProjectHandoff(owner)
                if (native != null) {
                    gate.reserve(this)
                    nativeLease = native.begin(::onReleased)
                }
                // Finishing composition may have delivered text. It must be captured and
                // checked before acceptance, never suppressed by read-only/handoff policy.
                validate()
                admitted = true
            } catch (failure: Throwable) {
                abort()
                throw failure
            }
        }

        override fun commit() {
            // All checks belong to invoke. No user callback, IO, or throwing validation here.
            if (!admitted || committed || aborted) return
            committed = true
            token?.let { store?.commitRetirement(it) }
            nativeLease?.commit()
        }

        override fun abort() {
            if (committed || aborted) return
            aborted = true
            nativeLease?.abort()
            state.abortProjectHandoff(owner)
            token?.let { store?.abortRetirement(it) }
            gate.clear(this)
        }

        private fun rememberFailure(
            failure: Throwable,
            expectedPublication: Any? = gate.publicationFor(this),
        ) {
            preservationFailure = failure
            val reason = if (pendingSnapshot == null || releaseFailure != null) {
                WorkspaceFailureReason.EDITOR_CAPTURE_BLOCKED
            } else WorkspaceFailureReason.EDITOR_PRESERVATION_RETRY
            if (expectedPublication == null || reportedReason == reason) return
            // Validate exact A/B now; the UI predicate retains only gate + generations.
            val isCurrent = gate.noticeValidityFor(this, expectedPublication) ?: return
            reportedReason = reason
            // Keep the original error and retained owner even if presentation itself fails.
            try { reportPreservationFailure(reason, failure, isCurrent) } catch (_: Throwable) { }
        }

        private fun onReleased(failure: Throwable?) {
            if (released) return
            released = true
            releaseFailure = failure
            try {
                if (failure != null) throw IllegalStateException(
                    "Previous editor capture/release failed; pending unsaved changes are retained and switching is blocked",
                    failure,
                )
                if (documents() == capturedDocuments) {
                    gate.clear(this)
                    nativeLease = null
                    return
                }
                // One immutable release-time snapshot. No recovery data is acknowledged
                // until the exact retired store has accepted this write.
                pendingSnapshot = snapshot()
                val scope = preservationScope
                if (scope != null) scope.launch {
                    try { preserveOnce() } catch (error: Throwable) {
                        if (preservationFailure !== error) rememberFailure(error)
                    }
                }
            } catch (error: Throwable) {
                rememberFailure(error)
            }
        }

        private suspend fun preserveOnce() {
            check(!preservationRunning) { "Pending unsaved editor changes are still being preserved; switch again after it finishes" }
            val checkpoint = checkNotNull(pendingSnapshot) {
                "Previous editor capture is incomplete; pending unsaved changes remain retained and switching is blocked"
            }
            val recovery = checkNotNull(store) { "No recovery store is available for pending unsaved editor changes" }
            val retirement = checkNotNull(token) { "The previous editor recovery owner is unavailable" }
            val publication = checkNotNull(gate.publicationFor(this)) {
                "Published workspace ownership is unavailable; pending unsaved changes remain retained"
            }
            preservationRunning = true
            try {
                recovery.saveRetired(checkpoint, retirement)
                check(gate.acknowledgePreservation(this, publication)) {
                    "Workspace ownership changed before preservation acknowledgement; pending unsaved changes remain retained"
                }
                preservationFailure = null
                pendingSnapshot = null
                nativeLease = null
            } catch (error: Throwable) {
                val failure = IllegalStateException(
                    "Could not preserve pending unsaved changes from the previous project; switch again to retry. The edits remain in memory, not yet acknowledged in recovery.",
                    error,
                )
                rememberFailure(failure, publication)
                throw failure
            } finally {
                preservationRunning = false
            }
        }

        override suspend fun retryPreservation() {
            check(!preservationRunning) { "Pending unsaved editor changes are still being preserved; switch again after it finishes" }
            check(released) { "Previous editor has not finished releasing; pending changes are retained. Switch again after it finishes." }
            if (releaseFailure != null || pendingSnapshot == null) throw IllegalStateException(
                "Previous editor capture is incomplete; pending unsaved changes are retained and switching is blocked",
                preservationFailure ?: releaseFailure,
            )
            // Only an explicit switch retry permits one more actionable notice.
            reportedReason = null
            preserveOnce()
        }
    }
}
