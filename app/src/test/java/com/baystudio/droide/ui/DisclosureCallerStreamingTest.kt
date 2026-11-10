package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DisclosureCallerStreamingTest {
    private val scale = mutableStateOf(1f)
    @get:Rule val compose = createComposeRule(effectContext = object : MotionDurationScale {
        override val scaleFactor get() = scale.value
    })
    private fun height() = compose.onNodeWithTag("surface").fetchSemanticsNode().boundsInRoot.height
    private fun frames(count: Int): List<Float> = List(count) { compose.mainClock.advanceTimeByFrame(); height() }
    private fun assertStreamingSnaps(reduced: Boolean = false, change: () -> Unit) {
        compose.runOnIdle(change)
        val observed = frames(4)
        val settled = frames(16).last()
        println("CALLER_STREAM heights=$observed final=$settled")
        assertEquals("Streaming must settle without following a new tween: $observed -> $settled", settled, observed[if (reduced) 2 else 1], .01f)
        if (reduced) assertTrue("Reduced motion must have no intermediate tween sizes", observed.distinct().size <= 2)
    }
    private fun tool() = AgentStreamPart("tool:1:0", 1, AgentStreamPartKind.TOOL, AgentStreamPartState.COMPLETED,
        title="command", toolName="run_command", output="First line", detail="Succeeded", startedAtMs=0L, endedAtMs=0L)
    private fun toolAt(frameCount: Int, reduced: Boolean = false) {
        compose.mainClock.autoAdvance = false
        if (reduced) scale.value = 0f
        var part by mutableStateOf(tool())
        compose.setContent { DroideTheme { Box(Modifier.width(320.dp)) {
            AgentStreamPartItem(part, false, Modifier.testTag("surface"))
        } } }
        height()
        compose.onNodeWithContentDescription("Expand").performClick()
        frames(frameCount)
        assertStreamingSnaps(reduced) { part = part.copy(output="First line\nSecond line\nThird line\nFourth line\nFifth line") }
        compose.onNodeWithContentDescription("Collapse").performClick()
        frames(1)
        compose.onNodeWithText("OUTPUT").assertDoesNotExist()
    }
    @Test fun toolOutputAt32ms() = toolAt(2)
    @Test fun toolOutputAt96ms() = toolAt(6)
    @Test fun toolOutputAt160ms() = toolAt(10)
    @Test fun toolReducedMotion() = toolAt(2, true)

    @Test fun stepStatisticsDuringExpansion() {
        compose.mainClock.autoAdvance=false
        var part by mutableStateOf(AgentStreamPart("step:1",1,AgentStreamPartKind.STEP,AgentStreamPartState.COMPLETED,
            title="Step 1",startedAtMs=0L,endedAtMs=0L))
        compose.setContent { DroideTheme { Box(Modifier.width(320.dp).testTag("surface")) { AgentStreamPartItem(part,true) } } }
        height()
        compose.onNodeWithText("Step 1").performClick(); frames(6)
        assertStreamingSnaps { part=part.copy(finishReason="A new completion detail\nwith additional lines\nand another line") }
    }
    @Test fun runHeaderDuringExpansion() {
        compose.mainClock.autoAdvance=false
        var details by mutableStateOf(false)
        var state by mutableStateOf(AgentStreamState(modelId="Model",status=AgentStreamState.Status.IDLE))
        compose.setContent { DroideTheme { Box(Modifier.width(320.dp)) {
            AgentStreamRunHeader(state,0,details,{ details=!details },modifier=Modifier.testTag("surface"))
        } } }
        height()
        compose.onNodeWithContentDescription("Show stream details").performClick(); frames(6)
        assertStreamingSnaps { state=state.copy(transport=AgentStreamTransport.NATIVE, parts=listOf(tool())) }
    }
    @Test fun externalThoughtDuringExpansion() {
        compose.mainClock.autoAdvance=false
        var details by mutableStateOf(false)
        var state by mutableStateOf(ExternalAgentConversationState())
        compose.setContent { DroideTheme { Box(Modifier.width(320.dp)) {
            ExternalAgentLiveStream("External",state,"stream",details,{ details=!details },modifier=Modifier.testTag("surface"),renderTranscript=false)
        } } }
        height()
        compose.onNodeWithContentDescription("Show stream details").performClick(); frames(6)
        assertStreamingSnaps { state=state.copy(liveThought="First thought\nSecond thought\nThird thought") }
    }
    @Test fun equalCopiedStreamDataDoesNotCancelDisclosure() {
        compose.mainClock.autoAdvance=false
        var part by mutableStateOf(tool(), referentialEqualityPolicy())
        compose.setContent { DroideTheme { Box(Modifier.width(320.dp)) { AgentStreamPartItem(part,false,Modifier.testTag("surface")) } } }
        val collapsed=height()
        compose.onNodeWithContentDescription("Expand").performClick();frames(3)
        compose.runOnIdle { part=part.copy() }
        val during=frames(2).last()
        val final=frames(16).last()
        assertTrue("Equal structural copy must retain transition ($collapsed, $during, $final)",during>collapsed && during<final)
    }
    @Test fun actualToolRapidReversalKeepsLatestTargetAndRemovesHiddenActions() {
        compose.mainClock.autoAdvance=false
        compose.setContent { DroideTheme { Box(Modifier.width(320.dp)) { AgentStreamPartItem(tool(),false,Modifier.testTag("surface")) } } }
        val collapsed=height()
        compose.onNodeWithContentDescription("Expand").performClick();frames(3)
        compose.onNodeWithContentDescription("Collapse").performClick();frames(1)
        compose.onNodeWithText("OUTPUT").assertDoesNotExist()
        frames(2)
        compose.onNodeWithContentDescription("Expand").performClick()
        val expanding=frames(3).last()
        val expanded=frames(16).last()
        assertTrue(expanding<expanded)
        compose.onAllNodesWithText("OUTPUT").assertCountEquals(1)
        compose.onNodeWithContentDescription("Collapse").performClick();frames(1)
        compose.onNodeWithText("OUTPUT").assertDoesNotExist()
        assertEquals(collapsed,frames(16).last(),.01f)
    }
    @Test fun disablingMotionDuringActualToolDisclosureReachesFinalGeometry() {
        compose.mainClock.autoAdvance=false
        compose.setContent { DroideTheme { Box(Modifier.width(320.dp)) { AgentStreamPartItem(tool(),false,Modifier.testTag("surface")) } } }
        height()
        compose.onNodeWithContentDescription("Expand").performClick();frames(4)
        compose.runOnIdle { scale.value=0f }
        val snapped=frames(3).last()
        val settled=frames(16).last()
        assertEquals(settled,snapped,.01f)
        compose.onNodeWithText("First line").assertExists()
    }

    private fun assertUnrelatedUpdateKeepsTween(change: () -> Unit) {
        val before=height()
        compose.runOnIdle(change)
        val after=frames(2).last()
        val settled=frames(16).last()
        assertTrue("Unrendered data must preserve motion: $before -> $after -> $settled",after>before && after<settled)
    }
    @Test fun headerIgnoresUnrenderedTranscriptAndMetadata() {
        compose.mainClock.autoAdvance=false
        var details by mutableStateOf(false)
        var total by mutableIntStateOf(0)
        var state by mutableStateOf(AgentStreamState(modelId="Model"))
        compose.setContent { DroideTheme { Box(Modifier.width(320.dp)) {
            AgentStreamRunHeader(state,total,details,{details=!details},modifier=Modifier.testTag("surface"))
        } } }
        height();compose.onNodeWithContentDescription("Show stream details").performClick();frames(3)
        assertUnrelatedUpdateKeepsTween {
            total=100
            state=state.copy(sessionId="other",baseMessageCount=100,parts=listOf(AgentStreamPart("text",1,AgentStreamPartKind.TEXT,AgentStreamPartState.RUNNING,text="Unrendered text")))
        }
    }
    private fun externalUnrelated(renderTranscript: Boolean) {
        compose.mainClock.autoAdvance=false
        var details by mutableStateOf(false)
        var state by mutableStateOf(ExternalAgentConversationState(streamParts=if(renderTranscript) listOf(tool()) else emptyList(),liveThought="One thought\nSecond thought"))
        compose.setContent { DroideTheme { Box(Modifier.width(320.dp)) {
            ExternalAgentLiveStream("External",state,"stream",details,{details=!details},modifier=Modifier.testTag("surface"),renderTranscript=renderTranscript)
        } } }
        height();compose.onNodeWithContentDescription("Show stream details").performClick();frames(3)
        assertUnrelatedUpdateKeepsTween {
            state=state.copy(liveText="Unused fallback text",streamParts=if(renderTranscript) state.streamParts else listOf(tool()))
        }
    }
    @Test fun externalIgnoresHiddenTranscript() = externalUnrelated(false)
    @Test fun externalIgnoresUnusedFallbackText() = externalUnrelated(true)
    @Test fun toolIgnoresUnrenderedTokenAndPublicTextData() {
        compose.mainClock.autoAdvance=false
        var part by mutableStateOf(tool())
        compose.setContent { DroideTheme { Box(Modifier.width(320.dp)) { AgentStreamPartItem(part,false,Modifier.testTag("surface")) } } }
        height();compose.onNodeWithContentDescription("Expand").performClick();frames(3)
        assertUnrelatedUpdateKeepsTween {part=part.copy(totalTokens=500,text="Unused",presentationId="new")}
    }
    @Test fun stepIgnoresUnrenderedToolOutputAndPublicText() {
        compose.mainClock.autoAdvance=false
        var part by mutableStateOf(AgentStreamPart("step:1",1,AgentStreamPartKind.STEP,AgentStreamPartState.COMPLETED,title="Step 1",startedAtMs=0L,endedAtMs=0L))
        compose.setContent { DroideTheme { Box(Modifier.width(320.dp).testTag("surface")) { AgentStreamPartItem(part,true) } } }
        height();compose.onNodeWithText("Step 1").performClick();frames(3)
        assertUnrelatedUpdateKeepsTween {part=part.copy(output="Unused",text="Unused",presentationId="new")}
    }

}
