package com.baystudio.droide.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TerminalCommandActivityTest {
    private val token = "0123456789abcdef0123456789abcdef"
    private fun marker(event: String) = TerminalCommandActivityTracker.TITLE_PREFIX + token + ":" + event

    @Test fun initialPromptDoesNotInventACompletedCommandAndSilentCommandsHaveAnExitStatus() {
        val tracker = TerminalCommandActivityTracker(token)
        tracker.consumeTitle(marker("ready:0"))
        assertEquals("Shell ready", tracker.state.value.displayText())
        assertNull(tracker.state.value.lastExitCode)
        tracker.submitted()
        assertEquals(TerminalCommandPhase.AWAITING_RESPONSE, tracker.state.value.phase)
        tracker.consumeTitle(marker("running"))
        assertEquals(TerminalCommandPhase.RUNNING, tracker.state.value.phase)
        tracker.consumeTitle(marker("ready:0"))
        assertEquals(0, tracker.state.value.lastExitCode)
        assertTrue(tracker.state.value.displayText().contains("completed"))
        tracker.submitted()
        tracker.consumeTitle(marker("ready:7"))
        assertEquals(7, tracker.state.value.lastExitCode)
        assertTrue(tracker.state.value.displayText().contains("failed (exit 7)"))
    }

    @Test fun ordinaryTitlesWrongSessionAndMalformedMetadataCannotAnnounceSuccess() {
        val tracker = TerminalCommandActivityTracker(token)
        for (value in listOf("vim", "\$ ", marker("ready:999"), marker("ready:-1"), marker("ready:0:extra"),
            marker("ready:999999999999999999999999"), marker("running:extra"), marker("ready:"),
            marker("ready:0").replace(token, "f".repeat(32)))) tracker.consumeTitle(value)
        assertEquals(TerminalCommandPhase.STARTING, tracker.state.value.phase)
        assertNull(tracker.state.value.lastExitCode)
    }

    @Test fun firstPromptDoesNotAnnounceCompletionOfInputQueuedDuringStartup() {
        val tracker = TerminalCommandActivityTracker(token)
        tracker.submitted()
        tracker.resetForLaunch()
        tracker.consumeTitle(marker("ready:0"))
        assertEquals(TerminalCommandPhase.AWAITING_RESPONSE, tracker.state.value.phase)
        assertNull(tracker.state.value.lastExitCode)
        tracker.consumeTitle(marker("ready:7"))
        assertEquals(TerminalCommandPhase.READY, tracker.state.value.phase)
        assertEquals(7, tracker.state.value.lastExitCode)
    }

    @Test fun repeatedPromptMetadataSurvivesEveryReadBoundaryWithoutLeakingToOutput() {
        val tracker = TerminalCommandActivityTracker(token)
        val events = mutableListOf<TerminalCommandActivity>()
        val stream = TerminalDirectoryStream({}, { title -> tracker.consumeTitle(title); events += tracker.state.value })
        val bytes = "\u001b]2;${marker("ready:0")}\u0007\$ " +
            "\u001b]2;${marker("running")}\u001b\\result\n" +
            "\u001b]2;${marker("pulse")}\u0007\u001b]2;${marker("ready:0")}\u0007\$ "
        assertEquals("\$ result\n\$ ", bytes.map { stream.consume(it.toString()) }.joinToString(""))
        assertTrue(events.any { it.phase == TerminalCommandPhase.RUNNING })
        assertEquals(TerminalCommandPhase.READY, tracker.state.value.phase)
        assertEquals(0, tracker.state.value.lastExitCode)
    }

    @Test fun closedShellCannotBecomeReadyFromLateMetadataButAnExplicitLaunchCanResetIt() {
        val tracker = TerminalCommandActivityTracker(token)
        tracker.closed(9)
        tracker.consumeTitle(marker("ready:0"))
        assertEquals(TerminalCommandPhase.CLOSED, tracker.state.value.phase)
        assertEquals(9, tracker.state.value.lastExitCode)
        tracker.resetForLaunch()
        tracker.consumeTitle(marker("ready:0"))
        assertEquals("Shell ready", tracker.state.value.displayText())
    }

    @Test fun agentResultsDistinguishQuietRunningSuccessFailureAndAlreadyConsumedOutput() {
        fun render(state: BackgroundCommandManager.State, code: Int?, output: String = "", cursor: Long = 0): String =
            AgentCommandSessions.render(BackgroundCommandManager.Snapshot("fixture-session", state, "fixture", output,
                code, null, 0, if (state == BackgroundCommandManager.State.RUNNING) null else 1), cursor)
        assertTrue(render(BackgroundCommandManager.State.RUNNING, null).contains("still running"))
        assertTrue(render(BackgroundCommandManager.State.RUNNING, null).contains("do not rerun"))
        assertTrue(render(BackgroundCommandManager.State.EXITED, 0).contains("completed successfully"))
        assertTrue(render(BackgroundCommandManager.State.FAILED, 7).contains("command failed"))
        val stopped = render(BackgroundCommandManager.State.KILLED, 143)
        assertTrue(stopped.contains("stop requested"))
        assertTrue(stopped.contains("process exit and cleanup are unconfirmed"))
        assertFalse(stopped.contains("command stopped"))
        assertFalse(stopped.contains("completed successfully"))
        assertTrue(render(BackgroundCommandManager.State.TIMED_OUT, 124).contains("timed out"))
        assertTrue(render(BackgroundCommandManager.State.EXITED, 0, "seen", 4).contains("no additional output"))
    }

    @Test(timeout = 8_000) fun actualBashHooksPreserveExitCodeAndExposeSilentCompletionWithoutChangingPrompt() = runBlocking {
        val tracker = TerminalCommandActivityTracker(token)
        val activities = java.util.concurrent.CopyOnWriteArrayList<TerminalCommandActivity>()
        val text = StringBuffer()
        val stream = TerminalDirectoryStream({}, { title -> tracker.consumeTitle(title); activities += tracker.state.value })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val terminal = InteractiveTerminalProcess(scope, {
            ProcessBuilder("/bin/bash", "--noprofile", "--norc", "-i").redirectErrorStream(true)
                .apply { environment().putAll(TerminalPromptPresentation.bashEnvironment() + tracker.environment) }.start()
        }, {}, { text.append(stream.consume(it)) })
        suspend fun until(condition: () -> Boolean) = withTimeout(3_000) { while (!condition()) delay(10) }
        try {
            terminal.start()
            until { tracker.state.value.phase == TerminalCommandPhase.READY }
            terminal.send("false")
            until { tracker.state.value.lastExitCode == 1 }
            terminal.send("printf 'PRESERVED_EXIT=%s\\n' \"\$?\"")
            until { text.toString().contains("PRESERVED_EXIT=1") }
            terminal.send("true")
            until { tracker.state.value.phase == TerminalCommandPhase.READY && tracker.state.value.lastExitCode == 0 }
            terminal.send("sleep 0.2; exit 7")
            until { text.toString().contains("process exited: 7") }
            assertTrue(activities.any { it.phase == TerminalCommandPhase.RUNNING })
            assertFalse(text.toString().contains(TerminalCommandActivityTracker.TITLE_PREFIX))
            assertTrue(text.toString().contains("\$ "))
        } finally {
            terminal.destroy()
            scope.coroutineContext.job.cancelAndJoin()
        }
    }
    @Test fun staleStopCannotInterruptNextHumanCommandEvenIfReadyStateWasConflated() {
        val tracker = TerminalCommandActivityTracker(token)
        tracker.consumeTitle(marker("ready:0"))
        tracker.submitted()
        val firstGeneration = tracker.state.value.generation
        tracker.consumeTitle(marker("running"))
        assertEquals(firstGeneration, tracker.state.value.generation)
        assertTrue(InteractivePtyOwnership.mayStopHumanCommand(tracker.state.value, firstGeneration, false, false))
        tracker.consumeTitle(marker("ready:0"))
        tracker.submitted()
        assertTrue(tracker.state.value.generation > firstGeneration)
        assertFalse(InteractivePtyOwnership.mayStopHumanCommand(tracker.state.value, firstGeneration, false, false))
        val secondGeneration = tracker.state.value.generation
        tracker.resetForLaunch()
        assertEquals(secondGeneration, tracker.state.value.generation)
        tracker.closed()
        assertFalse(InteractivePtyOwnership.mayStopHumanCommand(tracker.state.value, secondGeneration, false, false))
    }

}
