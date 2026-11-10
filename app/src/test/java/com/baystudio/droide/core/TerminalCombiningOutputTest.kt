package com.baystudio.droide.core

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Parser coverage without spawning JNI, PRoot, or a user command. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TerminalCombiningOutputTest {
    @get:org.junit.Rule val terminalMemoryBudget = com.termux.terminal.TerminalMemoryBudgetTestRule()

    @Test fun ordinaryMixedZwjVariationAndCombiningSequencesRemainExact() {
        val terminal = DeviceTerminalEmulator({}, 80, 24)

        val expected = "\uD83D\uDC69\u200D\uD83D\uDCBB\uFE0F e\u0301\u0323 " +
            "\u2764\uFE0F\u200D\uD83D\uDD25"
        terminal.appendFromWorker(expected)
        assertEquals(expected, terminal.snapshotText())
        terminal.resize(40, 24)
        assertEquals(expected, terminal.snapshotText())
    }

    @Test fun splitUtf8CombiningOutputRemainsBoundedAndAcceptsFollowingOutput() {
        val terminal = DeviceTerminalEmulator({}, 80, 24)
        val bytes = ("a" + "\u0301".repeat(40_000)).encodeToByteArray()
        bytes.asList().chunked(127).forEach { kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.Default) { terminal.append(it.toByteArray()) } }
        terminal.appendFromWorker("\r\nready")
        val text = terminal.snapshotText()
        assertTrue(text.startsWith("a" + "\u0301".repeat(63)))
        assertTrue(text.endsWith("ready"))
        assertTrue(text.length < 200)
        terminal.resize(40, 24)
        assertTrue(terminal.snapshotText().endsWith("ready"))
    }

    @Test fun repeatEscapeCannotBypassCombiningBudget() {
        val terminal = DeviceTerminalEmulator({}, 80, 24)
        terminal.appendFromWorker("a\u0301" + "\u001b[9999b".repeat(4) + "\r\ndone")
        val text = terminal.snapshotText()
        assertTrue(text.startsWith("a" + "\u0301".repeat(63)))
        assertTrue(text.endsWith("done"))
        assertTrue(text.length < 200)
    }
    @Test fun cancelledBackgroundSliceDoesNotReplayOldExpansionIntoFollowingInput() {
        val terminal = DeviceTerminalEmulator({}, 80, 24)
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.Default) {
            var checks = 0
            try {
                terminal.append("x\u001b[900b".encodeToByteArray()) {
                    if (++checks == 2) throw java.util.concurrent.CancellationException("cancelled test reader")
                }
                fail("Expected cancellation between slices")
            } catch (_: java.util.concurrent.CancellationException) { }
            terminal.resetAndClear()
            terminal.appendText("fresh")
        }
        assertEquals("fresh", terminal.snapshotText())
    }

    @Test fun mainLooperStreamAdmissionFailsBeforeMutatingOutput() {
        val terminal = DeviceTerminalEmulator({}, 80, 24)
        try {
            terminal.appendText("untrusted")
            fail("Main Looper must not own a streaming append loop")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("background reader"))
        }
        assertEquals("", terminal.snapshotText())
    }

}

/** Test fixture only: production PTY readers decode off the Android Looper. */
private fun com.baystudio.droide.core.DeviceTerminalEmulator.appendFromWorker(text: String) =
    kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.Default) { appendText(text) }
