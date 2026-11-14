package com.baystudio.droide.ui

import android.app.Application
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import org.robolectric.annotation.GraphicsMode

/**
 * Authored, uncompiled/unrun Android/Compose candidates. Exercises the real fallback pane and
 * pointer-dispatched accessory arrows. The fake ITerminalSession records boundary calls only;
 * these tests make no claim about process queue admission, shell execution or native PTY delivery.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerminalCommandHistoryUiR73Test {
    @get:Rule val compose = createComposeRule()
    private val root = Files.createTempDirectory("droide-history-r73-").toFile()
    private class Session : ITerminalSession {
        override val output = MutableStateFlow("ready")
        var reject = false
        val commands = mutableListOf<String>()
        override fun start() = Unit
        override fun send(cmd: String) {
            if (reject) throw IOException("Test input rejected")
            commands += cmd
        }
        override fun interrupt() = Unit
        override fun clear() { output.value = "" }
        override fun destroy() = Unit
        override suspend fun execOnce(cmd: String, timeoutMs: Long) = ExecResult(0, "", false)
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long) = ExecResult(0, "", false)
    }
    private val first = Session()
    private var active by mutableStateOf<ITerminalSession>(first)
    private var view: View? = null
    private var density = 1f

    @After fun release() { root.deleteRecursively() }

    private fun mount() {
        val theme = ThemeManager(root).snapshot()
        compose.setContent {
            val host = LocalView.current
            val scale = LocalDensity.current.density
            SideEffect { view = host; density = scale }
            DroideTheme { Box(Modifier.fillMaxSize()) {
                val focus = remember { AccessoryInputFocusController() }
                TerminalPane(active, TerminalManager.Profile.LOCAL_QEMU, theme = theme,
                    accessoryInputFocus = focus, accessoryKeysExpanded = true,
                    onAccessoryKeysExpandedChange = {})
            } }
        }
        focusInput()
    }
    private fun focusInput() {
        command().performClick()
        compose.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(requireNotNull(view), WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, (200 * density).toInt()))
                .setVisible(WindowInsetsCompat.Type.ime(), true).build())
        }
        compose.waitForIdle()
    }
    private fun command() = compose.onNode(hasSetTextAction())
    private fun value() = command().fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun tapArrow(description: String) {
        compose.onNodeWithContentDescription(description, substring = true).performTouchInput {
            down(center); advanceEventTime(16); up()
        }
    }
    private fun submit(text: String) {
        command().performTextReplacement(text)
        command().performImeAction()
        assertEquals("", value())
    }

    @Test fun upAndDownRecallWithoutResubmittingAndRestoreTheLiveUnicodeDraft() {
        mount()
        submit("echo first"); submit("echo 日本語 🙂")
        command().performTextReplacement("unsent 🙂 draft")
        tapArrow("Up arrow"); assertEquals("echo 日本語 🙂", value())
        tapArrow("Up arrow"); assertEquals("echo first", value())
        tapArrow("Up arrow"); assertEquals("echo first", value())
        tapArrow("Down arrow"); assertEquals("echo 日本語 🙂", value())
        tapArrow("Down arrow"); assertEquals("unsent 🙂 draft", value())
        assertEquals(listOf("echo first", "echo 日本語 🙂"), first.commands)
    }

    @Test fun failedSubmissionRetainsDraftAndDoesNotEnterHistory() {
        mount(); submit("accepted")
        compose.runOnIdle { first.reject = true }
        command().performTextReplacement("rejected draft")
        command().performImeAction()
        assertEquals("rejected draft", value())
        compose.onNodeWithText("Test input rejected").assertExists()
        tapArrow("Up arrow"); assertEquals("accepted", value())
        tapArrow("Down arrow"); assertEquals("rejected draft", value())
        assertEquals(listOf("accepted"), first.commands)
    }

    @Test fun replacingTheActiveSessionDoesNotRecallAnotherSessionsCommands() {
        mount(); submit("first session")
        val second = Session()
        compose.runOnIdle { active = second }
        focusInput()
        command().performTextReplacement("second draft")
        tapArrow("Up arrow"); assertEquals("second draft", value())
        tapArrow("Down arrow"); assertEquals("second draft", value())
        assertEquals(listOf("first session"), first.commands)
        assertTrue(second.commands.isEmpty())
    }
}
