package com.baystudio.droide.core

/** Main-owner operations only. The final invoke/commit/abort must never suspend. */
interface WorkspaceHandoffAdmission : () -> Unit {
    /** No-throw owner transfer, immediately before runtime publication. */
    fun commit()
    /** Idempotent; only this admission's still-tentative ownership can be restored. */
    fun abort()

    object Empty : WorkspaceHandoffAdmission {
        override fun invoke() = Unit
        override fun commit() = Unit
        override fun abort() = Unit
    }
}

/** One old editor awaiting actual view release or acknowledged recovery preservation. */
interface WorkspaceHandoffRetention {
    /** One explicit user switch attempt, never an automatic retry loop. */
    suspend fun retryPreservation()
}

class WorkspaceHandoffGate {
    private var retained: WorkspaceHandoffRetention? = null
    private var publishedOwner: Any? = null
    private var retentionGeneration = 0L
    private var publicationGeneration = 0L

    internal data class Observation(val retained: Boolean, val retentionGeneration: Long, val publicationGeneration: Long)

    /** Main-owner read only. Generations detect even a reserve/clear or publication ABA. */
    internal fun observe(): Observation = Observation(retained != null, retentionGeneration, publicationGeneration)
    internal fun requireUnchanged(expected: Observation) {
        check(observe() == expected) { "Editor handoff ownership changed during observation" }
    }

    fun requireClear() {
        check(retained == null) { "Previous editor release or late-change preservation is still pending" }
    }

    fun reserve(owner: WorkspaceHandoffRetention) {
        requireClear()
        retained = owner
        retentionGeneration++
    }

    /** Called before runtime StateFlow publication; plain assignment only. */
    fun publishOwner(owner: Any?) {
        publicationGeneration++
        if (retained != null) {
            publishedOwner = owner
        }
    }

    fun publicationFor(owner: WorkspaceHandoffRetention): Any? =
        if (retained === owner) publishedOwner else null

    /** The returned UI predicate retains only this gate and numeric generations, not A/B. */
    fun noticeValidityFor(owner: WorkspaceHandoffRetention, expectedPublication: Any): (() -> Boolean)? {
        if (retained !== owner || publishedOwner !== expectedPublication) return null
        val expectedRetentionGeneration = retentionGeneration
        val expectedPublicationGeneration = publicationGeneration
        return {
            retained != null && publishedOwner != null &&
                retentionGeneration == expectedRetentionGeneration &&
                publicationGeneration == expectedPublicationGeneration
        }
    }

    fun acknowledgePreservation(owner: WorkspaceHandoffRetention, expectedPublication: Any): Boolean {
        if (retained !== owner || publishedOwner !== expectedPublication) return false
        clear(owner)
        return true
    }

    fun clear(owner: WorkspaceHandoffRetention) {
        if (retained === owner) {
            retained = null
            retentionGeneration++
            publishedOwner = null
        }
    }

    suspend fun retryPending(currentOwner: Any?) {
        val pending = retained ?: return
        check(publishedOwner === currentOwner && currentOwner != null) {
            "Workspace ownership changed; pending unsaved editor changes are still retained"
        }
        pending.retryPreservation()
        requireClear()
    }
}
