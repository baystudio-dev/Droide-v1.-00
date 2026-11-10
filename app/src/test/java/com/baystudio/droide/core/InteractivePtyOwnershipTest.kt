package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class InteractivePtyOwnershipTest {
    @Test fun busyStartingClosedOrUnknownHumanShellCannotBeHiddenByAnAgent() {
        TerminalCommandPhase.entries.filter { it != TerminalCommandPhase.READY }.forEach { phase ->
            assertThrows(IllegalStateException::class.java) { InteractivePtyOwnership().acquire(phase) }
        }
    }
    @Test fun secondInteractiveCommandCannotReplaceTheFirst() {
        val owners = InteractivePtyOwnership()
        val first = owners.acquire(TerminalCommandPhase.READY)
        assertThrows(IllegalStateException::class.java) { owners.acquire(TerminalCommandPhase.READY) }
        assertTrue(owners.isOwner(first))
        assertTrue(owners.release(first))
        assertNotSame(first, owners.acquire(TerminalCommandPhase.READY))
    }
    @Test fun oldExitCallbackCannotReleaseANewerProcess() {
        val owners = InteractivePtyOwnership()
        val old = owners.acquire(TerminalCommandPhase.READY)
        assertTrue(owners.release(old))
        val replacement = owners.acquire(TerminalCommandPhase.READY)
        assertFalse(owners.release(old))
        assertTrue(owners.isOwner(replacement))
        assertFalse(owners.isOwner(old))
    }
    @Test fun tuiRedrawsAreExplicitSnapshotsAndUnchangedFramesAreDeduplicated() {
        val screen = InteractivePtyScreenOutput()
        assertNull(screen.capture(""))
        val first = requireNotNull(screen.capture("hello 日本語"))
        assertTrue(first.contains("PTY screen snapshot"))
        assertNull(screen.capture("hello 日本語"))
        val redraw = requireNotNull(screen.capture("replacement"))
        assertTrue(redraw.contains("redraws may replace previous text"))
        assertTrue(redraw.endsWith("replacement\n"))
        assertFalse(redraw.contains("hello"))
    }
    @Test fun snapshotMemoryIsBoundedAndClearingATuiProducesEvidence() {
        val screen = InteractivePtyScreenOutput(limit = 1_000)
        repeat(100) { screen.capture("x".repeat(10_000) + "$it") }
        assertTrue(screen.output.length <= 20_000)
        assertFalse(screen.output.contains("x".repeat(1_001)))
        assertNotNull(screen.capture(""))
    }
    @Test fun supportedNativeCapabilityAllowsExplicitTtyWhilePipesRemainFailClosed() {
        assertNull(AgentTtyPolicy.unsupportedReason(true, supportsVisiblePty = true))
        assertTrue(AgentTtyPolicy.unsupportedReason(true, false)!!.contains("No command was launched"))
        assertNull(AgentTtyPolicy.unsupportedReason(false, false))
    }
    @Test fun staleHumanForegroundStopCannotInterruptPreservedShellOrDisplayedAgent() {
        TerminalCommandPhase.entries.forEach { phase ->
            assertFalse(InteractivePtyOwnership.mayStopHumanCommand(phase, agentDisplayed = true, closed = false))
            assertFalse(InteractivePtyOwnership.mayStopHumanCommand(phase, agentDisplayed = false, closed = true))
            assertEquals(phase == TerminalCommandPhase.RUNNING || phase == TerminalCommandPhase.AWAITING_RESPONSE,
                InteractivePtyOwnership.mayStopHumanCommand(phase, agentDisplayed = false, closed = false))
        }
    }
}
