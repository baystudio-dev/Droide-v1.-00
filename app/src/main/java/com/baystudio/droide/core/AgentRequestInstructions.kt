package com.baystudio.droide.core

/** Request-owned refresh, not a shared cache. No hook invocation or retained rule state. */
internal object AgentRequestInstructions {
    suspend fun refresh(
        hookContext: String,
        ensureCurrent: () -> Unit,
        load: suspend () -> String,
    ): String {
        ensureCurrent()
        val current = load()
        // Loading may suspend: never return a stale run's instructions for publication.
        ensureCurrent()
        return current + hookContext
    }
}
