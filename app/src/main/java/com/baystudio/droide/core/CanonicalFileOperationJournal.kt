package com.baystudio.droide.core

import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

internal class CanonicalFileOperationJournal(
    private val root: File,
    private val authority: File,
    private val enrollment: Enrollment,
    private val maxBytes: Long,
    private val checkpoint: (String) -> Unit = {},
    private val databaseSizeLimitBytes: Long? = null,
) {
    @Serializable data class Enrollment(val projectId: String, val rootPath: String, val rootKey: String,
        val authorityPath: String, val authorityKey: String, val databaseKey: String,
        val custodyKey: String, val generation: String, val authorityParentKey: String)
    data class Content(val size: Long, val sha256: String, val mode: Int)
    data class Parent(val path: String, val key: String)
    enum class OperationKind { REPLACE, CREATE }
    data class RetainedFileReview(val name: String, val expected: CanonicalFileSnapshot.Version,
        val kind: AnchoredWorkspaceFiles.Kind, val observed: CanonicalFileSnapshot.Version?) {
        val matches: Boolean get() = kind == AnchoredWorkspaceFiles.Kind.FILE && observed == expected
    }
    enum class ConflictRole { RETIRED, STAGE, CANONICAL }
    data class PublicationConflict(val role: ConflictRole, val kind: AnchoredWorkspaceFiles.Kind,
                                   val observed: CanonicalFileSnapshot.Version?)
    data class PublicationConflictReview(val operationId: String, val recorded: PublicationConflict,
        val captured: CanonicalFileSnapshot.Version, val currentKind: AnchoredWorkspaceFiles.Kind,
        val currentVersion: CanonicalFileSnapshot.Version?) {
        val currentMatchesRecordedObservation: Boolean get() = currentKind == recorded.kind && currentVersion == recorded.observed
    }
    enum class Phase { PREPARED, SNAPSHOT_RECORDED, PROPOSAL_RECORDED, PUBLICATION_STAGED,
        RETIREMENT_PREPARED, RETIREMENT_RECORDED, RETIREMENT_RECOVERED, RETENTION_RECORDED,
        PUBLICATION_PREPARED, PUBLICATION_RECORDED, PUBLICATION_COMPLETED, PUBLICATION_RECOVERED }
    data class Intent(val id: String, val path: String, val parents: List<Parent>,
        val before: CanonicalFileSnapshot.Version?, val proposal: Content, val phase: Phase,
        val snapshot: CanonicalFileSnapshot.Version?, val proposalCopy: CanonicalFileSnapshot.Version? = null,
        val publicationStage: CanonicalFileSnapshot.Version? = null,
        val retirement: AnchoredWorkspaceFiles.MoveReceipt? = null,
        val retainedSnapshotIdentity: String? = null,
        val publication: AnchoredWorkspaceFiles.MoveReceipt? = null,
        val publicationConflict: PublicationConflict? = null,
        val publicationRecovery: CanonicalFileSnapshot.Version? = null,
        val publicationConflictCopy: CanonicalFileSnapshot.Version? = null,
        val retirementRecovery: CanonicalFileSnapshot.Version? = null,
        val kind: OperationKind = OperationKind.REPLACE) {
        val snapshotName: String get() = "$id.before"
        val proposalName: String get() = "$id.after"
        val publicationName: String get() = "$id.publish"
        val retiredName: String get() = "$id.retired"
        val conflictCopyName: String get() = "$id.conflict"
    }
    data class RecoveryMetadata(val pending: Intent?, val completed: List<Intent>,
                                val retentionUsage: CanonicalJournalRetention.Usage? = null)
    data class FileObservation(val kind: AnchoredWorkspaceFiles.Kind, val version: CanonicalFileSnapshot.Version?)
    enum class CustodyRole { SNAPSHOT, PROPOSAL, PUBLICATION_STAGE, RETIRED, CONFLICT }
    data class CustodyFileReview(val role: CustodyRole, val name: String,
        val recordedVersion: CanonicalFileSnapshot.Version?, val observed: FileObservation)
    data class OperationRecoveryReview(val intent: Intent, val archived: Boolean,
        val canonicalPath: String, val custodyPath: String, val canonical: FileObservation,
        val custody: List<CustodyFileReview>)
    data class RetainedContent(val operationId: String, val role: CustodyRole, val name: String,
        val archived: Boolean, val recordedVersion: CanonicalFileSnapshot.Version?,
        val content: CanonicalFileSnapshot.VerifiedBytes)

    private val database = File(authority, "state.sqlite")
    private val snapshotter = CanonicalFileSnapshot(maxBytes) { boundary, _ ->
        checkpoint("snapshot-${boundary.name}")
    }

    private val proposalSnapshotter = CanonicalFileSnapshot(maxBytes) { boundary, _ ->
        checkpoint("proposal-${boundary.name}")
    }

    private val publicationSnapshotter = CanonicalFileSnapshot(maxBytes) { boundary, _ ->
        checkpoint("publication-stage-${boundary.name}")
    }

    private val conflictSnapshotter = CanonicalFileSnapshot(maxBytes) { boundary, _ ->
        checkpoint("conflict-copy-${boundary.name}")
    }

    init {
        require(maxBytes >= 0)
        databaseSizeLimitBytes?.let { require(it >= 4096) }
        text(enrollment.projectId, 160); uuid(enrollment.generation)
        listOf(enrollment.rootKey, enrollment.authorityKey, enrollment.databaseKey,
            enrollment.custodyKey, enrollment.authorityParentKey).forEach { text(it, 256) }
        require(root.absolutePath == enrollment.rootPath && root.canonicalPath == enrollment.rootPath)
        require(authority.absolutePath == enrollment.authorityPath && authority.canonicalPath == enrollment.authorityPath)
        val rootPath = File(enrollment.rootPath).toPath()
        val authorityPath = File(enrollment.authorityPath).toPath()
        require(!authorityPath.startsWith(rootPath) && !rootPath.startsWith(authorityPath)) {
            "Authority and workspace must have separate directory trees"
        }
    }

    fun read(): Intent? = access { db, anchor, store -> readIntent(db, anchor, store) }

    fun requireWorkspaceBinding(expectedRoot: File, context: CoroutineContext) {
        check(expectedRoot.absolutePath == enrollment.rootPath && expectedRoot.canonicalPath == enrollment.rootPath) {
            "Recovery copy journal belongs to a different workspace"
        }
        requireAuthorityBinding(context)
    }

    // Identity checks only; safe inside an existing transaction, not a database-integrity check.
    fun requireAuthorityBinding(context: CoroutineContext) {
        context.ensureActive()
        pins { _, _, verify -> verify(); context.ensureActive() }
    }

    // Budget preflight only; admission revalidates current data and capacity inside its transaction.
    fun requireCompletionCapacity(beforeBytes: Long, proposedBytes: Long, policy: CanonicalJournalRetention.Policy,
                                  context: CoroutineContext) {
        require(beforeBytes in 0..maxBytes && proposedBytes in 0..maxBytes)
        context.ensureActive()
        authorityPins { store, verify ->
            accessDatabase(verify, readOnly = true) { db ->
                val retention = readRetention(db)
                check(readStoredIntent(db, store, retention) == null) { "A catalog operation is pending; retained work needs review" }
                val state = retention?.state ?: CanonicalJournalRetention.State(policy, emptyList())
                check(state.policy == policy) { "Existing retention limits require explicit review" }
                state.requireAdmission(peakBytes(beforeBytes, proposedBytes))
                context.ensureActive()
            }
        }
    }

    // Historical metadata only: source paths and retained copies are not verified by this read.
    fun readPendingMetadataForRecovery(): Intent? = authorityPins { store, verify ->
        accessDatabase(verify) { db -> readStoredIntent(db, store) }
    }

    // Read-only inspection cannot initialize or roll back an incomplete enrollment database.
    fun inspectPendingMetadataForRecovery(): Intent? = authorityPins { store, verify ->
        accessDatabase(verify, readOnly = true) { db -> readStoredIntent(db, store) }
    }

    /** Read-only declared policy. Does not establish current catalog or payload liveness. */
    internal fun readRetentionPolicy(context: CoroutineContext): CanonicalJournalRetention.Policy {
        context.ensureActive()
        return authorityPins { store, verify ->
            accessDatabase(verify, readOnly = true) { db ->
                val retained = checkNotNull(readRetention(db)) { "Persisted catalog retention policy is missing" }
                readStoredIntent(db, store, retained)
                context.ensureActive()
                retained.state.policy
            }
        }
    }

    fun inspectRecoveryMetadata(): RecoveryMetadata = authorityPins { store, verify ->
        accessDatabase(verify, readOnly = true) { db ->
            val retention = readRetention(db)
            RecoveryMetadata(readStoredIntent(db, store, retention), retention?.intents.orEmpty(), retention?.state?.usage)
        }
    }

    fun inspectOperationRecovery(id: String, context: CoroutineContext): OperationRecoveryReview {
        uuid(id); context.ensureActive()
        val history = inspectRecoveryMetadata()
        val archived = history.completed.any { it.id == id }
        val intent = history.pending?.takeIf { it.id == id } ?: history.completed.find { it.id == id }
            ?: error("Recovery operation is missing; no files were adopted or changed")
        val result = pins { anchor, store, verify ->
            fun observe(): OperationRecoveryReview {
                context.ensureActive()
                requireParents(anchor, intent.parents)
                val canonical = anchor.openDirectory(intent.parents.last().path).use { parent ->
                    observeRecoveryFile(parent, intent.path.substringAfterLast('/'), context)
                }
                val files = CustodyRole.values().map { role ->
                    val (name, recorded) = intent.custodyFile(role)
                    CustodyFileReview(role, name, recorded, observeRecoveryFile(store, name, context))
                }
                requireParents(anchor, intent.parents)
                verify()
                return OperationRecoveryReview(intent, archived, File(root, intent.path).path,
                    File(authority, "custody").path, canonical, files)
            }
            val before = observe()
            checkpoint("operation-review-observed")
            check(before == observe()) { "Recovery files changed during review; all versions were preserved" }
            before
        }
        checkpoint("operation-review-verified")
        check(inspectRecoveryMetadata() == history) {
            "Recovery metadata changed during review; no resolution was performed"
        }
        context.ensureActive()
        return result
    }

    // Custody is pinned independently so an unavailable canonical source does not destroy read
    // access.
    fun readRetainedContent(id: String, role: CustodyRole, expected: CanonicalFileSnapshot.Version,
                            context: CoroutineContext): RetainedContent {
        uuid(id); context.ensureActive()
        val history = inspectRecoveryMetadata()
        val intent = history.pending?.takeIf { it.id == id } ?: history.completed.find { it.id == id }
            ?: error("Recovery operation is missing; no files were adopted or changed")
        val (name, recorded) = intent.custodyFile(role)
        val result = authorityPins { store, verify ->
            val reader = CanonicalFileSnapshot(maxBytes) { boundary, _ ->
                checkpoint("retained-content-${boundary.name}")
            }
            val content = reader.readVerifiedBytes(store, name, expected, context)
            verify()
            RetainedContent(id, role, name, history.completed.any { it.id == id }, recorded, content)
        }
        checkpoint("retained-content-read")
        check(inspectRecoveryMetadata() == history) {
            "Recovery metadata changed during reading; no resolution was performed"
        }
        context.ensureActive()
        return result
    }

    private fun Intent.custodyFile(role: CustodyRole): Pair<String, CanonicalFileSnapshot.Version?> = when (role) {
        CustodyRole.SNAPSHOT -> snapshotName to snapshot
        CustodyRole.PROPOSAL -> proposalName to proposalCopy
        CustodyRole.PUBLICATION_STAGE -> publicationName to publicationStage
        CustodyRole.RETIRED -> retiredName to before
        CustodyRole.CONFLICT -> conflictCopyName to publicationConflictCopy
    }

    private fun observeRecoveryFile(parent: AnchoredWorkspaceFiles, name: String,
                                    context: CoroutineContext): FileObservation {
        context.ensureActive()
        val kind = parent.kind(name)
        val version = if (kind == AnchoredWorkspaceFiles.Kind.FILE) snapshotter.inspect(parent, name, context) else null
        check(parent.kind(name) == kind) { "Recovery file type changed during review" }
        context.ensureActive()
        return FileObservation(kind, version)
    }

    fun inspectUnusedEnrollmentForRecovery() = authorityPins { store, verify ->
        accessDatabase(verify, readOnly = true) { db ->
            check(db.version == 1 && readStoredIntent(db, store) == null) {
                "Enrollment has been used or upgraded; its original receipt requires review"
            }
        }
    }

    fun enableCompletionRetention(policy: CanonicalJournalRetention.Policy, context: CoroutineContext) {
        context.ensureActive()
        val upgraded = access { db, anchor, store ->
            val retention = readRetention(db)
            val pending = readStoredIntent(db, store, retention)
            if (retention != null) {
                check(retention.state.policy == policy) { "Existing retention limits require explicit review" }
                return@access false
            }
            pending?.let {
                requireParents(anchor, it.parents)
                CanonicalJournalRetention.State(policy, emptyList()).requireAdmission(peakBytes(it))
            }
            checkpoint("retention-enable-verified")
            context.ensureActive()
            CanonicalJournalRetention.enable(db, policy)
            checkpoint("retention-enable-before-commit")
            context.ensureActive()
            true
        }
        if (upgraded) checkpoint("retention-enable-after-commit")
    }

    // Releases the pending metadata slot only; this historical receipt never authorizes a file
    // replacement.
    fun archiveCompleted(id: String, context: CoroutineContext): Intent {
        uuid(id); context.ensureActive()
        var archived = false
        val result = access { db, anchor, store ->
            val retention = checkNotNull(readRetention(db)) { "Completion retention has not been enabled" }
            val pending = readStoredIntent(db, store, retention)
            retention.intents.firstOrNull { it.id == id }?.let { return@access it }
            val intent = checkNotNull(pending) { "Pending completion is missing" }
            check(intent.id == id && intent.phase == Phase.PUBLICATION_COMPLETED) {
                "Only a completed publication can leave the pending slot"
            }
            requireCompletedCustody(retention, store)
            requireVerifiedPublication(intent, anchor, store, context)
            checkpoint("completion-archive-verified")
            requireVerifiedPublication(intent, anchor, store, context)
            CanonicalJournalRetention.append(db, retention.state,
                CanonicalJournalRetention.Entry(intent.id, encode(intent), retainedBytes(intent)))
            CatalogMembershipCommands.complete(db, intent.id, hash(encode(intent)))
            db.execSQL("DELETE FROM operation WHERE slot=1")
            db.execSQL("UPDATE meta SET expected_operation=NULL,record_digest=NULL WHERE id=1")
            checkpoint("completion-archive-before-commit")
            requireVerifiedPublication(intent, anchor, store, context)
            context.ensureActive()
            archived = true
            intent
        }
        if (archived) checkpoint("completion-archive-after-commit")
        return result
    }

    fun readCompletedMetadataForRecovery(): List<Intent> = authorityPins { store, verify ->
        accessDatabase(verify) { db ->
            val retention = readRetention(db)
            readStoredIntent(db, store, retention)
            retention?.intents.orEmpty()
        }
    }

    // Validate one exact history snapshot in one read-only transaction, rather than reopening and
    // decoding the complete history once per completed ID. No receipt grants file release.
    fun requireCompletedCustody(expected: RecoveryMetadata, context: CoroutineContext) {
        context.ensureActive()
        authorityPins { store, verify ->
            accessDatabase(verify, readOnly = true) { db ->
                val retention = readRetention(db)
                val observed = RecoveryMetadata(readStoredIntent(db, store, retention),
                    retention?.intents.orEmpty(), retention?.state?.usage)
                check(observed == expected) {
                    "Recovery history changed before custody validation; retained work was preserved"
                }
                fun requireFiles(intent: Intent) {
                    completedFiles(intent).forEach { (name, recorded) ->
                        context.ensureActive()
                        check(store.kind(name) == AnchoredWorkspaceFiles.Kind.FILE &&
                            snapshotter.inspect(store, name, context) == recorded) {
                            "Completed catalog custody changed; retained versions require review"
                        }
                    }
                }
                observed.completed.forEach { intent ->
                    requireFiles(intent)
                    checkpoint("completed-custody-reviewed")
                    context.ensureActive()
                }
                // The last reviewed entry is not a barrier for files checked earlier.

                verify()
                observed.completed.forEach(::requireFiles)
                val finalRetention = readRetention(db)
                check(RecoveryMetadata(readStoredIntent(db, store, finalRetention),
                    finalRetention?.intents.orEmpty(), finalRetention?.state?.usage) == expected) {
                    "Recovery history changed during custody validation; retained work was preserved"
                }
                context.ensureActive()
            }
        }
    }

    data class CompletedReceiptReview(val entry: CanonicalJournalRetention.Entry, val intent: Intent,
                                      val custody: List<Pair<String, CanonicalFileSnapshot.Version>>)
    data class RetentionReview(val history: RecoveryMetadata, val policy: CanonicalJournalRetention.Policy,
                               val receipts: List<CompletedReceiptReview>)

    // Raw receipts and decoded identities from the same validated read-only transaction. This
    // observation neither grants admission nor authorizes release or accounting changes.
    fun collectRetentionReview(context: CoroutineContext): RetentionReview {
        context.ensureActive()
        return authorityPins { store, verify ->
            accessDatabase(verify, readOnly = true) { db ->
                val retention = checkNotNull(readRetention(db)) { "Completion retention is unavailable" }
                val history = RecoveryMetadata(readStoredIntent(db, store, retention), retention.intents,
                    retention.state.usage)
                fun capture(intent: Intent): List<Pair<String, CanonicalFileSnapshot.Version>> =
                    completedFiles(intent).toSortedMap().map { (name, recorded) ->
                        context.ensureActive()
                        check(store.kind(name) == AnchoredWorkspaceFiles.Kind.FILE) { "Completed custody is unavailable" }
                        val observed = snapshotter.inspect(store, name, context)
                        check(observed == recorded) { "Completed custody changed during preflight collection" }
                        name to observed
                    }
                val receipts = retention.state.entries.zip(retention.intents).map { (entry, intent) ->
                    check(entry.id == intent.id) { "Completed receipt identity mismatch" }
                    CompletedReceiptReview(entry, intent, capture(intent))
                }
                checkpoint("preflight-receipts-observed")
                context.ensureActive()
                verify()
                receipts.forEach { check(capture(it.intent) == it.custody) { "Completed custody changed during collection" } }
                val finalRetention = checkNotNull(readRetention(db))
                check(finalRetention.state.policy == retention.state.policy &&
                    finalRetention.state.entries == retention.state.entries &&
                    RecoveryMetadata(readStoredIntent(db, store, finalRetention), finalRetention.intents,
                        finalRetention.state.usage) == history) { "Retention changed during preflight collection" }
                context.ensureActive()
                RetentionReview(history, retention.state.policy, receipts)
            }
        }
    }

    fun reviewCompletedCustody(id: String, context: CoroutineContext): List<RetainedFileReview> {
        uuid(id); context.ensureActive()
        return authorityPins { store, verify ->
            accessDatabase(verify) { db ->
                val retention = checkNotNull(readRetention(db)) { "Completion retention has not been enabled" }
                readStoredIntent(db, store, retention)
                val intent = checkNotNull(retention.intents.find { it.id == id }) { "Completed operation is missing" }
                completedFiles(intent).map { (name, expected) ->
                    context.ensureActive()
                    val kind = store.kind(name)
                    val observed = if (kind == AnchoredWorkspaceFiles.Kind.FILE) snapshotter.inspect(store, name, context) else null
                    RetainedFileReview(name, expected, kind, observed)
                }
            }
        }
    }

    fun admit(path: String, before: CanonicalFileSnapshot.Version, proposal: Content,
              context: CoroutineContext): Intent = admitOperation(path, before, proposal, OperationKind.REPLACE, context)

    fun admitCreation(path: String, proposal: Content, context: CoroutineContext): Intent =
        admitOperation(path, null, proposal, OperationKind.CREATE, context)

    private fun admitOperation(path: String, before: CanonicalFileSnapshot.Version?, proposal: Content,
                               kind: OperationKind, context: CoroutineContext, command: CatalogMembershipCommands.Request? = null): Intent {
        validatePath(path); before?.let(::version); content(proposal)
        context.ensureActive()
        val parents = AnchoredWorkspaceFiles(root).use { anchor ->
            parentPaths(path).map { Parent(it, anchor.directoryKey(it)) }
        }
        val intent = Intent(UUID.randomUUID().toString(), path, parents, before, proposal, Phase.PREPARED, null, kind = kind)
        validate(intent)
        AnchoredWorkspaceFiles(root).use { anchor ->
            requireParents(anchor, parents)
            anchor.openDirectory(parents.last().path).use { parent ->
                if (kind == OperationKind.CREATE) check(parent.kind(path.substringAfterLast('/')) == AnchoredWorkspaceFiles.Kind.ABSENT) {
                    "Creation target exists; its current contents were preserved"
                } else check(snapshotter.inspect(parent, path.substringAfterLast('/'), context) == before) {
                    "Canonical before-version changed; no intent was admitted"
                }
            }
            requireParents(anchor, parents)
        }
        access { db, anchor, store ->
            val retention = readRetention(db)
            if (kind == OperationKind.CREATE) {
                checkNotNull(retention) { "Creation requires explicit completion retention before admission" }
                check(anchor.kind(path) == AnchoredWorkspaceFiles.Kind.ABSENT) { "Creation target appeared before admission" }
            }
            check(readStoredIntent(db, store, retention) == null) { "An intent is pending; no second operation was admitted" }
            retention?.let {
                requireCompletedCustody(it, store)
                it.state.requireAdmission(peakBytes(intent))
            }
            requireParents(anchor, parents)
            context.ensureActive()
            val record = encode(intent)
            val receipt = command?.let { request ->
                requireCommandRequest(request, intent)
                requireMembershipRoot(request)
                CatalogMembershipCommands.Receipt(UUID.randomUUID().toString(), enrollmentHash(enrollment), request,
                    intent.id, record, null)
            }
            CatalogMembershipCommands.admit(db, intent.id, receipt, retention?.state?.policy?.maxEntries ?: 128)
            db.execSQL("INSERT INTO operation VALUES(1,?)", arrayOf(record))
            db.execSQL("UPDATE meta SET expected_operation=?,record_digest=? WHERE id=1", arrayOf(intent.id, hash(record)))
            checkpoint("admission-before-commit")
            command?.let(::requireMembershipRoot)
            requireParents(anchor, parents)
            context.ensureActive()
        }
        checkpoint("admission-after-commit")
        context.ensureActive()
        return intent
    }

    // Source version is historical; custody verification is a current observation, never publication
    // authority.
    fun capture(id: String, context: CoroutineContext): CanonicalFileSnapshot.Capture {
        uuid(id); context.ensureActive()
        val intent = requireNotNull(read()) { "Pending intent is missing" }
        check(intent.id == id && intent.phase == Phase.PREPARED) { "Intent is foreign or already recorded" }
        check(intent.kind == OperationKind.REPLACE) { "Creation has no original file to capture" }
        val before = requireNotNull(intent.before)
        val captured = pins { anchor, store, verify ->
            requireParents(anchor, intent.parents)
            anchor.openDirectory(intent.parents.last().path).use { parent ->
                check(parent.directoryKey() == intent.parents.last().key)
                snapshotter.capture(parent, intent.path.substringAfterLast('/'), before,
                    store, intent.snapshotName, context).also {
                    verify(); requireParents(anchor, intent.parents)
                }
            }
        }
        val recorded = intent.copy(phase = Phase.SNAPSHOT_RECORDED, snapshot = captured.copy)
        access { db, anchor, store ->
            check(readIntent(db, anchor, store) == intent) { "Pending intent changed; custody was preserved" }
            context.ensureActive()
            val record = encode(recorded)
            db.execSQL("UPDATE operation SET record_json=? WHERE slot=1", arrayOf(record))
            db.execSQL("UPDATE meta SET record_digest=? WHERE id=1", arrayOf(hash(record)))
            checkpoint("snapshot-before-commit")
            check(CanonicalFileSnapshot(maxBytes).inspect(store, intent.snapshotName, context) == captured.copy) {
                "Captured snapshot changed before receipt commit; custody was preserved"
            }
            requireParents(anchor, intent.parents)
            context.ensureActive()
        }
        checkpoint("snapshot-after-commit")
        return verifySnapshot(id, context)
    }

    fun verifySnapshot(id: String, context: CoroutineContext): CanonicalFileSnapshot.Capture {
        uuid(id); context.ensureActive()
        val intent = requireNotNull(read()) { "Pending intent is missing" }
        check(intent.id == id && intent.phase != Phase.PREPARED) { "No durable snapshot receipt exists" }
        check(intent.kind == OperationKind.REPLACE) { "Creation has no original snapshot" }
        val before = requireNotNull(intent.before)
        val result = pins { anchor, store, verify ->
            requireParents(anchor, intent.parents)
            val actual = snapshotter.inspect(store, intent.snapshotName, context)
            check(actual == intent.snapshot) { "Recorded snapshot is missing, replaced, or corrupt" }
            verify(); requireParents(anchor, intent.parents)
            CanonicalFileSnapshot.Capture(before, actual, intent.parents.last().key,
                enrollment.custodyKey, intent.path.substringAfterLast('/'), intent.snapshotName)
        }
        check(read() == intent) { "Snapshot receipt changed during verification" }
        context.ensureActive()
        return result
    }

    fun captureProposal(id: String, source: AnchoredWorkspaceFiles, name: String,
                        expected: CanonicalFileSnapshot.Version, context: CoroutineContext): CanonicalFileSnapshot.Version {
        uuid(id); version(expected); context.ensureActive()
        val intent = requireNotNull(read()) { "Pending intent is missing" }
        val requiredPhase = if (intent.kind == OperationKind.CREATE) Phase.PREPARED else Phase.SNAPSHOT_RECORDED
        check(intent.id == id && intent.phase == requiredPhase) { "Proposal requires its admitted operation and original custody" }
        check(Content(expected.size, expected.sha256, expected.mode) == intent.proposal) {
            "Staged proposal does not match the admitted content"
        }
        if (intent.kind == OperationKind.REPLACE) verifySnapshot(id, context)
        val captured = pins { anchor, store, verify ->
            requireParents(anchor, intent.parents)
            requireOriginalCopy(intent, store, context)
            proposalSnapshotter.capture(source, name, expected, store, intent.proposalName, context).also {
                check(it.copy.identity != intent.before?.identity && it.copy.identity != intent.snapshot?.identity)
                verify(); requireParents(anchor, intent.parents)
                requireOriginalCopy(intent, store, context)
            }
        }
        return recordProposal(intent, captured.copy, context)
    }

    fun captureProposal(id: String, bytes: ByteArray, context: CoroutineContext): CanonicalFileSnapshot.Version {
        uuid(id); context.ensureActive()
        val intent = requireNotNull(read()) { "Pending intent is missing" }
        val requiredPhase = if (intent.kind == OperationKind.CREATE) Phase.PREPARED else Phase.SNAPSHOT_RECORDED
        check(intent.id == id && intent.phase == requiredPhase) { "Proposal requires its admitted operation and original custody" }
        check(bytes.size.toLong() == intent.proposal.size) { "Produced proposal size differs from its admitted content" }
        if (intent.kind == OperationKind.REPLACE) verifySnapshot(id, context)
        val captured = pins { anchor, store, verify ->
            requireParents(anchor, intent.parents)
            requireOriginalCopy(intent, store, context)
            proposalSnapshotter.captureBytes(store, intent.proposalName, bytes, intent.proposal.sha256,
                intent.proposal.mode, context).also {
                check(it.identity != intent.before?.identity && it.identity != intent.snapshot?.identity)
                verify(); requireParents(anchor, intent.parents)
                requireOriginalCopy(intent, store, context)
            }
        }
        return recordProposal(intent, captured, context)
    }

    private fun recordProposal(intent: Intent, captured: CanonicalFileSnapshot.Version,
                               context: CoroutineContext): CanonicalFileSnapshot.Version {
        val recorded = intent.copy(phase = Phase.PROPOSAL_RECORDED, proposalCopy = captured)
        access { db, anchor, store ->
            check(readIntent(db, anchor, store) == intent) { "Pending intent changed; proposal custody was preserved" }
            context.ensureActive()
            val record = encode(recorded)
            db.execSQL("UPDATE operation SET record_json=? WHERE slot=1", arrayOf(record))
            db.execSQL("UPDATE meta SET record_digest=? WHERE id=1", arrayOf(hash(record)))
            checkpoint("proposal-before-commit")
            check(CanonicalFileSnapshot(maxBytes).inspect(store, intent.proposalName, context) == captured) {
                "Captured proposal changed before receipt commit; custody was preserved"
            }
            requireOriginalCopy(intent, store, context)
            requireParents(anchor, intent.parents)
            context.ensureActive()
        }
        checkpoint("proposal-after-commit")
        return verifyProposal(intent.id, context)
    }

    // Verifies both retained copies; never infers current canonical-file content or publication
    // permission.
    fun verifyProposal(id: String, context: CoroutineContext): CanonicalFileSnapshot.Version {
        uuid(id); context.ensureActive()
        val intent = requireNotNull(read()) { "Pending intent is missing" }
        check(intent.id == id && (intent.phase == Phase.PROPOSAL_RECORDED || intent.phase in STAGED_PHASES)) {
            "No durable proposal receipt exists"
        }
        if (intent.kind == OperationKind.REPLACE) verifySnapshot(id, context)
        val result = pins { anchor, store, verify ->
            requireParents(anchor, intent.parents)
            requireOriginalCopy(intent, store, context)
            snapshotter.inspect(store, intent.proposalName, context).also {
                check(it == intent.proposalCopy) { "Recorded proposal is missing, replaced, or corrupt" }
                verify(); requireParents(anchor, intent.parents)
            }
        }
        check(read() == intent) { "Proposal receipt changed during verification" }
        context.ensureActive()
        return result
    }

    // Records independent staging custody; never renames, replaces, restores or deletes canonical
    // files.
    fun stagePublication(id: String, context: CoroutineContext): CanonicalFileSnapshot.Version {
        uuid(id); context.ensureActive()
        val intent = requireNotNull(read()) { "Pending intent is missing" }
        check(intent.id == id) { "Pending intent is foreign" }
        if (intent.phase == Phase.PUBLICATION_STAGED) return verifyPublicationStage(id, context)
        check(intent.phase == Phase.PROPOSAL_RECORDED) { "Publication staging requires a durable proposal receipt" }
        val captured = pins { anchor, store, verify ->
            requireParents(anchor, intent.parents)
            requireRetainedCopies(intent, store, context)
            publicationSnapshotter.capture(store, intent.proposalName, requireNotNull(intent.proposalCopy),
                store, intent.publicationName, context).also {
                verify(); requireParents(anchor, intent.parents)
                requireRetainedCopies(intent, store, context)
            }
        }
        val recorded = intent.copy(phase = Phase.PUBLICATION_STAGED, publicationStage = captured.copy)
        validate(recorded)
        access { db, anchor, store ->
            check(readIntent(db, anchor, store) == intent) { "Pending intent changed; staging was preserved" }
            requireRetainedCopies(recorded, store, context)
            check(snapshotter.inspect(store, recorded.publicationName, context) == recorded.publicationStage) {
                "Publication stage changed before recording; all versions were preserved"
            }
            context.ensureActive()
            val record = encode(recorded)
            db.execSQL("UPDATE operation SET record_json=? WHERE slot=1", arrayOf(record))
            db.execSQL("UPDATE meta SET record_digest=? WHERE id=1", arrayOf(hash(record)))
            checkpoint("publication-stage-before-commit")
            check(CanonicalFileSnapshot(maxBytes).inspect(store, recorded.publicationName, context) == recorded.publicationStage) {
                "Publication stage changed before receipt commit; custody was preserved"
            }
            requireRetainedCopies(recorded, store, context)
            requireParents(anchor, intent.parents)
            context.ensureActive()
        }
        checkpoint("publication-stage-after-commit")
        return verifyPublicationStage(id, context)
    }

    // A current observation of recorded custody, not proof of publication or authority to replace a
    // file.
    fun verifyPublicationStage(id: String, context: CoroutineContext): CanonicalFileSnapshot.Version {
        uuid(id); context.ensureActive()
        return access { db, anchor, store ->
            val intent = requireNotNull(readIntent(db, anchor, store)) { "Pending intent is missing" }
            check(intent.id == id && intent.phase in STAGED_PHASES) { "No durable publication stage receipt exists" }
            requireRetainedCopies(intent, store, context)
            snapshotter.inspect(store, intent.publicationName, context).also {
                check(it == intent.publicationStage) { "Recorded publication stage is missing, replaced, or corrupt" }
                requireParents(anchor, intent.parents)
                context.ensureActive()
            }
        }
    }

    // Durable intent precedes one no-replace attempt; an interrupted attempt is never replayed here.
    fun retireCanonical(id: String, context: CoroutineContext): CanonicalFileSnapshot.Version {
        uuid(id); context.ensureActive()
        val prepared = access { db, anchor, store ->
            val intent = requireNotNull(readIntent(db, anchor, store)) { "Pending intent is missing" }
            check(intent.kind == OperationKind.REPLACE) { "Creation must never retire an existing file" }
            check(intent.id == id && intent.phase == Phase.PUBLICATION_STAGED) {
                "Retirement is pending or unavailable; explicit recovery is required"
            }
            requireRetainedCopies(intent, store, context)
            requireStage(intent, store, context)
            check(store.kind(intent.retiredName) == AnchoredWorkspaceFiles.Kind.ABSENT) {
                "Retirement destination exists; all versions were preserved"
            }
            anchor.openDirectory(intent.parents.last().path).use { parent ->
                check(snapshotter.inspect(parent, intent.path.substringAfterLast('/'), context) == intent.before) {
                    "Canonical version changed; retirement was not admitted"
                }
            }
            val next = intent.copy(phase = Phase.RETIREMENT_PREPARED)
            writeRecord(db, next)
            checkpoint("retirement-intent-before-commit")
            requireParents(anchor, intent.parents); context.ensureActive()
            next
        }
        checkpoint("retirement-intent-after-commit")
        context.ensureActive()
        val outcome = pins { anchor, store, verify ->
            check(read() == prepared) { "Retirement intent changed; no move was attempted" }
            requireParents(anchor, prepared.parents)
            requireRetainedCopies(prepared, store, context)
            requireStage(prepared, store, context)
            anchor.openDirectory(prepared.parents.last().path).use { parent ->
                val name = prepared.path.substringAfterLast('/')
                check(snapshotter.inspect(parent, name, context) == prepared.before) {
                    "Canonical version changed after admission; recovery is required"
                }
                checkpoint("retirement-before-move")
                verify(); requireParents(anchor, prepared.parents); context.ensureActive()
                parent.moveNoReplaceDurable(name, store, prepared.retiredName)
            }
        }
        checkpoint("retirement-after-move")
        context.ensureActive()
        val recorded = prepared.copy(phase = Phase.RETIREMENT_RECORDED, retirement = outcome)
        access { db, anchor, store ->
            check(readIntent(db, anchor, store) == prepared) { "Retirement intent changed; outcome needs recovery" }
            writeRecord(db, recorded)
            checkpoint("retirement-outcome-before-commit")
            requireParents(anchor, prepared.parents); context.ensureActive()
        }
        checkpoint("retirement-outcome-after-commit")
        return verifyRetirement(id, context)
    }

    // Observes retained bytes after a recorded move; this is not an immutable copy or publication
    // authority.
    fun verifyRetirement(id: String, context: CoroutineContext): CanonicalFileSnapshot.Version {
        uuid(id); context.ensureActive()
        return access { db, anchor, store ->
            val intent = requireNotNull(readIntent(db, anchor, store)) { "Pending intent is missing" }
            check(intent.id == id && intent.phase in PRE_PUBLICATION_RETIRED_PHASES) {
                "No durable retirement outcome exists; explicit recovery is required"
            }
            check(hasDurableRetirement(intent)) {
                "Retirement needs recovery (moved=${intent.retirement?.published}, errno=${intent.retirement?.error}); all versions were preserved"
            }
            requireRetiredVersions(intent, anchor, store, context)
        }
    }

    // Reestablishes durability of an observed retirement without retrying a move or inventing its
    // outcome.
    fun recoverRetiredState(id: String, context: CoroutineContext): CanonicalFileSnapshot.Version {
        uuid(id); context.ensureActive()
        val recorded = access { db, anchor, store ->
            val intent = requireNotNull(readIntent(db, anchor, store)) { "Pending intent is missing" }
            check(intent.id == id) { "Pending intent is foreign" }
            if (intent.retirementRecovery != null) {
                check(intent.phase in PRE_PUBLICATION_RETIRED_PHASES) { "Retirement recovery is no longer pending" }
                requireRetiredVersions(intent, anchor, store, context)
                return@access false
            }
            check(intent.phase == Phase.RETIREMENT_PREPARED ||
                intent.phase == Phase.RETIREMENT_RECORDED && intent.retirement?.let { it.published && !it.durable } == true) {
                "Recovery requires a missing or non-durable moved retirement outcome"
            }
            requireRetiredVersions(intent, anchor, store, context)
            checkpoint("retirement-recovery-verified")
            snapshotter.forceVerified(store, intent.retiredName, requireNotNull(intent.before), context)
            checkpoint("retirement-recovery-file-forced")
            anchor.openDirectory(intent.parents.last().path).use { parent ->
                check(parent.directoryKey() == intent.parents.last().key) { "Canonical parent binding changed" }
                context.ensureActive(); parent.forceDirectory()
            }
            checkpoint("retirement-recovery-parent-forced")
            context.ensureActive(); store.forceDirectory()
            checkpoint("retirement-recovery-directories-forced")
            requireRetiredVersions(intent, anchor, store, context)
            writeRecord(db, intent.copy(phase = Phase.RETIREMENT_RECOVERED, retirementRecovery = intent.before))
            checkpoint("retirement-recovery-before-commit")
            requireParents(anchor, intent.parents); context.ensureActive()
            true
        }
        if (recorded) checkpoint("retirement-recovery-after-commit")
        return verifyRetirement(id, context)
    }

    private fun hasDurableRetirement(intent: Intent): Boolean =
        intent.retirement?.durable == true || intent.retirementRecovery != null

    private fun requireRetiredVersions(intent: Intent, anchor: AnchoredWorkspaceFiles,
                                       store: AnchoredWorkspaceFiles, context: CoroutineContext): CanonicalFileSnapshot.Version {
        requireRetainedCopies(intent, store, context)
        requireStage(intent, store, context)
        check(anchor.kind(intent.path) == AnchoredWorkspaceFiles.Kind.ABSENT) {
            "Canonical path was recreated; retained and current versions need review"
        }
        return snapshotter.inspect(store, intent.retiredName, context).also {
            check(it == intent.before) { "Retired version changed; retained and original copies need review" }
            requireParents(anchor, intent.parents); context.ensureActive()
        }
    }

    // Reuses independently recorded original custody after retirement; never creates another data
    // copy.
    fun recordRetainedSnapshot(id: String, context: CoroutineContext): CanonicalFileSnapshot.Version {
        uuid(id); context.ensureActive()
        val intent = requireNotNull(read()) { "Pending intent is missing" }
        check(intent.id == id && intent.phase in PRE_PUBLICATION_RETIRED_PHASES) { "Retention requires a recorded retirement outcome" }
        verifyRetirement(id, context)
        if (intent.phase == Phase.RETENTION_RECORDED) return verifyRetainedSnapshot(id, context)
        val recorded = intent.copy(phase = Phase.RETENTION_RECORDED,
            retainedSnapshotIdentity = requireNotNull(intent.snapshot).identity)
        access { db, anchor, store ->
            check(readIntent(db, anchor, store) == intent) { "Pending intent changed; all versions were preserved" }
            requireRetainedCopies(intent, store, context)
            requireStage(intent, store, context)
            check(anchor.kind(intent.path) == AnchoredWorkspaceFiles.Kind.ABSENT) {
                "Canonical path was recreated; retained and current versions need review"
            }
            check(snapshotter.inspect(store, intent.retiredName, context) == intent.before) {
                "Retired version changed; retention was not recorded"
            }
            checkpoint("retention-verified")
            context.ensureActive()
            writeRecord(db, recorded)
            checkpoint("retention-before-commit")
            requireParents(anchor, intent.parents); context.ensureActive()
        }
        checkpoint("retention-after-commit")
        verifyRetirement(id, context)
        return verifyRetainedSnapshot(id, context)
    }

    // Historical independent custody only; current retirement must be verified separately before
    // publication.
    fun verifyRetainedSnapshot(id: String, context: CoroutineContext): CanonicalFileSnapshot.Version {
        uuid(id); context.ensureActive()
        return access { db, anchor, store ->
            val intent = requireNotNull(readIntent(db, anchor, store)) { "Pending intent is missing" }
            check(intent.id == id && intent.phase in RETAINED_PHASES) { "No durable retention receipt exists" }
            check(intent.kind == OperationKind.REPLACE) { "Creation has no retained original snapshot" }
            requireRetainedCopies(intent, store, context)
            if (intent.phase !in PUBLICATION_PHASES) requireStage(intent, store, context)
            requireParents(anchor, intent.parents); context.ensureActive()
            requireNotNull(intent.snapshot)
        }
    }

    // Commits intent before one no-replace publication; every interrupted attempt requires explicit
    // recovery.
    fun publishStaged(id: String, context: CoroutineContext): CanonicalFileSnapshot.Version {
        uuid(id); context.ensureActive()
        val prepared = access { db, anchor, store ->
            val intent = requireNotNull(readIntent(db, anchor, store)) { "Pending intent is missing" }
            val requiredPhase = if (intent.kind == OperationKind.CREATE) Phase.PUBLICATION_STAGED else Phase.RETENTION_RECORDED
            check(intent.id == id && intent.phase == requiredPhase) {
                "Publication is pending or unavailable; explicit recovery is required"
            }
            requirePublicationReady(intent, anchor, store, context)
            val next = intent.copy(phase = Phase.PUBLICATION_PREPARED)
            writeRecord(db, next)
            checkpoint("publication-intent-before-commit")
            requireParents(anchor, intent.parents); context.ensureActive()
            next
        }
        checkpoint("publication-intent-after-commit")
        context.ensureActive()
        val outcome = pins { anchor, store, verify ->
            check(read() == prepared) { "Publication intent changed; no move was attempted" }
            requirePublicationReady(prepared, anchor, store, context)
            requireParents(anchor, prepared.parents)
            anchor.openDirectory(prepared.parents.last().path).use { parent ->
                checkpoint("publication-before-move")
                verify(); requireParents(anchor, prepared.parents); context.ensureActive()
                store.moveNoReplaceDurable(prepared.publicationName, parent, prepared.path.substringAfterLast('/'))
            }
        }
        checkpoint("publication-after-move")
        context.ensureActive()
        val recorded = prepared.copy(phase = Phase.PUBLICATION_RECORDED, publication = outcome)
        access { db, anchor, store ->
            check(readIntent(db, anchor, store) == prepared) { "Publication intent changed; outcome needs recovery" }
            writeRecord(db, recorded)
            checkpoint("publication-outcome-before-commit")
            requireParents(anchor, prepared.parents); context.ensureActive()
        }
        checkpoint("publication-outcome-after-commit")
        return verifyPublication(id, context)
    }

    // Verifies a recorded outcome and current versions; never declares completion or performs
    // recovery.
    fun verifyPublication(id: String, context: CoroutineContext): CanonicalFileSnapshot.Version {
        uuid(id); context.ensureActive()
        return access { db, anchor, store ->
            val intent = requireNotNull(readIntent(db, anchor, store)) { "Pending intent is missing" }
            check(intent.id == id && intent.phase in RECORDED_PUBLICATION_PHASES) {
                "No durable publication outcome exists; explicit recovery is required"
            }
            requireVerifiedPublication(intent, anchor, store, context)
        }
    }

    fun recordPublicationCompletion(id: String, context: CoroutineContext): CanonicalFileSnapshot.Version {
        uuid(id); context.ensureActive()
        val recorded = access { db, anchor, store ->
            val intent = requireNotNull(readIntent(db, anchor, store)) { "Pending intent is missing" }
            check(intent.id == id && intent.phase in RECORDED_PUBLICATION_PHASES) {
                "Completion requires a recorded publication outcome"
            }
            requireVerifiedPublication(intent, anchor, store, context)
            if (intent.phase == Phase.PUBLICATION_COMPLETED) return@access false
            checkpoint("publication-completion-verified")
            requireVerifiedPublication(intent, anchor, store, context)
            writeRecord(db, intent.copy(phase = Phase.PUBLICATION_COMPLETED))
            checkpoint("publication-completion-before-commit")
            requireParents(anchor, intent.parents); context.ensureActive()
            true
        }
        if (recorded) checkpoint("publication-completion-after-commit")
        return verifyPublication(id, context)
    }

    // Explicitly reestablishes durability of the observed published inode without replaying any
    // move.
    fun recoverPublishedState(id: String, context: CoroutineContext): CanonicalFileSnapshot.Version {
        uuid(id); context.ensureActive()
        val recorded = access { db, anchor, store ->
            val intent = requireNotNull(readIntent(db, anchor, store)) { "Pending intent is missing" }
            check(intent.id == id) { "Pending intent is foreign" }
            if (intent.publicationRecovery != null) {
                requireVerifiedPublication(intent, anchor, store, context)
                return@access false
            }
            check(intent.phase == Phase.PUBLICATION_PREPARED ||
                (intent.phase == Phase.PUBLICATION_RECORDED && intent.publication?.let { it.published && !it.durable } == true)) {
                "Recovery requires a missing or non-durable moved publication outcome"
            }
            check(intent.publicationConflict == null) { "Recorded conflict requires explicit resolution" }
            requirePublishedVersions(intent, anchor, store, context)
            checkpoint("publication-recovery-verified")
            context.ensureActive()
            anchor.openDirectory(intent.parents.last().path).use { parent ->
                check(parent.directoryKey() == intent.parents.last().key) { "Canonical parent binding changed" }
                snapshotter.forceVerified(parent, intent.path.substringAfterLast('/'), requireNotNull(intent.publicationStage), context)
                checkpoint("publication-recovery-file-forced")
                context.ensureActive(); parent.forceDirectory()
                checkpoint("publication-recovery-parent-forced")
                context.ensureActive(); store.forceDirectory()
            }
            checkpoint("publication-recovery-directories-forced")
            requirePublishedVersions(intent, anchor, store, context)
            writeRecord(db, intent.copy(phase = Phase.PUBLICATION_RECOVERED, publicationRecovery = intent.publicationStage))
            checkpoint("publication-recovery-before-commit")
            requireParents(anchor, intent.parents); context.ensureActive()
            true
        }
        if (recorded) checkpoint("publication-recovery-after-commit")
        return verifyPublication(id, context)
    }

    private fun hasDurablePublication(intent: Intent): Boolean =
        intent.publication?.durable == true || intent.publicationRecovery != null

    private fun requireVerifiedPublication(intent: Intent, anchor: AnchoredWorkspaceFiles,
                                           store: AnchoredWorkspaceFiles, context: CoroutineContext): CanonicalFileSnapshot.Version {
        check(intent.publicationConflict == null) { "Recorded publication conflict requires explicit resolution" }
        check(hasDurablePublication(intent)) {
            "Publication needs recovery (moved=${intent.publication?.published}, errno=${intent.publication?.error}); all versions were preserved"
        }
        return requirePublishedVersions(intent, anchor, store, context)
    }

    private fun requirePublishedVersions(intent: Intent, anchor: AnchoredWorkspaceFiles,
                                         store: AnchoredWorkspaceFiles, context: CoroutineContext): CanonicalFileSnapshot.Version {
        requireRetainedCopies(intent, store, context)
        if (intent.kind == OperationKind.REPLACE) check(snapshotter.inspect(store, intent.retiredName, context) == intent.before) {
            "Retired version changed; original and CLI versions need review"
        }
        check(store.kind(intent.publicationName) == AnchoredWorkspaceFiles.Kind.ABSENT) {
            "Publication stage reappeared; all versions need review"
        }
        return anchor.openDirectory(intent.parents.last().path).use { parent ->
            check(parent.directoryKey() == intent.parents.last().key) { "Canonical parent binding changed" }
            snapshotter.inspect(parent, intent.path.substringAfterLast('/'), context).also {
                check(it == intent.publicationStage) { "Published version changed; current and proposed versions need review" }
                requireParents(anchor, intent.parents); context.ensureActive()
            }
        }
    }

    // Records the first observed conflict; a later matching version never clears it implicitly.
    fun recordPublicationConflict(id: String, context: CoroutineContext): PublicationConflict {
        uuid(id); context.ensureActive()
        val result = access { db, anchor, store ->
            val intent = requireNotNull(readIntent(db, anchor, store)) { "Pending intent is missing" }
            check(intent.id == id && intent.phase in RECORDED_PUBLICATION_PHASES && hasDurablePublication(intent)) {
                "Conflict recording requires a durable publication outcome; explicit recovery is required"
            }
            intent.publicationConflict?.let { return@access it to false }
            requireRetainedCopies(intent, store, context)
            val conflict = requireNotNull(observePublicationConflict(intent, anchor, store, context)) {
                "No stable publication conflict was observed"
            }
            checkpoint("publication-conflict-observed")
            context.ensureActive()
            requireRetainedCopies(intent, store, context)
            check(observePublicationConflict(intent, anchor, store, context) == conflict) {
                "Publication conflict changed while observing; no receipt was recorded"
            }
            writeRecord(db, intent.copy(publicationConflict = conflict))
            checkpoint("publication-conflict-before-commit")
            requireParents(anchor, intent.parents); context.ensureActive()
            conflict to true
        }
        if (result.second) checkpoint("publication-conflict-after-commit")
        return result.first
    }

    private fun observePublicationConflict(intent: Intent, anchor: AnchoredWorkspaceFiles,
                                           store: AnchoredWorkspaceFiles, context: CoroutineContext): PublicationConflict? {
        fun observe(parent: AnchoredWorkspaceFiles, name: String, role: ConflictRole,
                    expected: CanonicalFileSnapshot.Version?): PublicationConflict? {
            context.ensureActive()
            val kind = parent.kind(name)
            val observed = if (kind == AnchoredWorkspaceFiles.Kind.FILE) snapshotter.inspect(parent, name, context) else null
            check(parent.kind(name) == kind) { "Publication entry changed during conflict observation" }
            if (kind == (if (expected == null) AnchoredWorkspaceFiles.Kind.ABSENT else AnchoredWorkspaceFiles.Kind.FILE)
                && observed == expected) return null
            return PublicationConflict(role, kind, observed)
        }
        return ((if (intent.kind == OperationKind.REPLACE) observe(store, intent.retiredName, ConflictRole.RETIRED, intent.before) else null)
            ?: observe(store, intent.publicationName, ConflictRole.STAGE, null)
            ?: anchor.openDirectory(intent.parents.last().path).use { parent ->
                check(parent.directoryKey() == intent.parents.last().key) { "Canonical parent binding changed" }
                observe(parent, intent.path.substringAfterLast('/'), ConflictRole.CANONICAL, intent.publicationStage)
            }).also { requireParents(anchor, intent.parents); context.ensureActive() }
    }

    private fun requirePublicationReady(intent: Intent, anchor: AnchoredWorkspaceFiles,
                                        store: AnchoredWorkspaceFiles, context: CoroutineContext) {
        requireRetainedCopies(intent, store, context)
        requireStage(intent, store, context)
        if (intent.kind == OperationKind.REPLACE) {
            check(hasDurableRetirement(intent) && intent.retainedSnapshotIdentity == intent.snapshot?.identity)
            check(snapshotter.inspect(store, intent.retiredName, context) == intent.before) {
                "Retired version changed; publication was not attempted"
            }
        }
        check(anchor.kind(intent.path) == AnchoredWorkspaceFiles.Kind.ABSENT) {
            "Canonical path was recreated; current and proposed versions need review"
        }
        requireParents(anchor, intent.parents); context.ensureActive()
    }

    private fun requireStage(intent: Intent, store: AnchoredWorkspaceFiles, context: CoroutineContext) {
        check(snapshotter.inspect(store, intent.publicationName, context) == intent.publicationStage) {
            "Recorded publication stage is missing, replaced, or corrupt"
        }
    }

    // Preserves the recorded conflicting version; never resolves or overwrites the current source.
    fun capturePublicationConflict(id: String, context: CoroutineContext): CanonicalFileSnapshot.Version {
        uuid(id); context.ensureActive()
        val intent = requireNotNull(read()) { "Pending intent is missing" }
        check(intent.id == id) { "Pending intent is foreign" }
        val conflict = requireNotNull(intent.publicationConflict) { "No publication conflict was recorded" }
        if (intent.publicationConflictCopy != null) return verifyPublicationConflictCopy(id, context)
        check(conflict.kind == AnchoredWorkspaceFiles.Kind.FILE) { "Only a recorded regular file can be captured" }
        val observed = requireNotNull(conflict.observed)
        val captured = pins { anchor, store, verify ->
            check(read() == intent) { "Conflict metadata changed; no capture was attempted" }
            requireRetainedCopies(intent, store, context)
            fun capture(parent: AnchoredWorkspaceFiles, name: String) =
                conflictSnapshotter.capture(parent, name, observed, store, intent.conflictCopyName, context).copy
            val copy = when (conflict.role) {
                ConflictRole.RETIRED -> capture(store, intent.retiredName)
                ConflictRole.STAGE -> capture(store, intent.publicationName)
                ConflictRole.CANONICAL -> anchor.openDirectory(intent.parents.last().path).use { parent ->
                    check(parent.directoryKey() == intent.parents.last().key) { "Canonical parent binding changed" }
                    capture(parent, intent.path.substringAfterLast('/'))
                }
            }
            verify(); requireParents(anchor, intent.parents); context.ensureActive()
            copy
        }
        checkpoint("conflict-copy-captured")
        access { db, anchor, store ->
            check(readIntent(db, anchor, store) == intent) { "Conflict metadata changed; captured data was preserved" }
            requireRetainedCopies(intent, store, context)
            check(snapshotter.inspect(store, intent.conflictCopyName, context) == captured) {
                "Captured conflict changed; no receipt was recorded"
            }
            context.ensureActive()
            writeRecord(db, intent.copy(publicationConflictCopy = captured))
            checkpoint("conflict-copy-before-commit")
            requireParents(anchor, intent.parents); context.ensureActive()
        }
        checkpoint("conflict-copy-after-commit")
        return verifyPublicationConflictCopy(id, context)
    }

    // Verifies historical custody only; later source changes remain separate and unresolved.
    fun verifyPublicationConflictCopy(id: String, context: CoroutineContext): CanonicalFileSnapshot.Version {
        uuid(id); context.ensureActive()
        return access { db, anchor, store ->
            val intent = requireNotNull(readIntent(db, anchor, store)) { "Pending intent is missing" }
            check(intent.id == id) { "Pending intent is foreign" }
            requireConflictCopy(intent, store, context).also {
                requireParents(anchor, intent.parents); context.ensureActive()
            }
        }
    }

    // A current observation for comparison, never permission to resolve or overwrite later.
    fun inspectPublicationConflict(id: String, context: CoroutineContext): PublicationConflictReview {
        uuid(id); context.ensureActive()
        return access { db, anchor, store ->
            val intent = requireNotNull(readIntent(db, anchor, store)) { "Pending intent is missing" }
            check(intent.id == id) { "Pending intent is foreign" }
            val conflict = requireNotNull(intent.publicationConflict) { "No publication conflict was recorded" }
            val captured = requireConflictCopy(intent, store, context)
            fun observe(): Pair<AnchoredWorkspaceFiles.Kind, CanonicalFileSnapshot.Version?> {
                context.ensureActive()
                fun read(parent: AnchoredWorkspaceFiles, name: String): Pair<AnchoredWorkspaceFiles.Kind, CanonicalFileSnapshot.Version?> {
                    val kind = parent.kind(name)
                    val version = if (kind == AnchoredWorkspaceFiles.Kind.FILE) snapshotter.inspect(parent, name, context) else null
                    check(parent.kind(name) == kind) { "Conflict source changed during review" }
                    return kind to version
                }
                return when (conflict.role) {
                    ConflictRole.RETIRED -> read(store, intent.retiredName)
                    ConflictRole.STAGE -> read(store, intent.publicationName)
                    ConflictRole.CANONICAL -> anchor.openDirectory(intent.parents.last().path).use { parent ->
                        check(parent.directoryKey() == intent.parents.last().key) { "Canonical parent binding changed" }
                        read(parent, intent.path.substringAfterLast('/'))
                    }
                }
            }
            val current = observe()
            checkpoint("conflict-review-observed")
            check(current == observe()) { "Conflict source changed during review; no resolution was performed" }
            check(requireConflictCopy(intent, store, context) == captured) { "Conflict custody changed during review" }
            requireParents(anchor, intent.parents); context.ensureActive()
            PublicationConflictReview(id, conflict, captured, current.first, current.second)
        }
    }

    private fun requireConflictCopy(intent: Intent, store: AnchoredWorkspaceFiles, context: CoroutineContext): CanonicalFileSnapshot.Version {
        val expected = requireNotNull(intent.publicationConflictCopy) { "No conflict copy receipt exists" }
        requireRetainedCopies(intent, store, context)
        return snapshotter.inspect(store, intent.conflictCopyName, context).also {
            check(it == expected) { "Recorded conflict copy is missing, replaced, or corrupt" }
        }
    }

    private fun writeRecord(db: SQLiteDatabase, intent: Intent) {
        validate(intent)
        val record = encode(intent)
        db.execSQL("UPDATE operation SET record_json=? WHERE slot=1", arrayOf(record))
        db.execSQL("UPDATE meta SET record_digest=? WHERE id=1", arrayOf(hash(record)))
    }

    private fun requireOriginalCopy(intent: Intent, store: AnchoredWorkspaceFiles, context: CoroutineContext) {
        if (intent.kind == OperationKind.CREATE) {
            check(store.kind(intent.snapshotName) == AnchoredWorkspaceFiles.Kind.ABSENT &&
                store.kind(intent.retiredName) == AnchoredWorkspaceFiles.Kind.ABSENT) {
                "Creation has unexpected original custody; all files were preserved"
            }
            return
        }
        check(snapshotter.inspect(store, intent.snapshotName, context) == intent.snapshot) {
            "Recorded original snapshot is missing, replaced, or corrupt"
        }
    }

    private fun requireRetainedCopies(intent: Intent, store: AnchoredWorkspaceFiles, context: CoroutineContext) {
        requireOriginalCopy(intent, store, context)
        check(snapshotter.inspect(store, intent.proposalName, context) == intent.proposalCopy) {
            "Recorded proposal is missing, replaced, or corrupt"
        }
    }

    private fun readIntent(db: SQLiteDatabase, anchor: AnchoredWorkspaceFiles, store: AnchoredWorkspaceFiles): Intent? =
        readStoredIntent(db, store)?.also { requireParents(anchor, it.parents) }

    private data class StoredRetention(val state: CanonicalJournalRetention.State, val intents: List<Intent>)

    private fun readRetention(db: SQLiteDatabase): StoredRetention? = CanonicalJournalRetention.read(db)?.let { state ->
        StoredRetention(state, state.entries.map { entry ->
            decode(entry.record).also { intent ->
                validate(intent)
                check(intent.id == entry.id && encode(intent) == entry.record &&
                    intent.phase == Phase.PUBLICATION_COMPLETED && intent.publicationConflict == null &&
                    entry.retainedBytes == retainedBytes(intent)) { "Invalid completed recovery record" }
            }
        })
    }

    private fun completedFiles(intent: Intent): Map<String, CanonicalFileSnapshot.Version> = buildMap {
        put(intent.proposalName, requireNotNull(intent.proposalCopy))
        if (intent.kind == OperationKind.REPLACE) {
            put(intent.snapshotName, requireNotNull(intent.snapshot))
            put(intent.retiredName, requireNotNull(intent.before))
        }
    }

    private fun retainedBytes(intent: Intent): Long =
        Math.addExact(Math.multiplyExact(2L, intent.before?.size ?: 0L), intent.proposal.size)

    private fun peakBytes(intent: Intent): Long = peakBytes(intent.before?.size ?: 0L, intent.proposal.size)

    private fun peakBytes(beforeBytes: Long, proposedBytes: Long): Long =
        Math.addExact(Math.addExact(Math.multiplyExact(2L, beforeBytes), Math.multiplyExact(2L, proposedBytes)), maxBytes)

    private fun requireCompletedCustody(retention: StoredRetention, store: AnchoredWorkspaceFiles) {
        retention.intents.forEach { intent ->
            completedFiles(intent).keys.forEach { name ->
                check(store.kind(name) == AnchoredWorkspaceFiles.Kind.FILE) {
                    "Completed custody is missing or unsafe; retained work requires review: $name"
                }
            }
        }
    }

    private fun requireKnownCustody(store: AnchoredWorkspaceFiles, pending: Intent?, retention: StoredRetention?) {
        if (retention == null && pending != null) return
        val allowed = buildSet {
            retention?.intents?.forEach { addAll(completedFiles(it).keys) }
            pending?.let { addAll(listOf(it.snapshotName, it.proposalName, it.publicationName, it.retiredName, it.conflictCopyName)) }
        }
        store.forEachChild { name ->
            check(name in allowed) { "Custody exists without matching membership; all files were preserved: $name" }
        }
    }

    private fun readStoredIntent(db: SQLiteDatabase, store: AnchoredWorkspaceFiles,
                                 retention: StoredRetention? = readRetention(db)): Intent? {
        check(scalar(db, "SELECT count(*) FROM meta") == 1L) { "Authority metadata is missing or duplicated" }
        fun bounded(column: String, limit: Int) =
            "CASE WHEN $column IS NULL OR (typeof($column)='text' AND length($column)<=$limit) THEN $column ELSE 0 END"
        val metadata = listOf(bounded("binding", 64), bounded("generation", 36),
            bounded("expected_operation", 36), bounded("record_digest", 64)).joinToString(",")
        val membership = db.rawQuery("SELECT id,$metadata FROM meta", null).use { row ->
            check(row.moveToFirst() && integer(row, 0) == 1L && string(row, 1, 64) == enrollmentHash(enrollment) &&
                string(row, 2, 36) == enrollment.generation) { "Authority enrollment is foreign or corrupt" }
            val id = if (row.isNull(3)) null else string(row, 3, 36).also(::uuid)
            val digest = if (row.isNull(4)) null else string(row, 4, 64).also { check(SHA.matches(it)) }
            check((id == null) == (digest == null) && !row.moveToNext()) { "Invalid authority membership" }
            id to digest
        }
        val count = scalar(db, "SELECT count(*) FROM operation")
        check(count == if (membership.first == null) 0L else 1L) { "Admitted operation is missing or unexpected" }
        if (count == 0L) {
            requireKnownCustody(store, null, retention)
            requireCommandMembership(db, retention, null)
            return null
        }
        val record = db.rawQuery("SELECT slot,CASE WHEN typeof(record_json)='text' AND length(record_json)<=${RECORD_LIMIT} THEN record_json END FROM operation", null).use { row ->
            check(row.moveToFirst() && integer(row, 0) == 1L)
            string(row, 1, RECORD_LIMIT).also { check(!row.moveToNext()) }
        }
        check(hash(record) == membership.second) { "Operation digest does not match durable membership" }
        return decode(record).also {
            validate(it)
            check(it.kind != OperationKind.CREATE || retention != null) {
                "Creation retention authority is missing; existing evidence requires review"
            }
            check(it.id == membership.first && encode(it) == record) { "Operation metadata is not canonical" }
            check(retention?.intents?.none { entry -> entry.id == it.id } != false) { "Operation is both pending and completed" }
            requireKnownCustody(store, it, retention)
            requireCommandMembership(db, retention, it)
        }
    }

    private fun requireMembershipRoot(request: CatalogMembershipCommands.Request) {
        check(enrollment.projectId == ProjectCatalogUpdate.AUTHORITY_ID)
        val app = checkNotNull(root.parentFile)
        val target = ProjectWorkspacePolicy.requireDirectChild(ProjectWorkspacePolicy.projectsRoot(app), File(request.rootPath))
        AnchoredWorkspaceFiles(target).use { check(it.directoryKey() == request.rootKey) { "Membership root was replaced" } }
    }

    private fun requireCommandRequest(request: CatalogMembershipCommands.Request, intent: Intent) {
        check(intent.kind == OperationKind.REPLACE && intent.path == "projects.json")
        val before = checkNotNull(intent.before)
        check(request.beforeBytes == before.size && request.beforeHash == before.sha256 &&
            request.proposal.toByteArray(Charsets.UTF_8).size.toLong() == intent.proposal.size &&
            hash(request.proposal) == intent.proposal.sha256) { "Membership proposal differs from admitted operation" }
    }

    private fun preparedIdentity(actual: Intent): Intent = Intent(actual.id, actual.path, actual.parents,
        actual.before, actual.proposal, Phase.PREPARED, null, kind = actual.kind)

    internal fun preparedIdentityRecord(actual: Intent): String = encode(preparedIdentity(actual))

    /** Filesystem-only boundary check; safe inside capture callbacks, never re-enters SQLite. */
    internal fun requirePreparationInventory(intent: Intent, inFlightName: String?, context: CoroutineContext) {
        check(intent.kind == OperationKind.REPLACE && intent.path == "projects.json")
        val next = when (intent.phase) {
            Phase.PREPARED -> intent.snapshotName
            Phase.SNAPSHOT_RECORDED -> intent.proposalName
            Phase.PROPOSAL_RECORDED -> intent.publicationName
            Phase.PUBLICATION_STAGED -> null
            else -> error("Preparation continuation cannot advance publication")
        }
        check(inFlightName == null || inFlightName == next)
        validate(intent)
        val expected = linkedMapOf(intent.snapshotName to intent.snapshot, intent.proposalName to intent.proposalCopy,
            intent.publicationName to intent.publicationStage, intent.retiredName to null, intent.conflictCopyName to null)
        val observer = CanonicalFileSnapshot(maxBytes)
        pins { anchor, store, verify ->
            requireParents(anchor, intent.parents)
            check(observer.inspect(anchor, intent.path, context) == intent.before) { "Original catalog changed during preparation review" }
            expected.forEach { (name, version) ->
                if (version != null) check(observer.inspect(store, name, context) == version) { "Recorded preparation custody changed" }
                else if (name == inFlightName) {
                    // Only this call's exclusive copy owner may be creating this name. Never survives a
                    // return/restart.
                    check(store.kind(name) in listOf(AnchoredWorkspaceFiles.Kind.ABSENT, AnchoredWorkspaceFiles.Kind.FILE))
                } else check(store.kind(name) == AnchoredWorkspaceFiles.Kind.ABSENT) {
                    "Unrecorded preparation custody requires review; all files were preserved: $name"
                }
            }
            verify(); requireParents(anchor, intent.parents); context.ensureActive()
        }
    }

    private fun requireCommandMembership(db: SQLiteDatabase, retention: StoredRetention?, pending: Intent?) {
        val state = CatalogMembershipCommands.read(db) ?: return
        check(state.receipts.size <= checkNotNull(retention).state.policy.maxEntries)
        val operations = retention.intents + listOfNotNull(pending)
        check(state.operations.keys == operations.map { it.id }.toSet()) { "Command operation classification is incomplete" }
        state.compacted.forEach { compact ->
            check(compact.authority == enrollmentHash(enrollment)) { "Foreign compact membership authority" }
            val actual = retention.intents.singleOrNull { it.id == compact.operationId }
                ?: error("Compact membership requires a retained completed operation")
            check(compact.completedRecordHash == hash(encode(actual)) &&
                compact.frozenIntentHash == hash(preparedIdentityRecord(actual))) { "Compact membership receipt binding differs" }
        }
        state.receipts.forEach { receipt ->
            check(receipt.authority == enrollmentHash(enrollment)) { "Foreign membership authority" }
            receipt.operationId?.let { id ->
                val actual = operations.single { it.id == id }
                val frozen = decode(checkNotNull(receipt.frozenIntent))
                validate(frozen)
                check(encode(frozen) == receipt.frozenIntent && frozen.phase == Phase.PREPARED &&
                    frozen == preparedIdentity(actual))
                requireCommandRequest(receipt.request, frozen)
                if (pending?.id == id) check(receipt.outcome == null && !receipt.acknowledged)
                else check(receipt.outcome == hash(encode(actual))) { "Command completion does not match exact receipt" }
            }
            if (receipt.operationId == null) check(receipt.outcome == CatalogMembershipCommands.noChangeOutcome(receipt))
        }
    }

    // Only used as a canonical non-null placeholder while deriving a no-change outcome.
    private val nullPlaceholder = "0".repeat(64)

    fun enableMembershipCommands(context: CoroutineContext) = access { db, _, store ->
        context.ensureActive()
        val retention = checkNotNull(readRetention(db))
        check(readStoredIntent(db, store, retention) == null) { "Pending preparation prevents command migration" }
        requireCompletedCustody(retention, store)
        if (db.version in 3..4) return@access
        check(db.version == 2)
        checkpoint("membership-migration-verified")
        context.ensureActive()
        CatalogMembershipCommands.enable(db, retention.intents.map { it.id })
        checkpoint("membership-migration-before-commit")
        context.ensureActive()
    }

    fun enableMembershipCompaction(context: CoroutineContext) = access { db, _, store ->
        context.ensureActive()
        val retention = checkNotNull(readRetention(db))
        check(readStoredIntent(db, store, retention) == null) { "Pending preparation prevents compaction migration" }
        requireCompletedCustody(retention, store)
        if (db.version == 4) return@access
        check(db.version == 3) { "Membership commands must be explicitly enabled first" }
        checkpoint("membership-compaction-migration-verified")
        context.ensureActive()
        CatalogMembershipCommands.enableCompaction(db)
        checkpoint("membership-compaction-migration-before-commit")
        context.ensureActive()
        readStoredIntent(db, store, retention)
    }

    fun admitMembership(request: CatalogMembershipCommands.Request, before: CanonicalFileSnapshot.Version,
                        proposal: Content, context: CoroutineContext): Intent =
        admitOperation("projects.json", before, proposal, OperationKind.REPLACE, context, request)

    fun admitMembershipNoChange(request: CatalogMembershipCommands.Request, context: CoroutineContext): CatalogMembershipCommands.Receipt =
        access { db, anchor, store ->
            context.ensureActive()
            check(db.version in 3..4) { "Membership commands require explicit migration" }
            val retention = checkNotNull(readRetention(db))
            check(readStoredIntent(db, store, retention) == null)
            requireCompletedCustody(retention, store)
            requireMembershipRoot(request)
            val before = snapshotter.inspect(anchor, "projects.json", context)
            check(before.size == request.beforeBytes && before.sha256 == request.beforeHash &&
                hash(request.proposal) == before.sha256 && request.proposal.toByteArray(Charsets.UTF_8).size.toLong() == before.size)
            val initial = CatalogMembershipCommands.Receipt(UUID.randomUUID().toString(), enrollmentHash(enrollment),
                request, null, null, nullPlaceholder)
            val receipt = initial.copy(outcome = CatalogMembershipCommands.noChangeOutcome(initial))
            CatalogMembershipCommands.admit(db, null, receipt, retention.state.policy.maxEntries)
            checkpoint("membership-no-change-before-commit")
            requireMembershipRoot(request)
            context.ensureActive()
            check(snapshotter.inspect(anchor, "projects.json", context) == before)
            receipt
        }

    fun membershipCommands(context: CoroutineContext): List<CatalogMembershipCommands.Receipt> =
        pins { _, store, verify -> accessDatabase(verify, readOnly = true) { db ->
            context.ensureActive()
            val retention = checkNotNull(readRetention(db))
            readStoredIntent(db, store, retention)
            requireCompletedCustody(retention, store)
            checkNotNull(CatalogMembershipCommands.read(db)) { "Membership commands are not enabled" }.receipts
        } }

    fun expireAcceptedNoChange(token: String, outcome: String, context: CoroutineContext): CatalogMembershipCommands.Expiry =
        access { db, _, store ->
            context.ensureActive()
            val retention = checkNotNull(readRetention(db))
            readStoredIntent(db, store, retention)
            requireCompletedCustody(retention, store)
            val result = CatalogMembershipCommands.expireAcceptedNoChange(db, token, outcome)
            if (result == CatalogMembershipCommands.Expiry.EXPIRED) checkpoint("membership-expiry-before-commit")
            context.ensureActive()
            readStoredIntent(db, store, retention)
            result
        }.also {
            if (it == CatalogMembershipCommands.Expiry.EXPIRED) checkpoint("membership-expiry-after-commit")
            context.ensureActive()
        }

    fun expireAcceptedOperation(token: String, outcome: String, context: CoroutineContext): CatalogMembershipCommands.Expiry =
        access { db, _, store ->
            context.ensureActive()
            val retention = checkNotNull(readRetention(db))
            readStoredIntent(db, store, retention)
            requireCompletedCustody(retention, store)
            val result = CatalogMembershipCommands.expireAcceptedOperation(db, token, outcome)
            if (result == CatalogMembershipCommands.Expiry.EXPIRED) checkpoint("membership-compaction-before-commit")
            context.ensureActive()
            readStoredIntent(db, store, retention)
            result
        }.also {
            if (it == CatalogMembershipCommands.Expiry.EXPIRED) checkpoint("membership-compaction-after-commit")
            context.ensureActive()
        }

    fun acknowledgeMembership(token: String, outcome: String, context: CoroutineContext): CatalogMembershipCommands.Receipt =
        access { db, _, store ->
            context.ensureActive()
            val retention = checkNotNull(readRetention(db))
            readStoredIntent(db, store, retention)
            requireCompletedCustody(retention, store)
            val result = CatalogMembershipCommands.acknowledge(db, token, outcome)
            requireMembershipRoot(result.request)
            checkpoint("membership-ack-before-commit")
            requireMembershipRoot(result.request)
            context.ensureActive()
            result
        }.also { checkpoint("membership-ack-after-commit"); context.ensureActive() }

    private fun validate(intent: Intent) {
        uuid(intent.id); validatePath(intent.path); content(intent.proposal)
        check(intent.parents.map { it.path } == parentPaths(intent.path)) { "Incomplete source parent binding" }
        intent.parents.forEach { text(it.key, 256) }
        check(intent.parents.first().key == enrollment.rootKey)
        if (intent.kind == OperationKind.CREATE) {
            check(intent.phase in CREATION_PHASES) { "Creation cannot enter an original-file phase" }
            check(intent.before == null && intent.snapshot == null && intent.retirement == null &&
                intent.retirementRecovery == null && intent.retainedSnapshotIdentity == null) {
                "Creation cannot contain original-file evidence"
            }
        } else {
            val before = requireNotNull(intent.before) { "Replacement requires its original version" }
            version(before)
            if (intent.phase == Phase.PREPARED) check(intent.snapshot == null)
            else {
                val copy = requireNotNull(intent.snapshot)
                version(copy)
                check(copy.identity != before.identity && copy.copy(identity = before.identity) == before)
            }
        }
        if (intent.phase in listOf(Phase.PREPARED, Phase.SNAPSHOT_RECORDED)) check(intent.proposalCopy == null)
        else {
            val copy = requireNotNull(intent.proposalCopy)
            version(copy)
            check(Content(copy.size, copy.sha256, copy.mode) == intent.proposal)
            check(copy.identity != intent.before?.identity && copy.identity != intent.snapshot?.identity)
        }
        if (intent.phase !in STAGED_PHASES) check(intent.publicationStage == null)
        else {
            val stage = requireNotNull(intent.publicationStage)
            version(stage)
            check(Content(stage.size, stage.sha256, stage.mode) == intent.proposal)
            check(stage.identity !in listOf(intent.before?.identity, intent.snapshot?.identity, intent.proposalCopy?.identity))
        }
        if (intent.kind == OperationKind.CREATE || intent.phase !in RETIRED_PHASES) check(intent.retirement == null)
        else {
            if (intent.retirementRecovery == null) requireNotNull(intent.retirement)
            intent.retirement?.let { outcome ->
                check(outcome.error in 0..4095 && (outcome.published || outcome.error != 0)) {
                    "Invalid retirement outcome"
                }
            }
        }
        if (intent.phase == Phase.RETIREMENT_RECOVERED) requireNotNull(intent.retirementRecovery)
        intent.retirementRecovery?.let { recovery ->
            check(intent.phase == Phase.RETIREMENT_RECOVERED || intent.phase in RETAINED_PHASES)
            version(recovery)
            check(recovery == intent.before) { "Recovery does not reference the original retired inode" }
            check(intent.retirement == null || intent.retirement.published && !intent.retirement.durable) {
                "Recovery must preserve a missing or non-durable moved retirement outcome"
            }
        }
        if (intent.kind == OperationKind.CREATE || intent.phase !in RETAINED_PHASES) check(intent.retainedSnapshotIdentity == null)
        else {
            check(hasDurableRetirement(intent)) { "Retention requires durable retirement or its recovery receipt" }
            check(intent.retainedSnapshotIdentity == requireNotNull(intent.snapshot).identity) {
                "Retention does not reference the recorded independent snapshot"
            }
        }
        if (intent.phase !in RECORDED_PUBLICATION_PHASES) check(intent.publication == null)
        else {
            if (intent.publicationRecovery == null) requireNotNull(intent.publication)
            intent.publication?.let { outcome ->
                check(outcome.error in 0..4095 && (outcome.published || outcome.error != 0)) { "Invalid publication outcome" }
            }
        }
        if (intent.phase == Phase.PUBLICATION_RECOVERED) requireNotNull(intent.publicationRecovery)
        intent.publicationRecovery?.let { recovery ->
            check(intent.phase in listOf(Phase.PUBLICATION_RECOVERED, Phase.PUBLICATION_COMPLETED))
            version(recovery); check(recovery == intent.publicationStage) { "Recovery does not reference the recorded publication inode" }
            check(intent.publication == null || (intent.publication.published && !intent.publication.durable)) {
                "Recovery must preserve a missing or non-durable moved native outcome"
            }
        }
        if (intent.phase == Phase.PUBLICATION_COMPLETED) check(hasDurablePublication(intent)) { "Completion requires durable publication" }
        intent.publicationConflict?.let { conflict ->
            check(intent.kind != OperationKind.CREATE || conflict.role != ConflictRole.RETIRED) {
                "Creation cannot have a retired-file conflict"
            }
            check(intent.phase in RECORDED_PUBLICATION_PHASES && hasDurablePublication(intent))
            check((conflict.kind == AnchoredWorkspaceFiles.Kind.FILE) == (conflict.observed != null))
            conflict.observed?.let {
                text(it.identity, 256)
                check(it.size in 0..maxBytes && SHA.matches(it.sha256) && it.mode in 0..4095)
            }
            val expected = when (conflict.role) {
                ConflictRole.RETIRED -> intent.before
                ConflictRole.STAGE -> null
                ConflictRole.CANONICAL -> intent.publicationStage
            }
            check(conflict.kind != (if (expected == null) AnchoredWorkspaceFiles.Kind.ABSENT else AnchoredWorkspaceFiles.Kind.FILE)
                || conflict.observed != expected) { "Conflict receipt does not describe a differing version" }
        }
        intent.publicationConflictCopy?.let { copy ->
            val conflict = requireNotNull(intent.publicationConflict)
            check(conflict.kind == AnchoredWorkspaceFiles.Kind.FILE)
            val observed = requireNotNull(conflict.observed)
            version(copy)
            check(copy.identity !in listOf(observed.identity, intent.before?.identity, intent.snapshot?.identity,
                intent.proposalCopy?.identity, intent.publicationStage?.identity)) { "Conflict copy must be an independent inode" }
            check(copy.copy(identity = observed.identity) == observed) { "Conflict copy does not match its recorded observation" }
        }
    }

    private fun version(value: CanonicalFileSnapshot.Version) {
        text(value.identity, 256); content(Content(value.size, value.sha256, value.mode))
    }
    private fun content(value: Content) {
        require(value.size in 0..maxBytes && SHA.matches(value.sha256) && value.mode in listOf(384, 420, 493))
    }
    private fun validatePath(path: String) {
        text(path, 4096)
        require(!path.startsWith('/') && '\\' !in path && path.split('/').let { parts ->
            parts.size <= 64 && parts.all { it.isNotEmpty() && it != "." && it != ".." }
        }) { "Expected a confined canonical file path" }
    }
    private fun parentPaths(path: String): List<String> = listOf("") +
        path.split('/').dropLast(1).runningReduce { prefix, part -> "$prefix/$part" }
    private fun requireParents(anchor: AnchoredWorkspaceFiles, parents: List<Parent>) {
        parents.forEach { check(anchor.directoryKey(it.path) == it.key) { "Canonical parent binding changed" } }
    }

    private fun <T> pins(action: (AnchoredWorkspaceFiles, AnchoredWorkspaceFiles, () -> Unit) -> T): T =
        AnchoredWorkspaceFiles(root).use { anchor ->
            authorityPins { store, verifyAuthority ->
                val verify = {
                    verifyAuthority()
                    check(root.canonicalPath == enrollment.rootPath && anchor.directoryKey() == enrollment.rootKey) {
                        "Canonical workspace binding changed; all files were preserved"
                    }
                }
                verify()
                action(anchor, store, verify).also { verify() }
            }
        }

    private fun <T> authorityPins(action: (AnchoredWorkspaceFiles, () -> Unit) -> T): T =
        AnchoredWorkspaceFiles(requireNotNull(authority.parentFile)).use { outer ->
            outer.openDirectory(authority.name).use { owner ->
                owner.openDirectory("custody").use { store ->
                    owner.openRead("state.sqlite").use { pinned ->
                        val verify = {
                            check(authority.canonicalPath == enrollment.authorityPath)
                            check(outer.directoryKey() == enrollment.authorityParentKey)
                            check(outer.directoryKey(authority.name) == enrollment.authorityKey && owner.directoryKey() == enrollment.authorityKey)
                            check(owner.directoryKey("custody") == enrollment.custodyKey && store.directoryKey() == enrollment.custodyKey)
                            fun requireDatabase(file: AnchoredWorkspaceFiles.OpenedFile) {
                                val observed = file.observe().attributes
                                check(observed.fileKey().toString() == enrollment.databaseKey && observed.size() > 0L) {
                                    "Authority database is missing, empty, or replaced"
                                }
                            }
                            requireDatabase(pinned)
                            owner.openRead("state.sqlite").use(::requireDatabase)
                            listOf("-journal", "-wal", "-shm").forEach { suffix ->
                                check(owner.kind("state.sqlite$suffix") in listOf(AnchoredWorkspaceFiles.Kind.ABSENT,
                                    AnchoredWorkspaceFiles.Kind.FILE)) { "Unsafe authority sidecar" }
                            }
                        }
                        verify()
                        action(store, verify).also { verify() }
                    }
                }
            }
        }

    private fun <T> access(action: (SQLiteDatabase, AnchoredWorkspaceFiles, AnchoredWorkspaceFiles) -> T): T =
        pins { anchor, store, verify -> accessDatabase(verify) { db -> action(db, anchor, store) } }

    private fun <T> accessDatabase(verify: () -> Unit, readOnly: Boolean = false, action: (SQLiteDatabase) -> T): T =
        openExisting(readOnly).use { db ->
            verify()
            check(db.version in 1..4) { "Unsupported authority schema" }
            if (!readOnly) databaseSizeLimitBytes?.let { db.setMaximumSize(it) }
            db.beginTransaction()
            var failure: Throwable? = null
            try {
                action(db).also { verify(); db.setTransactionSuccessful() }
            } catch (error: Throwable) { failure = error; throw error }
            finally {
                try { db.endTransaction() } catch (end: Throwable) {
                    if (failure == null) { failure = end; throw end }
                    if (failure !== end) failure.addSuppressed(end)
                } finally {
                    try { verify() } catch (binding: Throwable) {
                        if (failure == null) throw binding
                        if (failure !== binding) failure.addSuppressed(binding)
                    }
                }
            }
        }

    private fun openExisting(readOnly: Boolean = false): SQLiteDatabase {
        requireRollbackFormat()
        val parameters = SQLiteDatabase.OpenParams.Builder()
            .setOpenFlags((if (readOnly) SQLiteDatabase.OPEN_READONLY else SQLiteDatabase.OPEN_READWRITE) or
                SQLiteDatabase.NO_LOCALIZED_COLLATORS)
            .apply { if (!readOnly) { setJournalMode("DELETE"); setSynchronousMode("EXTRA") } }
            .setErrorHandler(DatabaseErrorHandler {
                throw SQLiteDatabaseCorruptException("Authority is corrupt; existing bytes were preserved")
            }).build()
        val db = SQLiteDatabase.openDatabase(database, parameters)
        try {
            check(db.isReadOnly == readOnly) { "Authority database access mode was not honored" }
            db.rawQuery("PRAGMA journal_mode", null).use { check(it.moveToFirst() && it.getString(0).equals("delete", true)) }
            if (!readOnly) check(scalar(db, "PRAGMA synchronous") == 3L)
            return db
        } catch (failure: Throwable) { db.close(); throw failure }
    }

    // Refuse unresolved sidecars before SQLite can replay, truncate, or remove existing evidence.
    private fun requireRollbackFormat() {
        AnchoredWorkspaceFiles(authority).use { owner ->
            check(owner.directoryKey() == enrollment.authorityKey) { "Authority identity changed before database open" }
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                check(owner.kind(database.name + suffix) == AnchoredWorkspaceFiles.Kind.ABSENT) {
                    "Pending or unsupported authority sidecars; existing evidence requires review"
                }
            }
            owner.openRead(database.name).use { input ->
                val before = input.observe()
                check(before.attributes.fileKey().toString() == enrollment.databaseKey && before.attributes.size() >= 100L) {
                    "Authority database identity or header is unavailable"
                }
                val header = ByteArray(20)
                var offset = 0
                while (offset < header.size) {
                    val count = input.read(header, offset, header.size - offset)
                    check(count > 0) { "Authority database header is incomplete" }
                    offset += count
                }
                check(SQLITE_HEADER_MAGIC.indices.all { header[it] == SQLITE_HEADER_MAGIC[it] } &&
                    header[18] == 1.toByte() && header[19] == 1.toByte()) {
                    "Unsupported authority database format; existing evidence was preserved"
                }
                val after = input.observe()
                check(before.attributes.fileKey() == after.attributes.fileKey() &&
                    before.attributes.size() == after.attributes.size() && before.changed == after.changed &&
                    before.attributes.lastModifiedTime() == after.attributes.lastModifiedTime()) {
                    "Authority database changed during header inspection"
                }
                owner.openRead(database.name).use {
                    check(it.observe().attributes.fileKey().toString() == enrollment.databaseKey) {
                        "Authority database was replaced during header inspection"
                    }
                }
            }
        }
    }

    private fun encode(value: Intent): String {
        val fields = listOf(
            value.id, value.path,
            JsonArray(value.parents.map { array(listOf(it.path, it.key)) }), value.before?.let(::versionJson),
            array(listOf(value.proposal.size, value.proposal.sha256, value.proposal.mode)), value.phase.name,
            value.snapshot?.let(::versionJson), value.proposalCopy?.let(::versionJson),
            value.publicationStage?.let(::versionJson),
            value.retirement?.let { array(listOf(if (it.published) 1 else 0, it.error)) },
            value.retainedSnapshotIdentity,
            value.publication?.let { array(listOf(if (it.published) 1 else 0, it.error)) },
            value.publicationConflict?.let { array(listOf(it.role.name, it.kind.name, it.observed?.let(::versionJson))) },
            value.publicationRecovery?.let(::versionJson), value.publicationConflictCopy?.let(::versionJson),
            value.retirementRecovery?.let(::versionJson),
            value.kind.takeUnless { it == OperationKind.REPLACE }?.name,
        )
        val length = maxOf(7, fields.indexOfLast { it != null } + 1)
        return array(fields.take(length)).toString().also { check(it.length <= RECORD_LIMIT) }
    }
    private fun versionJson(value: CanonicalFileSnapshot.Version) = array(listOf(value.identity, value.size, value.sha256, value.mode))
    private fun decode(record: String): Intent {
        fun fields(element: JsonElement, size: Int): JsonArray =
            checkNotNull(element as? JsonArray) { "Operation field must be an array" }.also { check(it.size == size) }
        fun string(element: JsonElement): String =
            checkNotNull(element as? JsonPrimitive) { "Operation field must be text" }.also { check(it.isString) }.content
        fun number(element: JsonElement): Long =
            checkNotNull(element as? JsonPrimitive) { "Operation field must be numeric" }.also { check(!it.isString) }.content.toLong()
        fun mode(element: JsonElement): Int = number(element).also { check(it == 384L || it == 420L || it == 493L) }.toInt()
        fun version(element: JsonElement): CanonicalFileSnapshot.Version = fields(element, 4).let {
            CanonicalFileSnapshot.Version(string(it[0]), number(it[1]), string(it[2]), mode(it[3]))
        }
        val recordFields = checkNotNull(Json.parseToJsonElement(record) as? JsonArray) {
            "Operation record must be an array"
        }.also { check(it.size in 7..17) }
        val parents = checkNotNull(recordFields[2] as? JsonArray) { "Operation parents must be an array" }
        check(parents.size in 1..64)
        val proposal = fields(recordFields[4], 3)
        return Intent(string(recordFields[0]), string(recordFields[1]), parents.map { fields(it, 2).let { pair ->
            Parent(string(pair[0]), string(pair[1]))
        } }, recordFields[3].takeUnless { it == JsonNull }?.let(::version), Content(number(proposal[0]), string(proposal[1]), mode(proposal[2])),
            Phase.valueOf(string(recordFields[5])), recordFields[6].takeUnless { it == JsonNull }?.let(::version),
            recordFields.getOrNull(7)?.takeUnless { it == JsonNull }?.let(::version),
            recordFields.getOrNull(8)?.takeUnless { it == JsonNull }?.let(::version),
            recordFields.getOrNull(9)?.takeUnless { it == JsonNull }?.let { fields(it, 2).let { receipt ->
                val moved = number(receipt[0]).also { check(it in 0L..1L) }
                val error = number(receipt[1]).also { check(it in 0L..4095L) }
                AnchoredWorkspaceFiles.MoveReceipt(moved == 1L, error.toInt())
            } }, recordFields.getOrNull(10)?.takeUnless { it == JsonNull }?.let(::string),
            recordFields.getOrNull(11)?.takeUnless { it == JsonNull }?.let { fields(it, 2).let { receipt ->
                val moved = number(receipt[0]).also { check(it in 0L..1L) }
                val error = number(receipt[1]).also { check(it in 0L..4095L) }
                AnchoredWorkspaceFiles.MoveReceipt(moved == 1L, error.toInt())
            } }, recordFields.getOrNull(12)?.takeUnless { it == JsonNull }?.let { fields(it, 3).let { conflict ->
                PublicationConflict(ConflictRole.valueOf(string(conflict[0])), AnchoredWorkspaceFiles.Kind.valueOf(string(conflict[1])),
                    conflict[2].takeUnless { it == JsonNull }?.let { value -> fields(value, 4).let { observed ->
                        CanonicalFileSnapshot.Version(string(observed[0]), number(observed[1]), string(observed[2]),
                            number(observed[3]).also { mode -> check(mode in 0L..4095L) }.toInt())
                    } })
            } }, recordFields.getOrNull(13)?.takeUnless { it == JsonNull }?.let(::version),
            recordFields.getOrNull(14)?.takeUnless { it == JsonNull }?.let(::version),
            recordFields.getOrNull(15)?.takeUnless { it == JsonNull }?.let(::version),
            recordFields.getOrNull(16)?.let { OperationKind.valueOf(string(it)) } ?: OperationKind.REPLACE)
    }

    companion object {
        private val RECORDED_PUBLICATION_PHASES = setOf(Phase.PUBLICATION_RECORDED, Phase.PUBLICATION_COMPLETED, Phase.PUBLICATION_RECOVERED)
        private val PUBLICATION_PHASES = setOf(Phase.PUBLICATION_PREPARED) + RECORDED_PUBLICATION_PHASES
        private val CREATION_PHASES = setOf(Phase.PREPARED, Phase.PROPOSAL_RECORDED, Phase.PUBLICATION_STAGED) + PUBLICATION_PHASES
        private val RETAINED_PHASES = setOf(Phase.RETENTION_RECORDED) + PUBLICATION_PHASES
        private val PRE_PUBLICATION_RETIRED_PHASES = setOf(Phase.RETIREMENT_RECORDED, Phase.RETIREMENT_RECOVERED, Phase.RETENTION_RECORDED)
        private val RETIRED_PHASES = PRE_PUBLICATION_RETIRED_PHASES + PUBLICATION_PHASES
        private val STAGED_PHASES = setOf(Phase.PUBLICATION_STAGED, Phase.RETIREMENT_PREPARED) + RETIRED_PHASES
        private const val RECORD_LIMIT = 524288
        private val SHA = Regex("[0-9a-f]{64}")
        private val SQLITE_HEADER_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        val SCHEMA_SQL: List<String> = listOf(
            "CREATE TABLE meta(id INTEGER PRIMARY KEY CHECK(id=1),binding TEXT NOT NULL,generation TEXT NOT NULL,expected_operation TEXT,record_digest TEXT)",
            "CREATE TABLE operation(slot INTEGER PRIMARY KEY CHECK(slot=1),record_json TEXT NOT NULL)")
        fun enrollmentHash(value: Enrollment): String = hash(array(listOf(value.projectId, value.rootPath, value.rootKey,
            value.authorityPath, value.authorityKey, value.databaseKey, value.custodyKey, value.generation,
            value.authorityParentKey)).toString())
        private fun array(values: List<Any?>): JsonArray = JsonArray(values.map {
            when (it) { null -> JsonNull; is JsonElement -> it; is Number -> JsonPrimitive(it); else -> JsonPrimitive(it.toString()) }
        })
        private fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun uuid(value: String) { require(value.length == 36 && UUID.fromString(value).toString() == value) }
        private fun text(value: String, limit: Int) {
            require(value.length in 1..limit && value.none { it.code < 32 || it.code == 127 })
        }
        private fun string(row: Cursor, column: Int, limit: Int): String {
            check(row.getType(column) == Cursor.FIELD_TYPE_STRING)
            return row.getString(column).also { text(it, limit) }
        }
        private fun integer(row: Cursor, column: Int): Long {
            check(row.getType(column) == Cursor.FIELD_TYPE_INTEGER)
            return row.getLong(column)
        }
        private fun scalar(db: SQLiteDatabase, query: String): Long = db.rawQuery(query, null).use {
            check(it.moveToFirst()); integer(it, 0).also { value -> check(value >= 0 && !it.moveToNext()) }
        }
    }
}
