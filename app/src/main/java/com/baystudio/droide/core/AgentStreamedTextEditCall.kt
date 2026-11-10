package com.baystudio.droide.core

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Durable intent and result for one streamed disk write, same session authority as legacy edits. */
internal class AgentStreamedTextEditCall(
    private val sessions: SessionManager,
    private val owner: SessionTextEditEvidence.Owner,
    private val isCurrent: () -> Boolean,
    private val publish: (SessionTextEditEvidence.Record, AgentTextEditAssociation) -> Unit,
) {
    private var lease: SessionTextEditEvidence.Lease? = null
    var deliveryFailure: Throwable? = null
        private set
    var association: AgentTextEditAssociation? = null
        private set
    fun attach(observation: StreamedTextEditObservation, backup: EditHistory.Snapshot) {
        check(lease == null && observation.admit == null && isCurrent())
        observation.admit = { actual ->
            check(actual === observation && lease == null && isCurrent())
            require(ProjectCopyEvidence.root(observation.repository.root).second == backup.streamRootIdentity)
            require(ProjectCopyEvidence.identity(backup.backup).key == backup.streamBackupIdentity)
            val relativeBackup = observation.repository.root.canonicalFile.toPath().relativize(backup.backup.absoluteFile.toPath()).toString()
            val version = StreamFileVersion.capture(observation.repository.root, relativeBackup)
            require(StreamContentVersion.of(version) == backup.streamBackup) { "Undo backup content changed before publication" }
            require(ProjectCopyEvidence.identity(backup.backup).key == backup.streamBackupIdentity)
            observation.bindBackup(relativeBackup, version)
            val admitted = sessions.admitStreamedTextEdit(owner, observation, backup, isCurrent)
            lease = admitted
            observation.bind(admitted)
            association = admitted.lastRecord.association()
            publish(admitted.lastRecord, requireNotNull(association))
            check(isCurrent()) { "Original streamed edit run changed after admission" }
        }
    }
    /** Runtime proof only; no deserialized Record can manufacture this call's invocation token. */
    class PublicationProof private constructor(
        val record: SessionTextEditEvidence.Record,
        val receipt: StreamPublicationReceipt,
    ) {
        companion object {
            internal fun admit(lease: SessionTextEditEvidence.Lease, observation: StreamedTextEditObservation): PublicationProof {
                check(lease.invocationToken === observation.invocationToken && lease.acknowledged)
                val state = checkNotNull(observation.settlement)
                val receipt = checkNotNull(state.receipt)
                check(state.ownedCurrent && receipt.preparation === observation.preparation)
                val record = lease.lastRecord
                check(record.outcomeDigest != null && record.problem == null && record.outcome?.disposition == "OWNED_CURRENT")
                check(record.intent.streamPreparation == receipt.preparation && record.intent.argumentsSha256 == observation.argumentsSha256)
                return PublicationProof(record, receipt)
            }
        }
    }
    fun publicationProof(observation: StreamedTextEditObservation): PublicationProof =
        PublicationProof.admit(checkNotNull(lease), observation)

    fun terminal(state: AgentStreamPartState): AgentTextEditAssociation? {
        val previous = association ?: return null
        val normal = state == AgentStreamPartState.COMPLETED && previous.metadataAcknowledged &&
            previous.localDisposition == "OWNED_CURRENT" && previous.repositoryReturn in setOf("RETURNED", "SYNC_FAILED")
        return previous.copy(normalTerminal = normal).also { result ->
            association = result
            lease?.let { admitted ->
                try { publish(admitted.lastRecord, result) }
                catch (failure: Exception) { deliveryFailure = failure; if (failure is kotlinx.coroutines.CancellationException) throw failure }
            }
        }
    }

    suspend fun finish(observation: StreamedTextEditObservation, failure: Throwable?): Throwable? {
        val admitted = lease ?: return null
        var metadataFailure: Throwable? = null
        try { withContext(NonCancellable) { sessions.completeStreamedTextEdit(admitted, observation, failure) } }
        catch (error: Throwable) { metadataFailure = error }
        if (metadataFailure != null && admitted.lastRecord.outcome == null) {
            admitted.lastRecord = SessionTextEditEvidence.Record(admitted.intent, admitted.intentDigest,
                admitted.store.streamOutcome(admitted, observation, failure), null, "Outcome metadata acknowledgement is uncertain")
        }
        association = admitted.lastRecord.association(admitted.acknowledged)
        try { publish(admitted.lastRecord, requireNotNull(association)) }
        catch (delivery: Throwable) { deliveryFailure = delivery }
        return metadataFailure
    }
}
