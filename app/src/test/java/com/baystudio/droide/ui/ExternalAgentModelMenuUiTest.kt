package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ExternalAgentModelMenuUiTest {
    @get:Rule val compose = createComposeRule()
    private val spec = WorkspaceAgentPluginCatalog.entries.first()
    private val config = WorkspaceAgentSessionConfiguration(
        models = listOf(WorkspaceAgentModelOption("provider/first", "First model"), WorkspaceAgentModelOption("provider/second", "Second model")),
        currentModelId = "provider/first", modelConfigId = "model",
    )

    private fun mount(state: ExternalAgentConversationState, busy: Boolean = false,
                      pick: (String) -> Unit = {}, refresh: () -> Unit = {}) {
        compose.setContent { DroideTheme { Box(Modifier.size(320.dp, 500.dp)) {
            ExternalAgentModelMenu(true, state, busy, spec, pick, refresh, {})
        } } }
    }

    @Test fun cliReportedModelsCanBeSelectedAndRefreshed() {
        val picked = mutableListOf<String>(); var refreshes = 0
        mount(ExternalAgentConversationState(sessionId = "same_session", configuration = config), pick = picked::add, refresh = { refreshes++ })
        compose.onNodeWithText("OpenCode models").assertIsDisplayed()
        compose.onNodeWithText("Second model").performClick()
        compose.onNodeWithText("Refresh models").performClick()
        assertEquals(listOf("provider/second"), picked)
        assertEquals(1, refreshes)
    }

    @Test fun choicesAndRefreshAreDisabledWhileTheSameSessionIsWorking() {
        mount(ExternalAgentConversationState(configuration = config), busy = true)
        compose.onNodeWithText("Second model").assertIsNotEnabled()
        compose.onNodeWithText("Loading…").assertIsNotEnabled()
    }

    @Test fun failedLoginShowsActionableCliHintInsteadOfInventingModelOptions() {
        mount(ExternalAgentConversationState(connectionError = "Authentication required"))
        compose.onNodeWithText("Authentication required").assertIsDisplayed()
        compose.onNodeWithText("opencode auth login").assertIsDisplayed()
        compose.onNodeWithText("First model").assertDoesNotExist()
        compose.onNodeWithText("Refresh models").assertIsEnabled()
    }

    @Test fun agentWithNoModelMetadataExplainsItsConfiguredDefault() {
        mount(ExternalAgentConversationState(configuration = WorkspaceAgentSessionConfiguration()))
        compose.onNodeWithText("This Agent has not reported model choices. Its configured default is used.").assertExists()
    }
}
