package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.baystudio.droide.core.*
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp")
class FrontendTerminalUserWorkflowTest {
    @get:Rule val compose = createComposeRule()
    private val root = Files.createTempDirectory("droide-frontend-console-").toFile()
    private val session = Session()

    private class Session : ITerminalSession {
        override val output = MutableStateFlow("ready")
        var rejectSend = false
        var rejectInterrupt = false
        val commands = mutableListOf<String>()
        override fun start() = Unit
        override fun send(cmd: String) {
            if (rejectSend) throw IOException("Shell input is unavailable")
            commands += cmd
        }
        override fun interrupt() { if (rejectInterrupt) throw IOException("Shell interrupt is unavailable") }
        override fun clear() { output.value = "" }
        override fun destroy() = Unit
        override suspend fun execOnce(cmd: String, timeoutMs: Long) = ExecResult(0, "", false)
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long) = ExecResult(0, "", false)
    }

    @After fun release() { root.deleteRecursively() }

    private fun mount(expandedKeys: Boolean = false) {
        val theme = ThemeManager(root).snapshot()
        compose.setContent { DroideTheme { Box(Modifier.fillMaxSize()) {
            val focus = remember { AccessoryInputFocusController() }
            TerminalPane(session, TerminalManager.Profile.LOCAL_QEMU, theme = theme,
                accessoryInputFocus = focus, accessoryKeysExpanded = expandedKeys,
                onAccessoryKeysExpandedChange = {})
        } } }
    }

    private fun command() = compose.onNode(hasSetTextAction())
    private fun transcript() = compose.onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
    private fun scrollValue(): Float = transcript().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun scrollMaximum(): Float = transcript().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue()

    @Test fun rejectedRunKeepsTheDraftAndRetryOnlyClearsAfterAcceptedInput() {
        session.rejectSend = true
        mount()
        command().performTextInput("npm test")
        compose.onNodeWithContentDescription("Run").performTouchInput { click() }
        compose.onNodeWithText("Shell input is unavailable").assertExists()
        command().assertTextContains("npm test")
        assertTrue(session.commands.isEmpty())
        compose.runOnIdle { session.rejectSend = false }
        compose.onNodeWithContentDescription("Run").performTouchInput { click() }
        assertEquals("", command().fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals(listOf("npm test"), session.commands)
        compose.onNodeWithText("Shell input is unavailable").assertDoesNotExist()
    }

    @Test fun rejectedImeSendKeepsTheCommandAndUiRemainsUsable() {
        session.rejectSend = true
        mount()
        command().performTextInput("python app.py")
        command().performImeAction()
        compose.onNodeWithText("Shell input is unavailable").assertExists()
        command().assertTextContains("python app.py")
        compose.onNodeWithText("Commands").performClick()
        compose.onNodeWithContentDescription("Clear terminal output").performClick()
        assertEquals("", session.output.value)
    }

    @Test fun incomingOutputDoesNotMoveAUserReadingOlderLogsAndLatestResumesFollowing() {
        session.output.value = (0 until 150).joinToString("\n") { "build line $it" }
        mount()
        compose.waitForIdle()
        assertEquals("A fresh terminal follows the initial output", scrollMaximum(), scrollValue(), 1f)
        assertTrue(scrollMaximum() > 300f)
        transcript().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -350f) }
        compose.waitForIdle()
        val readingPosition = scrollValue()
        compose.runOnIdle { session.output.value += "\nnew output while reading" }
        compose.waitForIdle()
        assertEquals("Incoming output must preserve the reading position", readingPosition, scrollValue(), 1f)
        compose.onNodeWithText("Latest output").performClick()
        compose.waitForIdle()
        assertEquals(scrollMaximum(), scrollValue(), 1f)
        compose.runOnIdle { session.output.value += "\nfollowing again" }
        compose.waitForIdle()
        assertEquals(scrollMaximum(), scrollValue(), 1f)
    }
}
