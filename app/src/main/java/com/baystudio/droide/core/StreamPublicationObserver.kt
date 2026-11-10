package com.baystudio.droide.core

import kotlinx.serialization.Serializable

internal interface StreamStagePlan {
    val plannedStagePath: String?
}

internal interface StreamPublicationRevalidator {
    fun revalidateBeforePublication()
}

interface StreamPublicationObserver {
    /** Must succeed immediately before canonical publication; failures forbid publication. */
    fun beforePublish(preparation: StreamPublicationPreparation)

    /** Called only after stage data, native publication and canonical-parent durability barriers. */
    fun onPublished(receipt: StreamPublicationReceipt)
}

@Serializable
data class StreamPublicationPreparation(
    val publicationId: String,
    val canonicalRoot: String,
    val rootIdentity: String,
    val relativePath: String,
    val bytes: Long,
    val sha256: String,
    val stageIdentity: String,
)

@Serializable
data class StreamPublicationReceipt(
    val preparation: StreamPublicationPreparation,
    val destinationIdentity: String,
)

/** A canonical publication may exist; this must never be reported as a safe precommit retry. */
class StreamPublicationUncertainException(
    val relativePath: String,
    val bytes: Long,
    val publicationReturned: Boolean,
    cause: Throwable,
) : java.nio.file.FileSystemException(relativePath, null,
    "Publication outcome or durability is uncertain at $relativePath. Preserve the destination; " +
        "inspect recovery before deciding on a new manual copy. Do not copy again automatically.",
) {
    init { initCause(cause) }
}

/** Durable preparation may suspend, but is always before the final synchronous publication gate. */
internal interface StreamPublicationAdmission {
    suspend fun preparePublication(preparation: StreamPublicationPreparation)
}

/** Repository-captured final fact under its original root guard. Never infer ownership by rereading later. */
internal data class StreamPublicationSettlement(
    val preparation: StreamPublicationPreparation?,
    val receipt: StreamPublicationReceipt?,
    val attempted: Boolean,
    val ownedCurrent: Boolean,
)
internal interface StreamPublicationSettlementObserver {
    /** In-memory observation only; must not block or reacquire a repository lock. */
    fun onSettled(settlement: StreamPublicationSettlement)
}

/** Staging/hash/fsync must stay outside. Action performs only final checks plus native rename. */
internal interface StreamPublicationCommitBarrier {
    suspend fun commitPublication(action: () -> Unit)
}

/** Mandatory editor-owner barrier for the streamed disk-only Agent route and its undo. */
interface StreamedDiskCommitAuthority {
    suspend fun commitDiskOnly(path: String, action: () -> Unit)
}
