package com.baystudio.droide.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Brackets the suspending root check with owner checks on the owner's dispatcher. */
internal class CatalogEditorOwnerValidator(
    private val checkOwner: suspend () -> Unit,
    private val checkRoot: suspend () -> Unit,
) {
    suspend fun validateIdentity() {
        currentCoroutineContext().ensureActive()
        checkOwner()
        currentCoroutineContext().ensureActive()
    }
    suspend fun validate() {
        validateIdentity()
        checkRoot()
        validateIdentity()
    }
}

/** Coordinator-owned observation and validator. No mutation or process-closure capability. */
internal class CatalogEditorReview(
    private val catalogRoot: String,
    private val editor: WorkspaceEditorObservation,
    private val handoffPending: Boolean,
    private val validateIdentity: suspend () -> Unit,
    private val processes: CatalogProcessObservation? = null,
    private val terminals: CatalogTerminalObservation? = null,
    private val children: ChildOwnershipObservation? = null,
    private val validateOwner: suspend () -> Unit,
) {
    suspend fun validate() {
        validateOwner()
        terminals?.validate()
        editor.validate()
        validateOwner()
        // The owner/root check may suspend. Refresh editor evidence after it, then
        // validate the non-suspending ledger snapshots last, without further owner IO.
        editor.validate()
        // The last editor validation may dispatch to its owner. Check coordinator identity
        // afterward without restarting root IO, then finish with ledger-only validation.
        validateIdentity()
        // No suspending observer follows this final metadata phase. These checks detect
        // known callback mutations, not concurrent writer exclusion or an atomic snapshot.
        terminals?.validateMetadata()
        children?.validateMetadata()
        processes?.validate()
    }

    suspend fun review(update: ProjectCatalogUpdate, selectedAt: CatalogReleasePreflight.Snapshot,
                       selectedIds: List<String>): CatalogReleasePreflight.Result {
        val selection = selectedIds.toList()
        check(selectedAt.scope.root == catalogRoot) { "Selected catalog belongs to a different application" }
        validate()
        val current = update.collectReleaseReview()
        check(current.scope.root == catalogRoot) { "Collected catalog belongs to a different application" }
        validate()
        val evidence = CatalogReleasePreflight.EditorEvidence(
            editor.snapshots.filter { it.dirty }.map { it.path }.sorted(),
            editor.pendingSaves.sorted(), editor.pendingSync.sorted(),
            (editor.retainedPaths + if (handoffPending) setOf("retained-editor-handoff") else emptySet()).sorted(),
        )
        // Participating process observations always retain explicit coverage/closure unknowns.
        return CatalogReleasePreflight.evaluate(selectedAt, current, selection, evidence, processes = processes?.evidence(terminals?.evidence(), children?.evidence()))
    }
}
