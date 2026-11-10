package com.baystudio.droide.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Stores only counters and outcomes; never stores command text, secrets or output. */
data class TerminalAgentActivity(
    val running: Int = 0,
    val lastExitCode: Int? = null,
    val completed: Boolean = false,
) {
    fun displayText(): String = when {
        running > 0 -> "Agent command running (" + running + ")"
        completed -> "Agent command finished · exit " + (lastExitCode?.toString() ?: "unknown")
        else -> ""
    }
}

interface TerminalAgentActivitySession {
    val agentActivity: StateFlow<TerminalAgentActivity>
    fun agentCommandStarted()
    fun agentCommandFinished(exitCode: Int?)
}

internal class TerminalAgentActivityTracker {
    private val mutable = MutableStateFlow(TerminalAgentActivity())
    val state: StateFlow<TerminalAgentActivity> = mutable.asStateFlow()

    @Synchronized fun start() {
        val prior = mutable.value
        mutable.value = prior.copy(running = prior.running + 1, completed = false)
    }

    @Synchronized fun finish(exitCode: Int?) {
        val prior = mutable.value
        if (prior.running <= 0) return
        mutable.value = prior.copy(
            running = prior.running - 1,
            lastExitCode = exitCode,
            completed = prior.running == 1,
        )
    }
}
