package com.baystudio.droide.core

import android.app.Application
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Device model integration, authored for the Android lane; host helper tests are separate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DeviceTerminalPasteR67Test {
    @get:org.junit.Rule val terminalMemoryBudget = com.termux.terminal.TerminalMemoryBudgetTestRule()

    @Test fun modelUsesOneAdmissionForCompleteNativeFraming() = runBlocking {
        val writes = mutableListOf<String>()
        val model = DeviceTerminalEmulator({ fail("Paste must use the captured admission") },
            pasteAdmission = { { bytes -> writes += bytes.toString(Charsets.UTF_8); true } })
        withContext(Dispatchers.Default) { model.appendText("\u001b[?2004h") }
        assertTrue(model.paste("  日本😀\r\nend  "))
        assertEquals(listOf("\u001b[200~  日本😀\rend  \u001b[201~"), writes)
    }

    @Test fun queueRejectionIsReportedOnceWithoutRawFallback() {
        val errors = mutableListOf<String>()
        var admissions = 0
        val model = DeviceTerminalEmulator({ fail("No fallback on rejection") },
            pasteAdmission = { { _ -> admissions++; false } })
        model.setUiListener(object : DeviceTerminalEmulator.UiListener {
            override fun onScreenChanged() = Unit
            override fun onInputRejected(message: String) { errors += message }
        })
        assertFalse(model.paste("body"))
        assertEquals(1, admissions); assertEquals(1, errors.size)
    }

    @Test fun unavailableOwnerRejectsWithoutAttemptingWrite() {
        val errors = mutableListOf<String>()
        val model = DeviceTerminalEmulator({ fail("Unavailable input must not write") }, pasteAdmission = { null })
        model.setUiListener(object : DeviceTerminalEmulator.UiListener {
            override fun onScreenChanged() = Unit
            override fun onInputRejected(message: String) { errors += message }
        })
        assertFalse(model.paste("body")); assertEquals(1, errors.size)
    }

    @Test fun oldCapturedSinkCannotRedirectToNewOwner() {
        val old = mutableListOf<String>(); val replacement = mutableListOf<String>()
        var current: Any = Any()
        val expected = current
        val model = DeviceTerminalEmulator({ replacement += it.toString(Charsets.UTF_8) },
            pasteAdmission = {
                // Deterministically simulate reconnect after selecting this exact sink.
                current = Any();
                { bytes: ByteArray ->
                    if (current !== expected) false else { old += bytes.toString(Charsets.UTF_8); true }
                }
            })
        assertFalse(model.paste("body")); assertTrue(old.isEmpty()); assertTrue(replacement.isEmpty())
    }
}
