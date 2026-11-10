package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AgentActivityLifecycleUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nativeDetailsToggleRetainsTheStepAndToolData() {
        val part = AgentStreamPart("step:7", 7, AgentStreamPartKind.STEP, AgentStreamPartState.COMPLETED, title = "Step 7", detail = "Tools complete")
        var details by mutableStateOf(false)
        val state = AgentStreamState(status = AgentStreamState.Status.BUSY, activeStep = 7, parts = listOf(part), modelActivity = AgentModelActivity.THINKING)
        compose.setContent { DroideTheme { Column(Modifier.width(280.dp)) {
            AgentStreamRunHeader(state, 0, details, { details = !details })
            visibleAgentStreamParts(state.parts, details).forEach { AgentStreamPartItem(it, details) }
        } } }
        compose.onNodeWithText("Thinking…").assertIsDisplayed()
        compose.onAllNodesWithText("Step 7").assertCountEquals(0)
        compose.onNodeWithContentDescription("Show stream details").performClick()
        compose.onNodeWithText("Tools complete").assertExists()
        compose.onAllNodesWithText("Step 7").assertCountEquals(2)
        compose.onNodeWithContentDescription("Hide stream details").performClick()
        compose.onAllNodesWithText("Step 7").assertCountEquals(0)
        assertEquals("Tools complete", state.parts.single().detail)
    }

    @Test fun waitingAndTerminalStatusesReplaceThinkingAndStopSpinning() {
        var state by mutableStateOf(AgentStreamState(status = AgentStreamState.Status.BUSY, modelActivity = AgentModelActivity.THINKING))
        var approval by mutableStateOf(false)
        compose.setContent { DroideTheme { AgentStreamRunHeader(state, 0, false, {}, waitingForApproval = approval) } }
        compose.onNodeWithText("Thinking…").assertExists()
        compose.runOnIdle { approval = true }
        compose.onNodeWithText("Waiting for approval").assertExists()
        compose.onNodeWithText("Thinking…").assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        compose.runOnIdle { state = state.copy(status = AgentStreamState.Status.CANCELLED) }
        compose.onNodeWithText("Stopped").assertExists()
        compose.runOnIdle { state = state.copy(status = AgentStreamState.Status.ERROR) }
        compose.onNodeWithText("Failed").assertExists()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
    }

    @Test fun externalPhaseCanChangeAfterCommentaryWithoutHidingReplyAndFailure() {
        var details by mutableStateOf(false)
        var state by mutableStateOf(ExternalAgentConversationState(liveText = "Earlier public commentary",
            liveThought = "Available public summary", progress = ExternalAgentActivity(phase = AgentActivityKind.THINKING)))
        compose.setContent { DroideTheme { Box(Modifier.width(280.dp)) {
            ExternalAgentLiveStream("CLI", state, "run", details, { details = !details })
        } } }
        compose.onNodeWithText("Thinking…").assertExists()
        compose.onNodeWithText("Earlier public commentary").assertExists()
        compose.onNodeWithText("Available public summary").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show stream details").performClick()
        compose.onNodeWithText("Available public summary").assertExists()
        compose.onNodeWithContentDescription("Hide stream details").performClick()
        val transcript = ExternalAgentTranscript()
        transcript.accept(WorkspaceAgentEvent.Text("Earlier public commentary"))
        val event = WorkspaceAgentEvent.Tool.fromAcp(Json.parseToJsonElement("""{"sessionUpdate":"tool_call","toolCallId":"build","title":"Build","kind":"execute","status":"failed","rawOutput":"Compiler exited with code 1"}""").jsonObject)
        assertEquals("failed", event.update!!.status)
        compose.runOnIdle {
            transcript.accept(event)
            state = state.copy(progress = state.progress.accept(event), streamParts = transcript.liveParts)
        }
        assertEquals(AgentStreamPartState.ERROR, state.streamParts.single { it.kind == AgentStreamPartKind.TOOL }.state)
        compose.onNodeWithText("Earlier public commentary").assertExists()
        compose.onNodeWithText("Failed · Build").assertExists()
        compose.onNodeWithText("Tool failed: Build").assertDoesNotExist()
        compose.onNodeWithText("Failed · Build").performClick()
        compose.onNodeWithText("Compiler exited with code 1", substring = true).assertExists()
        compose.runOnIdle { state = state.copy(progress = state.progress.finish(AgentActivityKind.STOPPED)) }
        compose.onNodeWithText("Stopped").assertExists()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
    }

    @Test fun interruptedStepNoticeStaysVisibleWithoutInternalCounter() {
        val failed = AgentStreamPart("step:9", 9, AgentStreamPartKind.STEP, AgentStreamPartState.ERROR,
            title = "Step 9", detail = "Provider disconnected")
        compose.setContent { DroideTheme { AgentStreamPartItem(failed, false) } }
        compose.onNodeWithText("Response failed").assertExists()
        compose.onNodeWithText("Provider disconnected").assertExists()
        compose.onNodeWithText("Step 9").assertDoesNotExist()
    }

    @Test fun actualPartialAcpFailureRetainsTitleAndOutputInTranscriptCard() {
        val transcript = ExternalAgentTranscript()
        var state by mutableStateOf(ExternalAgentConversationState())
        fun accept(raw: String) {
            val event = WorkspaceAgentEvent.Tool.fromAcp(Json.parseToJsonElement(raw).jsonObject)
            transcript.accept(event)
            state = state.copy(progress = state.progress.accept(event), streamParts = transcript.liveParts)
        }
        accept("""{"sessionUpdate":"tool_call","toolCallId":"build","title":"Build","kind":"execute","status":"in_progress"}""")
        compose.setContent { DroideTheme { ExternalAgentLiveStream("CLI", state, "run") } }
        compose.runOnIdle {
            accept("""{"sessionUpdate":"tool_call_update","toolCallId":"build","status":"failed","content":[{"type":"content","content":{"type":"text","text":"Gradle compilation failed"}}]}""")
        }
        assertEquals(1, state.streamParts.size)
        assertEquals("Build", state.streamParts.single().title)
        assertEquals(AgentStreamPartState.ERROR, state.streamParts.single().state)
        assertEquals("Gradle compilation failed", state.streamParts.single().output)
        compose.onNodeWithText("Failed · Build").assertExists().performClick()
        compose.onNodeWithText("Gradle compilation failed", substring = true).assertExists()
    }
}
