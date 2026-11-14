package com.baystudio.droide.core

import java.util.concurrent.atomic.AtomicReference

/** Fatal for this resolve attempt: falling back must not launch another tool after unknown cleanup. */
internal class UserEnvironmentCleanupUnconfirmed(cause: Throwable) :
    IllegalStateException("User toolchain environment process cleanup is unconfirmed", cause)

internal object UserToolchainEnvironmentCleanup {
    /**
     * The supervisor invokes its completion receipt synchronously before returning/throwing.
     * A failed receipt is fatal even when the supervisor preserves bounded-capture semantics
     * and returns normally. Throw outside the capture's timeout cancellation, otherwise
     * withTimeoutOrNull could turn unknown cleanup into an apparently safe fallback.
     */
    suspend fun <T> capture(block: suspend ((Throwable?) -> Unit) -> T): T {
        val receiptFailure = AtomicReference<Throwable?>(null)
        var operationFailure: Throwable? = null
        try {
            return block { failure ->
                if (failure != null) receiptFailure.compareAndSet(null, failure)
            }
        } catch (failure: Throwable) {
            operationFailure = failure
            throw failure
        } finally {
            receiptFailure.get()?.let { failure ->
                val marker = UserEnvironmentCleanupUnconfirmed(failure)
                operationFailure?.takeUnless { it === failure }?.let(marker::addSuppressed)
                throw marker
            }
        }
    }
}
