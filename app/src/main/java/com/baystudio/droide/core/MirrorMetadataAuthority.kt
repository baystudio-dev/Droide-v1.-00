package com.baystudio.droide.core

import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

internal class MirrorMetadataAuthority(
    val directory: File,
    private val source: File,
    private val working: File,
    private val durability: ProjectCopyJournal.Durability = ProjectCopyJournal.Durability.platform,
    private val databaseSizeLimitBytes: Long? = null,
    private val processCheckpoint: (String) -> Unit = {},
    private val processBootIdentity: () -> WorkspaceProcessBootIdentity.Identity? = { LocalExecutionSubstrate.processBootIdentity(working) },
) {
    enum class BootstrapPhase { PREPARED, LINKED }
    data class State(
        val revision: Long,
        val baseline: Map<String, WorkspaceSyncManifest.Entry>,
        val conflicts: Set<String>,
        val bootstrapPhase: BootstrapPhase = BootstrapPhase.LINKED,
        val directoryHistory: Set<String> = emptySet(),
    )
    enum class Phase { PREPARED, RETAINED, APPLIED, CONFLICT }
    enum class NodeType { ABSENT, FILE, DIRECTORY }
    data class Node(val type: NodeType, val sha256: String, val mode: Int) {
        fun signature(): String = MessageDigest.getInstance("SHA-256").digest("${type.name}:$sha256:$mode".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        companion object {
            val absent = Node(NodeType.ABSENT, "", 0)
            fun file(entry: WorkspaceSyncManifest.Entry) = Node(NodeType.FILE, entry.sha256, requireNotNull(entry.mode))
            fun directory(sha256: String) = Node(NodeType.DIRECTORY, sha256, 0)
        }
    }
    data class Intent(val id: String, val path: String, val kind: String, val retainedName: String, val phase: Phase,
                      val before: Node, val proposed: Node, val acknowledgedOriginal: String? = null) {
        val stableName: String get() = "$retainedName.stable"
    }
    data class Proposal(val path: String, val kind: String, val before: Node, val proposed: Node)
    data class Transition(val id: String, val expected: Phase, val next: Phase)
    private val sourceIdentity = WorkspaceDirectoryIdentity(source)
    private val workingIdentity = WorkspaceDirectoryIdentity(working)
    private val parent = checkNotNull(directory.parentFile)
    private val parentIdentity = WorkspaceDirectoryIdentity(parent)
    private var authorityIdentity: WorkspaceDirectoryIdentity? =
        if (Files.exists(directory.toPath(), NOFOLLOW_LINKS)) WorkspaceDirectoryIdentity(directory) else null
    private val binding = digest(listOf(source.canonicalPath, key(source), key(working)))
    private val hashPattern = Regex("[0-9a-f]{64}")

    private val accessLock = accessLocks.computeIfAbsent(directory.canonicalPath) { Any() }

    private fun createNew(
        baseline: Map<String, WorkspaceSyncManifest.Entry>,
        conflicts: Set<String>,
        bootstrap: BootstrapPhase = BootstrapPhase.LINKED,
        directoryHistory: Set<String> = emptySet(),
        typedHistory: Boolean = false,
    ) {
        validateState(baseline, conflicts, directoryHistory)
        requireCurrentParents()
        require(!PathSecurity.contains(source, directory) && !PathSecurity.contains(working, directory)) {
            "Synchronization authority must be outside both project and CLI working copy"
        }
        check(!Files.exists(directory.toPath(), NOFOLLOW_LINKS)) { "Synchronization authority already exists; it was preserved" }
        val stage = Files.createTempDirectory(parent.toPath(), ".mirror-authority-").toFile()
        val retained = File(stage, "retained").apply { Files.createDirectory(toPath()) }
        val prepared = File(stage, "prepared").apply { Files.createDirectory(toPath()) }
        durability.forceDirectory(retained)
        durability.forceDirectory(prepared)
        val marker = "DROIDE-MIRROR-AUTHORITY-2\n$binding\n${key(stage)}\n${UUID.randomUUID()}\n${key(retained)}\n${key(prepared)}\n"
        val dbFile = File(stage, "state.sqlite")
        openDatabase(dbFile, create = true).use { db ->
            transaction(db) {
                db.execSQL("CREATE TABLE meta (id INTEGER PRIMARY KEY CHECK(id=1), binding TEXT NOT NULL, authority_key TEXT NOT NULL, revision INTEGER NOT NULL, baseline_digest TEXT NOT NULL, conflicts_digest TEXT NOT NULL, operations_count INTEGER NOT NULL, bootstrap_phase TEXT NOT NULL, bootstrap_digest TEXT NOT NULL)")
                db.execSQL("CREATE TABLE baseline (path TEXT PRIMARY KEY, sha256 TEXT NOT NULL, mode INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE conflicts (path TEXT PRIMARY KEY)")
                db.execSQL("CREATE TABLE operations (id TEXT PRIMARY KEY, path TEXT NOT NULL, kind TEXT NOT NULL, retained_name TEXT NOT NULL, phase TEXT NOT NULL, old_type TEXT NOT NULL, old_sha TEXT NOT NULL, old_mode INTEGER NOT NULL, new_type TEXT NOT NULL, new_sha TEXT NOT NULL, new_mode INTEGER NOT NULL, record_digest TEXT NOT NULL)")
                db.execSQL("CREATE INDEX operation_phase ON operations(phase,id)")
                writeRows(db, emptyMap(), baseline, emptySet(), conflicts)
                db.execSQL("INSERT INTO meta VALUES (1,?,?,?,?,?,?,?,?)", arrayOf<Any?>(binding, key(stage), 0L, baselineDigest(baseline), digest(conflicts.sorted()), 0L,
                    bootstrap.name, bootstrapDigest(bootstrap)))
                db.version = 1
                if (typedHistory) {
                    enableDirectoryHistory(db)
                    writeDirectoryRows(db, emptySet(), directoryHistory)
                    db.execSQL("UPDATE meta SET directory_digest=? WHERE id=1", arrayOf(digest(directoryHistory.sorted())))
                }
            }
        }
        FileChannel.open(File(stage, "identity").toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
            val bytes = ByteBuffer.wrap(marker.toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            durability.forceFile(channel)
        }
        FileChannel.open(dbFile.toPath(), WRITE, NOFOLLOW_LINKS).use(durability::forceFile)
        durability.forceDirectory(stage)
        requireCurrentParents()
        AnchoredWorkspaceFiles(parent).use { anchor -> anchor.moveNoReplace(stage.name, anchor, directory.name); anchor.forceDirectory() }
        authorityIdentity = WorkspaceDirectoryIdentity(directory)
        load()
    }

    fun load(): State = access { db -> transaction(db) { readState(db) } }

    fun processCustody(runtimeId: String = WorkspaceProcessCustody.runtimeId): WorkspaceProcessCustody {
        require(UUID.fromString(runtimeId).toString() == runtimeId)
        return object : WorkspaceProcessCustody() {
            override fun acquire(root: File): WorkspaceProcessCustody.Receipt {
                require(root.canonicalFile.toPath().startsWith(working.canonicalFile.toPath())) {
                    "Process custody belongs to a different CLI working copy"
                }
                revokeUnenteredProcessReservations(runtimeId)
                val boot = processBootIdentity()
                val rebootObserved = observeProcessRecovery(boot, runtimeId)
                val owner = MirrorProcessCustodyLedger.Owner(UUID.randomUUID().toString(), runtimeId, boot)
                access { db -> transaction(db) {
                    val state = readState(db)
                    check(state.bootstrapPhase == BootstrapPhase.LINKED) { "CLI process custody requires a linked authority" }
                    db.rawQuery("SELECT 1 FROM operations WHERE phase IN ('PREPARED','RETAINED') LIMIT 1", null).use {
                        check(!it.moveToFirst()) { "Interrupted mirror publication needs recovery before process launch" }
                    }
                    if (db.version < PROCESS_CUSTODY_SCHEMA) {
                        enableAcknowledgements(db)
                        MirrorProcessCustodyLedger.enable(db, binding)
                        db.version = PROCESS_CUSTODY_SCHEMA
                    }
                    MirrorProcessCustodyLedger.enableBootIdentity(db, binding)
                    val owners = MirrorProcessCustodyLedger.read(db, binding)
                    val receipts = MirrorProcessCleanupReceipts(directory, binding, checkpoint = processCheckpoint)
                    // Only the committed ledger snapshot can authorize removal of redundant private proof; never use
                    // this transaction's removals.
                    receipts.retireCompleted(owners)
                    val cancelled = committedCancelledProcessOwners(db)
                    val remaining = MirrorProcessRebootBarrier(directory, binding).remaining(
                        receipts.remaining(MirrorProcessCustodyLedger.withoutPreviousBoot(owners, boot, runtimeId)
                            .filterNot { it.id in cancelled }), boot, runtimeId)
                    if (remaining.any { it.runtimeId != runtimeId }) throw processRecoveryRequired(rebootObserved)
                    if (db.version < MirrorProcessCustodyLedger.LAUNCH_SCHEMA && remaining.isNotEmpty()) {
                        throw WorkspaceProcessRecoveryRequiredException("CLI session recovery is required. Legacy process cleanup must finish before another launch; existing work was preserved.")
                    }
                    receipts.requireCapacity(remaining)
                    MirrorProcessCustodyLedger.recordBootReconciliation(db, binding, owners, remaining)
                    // Valid legacy cleanup/reboot proof must be reconciled before demanding an empty ledger for the
                    // launch-state migration.
                    MirrorProcessCustodyLedger.enableLaunchState(db, binding)
                    MirrorProcessCustodyLedger.append(db, binding, remaining, owner)
                    processCheckpoint("writer-admission-before-commit")
                } }
                processCheckpoint("writer-admission-after-commit")
                return object : WorkspaceProcessCustody.Receipt() {
                    override fun enterFactory() {
                        access { db -> transaction(db) {
                            readState(db)
                            MirrorProcessCustodyLedger.enterFactory(db, binding, owner)
                            processCheckpoint("writer-factory-entry-before-commit")
                        } }
                        processCheckpoint("writer-factory-entry-after-commit")
                    }
                    override fun confirmCleanupOrAbort() {
                        access { db -> transaction(db) {
                            readState(db)
                            check(db.version in PROCESS_CUSTODY_SCHEMA..MirrorProcessCustodyLedger.LAUNCH_SCHEMA) { "Process custody disappeared before cleanup confirmation" }
                            val owners = MirrorProcessCustodyLedger.read(db, binding)
                            // The local one-shot capability was consumed before this IO. Exact cleanup or a locally aborted
                            // invocation can never be followed by another factory on that capability.
                            if (owners.any { it.id == owner.id }) {
                                MirrorProcessCleanupReceipts(directory, binding, checkpoint = processCheckpoint).record(owner, owners)
                            }
                            MirrorProcessCustodyLedger.confirm(db, binding, owners, owner)
                            processCheckpoint("writer-completion-before-commit")
                        } }
                        processCheckpoint("writer-completion-after-commit")
                    }
                }
            }
        }
    }

    /** Revoke permission, rather than infer that an old runtime died. This must commit independently
     * even if an entered/legacy owner still blocks recovery. */
    private fun revokeUnenteredProcessReservations(runtimeId: String) {
        val changed = access { db -> transaction(db) {
            readState(db)
            if (db.version < MirrorProcessCustodyLedger.LAUNCH_SCHEMA) return@transaction false
            MirrorProcessCustodyLedger.cancelUnentered(db, binding, runtimeId).also {
                if (it) processCheckpoint("writer-reservation-cancel-before-commit")
            }
        } }
        if (changed) processCheckpoint("writer-reservation-cancel-after-commit")
    }

    /** Called only in a fresh transaction, never the one that changed PREPARED. */

    private fun committedCancelledProcessOwners(db: SQLiteDatabase): Set<String> =
        MirrorProcessCustodyLedger.launchStates(db).filterValues {
            it == MirrorProcessCustodyLedger.LaunchState.CANCELLED_NO_START
        }.keys

    private fun observeProcessRecovery(boot: WorkspaceProcessBootIdentity.Identity?, runtimeId: String): Boolean = access { db -> transaction(db) {
        readState(db)
        if (db.version < PROCESS_CUSTODY_SCHEMA) return@transaction false
        MirrorProcessRebootBarrier(directory, binding, processCheckpoint).observe(MirrorProcessCustodyLedger.read(db, binding), boot, runtimeId).also {
            if (it) processCheckpoint("writer-reboot-observation-after-force")
        }
    } }

    private fun processRecoveryRequired(rebootObserved: Boolean) = WorkspaceProcessRecoveryRequiredException(
        if (rebootObserved) "CLI session recovery is required: cleanup from an earlier runtime is unconfirmed. Your files and retained changes are preserved. Restart the Android device once, then reopen Droide and retry; restarting only the app is not enough."
        else "CLI session recovery is required: cleanup from an earlier runtime is unconfirmed. Your files and retained changes are preserved. This workspace has no comparable trusted device-restart proof; retrying the prompt or restarting only the app cannot clear it. Inspect CLI workspace recovery before starting another session."
    )

    fun pendingProcessOwners(): List<MirrorProcessCustodyLedger.Owner> = access { db -> transaction(db) {
        readState(db)
        if (db.version < PROCESS_CUSTODY_SCHEMA) emptyList() else MirrorProcessCustodyLedger.read(db, binding)
    } }

    fun requireNoPendingProcessOwners() {
        revokeUnenteredProcessReservations(WorkspaceProcessCustody.runtimeId)
        val boot = processBootIdentity()
        val rebootObserved = observeProcessRecovery(boot, WorkspaceProcessCustody.runtimeId)
        val changed = access { db -> transaction(db) {
            readState(db)
            if (db.version < PROCESS_CUSTODY_SCHEMA) return@transaction false
            val owners = MirrorProcessCustodyLedger.read(db, binding)
            val cancelled = committedCancelledProcessOwners(db)
            val remaining = MirrorProcessRebootBarrier(directory, binding).remaining(MirrorProcessCleanupReceipts(directory, binding, checkpoint = processCheckpoint).remaining(
                MirrorProcessCustodyLedger.withoutPreviousBoot(owners, boot, WorkspaceProcessCustody.runtimeId)
                    .filterNot { it.id in cancelled }), boot, WorkspaceProcessCustody.runtimeId)
            if (remaining.isNotEmpty()) {
                if (remaining.any { it.runtimeId != WorkspaceProcessCustody.runtimeId }) throw processRecoveryRequired(rebootObserved)
                throw WorkspaceProcessRecoveryRequiredException("CLI session recovery is required: ${remaining.size} process cleanup receipt(s) remain unconfirmed. Stop the active CLI session and retry cleanup. Recovery metadata and retained files were preserved.")
            }
            MirrorProcessCustodyLedger.recordBootReconciliation(db, binding, owners, remaining)
            (owners != remaining).also { if (it) processCheckpoint("writer-boot-reconciliation-before-commit") }
        } }
        if (changed) processCheckpoint("writer-boot-reconciliation-after-commit")
    }

    fun completeExternalLink(expectedRevision: Long): State = access { db -> transaction(db) {
        val state = readState(db)
        check(state.revision == expectedRevision) { "Bootstrap state changed; no workspace file was mutated" }
        if (state.bootstrapPhase == BootstrapPhase.LINKED) return@transaction state
        db.rawQuery("SELECT operations_count FROM meta WHERE id=1", null).use { row ->
            check(row.moveToFirst() && row.getLong(0) == 0L) { "Filesystem intents exist before the authority link was established; recovery is required" }
        }
        check(state.revision in 0 until Long.MAX_VALUE) { "Synchronization revision capacity exhausted" }
        db.execSQL("UPDATE meta SET bootstrap_phase=?,bootstrap_digest=?,revision=? WHERE id=1",
            arrayOf<Any?>(BootstrapPhase.LINKED.name, bootstrapDigest(BootstrapPhase.LINKED), state.revision + 1))
        state.copy(revision = state.revision + 1, bootstrapPhase = BootstrapPhase.LINKED)
    } }

    fun commit(expectedRevision: Long, baseline: Map<String, WorkspaceSyncManifest.Entry>, conflicts: Set<String>): State {
        return commitMetadata(expectedRevision, baseline, conflicts, directoryHistory = null)
    }

    fun commitWithHistory(
        expectedRevision: Long,
        baseline: Map<String, WorkspaceSyncManifest.Entry>,
        conflicts: Set<String>,
        directoryHistory: Set<String>,
    ): State = commitMetadata(expectedRevision, baseline, conflicts, directoryHistory)

    private fun commitMetadata(
        expectedRevision: Long,
        baseline: Map<String, WorkspaceSyncManifest.Entry>,
        conflicts: Set<String>,
        directoryHistory: Set<String>?,
    ): State {
        return access { db -> transaction(db) {
            val before = readState(db)
            check(before.revision == expectedRevision) { "Synchronization metadata changed concurrently; working-copy data was preserved" }
            check(before.bootstrapPhase == BootstrapPhase.LINKED) { "Synchronization authority is not linked; working-copy data was preserved" }
            db.rawQuery("SELECT id FROM operations WHERE phase IN ('PREPARED','RETAINED') LIMIT 1", null).use { row ->
                check(!row.moveToFirst()) { "Pending filesystem intent must be recovered before changing its baseline" }
            }
            commitState(db, before, baseline, conflicts, directoryHistory)
        } }
    }

    fun prepare(path: String, kind: String, before: Node, proposed: Node): Intent {
        return prepareBatch(load().revision, listOf(Proposal(path, kind, before, proposed))).single()
    }

    fun prepareBatch(expectedRevision: Long, proposals: List<Proposal>): List<Intent> {
        require(proposals.size <= 20_000 && proposals.map { it.path }.toSet().size == proposals.size) { "Invalid recovery batch" }
        var bytes = 0L
        val records = proposals.map { change ->
            bytes += change.path.toByteArray(Charsets.UTF_8).size + 256L
            require(bytes <= 16L * 1024 * 1024) { "Recovery batch exceeds its byte limit" }
            val id = UUID.randomUUID().toString()
            Intent(id, change.path, change.kind, id, Phase.PREPARED, change.before, change.proposed).also(::validateIntent)
        }
        return access { db -> transaction(db) {
            val state = readState(db)
            check(state.revision == expectedRevision && state.bootstrapPhase == BootstrapPhase.LINKED) { "Synchronization state changed or is not linked; no working-copy file was mutated" }
            if (records.isEmpty()) return@transaction emptyList()
            db.rawQuery("SELECT operations_count FROM meta WHERE id=1", null).use { row ->
                check(row.moveToFirst() && row.getLong(0) <= 100_000L - records.size) {
                    "Recovery history capacity reached; archive retained versions explicitly before synchronizing again"
                }
            }
            db.rawQuery("SELECT id FROM operations WHERE phase IN ('PREPARED','RETAINED') LIMIT 1", null).use { row ->
                check(!row.moveToFirst()) { "Earlier filesystem operations need recovery; source and retained CLI files were preserved" }
            }
            for (record in records) db.execSQL("INSERT INTO operations (id,path,kind,retained_name,phase,old_type,old_sha,old_mode,new_type,new_sha,new_mode,record_digest) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(
                record.id, record.path, record.kind, record.retainedName, record.phase.name,
                record.before.type.name, record.before.sha256, record.before.mode,
                record.proposed.type.name, record.proposed.sha256, record.proposed.mode, intentDigest(record)))
            db.execSQL("UPDATE meta SET operations_count=operations_count+? WHERE id=1", arrayOf<Any?>(records.size))
            records
        } }
    }

    fun transition(id: String, expected: Phase, next: Phase) {
        transitionBatch(listOf(Transition(id, expected, next)))
    }

    fun transitionBatch(changes: List<Transition>) {
        validateTransitions(changes, allowApplied = false)
        if (changes.isEmpty()) return
        access { db -> transaction(db) { readState(db); transitionRows(db, changes) } }
    }

    fun finishBatch(expectedRevision: Long, changes: List<Transition>,
                    baseline: Map<String, WorkspaceSyncManifest.Entry>, conflicts: Set<String>): State {
        return finishMetadata(expectedRevision, changes, baseline, conflicts, directoryHistory = null)
    }

    fun finishBatchWithHistory(
        expectedRevision: Long,
        changes: List<Transition>,
        baseline: Map<String, WorkspaceSyncManifest.Entry>,
        conflicts: Set<String>,
        directoryHistory: Set<String>,
    ): State = finishMetadata(expectedRevision, changes, baseline, conflicts, directoryHistory)

    private fun finishMetadata(
        expectedRevision: Long,
        changes: List<Transition>,
        baseline: Map<String, WorkspaceSyncManifest.Entry>,
        conflicts: Set<String>,
        directoryHistory: Set<String>?,
    ): State {
        validateTransitions(changes, allowApplied = true)
        return access { db -> transaction(db) {
            val before = readState(db)
            check(before.revision == expectedRevision) { "Synchronization revision changed before completion; retained bytes were preserved" }
            check(before.bootstrapPhase == BootstrapPhase.LINKED) { "Synchronization authority is not linked; recovery is required" }
            val directories = directoryHistory ?: before.directoryHistory
            for (change in changes.filter { it.next == Phase.APPLIED }) {
                val record = db.rawQuery("SELECT * FROM operations WHERE id=?", arrayOf(change.id)).use { row ->
                    check(row.moveToFirst()) { "Completion intent is missing; ancestry was not advanced" }
                    readIntent(row)
                }
                val expected = record.proposed.takeIf { it.type == NodeType.FILE }?.let { WorkspaceSyncManifest.Entry(it.sha256, it.mode) }
                check(baseline[record.path] == expected &&
                    (record.proposed.type == NodeType.DIRECTORY || baseline.keys.none { it.startsWith("${record.path}/") })) {
                    "Completed publication and baseline disagree; both remain pending for recovery"
                }
                if (directoryHistory != null || db.version >= TYPED_SCHEMA) {
                    val hasDirectory = record.path in directories
                    check(hasDirectory == (record.proposed.type == NodeType.DIRECTORY) &&
                        (record.proposed.type == NodeType.DIRECTORY || directories.none { it.startsWith("${record.path}/") })) {
                        "Completed publication and directory history disagree; recovery remains pending"
                    }
                }
            }
            transitionRows(db, changes)
            commitState(db, before, baseline, conflicts, directoryHistory)
        } }
    }

    private fun validateTransitions(changes: List<Transition>, allowApplied: Boolean) {
        require(changes.size <= 256) { "Recovery receipt batch exceeds its limit" }
        changes.forEach { change ->
            require(UUID.fromString(change.id).toString() == change.id) { "Invalid recovery identity" }
            require(allowApplied || change.next != Phase.APPLIED) { "Completion requires an atomic baseline transaction" }
            require((change.expected == Phase.PREPARED && change.next in setOf(Phase.RETAINED, Phase.CONFLICT)) ||
                (change.expected == Phase.RETAINED && change.next in setOf(Phase.APPLIED, Phase.CONFLICT))) { "Invalid recovery transition" }
        }
    }

    private fun transitionRows(db: SQLiteDatabase, changes: List<Transition>) {
        for ((id, expected, next) in changes) {
            val record = db.rawQuery("SELECT * FROM operations WHERE id=?", arrayOf(id)).use { row ->
                check(row.moveToFirst()) { "Recovery intent is missing; retained bytes were preserved" }
                readIntent(row)
            }
            check(record.phase == expected) { "Recovery state changed concurrently; retained bytes were preserved" }
            db.execSQL("UPDATE operations SET phase=?,record_digest=? WHERE id=? AND phase=?",
                arrayOf(next.name, intentDigest(record.copy(phase = next)), id, expected.name))
            db.rawQuery("SELECT changes()", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getLong(0) == 1L) { "Recovery state changed concurrently; retained bytes were preserved" }
            }
        }
    }

    fun operations(after: String? = null, limit: Int = 128): List<Intent> {
        return readOperations(after, limit, retainedOnly = false)
    }

    fun recoveryOperations(after: String? = null, limit: Int = 33): List<Intent> {
        return readOperations(after, limit, retainedOnly = true)
    }

    fun operation(id: String): Intent = access { db -> transaction(db) {
        require(UUID.fromString(id).toString() == id) { "Invalid recovery identity" }
        readState(db)
        db.rawQuery("SELECT * FROM operations WHERE id=?", arrayOf(id)).use { cursor ->
            check(cursor.moveToFirst()) { "Retained operation is no longer available; inspect again" }
            readIntent(cursor)
        }
    } }

    /** The reviewed original stays in place; subsequent content changes require a new review. */
    fun acknowledgeOriginal(expectedRevision: Long, expected: Intent, signature: String): State = access { db -> transaction(db) {
        val state = readState(db)
        check(state.revision == expectedRevision && state.bootstrapPhase == BootstrapPhase.LINKED)
        require(hashPattern.matches(signature))
        val actual = db.rawQuery("SELECT * FROM operations WHERE id=?", arrayOf(expected.id)).use { row ->
            check(row.moveToFirst()) { "Retained operation is missing" }; readIntent(row)
        }
        check(actual == expected && actual.phase in setOf(Phase.APPLIED, Phase.CONFLICT)) { "Retained operation changed; inspect again" }
        if (actual.acknowledgedOriginal == signature) return@transaction state
        check(state.revision < Long.MAX_VALUE)
        enableAcknowledgements(db)
        val reviewed = actual.copy(acknowledgedOriginal = signature)
        db.execSQL("UPDATE operations SET ack_original=?,record_digest=? WHERE id=?",
            arrayOf(signature, intentDigest(reviewed), actual.id))
        db.execSQL("UPDATE meta SET revision=? WHERE id=1", arrayOf<Any>(state.revision + 1))
        state.copy(revision = state.revision + 1)
    } }

    private fun enableAcknowledgements(db: SQLiteDatabase) {
        if (db.version >= REVIEW_SCHEMA) return
        check(db.inTransaction()) { "Acknowledgement migration requires an owned transaction" }
        enableDirectoryHistory(db)
        db.execSQL("ALTER TABLE operations ADD COLUMN ack_original TEXT")
        db.version = REVIEW_SCHEMA
    }

    private fun readOperations(after: String?, limit: Int, retainedOnly: Boolean): List<Intent> {
        require(limit in 1..128)
        return access { db -> transaction(db) {
            readState(db)
            val filter = if (retainedOnly) " AND (old_type!='ABSENT' OR phase IN ('PREPARED','RETAINED','CONFLICT'))" else ""
            db.rawQuery("SELECT * FROM operations WHERE id>?$filter ORDER BY id LIMIT ?",
                arrayOf(after.orEmpty(), limit.toString())).use { cursor -> buildList {
                while (cursor.moveToNext()) {
                    add(readIntent(cursor))
                }
            } }
        } }
    }

    fun visitOperations(visitor: (List<Intent>) -> Unit): State = access { db -> transaction(db) {
        val state = readState(db)
        db.rawQuery("SELECT * FROM operations ORDER BY id", null).use { cursor ->
            val page = ArrayList<Intent>(128)
            while (cursor.moveToNext()) {
                page += readIntent(cursor)
                if (page.size == 128) { visitor(page.toList()); page.clear() }
            }
            if (page.isNotEmpty()) visitor(page.toList())
        }
        state
    } }

    fun loadExecutionReadyState(): State {
        val state = visitOperations { page ->
            val pending = page.firstOrNull { it.phase == Phase.PREPARED || it.phase == Phase.RETAINED }
            check(pending == null) {
                "CLI synchronization was interrupted at ${pending?.path}. Working files and prepared/retained versions remain at $directory; resolve recovery before retrying."
            }
        }
        check(state.bootstrapPhase == BootstrapPhase.LINKED) {
            "CLI synchronization authority has not completed bootstrap; all existing data was preserved"
        }
        return state
    }

    /** Captures raw evidence only. Missing cross-runtime exclusion never permits SQLite replay. */
    fun captureSqliteRollbackEvidence(requireCurrent: () -> Unit): MirrorSqliteRollbackRecovery.Review = synchronized(accessLock) {
        requireAuthorityFiles()
        MirrorMetadataProcessLock.access(directory, binding, {
            requireCurrent(); requireCurrentParents(); checkNotNull(authorityIdentity).requireCurrent()
        }, processCheckpoint) { requireHeld ->
            requireAuthorityFiles()
            MirrorSqliteRollbackRecovery.capture(directory, binding, requireHeld, processCheckpoint)
                .also { requireAuthorityFiles(); requireHeld() }
        }
    }

    /** Explicit speculative bytes only. Never opens an SQLite engine or changes live authority. */
    fun buildSqliteForensicCandidate(requireCurrent: () -> Unit): MirrorSqliteForensicCandidate.Result = synchronized(accessLock) {
        requireAuthorityFiles()
        MirrorMetadataProcessLock.access(directory, binding, {
            requireCurrent(); requireCurrentParents(); checkNotNull(authorityIdentity).requireCurrent()
        }, processCheckpoint) { requireHeld ->
            MirrorSqliteRollbackRecovery.withMatchingEvidence(directory, binding, requireHeld, processCheckpoint) { reviews, beforeReturn ->
                MirrorSqliteForensicCandidate.buildCurrent(directory, binding, reviews, requireHeld, processCheckpoint, beforeReturn)
            }.also { requireAuthorityFiles(); requireHeld() }
        }
    }

    private fun <T> access(action: (SQLiteDatabase) -> T): T = synchronized(accessLock) {
        requireAuthorityFiles()
        MirrorMetadataProcessLock.access(directory, binding, {
            requireCurrentParents(); checkNotNull(authorityIdentity).requireCurrent()
        }, processCheckpoint) { requireHeld ->
            val dbFile = requireAuthorityFiles()
            val identity = checkNotNull(authorityIdentity)
            requireRollbackFormat(dbFile, key(directory))
            processCheckpoint("metadata-before-database-open")
            requireHeld()
            openDatabase(dbFile, create = false).use { db ->
                validateSchema(db)
                identity.requireCurrent()
                action(db).also { identity.requireCurrent(); requireCurrentParents(); requireHeld() }
            }.also { requireHeld() }
        }
    }

    private fun requireAuthorityFiles(): File {
        requireCurrentParents()
        check(Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) { "Established synchronization authority is missing; working copy was preserved" }
        val identity = authorityIdentity ?: WorkspaceDirectoryIdentity(directory).also { authorityIdentity = it }
        identity.requireCurrent()
        val marker = File(directory, "identity")
        require(Files.isRegularFile(marker.toPath(), NOFOLLOW_LINKS) && marker.length() in 1..4_096) { "Synchronization identity is missing or invalid; data was preserved" }
        val markerText = Files.newInputStream(marker.toPath(), NOFOLLOW_LINKS).use { input ->
            val bytes = ByteArray(4_097); var total = 0
            while (total < bytes.size) { val count = input.read(bytes, total, bytes.size - total); if (count < 0) break; total += count }
            require(total <= 4_096) { "Synchronization identity grew during inspection" }
            bytes.copyOf(total).toString(Charsets.UTF_8)
        }.lines()
        val retained = File(directory, "retained")
        val prepared = File(directory, "prepared")
        require(markerText.size == 7 && markerText[0] == "DROIDE-MIRROR-AUTHORITY-2" && markerText[1] == binding && markerText[2] == key(directory) &&
            UUID.fromString(markerText[3]).toString() == markerText[3] && markerText[6].isEmpty() &&
            Files.isDirectory(retained.toPath(), NOFOLLOW_LINKS) && Files.isDirectory(prepared.toPath(), NOFOLLOW_LINKS) &&
            markerText[4] == key(retained) && markerText[5] == key(prepared)) { "Synchronization identity or retained-data directory changed; existing data was preserved" }
        val dbFile = File(directory, "state.sqlite")
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            val path = File(dbFile.path + suffix).toPath()
            require(!Files.exists(path, NOFOLLOW_LINKS) || Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Synchronization database path is unsafe; data was preserved" }
        }
        check(Files.isRegularFile(dbFile.toPath(), NOFOLLOW_LINKS) && dbFile.length() > 0) { "Established synchronization database is missing or empty; it was not recreated" }
        return dbFile
    }

    private fun requireRollbackFormat(database: File, authorityKey: String) {
        AnchoredWorkspaceFiles(directory).use { owner ->
            check(owner.directoryKey() == authorityKey) { "Synchronization authority changed before database open" }
            for (suffix in listOf("-wal", "-shm", "-journal")) {
                check(owner.kind(database.name + suffix) == AnchoredWorkspaceFiles.Kind.ABSENT) {
                    "Pending or unsupported synchronization sidecars; existing evidence requires review"
                }
            }
            owner.openRead(database.name).use { input ->
                val before = input.observe()
                check(before.attributes.fileKey() != null && before.attributes.size() >= 100L) {
                    "Synchronization database identity or header is unavailable"
                }
                val header = ByteArray(20)
                var offset = 0
                while (offset < header.size) {
                    val count = input.read(header, offset, header.size - offset)
                    check(count > 0) { "Synchronization database header is incomplete" }
                    offset += count
                }
                check(SQLITE_HEADER_MAGIC.indices.all { header[it] == SQLITE_HEADER_MAGIC[it] } &&
                    header[18] == 1.toByte() && header[19] == 1.toByte()) {
                    "Unsupported synchronization database format; existing evidence was preserved"
                }
                val after = input.observe()
                check(before.attributes.fileKey() == after.attributes.fileKey() &&
                    before.attributes.size() == after.attributes.size() && before.changed == after.changed &&
                    before.attributes.lastModifiedTime() == after.attributes.lastModifiedTime()) {
                    "Synchronization database changed during header inspection"
                }
                owner.openRead(database.name).use { current ->
                    check(current.observe().attributes.fileKey() == before.attributes.fileKey()) {
                        "Synchronization database was replaced during header inspection"
                    }
                }
            }
        }
    }

    private fun openDatabase(file: File, create: Boolean): SQLiteDatabase {
        val preservingHandler = DatabaseErrorHandler { throw SQLiteDatabaseCorruptException("Synchronization database is corrupt; all files were preserved") }
        val parameters = SQLiteDatabase.OpenParams.Builder()
            .setOpenFlags(SQLiteDatabase.NO_LOCALIZED_COLLATORS or (if (create) SQLiteDatabase.CREATE_IF_NECESSARY else SQLiteDatabase.OPEN_READWRITE))
            .setErrorHandler(preservingHandler)
            .setJournalMode("DELETE")
            .setSynchronousMode("EXTRA")
            .build()
        val db = SQLiteDatabase.openDatabase(file, parameters)
        try {
            db.rawQuery("PRAGMA journal_mode", null).use { check(it.moveToFirst() && it.getString(0).equals("delete", true)) { "Unsupported synchronization journal mode" } }
            db.rawQuery("PRAGMA synchronous", null).use { check(it.moveToFirst() && it.getInt(0) == 3) { "Required metadata durability is unavailable" } }
            databaseSizeLimitBytes?.let { bytes ->
                require(bytes >= 4_096L) { "Invalid synchronization database capacity" }
                db.setMaximumSize(bytes)
            }
            return db
        } catch (failure: Throwable) { db.close(); throw failure }
    }

    private fun readState(db: SQLiteDatabase): State {
        if (db.version >= PROCESS_CUSTODY_SCHEMA) MirrorProcessCustodyLedger.read(db, binding)
        var revision = -1L; var expectedBaseline = ""; var expectedConflicts = ""
        var expectedOperations = -1L
        var bootstrap = BootstrapPhase.PREPARED
        db.rawQuery("SELECT binding,authority_key,revision,baseline_digest,conflicts_digest,operations_count,bootstrap_phase,bootstrap_digest FROM meta WHERE id=1", null).use { row ->
            check(row.moveToFirst() && row.getString(0) == binding && row.getString(1) == key(directory)) { "Synchronization database identity is unavailable or changed" }
            revision = row.getLong(2); expectedBaseline = row.getString(3); expectedConflicts = row.getString(4)
            expectedOperations = row.getLong(5)
            bootstrap = BootstrapPhase.valueOf(row.getString(6))
            check(bootstrapDigest(bootstrap) == row.getString(7)) { "Synchronization bootstrap checksum changed; data was preserved" }
            check(!row.moveToNext() && revision >= 0 && expectedOperations >= 0) { "Invalid synchronization revision" }
        }
        db.rawQuery("SELECT COUNT(*) FROM operations", null).use { row ->
            check(row.moveToFirst() && row.getLong(0) == expectedOperations) { "Recovery intents are missing or changed; retained bytes were preserved" }
        }
        val baseline = linkedMapOf<String, WorkspaceSyncManifest.Entry>()
        var bytes = 0L
        db.rawQuery("SELECT path,sha256,mode FROM baseline ORDER BY path", null).use { rows ->
            while (rows.moveToNext()) {
                check(baseline.size < 20_000) { "Synchronization baseline exceeds its file limit" }
                val path = rows.getString(0)
                bytes += path.toByteArray(Charsets.UTF_8).size + 72L
                check(bytes <= 16L * 1024 * 1024) { "Synchronization baseline exceeds its byte limit" }
                baseline[path] = WorkspaceSyncManifest.Entry(rows.getString(1), rows.getInt(2))
            }
        }
        val directories = readDirectoryHistory(db, bytes)
        val conflicts = linkedSetOf<String>()
        db.rawQuery("SELECT path FROM conflicts ORDER BY path", null).use { rows ->
            while (rows.moveToNext()) { check(conflicts.size < 128) { "Synchronization conflicts exceed their limit" }; conflicts += rows.getString(0) }
        }
        validateState(baseline, conflicts, directories)
        check(baselineDigest(baseline) == expectedBaseline && digest(conflicts.sorted()) == expectedConflicts) { "Synchronization metadata checksum changed; working-copy bytes were preserved" }
        return State(revision, baseline, conflicts, bootstrap, directories)
    }

    private fun validateSchema(db: SQLiteDatabase) {
        val version = db.version
        require(version in 1..MirrorProcessCustodyLedger.LAUNCH_SCHEMA) {
            "Unsupported or damaged synchronization schema; existing data was preserved"
        }
        val hasDirectories = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='baseline_directories'", null).use { it.moveToFirst() }
        val hasDigest = db.rawQuery("PRAGMA table_info(meta)", null).use { rows ->
            var found = false
            while (rows.moveToNext()) if (rows.getString(1) == "directory_digest") found = true
            found
        }
        val hasAcknowledgement = db.rawQuery("PRAGMA table_info(operations)", null).use { rows ->
            var found = false
            while (rows.moveToNext()) if (rows.getString(1) == "ack_original") found = true
            found
        }
        require(hasDirectories == (version >= TYPED_SCHEMA) && hasDigest == (version >= TYPED_SCHEMA) &&
            hasAcknowledgement == (version >= REVIEW_SCHEMA)) {
            "Synchronization schema and directory history disagree; data was preserved"
        }
        MirrorProcessCustodyLedger.validateSchema(db, version >= PROCESS_CUSTODY_SCHEMA)
    }

    private fun readDirectoryHistory(db: SQLiteDatabase, fileBytes: Long): Set<String> {
        if (db.version == 1) return emptySet()
        val directories = linkedSetOf<String>()
        var bytes = fileBytes
        db.rawQuery("SELECT path FROM baseline_directories ORDER BY path", null).use { rows ->
            while (rows.moveToNext()) {
                check(directories.size < MAX_DIRECTORIES) { "Synchronization directory history exceeds its limit" }
                val path = rows.getString(0)
                bytes += path.toByteArray(Charsets.UTF_8).size + 3L
                check(bytes <= MAX_METADATA_BYTES && directories.add(path)) { "Invalid synchronization directory history" }
            }
        }
        db.rawQuery("SELECT directory_digest FROM meta WHERE id=1", null).use { row ->
            check(row.moveToFirst() && row.getString(0) == digest(directories.sorted())) {
                "Synchronization directory history checksum changed; working-copy bytes were preserved"
            }
        }
        return directories
    }

    private fun enableDirectoryHistory(db: SQLiteDatabase) {
        if (db.version >= TYPED_SCHEMA) return
        check(db.inTransaction() && db.version == 1) { "Directory history requires an explicit metadata transaction" }
        db.execSQL("CREATE TABLE baseline_directories (path TEXT PRIMARY KEY NOT NULL)")
        db.execSQL("ALTER TABLE meta ADD COLUMN directory_digest TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE meta SET directory_digest=? WHERE id=1", arrayOf(digest(emptyList())))
        db.version = TYPED_SCHEMA
    }

    private fun writeDirectoryRows(db: SQLiteDatabase, before: Set<String>, next: Set<String>) {
        (before - next).forEach { db.execSQL("DELETE FROM baseline_directories WHERE path=?", arrayOf(it)) }
        (next - before).forEach { db.execSQL("INSERT INTO baseline_directories VALUES (?)", arrayOf(it)) }
    }

    private fun writeRows(db: SQLiteDatabase, before: Map<String, WorkspaceSyncManifest.Entry>, next: Map<String, WorkspaceSyncManifest.Entry>, oldConflicts: Set<String>, conflicts: Set<String>) {
        (before.keys - next.keys).forEach { db.execSQL("DELETE FROM baseline WHERE path=?", arrayOf(it)) }
        next.forEach { (path, entry) -> if (before[path] != entry) db.execSQL("INSERT OR REPLACE INTO baseline VALUES (?,?,?)", arrayOf<Any?>(path, entry.sha256, entry.mode)) }
        (oldConflicts - conflicts).forEach { db.execSQL("DELETE FROM conflicts WHERE path=?", arrayOf(it)) }
        (conflicts - oldConflicts).forEach { db.execSQL("INSERT INTO conflicts VALUES (?)", arrayOf(it)) }
    }

    private fun commitState(
        db: SQLiteDatabase,
        before: State,
        baseline: Map<String, WorkspaceSyncManifest.Entry>,
        conflicts: Set<String>,
        directoryHistory: Set<String>?,
    ): State {
        val directories = directoryHistory ?: before.directoryHistory
        val upgrade = directoryHistory != null && db.version == 1
        if (!upgrade && before.baseline == baseline && before.conflicts == conflicts && before.directoryHistory == directories) return before
        validateState(baseline, conflicts, directories)
        check(before.revision in 0 until Long.MAX_VALUE) { "Synchronization revision capacity exhausted" }
        if (upgrade) enableDirectoryHistory(db)
        writeRows(db, before.baseline, baseline, before.conflicts, conflicts)
        if (db.version >= TYPED_SCHEMA) {
            writeDirectoryRows(db, before.directoryHistory, directories)
            db.execSQL("UPDATE meta SET directory_digest=? WHERE id=1", arrayOf(digest(directories.sorted())))
        }
        db.execSQL("UPDATE meta SET revision=?,baseline_digest=?,conflicts_digest=? WHERE id=1",
            arrayOf<Any?>(before.revision + 1, baselineDigest(baseline), digest(conflicts.sorted())))
        return State(before.revision + 1, baseline.toMap(), conflicts.toSet(), before.bootstrapPhase, directories.toSet())
    }

    private fun validateState(
        baseline: Map<String, WorkspaceSyncManifest.Entry>,
        conflicts: Set<String>,
        directories: Set<String> = emptySet(),
    ) {
        require(baseline.size <= 20_000 && conflicts.size <= 128 && directories.size <= MAX_DIRECTORIES)
        var bytes = 0L
        baseline.forEach { (path, entry) ->
            validateAncestry(path, baseline)
            bytes += path.toByteArray(Charsets.UTF_8).size + 72L
            require(bytes <= 16L * 1024 * 1024 && AgentWorkspacePathPolicy.visible(path) && path.toByteArray(Charsets.UTF_8).size <= 4_096 &&
            hashPattern.matches(entry.sha256) && (entry.mode == 420 || entry.mode == 493)) { "Invalid trusted synchronization entry" } }
        for (path in directories) {
            validateAncestry(path, baseline)
            bytes += path.toByteArray(Charsets.UTF_8).size + 3L
            require(bytes <= MAX_METADATA_BYTES && AgentWorkspacePathPolicy.visible(path) &&
                path.toByteArray(Charsets.UTF_8).size <= 4_096 && path !in baseline) {
                "Invalid trusted synchronization directory entry"
            }
        }
        require(conflicts.all { AgentWorkspacePathPolicy.visible(it) && it.toByteArray(Charsets.UTF_8).size <= 4_096 }) { "Invalid trusted conflict entry" }
    }

    private fun validateAncestry(path: String, baseline: Map<String, WorkspaceSyncManifest.Entry>) {
        var slash = path.indexOf('/')
        while (slash >= 0) {
            require(path.substring(0, slash) !in baseline) { "Synchronization baseline contains a file/directory identity collision" }
            slash = path.indexOf('/', slash + 1)
        }
    }

    private fun validateIntent(record: Intent) {
        fun valid(node: Node) = when (node.type) {
            NodeType.ABSENT -> node.sha256.isEmpty() && node.mode == 0
            NodeType.FILE -> hashPattern.matches(node.sha256) && (node.mode == 420 || node.mode == 493)
            NodeType.DIRECTORY -> hashPattern.matches(node.sha256) && node.mode == 0
        }
        val shape = when (record.kind) {
            "create" -> record.before.type == NodeType.ABSENT && record.proposed.type != NodeType.ABSENT
            "delete" -> record.before.type != NodeType.ABSENT && record.proposed.type == NodeType.ABSENT
            "replace" -> record.before.type == NodeType.FILE && record.proposed.type == NodeType.FILE
            "directory" -> record.before.type != NodeType.ABSENT && record.proposed.type != NodeType.ABSENT &&
                (record.before.type == NodeType.DIRECTORY || record.proposed.type == NodeType.DIRECTORY)
            else -> false
        }
        require(UUID.fromString(record.id).toString() == record.id && record.retainedName == record.id &&
            AgentWorkspacePathPolicy.visible(record.path) && record.path.toByteArray(Charsets.UTF_8).size <= 4_096 &&
            valid(record.before) && valid(record.proposed) && shape &&
            (record.acknowledgedOriginal == null || hashPattern.matches(record.acknowledgedOriginal) &&
                record.phase in setOf(Phase.APPLIED, Phase.CONFLICT))) { "Invalid recovery metadata; retained bytes were preserved" }
    }

    private fun readIntent(row: android.database.Cursor): Intent {
        fun text(name: String) = row.getString(row.getColumnIndexOrThrow(name))
        fun number(name: String) = row.getInt(row.getColumnIndexOrThrow(name))
        val record = Intent(text("id"), text("path"), text("kind"), text("retained_name"), Phase.valueOf(text("phase")),
            Node(NodeType.valueOf(text("old_type")), text("old_sha"), number("old_mode")),
            Node(NodeType.valueOf(text("new_type")), text("new_sha"), number("new_mode")),
            row.getColumnIndex("ack_original").takeIf { it >= 0 && !row.isNull(it) }?.let(row::getString))
        validateIntent(record)
        check(intentDigest(record) == text("record_digest")) { "Recovery intent checksum changed; retained bytes were preserved" }
        return record
    }

    private fun intentDigest(record: Intent) = digest(listOf(binding, record.id, record.path, record.kind, record.retainedName, record.phase.name,
        record.before.type.name, record.before.sha256, record.before.mode.toString(), record.proposed.type.name, record.proposed.sha256, record.proposed.mode.toString()) +
        record.acknowledgedOriginal?.let { listOf("reviewed-original-v1", it) }.orEmpty())

    private fun requireCurrentParents() { sourceIdentity.requireCurrent(); workingIdentity.requireCurrent(); parentIdentity.requireCurrent() }
    private fun key(file: File): String = checkNotNull(Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()).toString()
    private fun baselineDigest(values: Map<String, WorkspaceSyncManifest.Entry>) = digest(values.toSortedMap().map { (path, entry) -> "${entry.sha256}\t${entry.mode}\t$path" })
    private fun bootstrapDigest(phase: BootstrapPhase) = digest(listOf(binding, "bootstrap", phase.name))
    private fun digest(parts: List<String>): String {
        val hash = MessageDigest.getInstance("SHA-256")
        parts.forEach { hash.update(it.toByteArray(Charsets.UTF_8)); hash.update('\n'.code.toByte()) }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun <T> transaction(db: SQLiteDatabase, action: () -> T): T {
        db.beginTransaction()
        var failure: Throwable? = null
        try { return action().also { db.setTransactionSuccessful() } }
        catch (original: Throwable) { failure = original; throw original }
        finally {
            try { db.endTransaction() }
            catch (cleanup: Throwable) {
                val original = failure
                if (original == null) throw cleanup
                if (original !== cleanup) original.addSuppressed(cleanup)
            }
        }
    }

    companion object {
        private val SQLITE_HEADER_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        private const val TYPED_SCHEMA = 2
        private const val REVIEW_SCHEMA = 3
        private const val PROCESS_CUSTODY_SCHEMA = 4
        private val accessLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        private const val MAX_DIRECTORIES = 40_000
        private const val MAX_METADATA_BYTES = 16L * 1024 * 1024
        fun createForNewWorkingCopy(directory: File, source: File, working: File,
                                    durability: ProjectCopyJournal.Durability = ProjectCopyJournal.Durability.platform,
                                    requireExternalLink: Boolean = false): MirrorMetadataAuthority {
            check(!Files.exists(directory.toPath(), NOFOLLOW_LINKS)) { "Synchronization authority already exists; it was preserved" }
            Files.createDirectory(working.toPath())
            return MirrorMetadataAuthority(directory, source, working, durability).also {
                it.createNew(emptyMap(), emptySet(), if (requireExternalLink) BootstrapPhase.PREPARED else BootstrapPhase.LINKED)
            }
        }

        fun importLegacyHostBaseline(directory: File, source: File, working: File, expectedWorkingIdentity: String,
                                     baseline: Map<String, WorkspaceSyncManifest.Entry>, conflicts: Set<String>): MirrorMetadataAuthority {
            return importBaseline(directory, source, working, expectedWorkingIdentity, baseline, conflicts, emptySet(), typedHistory = false)
        }

        fun importHostBaseline(
            directory: File,
            source: File,
            working: File,
            expectedWorkingIdentity: String,
            baseline: Map<String, WorkspaceSyncManifest.Entry>,
            conflicts: Set<String>,
            directoryHistory: Set<String>,
        ): MirrorMetadataAuthority = importBaseline(
            directory, source, working, expectedWorkingIdentity, baseline, conflicts, directoryHistory, typedHistory = true,
        )

        private fun importBaseline(
            directory: File,
            source: File,
            working: File,
            expectedWorkingIdentity: String,
            baseline: Map<String, WorkspaceSyncManifest.Entry>,
            conflicts: Set<String>,
            directoryHistory: Set<String>,
            typedHistory: Boolean,
        ): MirrorMetadataAuthority {
            val identity = WorkspaceDirectoryIdentity(working)
            check(Files.readAttributes(working.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()?.toString() == expectedWorkingIdentity) {
                "Legacy working-copy identity changed; all data was preserved"
            }
            return MirrorMetadataAuthority(directory, source, working).also {
                identity.requireCurrent()
                it.createNew(baseline, conflicts, BootstrapPhase.PREPARED, directoryHistory, typedHistory)
                identity.requireCurrent()
            }
        }
    }
}
