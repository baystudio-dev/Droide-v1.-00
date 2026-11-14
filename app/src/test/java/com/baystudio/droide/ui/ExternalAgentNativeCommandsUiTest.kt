package com.baystudio.droide.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.baystudio.droide.DroideApplication
import com.baystudio.droide.core.*
import java.nio.file.Files
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
class ExternalAgentNativeCommandsUiTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as DroideApplication
    private val root = Files.createTempDirectory("external-native-commands-").toFile()
    private val host get() = app.workspaceHost
    private val ui by lazy { AgentWorkspaceUiState(host.runtimeScope, app) }
    private val agent by lazy { AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root),
        maxSubagentDepth = 0, appContext = app, runtimeScope = host.runtimeScope) }

    @Before fun openApplication() { ApplicationWorkAdmission.initialize(app); ApplicationWorkAdmission.reopenFromUser() }
    @After fun cleanup() { agent.shutdown(); ui.close(); host.close(); PathSecurity.deleteTreeNoFollow(root) }

    @Test fun advertisedCommandsReachTheCliUnchangedWithoutOpeningLocalViews() = exercise(advertised = true)
    @Test fun unsupportedCommandsRemainOrdinaryPromptsWithoutInventingCliCapabilities() = exercise(advertised = false)

    private fun exercise(advertised: Boolean) {
        compose.runOnIdle { host }
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            host.coordinator.runtime.value != null || host.initError.value != null
        }
        val spec = WorkspaceAgentPluginCatalog.entries.single { it.id == "codex" }
        val names = listOf("resume", "results", "compact")
        AgentPaneCliPeer(root, spec, host.runtimeScope, if (advertised) names else emptyList()).use { cli ->
            ui.backendId.value = spec.id
            compose.setContent { DroideTheme { Box(Modifier.size(360.dp, 640.dp)) {
                AgentPane(agent, ui, host.coordinator.providerConnections, host.coordinator.localLlamaAgentModels,
                    agentPlugins = cli.host)
            } } }
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                ui.externalController[spec.id].configuration != null && !ui.externalController.busy
            }
            val inputs = listOf("/resume", "/results", "/compact", "/resume  original-id\nkeep these arguments")
            inputs.forEachIndexed { index, input ->
                compose.onNode(hasSetTextAction()).performTextReplacement(input)
                compose.onNodeWithContentDescription("Send").assertIsEnabled().performClick()
                compose.waitUntil(10_000) { compose.waitForIdle(); cli.prompts.size == index + 1 }
                val request = cli.prompts[index]
                assertEquals(cli.sessionId, cli.promptSession(request))
                assertEquals(if (advertised) input else AgentRequestScope.forCliPrompt(input), cli.promptText(request))
                assertEquals("", ui.input.value)
                compose.onNodeWithText("Session results").assertDoesNotExist()
                compose.onNodeWithText("Prepare continuation").assertDoesNotExist()
                cli.text("CLI handled command ${index + 1}")
                cli.finish(request)
                compose.waitUntil(10_000) { compose.waitForIdle(); !ui.externalController.busy }
                assertEquals(cli.sessionId, ui.externalController[spec.id].sessionId)
                assertNull(ui.externalController[spec.id].connectionError)
            }
            assertEquals(1, cli.starts.get())
            assertEquals(1, cli.methods.count { it == "session/new" })
            assertFalse(cli.methods.any { it == "session/resume" || it == "session/load" })
            assertTrue(agent.messages.value.isEmpty())
        }
    }
}
