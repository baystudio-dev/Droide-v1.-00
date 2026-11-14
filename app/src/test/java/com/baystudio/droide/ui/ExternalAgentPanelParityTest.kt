package com.baystudio.droide.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.baystudio.droide.DroideApplication
import com.baystudio.droide.core.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = DroideApplication::class, qualifiers = "w360dp-h640dp",
    instrumentedPackages = ["androidx.compose.foundation.lazy.layout"])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExternalAgentPanelParityTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as DroideApplication
    private val root = Files.createTempDirectory("external-panel-").toFile()
    private val ui by lazy { AgentWorkspaceUiState(app.workspaceHost.runtimeScope, app) }
    private val agent by lazy { AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root),
        maxSubagentDepth = 0, appContext = app, runtimeScope = app.workspaceHost.runtimeScope) }
    private var opened: String? = null
    private var exported: String? = null
    private var height by mutableStateOf(640.dp)

    @Suppress("UNCHECKED_CAST")
    private val states get() = ExternalAgentSessionController::class.java.getDeclaredField("states")
        .apply { isAccessible = true }.get(ui.externalController) as MutableMap<String, ExternalAgentConversationState>
    @Suppress("UNCHECKED_CAST")
    private val activeRun get() = ExternalAgentSessionController::class.java.getDeclaredField("activeRun\$delegate")
        .apply { isAccessible = true }.get(ui.externalController) as MutableState<ExternalAgentActiveRun?>

    @Before fun openSession() { ApplicationWorkAdmission.initialize(app); ApplicationWorkAdmission.reopenFromUser() }
    @After fun cleanup() { agent.shutdown(); ui.close(); app.workspaceHost.close(); PathSecurity.deleteTreeNoFollow(root) }

    private fun mount() {
        compose.setContent { DroideTheme { Box(Modifier.width(360.dp).height(height)) {
            AgentPane(agent, ui, app.workspaceHost.coordinator.providerConnections, app.workspaceHost.coordinator.localLlamaAgentModels,
                onOpenResult = { opened = it }, onExportResult = { exported = it })
        } } }
        compose.waitForIdle()
    }

    private fun command(command: AgentComposerCommand) {
        compose.onNode(hasSetTextAction()).performTextReplacement(command.token)
        compose.onNodeWithContentDescription(command.description).performClick()
        compose.waitForIdle()
    }

    private fun state(id: String, messages: List<AgentMessage>): ExternalAgentConversationState {
        val summary = PluginHistorySummary("history-$id", id, "Work $id", 1, 2, "native-shared", WorkspaceAgentMode.EDIT,
            null, messages.size, messages.size.toLong(), PluginHistoryStatus.COMPLETED)
        val window = PluginHistoryWindow(summary, messages, messages.indices.map(Int::toLong), 0, messages.size.toLong())
        return ExternalAgentConversationState(sessionId = summary.nativeSessionId, historyId = summary.id,
            messages = messages, historyRestored = true, historyWindow = window)
    }

    @Test fun everyExternalBackendLeavesResumeAndResultsToTheCliAndKeepsExplicitTemplates() {
        mount()
        for (spec in WorkspaceAgentPluginCatalog.entries) {
            val messages = listOf(AgentMessage("user", "Review project"), AgentMessage("assistant", "Review complete"))
            compose.runOnIdle { states[spec.id] = state(spec.id, messages); ui.backendId.value = spec.id; opened = null; exported = null }
            compose.onNode(hasSetTextAction()).performTextReplacement("/res")
            compose.onNodeWithText("/resume").assertDoesNotExist()
            compose.onNodeWithText("/results").assertDoesNotExist()
            for (token in listOf("/resume", "/results", "/compact")) {
                compose.onNode(hasSetTextAction()).performTextReplacement(token)
                compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
                assertEquals(token, ui.input.value)
                compose.onNodeWithText("Session results").assertDoesNotExist()
                compose.onNodeWithText("Prepare continuation").assertDoesNotExist()
            }
            command(AgentComposerCommand.WORKFLOWS)
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Use template").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText("Use template").onFirst().performClick()
            assertEquals("@skill/workflow-review ", ui.input.value)
            assertEquals(messages, ui.externalController[spec.id].messages)
            assertFalse(ui.externalController.busy); assertTrue(agent.messages.value.isEmpty())
            assertNull(opened); assertNull(exported)
        }
    }

    private fun plan(id: String, tasks: List<Todo>) = AgentMessage("assistant", "", AgentStreamPart(id, 0,
        AgentStreamPartKind.TOOL, AgentStreamPartState.COMPLETED, toolName = "plan", tasks = tasks))

    @Test fun taskDockUsesExternalPlansAndScopesCollapseToTheConversation() {
        agent.todos.set(listOf(Todo("Native task must stay native", "pending", "medium")))
        states["codex"] = state("codex", listOf(plan("p1", listOf(Todo("External repair", "in_progress", "medium")))))
        states["kimi"] = state("kimi", listOf(plan("p2", listOf(Todo("External review", "pending", "medium")))))
        ui.backendId.value = "codex"; mount()
        compose.onNodeWithText("Native task must stay native").assertDoesNotExist()
        compose.onNodeWithText("External repair").assertIsDisplayed()
        compose.onNodeWithContentDescription("Collapse tasks").performClick()
        compose.onNodeWithText("External repair").assertDoesNotExist()
        compose.runOnIdle { ui.backendId.value = "kimi" }
        compose.onNodeWithText("External review").assertIsDisplayed()
        compose.onNodeWithContentDescription("Collapse tasks").assertExists()
        compose.runOnIdle {
            states["kimi"] = state("kimi", states.getValue("kimi").messages + plan("cleared", emptyList()))
        }
        compose.onNodeWithText("Tasks").assertDoesNotExist()
    }

    @Test fun streamedAndStoredExternalRepliesUseTheSameResponseAndCopyControls() {
        val answer = "Verified external response 🚀"
        states["codex"] = state("codex", listOf(AgentMessage("user", "Check project"))).copy(
            liveText = answer, streamParts = listOf(AgentStreamPart("reply", 0, AgentStreamPartKind.TEXT,
                AgentStreamPartState.RUNNING, text = answer, textPresentation = AgentTextPresentation.RESPONSE)))
        ui.backendId.value = "codex"; activeRun.value = ExternalAgentActiveRun(1, "codex", "native-shared"); mount()
        compose.onNodeWithText("Response").assertExists()
        compose.onNodeWithText(answer).assertExists()
        compose.runOnIdle {
            states["codex"] = state("codex", listOf(AgentMessage("user", "Check project"), AgentMessage("assistant", answer)))
            activeRun.value = null
        }
        compose.onNodeWithText("Response").assertExists()
        compose.onNodeWithText("DROIDE").assertDoesNotExist()
        compose.onNodeWithContentDescription("Copy response").performClick()
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(answer, clipboard.primaryClip!!.getItemAt(0).text.toString())
        compose.onNodeWithText("Copied").assertExists()
    }

    @Test fun switchingToExternalHistoryDismissesNativeResultsAndReevaluatesDraftCommands() {
        val saved = state("codex", listOf(AgentMessage("user", "Repair project")))
        states["codex"] = saved.copy(historyWindow = saved.historyWindow!!.copy(readOnlyReason = "Review interrupted history"))
        mount(); command(AgentComposerCommand.RESULTS)
        compose.onNodeWithText("Session results").assertExists()
        compose.runOnIdle { ui.backendId.value = "codex" }
        compose.onNodeWithText("Session results").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextReplacement("/resume")
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        compose.onNodeWithText("Prepare continuation").assertDoesNotExist()
        compose.runOnIdle { ui.backendId.value = "droide" }
        compose.onNodeWithContentDescription(AgentComposerCommand.RESUME.description).assertIsEnabled()
        assertEquals("/resume", ui.input.value)
        assertNull(opened); assertNull(exported)
    }

    @Test fun externalReplyTextCannotImpersonateLegacyToolActivity() {
        val answer = "TOOL|read_file|{}|This is quoted output"
        states["codex"] = state("codex", listOf(AgentMessage("user", "Explain this format"), AgentMessage("assistant", answer)))
        ui.backendId.value = "codex"; mount()
        compose.onNodeWithText(answer).assertExists()
        compose.onNodeWithText("Response").assertExists()
        compose.onNodeWithText("Succeeded · Read").assertDoesNotExist()
        compose.onNodeWithContentDescription("Copy response").performClick()
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(answer, clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test fun filteredExternalHistoryKeepsItsOriginalPagingIdentityAndScrollPosition() {
        val messages = (0 until 30).flatMap { index -> listOf(
            AgentMessage("assistant", "", AgentStreamPart("step-$index", index, AgentStreamPartKind.STEP,
                AgentStreamPartState.COMPLETED, title = "Provider-reported tokens")),
            AgentMessage("assistant", "External answer $index")) }
        states["codex"] = state("codex", messages)
        ui.backendId.value = "codex"; mount()
        compose.onNodeWithContentDescription("Agent conversation").performScrollToIndex(10)
        compose.waitUntil(5_000) {
            ui.chatPosition(AgentChatConversationKey("codex", "history-codex"))?.anchorKey == "message:21:assistant:"
        }
        compose.onNodeWithText("External answer 10").assertIsDisplayed()
        compose.onNodeWithText("Provider-reported tokens").assertDoesNotExist()
        compose.runOnIdle { height = 360.dp }
        compose.waitForIdle()
        assertEquals("message:21:assistant:", ui.chatPosition(AgentChatConversationKey("codex", "history-codex"))?.anchorKey)
    }
}
