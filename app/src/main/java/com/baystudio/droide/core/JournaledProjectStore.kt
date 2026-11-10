package com.baystudio.droide.core

import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Explicit integration; supplied staging callbacks retain ownership of their files and recovery
// decisions.
internal class JournaledProjectStore(
    private val selection: ProjectCatalogAdmission.UpdateSelection,
    private val retention: CanonicalJournalRetention.Policy,
    private val stageProposal: (suspend (String) -> File)? = null,
    private val checkpoint: (String) -> Unit = {},
) : VersionedProjectStore {
    private val codec = FileProjectStore(selection.appFilesDir)
    private val catalog = File(selection.appFilesDir, ".droide/projects.json")
    private val active = File(selection.appFilesDir, ".droide/active_project.txt")
    private val writes = Mutex()

    override suspend fun load(): List<DroideProject> = loadCatalog().projects

    override suspend fun loadCatalog(): ProjectCatalogSnapshot = selection.readReadyCatalog()

    suspend fun inspectRecovery(): CanonicalFileOperationJournal.RecoveryMetadata = selection.inspectRecovery()

    suspend fun inspectOperationRecovery(operationId: String): CanonicalFileOperationJournal.OperationRecoveryReview =
        selection.inspectOperationRecovery(operationId)

    suspend fun readRetainedContent(operationId: String, role: CanonicalFileOperationJournal.CustodyRole,
                                   expected: CanonicalFileSnapshot.Version): CanonicalFileOperationJournal.RetainedContent =
        selection.readRetainedContent(operationId, role, expected)

    override suspend fun catalogRevision(all: List<DroideProject>): DiskFileVersion = codec.catalogRevision(all)

    override suspend fun save(all: List<DroideProject>): Unit =
        error("A caller-owned catalog revision is required; use saveCatalog before publishing changes")

    override suspend fun saveCatalog(all: List<DroideProject>, expected: DiskFileVersion): DiskFileVersion = writing(catalog) {
        require(expected.existed) { "A missing catalog requires explicit registration recovery" }
        val requested = all.toList()
        val text = codec.encodeCatalog(requested)
        val proposed = DiskFileVersion.fromText(text)
        val current = loadCatalog()
        if (current.revision != expected) throw ProjectCatalogConflictException()
        if (proposed == expected) return@writing current.revision

        val updater = selection.guardedUpdate(retention, context, checkpoint)
        updater.requireCapacity(expected, proposed)
        checkpoint("selected-store-before-stage")
        selection.requireCurrentBinding(context)
        beginPublication()
        val stage = stageProposal
        val prepared = if (stage == null) {
            updater.prepareOperation(requested, expected)
        } else {
            val proposal = stage(text)
            checkpoint("selected-store-staged")
            selection.requireCurrentBinding(context)
            updater.prepareOperation(requested, expected, proposal)
        }
        try {
            checkpoint("selected-store-prepared")
            selection.requireCurrentBinding(context)
            val published = updater.resume(prepared.prepared.operationId)
            checkpoint("selected-store-published")
            selection.requireCurrentBinding(context)
            val ready = loadCatalog()
            check(published.revision == proposed && ready.revision == proposed) {
                "Catalog changed after publication; the proposal and journal require review"
            }
            context.ensureActive()
            ready.revision
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            // One read-only proof for this known attempt. No retry, new admission, or guard reset.
            try {
                context.ensureActive()
                selection.requireCurrentBinding(context)
                val completed = updater.readCompleted(prepared)
                selection.requireCurrentBinding(context)
                check(completed.revision == proposed) { "Completed catalog differs from this request" }
                context.ensureActive()
                completed.revision
            } catch (cancelled: CancellationException) {
                if (cancelled !== failure) cancelled.addSuppressed(failure)
                throw cancelled
            } catch (fatal: Error) {
                fatal.addSuppressed(failure)
                throw fatal
            } catch (proofFailure: Exception) {
                if (proofFailure !== failure) failure.addSuppressed(proofFailure)
                throw failure
            }
        }
    }

    internal suspend fun enableMembershipCommands(): Unit = writing(catalog) {
        check(stageProposal == null) { "Membership commands require journal-owned proposal staging" }
        val updater = selection.guardedUpdate(retention, context, checkpoint)
        beginPublication()
        updater.enableMembershipCommands()
        selection.requireCurrentBinding(context)
    }

    internal suspend fun continueMembershipPreparation(token: String, operationId: String, envelopeHash: String): ProjectCatalogUpdate.Prepared = writing(catalog) {
        check(stageProposal == null) { "Membership commands require journal-owned proposal staging" }
        val updater = selection.guardedUpdate(retention, context, checkpoint)
        beginPublication()
        updater.continueMembershipPreparation(token, operationId, envelopeHash).also { selection.requireCurrentBinding(context) }
    }

    internal suspend fun completeMembershipCommand(token: String, operationId: String, admissionHash: String): CatalogMembershipCommands.Receipt = writing(catalog) {
        check(stageProposal == null) { "Membership commands require journal-owned proposal staging" }
        val updater = selection.guardedUpdate(retention, context, checkpoint)
        beginPublication()
        updater.completeMembershipCommand(token, operationId, admissionHash).also { selection.requireCurrentBinding(context) }
    }

    /** Explicit interrupted-phase recovery; no state adoption or acknowledgement. */
    internal suspend fun recoverMembershipCommand(token: String, operationId: String, admissionHash: String): CatalogMembershipCommands.Receipt = writing(catalog) {
        check(stageProposal == null) { "Membership commands require journal-owned proposal staging" }
        val updater = selection.guardedUpdate(retention, context, checkpoint)
        beginPublication()
        updater.recoverMembershipCommand(token, operationId, admissionHash).also { selection.requireCurrentBinding(context) }
    }

    internal suspend fun enableMembershipCompaction(): Unit = writing(catalog) {
        check(stageProposal == null) { "Membership commands require journal-owned proposal staging" }
        val updater = selection.guardedUpdate(retention, context, checkpoint)
        beginPublication()
        updater.enableMembershipCompaction()
        selection.requireCurrentBinding(context)
    }

    internal suspend fun inspectMembershipCommands(): List<CatalogMembershipCommands.Receipt> = withContext(Dispatchers.IO) {
        writes.withLock {
            val context = currentCoroutineContext()
            val result = selection.guardedUpdate(retention, context, checkpoint).membershipCommands()
            selection.requireCurrentBinding(context)
            context.ensureActive()
            result
        }
    }

    internal suspend fun expireAcceptedNoChange(token: String, outcome: String): CatalogMembershipCommands.Expiry = writing(catalog) {
        val updater = selection.guardedUpdate(retention, context, checkpoint)
        beginPublication()
        updater.expireAcceptedNoChange(token, outcome).also { selection.requireCurrentBinding(context) }
    }

    internal suspend fun expireAcceptedOperation(token: String, outcome: String): CatalogMembershipCommands.Expiry = writing(catalog) {
        val updater = selection.guardedUpdate(retention, context, checkpoint)
        beginPublication()
        updater.expireAcceptedOperation(token, outcome).also { selection.requireCurrentBinding(context) }
    }

    internal suspend fun acknowledgeMembership(token: String, outcome: String): CatalogMembershipCommands.Receipt = writing(catalog) {
        val updater = selection.guardedUpdate(retention, context, checkpoint)
        beginPublication()
        updater.acknowledgeMembership(token, outcome).also { selection.requireCurrentBinding(context) }
    }

    internal suspend fun restoreMembership(targetId: String, expected: DiskFileVersion): CatalogMembershipCommands.Receipt = writing(catalog) {
        check(stageProposal == null) { "Membership commands require journal-owned proposal staging" }
        val current = loadCatalog()
        if (current.revision != expected) throw ProjectCatalogConflictException()
        val plan = ProjectMembershipRestore.plan(selection.appFilesDir, current.projects, targetId)
        val target = plan.target
        val root = plan.root
        val key = AnchoredWorkspaceFiles(root).use { it.directoryKey() }
        val rootIdentity = WorkspaceDirectoryIdentity(root)
        val requested = plan.projects
        val text = codec.encodeCatalog(requested)
        val command = CatalogMembershipCommands.Request(targetId, root.canonicalPath, key, expected.bytes, expected.sha256, text)
        val updater = selection.guardedUpdate(retention, context) { point ->
            checkpoint(point)
            rootIdentity.requireCurrent()
        }
        check(updater.membershipCommands().none { !it.acknowledged }) { "A membership result requires explicit acceptance" }
        beginPublication()
        if (!target.workspaceRemoved) return@writing updater.membershipNoChange(command)
        val prepared = updater.prepareOperation(requested, expected, command = command)
        // No implicit resume after a lost preparation return. This attempt still owns its exact carrier.
        try {
            updater.resume(prepared.prepared.operationId)
            checkpoint("membership-published")
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            try {
                context.ensureActive()
                selection.requireCurrentBinding(context)
                updater.readCompleted(prepared)
            } catch (proof: Throwable) {
                if (proof is CancellationException || proof is Error) {
                    if (proof !== failure) proof.addSuppressed(failure)
                    throw proof
                }
                if (proof !== failure) failure.addSuppressed(proof)
                throw failure
            }
        }
        selection.requireCurrentBinding(context)
        val ready = loadCatalog()
        check(ready.revision == DiskFileVersion.fromText(text)) { "Membership completion is historical; current catalog changed" }
        val result = updater.membershipCommands().single { it.operationId == prepared.prepared.operationId }
        rootIdentity.requireCurrent()
        check(result.outcome != null)
        checkpoint("membership-before-return")
        rootIdentity.requireCurrent()
        selection.requireCurrentBinding(context)
        context.ensureActive()
        result
    }

    override suspend fun loadActiveId(): String? = withContext(Dispatchers.IO) {
        val before = loadCatalog()
        val id = codec.loadActiveId()
        check(loadCatalog().revision == before.revision) { "Catalog changed while reading the active project" }
        id
    }

    override suspend fun saveActiveId(id: String) = writing(active) {
        val before = loadCatalog()
        require(before.projects.any { it.id == id && !it.workspaceRemoved }) {
            "Active project is not registered in the selected catalog"
        }
        checkpoint("selected-store-before-active")
        selection.requireCurrentBinding(context)
        beginPublication()
        codec.saveActiveId(id)
        checkpoint("selected-store-active-saved")
        selection.requireCurrentBinding(context)
        check(loadCatalog().revision == before.revision) {
            "Catalog changed while saving the active project; selection requires review"
        }
        context.ensureActive()
    }

    private class WriteAttempt(val context: CoroutineContext) {
        var started = false
            private set

        fun beginPublication() {
            context.ensureActive()
            started = true
        }
    }

    private suspend fun <T> writing(target: File, action: suspend WriteAttempt.() -> T): T {
        var attempt: WriteAttempt? = null
        try {
            return withContext(Dispatchers.IO) {
                writes.withLock {
                    currentCoroutineContext().ensureActive()
                    WriteAttempt(currentCoroutineContext()).also { attempt = it }.action()
                }
            }
        } catch (failure: Throwable) {
            // Mark after IO dispatch so prompt cancellation cannot erase an uncertain publication outcome.
            if (attempt?.started == true) throw ProjectMetadataPublication.unconfirmed(target, failure)
            throw failure
        }
    }
}
