package com.baystudio.droide.ui

import android.os.Looper
import com.baystudio.droide.core.DiskFileVersion
import com.baystudio.droide.core.EditorRecoveryBuffer
import com.baystudio.droide.core.EditorRecoverySnapshot
import com.baystudio.droide.core.EditorSelectionSnapshot
import com.baystudio.droide.core.EditorStateStore
import kotlinx.coroutines.CompletableDeferred

/** Main-owned Exit preparation. */

internal suspend fun prepareEditorExitCheckpoint(
    state: EditorWorkspaceState,
    store: EditorStateStore,
): EditorExitCheckpoint {
    requireEditorExitMain()

    while (true) {
        val saving = state.allDocuments().filter { it.isSaveInProgress }
        if (saving.isEmpty()) break
        saving.forEach { it.awaitPendingSaveForExit() }
    }
    return prepareEditorExitCheckpointNow(state, store)
}

internal fun prepareEditorExitCheckpointNow(
    state: EditorWorkspaceState,
    store: EditorStateStore,
): EditorExitCheckpoint {
    requireEditorExitMain()
    return EditorExitCheckpoint(state, store).also { it.prepare() }
}

private data class ExitDocumentVersion(
    val document: EditorDocument,
    val path: String,
    val revision: Int,
    val changeVersion: Int,
    val viewRevision: Long,
    val diskVersion: DiskFileVersion?,
)

internal class EditorExitCheckpoint internal constructor(
    private val state: EditorWorkspaceState,
    private val store: EditorStateStore,
) {
    private val owner = Any()
    private var token: EditorStateStore.RetirementToken? = null
    private var native: EditorNativeHandoffBinding? = null
    private var nativeLease: EditorNativeHandoffLease? = null
    private var captureOwner: Pair<Any?, Any?>? = null
    private var navigation: EditorWorkspaceState.Navigation? = null
    private var navigationRevision = 0L
    private var versions: Map<String, ExitDocumentVersion> = emptyMap()
    private var pendingSnapshot: EditorRecoverySnapshot? = null
    private var releasedSnapshot: EditorRecoverySnapshot? = null
    private var releasedVersions: Map<String, ExitDocumentVersion>? = null
    private var releasedNavigation: EditorWorkspaceState.Navigation? = null
    private var releasedNavigationRevision = 0L
    private val release = CompletableDeferred<Unit>()
    private var releaseFailure: Throwable? = null
    private var releaseReceiptInvalidated = false
    private var operationRunning = false
    private var prepared = false
    private var aborted = false
    private var initialVerified = false
    private var finalVerified = false
    private var nativeReleaseAllowedForRecreation = false
    private var preserveUnrestoredRecovery = false
    private var unrestoredLoaded = false
    private var unrestoredSnapshot: EditorRecoverySnapshot? = null
    private var verifiedModelSnapshot: EditorRecoverySnapshot? = null

    var releaseCommitted: Boolean = false
        private set
    val releaseObserved: Boolean get() = release.isCompleted
    val preservationComplete: Boolean get() = finalVerified
    val finalCaptureFailed: Boolean get() = release.isCompleted && (releaseFailure != null || releaseReceiptInvalidated)
    val recreationReleasePending: Boolean get() = nativeReleaseAllowedForRecreation && !release.isCompleted
    val nativeOwnerToRelease: Any? get() = native?.owner
    var lastFailure: Throwable? = null
        private set

    internal fun prepare() {
        requireEditorExitMain()
        check(!prepared && !aborted) { "Editor Exit preparation already started" }
        state.requireMutableOwner()
        preserveUnrestoredRecovery = !state.recoveryInitialized
        requireNoSave()
        state.captureForProjectHandoff()
        requireNoSave()
        native = state.nativeForProjectHandoff()
        captureOwner = state.projectHandoffOwner()
        native?.requireReady?.invoke()
        // Capture the original native owner synchronously, even if existing store IO is busy.
        // saveAndVerify drains that IO before admitting our first checkpoint.
        token = store.tryRetireWrites()
        try {
            state.sealForProjectHandoff(owner)

            nativeLease = native?.begin(::onReleased)
            capturePreparedSnapshot()
            prepared = true
        } catch (failure: Throwable) {
            lastFailure = failure
            try { abortBeforeRelease() } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    /** One bounded attempt. A failure keeps the frozen data/token available for explicit retry. */
    suspend fun saveAndVerify() {
        requireEditorExitMain()
        check(prepared && !aborted) { "Editor Exit preparation is no longer available" }
        if (releaseCommitted) {
            check(initialVerified) { "Editor Exit has no verified initial checkpoint" }
            return
        }
        check(!operationRunning) { "Editor recovery is already being preserved" }
        operationRunning = true
        try {
            awaitRecreationRelease()
            // A raw native callback can deliver text while frozen. Each explicit retry takes the latest
            // exact-owner snapshot, never reuses an older failed immutable copy.
            val previousVersions = versions
            val previousSnapshot = pendingSnapshot
            capturePreparedSnapshot()
            if (initialVerified && versions == previousVersions && pendingSnapshot == previousSnapshot) {
                lastFailure = null
                return
            }
            initialVerified = false
            val snapshot = checkNotNull(pendingSnapshot)
            preserveSnapshot(snapshot, committed = false)
            // Activity destruction can happen while the store is on IO. Its exact release receipt is
            // mandatory; a changed final buffer requires an explicit fresh save.
            awaitRecreationRelease()
            validatePreparedSnapshot()
            verifiedModelSnapshot = snapshot
            initialVerified = true
            lastFailure = null
        } catch (failure: Throwable) {
            initialVerified = false
            lastFailure = failure
            throw failure
        } finally {
            operationRunning = false
        }
    }

    /** Commits only the exact native lease so release can acknowledge its final capture even while
     * store preservation is still tentative/in flight. */
    fun allowNativeReleaseForRecreation() {
        requireEditorExitMain()
        check(prepared && !aborted) { "Editor Exit preparation is no longer available" }
        if (releaseCommitted || nativeReleaseAllowedForRecreation) return
        nativeReleaseAllowedForRecreation = true
        try {
            nativeLease?.commit()
            if (native == null) onReleased(null)
        } catch (failure: Throwable) {
            lastFailure = failure
            throw failure
        }
    }

    /** No suspension. After this succeeds the caller unmounts the workspace UI, but retains this
     * checkpoint and a stopped recovery panel until awaitReleaseAndCheckpoint succeeds. */
    fun commitForRelease() {
        requireEditorExitMain()
        if (releaseCommitted) return
        check(prepared && !aborted && !operationRunning && initialVerified) {
            "Preserve and verify editor recovery before releasing the editor"
        }
        try {
            validatePreparedSnapshot()
            check(store.commitRetirement(checkNotNull(token))) { "Editor recovery ownership changed" }
            releaseCommitted = true
            nativeLease?.commit()
            if (native == null) onReleased(null)
        } catch (failure: Throwable) {
            lastFailure = failure
            throw failure
        }
    }

    /** Repeated calls are safe; cancellation never destroys the retained recovery payload. */
    suspend fun awaitReleaseAndCheckpoint() {
        requireEditorExitMain()
        check(releaseCommitted) { "Editor release has not been committed" }
        release.await()
        retryFinalCheckpoint()
    }

    /** Retry storage/readback after release without restarting any editor or app work. */
    suspend fun retryFinalCheckpoint() {
        requireEditorExitMain()
        check(releaseCommitted && release.isCompleted) { "The editor has not finished releasing" }
        check(!operationRunning) { "Editor recovery is already being preserved" }
        releaseFailure?.let { throw IllegalStateException(
            "Editor release or final capture failed; retained edits must be recovered before Exit can finish", it,
        ).also { failure -> lastFailure = failure } }
        operationRunning = true
        try {
            requireSuccessfulRelease()
            requireNoReplacementOwner()
            if (finalVerified) return
            // Native release has already captured before AND after super.release. Do not call a
            // disposed/replaced pane's bridge; preserve the retained model directly.
            val finalSnapshot = snapshot()
            pendingSnapshot = finalSnapshot
            val finalVersions = documentVersions()
            // The store is exclusively retired to us. Reuse a verified checkpoint only when the exact
            // release receipt has the same model; final IME deltas still get saved.
            if (finalSnapshot != verifiedModelSnapshot) preserveSnapshot(finalSnapshot, committed = true)
            requireSuccessfulRelease()
            requireNoReplacementOwner()
            check(documentVersions() == finalVersions && snapshot() == finalSnapshot) {
                "Editor text changed during final preservation; retained edits need another checkpoint"
            }
            finalVerified = true
            verifiedModelSnapshot = finalSnapshot
            lastFailure = null
        } catch (failure: Throwable) {
            finalVerified = false
            lastFailure = failure
            throw failure
        } finally {
            operationRunning = false
        }
    }

    /** Only before release; caller must first cancel/join an in-flight checkpoint attempt. */
    fun abortBeforeRelease(): Boolean {
        requireEditorExitMain()
        if (releaseCommitted || nativeReleaseAllowedForRecreation) return false
        if (aborted) return true
        check(!operationRunning) { "Wait for editor preservation before cancelling Exit" }
        token?.let { check(store.abortRetirement(it)) { "Editor recovery retirement cannot be reversed" } }
        // Even an unexpected native abort failure must not leave the document model sealed.
        try { nativeLease?.abort() } finally {
            state.abortProjectHandoff(owner)
            aborted = true
            initialVerified = false
        }
        return true
    }

    private fun requireNoSave() {
        check(state.allDocuments().none { it.isSaveInProgress }) {
            "A file Save is still in progress; retry Exit after it finishes"
        }
    }

    private fun requireCaptureOwner() {
        if (nativeReleaseAllowedForRecreation) {
            check(release.isCompleted) { "The original editor is still releasing; its final capture is not confirmed" }
            requireSuccessfulRelease()
            requireNoReplacementOwner()
            requireNoSave()
            return
        }
        check(state.projectHandoffOwner() == captureOwner) {
            "The native editor changed during Exit; its retained edits must be captured before closing"
        }
        native?.requireReady?.invoke()
        requireNoSave()
    }

    private fun requireNoReplacementOwner() {
        val original = checkNotNull(captureOwner)
        val current = state.projectHandoffOwner()
        check((current.first == null || current.first === original.first) &&
            (current.second == null || current.second === original.second)) {
            "A replacement editor appeared during Exit; its bytes cannot replace the retained native capture"
        }
    }

    private fun capturePreparedSnapshot() {
        requireCaptureOwner()
        if (!nativeReleaseAllowedForRecreation) state.captureForProjectHandoff()
        requireCaptureOwner()
        navigation = state.navigation
        navigationRevision = state.navigationRevision
        pendingSnapshot = snapshot()
        versions = documentVersions()
    }

    private fun validatePreparedSnapshot() {
        requireCaptureOwner()
        if (!nativeReleaseAllowedForRecreation) state.captureForProjectHandoff()
        requireCaptureOwner()
        check(state.navigation == navigation && state.navigationRevision == navigationRevision && documentVersions() == versions && snapshot() == pendingSnapshot) {
            "Editor state changed while preserving Exit recovery; retry the checkpoint before closing"
        }
    }

    private fun documentVersions(): Map<String, ExitDocumentVersion> = state.allDocuments().associate { doc ->
        doc.path to ExitDocumentVersion(doc, doc.path, doc.revision, doc.changeVersion, doc.viewStateRevision, doc.savedDiskVersion)
    }

    private fun snapshot(): EditorRecoverySnapshot {
        val nav = state.navigation
        val captured = state.recoverySnapshot(nav?.openFiles.orEmpty(), nav?.activeFile.orEmpty())
            ?: state.emptyRecoverySnapshot()
        // Mirror the existing v6 presentation bounds without truncating a dirty/conflict body. Dirty
        // files beyond the ordinary tab bound are still restored by EditorTabRetention.
        val open = captured.openFiles.distinct().take(32)
        val relevant = (open + captured.dirtyPaths).toSet()
        return captured.copy(
            openFiles = open,
            selections = captured.selections.filterKeys { it in relevant }.mapValues { (_, selection) ->
                EditorSelectionSnapshot(selection.start.coerceIn(0, 100_000_000), selection.end.coerceIn(0, 100_000_000))
            },
            reviewFiles = captured.reviewFiles.filterTo(linkedSetOf()) { it in relevant },
        )
    }

    private fun onReleased(failure: Throwable?) {
        if (release.isCompleted) return
        releaseFailure = failure
        // Retain a valid copy even when release itself failed. Failure is never relabeled as durable
        // success: retryFinalCheckpoint keeps a capture/release blocker visible.
        try {
            requireNoReplacementOwner()
            releasedSnapshot = snapshot()
            releasedVersions = documentVersions()
            releasedNavigation = state.navigation
            releasedNavigationRevision = state.navigationRevision
        } catch (captureFailure: Throwable) {
            if (failure == null) releaseFailure = captureFailure
            else if (captureFailure !== failure) failure.addSuppressed(captureFailure)
        }
        release.complete(Unit)
    }

    private suspend fun awaitRecreationRelease() {
        if (!nativeReleaseAllowedForRecreation) return
        release.await()
        requireSuccessfulRelease()
    }

    private fun requireSuccessfulRelease() {
        releaseFailure?.let { throw IllegalStateException(
            "Original editor release/capture failed; retained edits are not acknowledged as fully preserved", it,
        ) }
        val matchesReceipt = release.isCompleted && releasedSnapshot != null && snapshot() == releasedSnapshot &&
            documentVersions() == releasedVersions && state.navigation == releasedNavigation &&
            state.navigationRevision == releasedNavigationRevision
        if (!matchesReceipt) releaseReceiptInvalidated = true
        check(matchesReceipt && !releaseReceiptInvalidated) {
            "Editor state no longer matches the original pane's final capture; retained data needs review before Exit"
        }
    }

    private suspend fun verifySnapshot(expected: EditorRecoverySnapshot) {
        val actual = checkNotNull(store.load()) { "Editor recovery readback is missing; retained edits were kept" }
        check(actual.openFiles == expected.openFiles && actual.activeFile == expected.activeFile &&
            actual.selections == expected.selections && actual.reviewFiles == expected.reviewFiles &&
            actual.dirtyPaths == expected.dirtyPaths && actual.conflictPaths == expected.conflictPaths) {
            "Editor recovery readback does not match the frozen workspace"
        }
        fun baselines(explicit: Map<String, DiskFileVersion>, deferred: Map<String, EditorRecoveryBuffer>) =
            deferred.mapNotNull { (path, ref) -> ref.diskVersion?.let { path to it } }.toMap() + explicit
        check(actual.diskVersions == baselines(expected.diskVersions, expected.deferredBuffers) &&
            actual.conflictDiskVersions == baselines(expected.conflictDiskVersions, expected.deferredConflictBuffers)) {
            "Editor recovery readback changed the original disk identity"
        }
        suspend fun verifyBodies(
            texts: Map<String, String>, references: Map<String, EditorRecoveryBuffer>,
            stored: Map<String, EditorRecoveryBuffer>,
        ) {
            for ((path, text) in texts) check(stored.getValue(path).readText() == text) {
                "Editor recovery text readback failed: $path"
            }
            for ((path, reference) in references) {
                val restored = stored.getValue(path)
                check(restored.blobName == reference.blobName &&
                    (reference.contentSha256 == null || restored.contentSha256 == reference.contentSha256)) {
                    "Deferred editor recovery ownership changed: $path"
                }
                // Sequential decrypt/checksum verification, not an eager copy of all deferred files.
                restored.readText()
            }
        }
        verifyBodies(expected.dirtyBuffers, expected.deferredBuffers, actual.deferredBuffers)
        verifyBodies(expected.conflictBuffers, expected.deferredConflictBuffers, actual.deferredConflictBuffers)
    }

    private suspend fun preserveSnapshot(model: EditorRecoverySnapshot, committed: Boolean) {
        val writeToken = token ?: store.retireWritesAfterPendingIo().also { token = it }
        // An unrestored backup is not a new unsaved edit. Do not rewrite, decrypt, or remove it merely
        // to close a clean workspace, including one whose recovery load failed.
        if (preserveUnrestoredRecovery && model.dirtyPaths.isEmpty() && model.conflictPaths.isEmpty()) return
        val checkpoint = if (preserveUnrestoredRecovery) {
            if (!unrestoredLoaded) {
                unrestoredSnapshot = store.load()
                unrestoredLoaded = true
            }
            mergeUnrestoredExitRecovery(model, unrestoredSnapshot).let { merged ->
                // Keep only older copies not adopted by the live model. This also makes retries/final native
                // deltas safe if publication succeeded but readback failed.
                unrestoredSnapshot = merged.retained
                merged.checkpoint
            }
        } else model
        if (committed) store.saveRetired(checkpoint, writeToken) else store.saveTentative(checkpoint, writeToken)
        verifySnapshot(checkpoint)
    }
}

private fun requireEditorExitMain() = check(Looper.myLooper() == Looper.getMainLooper()) {
    "Editor Exit capture and ownership require Main"
}
