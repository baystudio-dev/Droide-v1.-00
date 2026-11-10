package com.baystudio.droide.core

internal fun interface WorkspaceMutationParticipant {
    suspend fun begin(guard: WorkspaceMutationGuard, mutation: FileRepository.Mutation): WorkspaceMutationSession
}

internal enum class WorkspaceLocalEffect { CREATE_PARENTS, WRITE, CREATE_DIRECTORY, DELETE_STAGE, MOVE }

/** An observation/preparation, not a claim of durable publication or permission to replay. */
internal data class WorkspaceLocalPreparation(
    val effect: WorkspaceLocalEffect,
    val relativePath: String,
    val sourcePath: String? = null,
    val sourceIdentity: String? = null,
    val bytes: Long? = null,
    val sha256: String? = null,
    val stream: StreamPublicationPreparation? = null,
)

internal data class WorkspaceLocalReceipt(
    val preparation: WorkspaceLocalPreparation,
    val returned: Boolean,
    val destinationIdentity: String?,
    val durable: Boolean = false,
    val failure: Throwable? = null,
    val successful: Boolean = returned && failure == null,
)

internal data class WorkspaceMutationOutcome(
    val localAttempted: Boolean,
    val lastLocalReceipt: WorkspaceLocalReceipt?,
    val externalEffectAttempted: Boolean,
    val failure: Throwable?,
)

internal abstract class WorkspaceMutationSession(
    val guard: WorkspaceMutationGuard,
    val mutation: FileRepository.Mutation,
) {
    var externalEffectAttempted: Boolean = false
        private set

    /** Pure, nonthrowing disposition once durable local evidence forbids repository rollback. */
    open val retainLocalOnSyncFailure: Boolean get() = false

    /** Optional Delete-only destination, selected before the existing local move. */

    open suspend fun prepareDeleteStage(): String? = null

    fun markExternalEffectAttempted() {
        guard.requireRoot(guard.canonicalRoot)
        externalEffectAttempted = true
    }

    /** Potentially large snapshot/verification work belongs here, in the caller's cancellable context. */
    open suspend fun prepareLocal(preparation: WorkspaceLocalPreparation) {}
    /** Mandatory, small synchronous records only; throwing prevents the local invocation. */
    open fun beforeLocalAttempt(preparation: WorkspaceLocalPreparation) {}
    /** Small receipt only; returned=false cannot prove that the local invocation had no effect. */
    open fun localReturned(receipt: WorkspaceLocalReceipt) {}
    open suspend fun synchronize() {}
    /** True takes durable custody of that stage; the repository must not recursively clean it. */

    open fun retainsDeleteStage(receipt: WorkspaceLocalReceipt): Boolean = false
    /** Exactly once after begin returns a valid session, before releasing ownership/IO dispatch. */
    open fun finish(outcome: WorkspaceMutationOutcome) {}
}
