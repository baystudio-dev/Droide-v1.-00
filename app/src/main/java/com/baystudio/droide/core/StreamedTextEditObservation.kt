package com.baystudio.droide.core

/** One invocation, not an execution permit. Caller must have completed hooks and exact permission review. */
internal class StreamedTextEditObservation(
    val repository: FileRepository,
    val path: String,
    val before: StreamFileVersion,
    val after: StreamContentVersion,
    val argumentsSha256: String,
    private val barrier: StreamedDiskCommitAuthority,
    private val requireAuthority: () -> Unit,
) : StreamPublicationObserver, StreamPublicationAdmission, StreamPublicationRevalidator,
    StreamPublicationCommitBarrier, StreamPublicationSettlementObserver {
    val invocationToken = Any()
    var preparation: StreamPublicationPreparation? = null
        private set
    var settlement: StreamPublicationSettlement? = null
        private set
    private var operationId: String? = null
    private var prepared = false
    private var backupVersion: StreamFileVersion? = null
    private var backupPath: String? = null
    fun bindBackup(path: String, version: StreamFileVersion) {
        check(backupVersion == null && operationId == null)
        backupPath = path; backupVersion = version
    }
    var admit: (suspend (StreamedTextEditObservation) -> Unit)? = null
    init { require(after.existed && argumentsSha256.matches(Regex("[a-f0-9]{64}"))) }
    override suspend fun preparePublication(preparation: StreamPublicationPreparation) {
        requireAuthority()
        check(!prepared); prepared = true
        require(preparation.relativePath == path && preparation.canonicalRoot == repository.root.canonicalPath)
        require(StreamContentVersion.of(preparation) == after)
        this.preparation = preparation
        checkNotNull(admit) { "Streamed edit journal admission is required" }(this)
        requireAuthority()
        check(operationId != null) { "Streamed edit intent was not admitted" }
    }
    fun bind(lease: SessionTextEditEvidence.Lease) {
        check(operationId == null && lease.invocationToken === invocationToken)
        require(lease.intent.streamPreparation === preparation || lease.intent.streamPreparation == preparation)
        operationId = lease.intent.operationId
    }
    override fun beforePublish(preparation: StreamPublicationPreparation) {
        check(this.preparation === preparation && operationId != null)
        requireAuthority()
    }
    override fun revalidateBeforePublication() {
        check(operationId != null)
        checkNotNull(backupVersion).requireMetadataMatches(repository.root, checkNotNull(backupPath))
        requireAuthority()
    }
    override suspend fun commitPublication(action: () -> Unit) = barrier.commitDiskOnly(path) {
        revalidateBeforePublication(); action()
    }
    override fun onPublished(receipt: StreamPublicationReceipt) {
        check(receipt.preparation === preparation && receipt.destinationIdentity == preparation?.stageIdentity)
    }
    override fun onSettled(settlement: StreamPublicationSettlement) {
        check(this.settlement == null)
        check(settlement.preparation == null || settlement.preparation === preparation ||
            preparation == null && !settlement.attempted && settlement.receipt == null)
        this.settlement = settlement
    }
}
