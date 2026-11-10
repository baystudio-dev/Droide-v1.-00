package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.WorkspaceAgentEvent
import com.baystudio.droide.core.WorkspaceAgentModelOption
import com.baystudio.droide.core.WorkspaceAgentSessionConfiguration
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ExternalAgentControlsUiTest {
    @get:Rule val compose = createComposeRule()
    private val configuration = WorkspaceAgentSessionConfiguration(
        reasoningOptions = listOf(WorkspaceAgentModelOption("low", "Low"), WorkspaceAgentModelOption("ultra", "Ultra")),
        currentReasoningId = "low", reasoningConfigId = "reasoning_effort")

    @Test fun reasoningMenuUsesReportedChoicesAndForwardsTheirExactWireValue() {
        val selected = mutableListOf<String>()
        compose.setContent { DroideTheme { Box(Modifier.size(320.dp, 500.dp)) {
            ExternalAgentReasoningMenu(true, ExternalAgentConversationState(configuration = configuration), false,
                selected::add, {}, {})
        } } }
        compose.onNodeWithText("CLI Reasoning").assertIsDisplayed()
        compose.onNodeWithText("Ultra").performClick()
        assertEquals(listOf("ultra"), selected)
    }

    @Test fun reasoningCannotBeChangedDuringAnActiveTurn() {
        compose.setContent { DroideTheme { Box(Modifier.size(320.dp, 500.dp)) {
            ExternalAgentReasoningMenu(true, ExternalAgentConversationState(configuration = configuration), true, {}, {}, {})
        } } }
        compose.onNodeWithText("Ultra").assertIsNotEnabled()
        compose.onNodeWithText("Loading…").assertIsNotEnabled()
    }

    @Test fun aCliWithoutThoughtLevelChoicesExplainsItsDefault() {
        compose.setContent { DroideTheme { Box(Modifier.size(320.dp, 500.dp)) {
            ExternalAgentReasoningMenu(true, ExternalAgentConversationState(configuration = WorkspaceAgentSessionConfiguration()), false, {}, {}, {})
        } } }
        compose.onNodeWithText("This CLI has not reported Reasoning choices. Its configured default is used.").assertExists()
        compose.onNodeWithText("Ultra").assertDoesNotExist()
    }

    @Test fun liveChatRendersIncrementalTextAndCliThoughtBeforeAnyFinalMessage() {
        val thought = WorkspaceAgentEvent.Thought("Inspecting source")
        val state = mutableStateOf(ExternalAgentConversationState(
            liveThought = thought.text, progress = ExternalAgentActivity().accept(thought)))
        val expanded = mutableStateOf(false)
        compose.setContent { DroideTheme { Box(Modifier.size(360.dp, 620.dp)) {
            ExternalAgentLiveStream("Codex", state.value, "controls-fixture-run",
                showDetails = expanded.value, onToggleDetails = { expanded.value = !expanded.value })
        } } }
        compose.onNodeWithText("Thinking…").assertIsDisplayed()
        compose.onNodeWithText("Inspecting source").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show stream details").performClick()
        compose.onNodeWithText("Inspecting source").assertIsDisplayed()
        compose.onNodeWithText("Reasoning summary").assertIsDisplayed()
        compose.runOnIdle {
            val reply = WorkspaceAgentEvent.Text("First chunk")
            state.value = state.value.copy(liveText = reply.text, progress = state.value.progress.accept(reply))
        }
        compose.onNodeWithText("First chunk", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Responding…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Hide stream details").performClick()
        compose.onNodeWithText("Inspecting source").assertDoesNotExist()
        compose.runOnIdle {
            val reply = WorkspaceAgentEvent.Text(" and next chunk")
            state.value = state.value.copy(liveText = state.value.liveText + reply.text,
                progress = state.value.progress.accept(reply))
            assertEquals(emptyList<Any>(), state.value.messages)
        }
        compose.onNodeWithText("First chunk and next chunk", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Responding…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Show stream details").performClick()
        compose.onNodeWithText("Inspecting source").assertIsDisplayed()
        compose.onNodeWithText("First chunk and next chunk", substring = true).assertIsDisplayed()
    }
}
