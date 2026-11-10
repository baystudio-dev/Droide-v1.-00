package com.baystudio.droide.core

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Project-owned copy evidence; explicit history recovery preserves payloads and revalidates metadata. */
internal class ProjectCopyJournal(
    workDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val durability: Durability = Durability.platform,
    private val historyLimit: Int = 48,
    private val processEpoch: String = PROCESS_EPOCH,
    private val monotonicClock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val admissionClockNanos: () -> Long = System::nanoTime,
    private val inspectDestination: (File, String, String) -> ProjectCopyEvidence.Current = ProjectCopyEvidence::inspect,
) {
    @Serializable enum class Kind { AGENT_MEDIA, EXPLORER_IMPORT }
    @Serializable data class Owner(val kind: Kind, val sessionId: String?, val runId: String, val sourceId: String)
    data class Scope(val kind: Kind, val sessionId: String? = null)
    enum class Status { PREPARED, UNKNOWN, COMMITTED }

    @Serializable data class Intent(
        val version: Int = 1,
        val id: String,
        val canonicalRoot: String,
        val rootIdentity: String,
        val owner: Owner,
        val destination: String,
        val createdAt: Long,
        @OptIn(ExperimentalSerializationApi::class)
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val plannedStagePath: String? = null,
    )
    @Serializable private data class Attempt(val version: Int = 1, val intentHash: String,
        val preparation: StreamPublicationPreparation,
        @OptIn(ExperimentalSerializationApi::class)
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val stageParents: List<ProjectCopyEvidence.StageParent>? = null)
    @Serializable private data class Receipt(val version: Int = 1, val attemptHash: String,
        val publication: StreamPublicationReceipt)
    @Serializable private data class Seal(val version: Int = 1, val id: String, val intentHash: String,
        val attemptHash: String, val receiptHash: String)
    @Serializable private data class Retired(val version: Int = 1, val intent: Intent,
        val attempt: Attempt? = null, val receipt: Receipt? = null, val seal: Seal? = null)
    @Serializable private data class Retirement(val version: Int = 1, val id: String, val canonicalRoot: String,
        val rootKey: String, val metadataKey: String, val journalKey: String, val archiveKey: String,
        val recordKey: String, val evidenceHash: String)

    data class Record(
        val intent: Intent,
        val status: Status,
        val receipt: StreamPublicationReceipt?,
        val problem: String?,
        val evidenceHash: String,
        val active: Boolean,
        internal val retired: Boolean = false,
        val stagePresence: ProjectCopyEvidence.StagePresence = ProjectCopyEvidence.StagePresence.LEGACY_UNAVAILABLE,
        val preparation: StreamPublicationPreparation? = null,
        val stageParents: List<ProjectCopyEvidence.StageParent>? = null,
    ) {
        val needsStageInspection: Boolean get() = intent.plannedStagePath != null &&
            stagePresence != ProjectCopyEvidence.StagePresence.ABSENT
    }
    data class Recovery(val records: List<Record>, val blockingProblem: String?)
    data class Inspection internal constructor(val token: String, val record: Record,
        val current: ProjectCopyEvidence.Current, val expiresAt: Long,
        val stage: ProjectCopyEvidence.StageInspection? = null)
    enum class RetirementLocation { ACTIVE_HISTORY, ARCHIVE }
    data class RetirementInspection internal constructor(val token: String, val transactionId: String,
        val location: RetirementLocation, val expiresAt: Long)

    class Blocked(val transactionId: String?, message: String) : IllegalStateException(message)
    class AlreadyCommitted(val record: Record) : IllegalStateException(
        "This operation already committed historically. No file was copied or reverified now.")

    interface Durability {
        fun forceFile(channel: FileChannel)
        fun forceDirectory(directory: File)
        companion object {
            val platform = object : Durability {
                override fun forceFile(channel: FileChannel) = channel.force(true)
                override fun forceDirectory(directory: File) {
                    if (System.getProperty("java.vm.name") == "Dalvik" ||
                        System.getProperty("java.runtime.name") == "Android Runtime") {
                        val fd = Os.open(directory.absolutePath,
                            OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
                        try {
                            check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) { "Recovery storage is not a directory" }
                            Os.fsync(fd)
                        } finally { Os.close(fd) }
                    } else {
                        // Explicit host-JVM backend, never a fallback from failed Android fsync.
                        FileChannel.open(directory.toPath(), READ, NOFOLLOW_LINKS).use { it.force(true) }
                    }
                }
            }
        }
    }

    private data class Permission(val inspection: Inspection, val grantedAtNanos: Long)
    private class Shared {
        var metadataKey: String? = null
        var directoryKey: String? = null
        var retirementProblem: String? = null
        var receiptContinuation: ReceiptContinuation? = null
        val active = mutableSetOf<String>()
        val tainted = mutableSetOf<String>()
        val inspections = linkedMapOf<String, Inspection>()
        val permissions = linkedMapOf<String, Permission>()
    }
    private data class ReadRecord(val record: Record, val chain: Retired?, val identities: Map<String, String>) {
        val membershipRequired: Boolean get() = "membership.json" in identities
    }
    private data class Scan(val records: List<ReadRecord>, val problem: String?,
        val retiredMembership: List<Pair<ProjectCopyMembership.Entry, String>> = emptyList())
    private data class ReceiptContinuation(val intent: Retirement, val text: String, val identity: ProjectCopyEvidence.Identity)
    private data class PendingRetirement(val intent: Retirement, val text: String,
        val identity: ProjectCopyEvidence.Identity, val location: RetirementLocation, val record: ReadRecord,
        val fromReceipt: Boolean = false)
    private data class RetirementApproval(val inspection: RetirementInspection, val pending: PendingRetirement)
    private var retirementApproval: RetirementApproval? = null
    private val rootAndKey: Pair<File, String> = ProjectCopyEvidence.root(workDir)
    private val root: File = rootAndKey.first
    private val rootKey: String = rootAndKey.second
    private val metadata = File(root, ".droide")
    private val directory = File(root, RECOVERY_HOME)
    private val archive = File(root, ARCHIVE_HOME)
    private var membership = ProjectCopyMembership(root, rootKey, durability)
    internal var retirementCheckpoint: ((String) -> Unit)? = null
    private val shared = shared(root.path + "\u0000" + rootKey + "\u0000" + processEpoch)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }

    init { require(historyLimit in 1..48) }

    internal constructor(workDir: File, authorityHome: File) : this(workDir) {
        membership = ProjectCopyMembership(root, rootKey, durability, authorityHome)
    }

    /** A recovery permit additionally requires trusted process-local acceptance time of a NEW user
     * request, captured before queueing/picker launch, never refreshed when old work starts. */
    fun begin(owner: Owner, destination: String, requestAdmittedAtNanos: Long? = null): Lease =
        begin(owner, destination, requestAdmittedAtNanos, planStage = false)

    fun begin(owner: Owner, destination: String, requestAdmittedAtNanos: Long? = null,
        planStage: Boolean): Lease = synchronized(shared) {
        validateOwner(owner)
        ProjectCopyEvidence.validateDestination(destination)
        ensureStorage(create = true)
        expireApprovals()
        var scan = scan()
        scan.problem?.let { throw Blocked(null, it) }
        if (enrollLegacyRecords(scan)) {
            scan = scan()
            scan.problem?.let { throw Blocked(null, it) }
        }
        scan.retiredMembership.forEach { (entry, archiveKey) -> membership.retire(entry, archiveKey) }
        val exact = scan.records.firstOrNull {
            it.record.intent.owner == owner && it.record.intent.destination == destination && !it.record.retired
        }?.record
        if (exact?.status == Status.COMMITTED) throw AlreadyCommitted(exact)
        if (exact != null && exact.status == Status.PREPARED) throw Blocked(exact.intent.id,
            "This user-requested operation already prepared a copy. Make a new manual decision before trying again.")
        val conflicts = scan.records.map { it.record }.filter {
            !it.retired && (it.active || it.status == Status.UNKNOWN) && conflicts(it.intent, owner, destination)
        }
        val permissions = conflicts.map { conflict ->
            val approved = shared.permissions[conflict.intent.id]
            if (approved == null || requestAdmittedAtNanos == null ||
                requestAdmittedAtNanos <= approved.grantedAtNanos || requestAdmittedAtNanos > admissionClockNanos() ||
                approved.inspection.record.intent.owner.runId == owner.runId ||
                !sameSource(conflict.intent.owner, owner) || !approvalStillMatches(approved.inspection, conflict)) {
                throw Blocked(conflict.intent.id,
                    "An earlier copy may have published. Open recovery and inspect it before a new manual operation.")
            }
            approved.inspection
        }
        prune(scan)
        scan = scan()
        scan.problem?.let { throw Blocked(null, it) }
        check(scan.records.size < MAX_RECORDS) { "Copy recovery is full; uncertain records were preserved" }
        val now = clock()
        require(now >= 0L && now <= Long.MAX_VALUE - APPROVAL_LIFETIME_MS)
        val id = UUID.randomUUID().toString()
        val stage = if (planStage) destination.substringBeforeLast('/', "").let {
            (if (it.isEmpty()) "" else "$it/") + ".droide-tmp-$id"
        } else null
        val intent = Intent(version = if (planStage) 2 else 1, id = id, canonicalRoot = root.path, rootIdentity = rootKey,
            owner = owner, destination = destination, createdAt = now, plannedStagePath = stage)
        validateIntent(intent)
        val recordDir = File(directory, id)
        Files.createDirectory(recordDir.toPath())
        val recordDirKey = ProjectCopyEvidence.identity(recordDir).key
        durability.forceDirectory(directory)
        try {
            val witness = ProjectCopyMembership.Entry(id, recordDirKey, digest(intent))
            membership.admit(witness)
            write(recordDir, recordDirKey, "membership.json", witness)
            write(recordDir, recordDirKey, "intent.json", intent)
            permissions.forEach { shared.permissions.remove(it.record.intent.id) }
            shared.active += id
            Lease(intent, recordDirKey, permissions)
        } catch (failure: Throwable) {
            // Even partially written metadata is preserved, never treated as a successful begin.
            throw failure
        }
    }

    inner class Lease internal constructor(
        val intent: Intent,
        private val recordDirKey: String,
        private val permissions: List<Inspection>,
    ) : StreamPublicationObserver, StreamStagePlan, AutoCloseable {
        override val plannedStagePath: String? get() = intent.plannedStagePath
        private var preparation: StreamPublicationPreparation? = null
        private var admittedAttempt: Attempt? = null
        private var published: StreamPublicationReceipt? = null
        private var closed = false
        val committedReceipt: StreamPublicationReceipt? get() = synchronized(shared) { published }
        val isUncertain: Boolean get() = synchronized(shared) { intent.id in shared.tainted || preparation != null && published == null }

        override fun beforePublish(preparation: StreamPublicationPreparation) = synchronized(shared) {
            check(!closed && intent.id in shared.active && this.preparation == null) { "Copy lease is no longer active" }
            requireRoot()
            validatePreparation(intent, preparation)
            val parents = intent.plannedStagePath?.let { path ->
                val before = ProjectCopyEvidence.stageParents(root, rootKey, path)
                AnchoredWorkspaceFiles(root).use { files ->
                    files.openRead(path).use { stage ->
                        val observed = stage.observe().attributes
                        require(observed.fileKey()?.toString() == preparation.stageIdentity && observed.size() == preparation.bytes) {
                            "Planned stage does not match this copy's prepared payload"
                        }
                    }
                }
                check(ProjectCopyEvidence.stageParents(root, rootKey, path) == before) { "Planned stage parents changed" }
                before
            }
            val scan = scan()
            scan.problem?.let { throw Blocked(null, it) }
            val current = scan.records.singleOrNull { it.record.intent.id == intent.id }
                ?: throw Blocked(intent.id, "Copy intent disappeared before publication")
            check(current.record.status == Status.PREPARED && current.record.problem == null && current.record.intent == intent)
            permissions.forEach { approval ->
                val conflict = scan.records.singleOrNull { it.record.intent.id == approval.record.intent.id }?.record
                check(conflict != null && approvalStillMatches(approval, conflict)) {
                    "Recovery confirmation expired or its evidence changed; inspect again"
                }
            }

            scan.records.map { it.record }.filter {
                it.intent.id != intent.id && !it.retired && (it.active || it.status == Status.UNKNOWN) &&
                    conflicts(it.intent, intent.owner, intent.destination)
            }.forEach { conflict ->
                check(permissions.any { it.record.intent.id == conflict.intent.id }) {
                    "Another uncertain copy appeared before publication"
                }
            }
            val attempt = Attempt(version = if (parents == null) 1 else 2, intentHash = digest(intent),
                preparation = preparation, stageParents = parents)
            admittedAttempt = attempt
            this.preparation = preparation
            try { write(File(directory, intent.id), recordDirKey, "attempt.json", attempt) }
            catch (failure: Throwable) { shared.tainted += intent.id; throw failure }
        }

        override fun onPublished(receipt: StreamPublicationReceipt) = synchronized(shared) {
            check(!closed && intent.id in shared.active && intent.id !in shared.tainted && published == null)
            requireRoot()
            require(receipt.preparation == preparation && receipt.destinationIdentity == receipt.preparation.stageIdentity) {
                "Canonical publication receipt does not match this copy attempt"
            }
            validatePreparation(intent, receipt.preparation)
            val attempt = requireNotNull(admittedAttempt)
            require(attempt.preparation == receipt.preparation)
            val record = Receipt(attemptHash = digest(attempt), publication = receipt)
            val seal = Seal(id = intent.id, intentHash = digest(intent), attemptHash = digest(attempt), receiptHash = digest(record))
            try {
                val current = readRecord(File(directory, intent.id))
                check(current.record.intent == intent && current.chain?.attempt == attempt && current.record.problem == null)
                write(File(directory, intent.id), recordDirKey, "receipt.json", record)
                // Seal visibility proves only barriers preceding seal creation. Its own force is not inferred
                // from visibility. This live lease remains UNKNOWN on any exception.
                write(File(directory, intent.id), recordDirKey, "seal.json", seal)
                published = receipt
            } catch (failure: Throwable) { shared.tainted += intent.id; throw failure }
        }

        override fun close() = synchronized(shared) {
            if (!closed) { closed = true; shared.active.remove(intent.id) }
        }
    }

    fun recovery(scope: Scope): Recovery = synchronized(shared) {
        validateScope(scope)
        val scan = scan()
        Recovery(scan.records.map { it.record }.filter { !it.retired && visible(scope, it.intent.owner) }, scan.problem)
    }

    fun inspectRetirement(): RetirementInspection = synchronized(shared) {
        retirementApproval = null
        check(shared.active.isEmpty()) { "Finish or stop active copies before inspecting history" }
        val pending = readPendingRetirement()
        val now = monotonicClock()
        check(now >= 0 && now <= Long.MAX_VALUE - APPROVAL_LIFETIME_MS)
        RetirementInspection(UUID.randomUUID().toString(), pending.intent.id, pending.location,
            now + APPROVAL_LIFETIME_MS).also { retirementApproval = RetirementApproval(it, pending) }
    }

    fun finishRetirement(token: String, requireCurrent: () -> Unit = {}) = synchronized(shared) {
        val approved = retirementApproval
        retirementApproval = null
        check(approved != null && token == approved.inspection.token &&
            monotonicClock() in 0..approved.inspection.expiresAt) { "Inspect interrupted history again before confirming" }
        requireCurrent()
        check(shared.active.isEmpty()) { "Finish or stop active copies before recovering history" }
        val pending = readPendingRetirement()
        check(pending.intent == approved.pending.intent && pending.text == approved.pending.text &&
            pending.identity == approved.pending.identity && pending.location == approved.pending.location &&
            pending.fromReceipt == approved.pending.fromReceipt) {
            "Interrupted history changed; inspect again. Evidence was preserved."
        }
        val recordDir = File(if (pending.location == RetirementLocation.ACTIVE_HISTORY) directory else archive, pending.intent.id)
        retainRetiredRecord(recordDir, pending.intent.recordKey, pending.record, pending, requireCurrent)
        shared.retirementProblem = null
        shared.receiptContinuation = null
    }

    private fun readPendingRetirement(): PendingRetirement {
        ensureStorage(create = false)
        AnchoredWorkspaceFiles(metadata).use { parent ->
            AnchoredWorkspaceFiles(directory).use { active ->
                AnchoredWorkspaceFiles(archive).use { retained ->
                    val knownReceipt = shared.receiptContinuation
                    val fromReceipt = knownReceipt != null
                    if (fromReceipt) check(active.kind(RETIREMENT_PENDING) == AnchoredWorkspaceFiles.Kind.ABSENT) {
                        "Competing pending history appeared; both records were preserved"
                    }
                    val file = if (knownReceipt != null) File(archive, ".retirement-${knownReceipt.intent.id}.json")
                        else File(directory, RETIREMENT_PENDING)
                    val container = if (fromReceipt) retained else active
                    val (identity, text) = readRetirementText(file, container)
                    val intent = decode<Retirement>(text)
                    if (knownReceipt != null) check(knownReceipt.intent == intent && knownReceipt.text == text &&
                        knownReceipt.identity == identity) { "History receipt changed; evidence was preserved" }
                    check(intent.version == 1 && ProjectCopyEvidence.UUID_PATTERN.matches(intent.id) &&
                        ProjectCopyEvidence.SHA256_PATTERN.matches(intent.evidenceHash) &&
                        intent.canonicalRoot == root.path && intent.rootKey == rootKey &&
                        intent.metadataKey == shared.metadataKey && intent.journalKey == shared.directoryKey &&
                        parent.directoryKey() == intent.metadataKey && parent.directoryKey(directory.name) == intent.journalKey &&
                        active.directoryKey() == intent.journalKey && parent.directoryKey(archive.name) == intent.archiveKey &&
                        retained.directoryKey() == intent.archiveKey) { "Interrupted history belongs to different or replaced storage" }
                    check(fromReceipt || retained.kind(".retirement-${intent.id}.json") == AnchoredWorkspaceFiles.Kind.ABSENT) {
                        "History receipt already exists; competing evidence was preserved"
                    }
                    val inActive = active.kind(intent.id)
                    val inArchive = retained.kind(intent.id)
                    val location = when {
                        inActive == AnchoredWorkspaceFiles.Kind.DIRECTORY && inArchive == AnchoredWorkspaceFiles.Kind.ABSENT -> RetirementLocation.ACTIVE_HISTORY
                        inActive == AnchoredWorkspaceFiles.Kind.ABSENT && inArchive == AnchoredWorkspaceFiles.Kind.DIRECTORY -> RetirementLocation.ARCHIVE
                        else -> error("Interrupted history is missing, duplicated or unsafe; evidence was preserved")
                    }
                    check(!fromReceipt || location == RetirementLocation.ARCHIVE) { "Completed history left its archive; recovery was stopped" }
                    val recordDir = File(if (location == RetirementLocation.ACTIVE_HISTORY) directory else archive, intent.id)
                    check(ProjectCopyEvidence.identity(recordDir).key == intent.recordKey)
                    val record = readRecord(recordDir)
                    check(record.record.evidenceHash == intent.evidenceHash && record.record.retired &&
                        record.record.status == Status.COMMITTED && record.record.problem == null &&
                        !record.record.active && !record.record.needsStageInspection) {
                        "History evidence or retained copy changed; recovery was stopped"
                    }
                    requireRoot()
                    check(readRetirementText(file, container) == (identity to text))
                    check(parent.directoryKey(archive.name) == intent.archiveKey && parent.directoryKey(directory.name) == intent.journalKey)
                    return PendingRetirement(intent, text, identity, location, record, fromReceipt)
                }
            }
        }
    }

    fun inspect(scope: Scope, transactionId: String): Inspection = synchronized(shared) {
        validateScope(scope)
        expireApprovals()
        val scan = scan()
        scan.problem?.let { throw Blocked(null, it) }
        val record = scan.records.singleOrNull { it.record.intent.id == transactionId }?.record
            ?: throw Blocked(transactionId, "Recovery transaction is unavailable")
        check(visible(scope, record.intent.owner) && !record.retired && !record.active) { "Recovery belongs to another active owner" }
        check((record.status == Status.UNKNOWN || record.needsStageInspection) && record.problem == null) {
            "Only intact uncertain copies or retained payloads can be inspected"
        }
        check(shared.inspections.size < MAX_APPROVALS) { "Too many recovery inspections are open" }
        val current = if (record.status == Status.UNKNOWN) inspectDestination(root, rootKey, record.intent.destination)
            else ProjectCopyEvidence.inspectMetadata(root, rootKey, record.intent.destination)
        val stage = record.intent.plannedStagePath?.let {
            ProjectCopyEvidence.inspectStage(root, rootKey, it, record.preparation, record.stageParents)
        }
        val now = monotonicClock()
        require(now <= Long.MAX_VALUE - APPROVAL_LIFETIME_MS)
        Inspection(UUID.randomUUID().toString(), record, current, now + APPROVAL_LIFETIME_MS, stage).also {
            shared.inspections[it.token] = it
        }
    }

    /** Only permits one future user-requested run/picker operation. Never performs that operation. */
    fun acknowledge(scope: Scope, inspectionToken: String,
        commitAdmission: (grant: () -> Unit) -> Unit = { grant -> grant() },
    ) = synchronized(shared) {
        validateScope(scope)
        expireApprovals()
        val inspection = shared.inspections.remove(inspectionToken)
            ?: throw Blocked(null, "Recovery inspection expired; inspect again")
        check(visible(scope, inspection.record.intent.owner)) { "Recovery owner changed" }
        val scan = scan()
        scan.problem?.let { throw Blocked(null, it) }
        val record = scan.records.singleOrNull { it.record.intent.id == inspection.record.intent.id }?.record
        check(record != null && approvalStillMatches(inspection, record)) { "Recovery evidence changed; inspect again" }
        check(shared.permissions.size < MAX_APPROVALS)
        // All filesystem work is complete. Trusted owner admission must synchronously invoke grant
        // exactly once on this thread; it may hold its lifecycle lock only around grant.
        val grantThread = Thread.currentThread()
        var accepting = true
        var granted = false
        try {
            commitAdmission {
                check(Thread.currentThread() === grantThread && accepting && !granted) { "Recovery grant must be synchronous and single-use" }
                check(monotonicClock() in (inspection.expiresAt - APPROVAL_LIFETIME_MS)..inspection.expiresAt) { "Recovery inspection expired; inspect again" }
                check(shared.permissions.size < MAX_APPROVALS)
                shared.permissions[record.intent.id] = Permission(inspection, admissionClockNanos())
                granted = true
            }
            check(granted) { "Recovery permission was not admitted by its current owner" }
        } finally { accepting = false }
    }

    private fun approvalStillMatches(inspection: Inspection, record: Record): Boolean {
        if (inspection.stage?.status == ProjectCopyEvidence.StageStatus.UNSAFE) return false
        if (monotonicClock() !in (inspection.expiresAt - APPROVAL_LIFETIME_MS)..inspection.expiresAt || record.active ||
            record.status != Status.UNKNOWN || record.problem != null || record.evidenceHash != inspection.record.evidenceHash ||
            record.intent != inspection.record.intent) return false
        return inspectDestination(root, rootKey, record.intent.destination) == inspection.current &&
            (record.intent.plannedStagePath?.let {
                ProjectCopyEvidence.inspectStage(root, rootKey, it, record.preparation, record.stageParents)
            }) == inspection.stage
    }

    private fun expireApprovals() {
        val now = monotonicClock()
        shared.inspections.entries.removeAll { now !in (it.value.expiresAt - APPROVAL_LIFETIME_MS)..it.value.expiresAt }
        shared.permissions.entries.removeAll { now !in (it.value.inspection.expiresAt - APPROVAL_LIFETIME_MS)..it.value.inspection.expiresAt }
    }

    private fun sameSource(left: Owner, right: Owner) = left.kind == right.kind &&
        left.sessionId == right.sessionId && left.sourceId == right.sourceId
    private fun conflicts(intent: Intent, owner: Owner, path: String): Boolean = sameSource(intent.owner, owner) ||
        path == intent.destination || path.startsWith(intent.destination + "/") || intent.destination.startsWith(path + "/")
    private fun visible(scope: Scope, owner: Owner) = scope.kind == owner.kind && scope.sessionId == owner.sessionId

    private fun validateScope(scope: Scope) {
        if (scope.kind == Kind.AGENT_MEDIA) requireToken(requireNotNull(scope.sessionId)) else require(scope.sessionId == null)
    }
    private fun validateOwner(owner: Owner) {
        validateScope(Scope(owner.kind, owner.sessionId))
        requireToken(owner.runId)
        requireToken(owner.sourceId)
        if (owner.kind == Kind.EXPLORER_IMPORT) require(ProjectCopyEvidence.SHA256_PATTERN.matches(owner.sourceId))
    }
    private fun requireToken(value: String) {
        require(value.length in 1..160 && value.none { it.code < 0x20 || it.code == 0x7f || it in "/\\" })
    }
    private fun validateIntent(intent: Intent) {
        require(intent.version in 1..2 && ProjectCopyEvidence.UUID_PATTERN.matches(intent.id) && intent.createdAt >= 0L)
        require(intent.canonicalRoot == root.path && intent.rootIdentity == rootKey) { "Copy journal belongs to a different project identity" }
        validateOwner(intent.owner)
        ProjectCopyEvidence.validateDestination(intent.destination)
        if (intent.version == 1) require(intent.plannedStagePath == null) { "Legacy intent cannot declare a stage" }
        else ProjectCopyEvidence.validateStagePath(intent.destination, requireNotNull(intent.plannedStagePath), intent.id)
    }
    private fun validatePreparation(intent: Intent, value: StreamPublicationPreparation) {
        require(ProjectCopyEvidence.UUID_PATTERN.matches(value.publicationId) && value.canonicalRoot == root.path &&
            value.rootIdentity == rootKey && value.relativePath == intent.destination &&
            value.bytes in 0..ProjectCopyEvidence.MAX_COPY_BYTES && ProjectCopyEvidence.SHA256_PATTERN.matches(value.sha256) &&
            value.stageIdentity.length in 1..512 && value.stageIdentity.none { it.code < 0x20 }) {
            "Publication evidence does not match the copy's project, destination or staged bytes"
        }
    }

    private fun requireRoot() {
        check(ProjectCopyEvidence.root(root) == (root to rootKey)) { "Project root was replaced; recovery is blocked" }
    }
    private fun ensureStorage(create: Boolean) {
        requireRoot()
        membership.verifyStorage()
        if (ProjectCopyEvidence.identityOrAbsent(metadata) == null) {
            check(shared.metadataKey == null && shared.directoryKey == null) { "Copy journal metadata disappeared" }
            if (!create) return
            Files.createDirectory(metadata.toPath())
        }
        val metadataIdentity = ProjectCopyEvidence.identity(metadata)
        check(metadataIdentity.type == "directory" && (shared.metadataKey == null || shared.metadataKey == metadataIdentity.key)) {
            "Unsafe or replaced project metadata directory"
        }
        shared.metadataKey = metadataIdentity.key
        if (ProjectCopyEvidence.identityOrAbsent(directory) == null) {
            check(shared.directoryKey == null) { "Copy journal disappeared; existing recovery ownership is unknown" }
            if (!create) return
            Files.createDirectory(directory.toPath())
        }
        val actual = ProjectCopyEvidence.identity(directory)
        check(actual.type == "directory" && (shared.directoryKey == null || shared.directoryKey == actual.key)) { "Copy journal identity changed" }
        shared.directoryKey = actual.key
        if (create) {
            // A previous mkdir may have survived a failed parent force. Re-establish the complete
            // namespace's reachability before every new durable intent.
            durability.forceDirectory(root)
            durability.forceDirectory(metadata)
            requireRoot()
            check(ProjectCopyEvidence.identity(metadata).key == shared.metadataKey &&
                ProjectCopyEvidence.identity(directory).key == shared.directoryKey)
        }
    }

    private fun scan(): Scan {
        return try {
            ensureStorage(create = false)
            requireRetirementIdle()
            if (ProjectCopyEvidence.identityOrAbsent(directory) == null) return Scan(emptyList(), null)
            val records = mutableListOf<ReadRecord>()
            var bytes = 0L
            Files.newDirectoryStream(directory.toPath()).use { stream ->
                var entries = 0
                for (path in stream) {
                    check(++entries <= MAX_RECORDS) { "Copy journal entry budget exceeded; all evidence was preserved" }
                    val file = path.toFile()
                    check(ProjectCopyEvidence.UUID_PATTERN.matches(file.name) && ProjectCopyEvidence.identity(file).type == "directory") {
                        "Copy journal has unknown ownership; all evidence was preserved"
                    }
                    val children = boundedChildren(file)
                    check(children.isNotEmpty()) {
                        "Copy recovery record is empty (${file.name}); its history cannot be verified. " +
                            "Existing work was preserved. Inspect recovery before a new copy."
                    }
                    bytes += children.sumOf { minOf(ProjectCopyEvidence.identity(it).size, MAX_FILE_BYTES + 1) }
                    check(bytes <= MAX_TOTAL_BYTES) { "Copy journal metadata budget exceeded" }
                    records += readRecord(file, children)
                }
            }
            // A whole record can disappear without changing the journal directory identity. Retain only
            // knowledge already held by this process; this is not a restart ledger.
            val observedIds = records.mapTo(mutableSetOf()) { it.record.intent.id }
            val missingKnownId = (shared.active + shared.tainted).filter { it !in observedIds }.minOrNull()
            check(missingKnownId == null) {
                "Known copy recovery record disappeared ($missingKnownId); existing work was preserved. " +
                    "Recovery must be inspected before a new copy."
            }
            val entries = records.associate { record ->
                val intent = record.record.intent
                val entry = ProjectCopyMembership.Entry(intent.id,
                    ProjectCopyEvidence.identity(File(directory, intent.id)).key, digest(intent))
                if (record.membershipRequired) {
                    val sidecar = File(directory, "${intent.id}/membership.json")
                    check(decode<ProjectCopyMembership.Entry>(readText(sidecar, ProjectCopyEvidence.identity(sidecar))) == entry) {
                        "Copy membership sidecar changed (${intent.id}); recovery is required before another copy"
                    }
                }
                intent.id to entry
            }
            val required = records.filter { it.membershipRequired }.mapTo(mutableSetOf()) { it.record.intent.id }
            val retiredMembership = membership.verify(entries, required, ::verifyRetiredMembership)
            Scan(records, null, retiredMembership)
        } catch (failure: Exception) {
            Scan(emptyList(), (failure.message ?: "Copy recovery could not be read").take(300))
        }
    }

    private fun enrollLegacyRecords(scan: Scan): Boolean {
        val legacy = scan.records.filterNot { it.membershipRequired }
        legacy.forEach { record ->
            val recordDir = File(directory, record.record.intent.id)
            val key = ProjectCopyEvidence.identity(recordDir).key
            check(readRecord(recordDir).record.evidenceHash == record.record.evidenceHash) {
                "Legacy copy recovery changed before enrollment; existing work was preserved"
            }
            val witness = ProjectCopyMembership.Entry(record.record.intent.id, key, digest(record.record.intent))
            membership.admit(witness)
            check(readRecord(recordDir).record.evidenceHash == record.record.evidenceHash) {
                "Legacy copy recovery changed during enrollment; inspect retained membership evidence"
            }
            write(recordDir, key, "membership.json", witness)
        }
        return legacy.isNotEmpty()
    }

    private fun verifyRetiredMembership(entry: ProjectCopyMembership.Entry): String {
        val recordDir = File(archive, entry.id)
        check(ProjectCopyEvidence.identityOrAbsent(recordDir)?.let { it.type == "directory" && it.key == entry.recordKey } == true) {
            "Admitted copy record disappeared (${entry.id}); existing work was preserved. Inspect recovery before a new copy."
        }
        val read = readRecord(recordDir)
        check(read.membershipRequired) { "Retired copy membership sidecar is missing" }
        val record = read.record
        check(digest(record.intent) == entry.intentHash && record.retired && record.status == Status.COMMITTED &&
            record.problem == null && !record.active && !record.needsStageInspection) { "Retired copy membership cannot be verified" }
        AnchoredWorkspaceFiles(archive).use { retained ->
            val file = File(archive, ".retirement-${entry.id}.json")
            val (identity, text) = readRetirementText(file, retained)
            val receipt = decode<Retirement>(text)
            check(receipt.version == 1 && receipt.id == entry.id && receipt.recordKey == entry.recordKey &&
                receipt.canonicalRoot == root.path && receipt.rootKey == rootKey &&
                receipt.metadataKey == shared.metadataKey && receipt.journalKey == shared.directoryKey &&
                receipt.archiveKey == retained.directoryKey() && receipt.evidenceHash == record.evidenceHash) {
                "Retired copy membership has no matching durable history receipt"
            }
            check(readRetirementText(file, retained) == (identity to text)) { "Retired copy membership changed" }
            requireRoot()
            return receipt.archiveKey
        }
    }

    private fun boundedChildren(recordDir: File): List<File> {
        val children = mutableListOf<File>()
        Files.newDirectoryStream(recordDir.toPath()).use { stream ->
            for (path in stream) {
                check(children.size < 6) { "Unexpected copy journal entries" }
                children += path.toFile()
            }
        }
        return children.sortedBy { it.name }
    }

    private fun readRecord(recordDir: File, children: List<File> = boundedChildren(recordDir)): ReadRecord {
        val directoryIdentities = captureDirectoryIdentities(recordDir)
        val identities = linkedMapOf<String, String>()
        val contents = linkedMapOf<String, String>()
        var validIntent: Intent? = null
        try {
            children.forEach { file ->
                val identity = ProjectCopyEvidence.identity(file)
                require(identity.type == "file" && identity.size in 1..MAX_FILE_BYTES) { "Invalid copy journal file" }
                identities[file.name] = identity.key
                contents[file.name] = readText(file, identity)
            }
            require(captureDirectoryIdentities(recordDir) == directoryIdentities) { "Copy journal directory identity changed while reading" }
            if ("retired.json" in contents) {
                val terminal = decode<Retired>(contents.getValue("retired.json"))
                validateIntent(terminal.intent)
                require(recordDir.name == terminal.intent.id)
                validIntent = terminal.intent
                validateChain(terminal, requireTerminal = true)
                require(contents.keys.all { it in FILE_NAMES })
                validateExistingParts(contents, terminal)
                return readResult(terminal, contents, identities, directoryIdentities, true)
            }
            val intent = decode<Intent>(contents.getValue("intent.json"))
            validateIntent(intent)
            require(recordDir.name == intent.id)
            validIntent = intent
            require(contents.keys.all { it in FILE_NAMES - "retired.json" }) { "Unknown copy journal file" }
            val chain = Retired(intent = intent,
                attempt = contents["attempt.json"]?.let { decode<Attempt>(it) },
                receipt = contents["receipt.json"]?.let { decode<Receipt>(it) },
                seal = contents["seal.json"]?.let { decode<Seal>(it) })
            validateChain(chain, requireTerminal = false)
            return readResult(chain, contents, identities, directoryIdentities, false)
        } catch (failure: Exception) {
            // Re-read only the bounded independent intent if an unrelated child was corrupt.
            if (validIntent == null) validIntent = try {
                val file = File(recordDir, "intent.json")
                val identity = ProjectCopyEvidence.identity(file)
                decode<Intent>(readText(file, identity)).also { validateIntent(it); require(it.id == recordDir.name) }
            } catch (_: Exception) { null }
            val intent = validIntent ?: throw Blocked(recordDir.name,
                "Copy journal ownership is unreadable (${recordDir.name}); evidence was preserved")
            return ReadRecord(Record(intent, Status.UNKNOWN, null,
                (failure.message ?: "Corrupt copy recovery record").take(300), fingerprint(contents, identities, directoryIdentities),
                intent.id in shared.active, retired = false), null, identities)
        }
    }

    private fun validateChain(chain: Retired, requireTerminal: Boolean) {
        require(chain.version == 1)
        validateIntent(chain.intent)
        chain.attempt?.let {
            require(it.version == chain.intent.version && it.intentHash == digest(chain.intent))
            validatePreparation(chain.intent, it.preparation)
            if (it.version == 1) require(it.stageParents == null)
            else {
                val segments = listOf("") + chain.intent.destination.split('/').dropLast(1)
                val recorded = requireNotNull(it.stageParents)
                require(recorded.map { entry -> entry.segment } == segments && recorded.first().identity == chain.intent.rootIdentity)
                require(recorded.all { entry -> entry.identity.length in 1..512 && entry.identity.none { char -> char.code < 0x20 } })
            }
        }
        chain.receipt?.let {
            require(it.version == 1 && chain.attempt != null && it.attemptHash == digest(chain.attempt))
            require(it.publication.preparation == chain.attempt.preparation &&
                it.publication.destinationIdentity == chain.attempt.preparation.stageIdentity)
        }
        chain.seal?.let {
            require(it.version == 1 && chain.attempt != null && chain.receipt != null && it.id == chain.intent.id &&
                it.intentHash == digest(chain.intent) && it.attemptHash == digest(chain.attempt) && it.receiptHash == digest(chain.receipt))
        }
        if (requireTerminal) require(chain.attempt == null && chain.receipt == null && chain.seal == null || chain.seal != null)
    }

    private fun validateExistingParts(contents: Map<String, String>, chain: Retired) {
        contents["intent.json"]?.let { require(it == encode(chain.intent)) }
        contents["attempt.json"]?.let { require(chain.attempt != null && it == encode(chain.attempt)) }
        contents["receipt.json"]?.let { require(chain.receipt != null && it == encode(chain.receipt)) }
        contents["seal.json"]?.let { require(chain.seal != null && it == encode(chain.seal)) }
    }

    private fun readResult(chain: Retired, contents: Map<String, String>, identities: Map<String, String>,
        directoryIdentities: List<String>, retired: Boolean): ReadRecord {
        contents["membership.json"]?.let { text ->
            check(decode<ProjectCopyMembership.Entry>(text) == ProjectCopyMembership.Entry(
                chain.intent.id, directoryIdentities.last(), digest(chain.intent))) { "Copy membership sidecar changed" }
        }
        val stagePresence = ProjectCopyEvidence.stagePresence(root, rootKey, chain.intent.plannedStagePath, chain.attempt?.stageParents)
        val retainedStage = chain.intent.plannedStagePath != null && stagePresence != ProjectCopyEvidence.StagePresence.ABSENT
        val unsealedRetirement = retired && chain.seal == null
        val status = when {
            chain.intent.id in shared.tainted || unsealedRetirement -> Status.UNKNOWN
            chain.seal != null -> Status.COMMITTED
            chain.attempt != null -> Status.UNKNOWN
            else -> Status.PREPARED
        }
        return ReadRecord(Record(chain.intent, status, chain.receipt?.publication?.takeIf { status == Status.COMMITTED },
            null, fingerprint(contents, identities, directoryIdentities), chain.intent.id in shared.active,
            retired && !unsealedRetirement && !retainedStage, stagePresence, chain.attempt?.preparation,
            chain.attempt?.stageParents), chain, identities)
    }

    private fun captureDirectoryIdentities(recordDir: File): List<String> = listOf(metadata, directory, recordDir).map {
        ProjectCopyEvidence.identity(it).also { identity -> require(identity.type == "directory") }.key
    }

    private fun fingerprint(contents: Map<String, String>, identities: Map<String, String>, directories: List<String>): String =
        ProjectCopyEvidence.sha256((directories.joinToString("\n") + "\n" +
            contents.toSortedMap().entries.joinToString("\n") {
                "${it.key}:${identities[it.key]}:${ProjectCopyEvidence.sha256(it.value.toByteArray(Charsets.UTF_8))}"
            }).toByteArray(Charsets.UTF_8))

    private fun readText(file: File, before: ProjectCopyEvidence.Identity): String {
        require(before.type == "file" && before.size in 1..MAX_FILE_BYTES)
        val bytes = ByteArray(before.size.toInt())
        FileChannel.open(file.toPath(), READ, NOFOLLOW_LINKS).use { channel ->
            require(channel.size() == before.size && ProjectCopyEvidence.identity(file) == before)
            val buffer = ByteBuffer.wrap(bytes)
            var emptyReads = 0
            while (buffer.hasRemaining()) {
                val count = channel.read(buffer)
                require(count >= 0) { "Truncated copy journal" }
                if (count == 0) require(++emptyReads <= 16) else emptyReads = 0
            }
            require(channel.read(ByteBuffer.allocate(1)) == -1 && ProjectCopyEvidence.identity(file) == before)
        }
        return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    }

    private inline fun <reified T> encode(value: T): String = json.encodeToString(value)
    private inline fun <reified T> digest(value: T) = ProjectCopyEvidence.sha256(encode(value).toByteArray(Charsets.UTF_8))
    private inline fun <reified T> decode(text: String): T = json.decodeFromString<T>(text).also {
        require(encode(it) == text) { "Copy journal is not canonical strict-schema JSON" }
    }

    private inline fun <reified T> write(recordDir: File, recordDirKey: String, name: String, value: T) {
        ensureStorage(create = false)
        val bytes = encode(value).toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() in 1..MAX_FILE_BYTES)
        require(recordDir.parentFile == directory)
        AnchoredWorkspaceFiles(directory).use { parent ->
            check(parent.directoryKey() == shared.directoryKey) { "Copy journal directory changed" }
            parent.openDirectory(recordDir.name).use { record ->
                check(record.directoryKey() == recordDirKey) { "Copy journal record identity changed" }
                record.createNew(name).use { output ->
                    val key = output.observe().attributes.fileKey().toString()
                    output.write(bytes)
                    output.openWriteChannel().use { durability.forceFile(it) }
                    check(ProjectCopyEvidence.identity(File(recordDir, name)).key == key) { "Copy journal file identity changed" }
                }
                record.forceDirectory()
                if (durability !== Durability.platform) durability.forceDirectory(recordDir)
                check(parent.directoryKey(recordDir.name) == recordDirKey && record.directoryKey() == recordDirKey) {
                    "Copy journal record changed while becoming durable"
                }
            }
        }
        requireRoot()
    }

    /** Only sealed commits are terminal; a missing attempt cannot exclude retained payloads. */
    private fun prune(scan: Scan) {
        val terminal = scan.records.filter { !it.record.active && it.record.problem == null &&
            it.record.intent.id !in shared.tainted && it.record.status == Status.COMMITTED && !it.record.needsStageInspection }
        val remove = terminal.filter { it.record.retired } + terminal.filter { !it.record.retired }
            .sortedByDescending { it.record.intent.createdAt }.drop(historyLimit - 1)
        for (record in remove.distinctBy { it.record.intent.id }) {
            val recordDir = File(directory, record.record.intent.id)
            val identity = ProjectCopyEvidence.identity(recordDir)
            val current = readRecord(recordDir)
            require(current.record.intent == record.record.intent && current.record.problem == null && !current.record.active &&
                current.record.status == Status.COMMITTED && !current.record.needsStageInspection)
            if (!current.record.retired) {
                require(current.record.evidenceHash == record.record.evidenceHash)
                write(recordDir, identity.key, "retired.json", requireNotNull(current.chain))
            }
            val retired = readRecord(recordDir)
            check(retired.record.retired && retired.record.problem == null && retired.record.status == Status.COMMITTED &&
                !retired.record.needsStageInspection)
            retainRetiredRecord(recordDir, identity.key, retired)
        }
    }

    private fun retainRetiredRecord(recordDir: File, recordKey: String, retired: ReadRecord,
        resume: PendingRetirement? = null, requireCurrent: () -> Unit = {}) {
        var completedReceipt = shared.receiptContinuation
        try {
            requireCurrent()
            ensureStorage(create = false)
            if (resume == null) requireRetirementIdle()
            AnchoredWorkspaceFiles(metadata).use { parent ->
                when (parent.kind(archive.name)) {
                    AnchoredWorkspaceFiles.Kind.ABSENT -> {
                        check(resume == null) { "History archive disappeared; recovery was stopped" }
                        check(parent.createDirectory(archive.name).durable) { "Copy history archive creation was not confirmed" }
                    }
                    AnchoredWorkspaceFiles.Kind.DIRECTORY -> Unit
                    else -> error("Copy history archive is not an ordinary directory")
                }
                parent.forceDirectory()
                AnchoredWorkspaceFiles(directory).use { active ->
                    AnchoredWorkspaceFiles(archive).use { retained ->
                        val archiveKey = retained.directoryKey()
                        fun requireParents() {
                            requireCurrent()
                            requireRoot()
                            check(parent.directoryKey() == shared.metadataKey &&
                                parent.directoryKey(directory.name) == shared.directoryKey &&
                                active.directoryKey() == shared.directoryKey &&
                                parent.directoryKey(archive.name) == archiveKey && retained.directoryKey() == archiveKey) {
                                "Copy retirement storage changed"
                            }
                        }
                        fun requireRecord(file: File) {
                            requireParents()
                            check(ProjectCopyEvidence.identity(file).key == recordKey)
                            val current = readRecord(file).record
                            check(current.evidenceHash == retired.record.evidenceHash && current.retired &&
                                current.status == Status.COMMITTED && current.problem == null &&
                                !current.active && !current.needsStageInspection) { "Retirement evidence or stage state changed" }
                        }
                        fun checkpoint(phase: String) {
                            requireCurrent()
                            retirementCheckpoint?.invoke(if (resume == null) phase else "recovery-$phase")
                            requireCurrent()
                        }
                        val intent = resume?.intent ?: Retirement(id = recordDir.name, canonicalRoot = root.path, rootKey = rootKey,
                            metadataKey = requireNotNull(shared.metadataKey), journalKey = requireNotNull(shared.directoryKey),
                            archiveKey = archiveKey, recordKey = recordKey, evidenceHash = retired.record.evidenceHash)
                        check(intent.archiveKey == archiveKey && intent.journalKey == shared.directoryKey &&
                            intent.metadataKey == shared.metadataKey && intent.rootKey == rootKey)
                        val text = resume?.text ?: encode(intent)
                        val bytes = text.toByteArray(Charsets.UTF_8)
                        check(bytes.size.toLong() in 1..MAX_FILE_BYTES)
                        val fromReceipt = resume?.fromReceipt == true
                        val receipt = ".retirement-${intent.id}.json"
                        val intentFile = if (fromReceipt) File(archive, receipt) else File(directory, RETIREMENT_PENDING)
                        val intentContainer = if (fromReceipt) retained else active
                        val pending = if (resume == null) active.createNew(RETIREMENT_PENDING)
                            else intentContainer.openRead(intentFile.name)
                        pending.use {
                            val pendingKey = pending.observe().attributes.fileKey().toString()
                            fun requireIntent(file: File, container: AnchoredWorkspaceFiles): ProjectCopyEvidence.Identity {
                                requireParents()
                                val (observed, currentText) = readRetirementText(file, container)
                                check(observed.key == pendingKey && currentText == text &&
                                    (resume == null || observed == resume.identity)) { "Copy retirement intent changed; evidence was retained" }
                                return observed
                            }
                            if (resume == null) {
                                pending.write(bytes)
                                pending.openWriteChannel().use { durability.forceFile(it) }
                            } else {
                                requireIntent(intentFile, intentContainer)
                                pending.forceContents()
                            }
                            active.forceDirectory()
                            if (fromReceipt) retained.forceDirectory()
                            checkpoint(if (fromReceipt) "receipt-durable" else "intent-durable")
                            requireIntent(intentFile, intentContainer)
                            if (resume?.location != RetirementLocation.ARCHIVE) {
                                requireRecord(File(directory, intent.id))
                                checkpoint("before-record-transfer")
                                active.moveNoReplace(intent.id, retained, intent.id)
                                checkpoint("record-transferred")
                            }
                            if (!fromReceipt) {
                                durability.forceDirectory(directory)
                                durability.forceDirectory(archive)
                            }
                            requireRecord(File(archive, intent.id))
                            check(active.kind(intent.id) == AnchoredWorkspaceFiles.Kind.ABSENT) { "Competing active history appeared" }
                            if (!fromReceipt) {
                                checkpoint("before-intent-transfer")
                                val identity = requireIntent(File(directory, RETIREMENT_PENDING), active)
                                active.moveNoReplace(RETIREMENT_PENDING, retained, receipt)
                                completedReceipt = ReceiptContinuation(intent, text, identity)
                                checkpoint("intent-transferred")
                                durability.forceDirectory(directory)
                                durability.forceDirectory(archive)
                            }
                            check(active.kind(RETIREMENT_PENDING) == AnchoredWorkspaceFiles.Kind.ABSENT) { "Competing pending history appeared" }
                            requireIntent(File(archive, receipt), retained)
                            requireRecord(File(archive, intent.id))
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            shared.receiptContinuation = completedReceipt
            shared.retirementProblem = if (completedReceipt != null)
                "Copy history completion needs verification. Preserved receipt: .droide/copy-recovery-archive/.retirement-${completedReceipt.intent.id}.json."
            else "Copy history retirement needs recovery. Preserved: .droide/copy-recovery/$RETIREMENT_PENDING and .droide/copy-recovery-archive/${recordDir.name}."
            if (failure is java.util.concurrent.CancellationException || failure !is Exception) throw failure
            throw Blocked(null, shared.retirementProblem + " " + failure.message.orEmpty().take(160)).also {
                it.initCause(failure)
            }
        }
    }

    private fun readRetirementText(file: File, container: AnchoredWorkspaceFiles): Pair<ProjectCopyEvidence.Identity, String> {
        val identity = ProjectCopyEvidence.identity(file)
        check(identity.type == "file" && identity.size in 1..MAX_FILE_BYTES) { "Invalid interrupted history intent" }
        container.openRead(file.name).use { opened ->
            val before = opened.observe()
            check(before.attributes.fileKey().toString() == identity.key && before.attributes.size() == identity.size &&
                before.attributes.lastModifiedTime().toString() == identity.modified)
            val bytes = ByteArray(identity.size.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val count = opened.read(bytes, offset, bytes.size - offset)
                check(count > 0) { "Interrupted history intent is truncated" }
                offset += count
            }
            check(opened.read() == -1)
            val after = opened.observe()
            check(after.attributes.fileKey() == before.attributes.fileKey() && after.attributes.size() == before.attributes.size() &&
                after.attributes.lastModifiedTime() == before.attributes.lastModifiedTime() && after.changed == before.changed &&
                ProjectCopyEvidence.identity(file) == identity) { "Interrupted history intent changed while reading" }
            val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            return identity to text
        }
    }

    private fun requireRetirementIdle() {
        shared.retirementProblem?.let { throw Blocked(null, it) }
        check(ProjectCopyEvidence.identityOrAbsent(File(directory, RETIREMENT_PENDING)) == null) {
            "Copy history retirement needs recovery. Evidence was preserved in .droide/copy-recovery and .droide/copy-recovery-archive."
        }
    }

    companion object {
        private const val RECOVERY_HOME = ".droide/copy-recovery"
        private const val ARCHIVE_HOME = ".droide/copy-recovery-archive"

        internal fun isProtectedRecoveryPath(path: String): Boolean =
            path == RECOVERY_HOME || path.startsWith("$RECOVERY_HOME/") ||
                path == ARCHIVE_HOME || path.startsWith("$ARCHIVE_HOME/")

        internal fun requireOrdinaryMutation(mutation: FileRepository.Mutation, root: File) {
            fun checkPath(path: String) {
                require(!isProtectedRecoveryPath(path) &&
                    !isProtectedRecoveryPath(ExplorerFilePolicy.normalizeProjectPath(root, path))) {
                    "Copy recovery authority is protected; use its explicit recovery actions instead"
                }
            }
            when (mutation) {
                is FileRepository.Mutation.Move -> { checkPath(mutation.from); checkPath(mutation.to) }
                is FileRepository.Mutation.Delete -> checkPath(mutation.path)
                is FileRepository.Mutation.Write -> checkPath(mutation.path)
                is FileRepository.Mutation.CreateDirectory -> checkPath(mutation.path)
                is FileRepository.Mutation.Sync -> Unit
            }
        }

        private const val RETIREMENT_PENDING = ".retirement-pending"
        private const val MAX_RECORDS = 128
        private const val MAX_FILE_BYTES = 32_768L
        private const val MAX_TOTAL_BYTES = 4L * 1024 * 1024
        private const val MAX_APPROVALS = 32
        private const val APPROVAL_LIFETIME_MS = 5L * 60 * 1_000
        private val FILE_NAMES = setOf("intent.json", "attempt.json", "receipt.json", "seal.json", "retired.json", "membership.json")
        private val PROCESS_EPOCH = UUID.randomUUID().toString()
        private val states = mutableMapOf<String, Shared>()
        private fun shared(path: String): Shared = synchronized(states) {
            states[path] ?: run {
                check(states.size < 256) { "Too many copy-recovery project identities in this process" }
                Shared().also { states[path] = it }
            }
        }
        fun explorerSourceId(uri: String): String = ProjectCopyEvidence.sha256(uri.toByteArray(Charsets.UTF_8))
    }
}
