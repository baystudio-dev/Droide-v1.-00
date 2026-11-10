package com.baystudio.droide.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.AgentStreamPart
import com.baystudio.droide.core.AgentStreamPartKind
import com.baystudio.droide.core.AgentStreamPartState
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
class AgentActivityMotionWorkflowTest {
    private val durationScale = mutableStateOf(1f)
    @get:Rule val compose = createComposeRule(effectContext = object : MotionDurationScale {
        override val scaleFactor: Float get() = durationScale.value
    })

    private fun cornerBrightness(view: View): Float {
        val bounds = compose.onNodeWithTag("pixels").fetchSemanticsNode().boundsInRoot
        return compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                android.graphics.Color.red(bitmap.getPixel(bounds.right.toInt() - 2, bounds.bottom.toInt() - 2)) / 255f
            } finally { bitmap.recycle() }
        }
    }
    private fun tool(state: AgentStreamPartState = AgentStreamPartState.COMPLETED) = AgentStreamPart(
        id = "tool:1:0", step = 1, kind = AgentStreamPartKind.TOOL, state = state,
        title = "run command", toolName = "run_command", input = "{\"command\":\"pwd\"}",
        output = "Result body", detail = if (state == AgentStreamPartState.COMPLETED) "Succeeded" else "Running",
    )

    @Test fun entranceNeverChangesMeasuredBoundsOrBlocksContent() {
        compose.mainClock.autoAdvance = false
        val policy = AgentActivityEntrancePolicy(emptyList())
        compose.setContent { Box(Modifier.size(80.dp).testTag("parent")) {
            Box(Modifier.size(80.dp).agentActivityEntrance("tool:1:0", policy, true).testTag("tile")) { Text("Live") }
        } }
        val bounds = compose.onNodeWithTag("parent").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("Live").assertIsDisplayed()
        for (advance in listOf(0L, 48L, 200L)) {
            compose.mainClock.advanceTimeBy(advance)
            assertEquals(bounds, compose.onNodeWithTag("tile").fetchSemanticsNode().boundsInRoot)
        }
        assertEquals(1, policy.rememberedCount)
    }

    @Test fun settledEntranceDoesNotReplayForNewOutputOrCompositionRebind() {
        compose.mainClock.autoAdvance = false
        val policy = AgentActivityEntrancePolicy(emptyList())
        var visible by mutableStateOf(true)
        var text by mutableStateOf("First")
        lateinit var view: View
        compose.setContent { view = LocalView.current; Box(Modifier.size(80.dp).background(Color.Black).testTag("pixels")) {
            if (visible) Box(Modifier.size(80.dp).agentActivityEntrance("tool:1:0", policy, true)
                .background(Color.White)) { Text(text, color = Color.Black) }
        } }
        fun corner() = cornerBrightness(view)
        compose.mainClock.advanceTimeByFrame()
        val start = corner()
        assertTrue("Admitted entrance starts readable but unsettled", start >= .70f && start < .99f)
        compose.mainClock.advanceTimeBy(48)
        assertTrue("Entrance opacity progresses", corner() > start)
        compose.mainClock.advanceTimeBy(250)
        assertEquals(1f, corner(), .02f)
        compose.runOnIdle { text += " next chunk" }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1f, corner(), .02f)
        compose.runOnIdle { visible = false }; compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { visible = true }; compose.mainClock.advanceTimeByFrame()
        assertEquals(1f, corner(), .02f)
        assertEquals(1, policy.rememberedCount)
    }

    @Test fun reducedMotionSnapsImmediatelyAndDoesNotReplayWhenReenabled() {
        compose.mainClock.autoAdvance = false
        val policy = AgentActivityEntrancePolicy(emptyList())
        lateinit var view: View
        compose.setContent { view = LocalView.current; Box(Modifier.size(40.dp).background(Color.Black).testTag("pixels")) {
            Box(Modifier.size(40.dp).agentActivityEntrance("tool:1:0", policy, true).background(Color.White))
        } }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { durationScale.value = 0f }
        compose.mainClock.advanceTimeByFrame()
        fun brightness() = cornerBrightness(view)
        assertEquals(1f, brightness(), .02f)
        compose.runOnIdle { durationScale.value = 1f }; compose.mainClock.advanceTimeByFrame()
        assertEquals(1f, brightness(), .02f)
    }

    @Test fun statusTextIsCurrentAndOutgoingSpinnerHasNoProgressSemantics() {
        compose.mainClock.autoAdvance = false
        val part = mutableStateOf(tool(AgentStreamPartState.RUNNING))
        compose.setContent { DroideTheme { AgentStreamPartItem(part.value, showDetails = false) } }
        compose.onNodeWithText("Running · Terminal").assertExists()
        compose.runOnIdle { part.value = tool() }; compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Succeeded · Terminal").assertExists()
        compose.onNodeWithText("Running · Terminal").assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        compose.runOnIdle { part.value = tool(AgentStreamPartState.ERROR).copy(detail = "Failed") }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Failed · Terminal").assertExists()
    }

    @Test fun disclosureAnimatesOnlyUserChangesAndRemovesHiddenSemanticsImmediately() {
        compose.mainClock.autoAdvance = false
        var part by mutableStateOf(tool())
        compose.setContent { DroideTheme { AgentStreamPartItem(part, false, Modifier.testTag("tool")) } }
        val collapsed = compose.onNodeWithTag("tool").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithContentDescription("Expand").performClick(); compose.mainClock.advanceTimeBy(48)
        val entering = compose.onNodeWithTag("tool").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithText("Result body").assertExists()
        compose.mainClock.advanceTimeBy(240)
        val expanded = compose.onNodeWithTag("tool").fetchSemanticsNode().boundsInRoot.height
        assertTrue(entering > collapsed && entering < expanded)
        compose.runOnIdle { part = part.copy(output = "Result body\nNew output\nMore output") }
        val outputHeights = mutableListOf(expanded)
        repeat(8) {
            compose.mainClock.advanceTimeByFrame()
            outputHeights += compose.onNodeWithTag("tool").fetchSemanticsNode().boundsInRoot.height
        }
        compose.onNodeWithText("Result body\nNew output\nMore output").assertExists()
        assertTrue(outputHeights.last() > expanded)
        assertEquals("Streaming output must snap between sizes, without a tween: $outputHeights", 2, outputHeights.distinct().size)
        compose.onNodeWithContentDescription("Collapse").performClick(); compose.mainClock.advanceTimeBy(48)
        compose.onNodeWithText("OUTPUT").assertDoesNotExist()
        assertTrue(compose.onNodeWithTag("tool").fetchSemanticsNode().boundsInRoot.height > collapsed)
        compose.mainClock.advanceTimeBy(240)
        assertEquals(collapsed, compose.onNodeWithTag("tool").fetchSemanticsNode().boundsInRoot.height, 0f)
        compose.onNodeWithContentDescription("Expand").performClick(); compose.mainClock.advanceTimeBy(250)
        compose.onAllNodesWithText("OUTPUT").assertCountEquals(1)
    }

    @Test fun expandedDetailsSurviveLazyDisposalWithinTheSameStableItem() {
        compose.setContent { DroideTheme { LazyColumn(Modifier.width(360.dp).height(220.dp).testTag("list")) {
            item(key = agentActivityItemKey(9, "tool:1:0")) { AgentStreamPartItem(tool(), false) }
            items(30) { Text("History $it", Modifier.height(60.dp)) }
        } } }
        compose.onNodeWithContentDescription("Expand").performClick()
        compose.onNodeWithTag("list").performScrollToIndex(20)
        compose.onNodeWithTag("list").performScrollToIndex(0)
        compose.onNodeWithContentDescription("Collapse").assertExists()
        compose.onNodeWithText("Result body").assertExists()
    }
}
