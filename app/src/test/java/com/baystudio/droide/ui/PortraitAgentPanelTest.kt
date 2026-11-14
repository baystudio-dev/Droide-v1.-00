package com.baystudio.droide.ui

import android.app.Application
import android.widget.EditText
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PortraitAgentPanelTest {
    @get:Rule val compose = createComposeRule()
    private var visible by mutableStateOf(false)
    private var editor: EditText? = null
    private var editorMounts = 0
    private var editorReleases = 0
    private var agents = 0
    private var maxAgents = 0

    private fun mount() {
        compose.setContent { DroideTheme { Box(Modifier.size(360.dp, 640.dp)) {
            PortraitAgentPanel(visible, workspace = {
                val presented = LocalWorkspacePresented.current
                AndroidView(factory = { context -> EditText(context).apply {
                    editorMounts++; editor = this
                    setText("Unsaved editor draft"); setSelection(4)
                } }, update = { view ->
                    view.isFocusable = presented; view.isFocusableInTouchMode = presented
                    if (!presented) view.clearFocus()
                }, onRelease = { editorReleases++ }, modifier = Modifier.fillMaxSize().testTag("editor"))
            }, agent = {
                DisposableEffect(Unit) { agents++; maxAgents = maxOf(maxAgents, agents); onDispose { agents-- } }
                Text("Agent conversation", Modifier.fillMaxSize())
            })
        } } }
    }

    @Test fun openAndCloseAnimateBothDirectionsWithoutRecreatingEditorOrItsCursor() {
        compose.mainClock.autoAdvance = false
        mount(); val original = editor
        compose.runOnIdle { visible = true }
        repeat(5) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        val entering = compose.onNodeWithText("Agent conversation").getUnclippedBoundsInRoot().left
        assertTrue("Incoming content left=$entering", entering > 0.dp && entering < 32.dp)
        compose.onNodeWithTag("editor").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(300)
        assertEquals(0.dp, compose.onNodeWithTag("portrait-agent-panel").getUnclippedBoundsInRoot().left)
        compose.runOnIdle { visible = false }
        repeat(5) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithText("Agent conversation").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, agents) }
        compose.mainClock.advanceTimeBy(300)
        compose.runOnIdle {
            assertEquals(0, agents); assertEquals(1, editorMounts); assertEquals(0, editorReleases)
            assertSame(original, editor); assertEquals(4, editor!!.selectionStart)
            assertEquals("Unsaved editor draft", editor!!.text.toString())
        }
    }

    @Test fun rapidReversalKeepsOneAgentOwnerAndContinuesFromTheCurrentFrame() {
        compose.mainClock.autoAdvance = false
        mount()
        compose.runOnIdle { visible = true }
        repeat(5) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.runOnIdle { visible = false }
        repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.runOnIdle { visible = true }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("Agent conversation").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, maxAgents); assertEquals(1, editorMounts); assertEquals(0, editorReleases) }
    }
}
