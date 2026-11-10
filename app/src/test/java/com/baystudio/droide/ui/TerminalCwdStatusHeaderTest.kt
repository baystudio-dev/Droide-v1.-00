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
class TerminalCwdStatusHeaderTest {
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
    @Test fun unavailableCwdIsVisibleWithoutMovingHeaderControlsAndReadyReturnsAfterRecovery() {
        val session = Session()
        compose.setContent { MaterialTheme {
            TerminalSessionHeader(session, TerminalManager.Profile.LOCAL_LINUX_ARM64, true, null)
        } }
        val before = compose.onNodeWithTag("terminal-session-header").fetchSemanticsNode().boundsInRoot
        val commandBefore = compose.onNodeWithText("Commands").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { session.commandActivity.value = TerminalCommandActivity(TerminalCommandPhase.CWD_UNAVAILABLE) }
        compose.onNodeWithText("Working directory unavailable", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Shell ready").assertDoesNotExist()
        org.junit.Assert.assertEquals(before, compose.onNodeWithTag("terminal-session-header").fetchSemanticsNode().boundsInRoot)
        org.junit.Assert.assertEquals(commandBefore, compose.onNodeWithText("Commands").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { session.noteCommandInput() }
        compose.onNodeWithText("Input submitted", substring = true).assertIsDisplayed()
        compose.runOnIdle { session.commandActivity.value = TerminalCommandActivity(TerminalCommandPhase.READY, 0) }
        compose.onNodeWithText("Working directory unavailable", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Ready · last command completed (exit 0)").assertIsDisplayed()
    }
}
