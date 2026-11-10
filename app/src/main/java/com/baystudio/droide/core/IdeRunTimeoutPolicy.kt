package com.baystudio.droide.core

/** Process deadlines, not time until the UI returns or a resumable command yields. */
internal object IdeRunTimeoutPolicy {
    const val FORMATTER_DEADLINE_MS = 10L * 60_000L

    fun forContributed(kind: DroideToolKind): Long = when (kind) {
        DroideToolKind.FORMATTER -> FORMATTER_DEADLINE_MS
        DroideToolKind.RUN, DroideToolKind.LINTER, DroideToolKind.CLI -> 0L
        DroideToolKind.BUILD, DroideToolKind.TEST -> AgentTimeoutPolicy.BUILD_DEADLINE_MS
    }
}
