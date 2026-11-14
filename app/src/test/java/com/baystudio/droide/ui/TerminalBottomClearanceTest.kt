package com.baystudio.droide.ui

import android.app.Application
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.baystudio.droide.core.DevicePtySessionHandle
import com.baystudio.droide.core.DeviceTerminalEmulator
import com.baystudio.droide.core.ExecResult
import com.baystudio.droide.core.ITerminalSession
import com.baystudio.droide.core.TerminalManager
import com.baystudio.droide.core.ThemeManager
import com.termux.view.TerminalRenderer
import java.nio.file.Files
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real pane measurement, emulator resize, URL touch and IME input; no child process or device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerminalBottomClearanceTest {
    @get:org.junit.Rule val terminalMemoryBudget = com.termux.terminal.TerminalMemoryBudgetTestRule()

    @get:Rule val compose = createComposeRule()
    private val root = Files.createTempDirectory("terminal-touch-clearance-").toFile()
    private lateinit var composeView: View
    private lateinit var focusController: AccessoryInputFocusController
    private var density = 1f
    private var receivedImeBottom = 0
    private var expanded by mutableStateOf(false)

    private open class PipeSession : ITerminalSession {
        override val output = MutableStateFlow("")
        val input = mutableListOf<String>()
        var destroyed = 0
        override fun start() = Unit
        override fun send(cmd: String) { input += cmd }
        override fun interrupt() = Unit
        override fun clear() { output.value = "" }
        override fun destroy() { destroyed++ }
        override suspend fun execOnce(cmd: String, timeoutMs: Long) = ExecResult(0, "", false)
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long) = ExecResult(0, "", false)
    }

    private class PtySession : PipeSession(), DevicePtySessionHandle {
        override val terminalEmulator = DeviceTerminalEmulator({ input += it.decodeToString() }, 40, 24)
        override val terminalTitle = terminalEmulator.title
        override val workingDirectory = terminalEmulator.workingDirectory
        override val profileLabel = "test PTY"
        override val status = MutableStateFlow("Ready")
        val sizes = mutableListOf<Pair<Int, Int>>()
        override fun writeRaw(text: String) { input += text }
        override fun sendKeyCode(keyCode: Int, modifiers: Int) = Unit
        override fun resize(columns: Int, rows: Int) {
            sizes += columns to rows
            terminalEmulator.resize(columns, rows)
        }
        override suspend fun refreshWorkspace() = Unit
    }

    @After fun cleanUp() { root.deleteRecursively() }

    private fun mount(session: ITerminalSession, width: Int = 360, height: Int = 560) {
        val theme = ThemeManager(root).snapshot()
        compose.setContent {
            val view = LocalView.current
            val currentDensity = LocalDensity.current
            val imeBottom = WindowInsets.ime.getBottom(currentDensity)
            SideEffect {
                composeView = view
                density = currentDensity.density
                receivedImeBottom = imeBottom
            }
            DroideTheme {
                Box(Modifier.width(width.dp).height(height.dp).testTag("terminal-host")) {
                    val focus = remember { AccessoryInputFocusController() }
                    SideEffect { focusController = focus }
                    TerminalPane(session, TerminalManager.Profile.LOCAL_QEMU, theme = theme,
                        accessoryInputFocus = focus, accessoryKeysExpanded = expanded,
                        onAccessoryKeysExpandedChange = { expanded = it })
                }
            }
        }
        compose.waitForIdle()
        if (session is PtySession) {
            // An injected inset does not create a focused IME client. Request
            // the actual attached Android view, as a human terminal tap does.
            compose.runOnIdle { assertTrue(surfaces().single().requestFocus()) }
            compose.waitForIdle()
            compose.runOnIdle {
                assertTrue("Fixture must own terminal input focus", surfaces().single().hasFocus())
                assertEquals(AccessoryInputTarget.TERMINAL_DEVICE, focusController.activeTarget)
            }
        }
    }

    private fun dispatchIme(heightDp: Int) {
        val pixels = (heightDp * density).roundToInt()
        compose.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(composeView, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, pixels))
                .setVisible(WindowInsetsCompat.Type.ime(), heightDp > 0).build())
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        assertEquals("Fixture must deliver actual keyboard insets", pixels, receivedImeBottom)
    }

    private fun surfaces(view: View = composeView): List<DeviceTerminalView> = when (view) {
        is DeviceTerminalView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { surfaces(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun surfaceBottomInRoot(): Float {
        val rootLocation = IntArray(2).also(composeView::getLocationInWindow)
        val surface = surfaces().single()
        val surfaceLocation = IntArray(2).also(surface::getLocationInWindow)
        return (surfaceLocation[1] - rootLocation[1] + surface.height).toFloat()
    }

    private fun assertClearance(imeDp: Int, keysVisible: Boolean) {
        val boundary = if (keysVisible) {
            compose.onNodeWithContentDescription("Escape").fetchSemanticsNode().boundsInRoot.top
        } else {
            compose.onNodeWithTag("terminal-host").fetchSemanticsNode().boundsInRoot.bottom - imeDp * density
        }
        val gap = (boundary - surfaceBottomInRoot()) / density
        assertTrue("Last row needs a touch gutter above keyboard/keys; gap=$gap dp", gap >= 7f)
        assertTrue("The gutter must remain compact; gap=$gap dp", gap <= 25f)
        assertTrue(surfaces().single().height > 0)
    }

    private fun keyboardAndKeysRetainPty(width: Int, height: Int, imeDp: Int) {
        val session = PtySession()
        mount(session, width, height)
        val original = surfaces().single()
        val rowsWithoutKeyboard = session.terminalEmulator.rows()
        compose.runOnIdle { session.terminalEmulator.appendFromWorker("kept terminal output\r\n$ ") }
        dispatchIme(imeDp)
        assertClearance(imeDp, keysVisible = false)
        val rowsWithKeyboard = session.terminalEmulator.rows()
        assertTrue("The PTY must resize with the actual smaller viewport", rowsWithKeyboard < rowsWithoutKeyboard)
        compose.onNodeWithContentDescription("Show terminal keys").performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        assertClearance(imeDp, keysVisible = true)
        assertTrue("Showing keys must reduce rows rather than cover them", session.terminalEmulator.rows() < rowsWithKeyboard)
        compose.onNodeWithContentDescription("Hide terminal keys").performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        assertClearance(imeDp, keysVisible = false)
        assertEquals(rowsWithKeyboard, session.terminalEmulator.rows())
        dispatchIme(0)
        compose.runOnIdle {
            assertSame("Keyboard/deck resize must keep the attached view and live session", original, surfaces().single())
            assertEquals(rowsWithoutKeyboard, session.terminalEmulator.rows())
            assertTrue(session.terminalEmulator.snapshotText().contains("kept terminal output"))
            original.onCreateInputConnection(EditorInfo()).commitText("echo halo\n", 1)
            assertEquals("echo halo\r", session.input.joinToString(""))
            assertEquals(0, session.destroyed)
        }
    }

    @Test fun portraitKeyboardAndAccessoryKeysLeaveTouchableSpaceWithoutReplacingPty() {
        keyboardAndKeysRetainPty(360, 560, 250)
    }

    @Test @Config(qualifiers = "w720dp-h360dp-land")
    fun landscapeKeyboardAndAccessoryKeysLeaveTouchableSpaceWithoutReplacingPty() {
        keyboardAndKeysRetainPty(720, 340, 110)
    }

    private fun openLastRowUrl(width: Int, height: Int, imeDp: Int) {
        val session = PtySession()
        mount(session, width, height)
        dispatchIme(imeDp)
        for (keysVisible in listOf(false, true)) {
            if (keysVisible) {
                compose.onNodeWithContentDescription("Show terminal keys").performClick()
                compose.mainClock.advanceTimeBy(300)
                compose.waitForIdle()
            }
            assertClearance(imeDp, keysVisible)
            lateinit var url: String
            var tapInHost = Offset.Zero
            var priorInput = ""
            val hostBounds = compose.onNodeWithTag("terminal-host").fetchSemanticsNode().boundsInRoot
            compose.runOnIdle {
                val surface = surfaces().single()
                val size = nativeTerminalTextSizePixels(surface.context, NativeTerminalTextSize.DEFAULT_SP)
                val renderer = TerminalRenderer(size, Typeface.MONOSPACE)
                val firstLineOffset = Paint(Paint.ANTI_ALIAS_FLAG).run {
                    typeface = Typeface.MONOSPACE
                    textSize = size.toFloat()
                    ceil(fontSpacing.toDouble()).toInt() + ceil(ascent().toDouble()).toInt()
                }
                val row = session.terminalEmulator.rows() - 1
                val columns = session.terminalEmulator.columns()
                val prefix = "https://example.test/"
                assertTrue(columns > prefix.length + 2)
                url = prefix + "x".repeat(columns - prefix.length - 1)
                session.terminalEmulator.appendFromWorker("\u001b[2J\u001b[${row + 1};1H$url")
                val x = (columns - 1.5f) * renderer.fontWidth
                val y = firstLineOffset + (row + .5f) * renderer.fontLineSpacing
                assertTrue("Fixture must tap an actual visible last-row cell", y < surface.height)
                val surfaceLocation = IntArray(2).also(surface::getLocationInWindow)
                val rootLocation = IntArray(2).also(composeView::getLocationInWindow)
                tapInHost = Offset(surfaceLocation[0] - rootLocation[0] + x - hostBounds.left,
                    surfaceLocation[1] - rootLocation[1] + y - hostBounds.top)
                priorInput = session.input.joinToString("")
            }
            // Dispatch through the actual Compose host: a direct View tap would
            // incorrectly bypass an accessory handle overlaying the last cells.
            compose.onNodeWithTag("terminal-host").performTouchInput { click(tapInHost) }
            compose.runOnIdle {
                val surface = surfaces().single()
                val opened = shadowOf(surface.context.applicationContext as Application).nextStartedActivity
                assertNotNull("A last-row URL near the right edge must not be intercepted by the keys handle", opened)
                assertEquals(url, opened.dataString)
                assertEquals("A URL tap must not inject bytes into the shell", priorInput, session.input.joinToString(""))
                surface.onCreateInputConnection(EditorInfo()).commitText("authorization-code\n", 1)
                assertEquals(priorInput + "authorization-code\r", session.input.joinToString(""))
            }
        }
    }

    @Test fun portraitLastRowUrlRemainsClickableAboveKeyboardAndInputStaysInteractive() {
        openLastRowUrl(360, 560, 250)
    }

    @Test @Config(qualifiers = "w720dp-h360dp-land")
    fun landscapeLastRowUrlRemainsClickableAboveKeyboardAndInputStaysInteractive() {
        openLastRowUrl(720, 340, 110)
    }

    @Test fun fallbackFollowingTranscriptCanScrollPastItsFinalLineToTheTouchGutter() {
        val session = PipeSession().apply {
            output.value = (0 until 100).joinToString("\n") { "output line $it" }
        }
        mount(session)
        dispatchIme(200)
        val scroll = compose.onNode(hasScrollAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        val range = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(range.maxValue() > 0f)
        assertEquals("Following output includes the trailing content gutter", range.maxValue(), range.value(), 1f)
        val lastTextBottom = compose.onNodeWithText(session.output.value).fetchSemanticsNode().boundsInRoot.bottom
        val visibleBottom = scroll.fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("Padding must be inside scroll content, not cover the last line", visibleBottom - lastTextBottom >= 7f * density)
        scroll.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, -120f) }
        compose.waitForIdle()
        val readingPosition = range.value()
        dispatchIme(250)
        assertEquals("Keyboard resize must preserve a user reading older output", readingPosition, range.value(), 1f)
        compose.onNodeWithText("Latest output").assertExists()
        compose.runOnIdle { session.output.value += "\nnew output while reading history" }
        compose.waitForIdle()
        assertEquals("Incoming output must preserve the history position after keyboard resize", readingPosition, range.value(), 1f)
        compose.onNodeWithText("Latest output").performClick()
        compose.waitForIdle()
        assertEquals("Latest output must include the new tail and its gutter", range.maxValue(), range.value(), 1f)
        val resumedTextBottom = compose.onNodeWithText(session.output.value).fetchSemanticsNode().boundsInRoot.bottom
        val resumedVisibleBottom = scroll.fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("Resuming follow must keep the final line above its touch gutter", resumedVisibleBottom - resumedTextBottom >= 7f * density)
        assertEquals(0, session.destroyed)
    }
}

/** Test fixture only: production PTY readers decode off the Android Looper. */
private fun com.baystudio.droide.core.DeviceTerminalEmulator.appendFromWorker(text: String) =
    kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.Default) { appendText(text) }
