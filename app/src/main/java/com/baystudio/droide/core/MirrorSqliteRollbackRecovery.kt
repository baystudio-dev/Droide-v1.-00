package com.baystudio.droide.core

import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Explicit, bounded forensic review. Never opens or replaces the authoritative database.
 * An observed stable pair is NOT proof that legacy or external writers have stopped.
 * Consequently this result cannot authorize activation, cleanup or history release.
 */
internal object MirrorSqliteRollbackRecovery {
    data class Review(val evidenceDirectory: File, val databaseSha256: String, val journalSha256: String,
                      internal val evidenceIdentity: String,
                      internal val observations: Map<String, AnchoredWorkspaceFiles.Observation> = emptyMap())
    private const val DATABASE = "state.sqlite"
    private const val JOURNAL = "state.sqlite-journal"
    private const val MAX_BYTES = 64L * 1024 * 1024

    /** Injectable IO boundary for failure-contract tests; production uses platform below. */
    internal interface ReceiptIo {
        fun write(file: AnchoredWorkspaceFiles.OpenedFile, bytes: ByteArray)
        fun force(file: AnchoredWorkspaceFiles.OpenedFile)
        fun publish(owner: AnchoredWorkspaceFiles, staged: String): AnchoredWorkspaceFiles.MoveReceipt

        companion object {
            val platform = object : ReceiptIo {
                override fun write(file: AnchoredWorkspaceFiles.OpenedFile, bytes: ByteArray) = file.write(bytes)
                override fun force(file: AnchoredWorkspaceFiles.OpenedFile) = file.force(384)
                override fun publish(owner: AnchoredWorkspaceFiles, staged: String) =
                    owner.moveNoReplaceDurable(staged, owner, "receipt")
            }
        }
    }

    fun capture(directory: File, binding: String, requireCurrent: () -> Unit,
                checkpoint: (String) -> Unit = {}): Review =
        captureWithReceiptIo(directory, binding, requireCurrent, ReceiptIo.platform, checkpoint)

    internal fun captureWithReceiptIo(directory: File, binding: String, requireCurrent: () -> Unit,
                                      receiptIo: ReceiptIo, checkpoint: (String) -> Unit = {}): Review {
        requireCurrent()
        AnchoredWorkspaceFiles(directory).use { authority ->
            check(authority.kind("$DATABASE-wal") == AnchoredWorkspaceFiles.Kind.ABSENT &&
                authority.kind("$DATABASE-shm") == AnchoredWorkspaceFiles.Kind.ABSENT) {
                "WAL recovery is unsupported; all evidence was preserved"
            }
            check(authority.kind(DATABASE) == AnchoredWorkspaceFiles.Kind.FILE &&
                authority.kind(JOURNAL) == AnchoredWorkspaceFiles.Kind.FILE) {
                "Explicit rollback review requires an ordinary database and rollback journal"
            }
            // Bound retained forensic attempts. Interrupted attempts count and are never removed.
            check(attemptNames(authority, "sqlite-review-", 8).size < 8) {
                "Rollback review evidence capacity reached; existing evidence was preserved"
            }
            authority.openRead(DATABASE).use { database -> authority.openRead(JOURNAL).use { journal ->
                val dbBefore = database.observe()
                val journalBefore = journal.observe()
                for (before in listOf(dbBefore, journalBefore)) check(
                    before.attributes.fileKey() != null && before.attributes.size() in 1..MAX_BYTES) {
                    "Rollback review input identity or bounded size is unavailable"
                }
                val name = "sqlite-review-${UUID.randomUUID()}"
                val created = authority.createDirectory(name)
                check(created.durable) { "Rollback review evidence directory durability is unconfirmed" }
                val evidence = File(directory, name)
                authority.openDirectory(name).use { output ->
                    val dbCopy = copy(database, output, DATABASE, dbBefore.attributes.size(), requireCurrent)
                    checkpoint("sqlite-review-after-database-copy")
                    val journalCopy = copy(journal, output, JOURNAL, journalBefore.attributes.size(), requireCurrent)
                    checkpoint("sqlite-review-after-journal-copy")
                    val dbHash = dbCopy.sha
                    val journalHash = journalCopy.sha
                    fun requireCapture() {
                        requireCurrent()
                        requirePair(authority)
                        checkSame(dbBefore, database.observe()); checkSame(journalBefore, journal.observe())
                        checkSnapshot(authority, DATABASE, dbBefore, dbHash, requireCurrent)
                        checkSnapshot(authority, JOURNAL, journalBefore, journalHash, requireCurrent)
                        requirePair(output)
                        for ((name, copied) in listOf(DATABASE to dbCopy, JOURNAL to journalCopy)) {
                            output.openRead(name).use { privateFile(it) }
                            checkSnapshot(output, name, copied.observation, copied.sha, requireCurrent)
                        }
                        requirePair(authority)
                    }
                    requireCapture()
                    // Write/force privately, then publish without replacement. Receipt presence
                    // alone is never success: rename may complete before directory force fails.
                    // Only successful IO returns Review, and Review never authorizes replay.
                    val receipt = "DROIDE-SQLITE-EVIDENCE-1\n$binding\n${authority.directoryKey()}\n$dbHash\n$journalHash\n" +
                        "OBSERVED_STABLE_ONLY; WRITER_EXCLUSION_UNPROVEN; REPLAY_AND_ACTIVATION_DENIED\n"
                    checkpoint("sqlite-review-before-receipt")
                    val receiptBytes = receipt.toByteArray(Charsets.UTF_8)
                    val stagedReceipt = ".receipt-pending"
                    val stagedVersion = output.createNew(stagedReceipt).use {
                        receiptIo.write(it, receiptBytes)
                        receiptIo.force(it)
                        it.observe()
                    }
                    requireReceiptBytes(output, stagedReceipt, receiptBytes, stagedVersion)
                    checkpoint("sqlite-review-before-receipt-publication")
                    // Receipt IO/callbacks are not evidence of a stable capture. Renew both
                    // pinned source and copied identities immediately before publication.
                    requireCapture()
                    requireReceiptBytes(output, stagedReceipt, receiptBytes, stagedVersion)
                    check(receiptIo.publish(output, stagedReceipt).durable) {
                        "Rollback evidence receipt publication durability is unconfirmed; all evidence was preserved"
                    }
                    // Rename may change ctime, but must publish the same forced inode.
                    val committedVersion = requireReceiptBytes(output, "receipt", receiptBytes,
                        stagedVersion, publishedRename = true)
                    checkpoint("sqlite-review-after-receipt")
                    // Publication can succeed before a later integrity failure. Retain every
                    // byte, but never return success for a pair changed at that boundary.
                    requireCapture()
                    requireReceiptBytes(output, "receipt", receiptBytes, committedVersion)
                    requireCurrent()
                    return Review(evidence, dbHash, journalHash, output.directoryKey())
                }
            } }
        }
    }

    /** Malformed retained content is a miss, unlike I/O, caller, lease or identity failure. */
    internal class Ineligible(message: String) : IllegalStateException(message)
    internal fun eligible(condition: Boolean, message: String) {
        if (!condition) throw Ineligible(message)
    }
    private data class FileSnapshot(val observation: AnchoredWorkspaceFiles.Observation, val sha: String)

    /** No historical inode claim: v1 receipts are validated against bytes observed in this call. */
    fun <T> withMatchingEvidence(directory: File, binding: String, requireCurrent: () -> Unit,
                                 checkpoint: (String) -> Unit, action: (List<Review>, () -> Unit) -> T): T {
        requireCurrent()
        AnchoredWorkspaceFiles(directory).use { authority ->
            requirePair(authority)
            authority.openRead(DATABASE).use { sourceDb -> authority.openRead(JOURNAL).use { sourceJournal ->
                check(sourceDb.linkCount() == 1L && sourceJournal.linkCount() == 1L) { "Rollback originals have unsupported aliases" }
                val database = snapshot(sourceDb, requireCurrent)
                val journal = snapshot(sourceJournal, requireCurrent)
                val expected = receipt(binding, authority.directoryKey(), database.sha, journal.sha)
                val reviews = ArrayList<Review>()
                for (name in attemptNames(authority, "sqlite-review-", 8)) {
                    requireCurrent()
                    if (!name.matches(Regex("sqlite-review-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) ||
                        authority.kind(name) != AnchoredWorkspaceFiles.Kind.DIRECTORY) continue
                    authority.openDirectory(name).use { evidence ->
                        val found = try {
                            val receiptVersion = privateBytes(evidence, "receipt", expected, requireCurrent)
                            eligible(evidence.kind("$DATABASE-wal") == AnchoredWorkspaceFiles.Kind.ABSENT &&
                                evidence.kind("$DATABASE-shm") == AnchoredWorkspaceFiles.Kind.ABSENT, "WAL evidence is unsupported")
                            val db = privateSnapshot(evidence, DATABASE, requireCurrent)
                            val log = privateSnapshot(evidence, JOURNAL, requireCurrent)
                            eligible(db.sha == database.sha && log.sha == journal.sha, "Retained capture no longer matches its receipt")
                            Review(File(directory, name), db.sha, log.sha, evidence.directoryKey(),
                                mapOf(DATABASE to db.observation, JOURNAL to log.observation, "receipt" to receiptVersion))
                        } catch (_: Ineligible) { null }
                        if (found != null) {
                            checkpoint("sqlite-reuse-after-capture-validation")
                            requireCurrent()
                            found.observations.forEach { (file, before) -> evidence.openRead(file).use { checkSame(before, it.observe()) } }
                            reviews += found
                        }
                    }
                }
                requireSource(authority, sourceDb, sourceJournal, database, journal, requireCurrent)
                checkpoint("sqlite-reuse-before-consumer")
                requireCurrent()
                val result = action(reviews) {
                    checkpoint("sqlite-reuse-before-return")
                    requireCurrent()
                    requireSource(authority, sourceDb, sourceJournal, database, journal, requireCurrent)
                    authority.forceDirectory()
                }
                requireSource(authority, sourceDb, sourceJournal, database, journal, requireCurrent)
                requireCurrent()
                return result
            } }
        }
    }

    internal fun attemptNames(owner: AnchoredWorkspaceFiles, prefix: String, limit: Int): List<String> {
        val result = ArrayList<String>()
        var count = 0
        owner.forEachChild { name ->
            check(++count <= 4096) { "Forensic evidence namespace exceeds inspection bound; all entries were preserved" }
            if (name.startsWith(prefix)) {
                check(result.size < limit) { "Forensic retained attempt capacity exceeded; all entries were preserved" }
                result += name
            }
        }
        return result.sorted()
    }

    internal fun receipt(binding: String, authority: String, database: String, journal: String) =
        ("DROIDE-SQLITE-EVIDENCE-1\n$binding\n$authority\n$database\n$journal\n" +
            "OBSERVED_STABLE_ONLY; WRITER_EXCLUSION_UNPROVEN; REPLAY_AND_ACTIVATION_DENIED\n").toByteArray(Charsets.UTF_8)

    internal fun privateBytes(owner: AnchoredWorkspaceFiles, name: String, expected: ByteArray,
                              requireCurrent: () -> Unit): AnchoredWorkspaceFiles.Observation {
        eligible(owner.kind(name) == AnchoredWorkspaceFiles.Kind.FILE, "Incomplete forensic record")
        return owner.openRead(name).use { input ->
            privateFile(input)
            val before = input.observe()
            eligible(before.attributes.size() == expected.size.toLong(), "Unrecognized forensic record size")
            val bytes = ByteArray(expected.size)
            var offset = 0
            while (offset < bytes.size) {
                requireCurrent()
                val count = input.read(bytes, offset, bytes.size - offset)
                check(count > 0) { "Forensic record changed while reading" }
                offset += count
            }
            checkSame(before, input.observe())
            eligible(input.read() == -1 && bytes.contentEquals(expected), "Unrecognized forensic record")
            before
        }
    }

    internal fun privateFile(input: AnchoredWorkspaceFiles.OpenedFile) {
        eligible(input.linkCount() == 1L && input.permissionMode() == 384, "Forensic file is not private or has aliases")
    }

    private fun privateSnapshot(owner: AnchoredWorkspaceFiles, name: String, requireCurrent: () -> Unit): FileSnapshot {
        eligible(owner.kind(name) == AnchoredWorkspaceFiles.Kind.FILE, "Incomplete forensic capture")
        return owner.openRead(name).use { privateFile(it); snapshot(it, requireCurrent) }
    }

    private fun snapshot(input: AnchoredWorkspaceFiles.OpenedFile, requireCurrent: () -> Unit): FileSnapshot {
        val before = input.observe()
        eligible(before.attributes.fileKey() != null && before.attributes.size() in 1..MAX_BYTES, "Forensic input is unbounded")
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            requireCurrent()
            val count = input.read(buffer)
            if (count < 0) break
            check(count > 0 && total + count <= before.attributes.size()) { "Forensic input changed during inspection" }
            digest.update(buffer, 0, count); total += count
        }
        check(total == before.attributes.size()) { "Forensic input changed during inspection" }
        checkSame(before, input.observe())
        return FileSnapshot(before, hex(digest.digest()))
    }

    private fun requirePair(owner: AnchoredWorkspaceFiles) {
        check(owner.kind("$DATABASE-wal") == AnchoredWorkspaceFiles.Kind.ABSENT &&
            owner.kind("$DATABASE-shm") == AnchoredWorkspaceFiles.Kind.ABSENT) { "WAL recovery is unsupported; all evidence was preserved" }
        check(owner.kind(DATABASE) == AnchoredWorkspaceFiles.Kind.FILE && owner.kind(JOURNAL) == AnchoredWorkspaceFiles.Kind.FILE) {
            "Explicit rollback review requires an ordinary database and rollback journal"
        }
    }

    private fun requireSource(owner: AnchoredWorkspaceFiles, db: AnchoredWorkspaceFiles.OpenedFile,
                              log: AnchoredWorkspaceFiles.OpenedFile, database: FileSnapshot, journal: FileSnapshot,
                              requireCurrent: () -> Unit) {
        requirePair(owner)
        checkSame(database.observation, db.observe()); checkSame(journal.observation, log.observe())
        check(db.linkCount() == 1L && log.linkCount() == 1L) { "Rollback originals acquired aliases" }
        checkSnapshot(owner, DATABASE, database.observation, database.sha, requireCurrent)
        checkSnapshot(owner, JOURNAL, journal.observation, journal.sha, requireCurrent)
        requirePair(owner)
    }

    private fun requireReceiptBytes(owner: AnchoredWorkspaceFiles, name: String, expected: ByteArray,
                                    version: AnchoredWorkspaceFiles.Observation,
                                    publishedRename: Boolean = false): AnchoredWorkspaceFiles.Observation =
        owner.openRead(name).use { input ->
            privateFile(input)
            val before = input.observe()
            if (publishedRename) {
                check(version.attributes.fileKey() == before.attributes.fileKey() &&
                    version.attributes.size() == before.attributes.size() &&
                    version.attributes.lastModifiedTime() == before.attributes.lastModifiedTime()) {
                    "Rollback evidence receipt no longer identifies its forced inode; all evidence was preserved"
                }
            } else checkSame(version, before)
            check(before.attributes.size() == expected.size.toLong()) { "Rollback evidence receipt is incomplete" }
            val bytes = ByteArray(expected.size + 1)
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                if (count < 0) break
                check(count > 0) { "Rollback evidence receipt read stalled" }
                offset += count
            }
            check(offset == expected.size && bytes.copyOf(offset).contentEquals(expected)) {
                "Rollback evidence receipt changed before completion"
            }
            checkSame(before, input.observe())
            before
        }

    private fun copy(input: AnchoredWorkspaceFiles.OpenedFile, output: AnchoredWorkspaceFiles, name: String,
                     size: Long, requireCurrent: () -> Unit): FileSnapshot = output.createNew(name).use { target ->
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            requireCurrent()
            val count = input.read(buffer)
            if (count < 0) break
            check(count > 0 && total + count <= size) { "Rollback evidence changed while copying" }
            target.write(buffer, 0, count); hash.update(buffer, 0, count); total += count
        }
        check(total == size) { "Rollback evidence was truncated while copying" }
        target.force(384)
        FileSnapshot(target.observe(), hex(hash.digest()))
    }

    private fun checkSnapshot(owner: AnchoredWorkspaceFiles, name: String, before: AnchoredWorkspaceFiles.Observation,
                              expected: String, requireCurrent: () -> Unit) = owner.openRead(name).use { current ->
        checkSame(before, current.observe())
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            requireCurrent()
            val count = current.read(buffer)
            if (count < 0) break
            check(count > 0 && total + count <= before.attributes.size()) { "Rollback evidence grew during verification" }
            hash.update(buffer, 0, count); total += count
        }
        check(total == before.attributes.size() && hex(hash.digest()) == expected) { "Rollback evidence changed during verification" }
        checkSame(before, current.observe())
    }

    private fun checkSame(before: AnchoredWorkspaceFiles.Observation, after: AnchoredWorkspaceFiles.Observation) {
        check(before.attributes.fileKey() == after.attributes.fileKey() && before.attributes.size() == after.attributes.size() &&
            before.attributes.lastModifiedTime() == after.attributes.lastModifiedTime() && before.changed == after.changed) {
            "Rollback evidence identity or version changed; no recovery was authorized"
        }
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
