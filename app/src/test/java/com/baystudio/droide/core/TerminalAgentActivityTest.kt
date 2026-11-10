package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class TerminalAgentActivityTest {
    @Test fun concurrentCommandsCannotCreateNegativeActiveCounts() {
        val tracker = TerminalAgentActivityTracker()
        tracker.start()
        tracker.start()
        assertEquals(2, tracker.state.value.running)
        assertFalse(tracker.state.value.completed)
        tracker.finish(0)
        assertEquals(1, tracker.state.value.running)
        tracker.finish(7)
        assertEquals(0, tracker.state.value.running)
        assertEquals(7, tracker.state.value.lastExitCode)
        assertTrue(tracker.state.value.completed)
        tracker.finish(null)
        assertEquals(0, tracker.state.value.running)
    }

    @Test fun metadataContainsNoTerminalInputOrTranscript() {
        val tracker = TerminalAgentActivityTracker()
        tracker.start()
        assertTrue(tracker.state.value.displayText().contains("Agent"))
        tracker.finish(null)
        assertTrue(tracker.state.value.displayText().contains("unknown"))
        tracker.start()
        assertEquals(1, tracker.state.value.running)
        assertFalse(tracker.state.value.completed)
    }
}
