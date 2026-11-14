package com.baystudio.droide.core

/** Event-driven lifecycle inspired by Codex, with Droide-compatible default tolerance.
 * This is not a total response/turn deadline. Custom providers can configure it in
 * .droide/providers.json; provider definitions can also supply it directly.
 */
internal object ProviderStreamPolicy {
    fun validateIdleTimeout(value: Long?) {
        require(value == null || (value > 0 && value <= Long.MAX_VALUE / 1_000_000L)) {
            "streamIdleTimeoutMs must be positive and representable as nanoseconds"
        }
    }
    fun idleTimeoutMillis(provider: AiProvider): Long =
        provider.streamIdleTimeoutMs ?: AgentTimeoutPolicy.RESPONSE_IDLE_MS
}
