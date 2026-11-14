package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DroideSystemReducedMotionTest {
    @get:Rule val compose = createComposeRule(effectContext = object : MotionDurationScale { override val scaleFactor = 0f })

    @Test fun androidMotionDurationScaleZeroHasNoInitialTranslationOrDelayedControls() {
        compose.mainClock.autoAdvance = false
        compose.setContent { Box(Modifier.size(240.dp, 160.dp).testTag("layout")) {
            Box(Modifier.size(240.dp, 160.dp).droideContentMotion("settings").testTag("surface")) { Text("Settings") }
        } }
        assertEquals(compose.onNodeWithTag("layout").fetchSemanticsNode().boundsInRoot,
            compose.onNodeWithTag("surface").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test fun landscapeFocusAndManualPaneChangesAreImmediateWhenMotionIsDisabled() {
        compose.mainClock.autoAdvance = false
        val expanded = mutableStateOf(false)
        val visible = mutableStateOf(true)
        var normal = 0f
        var terminal = 0f
        var panel = 0f
        var enabled = true
        compose.setContent {
            val motionEnabled = rememberDroideMotionEnabled()
            val motion = rememberLandscapeWorkbenchMotion(false, expanded.value, visible.value)
            val n = motion.normal.value; val t = motion.terminal.value; val p = motion.panel.value
            SideEffect { normal = n; terminal = t; panel = p; enabled = motionEnabled }
        }
        compose.runOnIdle { assertEquals(false, enabled) }
        compose.runOnIdle { expanded.value = true; visible.value = false }
        repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.runOnIdle {
            assertEquals(0f, normal); assertEquals(1f, terminal); assertEquals(0f, panel)
        }
    }
    @Test fun portraitChatHonorsDisabledSystemAnimations() {
        compose.mainClock.autoAdvance = false
        val visible = mutableStateOf(false)
        compose.setContent {
            PortraitAgentPanel(visible.value, workspace = { Text("Workspace") }, agent = { Text("Conversation") })
        }
        compose.runOnIdle { visible.value = true }
        repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithText("Conversation").assertIsDisplayed()
        assertEquals(0.dp, compose.onNodeWithTag("portrait-agent-panel").getUnclippedBoundsInRoot().left)
        compose.runOnIdle { visible.value = false }
        repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithText("Conversation").assertDoesNotExist()
        compose.onNodeWithText("Workspace").assertIsDisplayed()
    }

}
