package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.baystudio.droide.core.*
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp")
class TerminalCommandStatusHeaderTest {
    @get:Rule val compose = createComposeRule()
    private class Session : ITerminalSession, TerminalCommandActivitySession {
        override val commandActivity = MutableStateFlow(TerminalCommandActivity(TerminalCommandPhase.READY))
        override val output = MutableStateFlow("\$ ")
        override fun noteCommandInput() { commandActivity.value = TerminalCommandActivity(TerminalCommandPhase.AWAITING_RESPONSE) }
        override fun start() = Unit
        override fun send(cmd: String) = Unit
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun destroy() = Unit
        override suspend fun execOnce(cmd: String, timeoutMs: Long) = ExecResult(0, "", false)
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long) = ExecResult(0, "", false)
    }
    @Test fun mobileHeaderShowsActualQuietCommandStatusWhileKeyboardFocusModeIsActive() {
        val session = Session()
        compose.setContent { MaterialTheme {
            TerminalSessionHeader(session, TerminalManager.Profile.LOCAL_LINUX_ARM64, true, null)
        } }
        compose.onNodeWithText("Shell ready").assertIsDisplayed()
        compose.runOnIdle { session.noteCommandInput() }
        compose.onNodeWithText("Input submitted · waiting for shell prompt").assertIsDisplayed()
        compose.runOnIdle { session.commandActivity.value = TerminalCommandActivity(TerminalCommandPhase.RUNNING) }
        compose.onNodeWithText("Command running · wait for completion").assertIsDisplayed()
        compose.runOnIdle { session.commandActivity.value = TerminalCommandActivity(TerminalCommandPhase.READY, 7) }
        compose.onNodeWithText("Ready · last command failed (exit 7)").assertIsDisplayed()
        compose.runOnIdle { session.commandActivity.value = TerminalCommandActivity(TerminalCommandPhase.READY, 0) }
        compose.onNodeWithText("Ready · last command completed (exit 0)").assertIsDisplayed()
    }

    @Test fun commandPipeIsClearlyIdentifiedAndTheGuideExplainsItsInteractiveLimit() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val failure = NativePtyUnavailableException(java.io.IOException("private launch detail"))
        // Deliberately never start a process: this verifies presentation, not device PRoot.
        val session = TerminalSession(File("/tmp"), scope,
            TerminalLaunchSpec(prefix = listOf("env", "-i"), shell = "/bin/bash"), commandModeReason = failure.commandModeReason)
        try {
            compose.setContent { MaterialTheme {
                TerminalSessionHeader(session, TerminalManager.Profile.LOCAL_LINUX_ARM64, false, null)
            } }
            compose.onNodeWithText("Command mode (no PTY)", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Commands").performClick()
            compose.onNodeWithText(failure.commandModeReason, substring = true).assertIsDisplayed()
            compose.onNodeWithText("private launch detail", substring = true).assertDoesNotExist()
            compose.onNodeWithText("Interactive stdin, TUI programs and job control require a PTY.", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Interactive stdin, TUI programs and job control require a PTY.", substring = true).assertDoesNotExist()
        } finally { session.destroy(); scope.cancel() }
    }
}
