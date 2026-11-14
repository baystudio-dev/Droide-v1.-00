package com.baystudio.droide.core

import android.view.KeyEvent
import com.termux.terminal.KeyHandler
import org.junit.Assert.*
import org.junit.Test

/** Runs against the unchanged admitted R63 classes.jar; this is encoding, not PTY delivery. */
class TerminalArrowEncodingR73Test {
    @Test fun normalCursorModeEncodesUpAndDownAsCsiSequences() {
        assertEquals("\u001b[A", KeyHandler.getCode(KeyEvent.KEYCODE_DPAD_UP, 0, false, false))
        assertEquals("\u001b[B", KeyHandler.getCode(KeyEvent.KEYCODE_DPAD_DOWN, 0, false, false))
    }

    @Test fun applicationCursorModeEncodesUpAndDownAsSs3Sequences() {
        assertEquals("\u001bOA", KeyHandler.getCode(KeyEvent.KEYCODE_DPAD_UP, 0, true, false))
        assertEquals("\u001bOB", KeyHandler.getCode(KeyEvent.KEYCODE_DPAD_DOWN, 0, true, false))
    }

    @Test fun modifierArrowSequencesKeepCtrlAltAndShiftDistinct() {
        for (application in listOf(false, true)) {
            assertEquals("\u001b[1;5A", KeyHandler.getCode(KeyEvent.KEYCODE_DPAD_UP, KeyHandler.KEYMOD_CTRL, application, false))
            assertEquals("\u001b[1;3B", KeyHandler.getCode(KeyEvent.KEYCODE_DPAD_DOWN, KeyHandler.KEYMOD_ALT, application, false))
            assertEquals("\u001b[1;2A", KeyHandler.getCode(KeyEvent.KEYCODE_DPAD_UP, KeyHandler.KEYMOD_SHIFT, application, false))
        }
    }

    @Test fun rapidAlternationAndCursorModeSwitchesHaveNoEncoderDebounceOrStaleState() {
        repeat(100) { index ->
            val application = index % 2 == 0
            val prefix = if (application) "\u001bO" else "\u001b["
            assertEquals(prefix + "A", KeyHandler.getCode(KeyEvent.KEYCODE_DPAD_UP, 0, application, false))
            assertEquals(prefix + "B", KeyHandler.getCode(KeyEvent.KEYCODE_DPAD_DOWN, 0, application, false))
        }
    }
}
