package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
class AgentFileIdentityUiTest {
    @get:Rule val compose = createComposeRule()

    private fun assertBeside(description: String, label: String) {
        val icon = compose.onNodeWithContentDescription(description, useUnmergedTree = true)
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        val text = compose.onNodeWithText(label, useUnmergedTree = true)
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("Icon must precede the filename", icon.right < text.left)
        assertTrue("Icon and filename must share a row",
            abs((icon.top.value + icon.bottom.value) - (text.top.value + text.bottom.value)) < 2f)
    }

    @Test fun standaloneRenameAndDeletionUseActualFileTypes() {
        val rename = AgentFileChanges.preview("src/View.tsx", "old\n", "new\n", previousPath = "src/view.py")
        val deletion = AgentFileChanges.preview("src/retired.kt", "old\n", null)
        compose.setContent { DroideTheme { Column {
            DiffText(rename.diff)
            DiffText(deletion.diff)
        } } }
        assertBeside("react file", "src/view.py → src/View.tsx")
        assertBeside("kotlin file", "src/retired.kt")
    }

    @Test fun expandedChangeCardDoesNotRepeatItsFileHeader() {
        val change = AgentFileChanges.preview("src/main.py", "old\n", "new\n")
        compose.setContent { DroideTheme { AgentFileChangesPreview(listOf(change)) } }
        compose.onAllNodesWithText(change.path).assertCountEquals(1)
        compose.onAllNodesWithContentDescription("python file", useUnmergedTree = true).assertCountEquals(1)
        assertBeside("python file", change.path)
        compose.onNodeWithText("+new").assertIsDisplayed()
    }

    @Test fun movedChangeCardBrandsBothIdentitiesWithoutRepeatingTheDiffHeader() {
        val change = AgentFileChanges.preview("src/new.tsx", "old\n", "new\n", previousPath = "src/old.py")
        compose.setContent { DroideTheme { AgentFileChangesPreview(listOf(change)) } }
        assertBeside("react file", "src/new.tsx")
        assertBeside("python file", "Moved from src/old.py")
        compose.onNodeWithText("src/old.py → src/new.tsx").assertDoesNotExist()
    }

    @Test fun streamingWaitsForCompleteFilePathAndReplacesTheIconWhenItChanges() {
        var input by mutableStateOf("""{"file_path":"src/index.py""")
        compose.setContent { DroideTheme {
            AgentStreamPartItem(AgentStreamPart("write", 1, AgentStreamPartKind.TOOL,
                AgentStreamPartState.RUNNING, toolName = "write_file", input = input), false)
        } }
        compose.onNodeWithContentDescription("python file", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { input = """{"file_path":"src/index.py","content":"pending""" }
        assertBeside("python file", "src/index.py")
        compose.runOnIdle { input = """{"file_path":"src/index.tsx","content":"pending""" }
        assertBeside("react file", "src/index.tsx")
        compose.onNodeWithContentDescription("python file", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun externalAcpDiffSuppliesIdentityWhenToolInputIsAbsent() {
        val update = requireNotNull(ExternalAgentToolUpdate.fromAcp(Json.parseToJsonElement("""
            {"toolCallId":"change","kind":"edit","status":"in_progress","title":"Update file",
             "content":[{"type":"diff","path":"src/service.go","oldText":"old","newText":"new"}]}
        """).jsonObject))
        val part = update.applyTo(AgentStreamPart("external:change", 1, AgentStreamPartKind.TOOL,
            AgentStreamPartState.PENDING))
        compose.setContent { DroideTheme { AgentStreamPartItem(part, false) } }
        assertBeside("go file", "src/service.go")
    }

    @Test fun terminalWorkingDirectoryDoesNotBecomeAFileIconOrHideTheCommand() {
        compose.setContent { DroideTheme {
            AgentCompletedToolPart("run_command", """{"cwd":"/tmp/project.py","command":"npm test"}""", "Done")
        } }
        compose.onNodeWithText("npm test").assertIsDisplayed()
        compose.onNodeWithContentDescription("python file", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun approvalFileResourceUsesTheSameIconForNativeAndExternalWrites() {
        var request by mutableStateOf(ApprovalRequest(1, "write_file", "Write app.kt",
            AgentFileChanges.preview("app.kt", null, "new\n").diff, action = "edit", resource = "app.kt"))
        compose.setContent { DroideTheme { AgentApprovalContent(request, 600.dp) {} } }
        compose.onAllNodesWithContentDescription("kotlin file", useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithText("edit · app.kt").assertIsDisplayed()
        compose.onNodeWithText("Allow once").assertIsDisplayed()
        compose.runOnIdle {
            request = ApprovalRequest(2, "agent_plugin_tool", "Update app.tsx", "External file proposal",
                action = "write_file", resource = "app.tsx")
        }
        assertBeside("react file", "write_file · app.tsx")
        compose.onNodeWithText("Allow once").assertIsDisplayed()
        compose.runOnIdle {
            request = ApprovalRequest(3, "agent_plugin_tool", "Run tool", "Command proposal",
                action = "run_command", resource = "python script.py")
        }
        compose.onNodeWithContentDescription("python file", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun narrowDiffWithLargeTextKeepsIconAndWrappedNameWithinItsWidth() {
        val path = "src/very/long/directory/another/directory/ScreenComponent.tsx"
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.7f)) {
                DroideTheme { Box(Modifier.width(180.dp)) {
                    DiffText(AgentFileChanges.preview(path, null, "new\n").diff, wrapFileNames = true)
                } }
            }
        }
        val icon = compose.onNodeWithContentDescription("react file", useUnmergedTree = true)
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        val text = compose.onNodeWithText(path).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(icon.right < text.left)
        assertTrue(text.right <= 180.dp)
        assertTrue(text.bottom - text.top > 30.dp)
    }

    @Test fun shortIdentityWrapsItsContentInsteadOfPushingAdjacentActionsAway() {
        compose.setContent { DroideTheme { Row(Modifier.width(320.dp)) {
            FileIdentityLabel("App.kt", style = MaterialTheme.typography.labelSmall)
            Text("Action")
        } } }
        val name = compose.onNodeWithText("App.kt").getUnclippedBoundsInRoot()
        val action = compose.onNodeWithText("Action").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("Short label must not stretch across the row", name.right - name.left < 100.dp)
        assertTrue("Adjacent action must follow the name", action.left - name.right < 10.dp)
    }
}
