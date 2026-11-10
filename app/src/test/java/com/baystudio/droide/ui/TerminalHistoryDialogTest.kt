package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.baystudio.droide.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Authored Android/Compose coverage; not part of the focused JVM runner. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TerminalHistoryDialogTest {
    @get:Rule val compose = createComposeRule()
    @Before fun openAdmission() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
    }
    private class Fake(text: String) : ITerminalSession {
        override val output = MutableStateFlow(text)
        var mutations = 0
        override fun start() { mutations++ }
        override fun send(cmd: String) { mutations++ }
        override suspend fun execOnce(cmd: String, timeoutMs: Long) = ExecResult(0,"",false)
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long) = ExecResult(0,"",false)
        override fun interrupt() { mutations++ }
        override fun clear() { mutations++ }
        override fun destroy() { mutations++ }
    }
    private fun ready() = compose.waitUntil(5_000) {
        compose.onAllNodesWithText("Export snapshot…").fetchSemanticsNodes().isNotEmpty() &&
            compose.onAllNodesWithText("Capturing retained text…").fetchSemanticsNodes().isEmpty()
    }
    private fun result(text: String) = compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    @Test fun snapshotStaysFrozenUntilExplicitRefresh() {
        val session=Fake("needle first")
        compose.setContent { DroideTheme { TerminalHistoryDialog(session) {} } };ready()
        compose.onNodeWithText("Find literal text").performTextInput("needle");result("1 matches")
        compose.runOnIdle { session.output.value="replacement" }
        compose.onNodeWithText("1 matches").assertExists()
        compose.onNodeWithText("Refresh").performClick();result("0 matches")
        compose.runOnIdle { assertEquals(0,session.mutations) }
    }
    @Test fun literalQueryDoesNotExecuteRegex() {
        val session=Fake("[a-z]+ literal")
        compose.setContent { DroideTheme { TerminalHistoryDialog(session) {} } };ready()
        compose.onNodeWithText("Find literal text").performTextInput("[a-z]+");result("1 matches")
    }
    @Test fun matchCapIsVisible() {
        val session=Fake("x ".repeat(501))
        compose.setContent { DroideTheme { TerminalHistoryDialog(session) {} } };ready()
        compose.onNodeWithText("Find literal text").performTextInput("x");result("First 500 matches; more matches omitted")
    }
    @Test fun oversizedQueryReportsRejectedInput() {
        val session=Fake("text")
        compose.setContent { DroideTheme { TerminalHistoryDialog(session) {} } };ready()
        compose.onNodeWithText("Find literal text").performTextInput("a".repeat(257))
        compose.onNodeWithText("Query limit is 256 UTF-16 units; extra input was not accepted.").assertExists()
    }
    @Test fun malformedSnapshotFailsWithoutExportOrTerminalMutation() {
        val session=Fake("broken\uD800")
        compose.setContent { DroideTheme { TerminalHistoryDialog(session) {} } }
        result("Could not capture retained text. No terminal data was changed; refresh to retry.")
        compose.onNodeWithText("Export snapshot…").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0,session.mutations) }
    }
    @Test fun replacementSessionDoesNotKeepOldMatches() {
        var session by mutableStateOf(Fake("first needle"))
        compose.setContent { DroideTheme { TerminalHistoryDialog(session) {} } };ready()
        compose.onNodeWithText("Find literal text").performTextInput("needle");result("1 matches")
        compose.runOnIdle { session=Fake("second") };ready()
        compose.onNodeWithText("Find literal text").performTextInput("needle");result("0 matches")
    }
    @Test fun closeDoesNotClearOrInterruptTerminal() {
        val session=Fake("retained");var closed=false
        compose.setContent { DroideTheme { TerminalHistoryDialog(session) {closed=true} } };ready()
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertTrue(closed);assertEquals(0,session.mutations);assertEquals("retained",session.output.value) }
    }
}
