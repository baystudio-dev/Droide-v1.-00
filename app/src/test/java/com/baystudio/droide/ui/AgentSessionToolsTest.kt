package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.baystudio.droide.DroideApplication
import com.baystudio.droide.core.*
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
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
class AgentSessionToolsTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as DroideApplication
    private val root = Files.createTempDirectory("session-tools-").toFile()
    private val ui by lazy { AgentWorkspaceUiState(app.workspaceHost.runtimeScope, app) }
    private val agent by lazy { AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root),
        maxSubagentDepth = 0, appContext = app, runtimeScope = app.workspaceHost.runtimeScope) }
    private var opened: String? = null
    private var exported: String? = null

    @Before fun openSession() { ApplicationWorkAdmission.initialize(app); ApplicationWorkAdmission.reopenFromUser() }
    @After fun cleanup() { agent.shutdown(); ui.close(); app.workspaceHost.close(); PathSecurity.deleteTreeNoFollow(root) }

    private fun seed() = runBlocking {
        root.resolve("result.txt").writeText("Saved output")
        val session = agent.sessions.create("Work").copy(messages = listOf(
            AgentMessage("user", "Create result.txt"),
            AgentMessage("assistant", "", AgentStreamPart("write", 1, AgentStreamPartKind.TOOL,
                AgentStreamPartState.COMPLETED, toolName = "write_file", fileChanges = listOf(AgentFileChange("result.txt", "done")))),
        ))
        agent.sessions.save(session); agent.loadSession(session)
    }

    private fun mount() {
        compose.setContent { DroideTheme { Box(Modifier.width(360.dp).height(640.dp)) {
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

    @Test fun workflowCommandWorksWithoutProviderAndOnlyPreparesADraft() {
        mount(); command(AgentComposerCommand.WORKFLOWS)
        compose.onNodeWithText("Workflows").assertIsDisplayed()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Use template").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Use template").onFirst().performClick()
        assertEquals("@skill/workflow-review ", ui.input.value)
        assertTrue(agent.messages.value.isEmpty()); assertFalse(agent.busy.value)
    }

    @Test fun resultsUseExistingOpenAndExportActionsWithoutCallingAModel() {
        seed(); mount(); command(AgentComposerCommand.RESULTS)
        compose.onNodeWithText("Session results").assertIsDisplayed()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Open").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Open").performClick()
        compose.waitUntil(5_000) { opened != null }
        assertEquals("result.txt", opened)
        command(AgentComposerCommand.RESULTS)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Export").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Export").performClick()
        compose.waitUntil(5_000) { exported != null }
        assertEquals("result.txt", exported)
        assertEquals(2, agent.messages.value.size)
    }

    @Test fun missingResultIsReportedWithoutInvokingAnOpenAction() {
        seed(); root.resolve("result.txt").delete(); mount(); command(AgentComposerCommand.RESULTS)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Open").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Open").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("This file is no longer available. Check its location in Explorer.").fetchSemanticsNodes().isNotEmpty() }
        assertNull(opened); assertNull(exported)
    }

    @Test fun continuationRequiresAnExistingTaskAndDoesNotSendAutomatically() {
        mount(); command(AgentComposerCommand.RESUME)
        compose.onNodeWithText("Prepare continuation").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Close Continue task").performClick()
        seed(); compose.waitForIdle(); command(AgentComposerCommand.RESUME)
        compose.onNodeWithText("Prepare continuation").assertIsEnabled().performClick()
        assertEquals(AGENT_CONTINUATION_PROMPT, ui.input.value)
        assertEquals(2, agent.messages.value.size); assertFalse(agent.busy.value)
    }

    @Test fun sessionSwitchDismissesOldResultActions() {
        seed(); mount(); command(AgentComposerCommand.RESULTS)
        compose.onNodeWithText("Session results").assertExists()
        val next = runBlocking { agent.sessions.create("Another task") }
        runBlocking { agent.loadSession(next) }
        compose.waitForIdle()
        compose.onNodeWithText("Session results").assertDoesNotExist()
        assertNull(opened)
    }

    @Test fun workflowPickerHonorsWorkspaceInvocationFlags() {
        runBlocking { SkillManagement(FileRepository(root)).save(
            "---\nname: workflow-review\ndescription: Private review\nuser-invocable: false\n---\nPrivate instructions", "workspace") }
        mount(); command(AgentComposerCommand.WORKFLOWS)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Use template").fetchSemanticsNodes().size == 2 }
        compose.onNodeWithText("Review a project").assertDoesNotExist()
        compose.onNodeWithText("Repair and verify").assertExists()
    }

    @Test fun streamingBurstKeepsAllDataWhileComposeCoalescesRenderingToFrames() {
        val state = MutableStateFlow(AgentStreamState(runId = 1, status = AgentStreamState.Status.BUSY))
        val projector = AgentStreamProjector(state, 20_000, 1_000)
        var renders = 0
        compose.setContent { DroideTheme {
            val value by state.collectAsState()
            SideEffect { renders++ }
            AgentStreamingMarkdownContent(value.parts.firstOrNull()?.text.orEmpty(), "burst", completed = value.status == AgentStreamState.Status.IDLE)
        } }
        compose.waitForIdle(); compose.mainClock.autoAdvance = false
        val before = renders
        compose.runOnIdle { repeat(1_000) { projector.appendText("text", 1, "x", false) } }
        assertEquals("x".repeat(1_000), state.value.parts.single().text)
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        assertTrue("Recompositions for one burst: ${renders - before}", renders - before <= 2)
        compose.onNodeWithText("x".repeat(1_000)).assertExists()
        compose.runOnIdle { state.value = state.value.copy(status = AgentStreamState.Status.IDLE) }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.onNodeWithText("x".repeat(1_000)).assertExists()
    }
}
