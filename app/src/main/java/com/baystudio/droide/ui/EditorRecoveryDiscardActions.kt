package com.baystudio.droide.ui

import kotlinx.coroutines.flow.StateFlow

/** UI boundary only. The workspace owner owns confirmed operations and reconciliation,
 * not the lifetime of the dialog. prepare never authorizes removal. */
internal interface EditorRecoveryDiscardActions {
    val state: StateFlow<EditorRecoveryDiscardUiState>
    suspend fun prepare(copy: EditorWorkspaceState.RetainedCopy): EditorRecoveryDiscardPreview
    /** Called exactly once for a preview after the explicit irreversible confirmation.
     * Must publish WORKING/PENDING synchronously before returning; do not throw after acceptance. */
    fun confirm(preview: EditorRecoveryDiscardPreview)
    /** Revokes only an unconfirmed preview. Must be non-throwing, idempotent and never undo a confirmed operation. */
    fun revoke(preview: EditorRecoveryDiscardPreview)
    /** Continue only the existing confirmed selection, never select another copy. */
    fun reconcile()
}

internal class EditorRecoveryDiscardPreview(
    val copy: EditorWorkspaceState.RetainedCopy,
    val ciphertextBytes: Long,
    val token: Any,
) {
    init { require(ciphertextBytes >= 0) }
    val path: String get() = copy.path
}

internal enum class EditorRecoveryDiscardMode { IDLE, WORKING, PENDING, REMOVED, KEPT }

internal data class EditorRecoveryDiscardUiState(
    val mode: EditorRecoveryDiscardMode = EditorRecoveryDiscardMode.IDLE,
    val path: String? = null,
    val detail: String? = null,
    /** Increase whenever a completed operation changes the retained-copy inventory. */
    val revision: Long = 0L,
) {
    val blocksNewAction get() = mode == EditorRecoveryDiscardMode.WORKING || mode == EditorRecoveryDiscardMode.PENDING
}
