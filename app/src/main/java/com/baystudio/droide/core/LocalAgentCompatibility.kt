package com.baystudio.droide.core

/** Explicit compatibility decision for the current full Agent; this does not alter GGUF certification. */
internal object LocalAgentCompatibility {
    const val FOUR_K_REASON = "Local Agent needs more than the 4K window for its required instructions and tools. " +
        "Import and certification remain available. Select a cloud model."

    fun incompatibilityReason(contextTokens: Int = LocalLlamaServerPolicy.CONTEXT_SIZE): String? =
        if (contextTokens <= 4_096) FOUR_K_REASON else null

    fun requireCompatible() {
        incompatibilityReason()?.let { throw IllegalStateException(it) }
    }
}
