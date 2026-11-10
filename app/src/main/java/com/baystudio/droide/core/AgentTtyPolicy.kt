package com.baystudio.droide.core

/** No fallback to pipes when a real TTY was explicitly requested. Check after tab pinning. */
internal object AgentTtyPolicy {
    fun unsupportedReason(requiresTty: Boolean, supportsVisiblePty: Boolean = false): String? =
        if (requiresTty && !supportsVisiblePty) {
            "ERROR: Interactive TTY was requested but the selected backend only supports supervised pipes. " +
                "No command was launched. Use the existing visible user terminal for interactive " +
                "programs; Agent will not inject keystrokes into that shell."
        } else null
}
