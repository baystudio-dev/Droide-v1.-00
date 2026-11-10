package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

/** A cancelled command controller is not an observed native process exit receipt. */
class AgentStopEvidenceTest {
    @Test fun stoppedSnapshotTextDoesNotContradictUnconfirmedCleanup() {
        for (output in listOf("", "previous output")) {
            val snapshot = BackgroundCommandManager.Snapshot("bg-stopped", BackgroundCommandManager.State.KILLED,
                "fixture command", output, null, null, 1L, 2L, interactivePty = true)
            val rendered = AgentCommandSessions.render(snapshot, output.length.toLong(), interrupted = true)
            assertTrue(rendered.contains(AgentCommandSessions.STOP_REQUEST_NOTICE))
            assertFalse(rendered.contains("command stopped"))
            assertFalse(rendered.contains("this command has finished"))
            assertTrue(snapshot.agentSummary().contains(AgentCommandSessions.STOP_REQUEST_NOTICE))
        }
    }

    @Test fun killedLifecycleAloneDoesNotProveProcessCleanup() {
        val evidence = AgentToolResultSemantics.classify("background_job", "id=bg-1 state=killed", "kill")
        assertEquals(AgentToolEvidenceState.STARTED, evidence.state)
        assertNull(evidence.success)
        assertTrue(evidence.detail.contains("requested"))
        assertTrue(evidence.detail.contains("unconfirmed"))
    }

    @Test fun interactiveStopDoesNotInventNativeExitReceipt() {
        val evidence = AgentToolResultSemantics.classify("write_stdin",
            "COMMAND_SESSION id=bg-2 state=killed backend=pty stdin_available=false interrupted=true\n" +
                "program text: process exited: 0; cleanup confirmed")
        assertEquals(AgentToolEvidenceState.STARTED, evidence.state)
        assertNull(evidence.success)
    }

    @Test fun laterCanceledStatusDoesNotClaimObservedTermination() {
        val evidence = AgentToolResultSemantics.classify("background_job", "id=bg-1 state=killed", "status")
        assertEquals(AgentToolEvidenceState.FAILED, evidence.state)
        assertEquals(false, evidence.success)
        assertTrue(evidence.detail.contains("canceled"))
        assertTrue(evidence.detail.contains("unconfirmed"))
        assertFalse(evidence.detail.contains("was killed"))
    }

    @Test fun actualProducerOwnedExitStillReportsObservedSuccess() {
        val evidence = AgentToolResultSemantics.classify("write_stdin",
            "COMMAND_SESSION id=bg-3 state=exited backend=pipe stdin_available=false interrupted=false\nexit=0\n")
        assertEquals(AgentToolEvidenceState.SUCCEEDED, evidence.state)
        assertEquals(true, evidence.success)
    }
}
