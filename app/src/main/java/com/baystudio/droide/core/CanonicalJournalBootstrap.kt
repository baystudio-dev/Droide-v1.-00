package com.baystudio.droide.core

import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Dormant explicit enrollment; no runtime caller and no shared-UID isolation guarantee.
internal class CanonicalJournalBootstrap(
    private val slot: File,
    private val maxBytes: Long,
    private val checkpoint: (String) -> Unit = {},
) {
    data class Inspection(val enrollment: CanonicalFileOperationJournal.Enrollment,
                          val history: CanonicalFileOperationJournal.RecoveryMetadata)

    @Serializable private data class Reservation(
        val version: Int = 1,
        val projectId: String,
        val rootPath: String,
        val rootKey: String,
        val slotPath: String,
        val slotKey: String,
        val parentKey: String,
        val generation: String,
    )

    @Serializable private data class Receipt(
        val version: Int = 1,
        val reservationHash: String,
        val enrollment: CanonicalFileOperationJournal.Enrollment,
    )

    @Serializable private data class Membership(
        val version: Int = 1,
        val projectId: String,
        val rootPath: String,
        val rootKey: String,
        val slotPath: String,
        val slotParentKey: String,
        val membershipPath: String,
        val membershipParentKey: String,
        val membershipKey: String,
        val generation: String,
    )

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }
    private val authority = File(slot, "authority")
    private val database = File(authority, "state.sqlite")
    private enum class ReadMode { VALIDATE_AND_FORCE, INSPECT, BINDING_ONLY }

    init {
        require(maxBytes >= 0)
        require(slot.isAbsolute && slot == slot.canonicalFile && slot.name !in listOf("", ".", ".."))
    }

    fun createNew(root: File, projectId: String, context: CoroutineContext): CanonicalFileOperationJournal.Enrollment =
        createGeneration(root, projectId, UUID.randomUUID().toString(), context)

    private fun createGeneration(root: File, projectId: String, generation: String,
                                 context: CoroutineContext): CanonicalFileOperationJournal.Enrollment {
        requireRequest(root, projectId)
        context.ensureActive()
        AnchoredWorkspaceFiles(root).use { workspace ->
            AnchoredWorkspaceFiles(requireNotNull(slot.parentFile)).use { parent ->
                check(parent.createDirectory(slot.name).durable) {
                    "Enrollment location already exists or creation was not durable; existing evidence was preserved"
                }
                parent.openDirectory(slot.name).use { owner ->
                    val reservation = Reservation(projectId = projectId, rootPath = root.path,
                        rootKey = workspace.directoryKey(), slotPath = slot.path, slotKey = owner.directoryKey(),
                        parentKey = parent.directoryKey(), generation = generation)
                    checkpoint("slot-created")
                    requireSlot(parent, owner, reservation)
                    owner.forEachChild { error("Unexpected enrollment contents; existing files were preserved") }
                    write(owner, "intent.json", json.encodeToString(reservation), context)
                    fun verify() {
                        context.ensureActive()
                        requireSlot(parent, owner, reservation)
                        check(workspace.directoryKey() == reservation.rootKey)
                        check(read(owner, "intent.json") == json.encodeToString(reservation)) { "Enrollment intent changed" }
                    }
                    verify()
                    check(owner.createDirectory(authority.name).durable) { "Authority creation was not confirmed" }
                    val authorityKey = owner.directoryKey(authority.name)
                    checkpoint("authority-created")
                    owner.openDirectory(authority.name).use { storage ->
                        check(storage.directoryKey() == authorityKey) { "New authority identity changed" }
                        storage.forEachChild { error("Unexpected authority contents; existing files were preserved") }
                        check(storage.createDirectory("custody").durable) { "Custody creation was not confirmed" }
                        storage.createNew(database.name).use { it.force(384) }
                        val databaseKey = fileKey(storage, database.name)
                        val enrollment = CanonicalFileOperationJournal.Enrollment(projectId, root.path,
                            reservation.rootKey, authority.path, storage.directoryKey(), databaseKey,
                            storage.directoryKey("custody"), reservation.generation, reservation.slotKey)
                        checkpoint("database-reserved")
                        fun requireDatabase() {
                            verify()
                            check(owner.directoryKey(authority.name) == enrollment.authorityKey)
                            check(storage.directoryKey("custody") == enrollment.custodyKey)
                            check(fileKey(storage, database.name) == databaseKey) { "Enrollment database identity changed" }
                            listOf("-journal", "-wal", "-shm").forEach {
                                check(storage.kind(database.name + it) in listOf(AnchoredWorkspaceFiles.Kind.ABSENT,
                                    AnchoredWorkspaceFiles.Kind.FILE)) { "Unsafe enrollment database sidecar" }
                            }
                        }
                        requireDatabase()
                        listOf("-journal", "-wal", "-shm").forEach {
                            check(storage.kind(database.name + it) == AnchoredWorkspaceFiles.Kind.ABSENT) {
                                "Unexpected database sidecar before enrollment; existing files were preserved"
                            }
                        }
                        storage.openRead(database.name).use {
                            check(it.observe().attributes.size() == 0L) { "New database is no longer empty; existing bytes were preserved" }
                        }
                        createDatabase(enrollment, ::requireDatabase)
                        checkpoint("database-committed")
                        requireDatabase()
                        forceFile(storage, database.name, databaseKey)
                        check(CanonicalFileOperationJournal(root, authority, enrollment, maxBytes)
                            .readPendingMetadataForRecovery() == null) { "New authority is not empty" }
                        verify()
                        val receipt = Receipt(reservationHash = digest(json.encodeToString(reservation)), enrollment = enrollment)
                        write(owner, "enrollment.json", json.encodeToString(receipt), context)
                        verify()
                        checkpoint("enrollment-complete")
                        return readGeneration(root, projectId, generation, context).also { check(it == enrollment) }
                    }
                }
            }
        }
    }

    // Explicit recovery only; it never creates a database, reservation, or replacement receipt.
    fun recoverCommittedEnrollment(root: File, projectId: String, context: CoroutineContext): CanonicalFileOperationJournal.Enrollment =
        recoverGeneration(root, projectId, null, context)

    private fun recoverGeneration(root: File, projectId: String, generation: String?,
                                  context: CoroutineContext): CanonicalFileOperationJournal.Enrollment {
        requireRequest(root, projectId)
        context.ensureActive()
        AnchoredWorkspaceFiles(requireNotNull(slot.parentFile)).use { parent ->
            parent.openDirectory(slot.name).use { owner ->
                val intentText = read(owner, "intent.json")
                val reservation = decode<Reservation>(intentText)
                fun verify() {
                    context.ensureActive()
                    requireReservation(parent, owner, reservation, root, projectId)
                    requireGeneration(reservation.generation, generation)
                    check(read(owner, "intent.json") == intentText) { "Enrollment intent changed during recovery" }
                }
                verify()
                if (owner.kind("enrollment.json") != AnchoredWorkspaceFiles.Kind.ABSENT) {
                    return readGeneration(root, projectId, generation, context).also { verify() }
                }
                owner.openDirectory(authority.name).use { storage ->
                    val enrollment = CanonicalFileOperationJournal.Enrollment(projectId, root.path,
                        reservation.rootKey, authority.path, storage.directoryKey(), fileKey(storage, database.name),
                        storage.directoryKey("custody"), reservation.generation, reservation.slotKey)
                    val journal = CanonicalFileOperationJournal(root, authority, enrollment, maxBytes)
                    journal.inspectUnusedEnrollmentForRecovery()
                    checkpoint("enrollment-recovery-inspected")
                    verify()
                    forceFile(storage, database.name, enrollment.databaseKey)
                    owner.forceDirectory()
                    checkpoint("enrollment-recovery-forced")
                    verify()
                    journal.inspectUnusedEnrollmentForRecovery()
                    val receipt = Receipt(reservationHash = digest(intentText), enrollment = enrollment)
                    write(owner, "enrollment.json", json.encodeToString(receipt), context)
                    verify()
                    checkpoint("enrollment-recovery-complete")
                    return readGeneration(root, projectId, generation, context).also { check(it == enrollment) }
                }
            }
        }
    }

    // Absence is always an error; historical enrollment does not authorize a filesystem mutation.
    fun readExisting(root: File, projectId: String, context: CoroutineContext): CanonicalFileOperationJournal.Enrollment =
        readGeneration(root, projectId, null, context)

    private fun readGeneration(root: File, projectId: String, generation: String?,
                               context: CoroutineContext, mode: ReadMode = ReadMode.VALIDATE_AND_FORCE): CanonicalFileOperationJournal.Enrollment {
        requireRequest(root, projectId)
        context.ensureActive()
        AnchoredWorkspaceFiles(requireNotNull(slot.parentFile)).use { parent ->
            parent.openDirectory(slot.name).use { owner ->
                val intentText = read(owner, "intent.json")
                val receiptText = read(owner, "enrollment.json")
                val reservation = decode<Reservation>(intentText)
                val receipt = decode<Receipt>(receiptText)
                val enrollment = receipt.enrollment
                requireReservation(parent, owner, reservation, root, projectId)
                requireGeneration(reservation.generation, generation)
                check(receipt.version == 1 && receipt.reservationHash == digest(intentText) &&
                    enrollment.projectId == projectId && enrollment.rootPath == root.path &&
                    enrollment.rootKey == reservation.rootKey && enrollment.generation == reservation.generation &&
                    enrollment.authorityPath == authority.path && enrollment.authorityParentKey == reservation.slotKey) {
                    "Enrollment receipt is foreign or corrupt; recovery is required"
                }
                val journal = CanonicalFileOperationJournal(root, authority, enrollment, maxBytes)
                when (mode) {
                    ReadMode.VALIDATE_AND_FORCE -> journal.readPendingMetadataForRecovery()
                    ReadMode.INSPECT -> journal.inspectPendingMetadataForRecovery()
                    ReadMode.BINDING_ONLY -> journal.requireAuthorityBinding(context)
                }
                checkpoint("existing-validated")
                if (mode == ReadMode.VALIDATE_AND_FORCE) {
                    owner.openDirectory(authority.name).use { storage ->
                        check(storage.directoryKey() == enrollment.authorityKey)
                        forceFile(storage, database.name, enrollment.databaseKey)
                    }
                    forceFile(owner, "intent.json", fileKey(owner, "intent.json"))
                    forceFile(owner, "enrollment.json", fileKey(owner, "enrollment.json"))
                    parent.forceDirectory()
                }
                context.ensureActive()
                requireSlot(parent, owner, reservation)
                check(read(owner, "intent.json") == intentText && read(owner, "enrollment.json") == receiptText) {
                    "Enrollment changed during recovery; existing metadata was preserved"
                }
                return enrollment
            }
        }
    }

    // Explicit provisioning only; a missing prior slot never authorizes a second enrollment.
    fun createWithMembership(root: File, projectId: String, membership: File,
                             context: CoroutineContext): CanonicalFileOperationJournal.Enrollment =
        membershipPins(root, projectId, membership, context) { workspace, slots, members ->
            val pending = pendingMembership(membership)
            check(slots.kind(slot.name) == AnchoredWorkspaceFiles.Kind.ABSENT &&
                members.kind(membership.name) == AnchoredWorkspaceFiles.Kind.ABSENT &&
                members.kind(pending) == AnchoredWorkspaceFiles.Kind.ABSENT) {
                "Enrollment membership or retained provisioning already exists; it was not recreated"
            }
            val witness = members.createNew(pending).use { output ->
                val record = Membership(projectId = projectId, rootPath = root.path, rootKey = workspace.directoryKey(),
                    slotPath = slot.path, slotParentKey = slots.directoryKey(), membershipPath = membership.path,
                    membershipParentKey = members.directoryKey(), membershipKey = output.observe().attributes.fileKey().toString(),
                    generation = UUID.randomUUID().toString())
                val bytes = json.encodeToString(record).toByteArray(Charsets.UTF_8)
                require(bytes.size in 1..MAX_RECORD_BYTES)
                output.write(bytes)
                checkpoint("membership-before-force")
                context.ensureActive()
                output.force(384)
                requireMembership(root, projectId, membership, workspace, slots, members, record, pending, context)
                checkpoint("membership-before-publication")
                requireMembership(root, projectId, membership, workspace, slots, members, record, pending, context)
                val outcome = members.moveNoReplaceDurable(pending, members, membership.name)
                checkpoint("membership-after-publication")
                check(outcome.durable) {
                    "Enrollment membership publication is uncertain (published=${outcome.published}, errno=${outcome.error}); retained evidence needs review"
                }
                record
            }
            fun verify() {
                check(members.kind(pending) == AnchoredWorkspaceFiles.Kind.ABSENT) { "Pending enrollment membership needs review" }
                requireMembership(root, projectId, membership, workspace, slots, members, witness, membership.name, context)
            }
            verify()
            guardedMembershipBootstrap(::verify).createGeneration(root, projectId, witness.generation, context).also { verify() }
        }

    fun readWithMembership(root: File, projectId: String, membership: File,
                           context: CoroutineContext): CanonicalFileOperationJournal.Enrollment =
        withExistingMembership(root, projectId, membership, context) { witness, verify ->
            guardedMembershipBootstrap(verify).readGeneration(root, projectId, witness.generation, context)
        }

    fun inspectWithMembership(root: File, projectId: String, membership: File,
                              context: CoroutineContext): Inspection =
        withExistingMembership(root, projectId, membership, context) { witness, verify ->
            val enrollment = guardedMembershipBootstrap(verify)
                .readGeneration(root, projectId, witness.generation, context, mode = ReadMode.INSPECT)
            val history = CanonicalFileOperationJournal(root, authority, enrollment, maxBytes).inspectRecoveryMetadata()
            Inspection(enrollment, history)
        }

    // Immutable receipts and filesystem binding only; the transaction owner validates database contents.
    fun readBindingWithMembership(root: File, projectId: String, membership: File,
                                  context: CoroutineContext): CanonicalFileOperationJournal.Enrollment =
        withExistingMembership(root, projectId, membership, context) { witness, verify ->
            guardedMembershipBootstrap(verify)
                .readGeneration(root, projectId, witness.generation, context, mode = ReadMode.BINDING_ONLY)
        }

    fun recoverWithMembership(root: File, projectId: String, membership: File,
                              context: CoroutineContext): CanonicalFileOperationJournal.Enrollment =
        withExistingMembership(root, projectId, membership, context) { witness, verify ->
            guardedMembershipBootstrap(verify).recoverGeneration(root, projectId, witness.generation, context)
        }

    private fun <T> withExistingMembership(root: File, projectId: String, membership: File, context: CoroutineContext,
                                           action: (Membership, () -> Unit) -> T): T =
        membershipPins(root, projectId, membership, context) { workspace, slots, members ->
            val witness = decode<Membership>(read(members, membership.name))
            fun verify() {
                check(members.kind(pendingMembership(membership)) == AnchoredWorkspaceFiles.Kind.ABSENT) {
                    "Enrollment membership has incomplete or competing publication; all evidence was preserved"
                }
                requireMembership(root, projectId, membership, workspace, slots, members, witness, membership.name, context)
            }
            verify()
            action(witness, ::verify).also { verify() }
        }

    private fun guardedMembershipBootstrap(verify: () -> Unit) = CanonicalJournalBootstrap(slot, maxBytes) { point ->
        checkpoint("registered/$point")
        verify()
    }

    private fun <T> membershipPins(root: File, projectId: String, membership: File, context: CoroutineContext,
                                   action: (AnchoredWorkspaceFiles, AnchoredWorkspaceFiles, AnchoredWorkspaceFiles) -> T): T {
        requireRequest(root, projectId)
        require(membership.isAbsolute && membership == membership.canonicalFile && membership.name.length in 1..120 &&
            PathSecurity.safeLeafName(membership.name) == membership.name &&
            !membership.toPath().startsWith(root.toPath()) && !membership.toPath().startsWith(slot.toPath())) {
            "Enrollment membership must be an independent host file outside the workspace and enrollment slot"
        }
        context.ensureActive()
        return AnchoredWorkspaceFiles(root).use { workspace ->
            AnchoredWorkspaceFiles(requireNotNull(slot.parentFile)).use { slots ->
                AnchoredWorkspaceFiles(requireNotNull(membership.parentFile)).use { members -> action(workspace, slots, members) }
            }
        }
    }

    private fun requireMembership(root: File, projectId: String, membership: File, workspace: AnchoredWorkspaceFiles,
                                  slots: AnchoredWorkspaceFiles, members: AnchoredWorkspaceFiles, witness: Membership,
                                  name: String, context: CoroutineContext) {
        context.ensureActive()
        check(witness.version == 1 && witness.projectId == projectId && witness.rootPath == root.path &&
            witness.rootKey == workspace.directoryKey() && witness.slotPath == slot.path &&
            witness.slotParentKey == slots.directoryKey() && witness.membershipPath == membership.path &&
            witness.membershipParentKey == members.directoryKey() && witness.membershipKey == fileKey(members, name) &&
            witness.generation == UUID.fromString(witness.generation).toString() &&
            decode<Membership>(read(members, name)) == witness) {
            "Enrollment membership is missing, foreign or changed; no replacement authority was admitted"
        }
    }

    private fun requireGeneration(actual: String, expected: String?) {
        check(expected == null || actual == expected) { "Enrollment generation differs from persistent membership; existing files were preserved" }
    }

    private fun pendingMembership(membership: File) = ".${membership.name}.pending"

    private fun createDatabase(enrollment: CanonicalFileOperationJournal.Enrollment, verify: () -> Unit) {
        val parameters = SQLiteDatabase.OpenParams.Builder()
            .setOpenFlags(SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
            .setJournalMode("DELETE").setSynchronousMode("EXTRA")
            .setErrorHandler(DatabaseErrorHandler {
                throw SQLiteDatabaseCorruptException("Enrollment database is corrupt; existing bytes were preserved")
            }).build()
        verify()
        SQLiteDatabase.openDatabase(database, parameters).use { db ->
            verify()
            db.rawQuery("PRAGMA journal_mode", null).use {
                check(it.moveToFirst() && it.getString(0).equals("delete", true))
            }
            db.rawQuery("PRAGMA synchronous", null).use { check(it.moveToFirst() && it.getInt(0) == 3) }
            check(db.version == 0) { "Enrollment database already has a schema" }
            db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { check(it.moveToFirst() && it.getLong(0) == 0L) {
                "Enrollment database contains an existing schema"
            } }
            db.beginTransaction()
            var failure: Throwable? = null
            try {
                CanonicalFileOperationJournal.SCHEMA_SQL.forEach(db::execSQL)
                db.version = 1
                db.execSQL("INSERT INTO meta VALUES(1,?,?,NULL,NULL)",
                    arrayOf(CanonicalFileOperationJournal.enrollmentHash(enrollment), enrollment.generation))
                checkpoint("schema-before-commit")
                verify()
                db.setTransactionSuccessful()
            } catch (error: Throwable) { failure = error; throw error }
            finally {
                try { db.endTransaction() }
                catch (end: Throwable) {
                    if (failure == null) throw end
                    if (failure !== end) failure.addSuppressed(end)
                }
            }
            verify()
        }
    }

    private fun requireRequest(root: File, projectId: String) {
        require(projectId.length in 1..160 && projectId.none { it.code < 32 || it.code == 127 })
        require(root.isAbsolute && root == root.canonicalFile)
        require(!slot.toPath().startsWith(root.toPath()) && !root.toPath().startsWith(slot.toPath())) {
            "Enrollment and workspace must have separate directory trees"
        }
    }

    private fun requireSlot(parent: AnchoredWorkspaceFiles, owner: AnchoredWorkspaceFiles, reservation: Reservation) {
        check(reservation.slotPath == slot.path && parent.directoryKey() == reservation.parentKey &&
            parent.directoryKey(slot.name) == reservation.slotKey && owner.directoryKey() == reservation.slotKey) {
            "Enrollment location disappeared or changed; existing data was preserved"
        }
    }

    private fun requireReservation(parent: AnchoredWorkspaceFiles, owner: AnchoredWorkspaceFiles,
                                   reservation: Reservation, root: File, projectId: String) {
        requireSlot(parent, owner, reservation)
        check(reservation.version == 1 && reservation.projectId == projectId && reservation.rootPath == root.path) {
            "Enrollment reservation is foreign or corrupt; existing evidence was preserved"
        }
    }

    private fun write(owner: AnchoredWorkspaceFiles, name: String, text: String, context: CoroutineContext) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_RECORD_BYTES)
        context.ensureActive()
        owner.createNew(name).use { output ->
            val key = output.observe().attributes.fileKey().toString()
            output.write(bytes)
            checkpoint("$name-before-force")
            context.ensureActive()
            output.force(384)
            checkpoint("$name-after-force")
            check(fileKey(owner, name) == key && read(owner, name) == text) { "Enrollment record changed while becoming durable" }
        }
    }

    private fun forceFile(owner: AnchoredWorkspaceFiles, name: String, key: String) {
        owner.openRead(name).use { input ->
            check(input.observe().attributes.fileKey().toString() == key) { "Enrollment file identity changed" }
            input.forceContents()
            owner.forceDirectory()
            check(fileKey(owner, name) == key) { "Enrollment file changed while becoming durable" }
        }
    }

    private fun fileKey(owner: AnchoredWorkspaceFiles, name: String): String = owner.openRead(name).use {
        checkNotNull(it.observe().attributes.fileKey()).toString()
    }

    private fun read(owner: AnchoredWorkspaceFiles, name: String): String = owner.openRead(name).use { input ->
        val before = input.observe()
        check(before.attributes.size() in 1..MAX_RECORD_BYTES.toLong()) { "Enrollment record is empty or oversized" }
        val bytes = ByteArray(before.attributes.size().toInt())
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            check(count > 0) { "Enrollment record was truncated" }
            offset += count
        }
        check(input.read() == -1) { "Enrollment record grew while reading" }
        val after = input.observe()
        check(before.attributes.fileKey() == after.attributes.fileKey() && before.attributes.size() == after.attributes.size() &&
            before.attributes.lastModifiedTime() == after.attributes.lastModifiedTime() && before.changed == after.changed &&
            fileKey(owner, name) == before.attributes.fileKey().toString()) { "Enrollment record changed while reading" }
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }

    private inline fun <reified T> decode(text: String): T = json.decodeFromString<T>(text).also {
        check(json.encodeToString(it) == text) { "Enrollment metadata is corrupt or unsupported" }
    }

    private fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private companion object { const val MAX_RECORD_BYTES = 16_384 }
}
