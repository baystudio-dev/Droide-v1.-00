package com.baystudio.droide.core

import java.io.File
import kotlinx.serialization.Serializable

@Serializable internal data class SafManifestImage(
    val version: Int, val operationId: String, val revision: Long, val parentRevision: Long,
    val parentDigest: String, val entries: Map<String, String>,
)
@Serializable internal data class SafManifestDelta(val version: Int, val edits: Map<String, String?>)
@Serializable internal data class SafManifestPlan(
    val version: Int, val operationId: String, val envelopeDigest: String, val trusted: SafEvidenceFile,
    val planned: SafEvidenceFile, val delta: SafEvidenceFile, val revision: Long, val parentRevision: Long,
    val parentDigest: String, val expectedVisible: SafStoredFile?, val metadataKey: String,
    val candidateBytes: Long,
)
@Serializable internal data class SafManifestCandidate(
    val version: Int, val operationId: String, val planDigest: String, val name: String, val identity: SafStoredFile,
)
@Serializable internal data class SafManifestAttempt(val version: Int, val operationId: String, val candidateDigest: String)
@Serializable internal data class SafManifestAcknowledgement(
    val version: Int, val operationId: String, val planDigest: String, val candidateDigest: String,
    val visible: SafStoredFile, val revision: Long,
)

/** Results are observations, never inputs conferring publication or cleanup authority. */
internal data class SafManifestCommitResult(val operationId: String, val revision: Long, val acknowledged: Boolean,
    val retainedAcknowledgementFailure: Throwable?)

/**
 * Isolated protocol core. Caller must hold the root-shared lease (Phase C). No provider replay,
 * rollback, evidence deletion, or local-publication durability claim is made by this class.
 */
internal class SafDurableManifestCommit(private val journal: SafOperationJournal) {
    private val storage get() = journal.storage
    private val metadata get() = File(journal.workspace, ".droide")
    private val visible get() = File(metadata, "saf_manifest.json")

    internal data class Trusted(val bytes: ByteArray, val entries: Map<String, String>, val revision: Long, val digest: String)

    /** Latest acknowledged ancestry, never the latest-looking visible/pending manifest. */
    internal fun trusted(checkActive: () -> Unit = {}): Trusted = trusted(journal.recoverAdmission(checkActive), checkActive)

    internal fun trusted(operations: List<SafOperationJournal.Operation>, checkActive: () -> Unit): Trusted =
        trusted(SafOperationJournal.Admission(operations, SafJournalRetirement(journal).load(checkActive)), checkActive)

    internal fun trusted(admission: SafOperationJournal.Admission, checkActive: () -> Unit): Trusted {
        checkActive()
        val retirement = SafJournalRetirement(journal)
        val operations = admission.operations
        val head = admission.head
        operations.forEach { retirement.admitOperation(head, it, checkActive) }
        val base = head?.image ?: storage.read(File(journal.directory, "baseline.bin"), journal.limits.manifestBytes, checkActive)
        journal.checkEvidence(File(journal.directory, "baseline.bin"), journal.anchor.baseline, checkActive)
        var current = if (head == null) Trusted(base, SafOperationJournal.legacyEntries(base, checkActive), 0, base.safDigest(checkActive))
            else Trusted(base, SafJournalCodec.decode(SafManifestImage.serializer(), base).entries,
                head.checkpoint.revision, head.checkpoint.digest)
        val ancestry = hashSetOf(current.revision to current.digest)
        val revisions = linkedSetOf<Long>()
        val planned = operations.filter { "manifest-plan.json" in it.files }.map { operation ->
            checkActive()
            val plan = journal.read(operation, "manifest-plan.json", SafManifestPlan.serializer(), checkActive)
            check(revisions.add(plan.revision)) { "Ambiguous SAF manifest revision; evidence retained" }
            if (head != null && head.checkpoint.pins.none { it.id == operation.id && it.planDigest != null })
                check(plan.revision > head.checkpoint.highwater) { "New SAF plan reused a checkpointed revision" }
            operation to plan
        }
        for ((operation, plan) in planned.filter { "committed.json" in it.first.files }.sortedBy { it.second.revision }) {
            checkActive()
            if (head != null && plan.revision <= head.checkpoint.revision) {
                check(head.checkpoint.pins.any { it.id == operation.id && it.committedDigest != null }) {
                    "Uncovered SAF acknowledgement predates checkpoint"
                }
                continue // Retained tail is already covered, not a descendant to replay.
            }
            check(plan.parentDigest == current.digest && plan.parentRevision == current.revision) {
                "SAF acknowledgement does not descend from the last trusted manifest"
            }
            val bytes = storage.read(journal.file(operation, plan.planned.name), journal.limits.manifestBytes, checkActive)
            val image = SafJournalCodec.decode(SafManifestImage.serializer(), bytes)
            current = Trusted(bytes, image.entries, image.revision, bytes.safDigest(checkActive))
            check(ancestry.add(current.revision to current.digest))
        }
        planned.forEach { (operation, plan) ->
            checkActive()
            val pinnedParent = head?.checkpoint?.pins?.any { it.id == operation.id && it.planDigest != null &&
                it.parentRevision == plan.parentRevision && it.parentDigest == plan.parentDigest } == true
            check(pinnedParent || (plan.parentRevision to plan.parentDigest) in ancestry) {
                "SAF pending manifest invents an unknown trusted ancestor"
            }
        }
        return current
    }

    /** Before any new operation/effect, admit global lineage and the exact currently visible inode. */
    internal fun admitBeforeEffects(operations: List<SafOperationJournal.Operation>, checkActive: () -> Unit): SafStoredFile? {
        return admitBeforeEffects(SafOperationJournal.Admission(operations, SafJournalRetirement(journal).load(checkActive)), checkActive)
    }

    internal fun admitBeforeEffects(admission: SafOperationJournal.Admission, checkActive: () -> Unit): SafStoredFile? {
        val base = trusted(admission, checkActive)
        storage.requireRealAncestors(visible)
        return visibleIdentity(checkActive).also { requireKnownVisible(base, admission.operations, it, admission.head, checkActive) }
    }

    /** Long preparation may not silently adopt a changed manifest, even with identical bytes. */
    internal data class VisibleContinuity(val file: SafLocalVersion?, val parent: SafLocalVersion?)

    internal fun requireAdmittedVisible(expected: SafStoredFile?, checkActive: () -> Unit): VisibleContinuity {
        storage.requireRealAncestors(visible)
        check(visibleIdentity(checkActive) == expected) { "SAF visible manifest changed after operation admission" }
        return VisibleContinuity(SafLocalFiles.version(storage, visible), SafLocalFiles.version(storage, metadata))
    }

    /** Direct continuation of the admitted content/identity proof, with no caller or read callback. */
    internal fun requireVisibleContinuity(expected: VisibleContinuity) {
        journal.ensureVerificationActive()
        check(SafLocalFiles.version(storage, visible) == expected.file &&
            SafLocalFiles.version(storage, metadata) == expected.parent) { "SAF visible namespace changed at allocation" }
    }

    /** Creates the exact private plan first. Its delta may touch only this operation's admitted scopes. */
    fun plan(operation: SafOperationJournal.Operation, delta: Map<String, String?>, checkActive: () -> Unit = {}) {
        journal.ownedWrite(operation, checkActive) {
            val poll = journal.admissionPoll(checkActive)
            check(operation.activeInvocation) { "Recovered SAF effects require explicit reconciliation, not a new manifest plan" }
            check(!journal.isTerminal(operation) && "manifest-plan.json" !in operation.files)
            check(operation.effects.values.all { poll(); it.phase == SafEffectPhase.VERIFIED })
            val admission = journal.recoverAdmission(poll)
            val operations = admission.operations
            val disjointLinks = linkedMapOf<String, SafLocalVersion?>()
            checkDisjoint(operation, operations, poll, disjointLinks)
            val base = trusted(admission, poll)
            poll()
            require(delta.size <= 100_000)
            val edits = sortedMapOf<String, String?>().apply {
                delta.forEach { (path, value) -> poll(); put(path, value) }
            }
            edits.forEach { (path, value) ->
                poll()
                SafOperationJournal.validPath(path, false)
                require(operation.scopes.any { SafOperationJournal.contains(it, path) })
                if (value != null) validateFingerprint(value)
            }
            val desired = applyDelta(base.entries, edits, poll)
            validateEntries(desired, poll)
            val previousRevision = maxOf(admission.head?.checkpoint?.highwater ?: 0L,
                operations.filter { "manifest-plan.json" in it.files }.maxOfOrNull {
                journal.read(it, "manifest-plan.json", SafManifestPlan.serializer(), poll).revision
            } ?: 0L)
            check(previousRevision < Long.MAX_VALUE)
            val revision = previousRevision + 1
            storage.requireRealAncestors(metadata)
            val expected = visibleIdentity(poll)
            requireKnownVisible(base, operations, expected, admission.head, poll)
            val image = SafManifestImage(3, operation.id, revision, base.revision, base.digest, desired)
            val imageBytes = SafJournalCodec.encode(SafManifestImage.serializer(), image)
            val deltaBytes = SafJournalCodec.encode(SafManifestDelta.serializer(), SafManifestDelta(1, edits))
            poll()
            check(imageBytes.size.toLong() <= journal.limits.manifestBytes && deltaBytes.size.toLong() <= journal.limits.manifestBytes)
            // Charge the additional namespace candidate before any planning file is written.
            // Its reservation remains charged even after replacement or an uncertain partial write.
            val total = base.bytes.size.toLong() + imageBytes.size.toLong() * 2 + deltaBytes.size
            check(total <= operation.envelope.reservation.payloadBytes - operation.payloadBytes)
            poll()
            val cancel = journal::ensureVerificationActive
            val renewed = journal.renewAdmission(admission)
            if (renewed !== admission) {
                val latest = trusted(renewed, cancel)
                check(latest.revision == base.revision && latest.digest == base.digest)
            }
            journal.validate(operation, cancel)
            val visibleContinuity = requireAdmittedVisible(expected, cancel)
            SafJournalRetirement(journal).reseal(admission.head)
            journal.requireNamespaceUnchanged(renewed)
            journal.requireDisjointLinks(disjointLinks)
            requireVisibleContinuity(visibleContinuity)
            // Only captured Job cancellation may run after this cut, including nested writes.
            // No observed admission state is reused after the first actual mutation.
            if (storage.attributes(metadata) == null) storage.createDirectory(metadata)
            val metadataKey = storage.key(metadata, true)
            val trustedRef = journal.writeAdmittedTrustedBase(operation, base.bytes)
            val plannedRef = journal.bytes(operation, "planned.bin", imageBytes, cancel)
            val deltaRef = journal.bytes(operation, "delta.bin", deltaBytes, cancel)
            val record = SafManifestPlan(1, operation.id, operation.envelopeIdentity.sha256, trustedRef, plannedRef,
                deltaRef, revision, base.revision, base.digest, expected, metadataKey, imageBytes.size.toLong())
            journal.put(operation, "manifest-plan.json", SafManifestPlan.serializer(), record, cancel)
            journal.chargeManifestCandidate(operation, record.candidateBytes)
        }
    }

    /** All errors before a durability receipt retain evidence and do not authorize undo. */
    fun publish(operation: SafOperationJournal.Operation, checkActive: () -> Unit = {}): SafManifestCommitResult {
        return journal.ownedWrite(operation, checkActive) {
            check(operation.activeInvocation) { "Recovered SAF plans cannot repeat a publication attempt" }
            check(!journal.isTerminal(operation) && "manifest-attempt.json" !in operation.files)
            val plan = admittedPlan(operation, checkActive)
            requireCurrentParent(operation, plan, checkActive)
            check(visibleIdentity(checkActive) == plan.expectedVisible) { "SAF manifest changed before publication" }
            val bytes = storage.read(journal.file(operation, plan.planned.name), journal.limits.manifestBytes, checkActive)
            journal.checkEvidence(journal.file(operation, plan.planned.name), plan.planned, checkActive)
            val name = ".saf-manifest-${operation.id}.candidate"
            val candidateFile = File(metadata, name)
            check(plan.candidateBytes == bytes.size.toLong())
            val candidateIdentity = storage.writeNew(candidateFile, bytes, checkActive)
            val candidate = SafManifestCandidate(1, operation.id, planDigest(operation, checkActive), name, candidateIdentity)
            journal.put(operation, "manifest-candidate.json", SafManifestCandidate.serializer(), candidate, checkActive)
            journal.put(operation, "manifest-attempt.json", SafManifestAttempt.serializer(),
                SafManifestAttempt(1, operation.id, candidateDigest(operation, checkActive)), checkActive)
            // Durable ATTEMPTING precedes the first publication move. No replacement retry is exposed.
            requireMetadata(plan, checkActive)
            check(visibleIdentity(checkActive) == plan.expectedVisible)
            check(storage.describe(candidateFile, journal.limits.manifestBytes, checkActive) == candidate.identity)
            checkActive()
            // The single move and final force/receipt sequence deliberately do not poll cancellation.
            // All substantial historical/payload validation above remains cancellable.
            storage.replace(candidateFile, visible)
            completeBarrierAndAcknowledge(operation, plan, candidate)
        }
    }

    /** Retry only the force barrier on the identical already-visible owned file. Never publish/replay. */
    fun retryExactBarrier(operation: SafOperationJournal.Operation, checkActive: () -> Unit = {}): SafManifestCommitResult {
        return journal.ownedWrite(operation, checkActive) {
            check(!journal.isTerminal(operation) && "manifest-attempt.json" in operation.files)
            val plan = admittedPlan(operation, checkActive)
            requireCurrentParent(operation, plan, checkActive)
            val candidate = journal.read(operation, "manifest-candidate.json", SafManifestCandidate.serializer(), checkActive)
            check(visibleIdentity(checkActive) == candidate.identity) { "A newer or different SAF manifest blocks the old exact-barrier retry" }
            checkActive()
            completeBarrierAndAcknowledge(operation, plan, candidate)
        }
    }

    private fun completeBarrierAndAcknowledge(operation: SafOperationJournal.Operation, plan: SafManifestPlan,
        candidate: SafManifestCandidate): SafManifestCommitResult {
        requireMetadata(plan)
        check(visibleIdentity() == candidate.identity)
        storage.syncFile(visible)
        storage.syncDirectory(metadata)
        storage.syncDirectory(journal.workspace) // Includes the possibly new .droide link.
        requireMetadata(plan)
        check(visibleIdentity() == candidate.identity)
        // This branch is the sole authority to write acknowledgement: all exact publication barriers returned.
        val failure = try {
            // Includes acknowledgement construction: digest reads can fail after publication is durable.
            val acknowledgement = SafManifestAcknowledgement(1, operation.id, planDigest(operation),
                candidateDigest(operation), candidate.identity, plan.revision)
            journal.put(operation, "committed.json", SafManifestAcknowledgement.serializer(), acknowledgement)
            null
        } catch (failure: Throwable) {
            operation.poisoned = true
            failure
        }
        return SafManifestCommitResult(operation.id, plan.revision, failure == null, failure)
    }

    private fun requireCurrentParent(operation: SafOperationJournal.Operation, plan: SafManifestPlan, checkActive: () -> Unit) {
        requireMetadata(plan, checkActive)
        val admission = journal.recoverAdmission(checkActive)
        val operations = admission.operations
        checkDisjoint(operation, operations, checkActive)
        val current = trusted(admission, checkActive)
        check(current.digest == plan.parentDigest && current.revision == plan.parentRevision) {
            "SAF trusted ancestry advanced; old manifest publication is blocked"
        }
    }
    private fun checkDisjoint(operation: SafOperationJournal.Operation, all: List<SafOperationJournal.Operation>, checkActive: () -> Unit,
        continuity: MutableMap<String, SafLocalVersion?>? = null) {
        all.filter { it.id != operation.id && !journal.isTerminal(it) }.forEach { sibling ->
            checkActive()
            journal.requireDisjointFromPending(operation.envelope.request, operation.scopes,
                operation.effects.values.map { it.plan }, sibling, checkActive, continuity)
        }
    }
    private fun requireKnownVisible(base: Trusted, operations: List<SafOperationJournal.Operation>, actual: SafStoredFile?,
        head: SafJournalRetirement.State?, checkActive: () -> Unit) {
        val trustedVisible = if (base.revision == 0L) journal.anchor.baselineVisible
        else if (head != null && base.revision == head.checkpoint.revision) {
            check(base.digest == head.checkpoint.digest)
            head.checkpoint.visible
        } else {
            val latest = operations.single { "committed.json" in it.files &&
                journal.read(it, "committed.json", SafManifestAcknowledgement.serializer(), checkActive).revision == base.revision }
            journal.read(latest, "committed.json", SafManifestAcknowledgement.serializer(), checkActive).visible
        }
        val recognizedPending = actual != null && operations.any {
            checkActive()
            !journal.isTerminal(it) && "manifest-attempt.json" in it.files && "manifest-candidate.json" in it.files &&
                journal.read(it, "manifest-candidate.json", SafManifestCandidate.serializer(), checkActive).identity == actual
        }
        check(actual == trustedVisible || recognizedPending) { "Unowned SAF manifest identity cannot become a trusted baseline" }
    }
    private fun requireMetadata(plan: SafManifestPlan, checkActive: () -> Unit = {}) {
        journal.validateRoot(checkActive)
        storage.requireRealAncestors(metadata)
        check(storage.key(metadata, true) == plan.metadataKey)
    }
    private fun visibleIdentity(checkActive: () -> Unit = {}): SafStoredFile? = if (storage.attributes(visible) == null) null
        else storage.describe(visible, journal.limits.manifestBytes, checkActive)
    private fun planDigest(operation: SafOperationJournal.Operation, checkActive: () -> Unit = {}) =
        storage.describe(journal.file(operation, "manifest-plan.json"), journal.limits.recordBytes, checkActive).sha256
    private fun candidateDigest(operation: SafOperationJournal.Operation, checkActive: () -> Unit = {}) =
        storage.describe(journal.file(operation, "manifest-candidate.json"), journal.limits.recordBytes, checkActive).sha256
    private fun admittedPlan(operation: SafOperationJournal.Operation, checkActive: () -> Unit): SafManifestPlan {
        admit(journal, operation, checkActive)
        return journal.read(operation, "manifest-plan.json", SafManifestPlan.serializer(), checkActive)
    }

    companion object {
        internal fun validateEntries(entries: Map<String, String>, checkActive: () -> Unit = {}) {
            require(entries.size <= 100_000)
            entries.forEach { (path, fingerprint) ->
                checkActive()
                SafOperationJournal.validPath(path, false)
                validateFingerprint(fingerprint)
                var parent = path.substringBeforeLast('/', "")
                while (parent.isNotEmpty()) {
                    checkActive()
                    require(entries[parent] == "d") { "SAF manifest has a missing or non-directory ancestor" }
                    parent = parent.substringBeforeLast('/', "")
                }
            }
        }
        private fun validateFingerprint(value: String) { require(value == "d" || value.matches(Regex("f:[0-9a-f]{64}"))) }
        private fun applyDelta(base: Map<String, String>, delta: Map<String, String?>, checkActive: () -> Unit): Map<String, String> =
            sortedMapOf<String, String>().apply {
                base.forEach { (path, value) -> checkActive(); put(path, value) }
                delta.forEach { (path, value) -> checkActive(); if (value == null) remove(path) else put(path, value) }
            }

        /** Strict admission never replays provider work or deletes uncertain evidence. */
        internal fun admit(journal: SafOperationJournal, operation: SafOperationJournal.Operation, checkActive: () -> Unit = {}) =
            admit(journal, operation, checkActive, forceDurability = true)

        internal fun admit(journal: SafOperationJournal, operation: SafOperationJournal.Operation,
            checkActive: () -> Unit, forceDurability: Boolean) {
            checkActive()
            val files = operation.files
            if ("manifest-plan.json" !in files) {
                require(files.none { it in SafOperationJournal.manifestNames || it in setOf("trusted.bin", "planned.bin", "delta.bin") })
                return
            }
            val plan = journal.read(operation, "manifest-plan.json", SafManifestPlan.serializer(), checkActive)
            require(plan.version == 1 && plan.operationId == operation.id && plan.envelopeDigest == operation.envelopeIdentity.sha256 &&
                plan.revision > plan.parentRevision && plan.parentRevision >= 0 && plan.metadataKey.isNotEmpty())
            require(plan.candidateBytes == plan.planned.identity.bytes)
            journal.chargeManifestCandidate(operation, plan.candidateBytes)
            val candidateFile = File(journal.workspace, ".droide/.saf-manifest-${operation.id}.candidate")
            journal.storage.requireRealAncestors(candidateFile)
            journal.storage.attributes(candidateFile)?.let {
                require(it.isRegularFile && it.size() in 0..plan.candidateBytes) { "Retained SAF candidate exceeds its reservation" }
            }
            plan.expectedVisible?.let { require(it.key.isNotEmpty() && it.bytes in 0..journal.limits.manifestBytes &&
                it.sha256.matches(Regex("[0-9a-f]{64}"))) }
            require(operation.effects.values.all { checkActive(); it.phase == SafEffectPhase.VERIFIED })
            require(plan.trusted.name == "trusted.bin" && plan.planned.name == "planned.bin" && plan.delta.name == "delta.bin")
            listOf(plan.trusted, plan.planned, plan.delta).forEach {
                require(it.identity.bytes <= journal.limits.manifestBytes)
                journal.checkEvidence(journal.file(operation, it.name), it, checkActive)
            }
            val baseBytes = journal.storage.read(journal.file(operation, "trusted.bin"), journal.limits.manifestBytes, checkActive)
            require(baseBytes.safDigest(checkActive) == plan.parentDigest)
            val base = if (plan.parentRevision == 0L) SafOperationJournal.legacyEntries(baseBytes, checkActive)
                else SafJournalCodec.decode(SafManifestImage.serializer(), baseBytes).also {
                    require(it.version == 3 && it.revision == plan.parentRevision)
                }.entries
            val planned = SafJournalCodec.decode(SafManifestImage.serializer(),
                journal.storage.read(journal.file(operation, "planned.bin"), journal.limits.manifestBytes, checkActive))
            val delta = SafJournalCodec.decode(SafManifestDelta.serializer(),
                journal.storage.read(journal.file(operation, "delta.bin"), journal.limits.manifestBytes, checkActive))
            require(delta.version == 1 && delta.edits.size <= 100_000 && delta.edits.keys.toList() == delta.edits.keys.sorted())
            delta.edits.forEach { (path, value) ->
                checkActive()
                SafOperationJournal.validPath(path, false)
                require(operation.scopes.any { SafOperationJournal.contains(it, path) })
                if (value != null) validateFingerprint(value)
            }
            require(planned == SafManifestImage(3, operation.id, plan.revision, plan.parentRevision,
                plan.parentDigest, applyDelta(base, delta.edits, checkActive)))
            validateEntries(planned.entries, checkActive)
            require(planned.entries.keys.toList() == planned.entries.keys.sorted())
            if ("manifest-candidate.json" !in files) {
                require("manifest-attempt.json" !in files && "committed.json" !in files)
                return
            }
            val candidate = journal.read(operation, "manifest-candidate.json", SafManifestCandidate.serializer(), checkActive)
            val planDigest = journal.storage.describe(journal.file(operation, "manifest-plan.json"), journal.limits.recordBytes, checkActive).sha256
            require(candidate.version == 1 && candidate.operationId == operation.id && candidate.planDigest == planDigest &&
                candidate.name == ".saf-manifest-${operation.id}.candidate" && candidate.identity.bytes == plan.planned.identity.bytes &&
                candidate.identity.sha256 == plan.planned.identity.sha256 && candidate.identity.key.isNotEmpty())
            if (journal.storage.attributes(candidateFile) != null)
                journal.checkEvidence(candidateFile, SafEvidenceFile(candidate.name, candidate.identity), checkActive)
            if ("manifest-attempt.json" !in files) { require("committed.json" !in files); return }
            val candidateDigest = journal.storage.describe(journal.file(operation, "manifest-candidate.json"), journal.limits.recordBytes, checkActive).sha256
            require(journal.read(operation, "manifest-attempt.json", SafManifestAttempt.serializer(), checkActive) ==
                SafManifestAttempt(1, operation.id, candidateDigest))
            if ("committed.json" in files) {
                require(journal.read(operation, "committed.json", SafManifestAcknowledgement.serializer(), checkActive) ==
                    SafManifestAcknowledgement(1, operation.id, planDigest, candidateDigest, candidate.identity, plan.revision))
                checkActive()
                if (forceDurability) {
                    // Mutation admission must repair a possibly interrupted acknowledgement force.
                    journal.storage.syncFile(journal.file(operation, "committed.json"))
                    journal.storage.syncDirectory(journal.file(operation, "committed.json").parentFile!!)
                }
            }
        }
    }
}
