package com.baystudio.droide.ui

import android.app.Application
import android.widget.EditText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.compose.ui.unit.height
import androidx.compose.ui.viewinterop.AndroidView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real Compose/native focus and view identity; no process, PTY or real keyboard is started. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LandscapeEditorAreaTest {
    @get:Rule val compose = createComposeRule()
    private val focus = AccessoryInputFocusController()
    private val editorOwner = focus.owner(AccessoryInputTarget.EDITOR)
    private val terminalOwner = focus.owner(AccessoryInputTarget.TERMINAL_NATIVE)
    private val agentOwner = focus.owner(AccessoryInputTarget.NONE)
    private var ime by mutableStateOf(false)
    private var agentFocused by mutableStateOf(false)
    private var panelVisible by mutableStateOf(true)
    private var viewport by mutableStateOf(720.dp to 360.dp)
    private var requestedPanelHeight by mutableStateOf(160.dp)
    private var draft by mutableStateOf("")
    private var terminalState: LandscapeInputExpansion? = null
    private var editor: EditText? = null
    private var terminal: EditText? = null
    private val mounts = IntArray(2)
    private val releases = IntArray(2)
    private var keyboardHides = 0

    @Composable private fun NativeInput(index: Int) {
        val presented = LocalWorkspacePresented.current
        val owner = if (index == 0) editorOwner else terminalOwner
        AndroidView(
            factory = { context ->
                EditText(context).apply {
                    mounts[index]++
                    setText(if (index == 0) "editor draft" else "terminal draft")
                    setSelection(3)
                    onFocusChangeListener = android.view.View.OnFocusChangeListener { _, focused -> focus.onFocusChanged(owner, focused) }
                    if (index == 0) editor = this else terminal = this
                }
            },
            update = { view ->
                view.isFocusable = presented
                view.isFocusableInTouchMode = presented
                if (!presented) view.clearFocus()
            },
            onRelease = { releases[index]++; focus.clearIf(owner) },
            modifier = Modifier.fillMaxSize().testTag(if (index == 0) "native-editor" else "native-terminal"),
        )
    }

    private fun mount() {
        val keyboard = object : SoftwareKeyboardController {
            override fun hide() { keyboardHides++ }
            override fun show() = Unit
        }
        compose.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                DroideTheme {
                    val state = rememberLandscapeInputExpansion("fixture", true, ime,
                        when {
                            agentFocused -> LandscapeInputTarget.NONE
                            focus.activeTarget == AccessoryInputTarget.EDITOR -> LandscapeInputTarget.EDITOR
                            focus.activeTarget == AccessoryInputTarget.TERMINAL_NATIVE -> LandscapeInputTarget.TERMINAL
                            else -> LandscapeInputTarget.NONE
                        })
                    val editorTyping = state.editorExpanded
                    SideEffect { terminalState = state }
                    val layout = droideWorkbenchLayout(viewport.first, viewport.second, requestedPanelHeight, true)
                    val motion = rememberLandscapeWorkbenchMotion(editorTyping, state.terminalExpanded, panelVisible)
                    Column(Modifier.size(viewport.first, viewport.second)) {
                        LandscapeChromeReveal({ motion.normal.value }, !editorTyping && !state.terminalExpanded,
                            modifier = Modifier.testTag("header")) {
                            Box(Modifier.fillMaxWidth().height(52.dp)) { Text("Workspace header") }
                        }
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            LandscapeEditorArea("fixture", DroideBottomTool.Terminal, panelVisible,
                                layout.effectiveBottomPanelHeight, layout.bottomPanelMaxHeight,
                                editorTyping, state.terminalExpanded, onPanelHeightChange = { requestedPanelHeight = it },
                                onSelectTool = {}, onOpenPanel = { panelVisible = true },
                                onClosePanel = { panelVisible = false }, onRestoreTerminal = state.restore,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                motion = motion,
                                editor = {
                                    Column {
                                        DroideEditorToolbar("main.kt", false, false, {}, {}, {}, {}, {},
                                            onRestoreSize = state.restore.takeIf { state.editorExpanded })
                                        Box(Modifier.weight(1f)) { NativeInput(0) }
                                    }
                                }, panel = { NativeInput(1) })
                            Box(Modifier.width(180.dp).fillMaxHeight().testTag("chat-pane")) {
                                BasicTextField(draft, { draft = it }, modifier = Modifier.testTag("chat-input")
                                    .onFocusChanged { agentFocused = it.isFocused; focus.onFocusChanged(agentOwner, it.isFocused) })
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun terminalExpansionAndXRestoreKeepNativeViewDraftCursorAndNormalSize() {
        mount()
        val previousBounds = compose.onNodeWithTag("landscape-panel-region").getUnclippedBoundsInRoot()
        compose.runOnIdle { requireNotNull(terminal).requestFocus(); ime = true }
        compose.onNodeWithTag("header").assertHeightIsEqualTo(0.dp)
        compose.onNodeWithTag("landscape-panel-region").assertHeightIsEqualTo(360.dp)
        compose.onNodeWithTag("native-editor").assertDoesNotExist()
        val native = terminal
        compose.onNodeWithContentDescription("Restore terminal size").performClick()
        compose.onNodeWithTag("header").assertIsDisplayed()
        assertEquals(previousBounds, compose.onNodeWithTag("landscape-panel-region").getUnclippedBoundsInRoot())
        compose.runOnIdle {
            assertSame(native, terminal)
            assertEquals("terminal draft", terminal!!.text.toString())
            assertEquals(3, terminal!!.selectionStart)
            assertEquals(160.dp, requestedPanelHeight)
            assertArrayEquals(intArrayOf(1, 1), mounts)
            assertArrayEquals(intArrayOf(0, 0), releases)
            assertEquals(1, keyboardHides)
            assertFalse(requireNotNull(terminalState).terminalExpanded)
        }
    }

    @Test fun chatTypingDoesNotChangeTheExistingEditorExpansionOrChatWidth() {
        mount()
        val chatWidth = compose.onNodeWithTag("chat-pane").getUnclippedBoundsInRoot().width
        compose.runOnIdle { editor!!.requestFocus(); ime = true }
        compose.onNodeWithTag("header").assertHeightIsEqualTo(0.dp)
        compose.onNodeWithTag("native-terminal").assertDoesNotExist()
        compose.onNodeWithTag("native-editor").assertIsDisplayed()
        compose.onNodeWithTag("chat-input").performTextInput("Keep this chat draft")
        compose.onNodeWithTag("header").assertHeightIsEqualTo(0.dp)
        compose.onNodeWithTag("native-terminal").assertDoesNotExist()
        compose.onNodeWithContentDescription("Restore editor size").assertIsDisplayed()
        compose.onNodeWithContentDescription("Restore terminal size").assertDoesNotExist()
        assertEquals(chatWidth, compose.onNodeWithTag("chat-pane").getUnclippedBoundsInRoot().width)
        compose.runOnIdle {
            assertEquals("Keep this chat draft", draft)
            assertEquals(0, keyboardHides)
            assertFalse(requireNotNull(terminalState).terminalExpanded)
        }
    }

    @Test fun movingFromTerminalToChatKeepsUserExpansionUntilRestore() {
        mount()
        compose.runOnIdle { terminal!!.requestFocus(); ime = true }
        compose.onNodeWithContentDescription("Restore terminal size").assertIsDisplayed()
        compose.onNodeWithTag("chat-input").performTextInput("agent")
        compose.onNodeWithContentDescription("Restore terminal size").assertIsDisplayed()
        compose.onNodeWithTag("header").assertHeightIsEqualTo(0.dp)
        compose.runOnIdle { ime = false }
        compose.onNodeWithTag("landscape-panel-region").assertHeightIsEqualTo(360.dp)
        compose.onNodeWithContentDescription("Restore terminal size").performClick()
        compose.onNodeWithTag("header").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, keyboardHides); assertArrayEquals(intArrayOf(1, 1), mounts) }
    }

    @Test fun anInitiallyClosedPanelIsNotStartedAndAHiddenMountedPanelLosesItsHitTargets() {
        panelVisible = false
        mount()
        compose.runOnIdle { assertEquals(0, mounts[1]); panelVisible = true }
        compose.onNodeWithTag("native-terminal").assertIsDisplayed()
        compose.runOnIdle { panelVisible = false }
        compose.onNodeWithTag("native-terminal").assertDoesNotExist()
        compose.runOnIdle { panelVisible = true }
        compose.onNodeWithTag("native-terminal").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, mounts[1]); assertEquals(0, releases[1]) }
    }

    @Test fun windowResizingKeepsBothNativeInputsAndDoesNotRewriteTheSavedPanelHeight() {
        mount()
        for (size in listOf(480.dp to 180.dp, 1024.dp to 700.dp, 600.dp to 240.dp)) {
            compose.runOnIdle { viewport = size }
            val panel = compose.onNodeWithTag("landscape-panel-region").getUnclippedBoundsInRoot()
            assertTrue(panel.bottom <= size.second)
        }
        compose.runOnIdle {
            assertArrayEquals(intArrayOf(1, 1), mounts)
            assertArrayEquals(intArrayOf(0, 0), releases)
            assertEquals(160.dp, requestedPanelHeight)
        }
    }

    @Test fun focusTransitionHasIntermediateFramesAndReversesWithoutReplacingNativeInputs() {
        compose.mainClock.autoAdvance = false
        mount()
        val original = compose.onNodeWithTag("landscape-panel-region").getUnclippedBoundsInRoot()
        compose.runOnIdle { terminal!!.requestFocus(); ime = true }
        repeat(6) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        val halfway = compose.onNodeWithTag("landscape-panel-region").getUnclippedBoundsInRoot()
        val header = compose.onNodeWithTag("header").getUnclippedBoundsInRoot()
        assertTrue(halfway.height > original.height && halfway.height < viewport.second)
        assertTrue(header.height > 0.dp && header.height < 52.dp)
        compose.runOnIdle { requireNotNull(terminalState).restore() }
        compose.mainClock.advanceTimeByFrame()
        val reversed = compose.onNodeWithTag("landscape-panel-region").getUnclippedBoundsInRoot()
        assertTrue(reversed.height > original.height && reversed.height < viewport.second)
        compose.mainClock.advanceTimeBy(350)
        assertEquals(original, compose.onNodeWithTag("landscape-panel-region").getUnclippedBoundsInRoot())
        compose.runOnIdle {
            assertArrayEquals(intArrayOf(1, 1), mounts)
            assertArrayEquals(intArrayOf(0, 0), releases)
            assertEquals("terminal draft", terminal!!.text.toString())
            assertEquals(3, terminal!!.selectionStart)
        }
    }

    @Test fun panelToggleAnimatesButDividerDragAppliesItsSizeImmediately() {
        compose.mainClock.autoAdvance = false
        mount()
        compose.runOnIdle { panelVisible = false }
        repeat(6) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        val closing = compose.onNodeWithTag("landscape-panel-region").getUnclippedBoundsInRoot().height
        assertTrue(closing > 0.dp && closing < 160.dp)
        compose.onNodeWithTag("native-terminal").assertDoesNotExist()
        compose.runOnIdle { panelVisible = true }
        compose.mainClock.advanceTimeBy(350)
        compose.runOnIdle { requestedPanelHeight = 140.dp }
        // Drain snapshot/recomposition without advancing a full panel animation.
        repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithTag("landscape-panel-region").assertHeightIsEqualTo(140.dp)
        compose.runOnIdle { assertEquals(1, mounts[1]); assertEquals(0, releases[1]) }
    }
    @Test fun editorStaysExpandedAfterKeyboardDismissalAndXPreservesItsNativeCursor() {
        mount()
        val original = editor
        compose.runOnIdle { editor!!.requestFocus(); ime = true }
        compose.onNodeWithContentDescription("Restore editor size").assertIsDisplayed()
        compose.runOnIdle { ime = false }
        compose.onNodeWithTag("header").assertHeightIsEqualTo(0.dp)
        compose.onNodeWithContentDescription("Restore editor size").performClick()
        compose.onNodeWithTag("header").assertIsDisplayed()
        compose.onNodeWithContentDescription("Restore editor size").assertDoesNotExist()
        compose.runOnIdle {
            assertSame(original, editor)
            assertEquals("editor draft", editor!!.text.toString())
            assertEquals(3, editor!!.selectionStart)
            assertEquals(160.dp, requestedPanelHeight)
            assertArrayEquals(intArrayOf(1, 1), mounts)
        }
    }

}
