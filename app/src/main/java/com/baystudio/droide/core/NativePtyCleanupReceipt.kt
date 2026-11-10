package com.baystudio.droide.core

import java.util.UUID

/** Diagnostic correlation only: never a numeric-PID lease or authority to signal a process. */
internal class NativePtyToken private constructor(val value: String) {
    val environment: Map<String, String> get() = mapOf("DROIDE_NATIVE_PTY_TOKEN" to value)

    companion object {
        fun create(): NativePtyToken = NativePtyToken(UUID.randomUUID().toString().replace("-", ""))
    }
}

/** Snapshot of Java's sticky failure getters; zero input failure is not proof of consumption. */
internal data class NativePtyCleanupReceipt(
    val observedExitStatus: Int,
    val processFailureReason: Int,
    val inputFailureReason: Int,
) {
    val exitCode: Int
        get() = if (processFailureReason == 0 && inputFailureReason == 0) observedExitStatus else -1

    fun withRelayInputFailure(reason: Int): NativePtyCleanupReceipt =
        if (inputFailureReason == 0 && reason != 0) copy(inputFailureReason = reason) else this

    val description: String
        get() = buildString {
            append(when (processFailureReason) {
                0 -> "[process exited: $observedExitStatus]"
                1 -> "[native PTY startup failed: no child was created; no exit status is available]"
                2 -> "[native PTY child ownership lost: exit status and cleanup are unconfirmed]"
                3 -> "[native PTY child quarantined: exit status and cleanup are unconfirmed]"
                4 -> "[process exited: $observedExitStatus; owned process-group cleanup is unconfirmed]"
                else -> "[native PTY exit status and cleanup are unconfirmed]"
            })
            if (processFailureReason != 1) append("\n[Detached processes or other process groups may still be running]")
            if (inputFailureReason != 0) append('\n').append(inputFailureNotice(inputFailureReason))
        }

    companion object {
        const val STOP_NOTICE = "[Native PTY stop requested; owned process-group cleanup is not yet confirmed. Detached processes may still be running.]"
        const val LEGACY_UNRESOLVED_PREFIX = "DROIDE_NATIVE_PTY_UNRESOLVED="

        fun isLegacyLeaseName(name: String): Boolean = name.endsWith(".pid") &&
            (name.startsWith("native-terminal-") || name.startsWith("agent-native-pty-"))

        fun inputFailureNotice(reason: Int): String {
            val detail = when (reason) {
                1 -> "write cancelled"
                2 -> "write deadline expired"
                3 -> "PTY I/O failed"
                4 -> "write failed with an exception"
                else -> "writer completion is unconfirmed"
            }
            return "Terminal input delivery could not be confirmed: $detail. Input may be partial; check the terminal before retrying. Nothing will be replayed."
        }
    }
}
