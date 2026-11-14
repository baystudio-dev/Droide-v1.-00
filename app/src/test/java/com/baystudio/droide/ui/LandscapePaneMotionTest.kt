package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LandscapePaneMotionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun hiddenPaneLeavesTheAccessibilityTreeWithoutDestroyingItsSession() {
        val width = mutableStateOf(240.dp)
        var mounts = 0
        var live = 0
        compose.setContent { Row(Modifier.size(360.dp, 200.dp)) {
            DroideClippedPaneRegion(width.value, 240.dp) {
                DisposableEffect(Unit) { mounts++; live++; onDispose { live-- } }
                Text("Agent controls")
            }
            Text("Editor")
        } }
        compose.onNodeWithText("Agent controls").assertExists()
        compose.runOnIdle { width.value = 0.dp }
        compose.onNodeWithText("Agent controls").assertDoesNotExist()
        compose.runOnIdle { width.value = 240.dp }
        compose.onNodeWithText("Agent controls").assertExists()
        assertEquals(1, mounts)
        assertEquals(1, live)
    }

    @Test fun visibilityInterpolationKeepsEveryPaneInBudgetAndDirectResizeIsImmediate() {
        for (full in listOf(0, 180, 246, 336, 520)) for (visible in listOf(false, true))
        for (fraction in listOf(-.1f, 0f, .2f, .5f, 1f, 1.1f)) {
            val actual = droideFocusPaneWidth(full.dp, visible, fraction)
            assertTrue(actual >= 0.dp && actual <= full.dp)
            if (!visible) assertEquals(0.dp, actual)
        }
        assertEquals(300.dp, droideFocusPaneWidth(300.dp, true, 1f))
        assertEquals(380.dp, droideFocusPaneWidth(380.dp, true, 1f))
        assertEquals(190.dp, droideFocusPaneWidth(380.dp, true, .5f))
    }

    @Test fun keyboardFocusReversalDuringMotionRestoresOneLivePaneWithinItsBudget() {
        compose.mainClock.autoAdvance = false
        val ime = mutableStateOf(false)
        var width = 0.dp
        var full = 0.dp
        var live = 0
        var mounts = 0
        compose.setContent {
            val layout = rememberDroideLandscapeImeFocusLayout(
                droideWorkbenchLayout(1280.dp, 800.dp, 210.dp), 1280.dp, DroideRailTool.Files,
                droideImePresentation(true, false, ime.value, true, false), ime.value, true, false, 300.dp,
            )
            val observedWidth = layout.sidebarRegionWidth
            SideEffect { width = observedWidth; full = layout.userPaneFit.sidebarWidth + 1.dp }
            Row(Modifier.size(360.dp, 200.dp)) {
                DroideClippedPaneRegion(layout.sidebarRegionWidth, layout.userPaneFit.sidebarWidth + 1.dp) {
                    DisposableEffect(Unit) { mounts++; live++; onDispose { live-- } }
                    Text("Same session")
                }
            }
        }
        compose.runOnIdle { ime.value = true }
        // Install the new target and flush the animation collector before measuring its frames.
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { }
        compose.mainClock.advanceTimeBy(80)
        compose.runOnIdle { assertTrue("Intermediate width=$width, full=$full", width > 0.dp && width < full) }
        compose.runOnIdle { ime.value = false }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { }
        compose.mainClock.advanceTimeBy(300)
        compose.runOnIdle {
            assertEquals(full, width)
            assertEquals(1, live)
            assertEquals(1, mounts)
        }
    }

    @Test
    @Config(qualifiers = "w720dp-h360dp-land-mdpi")
    fun manualSidebarAndChatTogglesReclaimSpaceGraduallyAndCanReverse() {
        compose.mainClock.autoAdvance = false
        val sidebar = mutableStateOf(true)
        val agent = mutableStateOf(true)
        var mounts = 0
        compose.setContent {
            val focus = rememberDroideLandscapeImeFocusLayout(
                droideWorkbenchLayout(720.dp, 360.dp, 160.dp, physicalLandscape = true),
                720.dp, DroideRailTool.Files, droideImePresentation(true, false, false, sidebar.value, agent.value),
                false, sidebar.value, agent.value, 260.dp,
            )
            Row(Modifier.size(720.dp, 200.dp)) {
                if (sidebar.value || focus.sidebarRegionVisible) {
                    DroideClippedPaneRegion({ focus.sidebarRegionWidth }, focus.sidebarRegionVisible,
                        focus.contentPaneFit.sidebarWidth + 1.dp) {
                        RetainedWorkspaceContent(sidebar.value, drawWhileHidden = focus.sidebarRegionVisible) {
                            DisposableEffect(Unit) { mounts++; onDispose { } }
                            Text("Sidebar action")
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight().testTag("editor"))
                if (agent.value || focus.agentRegionVisible) {
                    DroideClippedPaneRegion({ focus.agentRegionWidth }, focus.agentRegionVisible,
                        focus.contentPaneFit.agentWidth + 10.dp) { Text("Chat") }
                }
            }
        }
        fun width() = compose.onNodeWithTag("editor").fetchSemanticsNode().boundsInRoot.width
        val original = width()
        compose.runOnIdle { sidebar.value = false }
        repeat(6) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        val intermediate = width()
        assertTrue(intermediate > original)
        compose.onNodeWithText("Sidebar action").assertDoesNotExist()
        compose.runOnIdle { sidebar.value = true }
        compose.mainClock.advanceTimeByFrame()
        assertTrue(width() > original)
        compose.mainClock.advanceTimeBy(350)
        assertEquals(original, width(), .01f)
        assertEquals(1, mounts)
        compose.runOnIdle { sidebar.value = false; agent.value = false }
        repeat(6) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        val closingBoth = width()
        compose.mainClock.advanceTimeBy(350)
        assertTrue(closingBoth > original && closingBoth < width())
    }
}
