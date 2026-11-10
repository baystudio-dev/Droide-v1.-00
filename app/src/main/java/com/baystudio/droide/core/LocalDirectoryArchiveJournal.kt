package com.baystudio.droide.core

import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Append-only evidence for one exact deletion-token/destination pair. Never removes original
 * custody, releases an uncertain stage, or infers publication from current bytes. */
internal class LocalDirectoryArchiveJournal(private val root: File,
    private val checkpoint: (String) -> Unit = {}) {
    @Serializable data class Parent(val path: String, val identity: String)
    @Serializable data class Intent(val schema: Int = 1, val id: String,
        val operation: String, val preparedSha256: String, val destination: String,
        val canonicalRoot: String, val rootIdentity: String, val metadataIdentity: String,
        val homeIdentity: String, val groupIdentity: String, val recordIdentity: String,
        val lockIdentity: String, val parents: List<Parent>, val stagePath: String)
    @Serializable private data class Attempt(val schema: Int = 1, val intentHash: String,
        val preparation: StreamPublicationPreparation)
    @Serializable private data class Receipt(val schema: Int = 1, val attemptHash: String,
        val publication: StreamPublicationReceipt)
    @Serializable private data class Seal(val schema: Int = 1, val intentHash: String,
        val attemptHash: String, val receiptHash: String)
    data class Record(val intent: Intent, val receipt: StreamPublicationReceipt?, val sealed: Boolean)
    class Uncertain(val archiveId: String, message: String) : IllegalStateException(message)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }

    fun id(token: LocalDirectoryDeleteCustody.Token, destination: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(
            (token.operation + "\u0000" + token.preparedSha256 + "\u0000" + destination).toByteArray(Charsets.UTF_8))
        // A SHA-256-derived UUID fits the repository's exact planned-stage grammar. The intent also
        // binds the complete token and destination, never just this key.
        val bytes = java.nio.ByteBuffer.wrap(digest)
        return UUID(bytes.long, bytes.long).toString()
    }
    fun receiptPath(token: LocalDirectoryDeleteCustody.Token, destination: String) = "${directory(token, destination)}/receipt.json"
    private fun directory(token: LocalDirectoryDeleteCustody.Token, destination: String) = "$HOME/${token.operation}/${id(token, destination)}"

    fun inspect(token: LocalDirectoryDeleteCustody.Token, destination: String): Record? = AnchoredWorkspaceFiles(root).use { files ->
        val path = directory(token, destination)
        // Don't create recovery storage on list/inspect/review.
        if (files.kind(HOME) == AnchoredWorkspaceFiles.Kind.ABSENT ||
            files.kind("$HOME/${token.operation}") == AnchoredWorkspaceFiles.Kind.ABSENT ||
            files.kind(path) == AnchoredWorkspaceFiles.Kind.ABSENT) return null
        readRecord(files, token, destination)
    }

    data class Inspection(val status: String, val record: Record?, val detail: String)
    fun inspectStatus(token: LocalDirectoryDeleteCustody.Token, destination: String): Inspection = try {
        val record = inspect(token, destination)
        when {
            record == null -> Inspection("NOT_STARTED", null, "No archive request is recorded for this exact token and destination.")
            record.sealed -> Inspection("COMMITTED", record, "Exact historical publication receipt found. The current destination was not reverified and synchronization was not replayed.")
            else -> AnchoredWorkspaceFiles(root).use { files ->
                if (files.kind(record.intent.stagePath) == AnchoredWorkspaceFiles.Kind.ABSENT)
                    Inspection("PREPARED", record, "Archive intent exists without a publication attempt or remaining stage. A fresh approved request may capture again if the destination and parents still match.")
                else Inspection("UNKNOWN", record, "Retained staging remains without a sealed publication receipt. Preserve it; no publication or safe repeat is inferred.")
            }
        }
    } catch (failure: kotlinx.coroutines.CancellationException) { throw failure }
    catch (failure: Exception) { Inspection("UNKNOWN", null,
        "Archive evidence could not establish a complete publication receipt. Preserve ${directory(token, destination)} and the destination. ${(failure.message ?: "Unverifiable evidence").take(400)}") }

    fun parents(destination: String): List<Parent> = AnchoredWorkspaceFiles(root).use { files -> parents(files, destination) }
    private fun parents(files: AnchoredWorkspaceFiles, destination: String): List<Parent> {
        val parts = destination.split('/').dropLast(1)
        return listOf(Parent("", files.directoryKey())) + parts.indices.map { index ->
            val path = parts.take(index + 1).joinToString("/")
            Parent(path, files.directoryKey(path))
        }
    }
    fun requireParents(expected: List<Parent>, destination: String) {
        check(parents(destination) == expected) { "Recovery destination parents changed; inspect and approve again" }
    }

    fun begin(token: LocalDirectoryDeleteCustody.Token, destination: String,
        expectedParents: List<Parent>, guard: WorkspaceMutationGuard): Lease {
        guard.requireRoot(root)
        val files = AnchoredWorkspaceFiles(root)
        var input: AnchoredWorkspaceFiles.OpenedFile? = null
        var registration: Closeable? = null
        try {
            check(parents(files, destination) == expectedParents) { "Recovery destination parent changed" }
            val path = directory(token, destination)
            ensure(files, ".droide"); ensure(files, HOME); ensure(files, "$HOME/${token.operation}")
            val fresh = files.kind(path) == AnchoredWorkspaceFiles.Kind.ABSENT
            if (fresh) {
                check(files.createDirectory(path).durable) { "Archive recovery record is not durable" }
                files.createNew("$path/lock").use { it.force(384) }
            }
            val rootIdentity = files.directoryKey()
            val lockIdentity = lockKey("$path/lock")
            registration = LocalRecoveryMetadataLocks.claim(rootIdentity, lockIdentity)
            input = files.openRead("$path/lock")
            check(input.observe().attributes.fileKey().toString() == lockIdentity && input.observe().attributes.size() == 0L)
            val lease = input.acquireExclusiveMetadataLease()
            try {
                val intent = if (fresh) {
                    val stage = destination.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } + ".droide-tmp-${id(token, destination)}"
                    Intent(id = id(token, destination), operation = token.operation, preparedSha256 = token.preparedSha256,
                        destination = destination, canonicalRoot = root.canonicalPath, rootIdentity = rootIdentity,
                        metadataIdentity = files.directoryKey(".droide"), homeIdentity = files.directoryKey(HOME),
                        groupIdentity = files.directoryKey("$HOME/${token.operation}"), recordIdentity = files.directoryKey(path),
                        lockIdentity = lockIdentity, parents = expectedParents, stagePath = stage).also {
                        write(files, path, "intent.json", json.encodeToString(it)); checkpoint("intent")
                    }
                } else readRecord(files, token, destination).intent
                check(intent.parents == expectedParents)
                return Lease(files, token, intent, input, lease, registration).also { it.requireReusable() }
            } catch (failure: Throwable) { lease.close(); throw failure }
        } catch (failure: Throwable) {
            try { input?.close() } finally { try { files.close() } finally { registration?.close() } }
            throw failure
        }
    }

    inner class Lease internal constructor(private val files: AnchoredWorkspaceFiles,
        private val token: LocalDirectoryDeleteCustody.Token, val intent: Intent,
        private val input: AnchoredWorkspaceFiles.OpenedFile,
        private val lease: AnchoredWorkspaceFiles.OpenedFile.MetadataLease,
        private val registration: Closeable) : Closeable, StreamPublicationObserver, StreamStagePlan {
        override val plannedStagePath get() = intent.stagePath
        private val path = directory(token, intent.destination)
        private var closed = false
        private var attempt: Attempt? = null
        var committedReceipt: StreamPublicationReceipt? = null; private set
        fun validate() {
            check(!closed); lease.requireHeld(); validate(files, intent)
            check(input.observe().attributes.fileKey().toString() == intent.lockIdentity)
            check(read(files, "$path/intent.json") == json.encodeToString(intent)) { "Archive intent changed" }
            attempt?.let { check(read(files, "$path/attempt.json") == json.encodeToString(it)) { "Archive attempt evidence changed" } }
        }
        fun requireReusable() {
            validate()
            val record = readRecord(files, token, intent.destination)
            check(record.intent == intent && !record.sealed) { "Archive already has a committed publication receipt; inspect again" }
            if (files.kind("$path/attempt.json") != AnchoredWorkspaceFiles.Kind.ABSENT ||
                files.kind(intent.stagePath) != AnchoredWorkspaceFiles.Kind.ABSENT)
                throw Uncertain(intent.id, "Earlier archive attempt or staging remains; preserve it and inspect recovery. No archive was copied again.")
            requireParents(intent.parents, intent.destination)
            check(files.kind(intent.destination) == AnchoredWorkspaceFiles.Kind.ABSENT) { "Recovery destination is occupied; choose a new .tar name" }
        }
        override fun beforePublish(preparation: StreamPublicationPreparation) {
            validate(); check(attempt == null)
            requireParents(intent.parents, intent.destination)
            validatePreparation(intent, preparation)
            files.openRead(intent.stagePath).use {
                val observation = it.observe()
                check(observation.attributes.fileKey().toString() == preparation.stageIdentity && observation.attributes.size() == preparation.bytes)
            }
            check(files.kind(intent.destination) == AnchoredWorkspaceFiles.Kind.ABSENT)
            check(readRecord(files, token, intent.destination).intent == intent)
            attempt = Attempt(intentHash = digest(json.encodeToString(intent)), preparation = preparation)
            write(files, path, "attempt.json", json.encodeToString(attempt!!))
            validate(); checkpoint("attempt")
        }
        override fun onPublished(receipt: StreamPublicationReceipt) {
            validate()
            val admitted = checkNotNull(attempt)
            check(receipt.preparation == admitted.preparation && receipt.destinationIdentity == receipt.preparation.stageIdentity)
            validatePreparation(intent, receipt.preparation)
            check(read(files, "$path/attempt.json") == json.encodeToString(admitted))
            val record = Receipt(attemptHash = digest(json.encodeToString(admitted)), publication = receipt)
            write(files, path, "receipt.json", json.encodeToString(record)); checkpoint("receipt")
            val seal = Seal(intentHash = digest(json.encodeToString(intent)), attemptHash = digest(json.encodeToString(admitted)),
                receiptHash = digest(json.encodeToString(record)))
            write(files, path, "seal.json", json.encodeToString(seal))
            validate(); committedReceipt = receipt; checkpoint("sealed")
        }
        override fun close() {
            if (closed) return
            closed = true
            try { lease.close() } finally { try { input.close() } finally { try { files.close() } finally { registration.close() } } }
        }
    }

    private fun readRecord(files: AnchoredWorkspaceFiles, token: LocalDirectoryDeleteCustody.Token, destination: String): Record {
        val path = directory(token, destination)
        var slots = 0
        files.forEachChild(path) { check(++slots <= 5 && it in setOf("lock", "intent.json", "attempt.json", "receipt.json", "seal.json")) { "Unknown archive recovery evidence; preserve the record" } }
        val intentText = read(files, "$path/intent.json")
        val intent = json.decodeFromString<Intent>(intentText)
        check(intent.schema == 1 && intent.id == id(token, destination) && intent.operation == token.operation &&
            intent.preparedSha256 == token.preparedSha256 && intent.destination == destination)
        validate(files, intent)
        val hasAttempt = files.kind("$path/attempt.json") != AnchoredWorkspaceFiles.Kind.ABSENT
        val hasReceipt = files.kind("$path/receipt.json") != AnchoredWorkspaceFiles.Kind.ABSENT
        val hasSeal = files.kind("$path/seal.json") != AnchoredWorkspaceFiles.Kind.ABSENT
        if (!hasAttempt) {
            check(!hasReceipt && !hasSeal) { "Incomplete archive receipt chain; preserve all evidence" }
            return Record(intent, null, false)
        }
        try {
            val attemptText = read(files, "$path/attempt.json")
            val attempt = json.decodeFromString<Attempt>(attemptText)
            check(attempt.schema == 1 && attempt.intentHash == digest(intentText))
            validatePreparation(intent, attempt.preparation)
            if (!hasReceipt || !hasSeal) throw Uncertain(intent.id, "Archive publication outcome is unknown; receipt seal is missing. Do not copy again.")
            val receiptText = read(files, "$path/receipt.json")
            val receipt = json.decodeFromString<Receipt>(receiptText)
            check(receipt.schema == 1 && receipt.attemptHash == digest(attemptText) && receipt.publication.preparation == attempt.preparation &&
                receipt.publication.destinationIdentity == attempt.preparation.stageIdentity)
            val seal = json.decodeFromString<Seal>(read(files, "$path/seal.json"))
            check(seal.schema == 1 && seal.intentHash == digest(intentText) && seal.attemptHash == digest(attemptText) && seal.receiptHash == digest(receiptText))
            validate(files, intent)
            return Record(intent, receipt.publication, true)
        } catch (failure: Exception) {
            if (failure is Uncertain) throw failure
            throw Uncertain(intent.id, "Archive evidence is incomplete or changed; publication is unknown. Preserve destination and staging. ${failure.message}")
        }
    }
    private fun validate(files: AnchoredWorkspaceFiles, i: Intent) {
        val path = "$HOME/${i.operation}/${i.id}"
        check(i.canonicalRoot == root.canonicalPath && files.directoryKey() == i.rootIdentity &&
            files.directoryKey(".droide") == i.metadataIdentity && files.directoryKey(HOME) == i.homeIdentity &&
            files.directoryKey("$HOME/${i.operation}") == i.groupIdentity && files.directoryKey(path) == i.recordIdentity &&
            lockKey("$path/lock") == i.lockIdentity) { "Archive authority identity changed; preserve all evidence" }
        ProjectCopyEvidence.validateStagePath(i.destination, i.stagePath, i.id)
        check(i.parents.isNotEmpty() && i.parents.first() == Parent("", i.rootIdentity))
    }
    private fun validatePreparation(i: Intent, p: StreamPublicationPreparation) {
        check(p.canonicalRoot == i.canonicalRoot && p.rootIdentity == i.rootIdentity && p.relativePath == i.destination &&
            p.bytes >= 0 && p.sha256.matches(Regex("[a-f0-9]{64}")) && p.stageIdentity.isNotEmpty() && UUID.fromString(p.publicationId).toString() == p.publicationId)
    }
    private fun lockKey(path: String): String {
        val a = Files.readAttributes(File(root, path).toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        check(a.isRegularFile && !a.isSymbolicLink && a.size() == 0L)
        check((Files.getAttribute(File(root, path).toPath(), "unix:nlink", NOFOLLOW_LINKS) as Number).toLong() == 1L) { "Dedicated recovery lock has aliases; evidence preserved" }
        return checkNotNull(a.fileKey()).toString()
    }
    private fun ensure(files: AnchoredWorkspaceFiles, path: String) {
        if (files.kind(path) == AnchoredWorkspaceFiles.Kind.ABSENT) check(files.createDirectory(path).durable) { "Archive authority directory is not durable" }
        files.directoryKey(path)
    }
    private fun write(files: AnchoredWorkspaceFiles, directory: String, name: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8); check(bytes.size <= MAX_RECORD)
        files.createNew("$directory/$name").use { it.write(bytes); it.force(384) }
        check(read(files, "$directory/$name") == text)
        files.openDirectory(directory).use { it.forceDirectory() }
        files.forceDirectory()
    }
    private fun read(files: AnchoredWorkspaceFiles, path: String): String = files.openRead(path).use { input ->
        val before = input.observe(); check(before.attributes.size() in 1..MAX_RECORD.toLong())
        val bytes = ByteArray(before.attributes.size().toInt()); var count = 0
        while (count < bytes.size) { val n = input.read(bytes, count, bytes.size - count); check(n > 0); count += n }
        check(input.read() == -1)
        val after = input.observe()
        files.openRead(path).use { again -> check(same(before, after) && same(after, again.observe())) { "Archive evidence changed while reading" } }
        bytes.toString(Charsets.UTF_8)
    }
    private fun same(a: AnchoredWorkspaceFiles.Observation, b: AnchoredWorkspaceFiles.Observation) =
        a.attributes.fileKey() == b.attributes.fileKey() && a.attributes.size() == b.attributes.size() &&
            a.attributes.lastModifiedTime() == b.attributes.lastModifiedTime() && a.changed == b.changed && a.executable == b.executable
    private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).hex()
    companion object { const val HOME = ".droide/local-delete-archives"; private const val MAX_RECORD = 65_536 }
}
