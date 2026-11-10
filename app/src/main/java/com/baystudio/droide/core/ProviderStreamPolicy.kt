package com.baystudio.droide.core

/** Measures inactivity between provider events, not a deadline for the whole response. */

internal object ProviderStreamPolicy {
    fun validateIdleTimeout(value: Long?) {
        require(value == null || (value > 0 && value <= Long.MAX_VALUE / 1_000_000L)) {
            "streamIdleTimeoutMs must be positive and representable as nanoseconds"
        }
    }
    fun idleTimeoutMillis(provider: AiProvider): Long =
        provider.streamIdleTimeoutMs ?: AgentTimeoutPolicy.RESPONSE_IDLE_MS
}
