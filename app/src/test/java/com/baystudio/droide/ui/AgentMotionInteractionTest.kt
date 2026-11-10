package com.baystudio.droide.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentMotionInteractionTest {
    private val durationScale = mutableStateOf(1f)
    @get:Rule val compose = createComposeRule(effectContext = object : MotionDurationScale {
        override val scaleFactor: Float get() = durationScale.value
    })
    private val tool = AgentStreamPart("tool", 1, AgentStreamPartKind.TOOL, AgentStreamPartState.COMPLETED,
        title = "read file", toolName = "read_file", output = "One\nTwo\nThree\nFour", detail = "Succeeded")
    private fun height(tag: String = "tool") = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height
    private fun <T> pixels(view: View, read: (Bitmap) -> T): T = compose.runOnIdle {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try { view.draw(Canvas(bitmap)); read(bitmap) } finally { bitmap.recycle() }
    }

    @Test fun entranceMovesOnlyClippedPixelsAndAcceptsImmediateInput() {
        compose.mainClock.autoAdvance = false
        val policy = AgentActivityEntrancePolicy(emptyList())
        lateinit var view: View
        var clicks = 0
        compose.setContent { view = LocalView.current
            Box(Modifier.size(80.dp).background(Color.Black)) {
                Box(Modifier.size(40.dp).agentActivityEntrance("fresh", policy, true)
                    .background(Color.White).testTag("tile").clickable { clicks++ })
            }
        }
        compose.mainClock.advanceTimeByFrame()
        val b = compose.onNodeWithTag("tile").fetchSemanticsNode().boundsInRoot
        fun red(x: Int, y: Int) = pixels(view) { android.graphics.Color.red(it.getPixel(x, y)) }
        assertEquals(0, red(b.center.x.toInt(), b.top.toInt()))
        assertTrue(red(b.center.x.toInt(), b.center.y.toInt()) > 170)
        assertEquals(0, red(b.center.x.toInt(), b.bottom.toInt() + 1))
        compose.onNodeWithTag("tile").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, clicks) }
        compose.mainClock.advanceTimeBy(240)
        assertEquals(255, red(b.center.x.toInt(), b.top.toInt()))
        assertEquals(b, compose.onNodeWithTag("tile").fetchSemanticsNode().boundsInRoot)
        assertEquals(0, red(b.center.x.toInt(), b.bottom.toInt() + 1))
    }

    @Test fun rapidPhaseChangesAndStopExposeOnlyTheCurrentStatus() {
        compose.mainClock.autoAdvance = false
        var kind by mutableStateOf(AgentActivityKind.WORKING)
        lateinit var view: View
        compose.setContent { view = LocalView.current; DroideTheme {
            Box(Modifier.width(280.dp).height(48.dp).background(Color.Black)) {
                AgentActivityStatus(AgentActivityPresentation(kind))
            }
        } }
        for (next in listOf(AgentActivityKind.THINKING, AgentActivityKind.COMMAND, AgentActivityKind.APPROVAL, AgentActivityKind.STOPPED)) {
            val old = kind
            compose.runOnIdle { kind = next }
            var rendered = false
            var frames = 0
            while (!rendered && frames < 6) {
                compose.mainClock.advanceTimeByFrame()
                frames++
                rendered = compose.onAllNodesWithText(next.label).fetchSemanticsNodes().size == 1
            }
            assertTrue("Current status must render before a 120 ms transition finishes: ${next.label} after $frames frames\n${compose.onRoot().printToString()}", rendered)
            compose.onNodeWithText(old.label).assertDoesNotExist()
        }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        val b = compose.onNodeWithText("Stopped").fetchSemanticsNode().boundsInRoot
        fun ink() = pixels(view) { bitmap ->
            var sum = 0L
            for (y in b.top.toInt() until b.bottom.toInt()) for (x in b.left.toInt() until b.right.toInt()) {
                val p = bitmap.getPixel(x, y)
                sum += android.graphics.Color.red(p) + android.graphics.Color.green(p) + android.graphics.Color.blue(p)
            }
            sum
        }
        val immediate = ink()
        compose.mainClock.advanceTimeBy(240)
        assertTrue(immediate > 0)
        assertEquals(immediate, ink())
    }

    @Test fun disablingMotionFinishesDisclosureAndReenablingDoesNotReplay() {
        compose.mainClock.autoAdvance = false
        compose.setContent { DroideTheme { Box(Modifier.width(300.dp)) { AgentStreamPartItem(tool, false, Modifier.testTag("tool")) } } }
        val collapsed = height()
        compose.onNodeWithContentDescription("Expand").performClick(); compose.mainClock.advanceTimeBy(48)
        val entering = height()
        compose.runOnIdle { durationScale.value = 0f }; compose.mainClock.advanceTimeBy(32)
        val expanded = height()
        assertTrue(entering > collapsed && entering < expanded)
        compose.runOnIdle { durationScale.value = 1f }; compose.mainClock.advanceTimeBy(32)
        assertEquals(expanded, height(), 0f)
        compose.mainClock.advanceTimeBy(240)
        assertEquals(expanded, height(), 0f)
    }

    @Test fun rapidDisclosureReversalSettlesToTheLatestUserChoice() {
        compose.mainClock.autoAdvance = false
        compose.setContent { DroideTheme { Box(Modifier.width(300.dp)) { AgentStreamPartItem(tool, false, Modifier.testTag("tool")) } } }
        val collapsed = height()
        compose.onNodeWithContentDescription("Expand").performClick(); compose.mainClock.advanceTimeBy(48)
        compose.onNodeWithContentDescription("Collapse").performClick(); compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText("CODE").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand").performClick(); compose.mainClock.advanceTimeBy(240)
        assertTrue(height() > collapsed)
        compose.onAllNodesWithText("CODE").assertCountEquals(1)
        compose.onNodeWithContentDescription("Collapse").performClick(); compose.mainClock.advanceTimeBy(240)
        assertEquals(collapsed, height(), 0f)
        compose.onNodeWithText("CODE").assertDoesNotExist()
    }

    @Test fun cliDisclosureAnimatesWithoutDelayingSubsequentOutputOrHidingReply() {
        compose.mainClock.autoAdvance = false
        var details by mutableStateOf(false)
        var state by mutableStateOf(ExternalAgentConversationState(liveText = "Available reply", liveThought = "Public summary",
            activity = listOf("Reading source", "Inspecting tests")))
        compose.setContent { DroideTheme { Box(Modifier.width(300.dp)) {
            ExternalAgentLiveStream("CLI", state, "run", details, { details = !details }, modifier = Modifier.testTag("cli"))
        } } }
        val collapsed = height("cli")
        compose.onNodeWithContentDescription("Show stream details").performClick(); compose.mainClock.advanceTimeBy(48)
        val entering = height("cli")
        compose.onNodeWithText("Available reply").assertExists()
        compose.mainClock.advanceTimeBy(240)
        assertTrue(entering > collapsed && entering < height("cli"))
        compose.runOnIdle { state = state.copy(activity = state.activity + "New result\nAnother result") }
        compose.mainClock.advanceTimeBy(32)
        val updated = height("cli")
        compose.mainClock.advanceTimeBy(64)
        assertEquals(updated, height("cli"), 0f)
        compose.onNodeWithContentDescription("Hide stream details").performClick(); compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText("Public summary").assertDoesNotExist()
        compose.onNodeWithText("Available reply").assertExists()
        compose.mainClock.advanceTimeBy(240)
        assertEquals(collapsed, height("cli"), 0f)
    }

    @Test fun restoredDetailsAppearAtTheirFinalSizeWithoutAnEntrance() {
        compose.mainClock.autoAdvance = false
        val restoration = StateRestorationTester(compose)
        restoration.setContent { DroideTheme { Box(Modifier.width(300.dp)) {
            AgentStreamPartItem(tool, false, Modifier.testTag("tool"))
        } } }
        compose.onNodeWithContentDescription("Expand").performClick()
        compose.mainClock.advanceTimeBy(240)
        val before = height()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("CODE").assertExists()
        assertEquals(before, height(), 0f)
        compose.mainClock.advanceTimeBy(64)
        assertEquals(before, height(), 0f)
        compose.mainClock.advanceTimeBy(240)
        assertEquals(before, height(), 0f)
    }

    @Test fun slowSystemAnimationScaleKeepsTheDisclosureInFlightLonger() {
        durationScale.value = 2f
        compose.mainClock.autoAdvance = false
        compose.setContent { DroideTheme { Box(Modifier.width(300.dp)) {
            AgentStreamPartItem(tool, false, Modifier.testTag("tool"))
        } } }
        val collapsed = height()
        compose.onNodeWithContentDescription("Expand").performClick()
        compose.mainClock.advanceTimeBy(112)
        val entering = height()
        compose.mainClock.advanceTimeBy(512)
        assertTrue(entering > collapsed && entering < height())
        compose.onNodeWithContentDescription("Collapse").performClick()
        compose.mainClock.advanceTimeBy(112)
        assertTrue(height() > collapsed)
        compose.mainClock.advanceTimeBy(512)
        assertEquals(collapsed, height(), 0f)
    }
}
