package com.baystudio.droide.core

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Restore user defaults even when provisioning fails or is canceled, without hiding the original failure. */
internal suspend fun <T> withInstallerPrerequisiteRestoration(
    timeoutMs: Long = 60_000,
    restore: suspend () -> Unit,
    block: suspend () -> T,
): T {
    var originalFailure: Throwable? = null
    try {
        return block()
    } catch (failure: Throwable) {
        originalFailure = failure
        throw failure
    } finally {
        try {
            withContext(NonCancellable) { withTimeout(timeoutMs) { restore() } }
        } catch (restorationFailure: Throwable) {
            val original = originalFailure
            if (original != null) original.addSuppressed(restorationFailure)
            else throw IllegalStateException("Package transaction completed, but the previous prerequisite default could not be restored", restorationFailure)
        }
    }
}
