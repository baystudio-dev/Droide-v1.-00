package com.baystudio.droide.core

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.Collections
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive

/** SAF-only format. No provider replay/rollback; bounded terminal retirement runs only at admission. */
@Serializable internal enum class SafOperationKind { REFRESH, WRITE, CREATE_DIRECTORY, DELETE, MOVE, SYNC }
@Serializable internal enum class SafEffectKind { WRITE_FILE, CREATE_FILE, CREATE_DIRECTORY, DELETE, LOCAL_ACTIVATION, LOCAL_MUTATION }
@Serializable internal enum class SafEffectPhase { PREPARED, ATTEMPTING, RETURNED, VERIFIED }
@Serializable internal enum class SafEffectReturn { COMPLETED, THREW, REJECTED }
@Serializable internal enum class SafProviderReturnShape { URI, BOOLEAN, VOID, THREW }
@Serializable internal data class SafProviderReturn(
    val shape: SafProviderReturnShape, val uri: String? = null, val boolean: Boolean? = null,
    val diagnosticClass: String? = null, val diagnosticMessage: String? = null,
)

@Serializable internal data class SafOperationRequest(val kind: SafOperationKind, val paths: List<String>)
@Serializable internal data class SafJournalReservation(
    val actions: Int, val metadataBytes: Long, val payloadBytes: Long, val indexBytes: Long = 8192,
    val evidenceFiles: Int = 0,
)
@Serializable internal data class SafEvidenceFile(val name: String, val identity: SafStoredFile)
@Serializable internal data class SafOwnedDocument(
    val uri: String, val documentId: String, val name: String, val mimeType: String, val parentDocumentId: String,
)
@Serializable internal enum class SafLocalArtifactKind { LOCAL_ROOT, STAGE, BACKUP }
@Serializable internal data class SafLocalArtifact(val kind: SafLocalArtifactKind, val path: String, val directoryKey: String)
@Serializable internal data class SafEffectPlan(
    val kind: SafEffectKind, val path: String, val original: SafOwnedDocument?,
    val parent: SafOwnedDocument?, val creationActionId: String?, val previous: SafEvidenceFile?, val intended: SafEvidenceFile?,
    val localArtifacts: List<SafLocalArtifact> = emptyList(),
    val requestedMimeType: String? = null,
    val beforeTree: SafEvidenceFile? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val parentCreationActionId: String? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val local: SafLocalPlan? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val replacementDeleteActionId: String? = null,
)
@Serializable internal data class SafEffectRecord(
    val version: Int, val operationId: String, val actionId: String, val plan: SafEffectPlan,
    val phase: SafEffectPhase, val result: SafEffectReturn?, val returnedDocument: SafOwnedDocument?,
    val providerReturn: SafProviderReturn? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val localReturn: SafLocalReturn? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val localVerified: SafLocalVerified? = null,
)
@Serializable internal data class SafJournalAnchor(
    val version: Int, val workspace: String, val workspaceKey: String, val treeUri: String,
    val journalKey: String, val baseline: SafEvidenceFile, val baselineVisible: SafStoredFile?,
)
@Serializable internal data class SafOperationEnvelope(
    val version: Int, val id: String, val anchorDigest: String, val directoryKey: String,
    val request: SafOperationRequest, val scopes: List<String>, val reservation: SafJournalReservation,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val headDigest: String? = null,
)
@Serializable internal data class SafAbandonedOperation(val version: Int, val operationId: String, val envelopeDigest: String)
@Serializable internal data class SafActivationContainment(
    val version: Int, val operationId: String, val envelopeDigest: String, val actionId: String,
    val plansDigest: String, val original: SafEvidenceFile, val staged: SafEvidenceFile,
    val providerReceiptsDigest: String, val retainedBytes: Long,
    @kotlinx.serialization.Transient val roots: List<String> = emptyList(),
)
@Serializable internal data class SafLegacyTrustedManifest(val version: Int = 2, val entries: Map<String, String> = emptyMap())

internal data class SafJournalLimits(
    val operations: Int = 4096,
    val indexBytes: Long = 4L * 1024 * 1024,
    val recordBytes: Long = 1024L * 1024,
    val manifestBytes: Long = 16L * 1024 * 1024,
    val aggregateMetadataBytes: Long = 512L * 1024 * 1024,
    // Payloads stream to disk. Metadata retains its allocation limits; bytes are
    // admitted by disk capacity and per-operation reservations, not fixed GiB caps.
    val aggregatePayloadBytes: Long = Long.MAX_VALUE,
    val actionsPerOperation: Int = 200_000,
    val evidenceFilesPerOperation: Int = 200_000,
    val treeIndexBytes: Long = 16L * 1024 * 1024,
    val treeFiles: Int = 50_000,
    val treeEntries: Int = 99_998,
    val treeDepth: Int = 64,
    val treeBytes: Long = Long.MAX_VALUE,
) {
    init {
        require(operations > 0 && indexBytes > 0 && recordBytes in 1..Int.MAX_VALUE.toLong() &&
            manifestBytes in 1..Int.MAX_VALUE.toLong() && aggregateMetadataBytes > 0 && aggregatePayloadBytes > 0 && actionsPerOperation >= 0 &&
            evidenceFilesPerOperation >= 0 && treeIndexBytes in 1..16L * 1024 * 1024 &&
            treeFiles > 0 && treeEntries > 0 && treeDepth > 0 && treeBytes > 0)
    }
}

internal class SafOperationJournal private constructor(
    internal val workspace: File,
    internal val directory: File,
    internal val storage: SafOperationStorage,
    internal val limits: SafJournalLimits,
    internal val anchor: SafJournalAnchor,
    internal val anchorIdentity: SafStoredFile,
    private val cancellationJob: Job?,
) {
    private val owner = Any()
    private val liveHandles = mutableMapOf<String, WeakReference<Operation>>()
    internal var retirementDiagnostic: String? = null
    /** Captured coroutine cancellation only. Never an arbitrary caller callback. */
    internal fun ensureVerificationActive() { cancellationJob?.ensureActive() }
    /** Production has a captured Job; legacy internal callers retain their explicit callback API. */
    internal fun admissionPoll(caller: () -> Unit): () -> Unit {
        caller()
        return if (cancellationJob == null) caller else ::ensureVerificationActive
    }

    internal class Operation internal constructor(
        internal val owner: Any,
        internal val envelope: SafOperationEnvelope,
        internal val envelopeIdentity: SafStoredFile,
        internal val files: MutableSet<String>,
        internal var metadataBytes: Long,
        internal var indexBytes: Long,
        internal var payloadBytes: Long,
        internal val effects: MutableMap<String, SafEffectRecord>,
        internal val activeInvocation: Boolean,
        internal var poisoned: Boolean = false,
        internal val admittedVisible: SafStoredFile? = null,
    ) {
        internal val actions = mutableMapOf<String, WeakReference<Action>>()
        internal var candidatePayloadBytes: Long = 0
        internal var activationPayloadBytes: Long = 0
        internal var evidenceFiles: Int = 0
        internal var firstAttemptChecked: Boolean = false
        internal var pendingLocalAdmission: LocalAttemptAdmission? = null
        val id: String get() = envelope.id
        val scopes: List<String> get() = envelope.scopes
    }
    internal class Action internal constructor(internal val operation: Operation, internal val id: String,
        internal val admittedPlan: SafEffectPlan)
    internal class LocalAttemptAdmission internal constructor(
        internal val journalOwner: Any, internal val action: Action, internal val guard: WorkspaceMutationGuard,
        internal val prepared: SafEffectRecord, internal val preparedVersion: SafLocalVersion, internal val visible: SafLocalVersion?,
    ) { internal var consumed = false }
    /** Invocation/admission-local deduplication only; never a persistent trust cache. */
    private class EvidenceAdmission {
        val payloads = hashMapOf<String, SafEvidenceFile>()
        val trees = hashMapOf<String, Pair<SafEvidenceFile, SafTreeEvidence>>()
    }
    internal data class Summary(val id: String, val scopes: List<String>, val committed: Boolean, val abandoned: Boolean)
    /** A single read epoch, passed explicitly and discarded at the first mutation. Never cached. */
    internal data class Admission(val operations: List<Operation>, val head: SafJournalRetirement.State?,
        val versions: Map<String, Map<String, SafLocalVersion>> = emptyMap())

    /** Must run under the later root-shared lease. It is bounded, but not a cross-process CAS. */
    fun begin(request: SafOperationRequest, scopes: List<String>, reservation: SafJournalReservation,
        checkActive: () -> Unit = {}, id: String = UUID.randomUUID().toString()): Operation {
        require(request.paths.size <= 2 && scopes.size in 1..4)
        val admittedRequest = request.copy(paths = frozen(request.paths))
        val admittedScopes = frozen(scopes)
        val poll = admissionPoll(checkActive)
        validateRoot(poll)
        validateRequest(admittedRequest)
        validateScopes(admittedScopes)
        require(admittedRequest.kind != SafOperationKind.REFRESH || admittedScopes == listOf(""))
        require(admittedRequest.paths.all { path -> admittedScopes.any { contains(it, path) } })
        validateReservation(reservation)
        val admission = recoverAdmission(poll)
        val admitted = admission.operations
        val publisher = SafDurableManifestCommit(this)
        publisher.admitBeforeEffects(admission, poll)
        val disjointLinks = linkedMapOf<String, SafLocalVersion?>()
        for (operation in admitted) {
            poll()
            if (!isTerminal(operation)) requireDisjointFromPending(admittedRequest, admittedScopes, emptyList(), operation, poll, disjointLinks)
        }
        val retirement = SafJournalRetirement(this)
        val afterMaintenance = retirement.beforeBegin(admission, reservation, poll)
        val existing = afterMaintenance.operations
        val head = afterMaintenance.head
        val admittedVisible = publisher.admitBeforeEffects(afterMaintenance, poll)
        check(capacityFits(existing, head?.charge ?: SafRetirementCharge(), reservation)) {
            "SAF operation capacity exhausted; retained evidence must not be evicted" +
                (retirementDiagnostic?.let { "; $it" } ?: "")
        }
        requireUuid(id)
        check(head?.checkpoint?.receipts?.none { it.id == id } != false)
        val target = File(directory, id)
        // Caller callbacks supply cancellation, not enduring authority. Renew exact ownership
        // after the last one; no caller callback occurs between this seal and directory creation.
        poll()
        val renewed = renewAdmission(afterMaintenance)
        if (renewed !== afterMaintenance)
            check(publisher.admitBeforeEffects(renewed, ::ensureVerificationActive) == admittedVisible)
        val visibleContinuity = publisher.requireAdmittedVisible(admittedVisible, ::ensureVerificationActive)
        retirement.reseal(head)
        requireNamespaceUnchanged(renewed)
        requireDisjointLinks(disjointLinks)
        publisher.requireVisibleContinuity(visibleContinuity)
        storage.createDirectory(target) // CREATE, never adopt a colliding UUID directory.
        val envelope = SafOperationEnvelope(if (head == null) FORMAT_VERSION else 3, id, anchorIdentity.sha256,
            storage.key(target, true), admittedRequest, admittedScopes, reservation, head?.identity?.sha256)
        val bytes = encode(SafOperationEnvelope.serializer(), envelope)
        check(bytes.size.toLong() <= reservation.metadataBytes)
        check(bytes.size.toLong() <= reservation.indexBytes)
        // A failed write is deliberately retained; a subsequent index rejects partial evidence.
        val identity = storage.writeNew(File(target, "operation.json"), bytes, ::ensureVerificationActive)
        return Operation(owner, envelope, identity, mutableSetOf("operation.json"), identity.bytes, identity.bytes, 0, linkedMapOf(), true,
            admittedVisible = admittedVisible)
            .also { liveHandles[it.id] = WeakReference(it) }
    }

    fun summaries(checkActive: () -> Unit = {}): List<Summary> = recoverAll(checkActive).map {
        Summary(it.id, it.scopes, "committed.json" in it.files, "abandoned.json" in it.files)
    }

    fun recover(id: String, checkActive: () -> Unit = {}): Operation {
        requireUuid(id)
        return recoverAll(checkActive).single { it.id == id }.also { liveHandles[id] = WeakReference(it) }
    }

    fun preserve(operation: Operation, source: File, checkActive: () -> Unit = {}): SafEvidenceFile = ownedWrite(operation, checkActive) {
        requireEvidenceCapacity(operation)
        val name = "payload-${UUID.randomUUID()}.bin"
        val available = operation.envelope.reservation.payloadBytes - operation.payloadBytes
        val identity = storage.copyNew(source, file(operation, name), available, checkActive)
        operation.files += name
        operation.payloadBytes += identity.bytes
        operation.evidenceFiles++
        SafEvidenceFile(name, identity)
    }

    fun preserveInput(operation: Operation, input: InputStream, maximum: Long, checkActive: () -> Unit = {}): SafEvidenceFile {
        var consumed = false
        try { return ownedWrite(operation, checkActive) {
            requireEvidenceCapacity(operation)
            require(maximum >= 0)
            val name = "payload-${UUID.randomUUID()}.bin"
            val available = minOf(maximum, operation.envelope.reservation.payloadBytes - operation.payloadBytes)
            consumed = true
            val identity = storage.copyNew(input, file(operation, name), available, checkActive)
            operation.files += name
            operation.payloadBytes += identity.bytes
            operation.evidenceFiles++
            SafEvidenceFile(name, identity)
        } } catch (failure: Throwable) {
            if (!consumed) try { input.close() } catch (close: Throwable) { if (close !== failure) failure.addSuppressed(close) }
            throw failure
        }
    }

    fun preserveTreeIndex(operation: Operation, image: SafTreeEvidence, checkActive: () -> Unit = {}): SafEvidenceFile =
        ownedWrite(operation, checkActive) {
            requireEvidenceCapacity(operation)
            val available = minOf(limits.treeIndexBytes, operation.envelope.reservation.metadataBytes - operation.metadataBytes)
            val bytes = SafTreeEvidenceFormat.encode(image, available, limits, ::validateDocument, checkActive)
            // Detach mutable caller lists before admitting durable authority.
            val admitted = SafJournalCodec.decode(SafTreeEvidence.serializer(), bytes)
            require(operation.scopes.any { contains(it, admitted.rootPath) })
            validateTree(operation, admitted, admitted.rootPath, admitted.rootDocument, EvidenceAdmission(), checkActive)
            val name = "tree-${UUID.randomUUID()}.json"
            val identity = storage.writeNew(file(operation, name), bytes, checkActive)
            operation.files += name
            operation.metadataBytes += identity.bytes
            operation.evidenceFiles++
            SafEvidenceFile(name, identity)
        }

    internal fun preserveLocalIndex(operation: Operation, image: SafLocalEvidence, checkActive: () -> Unit): SafEvidenceFile =
        ownedWrite(operation, checkActive) {
            requireEvidenceCapacity(operation)
            require(operation.scopes.any { contains(it, image.endpoint.path) })
            SafLocalEvidenceFormat.validate(image, this, operation, image.endpoint, image.rootVersion, checkActive)
            val bytes = SafLocalEvidenceFormat.encode(image,
                minOf(limits.treeIndexBytes, operation.envelope.reservation.metadataBytes - operation.metadataBytes), checkActive)
            val name = "local-${UUID.randomUUID()}.json"
            val identity = storage.writeNew(file(operation, name), bytes, checkActive)
            operation.files += name
            operation.metadataBytes += identity.bytes
            operation.evidenceFiles++
            SafEvidenceFile(name, identity)
        }

    internal fun localInventory(operation: Operation, plan: SafLocalPlan, checkActive: () -> Unit): SafLocalEvidence {
        val ref = requireNotNull(plan.beforeInventory)
        require(ref.name in operation.files)
        val bytes = storage.read(file(operation, ref.name), limits.treeIndexBytes, checkActive)
        checkEvidence(file(operation, ref.name), ref, checkActive)
        return SafJournalCodec.decode(SafLocalEvidence.serializer(), bytes).also {
            SafLocalEvidenceFormat.validate(it, this, operation, requireNotNull(plan.source), requireNotNull(plan.sourceVersion), checkActive)
        }
    }

    private fun requireEvidenceCapacity(operation: Operation) {
        check(operation.activeInvocation) { "Recovered SAF evidence cannot resume provider work" }
        check(!isTerminal(operation) && "manifest-plan.json" !in operation.files)
        check(operation.evidenceFiles < operation.envelope.reservation.evidenceFiles) { "SAF evidence file reservation exhausted" }
    }

    fun prepare(operation: Operation, plan: SafEffectPlan, checkActive: () -> Unit = {}): Action = ownedWrite(operation, checkActive) {
        require(plan.localArtifacts.size <= 3)
        plan.local?.let { require(it.parents.size <= limits.treeDepth * 2 && it.missingDirectories.size <= limits.treeDepth) }
        val admittedPlan = plan.copy(localArtifacts = frozen(plan.localArtifacts), local = plan.local?.let {
            it.copy(parents = frozen(it.parents), missingDirectories = frozen(it.missingDirectories))
        })
        check(operation.activeInvocation) { "Recovered SAF evidence cannot resume provider work" }
        check(!isTerminal(operation) && "manifest-plan.json" !in operation.files)
        check("activation-contained.json" !in operation.files) { "Contained activation plans are immutable" }
        check(operation.effects.size < operation.envelope.reservation.actions)
        validatePlan(operation, admittedPlan, checkActive)
        require(admittedPlan.path.isEmpty() || admittedPlan.path.count { it == '/' } + 1 <= limits.treeDepth)
        validatePlanEvidence(operation, admittedPlan, EvidenceAdmission(), checkActive)
        validateCreationLink(operation, admittedPlan)
        admittedPlan.replacementDeleteActionId?.let {
            check(!operation.firstAttemptChecked) { "SAF replacement must be declared before the first attempt" }
            check(replacementDeletion(operation, admittedPlan).phase == SafEffectPhase.PREPARED)
        }
        if (admittedPlan.parentCreationActionId != null) {
            check(!operation.firstAttemptChecked) { "SAF child intents must be declared before the first attempt" }
            check(parentCreation(operation, admittedPlan).phase == SafEffectPhase.PREPARED)
            admitParentChain(operation, admittedPlan, requireVerified = false, checkActive)
        }
        admittedPlan.localArtifacts.forEach {
            checkActive()
            storage.requireRealAncestors(File(it.path))
            check(storage.key(File(it.path), true) == it.directoryKey)
        }
        operation.payloadBytes = boundedAdd(operation.payloadBytes, retainedLocalBytes(operation, admittedPlan, checkActive),
            operation.envelope.reservation.payloadBytes)
        val id = UUID.randomUUID().toString()
        val record = SafEffectRecord(effectVersion(admittedPlan), operation.id, id, admittedPlan, SafEffectPhase.PREPARED, null, null)
        put(operation, actionName(id, record.phase), SafEffectRecord.serializer(), record, checkActive)
        operation.effects[id] = record
        Action(operation, id, admittedPlan).also { operation.actions[id] = WeakReference(it) }
    }

    fun attempting(action: Action, checkActive: () -> Unit = {}) = transition(action, SafEffectPhase.ATTEMPTING, null, null, null, checkActive)
    fun returned(action: Action, result: SafEffectReturn, rawReturn: SafProviderReturn, checkActive: () -> Unit = {}) =
        transition(action, SafEffectPhase.RETURNED, result, null, rawReturn, checkActive)
    fun verified(action: Action, admittedDocument: SafOwnedDocument? = null, checkActive: () -> Unit = {}) =
        transition(action, SafEffectPhase.VERIFIED, null, admittedDocument, null, checkActive)

    /** Deep admission only. The token is an invocation-local observation, never a recovery capability. */
    internal fun admitLocalAttempt(action: Action, guard: WorkspaceMutationGuard, checkActive: () -> Unit): LocalAttemptAdmission =
        ownedWrite(action.operation, checkActive) {
            val operation = action.operation
            val preparedFile = file(operation, actionName(action.id, SafEffectPhase.PREPARED))
            val preparedVersion = requireNotNull(SafLocalFiles.version(storage, preparedFile))
            requireLocalAction(action, SafEffectPhase.PREPARED, checkActive)
            guard.requireRoot(workspace)
            validatePlanEvidence(operation, action.admittedPlan, EvidenceAdmission(), checkActive)
            requirePendingDisjoint(operation, checkActive)
            val visibleFile = File(workspace, ".droide/saf_manifest.json")
            val visible = SafLocalFiles.version(storage, visibleFile)
            SafDurableManifestCommit(this).requireAdmittedVisible(operation.admittedVisible, checkActive)
            check(SafLocalFiles.version(storage, visibleFile) == visible)
            SafLocalFiles.requireVersions(this, requireNotNull(action.admittedPlan.local), checkActive)
            guard.requireRoot(workspace)
            check(SafLocalFiles.version(storage, preparedFile) == preparedVersion)
            LocalAttemptAdmission(owner, action, guard, operation.effects.getValue(action.id), preparedVersion, visible).also {
                operation.pendingLocalAdmission = it
            }
        }

    /** Only bounded metadata checks and one phase append; no content/inventory/manifest hashing. */
    internal fun attemptLocal(admission: LocalAttemptAdmission) {
        val action = admission.action
        val operation = action.operation
        require(admission.journalOwner === owner && operation.owner === owner) { "Foreign local admission" }
        val current = operation.pendingLocalAdmission
        operation.pendingLocalAdmission = null
        val reused = admission.consumed
        admission.consumed = true
        ownedWrite(operation) {
            check(!reused && current === admission)
            admission.guard.requireRoot(workspace)
            val preparedFile = file(operation, actionName(action.id, SafEffectPhase.PREPARED))
            check(SafLocalFiles.version(storage, preparedFile) == admission.preparedVersion)
            requireLocalAction(action, SafEffectPhase.PREPARED)
            check(SafLocalFiles.version(storage, preparedFile) == admission.preparedVersion)
            check(operation.effects.getValue(action.id) == admission.prepared)
            SafLocalFiles.requireVersions(this, requireNotNull(action.admittedPlan.local))
            check(SafLocalFiles.version(storage, File(workspace, ".droide/saf_manifest.json")) == admission.visible)
            appendLocal(action, SafEffectPhase.ATTEMPTING, null, null)
        }
    }

    internal fun returnedLocal(action: Action, result: SafLocalReturn) = ownedWrite(action.operation) {
        requireLocalAction(action, SafEffectPhase.ATTEMPTING)
        appendLocal(action, SafEffectPhase.RETURNED, result, null)
    }

    internal fun verifiedLocal(action: Action, verified: SafLocalVerified, checkActive: () -> Unit) = ownedWrite(action.operation, checkActive) {
        requireLocalAction(action, SafEffectPhase.RETURNED, checkActive)
        check(action.operation.effects.getValue(action.id).result == SafEffectReturn.COMPLETED)
        appendLocal(action, SafEffectPhase.VERIFIED, null, verified.copy(createdDirectories = frozen(verified.createdDirectories)), checkActive)
    }

    private fun requireLocalAction(action: Action, phase: SafEffectPhase, checkActive: () -> Unit = {}) {
        val operation = action.operation
        check(operation.activeInvocation && operation.actions[action.id]?.get() === action)
        check(!isTerminal(operation) && "manifest-plan.json" !in operation.files)
        val previous = operation.effects.getValue(action.id)
        check(previous.plan == action.admittedPlan && previous.plan.kind == SafEffectKind.LOCAL_MUTATION && previous.phase == phase)
        check(read(operation, actionName(action.id, phase), SafEffectRecord.serializer(), checkActive) == previous)
    }

    private fun appendLocal(action: Action, phase: SafEffectPhase, raw: SafLocalReturn?, verified: SafLocalVerified?, checkActive: () -> Unit = {}) {
        val operation = action.operation
        val previous = operation.effects.getValue(action.id)
        val result = raw?.let { if (!it.invocationReturned || it.diagnosticClass != null) SafEffectReturn.THREW
            else if (it.successful) SafEffectReturn.COMPLETED else SafEffectReturn.REJECTED }
        val next = previous.copy(phase = phase, result = if (phase == SafEffectPhase.VERIFIED) previous.result else result,
            localReturn = if (phase == SafEffectPhase.VERIFIED) previous.localReturn else raw, localVerified = verified)
        validateEffect(operation, next, checkActive)
        put(operation, actionName(action.id, phase), SafEffectRecord.serializer(), next, checkActive)
        operation.effects[action.id] = next
    }

    private fun transition(action: Action, phase: SafEffectPhase, result: SafEffectReturn?, document: SafOwnedDocument?,
        rawReturn: SafProviderReturn?, checkActive: () -> Unit) {
        val operation = action.operation
        ownedWrite(operation, checkActive) {
            check(action.admittedPlan.kind != SafEffectKind.LOCAL_MUTATION) { "Local effects require their one-use admission" }
            check(operation.activeInvocation && operation.actions[action.id]?.get() === action) { "Foreign or recovered SAF action handle" }
            check(!isTerminal(operation) && "manifest-plan.json" !in operation.files)
            val previous = requireNotNull(operation.effects[action.id])
            check(previous.plan == action.admittedPlan) { "SAF action plan changed after admission" }
            validateCreationLink(operation, previous.plan)
            if (previous.plan.replacementDeleteActionId != null) replacementDeletion(operation, previous.plan)
            if (phase == SafEffectPhase.ATTEMPTING && previous.plan.creationActionId != null)
                check(operation.effects.getValue(previous.plan.creationActionId).phase == SafEffectPhase.VERIFIED)
            check(read(operation, actionName(action.id, previous.phase), SafEffectRecord.serializer(), checkActive) == previous)
            check(phase.ordinal == previous.phase.ordinal + 1) { "Illegal SAF effect transition" }
            if (phase == SafEffectPhase.ATTEMPTING) {
                // Substantial checks are cancellable before the durable attempt, never in the return finalizer.
                validatePlanEvidence(operation, action.admittedPlan, EvidenceAdmission(), checkActive)
                requirePendingDisjoint(operation, checkActive)
                admitParentChain(operation, action.admittedPlan, requireVerified = true, checkActive)
                if (!operation.firstAttemptChecked) {
                    SafDurableManifestCommit(this).requireAdmittedVisible(operation.admittedVisible, checkActive)
                    operation.firstAttemptChecked = true
                }
            }
            // A changed dependency must never discard the raw return of an already attempted provider call.
            if (phase == SafEffectPhase.ATTEMPTING || phase == SafEffectPhase.VERIFIED)
                checkDirectCreationReceipts(operation, previous.plan, checkActive)
            if (phase == SafEffectPhase.VERIFIED) check(previous.result == SafEffectReturn.COMPLETED)
            val next = previous.copy(phase = phase,
                result = if (phase == SafEffectPhase.VERIFIED) previous.result else result,
                returnedDocument = document,
                providerReturn = if (phase == SafEffectPhase.VERIFIED) previous.providerReturn else rawReturn)
            validateEffect(operation, next, checkActive)
            validateReturnedCreationLink(operation, next)
            put(operation, actionName(action.id, phase), SafEffectRecord.serializer(), next, checkActive)
            operation.effects[action.id] = next
        }
    }

    /** Releases only a proven no-attempt scope; all bytes remain retained for Phase D retirement. */
    fun abandonPrepared(operation: Operation, checkActive: () -> Unit = {}) = ownedWrite(operation, checkActive) {
        check(!isTerminal(operation) && "manifest-attempt.json" !in operation.files)
        val admitted = recoverAll(checkActive).single { it.id == operation.id }
        check(admitted.effects.values.all { checkActive(); it.phase == SafEffectPhase.PREPARED })
        put(operation, "abandoned.json", SafAbandonedOperation.serializer(),
            SafAbandonedOperation(1, operation.id, operation.envelopeIdentity.sha256), checkActive)
    }

    internal fun validateRoot(checkActive: () -> Unit = {}) {
        checkActive()
        storage.requireRealAncestors(workspace)
        storage.requireRealAncestors(directory)
        check(storage.key(workspace, true) == anchor.workspaceKey)
        check(storage.key(directory, true) == anchor.journalKey)
        check(storage.describe(File(directory, "anchor.json"), limits.recordBytes, checkActive) == anchorIdentity)
        val baseline = File(directory, anchor.baseline.name)
        check(storage.key(baseline, false) == anchor.baseline.identity.key &&
            storage.attributes(baseline)?.size() == anchor.baseline.identity.bytes)
    }

    internal fun validate(operation: Operation, checkActive: () -> Unit = {}) {
        check(operation.owner === owner && liveHandles[operation.id]?.get() === operation && !operation.poisoned) {
            "Stale or uncertain SAF operation handle"
        }
        validateRoot(checkActive)
        check(storage.key(File(directory, operation.id), true) == operation.envelope.directoryKey)
        check(storage.describe(file(operation, "operation.json"), limits.recordBytes, checkActive) == operation.envelopeIdentity)
        (manifestNames + setOf("abandoned.json", "activation-contained.json")).forEach { name ->
            check((storage.attributes(file(operation, name)) != null) == (name in operation.files)) { "SAF operation handle is stale" }
        }
    }

    internal fun file(operation: Operation, name: String): File {
        PathSecurity.safeLeafName(name)
        return File(File(directory, operation.id), name)
    }

    internal fun <T> read(operation: Operation, name: String, serializer: KSerializer<T>, checkActive: () -> Unit = {}): T =
        SafJournalCodec.decode(serializer, storage.read(file(operation, name), limits.recordBytes, checkActive))

    internal fun <T> put(operation: Operation, name: String, serializer: KSerializer<T>, value: T, checkActive: () -> Unit = {}) {
        if (operation.owner === owner && liveHandles[operation.id]?.get() === operation) operation.pendingLocalAdmission = null
        validate(operation, checkActive)
        check(!isTerminal(operation)) { "Terminal SAF evidence is immutable" }
        val bytes = encode(serializer, value)
        check(bytes.size.toLong() <= operation.envelope.reservation.metadataBytes - operation.metadataBytes) {
            "SAF metadata reservation exhausted; evidence retained"
        }
        val indexed = name in manifestNames || name == "abandoned.json"
        if (indexed) check(bytes.size.toLong() <= operation.envelope.reservation.indexBytes - operation.indexBytes)
        storage.writeNew(file(operation, name), bytes, checkActive)
        operation.metadataBytes += bytes.size
        if (indexed) operation.indexBytes += bytes.size
        operation.files += name
    }

    internal fun bytes(operation: Operation, name: String, bytes: ByteArray, checkActive: () -> Unit = {}): SafEvidenceFile {
        validate(operation, checkActive)
        return writeBytes(operation, name, bytes, checkActive)
    }

    /** First plan allocation only: full validation is immediately before the caller's final seal.
     * CREATE_NEW makes this fixed-name cut single-use; no observed read precedes allocation. */
    internal fun writeAdmittedTrustedBase(operation: Operation, bytes: ByteArray): SafEvidenceFile {
        ensureVerificationActive()
        check(operation.owner === owner && liveHandles[operation.id]?.get() === operation && !operation.poisoned && operation.activeInvocation)
        check("trusted.bin" !in operation.files && "manifest-plan.json" !in operation.files)
        check(storage.key(File(directory, operation.id), true) == operation.envelope.directoryKey)
        return writeBytes(operation, "trusted.bin", bytes, ::ensureVerificationActive)
    }

    private fun writeBytes(operation: Operation, name: String, bytes: ByteArray, checkActive: () -> Unit): SafEvidenceFile {
        check(!isTerminal(operation)) { "Terminal SAF evidence is immutable" }
        check(bytes.size.toLong() <= limits.manifestBytes)
        check(bytes.size.toLong() <= operation.envelope.reservation.payloadBytes - operation.payloadBytes)
        val identity = storage.writeNew(file(operation, name), bytes, checkActive)
        operation.files += name
        operation.payloadBytes += identity.bytes
        return SafEvidenceFile(name, identity)
    }

    internal fun checkEvidence(file: File, ref: SafEvidenceFile, checkActive: () -> Unit = {}) {
        require(ref.identity.bytes >= 0 && ref.identity.sha256.matches(Regex("[0-9a-f]{64}")))
        check(storage.describe(file, ref.identity.bytes, checkActive) == ref.identity) { "SAF retained evidence identity changed" }
    }

    /** Conservatively charged from the durable plan, including absent/partial/moved candidates. */
    internal fun chargeManifestCandidate(operation: Operation, bytes: Long) {
        require(bytes in 1..limits.manifestBytes)
        if (operation.candidatePayloadBytes == 0L) {
            operation.payloadBytes = boundedAdd(operation.payloadBytes, bytes, operation.envelope.reservation.payloadBytes)
            operation.candidatePayloadBytes = bytes
        } else check(operation.candidatePayloadBytes == bytes)
    }

    internal fun <T> ownedWrite(operation: Operation, checkActive: () -> Unit = {}, block: () -> T): T {
        val ownedLiveHandle = operation.owner === owner && liveHandles[operation.id]?.get() === operation
        // Any other mutation makes a previously issued local token stale. Foreign owners cannot revoke it.
        if (ownedLiveHandle) operation.pendingLocalAdmission = null
        return try {
            validate(operation, checkActive)
            check(!isTerminal(operation)) { "Terminal SAF evidence is immutable" }
            block()
        } catch (failure: Throwable) {
            if (ownedLiveHandle) operation.poisoned = true
            throw failure
        }
    }

    internal fun isTerminal(operation: Operation) = "committed.json" in operation.files || "abandoned.json" in operation.files

    internal fun preserveActivationInventory(operation: Operation, image: SafActivationInventory, checkActive: () -> Unit): SafEvidenceFile =
        ownedWrite(operation, checkActive) {
            requireEvidenceCapacity(operation)
            require(operation.envelope.request.kind == SafOperationKind.REFRESH && operation.scopes == listOf(""))
            require(image.artifact.kind in setOf(SafLocalArtifactKind.LOCAL_ROOT, SafLocalArtifactKind.STAGE))
            val retained = SafActivationFiles.validate(this, operation, image, image.artifact, checkActive)
            check(retained <= operation.envelope.reservation.payloadBytes - operation.payloadBytes)
            val bytes = SafActivationFiles.encode(image, minOf(limits.treeIndexBytes,
                operation.envelope.reservation.metadataBytes - operation.metadataBytes), checkActive)
            checkActive()
            check(bytes.size.toLong() <= limits.treeIndexBytes && bytes.size.toLong() <= operation.envelope.reservation.metadataBytes - operation.metadataBytes)
            val name = if (image.artifact.kind == SafLocalArtifactKind.LOCAL_ROOT) "activation-original.json" else "activation-stage.json"
            val identity = storage.writeNew(file(operation, name), bytes, checkActive)
            operation.files += name
            operation.metadataBytes += identity.bytes
            operation.evidenceFiles++
            operation.payloadBytes += retained
            operation.activationPayloadBytes += retained
            SafEvidenceFile(name, identity)
        }

    internal fun activationInventory(operation: Operation, ref: SafEvidenceFile, artifact: SafLocalArtifact,
        checkActive: () -> Unit): SafActivationInventory {
        require(ref.name == if (artifact.kind == SafLocalArtifactKind.LOCAL_ROOT) "activation-original.json" else "activation-stage.json")
        require(ref.name in operation.files && ref.identity.bytes in 1..limits.treeIndexBytes)
        val bytes = storage.read(file(operation, ref.name), limits.treeIndexBytes, checkActive)
        checkEvidence(file(operation, ref.name), ref, checkActive)
        return SafJournalCodec.decode(SafActivationInventory.serializer(), bytes).also {
            SafActivationFiles.validate(this, operation, it, artifact, checkActive)
        }
    }

    /** Issued once from complete fixed forests after provider verification; never a replay capability. */
    internal fun containActivation(action: Action, original: SafEvidenceFile, staged: SafEvidenceFile,
        checkActive: () -> Unit): SafActivationContainment = ownedWrite(action.operation, checkActive) {
        val operation = action.operation
        require(operation.activeInvocation && operation.actions[action.id]?.get() === action)
        check(operation.effects.getValue(action.id).phase == SafEffectPhase.PREPARED && action.admittedPlan.kind == SafEffectKind.LOCAL_ACTIVATION)
        check("activation-contained.json" !in operation.files)
        val artifacts = action.admittedPlan.localArtifacts
        val old = activationInventory(operation, original, artifacts.single { it.kind == SafLocalArtifactKind.LOCAL_ROOT }, checkActive)
        val next = activationInventory(operation, staged, artifacts.single { it.kind == SafLocalArtifactKind.STAGE }, checkActive)
        SafActivationFiles.forceAndVerify(this, operation, old, checkActive = checkActive)
        SafActivationFiles.forceAndVerify(this, operation, next, checkActive = checkActive)
        val backup = artifacts.single { it.kind == SafLocalArtifactKind.BACKUP }
        check(SafActivationFiles.capture(this, operation, backup, checkActive).entries.isEmpty())
        val roots = (old.entries + next.entries).map { checkActive(); it.path.substringBefore('/') }.distinct().sorted()
        val retained = (old.entries + next.entries).fold(0L) { total, entry ->
            checkActive(); if (entry.version.kind == SafLocalKind.FILE) boundedAdd(total, entry.version.bytes, operation.envelope.reservation.payloadBytes) else total }
        check(retained == operation.activationPayloadBytes)
        val certificate = SafActivationContainment(1, operation.id, operation.envelopeIdentity.sha256, action.id,
            immutablePlansDigest(operation, checkActive), original, staged, verifiedProviderDigest(operation, action.id, checkActive), retained, roots)
        put(operation, "activation-contained.json", SafActivationContainment.serializer(), certificate, checkActive)
        certificate
    }

    private fun immutablePlansDigest(operation: Operation, checkActive: () -> Unit): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        operation.effects.toSortedMap().forEach { (id, record) ->
            checkActive()
            digest.update(id.toByteArray(Charsets.UTF_8))
            digest.update(SafJournalCodec.encode(SafEffectPlan.serializer(), record.plan).safDigest(checkActive).toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun verifiedProviderDigest(operation: Operation, activationId: String, checkActive: () -> Unit): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        operation.effects.toSortedMap().filterKeys { it != activationId }.forEach { (id, record) ->
            checkActive()
            require(record.plan.kind in providerKinds && record.phase == SafEffectPhase.VERIFIED && record.result == SafEffectReturn.COMPLETED)
            val name = actionName(id, SafEffectPhase.VERIFIED)
            check(read(operation, name, SafEffectRecord.serializer(), checkActive) == record)
            digest.update(id.toByteArray(Charsets.UTF_8))
            digest.update(SafJournalCodec.encode(SafStoredFile.serializer(), storage.describe(file(operation, name), limits.recordBytes, checkActive)))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun activationContainment(operation: Operation, checkActive: () -> Unit): SafActivationContainment? {
        if ("activation-contained.json" !in operation.files) return null
        val certificate = read(operation, "activation-contained.json", SafActivationContainment.serializer(), checkActive)
        require(certificate.version == 1 && certificate.operationId == operation.id && certificate.envelopeDigest == operation.envelopeIdentity.sha256)
        require(operation.envelope.request.kind == SafOperationKind.REFRESH && operation.scopes == listOf(""))
        val action = operation.effects.getValue(certificate.actionId)
        require(action.plan.kind == SafEffectKind.LOCAL_ACTIVATION && certificate.plansDigest == immutablePlansDigest(operation, checkActive))
        val artifacts = action.plan.localArtifacts
        val old = activationInventory(operation, certificate.original, artifacts.single { it.kind == SafLocalArtifactKind.LOCAL_ROOT }, checkActive)
        val next = activationInventory(operation, certificate.staged, artifacts.single { it.kind == SafLocalArtifactKind.STAGE }, checkActive)
        val roots = (old.entries + next.entries).map { checkActive(); it.path.substringBefore('/') }.distinct().sorted()
        val retained = (old.entries + next.entries).fold(0L) { total, entry ->
            checkActive(); if (entry.version.kind == SafLocalKind.FILE) boundedAdd(total, entry.version.bytes, operation.envelope.reservation.payloadBytes) else total }
        require(certificate.retainedBytes == retained)
        check(certificate.providerReceiptsDigest == verifiedProviderDigest(operation, certificate.actionId, checkActive))
        return certificate.copy(roots = roots)
    }

    /** One overlap rule at admission, each first effect and manifest boundaries. Authority never widens. */
    internal fun requireDisjointFromPending(request: SafOperationRequest, scopes: List<String>, plans: List<SafEffectPlan>,
        pending: Operation, checkActive: () -> Unit, continuity: MutableMap<String, SafLocalVersion?>? = null) {
        if (isTerminal(pending) || scopes.none { a -> pending.scopes.any { overlaps(a, it) } }) return
        val certificate = activationContainment(pending, checkActive)
        val action = certificate?.let { pending.effects.getValue(it.actionId) }
        val narrow = certificate != null && action!!.phase >= SafEffectPhase.ATTEMPTING && request.kind != SafOperationKind.REFRESH
        if (!narrow) error("SAF operation overlaps retained uncertain evidence: ${pending.id}")
        fun ancestors(file: File) {
            storage.requireRealAncestors(file)
            if (continuity != null) {
                var path: File? = file.absoluteFile
                while (path != null) {
                    checkActive()
                    val node = path
                    val observed = SafLocalFiles.version(storage, node)
                    if (continuity.containsKey(node.path)) {
                        val previous = continuity[node.path]
                        check(previous?.kind == observed?.kind && previous?.key == observed?.key)
                    } else continuity[node.path] = observed
                    path = node.parentFile
                }
            }
        }
        action!!.plan.localArtifacts.forEach { artifact ->
            checkActive(); ancestors(File(artifact.path)); check(storage.key(File(artifact.path), true) == artifact.directoryKey)
        }
        val footprint = linkedSetOf<String>()
        fun endpoint(path: String) {
            validPath(path, false)
            footprint += path
            var part = ""
            path.split('/').dropLast(1).forEach {
                checkActive()
                part = if (part.isEmpty()) it else "$part/$it"
                val file = File(workspace, part)
                val attrs = storage.attributes(file)
                require(attrs == null || attrs.isDirectory && !attrs.isSymbolicLink) { "Ambiguous incoming SAF ancestor" }
                if (attrs == null) footprint += part
            }
            ancestors(File(workspace, path))
        }
        request.paths.forEach(::endpoint)
        plans.forEach { plan ->
            checkActive()
            when (plan.kind) {
                SafEffectKind.LOCAL_MUTATION -> {
                    val local = requireNotNull(plan.local)
                    listOfNotNull(local.source, local.destination.takeIf { local.deleteCustody == null }).forEach {
                        require(it.role == SafLocalRole.WORKSPACE); endpoint(it.path)
                    }
                    local.deleteCustody?.let {
                        require(local.effect == SafLocalEffect.DELETE_STAGE && local.destination.path == "${it.directory}/original")
                        SafLocalFiles.requireParents(this, local.parents, checkActive)
                    }
                    local.missingDirectories.forEach(::endpoint)
                }
                in providerKinds -> endpoint(plan.path)
                else -> error("Unsupported incoming SAF effect cannot narrow authority")
            }
        }
        require(footprint.all { path -> scopes.any { contains(it, path) } })
        certificate!!.roots.forEach { ancestors(File(workspace, it)) }
        check(footprint.none { a -> certificate.roots.any { overlaps(a, it) } }) {
            "SAF operation overlaps fixed activation recovery roots: ${pending.id}"
        }
    }

    /** Only root/ancestor type and identity facts used by pending-scope narrowing, never tree bodies. */
    internal fun requireDisjointLinks(observed: Map<String, SafLocalVersion?>) {
        observed.forEach { (path, expected) ->
            ensureVerificationActive()
            val current = SafLocalFiles.version(storage, File(path))
            check(current?.kind == expected?.kind && current?.key == expected?.key) { "SAF pending-scope ancestor changed at allocation" }
        }
    }

    internal fun requirePendingDisjoint(operation: Operation, checkActive: () -> Unit) {
        recoverAll(checkActive).filter { it.id != operation.id && !isTerminal(it) }.forEach {
            requireDisjointFromPending(operation.envelope.request, operation.scopes, operation.effects.values.map { it.plan }, it, checkActive)
        }
    }

    // Only fully admitted terminal records release unused headroom. These counters
    // include retained local artifacts and the conservatively charged manifest candidate. Pending
    // operations keep their entire reservation, including after a failed or uncertain write.
    private fun chargedMetadata(operation: Operation) = if (isTerminal(operation)) operation.metadataBytes else operation.envelope.reservation.metadataBytes
    private fun chargedIndex(operation: Operation) = if (isTerminal(operation)) operation.indexBytes else operation.envelope.reservation.indexBytes
    private fun chargedPayload(operation: Operation) = if (isTerminal(operation)) operation.payloadBytes else operation.envelope.reservation.payloadBytes

    /** Exact current allocation/reservations. A future unlink never contributes transition credit. */
    internal fun capacityFits(operations: List<Operation>, maintenance: SafRetirementCharge,
        incoming: SafJournalReservation? = null): Boolean {
        if (maintenance.operations < 0 || operations.size.toLong() + maintenance.operations + (if (incoming == null) 0 else 1) > limits.operations) return false
        var metadata = maintenance.metadata
        var index = maintenance.index
        var payload = maintenance.payload
        fun fits(current: Long, extra: Long, maximum: Long) = current >= 0 && extra >= 0 && current <= maximum && extra <= maximum - current
        if (!fits(index, anchorIdentity.bytes, limits.indexBytes) || !fits(payload, anchor.baseline.identity.bytes, limits.aggregatePayloadBytes)) return false
        index += anchorIdentity.bytes; payload += anchor.baseline.identity.bytes
        val charges = operations.map { SafRetirementCharge(1, chargedMetadata(it), chargedIndex(it), chargedPayload(it)) } +
            listOfNotNull(incoming?.let { SafRetirementCharge(1, it.metadataBytes, it.indexBytes, it.payloadBytes) })
        for (charge in charges) {
            if (!fits(metadata, charge.metadata, limits.aggregateMetadataBytes) || !fits(index, charge.index, limits.indexBytes) ||
                !fits(payload, charge.payload, limits.aggregatePayloadBytes)) return false
            metadata += charge.metadata; index += charge.index; payload += charge.payload
        }
        return metadata <= limits.aggregateMetadataBytes && index <= limits.indexBytes && payload <= limits.aggregatePayloadBytes
    }

    internal fun recoverAll(checkActive: () -> Unit = {}): List<Operation> = recoverAdmission(checkActive).operations

    internal fun recoverAdmission(checkActive: () -> Unit = {}): Admission = readAdmission(checkActive, false)

    /** Validates retained evidence without forcing durability, replaying, or collecting it. */
    internal fun inspectReadOnly(checkActive: () -> Unit = {}): Admission = readAdmission(checkActive, true)

    internal fun inspectExportReadOnly(checkActive: () -> Unit = {}): Admission = inspectReadOnly(checkActive).also { admission ->
        check(admission.operations.all(::isTerminal)) {
            "SAF recovery or pending publication needs review before export; retained data was not changed"
        }
    }

    private fun readAdmission(checkActive: () -> Unit, readOnlyInspection: Boolean): Admission {
        validateRoot(checkActive)
        val retirement = SafJournalRetirement(this)
        val head = retirement.load(checkActive, forceDurability = !readOnlyInspection)
        return recoverAgainst(head, checkActive, forceDurability = !readOnlyInspection)
    }

    /** The caller supplied a fully verified head for this read epoch; no checkpoint reload. */
    private fun recoverAgainst(head: SafJournalRetirement.State?, checkActive: () -> Unit, forceDurability: Boolean = true): Admission {
        validateRoot(checkActive)
        val retirement = SafJournalRetirement(this)
        val retired = head?.checkpoint?.receipts?.map { it.id }?.toSet() ?: emptySet()
        val result = arrayListOf<Operation>()
        var indexBytes = boundedAdd(anchorIdentity.bytes, head?.charge?.index ?: 0, limits.indexBytes)
        var reservedMetadata = head?.charge?.metadata ?: 0L
        var reservedPayload = boundedAdd(anchor.baseline.identity.bytes, head?.charge?.payload ?: 0, limits.aggregatePayloadBytes)
        check(reservedMetadata <= limits.aggregateMetadataBytes)
        Files.newDirectoryStream(directory.toPath()).use { children ->
            for (child in children) {
                checkActive()
                val name = child.fileName.toString()
                if (name == "anchor.json" || name == "baseline.bin" || name == SafJournalRetirement.CURRENT) continue
                requireUuid(name)
                if (name in retired) continue // Only a durable detached exact receipt admits partial files.
                check(result.size + (head?.charge?.operations ?: 0) < limits.operations) { "SAF operation index capacity exceeded" }
                val opDir = child.toFile()
                val directoryKey = storage.key(opDir, true)
                val envelopeFile = File(opDir, "operation.json")
                val raw = storage.read(envelopeFile, limits.recordBytes, checkActive)
                val decoded = SafJournalCodec.decode(SafOperationEnvelope.serializer(), raw)
                val envelope = decoded.copy(request = decoded.request.copy(paths = frozen(decoded.request.paths)), scopes = frozen(decoded.scopes))
                require(envelope.version == (if (envelope.headDigest == null) FORMAT_VERSION else 3) &&
                    (envelope.headDigest == null || envelope.headDigest.matches(Regex("[0-9a-f]{64}"))) &&
                    envelope.id == name && envelope.directoryKey == directoryKey &&
                    envelope.anchorDigest == anchorIdentity.sha256)
                validateRequest(envelope.request)
                validateScopes(envelope.scopes)
                require(envelope.request.kind != SafOperationKind.REFRESH || envelope.scopes == listOf(""))
                require(envelope.request.paths.all { path -> envelope.scopes.any { contains(it, path) } })
                validateReservation(envelope.reservation)
                val operation = Operation(owner, envelope, storage.describe(envelopeFile, limits.recordBytes, checkActive),
                    linkedSetOf(), 0, 0, 0, linkedMapOf(), false)
                admitFiles(operation, checkActive, forceDurability)
                retirement.admitOperation(head, operation, checkActive)
                indexBytes = boundedAdd(indexBytes, chargedIndex(operation), limits.indexBytes)
                reservedMetadata = boundedAdd(reservedMetadata, chargedMetadata(operation), limits.aggregateMetadataBytes)
                reservedPayload = boundedAdd(reservedPayload, chargedPayload(operation), limits.aggregatePayloadBytes)
                result += operation
            }
        }
        head?.checkpoint?.pins?.forEach { pin ->
            checkActive(); check(result.any { it.id == pin.id }) { "Pinned SAF operation disappeared; evidence retained" }
        }
        check(capacityFits(result, head?.charge ?: SafRetirementCharge()))
        val versions = result.associate { operation -> operation.id to operation.files.associateWith { name ->
            checkActive()
            requireNotNull(SafLocalFiles.version(storage, file(operation, name)))
        } }
        return Admission(result, head, versions)
    }

    /** Renew after the last arbitrary callback, using only the captured coroutine Job. */
    internal fun renewAdmission(previous: Admission): Admission {
        if (cancellationJob != null) {
            // No arbitrary caller callback followed the original deep admission in this branch.
            requireNamespaceUnchanged(previous)
            return previous
        }
        val cancel = ::ensureVerificationActive
        val head = SafJournalRetirement(this).readmitReceipts(previous.head, cancel)
        check(head?.charge == previous.head?.charge) { "SAF receipt namespace changed during admission" }
        val next = recoverAgainst(head, cancel)
        check(next.operations.map { it.id }.toSet() == previous.operations.map { it.id }.toSet()) {
            "SAF operation namespace changed during admission"
        }
        val old = previous.operations.associateBy { it.id }
        next.operations.forEach { operation ->
            val before = old.getValue(operation.id)
            check(operation.envelope == before.envelope && operation.envelopeIdentity == before.envelopeIdentity &&
                operation.files == before.files && operation.effects == before.effects &&
                operation.metadataBytes == before.metadataBytes && operation.indexBytes == before.indexBytes &&
                operation.payloadBytes == before.payloadBytes) { "SAF admitted operation changed before allocation" }
        }
        check(next.versions == previous.versions) { "SAF admitted file identity changed before allocation" }
        return next
    }

    /** Direct namespace/identity continuity after the last frame hash; no observed read or caller poll. */
    internal fun requireNamespaceUnchanged(admission: Admission, admittedCandidate: SafStoredFile? = null) {
        ensureVerificationActive()
        SafJournalRetirement(this).requireReceiptContinuity(admission.head)
        val retained = admission.head?.checkpoint?.receipts.orEmpty().filter { receipt ->
            val root = File(directory, receipt.id)
            if (storage.attributes(root) == null) false else {
                check(admission.head?.outstanding?.any { it.id == receipt.id } == true)
                check(storage.key(root, true) == receipt.directoryKey)
                true
            }
        }.map { it.id }
        val expected = admission.operations.map { it.id }.toSet() +
            retained +
            setOf("anchor.json", "baseline.bin") +
            (if (admission.head == null) emptySet() else setOf(SafJournalRetirement.CURRENT)) +
            (if (admittedCandidate == null) emptySet() else setOf(SafJournalRetirement.CANDIDATE))
        admittedCandidate?.let {
            val file = File(directory, SafJournalRetirement.CANDIDATE)
            check(storage.key(file, false) == it.key && storage.attributes(file)?.size() == it.bytes)
        }
        fun requireNames(root: File, allowed: Set<String>) {
            val seen = hashSetOf<String>()
            Files.newDirectoryStream(root.toPath()).use { stream ->
                for (child in stream) {
                    ensureVerificationActive()
                    val name = child.fileName.toString()
                    check(name in allowed && seen.size < allowed.size && seen.add(name)) {
                        "SAF namespace changed at allocation"
                    }
                }
            }
            check(seen == allowed) { "SAF namespace disappeared at allocation" }
        }
        requireNames(directory, expected)
        admission.operations.forEach { operation ->
            ensureVerificationActive()
            val root = File(directory, operation.id)
            check(storage.key(root, true) == operation.envelope.directoryKey)
            requireNames(root, operation.files)
            admission.versions.getValue(operation.id).forEach { (name, version) ->
                ensureVerificationActive()
                check(SafLocalFiles.version(storage, file(operation, name)) == version)
            }
        }
    }

    private fun admitFiles(operation: Operation, checkActive: () -> Unit, forceDurability: Boolean = true) {
        val records = linkedMapOf<String, MutableList<SafEffectRecord>>()
        val maximumFiles = operation.envelope.reservation.actions.toLong() * 4 + operation.envelope.reservation.evidenceFiles + 16L
        val admission = EvidenceAdmission()
        Files.newDirectoryStream(File(directory, operation.id).toPath()).use { children ->
            for (child in children) {
                checkActive()
                check(operation.files.size < maximumFiles)
                val name = child.fileName.toString()
                storage.key(child.toFile(), false)
                val size = requireNotNull(storage.attributes(child.toFile())).size()
                require(size >= 0)
                operation.files += name
                if (name.matches(Regex("payload-[0-9a-f-]{36}\\.bin")) || name in setOf("trusted.bin", "planned.bin", "delta.bin")) {
                    if (name.startsWith("payload-")) {
                        requireUuid(name.removePrefix("payload-").removeSuffix(".bin"))
                        check(++operation.evidenceFiles <= operation.envelope.reservation.evidenceFiles)
                    }
                    operation.payloadBytes = boundedAdd(operation.payloadBytes, size, operation.envelope.reservation.payloadBytes)
                } else {
                    operation.metadataBytes = boundedAdd(operation.metadataBytes, size, operation.envelope.reservation.metadataBytes)
                    if (name == "operation.json" || name in manifestNames || name == "abandoned.json")
                        operation.indexBytes = boundedAdd(operation.indexBytes, size, operation.envelope.reservation.indexBytes)
                    when {
                        name == "operation.json" -> Unit
                        name == "activation-original.json" || name == "activation-stage.json" -> {
                            check(++operation.evidenceFiles <= operation.envelope.reservation.evidenceFiles)
                            require(size <= limits.treeIndexBytes)
                            val image = SafJournalCodec.decode(SafActivationInventory.serializer(), storage.read(child.toFile(), limits.treeIndexBytes, checkActive))
                            require(image.artifact.kind == if (name == "activation-original.json") SafLocalArtifactKind.LOCAL_ROOT else SafLocalArtifactKind.STAGE)
                            val retained = SafActivationFiles.validate(this, operation, image, image.artifact, checkActive)
                            operation.payloadBytes = boundedAdd(operation.payloadBytes, retained, operation.envelope.reservation.payloadBytes)
                            operation.activationPayloadBytes += retained
                        }
                        name == "activation-contained.json" -> Unit
                        name.matches(Regex("(tree|local)-[0-9a-f-]{36}\\.json")) -> {
                            requireUuid(name.substringAfter('-').removeSuffix(".json"))
                            check(++operation.evidenceFiles <= operation.envelope.reservation.evidenceFiles)
                            require(size <= limits.treeIndexBytes)
                            // Unreferenced/partial recognized indexes stay retained, never effect authority.
                        }
                        name.matches(Regex("action-[0-9a-f-]{36}-[0-3]\\.json")) -> {
                            val record = read(operation, name, SafEffectRecord.serializer(), checkActive)
                            validateEffect(operation, record, checkActive)
                            require(name == actionName(record.actionId, record.phase))
                            records.getOrPut(record.actionId) { arrayListOf() }.add(record)
                            check(records.size <= operation.envelope.reservation.actions)
                        }
                        name in manifestNames -> Unit // Admitted by the manifest protocol below.
                        name == "abandoned.json" -> {
                            val abandoned = read(operation, name, SafAbandonedOperation.serializer(), checkActive)
                            require(abandoned == SafAbandonedOperation(1, operation.id, operation.envelopeIdentity.sha256))
                        }
                        else -> error("Unknown SAF evidence retained: $name")
                    }
                }
            }
        }
        for ((id, phases) in records) {
            checkActive()
            val ordered = phases.sortedBy { it.phase.ordinal }
            ordered.forEachIndexed { index, record ->
                require(record.phase.ordinal == index && record.plan == ordered.first().plan)
                if (index == SafEffectPhase.VERIFIED.ordinal) {
                    require(ordered[index - 1].result == SafEffectReturn.COMPLETED &&
                        record.result == ordered[index - 1].result && record.providerReturn == ordered[index - 1].providerReturn &&
                        record.localReturn == ordered[index - 1].localReturn)
                }
            }
            validatePlanEvidence(operation, ordered.first().plan, admission, checkActive)
            operation.payloadBytes = boundedAdd(operation.payloadBytes, retainedLocalBytes(operation, ordered.first().plan, checkActive),
                operation.envelope.reservation.payloadBytes)
            operation.effects[id] = ordered.last().let { it.copy(plan = it.plan.copy(localArtifacts = frozen(it.plan.localArtifacts),
                local = it.plan.local?.let { local -> local.copy(parents = frozen(local.parents), missingDirectories = frozen(local.missingDirectories)) }),
                localVerified = it.localVerified?.let { verified -> verified.copy(createdDirectories = frozen(verified.createdDirectories)) }) }
        }
        operation.effects.values.forEach {
            checkActive()
            validateCreationLink(operation, it.plan)
            if (it.plan.replacementDeleteActionId != null) {
                replacementDeletion(operation, it.plan)
                if (it.phase >= SafEffectPhase.ATTEMPTING) admitReplacementDeletion(operation, it.plan, checkActive)
            }
            validateReturnedCreationLink(operation, it)
            admitParentChain(operation, it.plan, requireVerified = it.phase >= SafEffectPhase.ATTEMPTING, checkActive)
            if (it.phase >= SafEffectPhase.ATTEMPTING && it.plan.creationActionId != null)
                require(operation.effects.getValue(it.plan.creationActionId).phase == SafEffectPhase.VERIFIED)
        }
        if ("abandoned.json" in operation.files) require("committed.json" !in operation.files &&
            "manifest-attempt.json" !in operation.files && operation.effects.values.all { it.phase == SafEffectPhase.PREPARED })
        activationContainment(operation, checkActive)?.let { check(it.retainedBytes == operation.activationPayloadBytes) }
        SafDurableManifestCommit.admit(this, operation, checkActive, forceDurability)
    }

    private fun validateEffect(operation: Operation, record: SafEffectRecord, checkActive: () -> Unit = {}) {
        require(record.version == effectVersion(record.plan) && record.operationId == operation.id)
        requireUuid(record.actionId)
        validatePlan(operation, record.plan, checkActive)
        if (record.plan.kind == SafEffectKind.LOCAL_MUTATION) { validateLocalRecord(record); return }
        require(record.localReturn == null && record.localVerified == null)
        if (record.phase <= SafEffectPhase.ATTEMPTING)
            require(record.result == null && record.returnedDocument == null && record.providerReturn == null)
        else {
            require(record.result != null)
            validateRawReturn(record.plan.kind, record.result, requireNotNull(record.providerReturn))
            if (record.phase == SafEffectPhase.RETURNED) require(record.returnedDocument == null)
            if (record.phase == SafEffectPhase.VERIFIED) {
                require(record.result == SafEffectReturn.COMPLETED)
                when (record.plan.kind) {
                    SafEffectKind.WRITE_FILE, SafEffectKind.CREATE_FILE, SafEffectKind.CREATE_DIRECTORY -> {
                        val returned = requireNotNull(record.returnedDocument)
                        validateDocument(returned)
                        require(returned.name == record.plan.path.substringAfterLast('/'))
                        record.plan.parent?.let { require(returned.parentDocumentId == it.documentId) }
                        require((returned.mimeType == DIRECTORY_MIME) == (record.plan.kind == SafEffectKind.CREATE_DIRECTORY))
                        record.plan.requestedMimeType?.let { require(returned.mimeType == it) }
                        record.plan.original?.let { require(returned == it) }
                        require(returned.uri.startsWith("${anchor.treeUri}/document/"))
                        if (record.plan.kind != SafEffectKind.WRITE_FILE) require(returned.uri == record.providerReturn.uri)
                    }
                    else -> require(record.returnedDocument == null)
                }
            }
        }
    }

    private fun validateRawReturn(kind: SafEffectKind, result: SafEffectReturn, raw: SafProviderReturn) {
        raw.uri?.let { require(it.length <= 256 * 1024 && it.toByteArray(Charsets.UTF_8).size <= 256 * 1024 && '\u0000' !in it) }
        raw.diagnosticClass?.let { require(it.length <= 256 && it.toByteArray(Charsets.UTF_8).size <= 256 && '\u0000' !in it) }
        raw.diagnosticMessage?.let { require(it.length <= 4096 && it.toByteArray(Charsets.UTF_8).size <= 4096 && '\u0000' !in it) }
        if (result == SafEffectReturn.THREW) {
            require(raw.shape == SafProviderReturnShape.THREW && raw.uri == null && raw.boolean == null &&
                !raw.diagnosticClass.isNullOrEmpty())
            return
        }
        require(raw.diagnosticClass == null && raw.diagnosticMessage == null)
        when (kind) {
            SafEffectKind.CREATE_FILE, SafEffectKind.CREATE_DIRECTORY -> {
                require(raw.shape == SafProviderReturnShape.URI && raw.boolean == null)
                require((raw.uri != null) == (result == SafEffectReturn.COMPLETED))
            }
            SafEffectKind.DELETE -> {
                require(raw.shape == SafProviderReturnShape.BOOLEAN && raw.uri == null && raw.boolean != null)
                require(raw.boolean == (result == SafEffectReturn.COMPLETED))
            }
            SafEffectKind.WRITE_FILE, SafEffectKind.LOCAL_ACTIVATION ->
                require(raw.shape == SafProviderReturnShape.VOID && raw.uri == null && raw.boolean == null && result == SafEffectReturn.COMPLETED)
            SafEffectKind.LOCAL_MUTATION -> error("Local effects do not have provider returns")
        }
    }

    private fun validatePlan(operation: Operation, plan: SafEffectPlan, checkActive: () -> Unit = {}) {
        checkActive()
        validPath(plan.path, allowRoot = plan.kind == SafEffectKind.LOCAL_ACTIVATION)
        // Old unlinked v2 evidence had only validPath's byte/component limits. Keep it recoverable;
        // all new live preparation and linked v3 evidence still obey the full lexical depth bound.
        if (plan.parentCreationActionId != null || plan.replacementDeleteActionId != null)
            require(plan.path.count { it == '/' } + 1 <= limits.treeDepth)
        require(operation.scopes.any { contains(it, plan.path) })
        plan.replacementDeleteActionId?.let {
            requireUuid(it)
            require(operation.envelope.request.kind == SafOperationKind.REFRESH &&
                plan.kind in setOf(SafEffectKind.CREATE_FILE, SafEffectKind.CREATE_DIRECTORY) &&
                plan.parent != null && plan.parentCreationActionId == null) { "Invalid SAF replacement declaration" }
        }
        if (plan.kind == SafEffectKind.LOCAL_MUTATION) { validateLocalPlan(operation, plan); return }
        require(plan.local == null)
        plan.original?.let(::validateDocument)
        plan.original?.let { require(it.uri.startsWith("${anchor.treeUri}/document/")) }
        if (plan.kind == SafEffectKind.CREATE_FILE || plan.kind == SafEffectKind.CREATE_DIRECTORY) {
            val mime = requireNotNull(plan.requestedMimeType)
            validateMime(mime)
            require((mime == DIRECTORY_MIME) == (plan.kind == SafEffectKind.CREATE_DIRECTORY))
        } else require(plan.requestedMimeType == null)
        if (plan.kind == SafEffectKind.LOCAL_ACTIVATION) {
            require(plan.parent == null && plan.parentCreationActionId == null && plan.original == null && plan.path.isEmpty())
            require(plan.localArtifacts.map { it.kind }.toSet() == SafLocalArtifactKind.entries.toSet() && plan.localArtifacts.size == 3)
            val root = plan.localArtifacts.single { it.kind == SafLocalArtifactKind.LOCAL_ROOT }
            require(root.path == anchor.workspace && root.directoryKey == anchor.workspaceKey)
            val siblings = plan.localArtifacts.filter { it.kind != SafLocalArtifactKind.LOCAL_ROOT }
            require(siblings.map { it.path }.distinct().size == 2)
            siblings.forEach {
                require(it.path != anchor.workspace && File(it.path).parentFile == workspace.parentFile &&
                    File(it.path).absoluteFile.toPath().normalize().toString() == it.path && it.directoryKey.isNotEmpty())
            }
        }
        else {
            require(plan.localArtifacts.isEmpty())
            require((plan.parent == null) == (plan.parentCreationActionId != null))
            if (plan.parent != null) {
                val parent = plan.parent
                validateDocument(parent)
                require(parent.uri.startsWith("${anchor.treeUri}/document/"))
                require(parent.mimeType == DIRECTORY_MIME)
                plan.original?.let { require(it.name == plan.path.substringAfterLast('/') && it.parentDocumentId == parent.documentId) }
            } else {
                requireUuid(requireNotNull(plan.parentCreationActionId))
                require(plan.original == null && plan.kind in setOf(SafEffectKind.CREATE_FILE, SafEffectKind.CREATE_DIRECTORY, SafEffectKind.WRITE_FILE))
            }
        }
        if (plan.kind == SafEffectKind.WRITE_FILE) {
            require(plan.intended != null && (plan.original == null) == (plan.creationActionId != null))
            require((plan.original != null) == (plan.previous != null))
            plan.original?.let { require(it.mimeType != DIRECTORY_MIME) }
        } else {
            require(plan.previous == null && plan.intended == null && plan.creationActionId == null)
            if (plan.kind == SafEffectKind.CREATE_FILE || plan.kind == SafEffectKind.CREATE_DIRECTORY) require(plan.original == null)
            if (plan.kind == SafEffectKind.DELETE) require(plan.original != null)
        }
        require((plan.kind == SafEffectKind.DELETE) == (plan.beforeTree != null))
        plan.beforeTree?.let { validateTreeReference(it) }
        listOfNotNull(plan.previous, plan.intended).forEach { ref ->
            validatePayloadReference(ref)
        }
    }

    private fun admitPayload(operation: Operation, ref: SafEvidenceFile, admission: EvidenceAdmission, checkActive: () -> Unit) {
        validatePayloadReference(ref)
        check(ref.name in operation.files) { "Payload was not admitted by this operation" }
        admission.payloads[ref.name]?.let { require(it == ref); return }
        checkEvidence(file(operation, ref.name), ref, checkActive)
        admission.payloads[ref.name] = ref
    }

    private fun validateTree(operation: Operation, image: SafTreeEvidence, path: String, document: SafOwnedDocument,
        admission: EvidenceAdmission, checkActive: () -> Unit) {
        SafTreeEvidenceFormat.validate(image, operation.id, anchor.treeUri, path, document, limits,
            ::validateDocument, { admitPayload(operation, it, admission, checkActive) }, checkActive)
    }

    private fun validatePlanEvidence(operation: Operation, plan: SafEffectPlan, admission: EvidenceAdmission, checkActive: () -> Unit) {
        plan.local?.let { local ->
            listOfNotNull(local.previous, local.intended).forEach { admitPayload(operation, it, admission, checkActive) }
            if (local.beforeInventory != null) localInventory(operation, local, checkActive).entries.forEach {
                checkActive()
                // Exact protected custody was admitted by validateLocalPlan. Preserve the user
                // subtree's logical source path/depth; generic protected-path denial stays intact.
                val root = if (local.deleteCustody == null) local.destination.path else requireNotNull(local.source).path
                val target = root + if (it.path.isEmpty()) "" else "/${it.path}"
                validPath(target, false)
                require(target.count { c -> c == '/' } + 1 <= limits.treeDepth)
            }
        }
        listOfNotNull(plan.previous, plan.intended).forEach { admitPayload(operation, it, admission, checkActive) }
        plan.beforeTree?.let { ref ->
            validateTreeReference(ref)
            check(ref.name in operation.files) { "Tree index was not admitted by this operation" }
            val existing = admission.trees[ref.name]
            val image = if (existing != null) {
                require(existing.first == ref)
                existing.second
            } else {
                val bytes = storage.read(file(operation, ref.name), limits.treeIndexBytes, checkActive)
                require(storage.key(file(operation, ref.name), false) == ref.identity.key &&
                    bytes.size.toLong() == ref.identity.bytes && bytes.safDigest(checkActive) == ref.identity.sha256)
                SafJournalCodec.decode(SafTreeEvidence.serializer(), bytes).also { admission.trees[ref.name] = ref to it }
            }
            validateTree(operation, image, plan.path, requireNotNull(plan.original), admission, checkActive)
        }
    }

    private fun validateLocalPlan(operation: Operation, outer: SafEffectPlan) {
        val plan = requireNotNull(outer.local)
        require(outer.original == null && outer.parent == null && outer.creationActionId == null && outer.parentCreationActionId == null &&
            outer.previous == null && outer.intended == null && outer.beforeTree == null && outer.localArtifacts.isEmpty() && outer.requestedMimeType == null)
        require(outer.path == (if (plan.deleteCustody == null) plan.destination.path else plan.source?.path) &&
            plan.activationOrdinal == null && plan.desiredInventory == null)
        plan.deleteCustody?.let { custody ->
            require(plan.effect == SafLocalEffect.DELETE_STAGE && custody.directory == ".droide/saf-delete-custody/${operation.id}" &&
                plan.destination.path == "${custody.directory}/original")
            requireBoundedKey(custody.directoryKey)
            require(plan.parents.single { it.path == custody.directory }.key == custody.directoryKey)
        }
        val endpoints = listOfNotNull(plan.source, plan.destination)
        endpoints.forEach { endpoint ->
            SafLocalFiles.resolve(this, endpoint, plan.deleteCustody)
            if (plan.deleteCustody == null || endpoint != plan.destination)
                require(operation.scopes.any { contains(it, endpoint.path) }) { "Local endpoint is outside immutable authority" }
        }
        require(plan.source == null || plan.source != plan.destination)
        val request = operation.envelope.request
        when (plan.effect) {
            SafLocalEffect.WRITE -> require(request.kind == SafOperationKind.WRITE && request.paths == listOf(plan.destination.path))
            SafLocalEffect.DELETE_STAGE -> require(request.kind == SafOperationKind.DELETE && request.paths == listOf(plan.source?.path))
            SafLocalEffect.MOVE -> require(request.kind == SafOperationKind.MOVE && request.paths == listOf(plan.source?.path, plan.destination.path))
            SafLocalEffect.CREATE_DIRECTORY -> require(request.kind == SafOperationKind.CREATE_DIRECTORY && request.paths == listOf(plan.destination.path))
            SafLocalEffect.CREATE_PARENTS -> require(request.kind in setOf(SafOperationKind.WRITE, SafOperationKind.MOVE) &&
                plan.destination.path == request.paths.last().substringBeforeLast('/', ""))
            else -> error("Activation requires its later exact artifact binding")
        }
        plan.sourceVersion?.let(SafLocalEvidenceFormat::validateVersion)
        plan.expectedDestination?.let(SafLocalEvidenceFormat::validateVersion)
        require(plan.parents.size in 1..(limits.treeDepth * 2) && plan.parents.map { it.path }.distinct().size == plan.parents.size)
        require(plan.missingDirectories.size <= limits.treeDepth && plan.missingDirectories.distinct().size == plan.missingDirectories.size)
        val expectedParents = linkedSetOf("")
        endpoints.forEach { endpoint ->
            var path = endpoint.path.substringBeforeLast('/', "")
            while (path.isNotEmpty()) { expectedParents += path; path = path.substringBeforeLast('/', "") }
        }
        plan.missingDirectories.forEach { missing ->
            validPath(missing, false)
            require(operation.scopes.any { contains(it, missing) })
            require(contains(missing, plan.destination.path))
        }
        require(plan.parents.map { it.path }.toSet() == expectedParents - plan.missingDirectories.toSet())
        plan.parents.forEach {
            val custodyParent = plan.deleteCustody?.let { custody ->
                it.path in setOf(".droide", ".droide/saf-delete-custody", custody.directory)
            } == true
            if (!custodyParent) validPath(it.path, true)
            requireBoundedKey(it.key)
        }
        require(plan.parents.single { it.path.isEmpty() }.key == anchor.workspaceKey)
        listOfNotNull(plan.intended, plan.previous).forEach(::validatePayloadReference)
        plan.beforeInventory?.let { require(it.name.matches(Regex("local-[0-9a-f-]{36}\\.json"))); requireUuid(it.name.removePrefix("local-").removeSuffix(".json"))
            requireBoundedKey(it.identity.key); require(it.identity.bytes in 1..limits.treeIndexBytes && it.identity.sha256.matches(Regex("[0-9a-f]{64}"))) }
        when (plan.effect) {
            SafLocalEffect.WRITE -> {
                require(plan.source != null && plan.sourceVersion?.kind == SafLocalKind.FILE && plan.intended != null && plan.beforeInventory == null && plan.missingDirectories.isEmpty())
                require(plan.expectedDestination == null || plan.expectedDestination.kind == SafLocalKind.FILE)
                require((plan.expectedDestination == null) == (plan.previous == null))
                require(plan.intended.identity.bytes == plan.sourceVersion.bytes)
                plan.previous?.let { require(it.identity.bytes == plan.expectedDestination!!.bytes) }
            }
            SafLocalEffect.MOVE, SafLocalEffect.DELETE_STAGE -> {
                require(plan.source != null && plan.sourceVersion?.kind in setOf(SafLocalKind.FILE, SafLocalKind.DIRECTORY) && plan.expectedDestination == null &&
                    plan.beforeInventory != null && plan.intended == null && plan.previous == null && plan.missingDirectories.isEmpty())
                require(!overlaps(plan.source.path, plan.destination.path))
                if (plan.effect == SafLocalEffect.DELETE_STAGE && plan.deleteCustody == null)
                    require(plan.source.path.substringBeforeLast('/', "") == plan.destination.path.substringBeforeLast('/', ""))
            }
            SafLocalEffect.CREATE_DIRECTORY, SafLocalEffect.CREATE_PARENTS -> {
                require(plan.source == null && plan.sourceVersion == null && plan.expectedDestination == null && plan.beforeInventory == null && plan.intended == null && plan.previous == null)
                require(plan.missingDirectories.isNotEmpty() && plan.missingDirectories.last() == plan.destination.path)
                plan.missingDirectories.forEachIndexed { index, path ->
                    val parent = path.substringBeforeLast('/', "")
                    require(if (index == 0) parent in plan.parents.map { it.path } else parent == plan.missingDirectories[index - 1])
                }
            }
            else -> error("Activation requires its later exact artifact binding")
        }
    }

    private fun retainedLocalBytes(operation: Operation, plan: SafEffectPlan, checkActive: () -> Unit): Long {
        val local = plan.local ?: return 0L
        return when (local.effect) {
            SafLocalEffect.WRITE -> requireNotNull(local.sourceVersion).bytes // distinct still-owned stage plus its private evidence copy
            SafLocalEffect.DELETE_STAGE -> localInventory(operation, local, checkActive).entries.fold(0L) { bytes, entry ->
                checkActive(); if (entry.version.kind == SafLocalKind.FILE) boundedAdd(bytes, entry.version.bytes, limits.treeBytes) else bytes }
            else -> 0L
        }
    }

    private fun validateLocalRecord(record: SafEffectRecord) {
        require(record.providerReturn == null && record.returnedDocument == null)
        if (record.phase < SafEffectPhase.RETURNED) {
            require(record.result == null && record.localReturn == null && record.localVerified == null)
            return
        }
        val raw = requireNotNull(record.localReturn)
        raw.destinationKey?.let(::requireBoundedKey)
        raw.diagnosticClass?.let { require(it.isNotEmpty() && it.length <= 256 && it.toByteArray(Charsets.UTF_8).size <= 256 && '\u0000' !in it) }
        raw.diagnosticMessage?.let { require(it.length <= 4096 && it.toByteArray(Charsets.UTF_8).size <= 4096 && '\u0000' !in it) }
        require(!raw.successful || (raw.invocationReturned && raw.diagnosticClass == null))
        require(!raw.reportedDurable || raw.successful)
        require(raw.invocationReturned || raw.diagnosticClass != null)
        require(raw.diagnosticClass != null || (raw.diagnosticMessage == null && !raw.diagnosticTruncated))
        val expected = if (!raw.invocationReturned || raw.diagnosticClass != null) SafEffectReturn.THREW
            else if (raw.successful) SafEffectReturn.COMPLETED else SafEffectReturn.REJECTED
        require(record.result == expected)
        if (record.phase == SafEffectPhase.RETURNED) { require(record.localVerified == null); return }
        require(expected == SafEffectReturn.COMPLETED)
        val verified = requireNotNull(record.localVerified)
        SafLocalEvidenceFormat.validateVersion(verified.destination)
        require(verified.forcesCompleted && verified.destination.key == raw.destinationKey)
        val plan = requireNotNull(record.plan.local)
        when (plan.effect) {
            SafLocalEffect.WRITE -> require(verified.destination == plan.sourceVersion && verified.contentDigest == plan.intended!!.identity.sha256 &&
                verified.inventoryDigest == null && verified.createdDirectories.isEmpty())
            SafLocalEffect.MOVE, SafLocalEffect.DELETE_STAGE -> require(verified.destination == plan.sourceVersion && verified.contentDigest == null &&
                verified.inventoryDigest == plan.beforeInventory!!.identity.sha256 && verified.createdDirectories.isEmpty())
            SafLocalEffect.CREATE_PARENTS, SafLocalEffect.CREATE_DIRECTORY -> {
                require(verified.destination.kind == SafLocalKind.DIRECTORY && verified.contentDigest == null && verified.inventoryDigest == null)
                require(verified.createdDirectories.size <= limits.treeDepth)
                require(verified.createdDirectories.map { it.path } == plan.missingDirectories)
                verified.createdDirectories.forEach { requireBoundedKey(it.key) }
                require(verified.createdDirectories.last().key == verified.destination.key)
            }
            else -> error("Unsupported local verification")
        }
    }

    private fun validateTreeReference(ref: SafEvidenceFile) {
        require(ref.name.matches(Regex("tree-[0-9a-f-]{36}\\.json")))
        requireUuid(ref.name.removePrefix("tree-").removeSuffix(".json"))
        requireBoundedKey(ref.identity.key)
        require(ref.identity.bytes in 1..limits.treeIndexBytes &&
            ref.identity.sha256.matches(Regex("[0-9a-f]{64}")))
    }

    private fun validateCreationLink(operation: Operation, plan: SafEffectPlan) {
        plan.creationActionId?.let { id ->
            requireUuid(id)
            val creation = requireNotNull(operation.effects[id])
            require(creation.plan.kind == SafEffectKind.CREATE_FILE && creation.plan.path == plan.path && creation.plan.parent == plan.parent &&
                creation.plan.parentCreationActionId == plan.parentCreationActionId)
        }
    }

    /** One exact same-operation destructive prerequisite; no disk I/O in this structural check. */
    internal fun replacementDeletion(operation: Operation, plan: SafEffectPlan): SafEffectRecord {
        val id = requireNotNull(plan.replacementDeleteActionId)
        requireUuid(id)
        val deletion = requireNotNull(operation.effects[id]) { "Missing same-operation SAF replacement deletion" }
        require(operation.envelope.request.kind == SafOperationKind.REFRESH &&
            plan.kind in setOf(SafEffectKind.CREATE_FILE, SafEffectKind.CREATE_DIRECTORY) &&
            plan.parent != null && plan.parentCreationActionId == null &&
            deletion.operationId == operation.id && deletion.actionId == id && deletion.plan.kind == SafEffectKind.DELETE &&
            deletion.plan.path == plan.path && deletion.plan.parent == plan.parent && deletion.plan.parentCreationActionId == null &&
            deletion.plan.beforeTree != null && deletion.plan.original != null &&
            (deletion.plan.original.mimeType == DIRECTORY_MIME) != (plan.kind == SafEffectKind.CREATE_DIRECTORY)) {
            "Invalid same-operation SAF replacement deletion binding"
        }
        return deletion
    }

    /** Direct receipt only; raw RETURNED/THREW must never call this after a provider effect. */
    internal fun admitReplacementDeletion(operation: Operation, plan: SafEffectPlan, checkActive: () -> Unit) {
        val deletion = replacementDeletion(operation, plan)
        require(deletion.phase == SafEffectPhase.VERIFIED && deletion.result == SafEffectReturn.COMPLETED &&
            deletion.providerReturn == SafProviderReturn(SafProviderReturnShape.BOOLEAN, boolean = true)) {
            "SAF replacement deletion is not verified"
        }
        check(read(operation, actionName(deletion.actionId, SafEffectPhase.VERIFIED), SafEffectRecord.serializer(), checkActive) == deletion) {
            "SAF replacement deletion receipt changed"
        }
    }

    private fun validateReturnedCreationLink(operation: Operation, record: SafEffectRecord) {
        if (record.plan.parentCreationActionId != null && record.phase == SafEffectPhase.VERIFIED)
            require(record.returnedDocument?.parentDocumentId == admittedParent(operation, record.plan).documentId)
        record.plan.creationActionId?.let { id ->
            if (record.phase == SafEffectPhase.VERIFIED)
                require(record.returnedDocument == operation.effects.getValue(id).returnedDocument)
        }
    }

    private fun parentCreation(operation: Operation, plan: SafEffectPlan): SafEffectRecord {
        val id = requireNotNull(plan.parentCreationActionId)
        requireUuid(id)
        val parent = requireNotNull(operation.effects[id]) { "Missing same-operation SAF parent creation" }
        require(parent.operationId == operation.id && parent.actionId == id && parent.plan.kind == SafEffectKind.CREATE_DIRECTORY &&
            parent.plan.requestedMimeType == DIRECTORY_MIME && parent.plan.path == plan.path.substringBeforeLast('/', "") &&
            parent.plan.path.isNotEmpty()) { "Invalid SAF parent-creation binding" }
        return parent
    }

    /** Cancellable whole-chain admission, never called by the raw RETURNED/THREW path. */
    internal fun admitParentChain(operation: Operation, plan: SafEffectPlan, requireVerified: Boolean, checkActive: () -> Unit) {
        if (plan.parentCreationActionId == null) return
        var current = plan
        var depth = 0
        while (current.parentCreationActionId != null) {
            checkActive()
            check(++depth <= limits.treeDepth)
            val parent = parentCreation(operation, current)
            if (requireVerified) require(parent.phase == SafEffectPhase.VERIFIED && parent.result == SafEffectReturn.COMPLETED)
            check(read(operation, actionName(parent.actionId, parent.phase), SafEffectRecord.serializer(), checkActive) == parent) {
                "SAF parent creation evidence changed"
            }
            current = parent.plan
        }
        require(current.parent != null)
        val nearestExisting = current.path.substringBeforeLast('/', "")
        require(operation.scopes.any { contains(it, nearestExisting) }) { "SAF parent chain exceeds its reserved namespace" }
    }

    /** Direct already-admitted identity only; callers perform durable receipt admission at the proper boundary. */
    internal fun admittedParent(operation: Operation, plan: SafEffectPlan): SafOwnedDocument = plan.parent ?: parentCreation(operation, plan).let {
        require(it.phase == SafEffectPhase.VERIFIED && it.result == SafEffectReturn.COMPLETED)
        requireNotNull(it.returnedDocument).also { parent -> require(parent.mimeType == DIRECTORY_MIME) }
    }

    private fun checkDirectCreationReceipts(operation: Operation, plan: SafEffectPlan, checkActive: () -> Unit) {
        listOfNotNull(plan.parentCreationActionId, plan.creationActionId).distinct().forEach { id ->
            checkActive()
            val receipt = requireNotNull(operation.effects[id])
            require(receipt.phase == SafEffectPhase.VERIFIED && receipt.result == SafEffectReturn.COMPLETED && receipt.returnedDocument != null)
            check(read(operation, actionName(id, SafEffectPhase.VERIFIED), SafEffectRecord.serializer(), checkActive) == receipt) {
                "SAF creation receipt changed"
            }
        }
        if (plan.replacementDeleteActionId != null) admitReplacementDeletion(operation, plan, checkActive)
    }

    private fun validateDocument(document: SafOwnedDocument) {
        PathSecurity.safeLeafName(document.name)
        validateMime(document.mimeType)
        require(document.uri.startsWith("content://") && document.documentId.isNotEmpty() &&
            document.mimeType.isNotEmpty() && document.parentDocumentId.isNotEmpty())
        require(listOf(document.uri, document.documentId, document.mimeType, document.parentDocumentId)
            .all { it.length <= 256 * 1024 && it.toByteArray(Charsets.UTF_8).size <= 256 * 1024 && '\u0000' !in it })
    }

    private fun validateMime(mime: String) {
        require(mime.length <= 255 && mime.toByteArray(Charsets.UTF_8).size <= 255 && mime.matches(Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+/[A-Za-z0-9!#$%&'*+.^_`|~-]+")))
    }

    private fun validateReservation(value: SafJournalReservation) {
        require(value.actions in 0..limits.actionsPerOperation)
        require(value.metadataBytes in 1..limits.aggregateMetadataBytes && value.payloadBytes in 0..limits.aggregatePayloadBytes)
        require(value.indexBytes in 1..limits.indexBytes)
        require(value.evidenceFiles in 0..limits.evidenceFilesPerOperation)
    }
    private fun validateScopes(scopes: List<String>) {
        require(scopes.isNotEmpty() && scopes.size <= 4 && scopes == scopes.distinct().sorted())
        scopes.forEach { validPath(it, true) }
    }
    private fun validateRequest(request: SafOperationRequest) {
        val size = when (request.kind) { SafOperationKind.REFRESH -> 0; SafOperationKind.MOVE -> 2; else -> 1 }
        require(request.paths.size == size)
        request.paths.forEach { validPath(it, false) }
    }
    private fun <T> encode(serializer: KSerializer<T>, value: T): ByteArray = SafJournalCodec.encode(serializer, value).also {
        check(it.size.toLong() <= limits.recordBytes) { "SAF record capacity exceeded" }
    }

    companion object {
        private val providerKinds = setOf(SafEffectKind.WRITE_FILE, SafEffectKind.CREATE_FILE, SafEffectKind.CREATE_DIRECTORY, SafEffectKind.DELETE)
        private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
        internal val manifestNames = setOf("manifest-plan.json", "manifest-candidate.json", "manifest-attempt.json", "committed.json")
        internal const val FORMAT_VERSION = 2
        private fun effectVersion(plan: SafEffectPlan) = if (plan.replacementDeleteActionId != null) 6 else if (plan.local?.deleteCustody != null) 5 else if (plan.kind == SafEffectKind.LOCAL_MUTATION) 4
            else if (plan.parentCreationActionId == null) FORMAT_VERSION else 3
        internal const val DIRECTORY_MIME = "vnd.android.document/directory"
        internal fun validatePayloadReference(ref: SafEvidenceFile) {
            require(ref.name.matches(Regex("payload-[0-9a-f-]{36}\\.bin")))
            requireUuid(ref.name.removePrefix("payload-").removeSuffix(".bin"))
            requireBoundedKey(ref.identity.key)
            require(ref.identity.bytes >= 0 && ref.identity.sha256.matches(Regex("[0-9a-f]{64}")))
        }
        private fun requireBoundedKey(key: String) {
            require(key.isNotEmpty() && key.length <= 4096 && key.toByteArray(Charsets.UTF_8).size <= 4096)
        }
        internal fun validPath(path: String, allowRoot: Boolean) {
            if (path.isEmpty()) { require(allowRoot); return }
            require(path.length <= 32 * 1024 && path.toByteArray(Charsets.UTF_8).size <= 32 * 1024)
            path.split('/').forEach { PathSecurity.safeLeafName(it) }
            require(path.substringBefore('/') !in setOf(".git", ".droide"))
        }
        internal fun contains(scope: String, path: String) = scope.isEmpty() || scope == path || path.startsWith("$scope/")
        internal fun overlaps(a: String, b: String) = contains(a, b) || contains(b, a)
        internal fun requireUuid(id: String) { require(UUID.fromString(id).toString() == id) }
        internal fun actionName(id: String, phase: SafEffectPhase) = "action-$id-${phase.ordinal}.json"
        internal fun boundedAdd(current: Long, add: Long, maximum: Long): Long {
            check(add >= 0 && current >= 0 && add <= maximum - current) { "SAF retained evidence capacity exceeded" }
            return current + add
        }
        internal fun legacyEntries(bytes: ByteArray, checkActive: () -> Unit = {}): Map<String, String> {
            checkActive()
            val codec = Json { ignoreUnknownKeys = false }
            val text = bytes.toString(Charsets.UTF_8)
            require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes))
            val value = codec.decodeFromString(SafLegacyTrustedManifest.serializer(), text)
            require(value.version == 2)
            require(codec.encodeToString(SafLegacyTrustedManifest.serializer(), value).toByteArray(Charsets.UTF_8).contentEquals(bytes) ||
                SafJournalCodec.encode(SafLegacyTrustedManifest.serializer(), value).contentEquals(bytes))
            SafDurableManifestCommit.validateEntries(value.entries, checkActive)
            return value.entries
        }

        /** Bootstrap is explicit, exclusive, and only imports the currently visible legacy baseline. */
        fun create(workspace: File, treeUri: String, directory: File, storage: SafOperationStorage = SafOperationStorage(),
            limits: SafJournalLimits = SafJournalLimits(), cancellationJob: Job? = null): SafOperationJournal {
            storage.requireRealAncestors(workspace)
            storage.requireRealAncestors(directory)
            require(treeUri.startsWith("content://") && '\u0000' !in treeUri)
            val rootKey = storage.key(workspace, true)
            val manifest = File(workspace, ".droide/saf_manifest.json")
            storage.requireRealAncestors(manifest)
            val baseline = if (storage.attributes(manifest) == null)
                SafJournalCodec.encode(SafLegacyTrustedManifest.serializer(), SafLegacyTrustedManifest())
            else storage.read(manifest, limits.manifestBytes)
            legacyEntries(baseline)
            val visible = if (storage.attributes(manifest) == null) null else storage.describe(manifest, limits.manifestBytes).also {
                check(it.sha256 == baseline.safDigest() && it.bytes == baseline.size.toLong())
            }
            storage.createDirectory(directory)
            val ref = SafEvidenceFile("baseline.bin", storage.writeNew(File(directory, "baseline.bin"), baseline))
            val anchor = SafJournalAnchor(FORMAT_VERSION, workspace.absoluteFile.toPath().normalize().toString(), rootKey, treeUri,
                storage.key(directory, true), ref, visible)
            val bytes = SafJournalCodec.encode(SafJournalAnchor.serializer(), anchor)
            check(bytes.size.toLong() <= limits.recordBytes)
            val identity = storage.writeNew(File(directory, "anchor.json"), bytes)
            return SafOperationJournal(workspace, directory, storage, limits, anchor, identity, cancellationJob)
        }

        fun open(workspace: File, treeUri: String, directory: File, storage: SafOperationStorage = SafOperationStorage(),
            limits: SafJournalLimits = SafJournalLimits(), cancellationJob: Job? = null): SafOperationJournal {
            storage.requireRealAncestors(directory)
            val raw = storage.read(File(directory, "anchor.json"), limits.recordBytes)
            val anchor = SafJournalCodec.decode(SafJournalAnchor.serializer(), raw)
            require(anchor.version == FORMAT_VERSION && anchor.workspace == workspace.absoluteFile.toPath().normalize().toString() &&
                anchor.treeUri == treeUri && anchor.baseline.name == "baseline.bin")
            anchor.baselineVisible?.let {
                require(it.key.isNotEmpty() && it.bytes == anchor.baseline.identity.bytes && it.sha256 == anchor.baseline.identity.sha256)
            }
            val journal = SafOperationJournal(workspace, directory, storage, limits, anchor,
                storage.describe(File(directory, "anchor.json"), limits.recordBytes), cancellationJob)
            journal.validateRoot()
            journal.checkEvidence(File(directory, "baseline.bin"), anchor.baseline)
            legacyEntries(storage.read(File(directory, "baseline.bin"), limits.manifestBytes))
            return journal
        }
    }
}
