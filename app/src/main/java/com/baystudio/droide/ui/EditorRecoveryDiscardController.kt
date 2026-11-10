package com.baystudio.droide.ui

import com.baystudio.droide.core.EditorRecoverySnapshot
import com.baystudio.droide.core.EditorStateStore
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runtime-owned, not dialog-owned. Only explicit confirm admits a selected permanent removal. */
internal class EditorRecoveryDiscardController(
    private val editor: EditorWorkspaceState,
    private val store: EditorStateStore,
    private val scope: CoroutineScope,
    private val allowed: () -> Boolean,
) : EditorRecoveryDiscardActions {
    private val status = MutableStateFlow(EditorRecoveryDiscardUiState())
    override val state = status.asStateFlow()
    private class PreviewToken(val owner: Any, val selection: EditorStateStore.ConflictDiscardSelection) { var valid = true }
    private class Pending(val selection: EditorStateStore.ConflictDiscardSelection, val transaction: EditorWorkspaceState.RecoveryDiscard) { var memoryFinished = false }
    private var pending: Pending? = null

    private fun snapshot(): EditorRecoverySnapshot {
        check(allowed() && editor.recoveryInitialized) { "Recovery must be ready in the active workspace" }
        editor.requireMutableOwner()
        editor.captureForProjectHandoff()
        val navigation = editor.navigation
        return editor.recoverySnapshot(navigation?.openFiles.orEmpty(), navigation?.activeFile.orEmpty()) ?: editor.emptyRecoverySnapshot()
    }
    override suspend fun prepare(copy: EditorWorkspaceState.RetainedCopy): EditorRecoveryDiscardPreview {
        check(!status.value.blocksNewAction)
        editor.requireRetainedCopy(copy)
        // Checkpoint encrypted recovery only. Never save editor contents into project files.
        store.save(snapshot())
        currentCoroutineContext().ensureActive()
        check(allowed()); editor.requireRetainedCopy(copy)
        val text = copy.text ?: checkNotNull(copy.deferred).readText()
        currentCoroutineContext().ensureActive()
        check(allowed()); editor.requireRetainedCopy(copy)
        val hash = withContext(Dispatchers.Default) { MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) } }
        val selected = store.prepareConflictDiscard(copy.path, hash)
        check(allowed()); editor.requireRetainedCopy(copy)
        return EditorRecoveryDiscardPreview(copy, selected.ciphertextBytes, PreviewToken(this, selected))
    }
    override fun revoke(preview: EditorRecoveryDiscardPreview) {
        (preview.token as? PreviewToken)?.takeIf { it.owner === this }?.valid = false
    }
    override fun confirm(preview: EditorRecoveryDiscardPreview) {
        if (status.value.blocksNewAction) return
        var transaction: EditorWorkspaceState.RecoveryDiscard? = null
        try {
            val token = preview.token as? PreviewToken ?: error("Unknown removal preview")
            check(token.owner === this && token.valid && allowed()) { "Removal preview expired; inspect again" }
            editor.requireRetainedCopy(preview.copy)
            // Capture before changing generation so failure leaves the original untouched.
            snapshot()
            transaction = editor.beginRecoveryDiscard(preview.copy)
            val navigation = editor.navigation
            val checkpoint = editor.snapshotForRecoveryDiscard(transaction, navigation?.openFiles.orEmpty(), navigation?.activeFile.orEmpty())
            token.valid = false
            val selected = Pending(token.selection, transaction)
            pending = selected
            status.value = status.value.copy(mode = EditorRecoveryDiscardMode.WORKING, path = preview.path,
                detail = "Removing only the confirmed older recovery copy…")
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    val result = store.discardConflict(checkpoint, selected.selection, editor::revokeDiscardCheckpoints)
                    finish(selected, result)
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable + Dispatchers.Main.immediate) { finish(selected, store.conflictDiscardOutcome(selected.selection, cancelled)) }
                    throw cancelled
                } catch (failure: Exception) { finish(selected, store.conflictDiscardOutcome(selected.selection, failure)) }
            }
        } catch (failure: Exception) {
            transaction?.let { editor.finishRecoveryDiscard(it, committed = false) }
            status.value = status.value.copy(mode = EditorRecoveryDiscardMode.KEPT, path = preview.path,
                detail = "Copy kept. ${failure.message}")
        }
    }
    private fun finish(selected: Pending, result: EditorStateStore.ConflictDiscardOutcome) {
        if (pending !== selected) return
        if (!selected.memoryFinished && result.phase != EditorStateStore.DiscardPhase.UNCERTAIN) {
            editor.finishRecoveryDiscard(selected.transaction, committed = result.phase == EditorStateStore.DiscardPhase.DURABLE)
            selected.memoryFinished = true
        }
        val mode = when {
            result.phase == EditorStateStore.DiscardPhase.UNCERTAIN -> EditorRecoveryDiscardMode.PENDING
            result.phase == EditorStateStore.DiscardPhase.PREPARED -> EditorRecoveryDiscardMode.KEPT
            !result.ciphertextRemoved -> EditorRecoveryDiscardMode.PENDING
            else -> EditorRecoveryDiscardMode.REMOVED
        }
        status.value = EditorRecoveryDiscardUiState(mode, selected.selection.path, result.message, status.value.revision + 1)
        if (mode == EditorRecoveryDiscardMode.REMOVED || mode == EditorRecoveryDiscardMode.KEPT) pending = null
    }
    override fun reconcile() {
        val selected = pending ?: return
        if (!allowed() || status.value.mode != EditorRecoveryDiscardMode.PENDING) return
        status.value = status.value.copy(mode = EditorRecoveryDiscardMode.WORKING, detail = "Checking the exact previously confirmed removal…")
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { finish(selected, store.reconcileConflictDiscard(selected.selection)) }
            catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.Main.immediate) { finish(selected, store.conflictDiscardOutcome(selected.selection, cancelled)) }
                throw cancelled
            } catch (failure: Exception) { finish(selected, store.conflictDiscardOutcome(selected.selection, failure)) }
        }
    }
}
