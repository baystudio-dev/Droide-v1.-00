package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class TerminalCommandFeedbackTest {
    private val token = "0123456789abcdef0123456789abcdef"
    private fun marker(event: String) = TerminalCommandActivityTracker.TITLE_PREFIX + token + ":" + event

    @Test fun quietCommandReportsElapsedSilenceWithoutInventingDownloadProgressOrSuccess() {
        var now = 1_000L
        val tracker = TerminalCommandActivityTracker(token) { now }
        tracker.consumeTitle(marker("ready:0"))
        tracker.submitted()
        tracker.consumeTitle(marker("running"))
        now += 65_000L
        assertEquals("Command running · 1:05 · no new output for 65s", TerminalCommandFeedback.describe(tracker.state.value, now))
        assertNull(tracker.state.value.lastExitCode)
        assertEquals(TerminalCommandPhase.RUNNING, tracker.state.value.phase)
    }

    @Test fun realOutputRefreshesQuietTimeButRepeatedBytesDoNotFloodTheActivityFlow() {
        var now = 1_000L
        val tracker = TerminalCommandActivityTracker(token) { now }
        tracker.submitted(); tracker.consumeTitle(marker("running"))
        now = 3_000L; tracker.observedOutput()
        assertEquals("Command running · 0:02 · output received", TerminalCommandFeedback.describe(tracker.state.value, now))
        now = 3_500L; tracker.observedOutput()
        assertEquals(3_000L, tracker.state.value.lastOutputAtMs)
        now = 4_000L; tracker.observedOutput()
        assertEquals(4_000L, tracker.state.value.lastOutputAtMs)
        assertEquals("Command running · 0:09 · no new output for 6s", TerminalCommandFeedback.describe(tracker.state.value, 10_000L))
    }

    @Test fun queuedStartupInputKeepsItsTimerAndDoesNotPretendTheShellExecutedIt() {
        var now = 1_000L
        val tracker = TerminalCommandActivityTracker(token) { now }
        tracker.submitted(); now = 2_000L; tracker.resetForLaunch()
        tracker.consumeTitle(marker("ready:0"))
        assertEquals(1_000L, tracker.state.value.startedAtMs)
        assertEquals("Input submitted · 0:06 · no new output for 6s", TerminalCommandFeedback.describe(tracker.state.value, 7_000L))
        assertNull(tracker.state.value.lastExitCode)
    }

    @Test fun completionDiscardsRunningTimersAndLateOutputCannotReplaceTheActualExitCode() {
        val tracker = TerminalCommandActivityTracker(token) { 1_000L }
        tracker.consumeTitle(marker("ready:0")); tracker.submitted(); tracker.consumeTitle(marker("running"))
        tracker.observedOutput(); tracker.consumeTitle(marker("ready:7")); tracker.observedOutput()
        assertNull(tracker.state.value.startedAtMs)
        assertNull(tracker.state.value.lastOutputAtMs)
        assertEquals("Ready · last command failed (exit 7)", TerminalCommandFeedback.describe(tracker.state.value, 900_000L))
        tracker.closed(9); tracker.observedOutput()
        assertEquals("Shell exited · exit 9", TerminalCommandFeedback.describe(tracker.state.value, 900_000L))
    }

    @Test fun anUnconfirmedCommandAndAnEarlierClockDoNotInventReceivedOutput() {
        val value = TerminalCommandActivity(TerminalCommandPhase.RUNNING, startedAtMs = 10_000L)
        assertEquals("Command running · wait for completion", TerminalCommandFeedback.describe(value, 9_000L))
        assertEquals("Command running · 0:01 · waiting for output", TerminalCommandFeedback.describe(value, 11_000L))
    }
}
