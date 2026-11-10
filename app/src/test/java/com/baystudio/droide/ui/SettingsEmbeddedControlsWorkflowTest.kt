package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.baystudio.droide.core.LocalLlamaModelCoordinator
import com.baystudio.droide.core.McpHealthPhase
import com.baystudio.droide.core.McpHealthSnapshot
import com.baystudio.droide.core.McpHealthState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** UI-only fixtures: no MCP process, device connection, identity reset or permission request. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w900dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsEmbeddedControlsWorkflowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun certifiedModelStatusHasNoButtonAffordanceOrClickAction() {
        val model = LocalLlamaModelCoordinator.Model("fixture", "Fixture model", 1024L, 3,
            LocalLlamaModelCoordinator.Availability.CERTIFIED)
        compose.setContent { DroideTheme {
            LocalModelRow(model, false) { error("Certified status is not an action") }
        } }
        compose.onAllNodes(hasText("Certified") and hasClickAction()).assertCountEquals(0)
        compose.onAllNodes(hasText("Certified") and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).assertCountEquals(0)
        compose.onAllNodesWithText("Certified")[0].assertExists()
    }

    @Test fun mcpServerDetailsExposeStateAndRealActions() {
        var retries = 0
        var configurations = 0
        var permissions = 0
        val item = McpHealthSnapshot("fixture", McpHealthState.CONFIGURED,
            McpHealthPhase.CONFIGURATION, "Configured")
        compose.setContent {
            DroideTheme { Column {
                McpSettingsSection(listOf(item), { retries++ }, { configurations++ }, { permissions++ })
            } }
        }
        val server = compose.onNodeWithText("fixture")
        server.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
            .performClick()
        server.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        compose.onNodeWithText("Retry check").performClick()
        compose.onNodeWithText("Open .droide/mcp.json").performClick()
        compose.onNodeWithText("Agent Permissions").performClick()
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(1, configurations)
            assertEquals(1, permissions)
        }
        server.performClick()
        compose.onNodeWithText("Retry check").assertDoesNotExist()
    }

    @Test fun pendingMcpActionsAreDisabledButDetailsRemainAvailable() {
        val item = McpHealthSnapshot("fixture", McpHealthState.CONFIGURED,
            McpHealthPhase.CONFIGURATION, "Configured")
        compose.setContent {
            DroideTheme { Column {
                McpSettingsSection(listOf(item), { error("Pending retry executed") },
                    { error("Pending config executed") }, {}, setOf("mcp:retry:fixture", "mcp:config"))
            } }
        }
        compose.onNodeWithText("fixture").performClick()
        compose.onNodeWithText("Retry check").assertIsNotEnabled()
        compose.onNodeWithText("Open .droide/mcp.json").assertIsNotEnabled()
        compose.onNodeWithText("Agent Permissions").assertIsEnabled()
    }
}
