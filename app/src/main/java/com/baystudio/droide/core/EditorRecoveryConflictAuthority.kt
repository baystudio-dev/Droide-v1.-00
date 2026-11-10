package com.baystudio.droide.core

import java.util.concurrent.atomic.AtomicLong

class EditorRecoveryCheckpointDeferredException(message: String) : IllegalStateException(message)

/** Runtime-only revocation. No lease is serialized or reconstructed from untrusted metadata. */
class EditorRecoveryConflictAuthority {
    private val epoch = AtomicLong()
    data class Revision internal constructor(internal val owner: EditorRecoveryConflictAuthority, internal val value: Long) {
        internal fun requireCurrent() {
            if (owner.epoch.get() != value) throw EditorRecoveryCheckpointDeferredException("Recovery copies changed after this checkpoint was captured; capture again")
        }
    }
    internal fun capture() = Revision(this, epoch.get())
    internal fun revoke() { epoch.incrementAndGet() }
}
