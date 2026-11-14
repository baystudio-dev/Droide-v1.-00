package com.baystudio.droide.core

import android.app.Application
import com.termux.terminal.TerminalEmulator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TerminalTranscriptSnapshotTest {
    @get:org.junit.Rule val terminalMemoryBudget = com.termux.terminal.TerminalMemoryBudgetTestRule()

    private fun fullText(model: DeviceTerminalEmulator): String {
        val field = DeviceTerminalEmulator::class.java.getDeclaredField("emulator").apply { isAccessible = true }
        return (field.get(model) as TerminalEmulator).screen.transcriptText
    }

    @Test fun boundedTailMatchesUpstreamAcrossWrappedUnicodeWhitespaceAndSmallLimits() {
        val model = DeviceTerminalEmulator({}, 12, 4)
        listOf("", "  a   b", "\r\n  日本語 😀 e\u0301 wrapped longer than one screen row  \r\n",
            "\t\r\n", "\u001b[?1049hAlternate 😀\r\n\u001b[?1049l", "tail   \r\n\r\n").forEach { text ->
            model.appendFromWorker(text)
            val expected = fullText(model)
            for (limit in 1..expected.length + 4) {
                assertEquals("tail limit $limit", codePointTail(expected, limit), model.snapshotText(limit))
                assertTrue(model.snapshotText(limit).length <= limit)
            }
        }
    }

    @Test fun droppingBoundaryPairDoesNotRefillFromAnEarlierRow() {
        val model = DeviceTerminalEmulator({}, 40, 4)
        model.appendFromWorker("earlier\r\n😀a")
        assertEquals("a", model.snapshotText(1))
        assertEquals("a", model.snapshotText(2))
        assertEquals("😀a", model.snapshotText(3))
        assertEquals("\n😀a", model.snapshotText(4))
    }

    @Test fun oneUnitLimitCanReturnEmptyRatherThanHalfAnEmojiOrEarlierText() {
        val model = DeviceTerminalEmulator({}, 40, 4)
        model.appendFromWorker("earlier\r\n😀")
        assertEquals("", model.snapshotText(1))
        assertEquals("😀", model.snapshotText(2))
        assertEquals("\n😀", model.snapshotText(3))
    }

    @Test fun pairSafeCutPreservesInteriorWhitespaceAndBmpCombiningTail() {
        val model = DeviceTerminalEmulator({}, 40, 4)
        model.appendFromWorker("earlier\r\n😀 a")
        assertEquals(" a", model.snapshotText(3))
        model.appendFromWorker("\r\n  日本語 e\u0301  \r\n")
        assertEquals("\u0301", model.snapshotText(1))
        assertEquals("e\u0301", model.snapshotText(2))
        assertEquals(" e\u0301", model.snapshotText(3))
        assertEquals("語 e\u0301", model.snapshotText(4))
    }

    @Test fun longScrollbackKeepsExactFinalOutputAfterRepeatedResizes() {
        val model = DeviceTerminalEmulator({}, 120, 24)
        repeat(120) { batch ->
            model.appendFromWorker((0..99).joinToString("") { "batch=$batch line=$it " + "x".repeat(100) + " 日本語 😀\r\n" })
        }
        for (columns in listOf(120, 90, 160, 80)) {
            model.resize(columns, 24)
            val expected = fullText(model)
            val field = DeviceTerminalEmulator::class.java.getDeclaredField("emulator").apply { isAccessible = true }
            val notice = (field.get(model) as TerminalEmulator).outputLimitNotice
            assertTrue(notice.contains("history omitted"))
            for (limit in listOf(1, notice.length, notice.length + 1, notice.length + 2,
                79, 1000, 200_000, 300_000)) {
                val prefix = notice.take(limit)
                assertEquals(prefix + codePointTail(expected, (limit - prefix.length).coerceAtLeast(0)), model.snapshotText(limit))
                assertTrue(model.snapshotText(limit).length <= limit)
            }
            assertTrue(model.snapshotText().contains("batch=119 line=99"))
        }
    }

    @Test fun detachingOldViewDoesNotRemoveReplacementListener() {
        val model = DeviceTerminalEmulator({}, 40, 24)
        var oldUpdates = 0
        var newUpdates = 0
        val old = object : DeviceTerminalEmulator.UiListener { override fun onScreenChanged() { oldUpdates++ } }
        val replacement = object : DeviceTerminalEmulator.UiListener { override fun onScreenChanged() { newUpdates++ } }
        model.setUiListener(old)
        model.setUiListener(replacement)
        model.clearUiListener(old)
        model.appendFromWorker("replacement remains usable")
        assertEquals(1, oldUpdates)
        assertEquals(2, newUpdates)
        model.clearUiListener(replacement)
        model.appendFromWorker("detached")
        assertEquals(2, newUpdates)
    }

    /** Independent oracle: walk whole code points backwards instead of slicing UTF-16 units. */
    private fun codePointTail(text: String, limit: Int): String {
        var start = text.length
        var remaining = limit
        while (start > 0) {
            val units = Character.charCount(text.codePointBefore(start))
            if (units > remaining) break
            start -= units
            remaining -= units
        }
        return text.substring(start)
    }
}

/** Test fixture only: production PTY readers decode off the Android Looper. */
private fun com.baystudio.droide.core.DeviceTerminalEmulator.appendFromWorker(text: String) =
    kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.Default) { appendText(text) }
