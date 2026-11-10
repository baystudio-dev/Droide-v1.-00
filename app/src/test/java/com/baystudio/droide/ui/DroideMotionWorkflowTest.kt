package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
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
class DroideMotionWorkflowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rapidNavigationHasOneSurfaceAndActionsUseTheCurrentPageDuringMotion() {
        compose.mainClock.autoAdvance = false
        val page = mutableStateOf("Files")
        var live = 0
        var peak = 0
        val clicked = mutableListOf<String>()
        compose.setContent { DroideTheme {
            Box(Modifier.size(240.dp, 160.dp).droideContentMotion(page.value)) {
                DisposableEffect(Unit) { live++; peak = maxOf(peak, live); onDispose { live-- } }
                Button(onClick = { clicked += page.value }) { Text(page.value) }
            }
        } }
        for (next in listOf("Terminal", "Editor", "Extensions", "Files")) {
            compose.runOnIdle { page.value = next }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText(next).performClick()
        }
        compose.mainClock.advanceTimeBy(250)
        assertEquals(listOf("Terminal", "Editor", "Extensions", "Files"), clicked)
        assertEquals(1, live)
        assertEquals("Navigation motion cannot duplicate native surfaces", 1, peak)
    }

    @Test fun streamingContentDoesNotReplayTheSettledEntrance() {
        val text = mutableStateOf("First chunk")
        compose.setContent { Box(Modifier.size(240.dp, 160.dp).droideContentMotion("terminal").testTag("surface")) { Text(text.value) } }
        val settled = compose.onNodeWithTag("surface").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { text.value = "First chunk and next chunk" }
        compose.onNodeWithText(text.value).assertExists()
        assertEquals(settled, compose.onNodeWithTag("surface").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun entranceSettlesWithoutChangingTheParentsLayoutBounds() {
        compose.mainClock.autoAdvance = false
        compose.setContent { Box(Modifier.size(240.dp, 160.dp).testTag("layout")) {
            Box(Modifier.size(240.dp, 160.dp).droideContentMotion("settings").testTag("surface")) { Text("Settings") }
        } }
        val parent = compose.onNodeWithTag("layout").fetchSemanticsNode().boundsInRoot
        val initial = compose.onNodeWithTag("surface").fetchSemanticsNode().boundsInRoot
        compose.mainClock.advanceTimeBy(250)
        val settled = compose.onNodeWithTag("surface").fetchSemanticsNode().boundsInRoot
        assertTrue(initial.top > settled.top)
        assertEquals(parent, compose.onNodeWithTag("layout").fetchSemanticsNode().boundsInRoot)
        assertEquals(parent.top, settled.top, .01f)
    }

    @Test fun motionDisabledContentIsImmediatelyAtItsFinalReadablePosition() {
        compose.mainClock.autoAdvance = false
        compose.setContent { Box(Modifier.size(240.dp, 160.dp).testTag("layout")) {
            Box(Modifier.size(240.dp, 160.dp).droideContentMotion("settings", enabled = false).testTag("surface")) { Text("Settings") }
        } }
        assertEquals(compose.onNodeWithTag("layout").fetchSemanticsNode().boundsInRoot,
            compose.onNodeWithTag("surface").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("Settings").assertIsDisplayed()
    }
}
