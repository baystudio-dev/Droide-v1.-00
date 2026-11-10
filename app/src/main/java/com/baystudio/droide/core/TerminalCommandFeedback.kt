package com.baystudio.droide.core

/** Time without output is display metadata; it never changes exit status or replays input. */
internal object TerminalCommandFeedback {
    fun describe(activity: TerminalCommandActivity, nowMs: Long): String {
        val started = activity.startedAtMs ?: return activity.displayText()
        if (activity.phase !in setOf(TerminalCommandPhase.RUNNING, TerminalCommandPhase.AWAITING_RESPONSE)) return activity.displayText()
        val elapsed = (nowMs - started).coerceAtLeast(0L) / 1_000L
        if (elapsed == 0L) return activity.displayText()
        val duration = "${elapsed / 60}:${(elapsed % 60).toString().padStart(2, '0')}"
        val quiet = (nowMs - (activity.lastOutputAtMs ?: started)).coerceAtLeast(0L) / 1_000L
        val action = if (activity.phase == TerminalCommandPhase.RUNNING) "Command running" else "Input submitted"
        return if (quiet >= 5L) "$action · $duration · no new output for ${quiet}s" else
            "$action · $duration · " + if (activity.lastOutputAtMs != null) "output received" else "waiting for output"
    }
}
