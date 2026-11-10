package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException

/** Final storage step after exact hooks/permission/schema admission; never executes model deltas. */
internal object StreamedDiskEditTransaction {
    data class Result(val synchronizationPending: Boolean, val receipt: StreamPublicationReceipt)
    suspend fun execute(
        files: FileRepository,
        history: EditHistory,
        pending: EditHistory.PendingBatch,
        observation: StreamedTextEditObservation,
        call: AgentStreamedTextEditCall,
        sourceSession: OwnedResponseContentStore.Session,
        content: StreamedFileArgumentExtractor.Source,
    ): Result {
        require(observation.repository === files && content.kind == OwnedResponseContentStore.Kind.TOOL_CONTENT)
        require(observation.after == StreamContentVersion(true, content.bytes, content.sha256))
        require(pending.snapshots.size == 1 && pending.snapshots.single().path == observation.path)
        content.verify(sourceSession)
        call.attach(observation, pending.snapshots.single())
        var failure: Throwable? = null
        try {
            files.writeStream(observation.path, sourceSession.openBytes(content.handle), content.bytes,
                expectedVersion = observation.before, publicationObserver = observation)
        } catch (error: Throwable) { failure = error }
        if (failure != null && failure !is Exception) throw failure
        val metadataFailure = call.finish(observation, failure)
        if (metadataFailure != null) {
            if (failure == null) failure = metadataFailure else if (failure !== metadataFailure) failure.addSuppressed(metadataFailure)
        }
        val state = observation.settlement
        val receipt = state?.receipt
        if (state?.ownedCurrent != true || receipt == null) {
            if (failure is CancellationException) {
                failure.addSuppressed(IllegalStateException("Streamed edit outcome unverified; undo backup retained; do not retry automatically"))
                throw failure
            }
            throw IllegalStateException("Streamed edit outcome unverified; undo backup retained; do not retry automatically", failure)
        }
        history.commitStream(pending, receipt)
        if (metadataFailure != null) throw IllegalStateException("File has an attributable receipt but outcome acknowledgement is uncertain; do not repeat write", failure)
        call.deliveryFailure?.let { if (it is CancellationException || it !is Exception) throw it }
        if (failure is ProjectFileSyncException && failure.relativePath == observation.path && failure.bytes == content.bytes)
            return Result(true, receipt)
        if (failure != null) throw failure
        return Result(false, receipt)
    }
}
