package com.baystudio.droide.core

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DeviceTerminalCommandActivityTest {
    @get:org.junit.Rule val terminalMemoryBudget = com.termux.terminal.TerminalMemoryBudgetTestRule()

    @Test fun realTermuxEmulatorProcessesFragmentedAndRepeatedCommandMetadataWithoutScreenLeak() {
        val emulator = DeviceTerminalEmulator({}, 40, 24)
        val token = emulator.commandTracker.environment.getValue(TerminalCommandActivityTracker.TOKEN_ENV)
        fun title(event: String) = "\u001b]2;${TerminalCommandActivityTracker.TITLE_PREFIX}$token:$event\u0007"
        fun feed(value: String) = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.Default) { value.toByteArray().forEach { emulator.append(byteArrayOf(it)) } }
        feed(title("pulse") + title("ready:0") + "\$ ")
        assertEquals("Shell ready", emulator.commandActivity.value.displayText())
        emulator.commandTracker.submitted()
        feed(title("running"))
        assertEquals(TerminalCommandPhase.RUNNING, emulator.commandActivity.value.phase)
        feed("日本語 😀\r\n" + title("pulse") + title("ready:7") + "\$ ")
        assertEquals(7, emulator.commandActivity.value.lastExitCode)
        emulator.commandTracker.submitted()
        feed(title("pulse") + title("ready:0") + "\$ ")
        assertEquals(0, emulator.commandActivity.value.lastExitCode)
        assertNull(emulator.title.value)
        assertFalse(emulator.snapshotText().contains(TerminalCommandActivityTracker.TITLE_PREFIX))
        assertTrue(emulator.snapshotText().contains("日本語 😀"))
    }
}
