package com.baystudio.droide.core

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Display metadata only. Process exit/permissions for Agent commands remain separate authorities. */
interface TerminalCommandActivitySession {
    val commandActivity: StateFlow<TerminalCommandActivity>
    fun noteCommandInput()
}

enum class TerminalCommandPhase { STARTING, READY, AWAITING_RESPONSE, RUNNING, CLOSED, ERROR, CWD_UNAVAILABLE }

data class TerminalCommandActivity(
    val phase: TerminalCommandPhase = TerminalCommandPhase.STARTING,
    val lastExitCode: Int? = null,
    val generation: Long = 0,
    val startedAtMs: Long? = null,
    val lastOutputAtMs: Long? = null,
) {
    fun displayText(): String = when (phase) {
        TerminalCommandPhase.STARTING -> "Starting shell…"
        TerminalCommandPhase.READY -> lastExitCode?.let {
            if (it == 0) "Ready · last command completed (exit 0)"
            else "Ready · last command failed (exit $it)"
        } ?: "Shell ready"
        TerminalCommandPhase.AWAITING_RESPONSE -> "Input submitted · waiting for shell prompt"
        TerminalCommandPhase.RUNNING -> "Command running · wait for completion"
        TerminalCommandPhase.CLOSED -> "Shell exited" + lastExitCode?.let { " · exit $it" }.orEmpty()
        TerminalCommandPhase.CWD_UNAVAILABLE -> "Working directory unavailable · use cd to an existing folder or reopen this terminal"
        TerminalCommandPhase.ERROR -> "Terminal error · check the terminal message"
    }
}

/** No command lines, password input or transcript copies are needed to display activity. */
internal class TerminalCommandActivityTracker(
    private val token: String = UUID.randomUUID().toString().replace("-", ""),
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val mutable = MutableStateFlow(TerminalCommandActivity())
    val state = mutable.asStateFlow()
    private var seenPrompt = false
    val environment = mapOf(TOKEN_ENV to token)

    init { require(token.matches(Regex("[0-9a-f]{32}"))) }

    @Synchronized fun submitted() {
        if (mutable.value.phase in setOf(TerminalCommandPhase.READY, TerminalCommandPhase.STARTING, TerminalCommandPhase.CWD_UNAVAILABLE)) {
            mutable.value = mutable.value.copy(phase = TerminalCommandPhase.AWAITING_RESPONSE, generation = mutable.value.generation + 1,
                startedAtMs = clockMs(), lastOutputAtMs = null)
        }
    }

    @Synchronized fun resetForLaunch() {
        seenPrompt = false
        val phase = if (mutable.value.phase == TerminalCommandPhase.AWAITING_RESPONSE)
            TerminalCommandPhase.AWAITING_RESPONSE else TerminalCommandPhase.STARTING
        mutable.value = TerminalCommandActivity(phase, generation = mutable.value.generation,
            startedAtMs = if (phase == TerminalCommandPhase.AWAITING_RESPONSE) mutable.value.startedAtMs else null)
    }

    /** Return true for private titles, even malformed ones, to keep them out of tab labels. */
    @Synchronized fun consumeTitle(title: String?): Boolean {
        if (title == null || !title.startsWith(TITLE_PREFIX)) return false
        val parts = title.removePrefix(TITLE_PREFIX).split(':', limit = 3)
        if (parts.size < 2 || parts[0] != token || mutable.value.phase == TerminalCommandPhase.CLOSED) return true
        when (parts[1]) {
            "running" -> if (parts.size == 2) {
                val previous = mutable.value
                val fresh = previous.phase in setOf(TerminalCommandPhase.READY, TerminalCommandPhase.STARTING, TerminalCommandPhase.CWD_UNAVAILABLE)
                mutable.value = previous.copy(phase = TerminalCommandPhase.RUNNING,
                    generation = previous.generation + if (fresh) 1 else 0,
                    startedAtMs = previous.startedAtMs ?: clockMs(), lastOutputAtMs = previous.lastOutputAtMs)
            }
            "ready", "cwd-unavailable" -> {
                val code = parts.getOrNull(2)?.takeIf { it.length in 1..3 && it.all(Char::isDigit) }
                    ?.toIntOrNull()?.takeIf { it in 0..255 } ?: return true
                val phase = if (parts[1] == "cwd-unavailable") TerminalCommandPhase.CWD_UNAVAILABLE
                    else if (!seenPrompt && mutable.value.phase == TerminalCommandPhase.AWAITING_RESPONSE)
                    TerminalCommandPhase.AWAITING_RESPONSE else TerminalCommandPhase.READY
                mutable.value = TerminalCommandActivity(phase, if (seenPrompt) code else null, mutable.value.generation,
                    startedAtMs = if (phase == TerminalCommandPhase.AWAITING_RESPONSE) mutable.value.startedAtMs else null,
                    lastOutputAtMs = if (phase == TerminalCommandPhase.AWAITING_RESPONSE) mutable.value.lastOutputAtMs else null)
                seenPrompt = true
            }
            // A pulse makes identical ready titles generate a fresh emulator notification.
            "pulse" -> Unit
        }
        return true
    }

    /** Output is observed, not proof that a download advanced or a command succeeded. */
    @Synchronized fun observedOutput() {
        val previous = mutable.value
        if (previous.phase !in setOf(TerminalCommandPhase.RUNNING, TerminalCommandPhase.AWAITING_RESPONSE)) return
        val now = clockMs()
        if (previous.lastOutputAtMs == null || now - previous.lastOutputAtMs >= 1_000L) {
            mutable.value = previous.copy(lastOutputAtMs = now)
        }
    }

    /** Serialize an explicit human Stop with command-title transitions; never use for Agent permissions. */
    @Synchronized internal fun withActiveGeneration(generation: Long, action: () -> Boolean): Boolean {
        val value = mutable.value
        if (value.generation != generation || value.phase !in setOf(
                TerminalCommandPhase.AWAITING_RESPONSE, TerminalCommandPhase.RUNNING)) return false
        return action()
    }

    @Synchronized fun closed(exitCode: Int? = null) {
        mutable.value = TerminalCommandActivity(TerminalCommandPhase.CLOSED, exitCode, mutable.value.generation)
    }

    @Synchronized fun failed() {
        if (mutable.value.phase != TerminalCommandPhase.CLOSED) {
            mutable.value = mutable.value.copy(phase = TerminalCommandPhase.ERROR)
        }
    }

    companion object {
        const val TITLE_PREFIX = "droide-command-v1:"
        const val TOKEN_ENV = "DROIDE_TERMINAL_ACTIVITY_TOKEN"
    }
}
