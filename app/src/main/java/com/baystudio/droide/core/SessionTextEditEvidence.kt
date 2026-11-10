package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Scalar association only. A restored association is never permission to mutate or replay. */
@Serializable
data class AgentTextEditAssociation(
    val sessionId: String,
    val sessionCreatedAt: Long,
    val operationId: String,
    val intentDigest: String,
    val outcomeDigest: String? = null,
    val localDisposition: String? = null,
    val repositoryReturn: String? = null,
    val metadataAcknowledged: Boolean = false,
    val normalTerminal: Boolean = false,
    val projected: Boolean = false,
) {
    internal fun detail(state: AgentStreamPartState, fallback: String): String {
        if (normalTerminal && state == AgentStreamPartState.COMPLETED) return fallback +
            if (!metadataAcknowledged) " Outcome metadata acknowledgement is unverified." else ""
        val prefix = if (state == AgentStreamPartState.CANCELLED) "Cancelled" else "Interrupted"
        val fact = when (localDisposition) {
            "OWNED_CURRENT" -> "local write observed; synchronization unverified"
            "UNCHANGED_WITHOUT_WRITE" -> "no target write attempted; preparatory effects may exist"
            else -> "file publication unverified"
        }
        return "$prefix; $fact. Do not repeat automatically." +
            if (localDisposition != null && !metadataAcknowledged) " Outcome metadata acknowledgement is unverified." else ""
    }
}

/** Numeric diagnostics at real I/O boundaries; never an admission or fault-injection API. */
internal enum class TextEditIo {
    SESSION_READ, SESSION_WRITE, INDEX_WRITE, METADATA_READ,
    INTENT_CREATE, INTENT_BYTES, OUTCOME_CREATE, OUTCOME_BYTES, DIRECTORY_ENTRY,
    FILE_FORCE, DIRECTORY_FORCE,
}

/** SessionManager owns locking and session existence. This store owns only two immutable records. */
internal class SessionTextEditEvidence(
    workDir: File,
    private val durability: () -> ProjectCopyJournal.Durability,
    private val io: (TextEditIo, Long) -> Unit,
    private val limit: () -> Int = { 64 },
) {
    @Serializable data class Version(val existed: Boolean, val bytes: Long, val sha256: String) {
        fun checked() = DiskFileVersion(existed, bytes, sha256)
        fun checkedStream() = StreamContentVersion(existed, bytes, sha256)
        companion object { fun of(value: DiskFileVersion) = Version(value.existed, value.bytes, value.sha256) }
    }
    data class Owner(val sessionId: String, val runId: String, val callId: String, val partId: String, val tool: String)
    @Serializable data class Backup(val relativePath: String, val identity: String, val version: Version)
    @Serializable data class Intent(
        val version: Int = 1,
        val operationId: String,
        val operationDirectoryIdentity: String,
        val sessionId: String,
        val sessionCreatedAt: Long,
        val runId: String,
        val callId: String,
        val partId: String,
        val tool: String,
        val canonicalRoot: String,
        val rootIdentity: String,
        val path: String,
        val before: Version,
        val after: Version,
        val backup: Backup,
        val streamPreparation: StreamPublicationPreparation? = null,
        val argumentsSha256: String? = null,
    )
    @Serializable data class Outcome(
        val version: Int = 1,
        val operationId: String,
        val intentDigest: String,
        val localAttempted: Boolean,
        val writeAttempted: Boolean,
        val effect: String?,
        val returned: Boolean?,
        val successful: Boolean?,
        val sourceIdentity: String?,
        val destinationIdentity: String?,
        val disposition: String,
        val repositoryReturn: String,
    )
    data class Record(val intent: Intent, val intentDigest: String, val outcome: Outcome?,
        val outcomeDigest: String?, val problem: String? = null) {
        fun association(acknowledged: Boolean = false) = AgentTextEditAssociation(
            intent.sessionId, intent.sessionCreatedAt, intent.operationId, intentDigest, outcomeDigest,
            outcome?.disposition, outcome?.repositoryReturn, acknowledged,
        )
    }
    data class Evidence(val sessionId: String, val createdAt: Long, val records: List<Record>, val problems: List<String>)
    class Lease internal constructor(
        internal val store: SessionTextEditEvidence,
        internal val entry: SessionWriteTransactions.Entry,
        internal val invocationToken: Any,
        val intent: Intent,
        internal val intentBytes: ByteArray,
        internal val parents: List<Pair<File, String>>,
    ) {
        val intentDigest: String = ProjectCopyEvidence.sha256(intentBytes)
        internal var completedDigest: String? = null
        internal var acknowledged = false
        internal var attemptedCompletion = false
        internal var lastRecord = Record(intent, intentDigest, null, null)
    }

    private val rootBinding: Pair<File, String> = ProjectCopyEvidence.root(workDir)
    private val root: File = rootBinding.first
    private val sessions = File(root, ".droide/sessions")
    private val base = File(sessions, ".text-edits")
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }

    fun requireRoot() {
        check(ProjectCopyEvidence.root(root) == rootBinding) { "Text edit workspace identity changed" }
    }

    fun admit(entry: SessionWriteTransactions.Entry, session: DroideSession, owner: Owner,
        observation: FileRepository.TextWriteObservation, snapshot: EditHistory.Snapshot): Lease {
        requireRoot()
        require(ProjectCopyEvidence.root(observation.repository.root) == rootBinding)
        return admitPrepared(entry, session, owner, observation.path, Version.of(observation.expectedBefore),
            Version.of(observation.expectedAfter), snapshot, observation.invocationToken)
    }

    fun admitStream(entry: SessionWriteTransactions.Entry, session: DroideSession, owner: Owner,
        observation: StreamedTextEditObservation, snapshot: EditHistory.Snapshot): Lease {
        requireRoot()
        require(ProjectCopyEvidence.root(observation.repository.root) == rootBinding)
        val preparation = checkNotNull(observation.preparation)
        require(preparation.rootIdentity == rootBinding.second && preparation.canonicalRoot == root.path)
        val before = StreamContentVersion.of(observation.before)
        return admitPrepared(entry, session, owner, observation.path, Version(before.existed, before.bytes, before.sha256),
            Version(true, observation.after.bytes, observation.after.sha256), snapshot, observation.invocationToken,
            preparation, observation.argumentsSha256)
    }

    private fun admitPrepared(entry: SessionWriteTransactions.Entry, session: DroideSession, owner: Owner,
        path: String, before: Version, after: Version, snapshot: EditHistory.Snapshot, invocationToken: Any,
        streamPreparation: StreamPublicationPreparation? = null, argumentsSha256: String? = null): Lease {
        require(session.id == owner.sessionId && session.createdAt >= 0)
        validateOwner(owner)
        validatePath(path)
        require(snapshot.path == path && snapshot.existed == before.existed && snapshot.liveExpected == null)
        val backupFile = snapshot.backup.absoluteFile
        require(backupFile.parentFile == File(root, ".droide/backups")) { "Backup is outside the original workspace" }
        realDirectory(File(root, ".droide")); realDirectory(backupFile.parentFile)
        val backupIdentity = ProjectCopyEvidence.identity(backupFile)
        val backupVersion = if (streamPreparation == null) Version.of(requireNotNull(snapshot.backupExpected))
            else requireNotNull(snapshot.streamBackup).let { Version(it.existed, it.bytes, it.sha256) }
        require(backupIdentity.type == "file" && backupIdentity.size == backupVersion.bytes)
        if (before.existed) require(backupVersion == before)
        val backup = Backup(root.toPath().relativize(backupFile.toPath()).toString().replace('\\', '/'),
            backupIdentity.key, backupVersion)
        val parents = ensureParents(owner.sessionId)
        val sessionDirectory = parents.last().first
        val names = operationNames(sessionDirectory)
        check(names.size < checkedLimit()) { "Text edit evidence capacity reached; interrupted outcomes were preserved" }
        names.forEach { checkStructure(File(sessionDirectory, it), requireIntent = true) }
        requireParents(parents)
        val operationId = UUID.randomUUID().toString()
        val operationDirectory = File(sessionDirectory, operationId)
        Files.createDirectory(operationDirectory.toPath())
        val operationKey = realDirectory(operationDirectory)
        forceDirectory(sessionDirectory)
        val intent = Intent(version = if (streamPreparation == null) 1 else 2, operationId = operationId, operationDirectoryIdentity = operationKey,
            sessionId = session.id, sessionCreatedAt = session.createdAt, runId = owner.runId,
            callId = owner.callId, partId = owner.partId, tool = owner.tool,
            canonicalRoot = root.path, rootIdentity = rootBinding.second, path = path,
            before = before, after = after, backup = backup,
            streamPreparation = streamPreparation, argumentsSha256 = argumentsSha256)
        validateIntent(intent)
        val bytes = encode(intent)
        val lease = Lease(this, entry, invocationToken, intent, bytes, parents + (operationDirectory to operationKey))
        writeImmutable(lease.parents, "intent.json", bytes, intent = true)
        return lease
    }

    fun outcome(lease: Lease, observation: FileRepository.TextWriteObservation, failure: Throwable?): Outcome {
        require(lease.store === this)
        require(lease.invocationToken === observation.invocationToken && lease.intent.path == observation.path &&
            lease.intent.before.checked() == observation.expectedBefore && lease.intent.after.checked() == observation.expectedAfter &&
            observation.matchesAdmission(lease.intent.canonicalRoot, lease.intent.rootIdentity, lease.intent.operationId)) {
            "Text edit outcome belongs to another invocation"
        }
        val receipt = observation.receipt
        return Outcome(operationId = lease.intent.operationId, intentDigest = lease.intentDigest,
            localAttempted = observation.localAttempted, writeAttempted = observation.writeAttempted,
            effect = receipt?.preparation?.effect?.name, returned = receipt?.returned, successful = receipt?.successful,
            sourceIdentity = receipt?.preparation?.sourceIdentity, destinationIdentity = receipt?.destinationIdentity,
            disposition = observation.disposition.name,
            repositoryReturn = when (failure) {
                null -> "RETURNED"
                is CancellationException -> "CANCELLED"
                is ProjectFileSyncException -> "SYNC_FAILED"
                else -> "FAILED"
            })
    }

    fun streamOutcome(lease: Lease, observation: StreamedTextEditObservation, failure: Throwable?): Outcome {
        require(lease.store === this && lease.invocationToken === observation.invocationToken && lease.intent.version == 2)
        require(lease.intent.streamPreparation == observation.preparation && lease.intent.path == observation.path)
        val state = observation.settlement
        val receipt = state?.receipt
        require(receipt == null || receipt.preparation == observation.preparation)
        val current = state?.ownedCurrent == true && receipt != null
        return Outcome(operationId = lease.intent.operationId, intentDigest = lease.intentDigest,
            localAttempted = state?.attempted == true, writeAttempted = state?.attempted == true,
            effect = receipt?.let { "WRITE" }, returned = receipt?.let { true }, successful = receipt?.let { true },
            sourceIdentity = receipt?.preparation?.stageIdentity, destinationIdentity = receipt?.destinationIdentity,
            disposition = if (current) "OWNED_CURRENT" else "RECOVERY_REQUIRED",
            repositoryReturn = when (failure) { null -> "RETURNED"; is CancellationException -> "CANCELLED";
                is ProjectFileSyncException -> "SYNC_FAILED"; else -> "FAILED" })
    }

    fun complete(lease: Lease, result: Outcome): Record {
        require(lease.store === this)
        requireParents(lease.parents)
        val directory = lease.parents.last().first
        val persistedIntent = readBytes(File(directory, "intent.json"))
        check(persistedIntent.contentEquals(lease.intentBytes)) { "Original text edit intent changed or disappeared" }
        validateOutcome(lease.intent, lease.intentDigest, result)
        val bytes = encode(result)
        val digest = ProjectCopyEvidence.sha256(bytes)
        lease.completedDigest?.let { previous ->
            check(previous == digest) { "Conflicting text edit outcome" }
            val existing = readBytes(File(directory, "outcome.json"))
            check(existing.contentEquals(bytes)) { "Original text edit outcome changed or disappeared" }
            check(lease.acknowledged) { "Text edit outcome force acknowledgement is uncertain" }
            return lease.lastRecord
        }
        check(!lease.attemptedCompletion) { "Text edit outcome completion is uncertain; no automatic retry" }
        lease.attemptedCompletion = true
        lease.completedDigest = digest
        lease.lastRecord = Record(lease.intent, lease.intentDigest, result, digest,
            problem = "Outcome metadata acknowledgement is uncertain")
        writeImmutable(lease.parents, "outcome.json", bytes, intent = false)
        lease.acknowledged = true
        return Record(lease.intent, lease.intentDigest, result, digest).also { lease.lastRecord = it }
    }

    fun read(session: DroideSession): Evidence {
        val records = mutableListOf<Record>()
        val problems = mutableListOf<String>()
        try {
            requireRoot()
            val parents = existingParents(session.id) ?: return Evidence(session.id, session.createdAt, records, problems)
            val names = operationNames(parents.last().first)
            for (name in names) {
                try {
                    val directory = File(parents.last().first, name)
                    val key = realDirectory(directory)
                    requireParents(parents)
                    val children = checkStructure(directory, requireIntent = true)
                    val bytes = readBytes(File(directory, "intent.json"))
                    val intent = decodeIntent(bytes)
                    require(intent.sessionId == session.id && intent.sessionCreatedAt == session.createdAt &&
                        intent.operationId == name && intent.operationDirectoryIdentity == key) { "Text edit owner differs" }
                    val digest = ProjectCopyEvidence.sha256(bytes)
                    val record = if ("outcome.json" !in children) Record(intent, digest, null, null)
                    else try {
                        val outcomeBytes = readBytes(File(directory, "outcome.json"))
                        val outcome = decodeOutcome(outcomeBytes)
                        validateOutcome(intent, digest, outcome)
                        Record(intent, digest, outcome, ProjectCopyEvidence.sha256(outcomeBytes))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { Record(intent, digest, null, null, "Outcome evidence could not be verified") }
                    requireParents(parents + (directory to key))
                    records += record
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { problems += "A text edit operation has incomplete or invalid evidence" }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { problems += "Text edit evidence storage could not be verified" }
        return Evidence(session.id, session.createdAt, records, problems.take(64))
    }

    fun retire(session: DroideSession, savedIdentity: ProjectCopyEvidence.Identity) {
        val parts = session.messages.mapNotNull { message ->
            message.streamPart?.takeIf { it.kind == AgentStreamPartKind.TOOL && it.state == AgentStreamPartState.COMPLETED }
                ?.takeIf { part -> part.textEdit?.let { it.normalTerminal && it.metadataAcknowledged && !it.projected &&
                    it.sessionId == session.id && it.sessionCreatedAt == session.createdAt } == true }
        }.distinctBy { it.textEdit?.operationId }
        if (parts.isEmpty()) return
        try {
            val parents = existingParents(session.id) ?: return
            val occupied = operationNames(parents.last().first).toSet()
            val eligible = parts.filter { it.textEdit?.operationId in occupied }.mapNotNull { part ->
                val association = requireNotNull(part.textEdit)
                try {
                    require(ProjectCopyEvidence.UUID_PATTERN.matches(association.operationId))
                    val directory = File(parents.last().first, association.operationId)
                    if (ProjectCopyEvidence.identityOrAbsent(directory) == null) return@mapNotNull null
                    val key = realDirectory(directory)
                    checkStructure(directory, requireIntent = true)
                    val intentFile = File(directory, "intent.json")
                    val outcomeFile = File(directory, "outcome.json")
                    val intentBytes = readBytes(intentFile)
                    val intent = decodeIntent(intentBytes)
                    val intentDigest = ProjectCopyEvidence.sha256(intentBytes)
                    val outcomeBytes = readBytes(outcomeFile)
                    val outcome = decodeOutcome(outcomeBytes)
                    validateOutcome(intent, intentDigest, outcome)
                    check(intent.sessionId == session.id && intent.sessionCreatedAt == session.createdAt &&
                        intent.operationId == association.operationId && intent.operationDirectoryIdentity == key &&
                        intent.partId == part.id && intent.callId == part.toolCallId && intent.tool == part.toolName &&
                        intentDigest == association.intentDigest && ProjectCopyEvidence.sha256(outcomeBytes) == association.outcomeDigest &&
                        outcome.disposition == "OWNED_CURRENT" && outcome.repositoryReturn == "RETURNED")
                    Triple(directory, key, listOf(outcomeFile to ProjectCopyEvidence.identity(outcomeFile),
                        intentFile to ProjectCopyEvidence.identity(intentFile)))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            }
            if (eligible.isEmpty()) return
            requireParents(parents)
            val sessionFile = File(sessions, "${session.id}.json")
            val sessionIdentity = ProjectCopyEvidence.identity(sessionFile)
            check(sessionIdentity.type == "file" && sessionIdentity == savedIdentity)
            FileChannel.open(sessionFile.toPath(), READ, NOFOLLOW_LINKS).use { channel ->
                check(ProjectCopyEvidence.identity(sessionFile) == sessionIdentity)
                forceFile(channel)
                check(ProjectCopyEvidence.identity(sessionFile) == sessionIdentity)
            }
            forceDirectory(sessions)
            check(ProjectCopyEvidence.identity(sessionFile) == savedIdentity)
            requireParents(parents)
            for ((directory, key, files) in eligible) {
                try {
                    for ((file, identity) in files) {
                        requireParents(parents + (directory to key))
                        check(ProjectCopyEvidence.identity(file) == identity)
                        Files.delete(file.toPath()) // Outcome first; intent last. Never recurse.
                        forceDirectory(directory)
                    }
                    requireParents(parents + (directory to key))
                    Files.delete(directory.toPath())
                    forceDirectory(parents.last().first)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {   }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Retirement failure cannot reject an already-committed ordinary conversation. */ }
    }

    private fun checkedLimit(): Int = limit().also { require(it in 1..64) }
    private fun validateOwner(owner: Owner) {
        require(owner.sessionId.startsWith("s-") && owner.sessionId.length <= 80)
        PathSecurity.safeLeafName(owner.sessionId)
        require(ProjectCopyEvidence.UUID_PATTERN.matches(owner.runId))
        field(owner.callId, 512); field(owner.partId, 160)
        require(owner.tool in setOf("write_file", "edit_file", "write", "edit"))
    }
    private fun field(value: String, max: Int) { require(value.length in 1..max && value.none { it.code < 0x20 }) }
    private fun validatePath(value: String) {
        field(value, 4096)
        require(!File(value).isAbsolute && '\\' !in value)
        value.split('/').forEach(PathSecurity::safeLeafName)
    }
    private fun validateIntent(intent: Intent) {
        requireRoot()
        require(intent.version in 1..2 && ProjectCopyEvidence.UUID_PATTERN.matches(intent.operationId))
        validateOwner(Owner(intent.sessionId, intent.runId, intent.callId, intent.partId, intent.tool))
        field(intent.operationDirectoryIdentity, 512)
        require(intent.sessionCreatedAt >= 0 && intent.canonicalRoot == root.path && intent.rootIdentity == rootBinding.second)
        validatePath(intent.path)
        val before = intent.before.checkedStream(); val after = intent.after.checkedStream()
        require(after.existed)
        if (intent.version == 1) {
            intent.before.checked(); intent.after.checked(); intent.backup.version.checked()
            require(after.bytes <= FileRepository.MAX_TEXT_WRITE_BYTES && intent.streamPreparation == null && intent.argumentsSha256 == null)
        } else {
            val prep = requireNotNull(intent.streamPreparation)
            require(prep.canonicalRoot == intent.canonicalRoot && prep.rootIdentity == intent.rootIdentity &&
                prep.relativePath == intent.path && prep.bytes == after.bytes && prep.sha256 == after.sha256)
            require(ProjectCopyEvidence.UUID_PATTERN.matches(prep.publicationId))
            field(prep.stageIdentity, 512)
            require(intent.argumentsSha256?.matches(Regex("[a-f0-9]{64}")) == true)
        }
        validatePath(intent.backup.relativePath)
        require(intent.backup.relativePath.split('/').size == 3 && intent.backup.relativePath.startsWith(".droide/backups/"))
        field(intent.backup.identity, 512)
        val backup = intent.backup.version.checkedStream()
        require(backup.existed)
        if (before.existed) require(before == backup)
    }
    private fun validateOutcome(intent: Intent, intentDigest: String, outcome: Outcome) {
        require(outcome.version == 1 && outcome.operationId == intent.operationId && outcome.intentDigest == intentDigest)
        require(outcome.disposition in FileRepository.TextWriteDisposition.entries.map { it.name })
        require(outcome.repositoryReturn in setOf("RETURNED", "CANCELLED", "SYNC_FAILED", "FAILED"))
        require(!outcome.writeAttempted || outcome.localAttempted)
        require(outcome.effect == null || outcome.effect in setOf("CREATE_PARENTS", "WRITE"))
        require((outcome.effect == null) == (outcome.returned == null) && (outcome.effect == null) == (outcome.successful == null))
        outcome.sourceIdentity?.let { field(it, 512) }; outcome.destinationIdentity?.let { field(it, 512) }
        if (outcome.disposition == "OWNED_CURRENT") require(outcome.writeAttempted && outcome.effect == "WRITE" &&
            outcome.returned == true && outcome.successful == true && outcome.sourceIdentity != null &&
            outcome.sourceIdentity == outcome.destinationIdentity)
        if (outcome.disposition == "UNCHANGED_WITHOUT_WRITE") require(!outcome.writeAttempted)
    }
    private fun realDirectory(file: File): String {
        val identity = ProjectCopyEvidence.identity(file)
        check(identity.type == "directory") { "Text edit metadata directory is not real" }
        return identity.key
    }
    private fun requireParents(parents: List<Pair<File, String>>) {
        requireRoot()
        parents.forEach { (file, key) -> check(realDirectory(file) == key) { "Text edit metadata namespace changed" } }
    }
    private fun paths(sessionId: String): List<File> {
        PathSecurity.safeLeafName(sessionId)
        return listOf(File(root, ".droide"), sessions, base, File(base, sessionId))
    }
    private fun existingParents(sessionId: String): List<Pair<File, String>>? {
        requireRoot()
        val result = mutableListOf<Pair<File, String>>()
        for (file in paths(sessionId)) {
            if (ProjectCopyEvidence.identityOrAbsent(file) == null) return null
            result += file to realDirectory(file)
        }
        requireParents(result)
        return result
    }
    private fun ensureParents(sessionId: String): List<Pair<File, String>> {
        requireRoot()
        val result = mutableListOf<Pair<File, String>>()
        for (file in paths(sessionId)) {
            requireParents(result)
            if (ProjectCopyEvidence.identityOrAbsent(file) == null) Files.createDirectory(file.toPath())
            result += file to realDirectory(file)
        }
        // Existing normal-session mkdirs did not force ancestors. Establish reachability for this
        // authoritative intent even when those directories predate this store.
        forceDirectory(root)
        result.forEach { (file, _) -> forceDirectory(file) }
        requireParents(result)
        return result
    }
    private fun operationNames(directory: File): List<String> {
        val result = mutableListOf<String>()
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            for (entry in entries) {
                io(TextEditIo.DIRECTORY_ENTRY, 1)
                check(result.size < checkedLimit()) { "Text edit evidence capacity or structure is uncertain" }
                val name = entry.fileName.toString()
                check(ProjectCopyEvidence.UUID_PATTERN.matches(name)) { "Unexpected text edit evidence entry" }
                realDirectory(entry.toFile())
                result += name
            }
        }
        return result
    }
    private fun checkStructure(directory: File, requireIntent: Boolean): Set<String> {
        val names = linkedSetOf<String>()
        Files.newDirectoryStream(directory.toPath()).use { children ->
            for (child in children) {
                io(TextEditIo.DIRECTORY_ENTRY, 1)
                check(names.size < 2)
                val name = child.fileName.toString()
                check(name == "intent.json" || name == "outcome.json") { "Unexpected text edit record" }
                val identity = ProjectCopyEvidence.identity(child.toFile())
                check(identity.type == "file" && identity.size in 1..MAX_RECORD_BYTES)
                names += name
            }
        }
        check(!requireIntent || "intent.json" in names) { "Text edit evidence is incomplete" }
        return names
    }
    private fun writeImmutable(parents: List<Pair<File, String>>, name: String, bytes: ByteArray, intent: Boolean) {
        require(bytes.size.toLong() in 1..MAX_RECORD_BYTES)
        requireParents(parents)
        val directory = parents.last().first
        val file = File(directory, name)
        var writtenIdentity: ProjectCopyEvidence.Identity? = null
        FileChannel.open(file.toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
            io(if (intent) TextEditIo.INTENT_CREATE else TextEditIo.OUTCOME_CREATE, 1)
            val identity = ProjectCopyEvidence.identity(file)
            check(identity.type == "file")
            val buffer = ByteBuffer.wrap(bytes)
            var emptyWrites = 0
            while (buffer.hasRemaining()) {
                val count = channel.write(buffer)
                if (count == 0) check(++emptyWrites <= 16) else emptyWrites = 0
                if (count > 0) io(if (intent) TextEditIo.INTENT_BYTES else TextEditIo.OUTCOME_BYTES, count.toLong())
            }
            requireParents(parents)
            check(ProjectCopyEvidence.identity(file).key == identity.key && channel.size() == bytes.size.toLong())
            forceFile(channel)
            requireParents(parents)
            check(ProjectCopyEvidence.identity(file).key == identity.key)
            writtenIdentity = ProjectCopyEvidence.identity(file)
        }
        forceDirectory(directory)
        requireParents(parents)
        check(ProjectCopyEvidence.identity(file) == writtenIdentity)
    }
    private fun readBytes(file: File): ByteArray {
        val before = ProjectCopyEvidence.identity(file)
        check(before.type == "file" && before.size in 1..MAX_RECORD_BYTES)
        FileChannel.open(file.toPath(), READ, NOFOLLOW_LINKS).use { channel ->
            check(ProjectCopyEvidence.identity(file) == before && channel.size() == before.size)
            val buffer = ByteBuffer.allocate(before.size.toInt() + 1)
            var emptyReads = 0
            while (buffer.hasRemaining()) {
                val count = channel.read(buffer)
                if (count < 0) break
                if (count == 0) check(++emptyReads <= 16) else emptyReads = 0
                if (count > 0) io(TextEditIo.METADATA_READ, count.toLong())
            }
            check(buffer.position() == before.size.toInt() && channel.size() == before.size && ProjectCopyEvidence.identity(file) == before)
            return buffer.array().copyOf(buffer.position())
        }
    }
    private inline fun <reified T> encode(value: T): ByteArray = json.encodeToString(value).toByteArray(Charsets.UTF_8).also {
        require(it.size.toLong() in 1..MAX_RECORD_BYTES) { "Text edit metadata exceeds its bounded capacity" }
    }
    private fun decodeIntent(bytes: ByteArray): Intent = json.decodeFromString<Intent>(bytes.toString(Charsets.UTF_8)).also {
        validateIntent(it); check(encode(it).contentEquals(bytes)) { "Text edit intent is not canonical" }
    }
    private fun decodeOutcome(bytes: ByteArray): Outcome = json.decodeFromString<Outcome>(bytes.toString(Charsets.UTF_8)).also {
        check(encode(it).contentEquals(bytes)) { "Text edit outcome is not canonical" }
    }
    private fun forceFile(channel: FileChannel) { io(TextEditIo.FILE_FORCE, 1); durability().forceFile(channel) }
    private fun forceDirectory(directory: File) { io(TextEditIo.DIRECTORY_FORCE, 1); durability().forceDirectory(directory) }
    companion object { const val MAX_RECORD_BYTES = 16L * 1024 }
}

/** One call's holder survives cancellation without retaining payloads or granting replay authority. */
internal class AgentTextEditCall(
    private val sessions: SessionManager,
    private val owner: SessionTextEditEvidence.Owner,
    private val isCurrent: () -> Boolean,
    private val requireOriginalRoot: () -> Unit,
    private val publish: (SessionTextEditEvidence.Record, AgentTextEditAssociation, Boolean) -> Unit,
) {
    private var lease: SessionTextEditEvidence.Lease? = null
    var association: AgentTextEditAssociation? = null
        private set

    fun requirePreparationOwner() {
        check(lease == null && isCurrent()) { "Text edit owner is no longer current" }
        requireOriginalRoot()
        check(isCurrent()) { "Text edit owner is no longer current" }
    }

    suspend fun admit(observation: FileRepository.TextWriteObservation, backup: EditHistory.Snapshot) {
        check(lease == null && isCurrent()) { "Text edit owner is no longer current" }
        val admitted = sessions.admitTextEdit(owner, observation, backup, isCurrent)
        lease = admitted
        association = admitted.lastRecord.association()
        publish(admitted.lastRecord, requireNotNull(association), false)
        check(isCurrent()) { "Text edit owner is no longer current" }
        observation.bindAdmission(FileRepository.TextWriteAdmission(admitted.intent.canonicalRoot,
            admitted.intent.rootIdentity, admitted.intent.operationId, isCurrent))
    }

    suspend fun finish(observation: FileRepository.TextWriteObservation, failure: Throwable?): Throwable? {
        val admitted = lease ?: return null
        var metadataFailure: Throwable? = null
        try {
            withContext(NonCancellable) { sessions.completeTextEdit(admitted, observation, failure) }
        } catch (error: Throwable) { metadataFailure = error }
        if (metadataFailure != null && admitted.lastRecord.outcome == null) {
            // Keep this invocation's live fact without claiming that these bytes reached storage.
            admitted.lastRecord = SessionTextEditEvidence.Record(admitted.intent, admitted.intentDigest,
                admitted.store.outcome(admitted, observation, failure), null, "Outcome metadata acknowledgement is uncertain")
        }
        association = admitted.lastRecord.association(admitted.acknowledged)
        publish(admitted.lastRecord, requireNotNull(association), false)
        return metadataFailure
    }

    fun terminal(state: AgentStreamPartState): AgentTextEditAssociation? {
        val current = association ?: return null
        val normal = state == AgentStreamPartState.COMPLETED && current.metadataAcknowledged && current.localDisposition == "OWNED_CURRENT" &&
            current.repositoryReturn in setOf("RETURNED", "SYNC_FAILED")
        return current.copy(normalTerminal = normal).also {
            association = it
            lease?.let { admitted -> publish(admitted.lastRecord, it, true) }
        }
    }
}

internal object AgentTextEditProjection {
    fun apply(messages: List<AgentMessage>, evidence: SessionTextEditEvidence.Evidence): List<AgentMessage> {
        val result = messages.filterNot { message -> message.streamPart?.textEdit?.let {
            it.projected && it.sessionId == evidence.sessionId && it.sessionCreatedAt == evidence.createdAt
        } == true }.toMutableList()
        for (record in evidence.records) {
            val index = result.indexOfFirst { message ->
                val part = message.streamPart
                val association = part?.textEdit
                association != null && association.sessionId == evidence.sessionId && association.sessionCreatedAt == evidence.createdAt &&
                    association.operationId == record.intent.operationId && association.intentDigest == record.intentDigest &&
                    (association.outcomeDigest == null || association.outcomeDigest == record.outcomeDigest) &&
                    part.id == record.intent.partId && part.toolCallId == record.intent.callId && part.toolName == record.intent.tool
            }
            val previous = result.getOrNull(index)?.streamPart ?: messages.firstOrNull { message ->
                message.streamPart?.textEdit?.let { it.projected && it.sessionId == evidence.sessionId &&
                    it.sessionCreatedAt == evidence.createdAt && it.operationId == record.intent.operationId &&
                    it.intentDigest == record.intentDigest } == true
            }?.streamPart
            val saved = previous?.textEdit
            val exactTerminal = previous?.state == AgentStreamPartState.COMPLETED && saved?.normalTerminal == true &&
                saved.metadataAcknowledged && record.problem == null && previous.kind == AgentStreamPartKind.TOOL && saved.outcomeDigest != null &&
                saved.outcomeDigest == record.outcomeDigest && record.outcome != null &&
                record.outcome.disposition == "OWNED_CURRENT" && record.outcome.repositoryReturn in setOf("RETURNED", "SYNC_FAILED")
            val association = record.association(acknowledged = saved?.metadataAcknowledged == true &&
                saved.outcomeDigest == record.outcomeDigest && record.outcomeDigest != null && record.problem == null)
                .copy(normalTerminal = exactTerminal, projected = index < 0)
            val state = when {
                exactTerminal -> AgentStreamPartState.COMPLETED
                previous?.state == AgentStreamPartState.CANCELLED || record.outcome?.repositoryReturn == "CANCELLED" -> AgentStreamPartState.CANCELLED
                else -> AgentStreamPartState.ERROR
            }
            val part = (previous ?: AgentStreamPart("text-edit:${record.intent.operationId}", 0,
                AgentStreamPartKind.STATUS, state, title = "${record.intent.tool.replace('_', ' ')} · ${record.intent.path}"))
                .copy(state = state, textEdit = association, detail = association.detail(state, previous?.detail.orEmpty()))
            if (index >= 0) result[index] = result[index].copy(streamPart = part)
            else result += AgentMessage("assistant", "", streamPart = part)
        }
        if (evidence.problems.isNotEmpty()) {
            val id = "text-edit:unverified:${evidence.sessionId}"
            result.removeAll { it.streamPart?.id == id }
            result += AgentMessage("assistant", "", streamPart = AgentStreamPart(id, 0, AgentStreamPartKind.STATUS,
                AgentStreamPartState.ERROR, title = "Interrupted file edit", detail = "Some original file-edit evidence could not be verified. No edit was repeated."))
        }
        return result
    }
}
