package com.baystudio.droide.core

import java.io.File
import java.lang.ref.WeakReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Explicit integration only; supplied staging files remain caller-owned and recovery remains
// explicit.
internal class ProjectCatalogUpdate(
    appFilesDir: File,
    private val enrollment: CanonicalFileOperationJournal.Enrollment,
    private val membership: File,
    private val maxBytes: Long,
    private val retention: CanonicalJournalRetention.Policy,
    private val checkpoint: (String) -> Unit = {},
) {
    data class Prepared(val operationId: String, val before: DiskFileVersion, val proposed: DiskFileVersion)

    /** Same-attempt identity, not publication permission or durable caller acknowledgement. */
    class PreparedOperation internal constructor(val prepared: Prepared, private val authorityBinding: String,
                                                  intent: CanonicalFileOperationJournal.Intent) {
        private val id = intent.id
        private val kind = intent.kind
        private val path = intent.path
        private val parents = java.util.Collections.unmodifiableList(intent.parents.toList())
        private val before = intent.before
        private val proposal = intent.proposal

        internal fun requireMatches(binding: String, completed: CanonicalFileOperationJournal.Intent) {
            check(binding == authorityBinding && prepared.operationId == id && completed.id == id &&
                kind == CanonicalFileOperationJournal.OperationKind.REPLACE && path == CATALOG &&
                completed.kind == kind && completed.path == path && completed.parents == parents &&
                completed.before == before && completed.proposal == proposal && before != null &&
                prepared.before == revision(before) && prepared.proposed == DiskFileVersion(true, proposal.size, proposal.sha256)) {
                "Completed catalog receipt does not match this prepared request"
            }
        }
    }

    private val app = appFilesDir.canonicalFile
    private val metadata = File(app, ".droide")
    private val store = FileProjectStore(app)
    private val snapshots = CanonicalFileSnapshot(maxBytes)
    private val journal = CanonicalFileOperationJournal(metadata, File(enrollment.authorityPath), enrollment, maxBytes, checkpoint)
    private val bootstrap = CanonicalJournalBootstrap(requireNotNull(File(enrollment.authorityPath).parentFile), maxBytes)

    init {
        require(maxBytes > 0 && enrollment.projectId == AUTHORITY_ID && enrollment.rootPath == metadata.path) {
            "Catalog updates require their explicitly enrolled metadata authority"
        }
    }

    suspend fun prepare(projects: List<DroideProject>, expected: DiskFileVersion, proposal: File? = null): Prepared =
        prepareOperation(projects, expected, proposal).prepared

    // Both callers share one preparation path; the store keeps the full frozen request identity.
    internal suspend fun prepareOperation(projects: List<DroideProject>, expected: DiskFileVersion,
                                          proposal: File? = null, command: CatalogMembershipCommands.Request? = null): PreparedOperation = serialized { context ->
        require(expected.existed) { "A missing catalog requires registration recovery, not replacement" }
        val appIdentity = WorkspaceDirectoryIdentity(app)
        inspectAuthority(context).also {
            check(it.pending == null) { "A catalog update is pending; inspect it before preparing another" }
            journal.requireCompletedCustody(it, context)
        }
        val current = store.loadCatalog()
        if (current.revision != expected) throw ProjectCatalogConflictException()
        val encoded = store.encodeCatalog(projects)
        val proposed = DiskFileVersion.fromText(encoded)
        require(proposed != expected) { "Identical catalog content needs no update" }
        require(proposed.bytes <= maxBytes) { "Catalog proposal exceeds the enrolled update budget" }

        fun admitAndCapture(proposalContent: CanonicalFileOperationJournal.Content,
                            capture: (String) -> Unit): PreparedOperation {
            val before = AnchoredWorkspaceFiles(metadata).use { files -> snapshots.inspect(files, CATALOG, context) }
            if (revision(before) != expected) throw ProjectCatalogConflictException()
            appIdentity.requireCurrent()
            context.ensureActive()
            journal.enableCompletionRetention(retention, context)
            command?.let { requireCommandRoot(it) }
            val intent = if (command == null) journal.admit(CATALOG, before, proposalContent, context)
                else journal.admitMembership(command, before, proposalContent, context)
            journal.capture(intent.id, context)
            capture(intent.id)
            journal.stagePublication(intent.id, context)
            appIdentity.requireCurrent()
            AnchoredWorkspaceFiles(metadata).use { files ->
                check(snapshots.inspect(files, CATALOG, context) == before) {
                    "Catalog changed during preparation; original and proposed versions were retained"
                }
            }
            checkpoint("catalog-update-prepared")
            inspectAuthority(context)
            return PreparedOperation(Prepared(intent.id, expected, proposed),
                CanonicalFileOperationJournal.enrollmentHash(enrollment), intent)
        }

        if (proposal == null) {
            journal.requireCompletionCapacity(expected.bytes, proposed.bytes, retention, context)
            val bytes = encoded.toByteArray(Charsets.UTF_8)
            val proposalContent = CanonicalFileOperationJournal.Content(proposed.bytes, proposed.sha256, 384)
            admitAndCapture(proposalContent) { id -> journal.captureProposal(id, bytes, context) }
        } else {
            val staged = proposal.absoluteFile
            AnchoredWorkspaceFiles(requireNotNull(staged.parentFile)).use { input ->
                val stagedVersion = snapshots.inspect(input, staged.name, context)
                check(revision(stagedVersion) == proposed) { "Staged catalog does not match the proposed registration" }
                admitAndCapture(content(stagedVersion)) { id ->
                    journal.captureProposal(id, input, staged.name, stagedVersion, context)
                }
            }
        }
    }

    internal suspend fun enableMembershipCommands() = serialized { context ->
        val appIdentity = WorkspaceDirectoryIdentity(app)
        val history = inspectAuthority(context)
        check(history.pending == null)
        journal.enableCompletionRetention(retention, context)
        appIdentity.requireCurrent()
        journal.enableMembershipCommands(context)
        appIdentity.requireCurrent()
        inspectAuthority(context)
    }

    /** Explicit staging-only continuation. Does not admit, retire, publish, acknowledge or adopt a result. */
    internal suspend fun continueMembershipPreparation(token: String, operationId: String, envelopeHash: String): Prepared =
        serialized { context ->
            val identity = WorkspaceDirectoryIdentity(app)
            val initialHistory = inspectAuthority(context)
            val initialCommands = journal.membershipCommands(context)
            val receipt = initialCommands.singleOrNull { it.token == token }
                ?: throw CatalogMembershipCommands.OutcomeUnavailableException()
            check(receipt.operationId == operationId && receipt.outcome == null && !receipt.acknowledged &&
                CatalogMembershipCommands.hash(CatalogMembershipCommands.encode(receipt)) == envelopeHash) {
                "Exact unresolved membership preparation is required"
            }
            var current = checkNotNull(initialHistory.pending)
            check(current.id == operationId && journal.preparedIdentityRecord(current) == receipt.frozenIntent)
            val proposal = receipt.request.proposal.toByteArray(Charsets.UTF_8)
            check(proposal.size.toLong() == current.proposal.size &&
                CatalogMembershipCommands.hash(receipt.request.proposal) == current.proposal.sha256)
            var inFlightName: String? = null
            fun boundary() {
                context.ensureActive(); identity.requireCurrent(); requireCommandRoot(receipt.request)
                journal.requirePreparationInventory(current, inFlightName, context)
                identity.requireCurrent(); requireCommandRoot(receipt.request); context.ensureActive()
            }
            fun requireHistory() {
                val observed = inspectAuthority(context)
                check(observed.completed == initialHistory.completed && observed.retentionUsage == initialHistory.retentionUsage &&
                    observed.pending == current && journal.membershipCommands(context) == initialCommands) {
                    "Membership preparation history changed; retained work requires review"
                }
            }
            // Local callback scope only. No mutable guard is installed on the shared updater/journal.
            val continuation = CanonicalFileOperationJournal(metadata, File(enrollment.authorityPath), enrollment, maxBytes, checkpoint = { point ->
                boundary(); checkpoint(point); boundary()
            })
            boundary(); requireHistory(); boundary()
            while (current.phase != CanonicalFileOperationJournal.Phase.PUBLICATION_STAGED) {
                boundary(); requireHistory(); boundary()
                val previous = current
                inFlightName = when (current.phase) {
                    CanonicalFileOperationJournal.Phase.PREPARED -> current.snapshotName
                    CanonicalFileOperationJournal.Phase.SNAPSHOT_RECORDED -> current.proposalName
                    CanonicalFileOperationJournal.Phase.PROPOSAL_RECORDED -> current.publicationName
                    else -> error("Preparation continuation cannot advance publication")
                }
                try {
                    current = when (previous.phase) {
                        CanonicalFileOperationJournal.Phase.PREPARED -> previous.copy(
                            phase = CanonicalFileOperationJournal.Phase.SNAPSHOT_RECORDED,
                            snapshot = continuation.capture(operationId, context).copy)
                        CanonicalFileOperationJournal.Phase.SNAPSHOT_RECORDED -> previous.copy(
                            phase = CanonicalFileOperationJournal.Phase.PROPOSAL_RECORDED,
                            proposalCopy = continuation.captureProposal(operationId, proposal, context))
                        CanonicalFileOperationJournal.Phase.PROPOSAL_RECORDED -> previous.copy(
                            phase = CanonicalFileOperationJournal.Phase.PUBLICATION_STAGED,
                            publicationStage = continuation.stagePublication(operationId, context))
                        else -> error("Unsupported preparation phase")
                    }
                } finally { inFlightName = null }
                boundary(); requireHistory(); boundary()
            }
            continuation.verifyPublicationStage(operationId, context)
            boundary(); requireHistory(); boundary()
            checkpoint("membership-prepare-before-return")
            boundary(); requireHistory(); boundary()
            Prepared(operationId, revision(checkNotNull(current.before)),
                DiskFileVersion(true, current.proposal.size, current.proposal.sha256))
        }

    /** Returns a historical command receipt only; never adopts manager state or acknowledges delivery. */
    internal suspend fun completeMembershipCommand(token: String, operationId: String, admissionHash: String): CatalogMembershipCommands.Receipt =
        finishMembershipCommand(token, operationId, admissionHash, MembershipFinishScope.STAGED_COMPLETION)

    /** Explicit phase recovery; may perform the first publication after proven retirement. */
    internal suspend fun recoverMembershipCommand(token: String, operationId: String, admissionHash: String): CatalogMembershipCommands.Receipt =
        finishMembershipCommand(token, operationId, admissionHash, MembershipFinishScope.INTERRUPTED_RECOVERY)

    private enum class MembershipFinishScope { STAGED_COMPLETION, INTERRUPTED_RECOVERY }

    private suspend fun finishMembershipCommand(token: String, operationId: String, admissionHash: String,
                                                scope: MembershipFinishScope): CatalogMembershipCommands.Receipt =
        serialized { context ->
            val identity = WorkspaceDirectoryIdentity(app)
            val history = inspectAuthority(context)
            val commands = journal.membershipCommands(context)
            val receipt = commands.singleOrNull { it.token == token }
                ?: throw CatalogMembershipCommands.OutcomeUnavailableException()
            check(receipt.operationId == operationId && CatalogMembershipCommands.admissionEnvelopeHash(receipt) == admissionHash) {
                "Exact admitted membership command is required"
            }
            if (receipt.outcome != null) {
                // Historical result retrieval: current catalog/root need not still be the proposed state.
                check(history.completed.any { it.id == operationId })
                journal.requireCompletedCustody(history, context)
                checkpoint("membership-completed-result-reviewed")
                context.ensureActive(); identity.requireCurrent()
                check(inspectAuthority(context) == history && journal.membershipCommands(context) == commands) {
                    "Historical membership result changed during review"
                }
                journal.requireCompletedCustody(history, context)
                check(inspectAuthority(context) == history && journal.membershipCommands(context) == commands)
                identity.requireCurrent(); context.ensureActive()
                return@serialized receipt
            }
            check(!receipt.acknowledged)
            val pending = checkNotNull(history.pending)
            val permittedPhase = when (scope) {
                MembershipFinishScope.STAGED_COMPLETION -> pending.phase == CanonicalFileOperationJournal.Phase.PUBLICATION_STAGED
                MembershipFinishScope.INTERRUPTED_RECOVERY -> pending.phase in MEMBERSHIP_RECOVERY_PHASES
            }
            check(pending.id == operationId && permittedPhase &&
                journal.preparedIdentityRecord(pending) == receipt.frozenIntent) {
                "Exact membership work in the requested completion or recovery scope is required"
            }
            fun rootBoundary() {
                context.ensureActive(); identity.requireCurrent(); requireCommandRoot(receipt.request)
                identity.requireCurrent(); context.ensureActive()
            }
            val guardedCheckpoint: (String) -> Unit = { point -> rootBoundary(); checkpoint(point); rootBoundary() }
            val guardedJournal = CanonicalFileOperationJournal(metadata, File(enrollment.authorityPath), enrollment, maxBytes,
                checkpoint = guardedCheckpoint)
            rootBoundary()
            if (scope == MembershipFinishScope.STAGED_COMPLETION) {
                journal.requirePreparationInventory(pending, null, context)
            }
            // Later phases deliberately use resumeLocked's exact phase-specific custody proofs. In
            // particular an interrupted native move is inspected, never reissued.
            journal.requireCompletedCustody(history, context)
            rootBoundary()
            check(inspectAuthority(context) == history && journal.membershipCommands(context) == commands)
            // One invocation, no retry/catch. Existing resume owns native receipt and publication semantics.
            resumeLocked(operationId, context, guardedJournal, guardedCheckpoint)
            rootBoundary()
            val completedHistory = inspectAuthority(context)
            check(completedHistory.pending == null && completedHistory.completed.count { it.id == operationId } == 1 &&
                completedHistory.completed.filterNot { it.id == operationId } == history.completed)
            val results = journal.membershipCommands(context)
            val result = results.single { it.token == token }
            check(result.operationId == operationId && result.outcome != null && !result.acknowledged &&
                CatalogMembershipCommands.admissionEnvelopeHash(result) == admissionHash)
            check(results == commands.map { if (it.token == token) result else it })
            guardedJournal.requireCompletedCustody(completedHistory, context)
            guardedCheckpoint("membership-command-completed-before-return")
            guardedJournal.requireCompletedCustody(completedHistory, context)
            check(inspectAuthority(context) == completedHistory && journal.membershipCommands(context) == results)
            rootBoundary()
            result
        }

    internal suspend fun enableMembershipCompaction() = serialized { context ->
        val identity = WorkspaceDirectoryIdentity(app)
        check(inspectAuthority(context).pending == null)
        journal.enableMembershipCompaction(context)
        identity.requireCurrent(); inspectAuthority(context); context.ensureActive()
    }

    internal suspend fun membershipCommands(): List<CatalogMembershipCommands.Receipt> = serialized { context ->
        val appIdentity = WorkspaceDirectoryIdentity(app)
        inspectAuthority(context)
        journal.membershipCommands(context).also { appIdentity.requireCurrent(); context.ensureActive() }
    }

    private fun requireCommandRoot(request: CatalogMembershipCommands.Request) {
        val root = ProjectWorkspacePolicy.requireDirectChild(ProjectWorkspacePolicy.projectsRoot(app), File(request.rootPath))
        AnchoredWorkspaceFiles(root).use { check(it.directoryKey() == request.rootKey) { "Membership root was replaced" } }
    }

    internal suspend fun membershipNoChange(request: CatalogMembershipCommands.Request): CatalogMembershipCommands.Receipt = serialized { context ->
        val identity = WorkspaceDirectoryIdentity(app)
        inspectAuthority(context)
        requireCommandRoot(request)
        journal.admitMembershipNoChange(request, context).also {
            requireCommandRoot(request); identity.requireCurrent(); context.ensureActive()
        }
    }

    internal suspend fun expireAcceptedNoChange(token: String, outcome: String): CatalogMembershipCommands.Expiry = serialized { context ->
        val identity = WorkspaceDirectoryIdentity(app)
        inspectAuthority(context)
        journal.expireAcceptedNoChange(token, outcome, context).also {
            identity.requireCurrent(); inspectAuthority(context); context.ensureActive()
        }
    }

    internal suspend fun expireAcceptedOperation(token: String, outcome: String): CatalogMembershipCommands.Expiry = serialized { context ->
        val identity = WorkspaceDirectoryIdentity(app)
        inspectAuthority(context)
        journal.expireAcceptedOperation(token, outcome, context).also {
            identity.requireCurrent(); inspectAuthority(context); context.ensureActive()
        }
    }

    internal suspend fun acknowledgeMembership(token: String, outcome: String): CatalogMembershipCommands.Receipt = serialized { context ->
        val identity = WorkspaceDirectoryIdentity(app)
        inspectAuthority(context)
        val receipt = journal.membershipCommands(context).singleOrNull { it.token == token } ?: throw CatalogMembershipCommands.OutcomeUnavailableException()
        requireCommandRoot(receipt.request)
        journal.acknowledgeMembership(token, outcome, context).also {
            requireCommandRoot(receipt.request); identity.requireCurrent(); context.ensureActive()
        }
    }

    // Opt-in read-only collector.

    suspend fun collectReleaseReview(): CatalogReleasePreflight.Snapshot = serialized { context ->
        val appIdentity = WorkspaceDirectoryIdentity(app)
        val history = inspectAuthority(context)
        val catalog = AnchoredWorkspaceFiles(metadata).use { snapshots.inspect(it, CATALOG, context) }
        val captured = journal.collectRetentionReview(context)
        check(captured.history == history) { "Catalog history changed before preflight collection" }
        requireHistory(captured.history)
        check(captured.policy == retention) { "Catalog retention policy differs from enrolled update policy" }
        checkpoint("catalog-release-review-observed")
        context.ensureActive()
        appIdentity.requireCurrent()
        check(inspectAuthority(context) == history) { "Catalog history changed during preflight collection" }
        check(journal.collectRetentionReview(context) == captured) { "Catalog receipts changed during preflight collection" }
        appIdentity.requireCurrent()
        AnchoredWorkspaceFiles(metadata).use { files ->
            check(snapshots.inspect(files, CATALOG, context) == catalog) { "Catalog changed during preflight collection" }
        }
        check(inspectAuthority(context) == history) { "Catalog authority changed during preflight collection" }
        context.ensureActive()
        CatalogReleasePreflight.Snapshot(
            CatalogReleasePreflight.Scope(enrollment.projectId,
                CanonicalFileOperationJournal.enrollmentHash(enrollment), enrollment.rootPath,
                enrollment.rootKey, enrollment.generation), catalog, captured.policy,
            captured.receipts.map { receipt ->
                CatalogReleasePreflight.Record(receipt.entry.id, enrollment.projectId,
                    receipt.intent.kind.name, receipt.entry.record, receipt.entry.retainedBytes,
                    receipt.custody.map { (name, version) -> CatalogReleasePreflight.Custody(name, version) },
                    receipt.intent.path)
            }, captured.history.pending?.id,
        )
    }

    // Editor/process producers are not integrated. Omitted evidence therefore blocks; supplied
    // evidence is an observation only, not a claim of global writer exclusion.
    suspend fun reviewRelease(selectedAt: CatalogReleasePreflight.Snapshot, selectedIds: List<String>,
                              editor: CatalogReleasePreflight.EditorEvidence? = null,
                              processes: CatalogReleasePreflight.ProcessEvidence? = null): CatalogReleasePreflight.Result =
        CatalogReleasePreflight.evaluate(selectedAt, collectReleaseReview(), selectedIds, editor, processes)

    suspend fun inspect(): CanonicalFileOperationJournal.RecoveryMetadata = serialized { context ->
        inspectAuthority(context)
    }

    suspend fun inspectOperationRecovery(operationId: String): CanonicalFileOperationJournal.OperationRecoveryReview =
        serialized { context ->
            val history = inspectAuthority(context)
            val review = journal.inspectOperationRecovery(operationId, context)
            check(inspectAuthority(context) == history) {
                "Catalog recovery history changed during review; retained work was preserved"
            }
            context.ensureActive()
            review
        }

    suspend fun readRetainedContent(operationId: String, role: CanonicalFileOperationJournal.CustodyRole,
                                   expected: CanonicalFileSnapshot.Version): CanonicalFileOperationJournal.RetainedContent =
        serialized { context ->
            val history = inspectAuthority(context)
            val content = journal.readRetainedContent(operationId, role, expected, context)
            check(inspectAuthority(context) == history) {
                "Catalog recovery history changed during reading; retained work was preserved"
            }
            context.ensureActive()
            content
        }

    suspend fun requireCapacity(before: DiskFileVersion, proposed: DiskFileVersion) = serialized { context ->
        require(before.existed && proposed.existed)
        inspectAuthority(context)
        journal.requireCompletionCapacity(before.bytes, proposed.bytes, retention, context)
    }

    suspend fun readReadyCatalog(): ProjectCatalogSnapshot = serialized { context ->
        val appIdentity = WorkspaceDirectoryIdentity(app)
        val history = inspectAuthority(context)
        check(history.pending == null) {
            "Catalog update ${history.pending?.id} is pending (${history.pending?.phase}); retained work requires review"
        }
        val before = AnchoredWorkspaceFiles(metadata).use { files -> snapshots.inspect(files, CATALOG, context) }
        checkpoint("catalog-update-read-observed")
        val current = store.loadCatalog()
        check(current.revision == revision(before)) { "Catalog changed while reading; no current state was accepted" }
        appIdentity.requireCurrent()
        AnchoredWorkspaceFiles(metadata).use { files ->
            check(snapshots.inspect(files, CATALOG, context) == before) {
                "Catalog identity or contents changed while reading; no current state was accepted"
            }
        }
        check(inspectAuthority(context) == history) { "Catalog update history changed while reading; retained work requires review" }
        journal.requireCompletedCustody(history, context)
        // Custody verification can outlive the catalog observation above. Do not return a previously
        // loaded catalog after its identity or bytes changed meanwhile.
        appIdentity.requireCurrent()
        AnchoredWorkspaceFiles(metadata).use { files ->
            check(snapshots.inspect(files, CATALOG, context) == before) {
                "Catalog changed during custody validation; no current state was accepted"
            }
        }
        check(inspectAuthority(context) == history) { "Catalog update history changed during custody validation; retained work requires review" }
        context.ensureActive()
        current
    }

    /** Read-only proof of this attempt's already archived result. Never resumes pending work. */
    internal suspend fun readCompleted(operation: PreparedOperation): ProjectCatalogSnapshot = serialized { context ->
        val appIdentity = WorkspaceDirectoryIdentity(app)
        val history = inspectAuthority(context)
        check(history.pending == null) { "Pending catalog work cannot be reconciled as completed" }
        val completed = history.completed.singleOrNull { it.id == operation.prepared.operationId }
            ?: error("Exact completed catalog receipt is unavailable; no update was recreated")
        operation.requireMatches(CanonicalFileOperationJournal.enrollmentHash(enrollment), completed)
        checkpoint("catalog-update-completion-review")
        context.ensureActive()
        val published = readPublished(completed, context, appIdentity)
        check(inspectAuthority(context) == history) {
            "Catalog history changed during completion proof; the original outcome remains unconfirmed"
        }
        appIdentity.requireCurrent()
        context.ensureActive()
        published
    }

    // Resumes only proven phases; an interrupted native move is inspected, never retried.
    suspend fun resume(operationId: String): ProjectCatalogSnapshot = serialized { context ->
        resumeLocked(operationId, context, journal, checkpoint)
    }

    private suspend fun resumeLocked(operationId: String, context: CoroutineContext,
                                     operationJournal: CanonicalFileOperationJournal,
                                     operationCheckpoint: (String) -> Unit): ProjectCatalogSnapshot {
        val appIdentity = WorkspaceDirectoryIdentity(app)
        val history = inspectAuthority(context)
        val pending = history.pending
        if (pending == null) {
            val completed = history.completed.singleOrNull { it.id == operationId }
                ?: error("Catalog update receipt is missing; no update was recreated")
            return readPublished(completed, context, appIdentity, operationJournal)
        }
        check(pending.id == operationId) { "A different catalog update is pending; no operation was replayed" }
        var intent = pending
        when (intent.phase) {
            CanonicalFileOperationJournal.Phase.PUBLICATION_STAGED -> {
                operationCheckpoint("catalog-update-before-retirement")
                inspectAuthority(context)
                operationJournal.retireCanonical(operationId, context)
            }
            CanonicalFileOperationJournal.Phase.RETIREMENT_PREPARED -> operationJournal.recoverRetiredState(operationId, context)
            CanonicalFileOperationJournal.Phase.RETIREMENT_RECORDED -> {
                if (intent.retirement?.let { it.published && !it.durable } == true) {
                    operationJournal.recoverRetiredState(operationId, context)
                } else operationJournal.verifyRetirement(operationId, context)
            }
            CanonicalFileOperationJournal.Phase.RETIREMENT_RECOVERED,
            CanonicalFileOperationJournal.Phase.RETENTION_RECORDED -> operationJournal.verifyRetirement(operationId, context)
            CanonicalFileOperationJournal.Phase.PUBLICATION_PREPARED -> operationJournal.recoverPublishedState(operationId, context)
            CanonicalFileOperationJournal.Phase.PUBLICATION_RECORDED -> {
                if (intent.publication?.let { it.published && !it.durable } == true) {
                    operationJournal.recoverPublishedState(operationId, context)
                } else operationJournal.verifyPublication(operationId, context)
            }
            CanonicalFileOperationJournal.Phase.PUBLICATION_RECOVERED,
            CanonicalFileOperationJournal.Phase.PUBLICATION_COMPLETED -> operationJournal.verifyPublication(operationId, context)
            else -> error("Catalog proposal preparation is incomplete; staged and original data require review")
        }
        appIdentity.requireCurrent()
        intent = requirePending(operationId)
        if (intent.phase in RETIRED_PHASES) {
            operationJournal.recordRetainedSnapshot(operationId, context)
            operationCheckpoint("catalog-update-before-publication")
            inspectAuthority(context)
            operationJournal.publishStaged(operationId, context)
        }
        appIdentity.requireCurrent()
        operationJournal.recordPublicationCompletion(operationId, context)
        appIdentity.requireCurrent()
        val completed = operationJournal.archiveCompleted(operationId, context)
        operationCheckpoint("catalog-update-completed")
        return readPublished(completed, context, appIdentity, operationJournal)
    }

    private suspend fun readPublished(
        intent: CanonicalFileOperationJournal.Intent,
        context: CoroutineContext,
        appIdentity: WorkspaceDirectoryIdentity,
        operationJournal: CanonicalFileOperationJournal = journal,
    ): ProjectCatalogSnapshot {
        val published = intent.publicationRecovery ?: checkNotNull(intent.publicationStage)
        fun requireCurrent() {
            appIdentity.requireCurrent()
            AnchoredWorkspaceFiles(metadata).use { files ->
                check(snapshots.inspect(files, CATALOG, context) == published) {
                    "Catalog changed after publication; committed and current versions require review"
                }
            }
        }
        requireCurrent()
        val current = store.loadCatalog()
        check(current.revision == revision(published)) { "Published catalog changed while loading; no state was accepted" }
        requireCurrent()
        val history = inspectAuthority(context)
        check(history.completed.any { it == intent }) { "Completed catalog receipt changed; retained work requires review" }
        operationJournal.requireCompletedCustody(history, context)
        requireCurrent()
        check(inspectAuthority(context) == history) { "Catalog update history changed during recovery; retained work requires review" }
        context.ensureActive()
        return current
    }

    private fun requirePending(id: String): CanonicalFileOperationJournal.Intent =
        checkNotNull(journal.read()) { "Pending catalog update is missing; no operation was recreated" }.also {
            requireIntent(it)
            check(it.id == id) { "Pending catalog update changed; retained versions require review" }
        }

    private fun requireHistory(history: CanonicalFileOperationJournal.RecoveryMetadata) = history.also {
        it.pending?.let(::requireIntent)
        it.completed.forEach(::requireIntent)
    }

    private fun inspectAuthority(context: CoroutineContext): CanonicalFileOperationJournal.RecoveryMetadata {
        val inspection = bootstrap.inspectWithMembership(metadata, AUTHORITY_ID, membership, context)
        check(inspection.enrollment == enrollment) {
            "Catalog update authority changed; retained versions require review"
        }
        return requireHistory(inspection.history)
    }

    private fun requireIntent(intent: CanonicalFileOperationJournal.Intent) {
        check(intent.kind == CanonicalFileOperationJournal.OperationKind.REPLACE && intent.path == CATALOG && intent.before != null) {
            "Catalog authority contains an unrelated operation; all evidence was preserved"
        }
    }

    private suspend fun <T> serialized(action: suspend (CoroutineContext) -> T): T = withContext(Dispatchers.IO) {
        mutex(enrollment.authorityPath).withLock {
            val context = currentCoroutineContext()
            context.ensureActive()
            action(context)
        }
    }

    companion object {
        const val AUTHORITY_ID = "droide-project-catalog-update-v1"
        private const val CATALOG = "projects.json"
        private val MEMBERSHIP_RECOVERY_PHASES = setOf(
            CanonicalFileOperationJournal.Phase.RETIREMENT_PREPARED,
            CanonicalFileOperationJournal.Phase.RETIREMENT_RECORDED,
            CanonicalFileOperationJournal.Phase.RETIREMENT_RECOVERED,
            CanonicalFileOperationJournal.Phase.RETENTION_RECORDED,
            CanonicalFileOperationJournal.Phase.PUBLICATION_PREPARED,
            CanonicalFileOperationJournal.Phase.PUBLICATION_RECORDED,
            CanonicalFileOperationJournal.Phase.PUBLICATION_RECOVERED,
            CanonicalFileOperationJournal.Phase.PUBLICATION_COMPLETED,
        )
        private val RETIRED_PHASES = setOf(
            CanonicalFileOperationJournal.Phase.RETIREMENT_RECORDED,
            CanonicalFileOperationJournal.Phase.RETIREMENT_RECOVERED,
            CanonicalFileOperationJournal.Phase.RETENTION_RECORDED,
        )
        private val locks = mutableMapOf<String, WeakReference<Mutex>>()
        private fun mutex(key: String): Mutex = synchronized(locks) {
            locks.entries.removeAll { it.value.get() == null }
            locks[key]?.get() ?: Mutex().also { locks[key] = WeakReference(it) }
        }
        private fun revision(version: CanonicalFileSnapshot.Version) = DiskFileVersion(true, version.size, version.sha256)
        private fun content(version: CanonicalFileSnapshot.Version) = CanonicalFileOperationJournal.Content(version.size, version.sha256, version.mode)
    }
}
