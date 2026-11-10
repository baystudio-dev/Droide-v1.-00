package com.baystudio.droide.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import com.baystudio.droide.DroideApplication
import com.baystudio.droide.core.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual AgentPane disposal/re-creation and its Stop button; loopback transport only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = DroideApplication::class, qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentPaneLifetimeTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as DroideApplication
    private val root = Files.createTempDirectory("agent-pane-lifetime-").toFile()
    private val host get() = app.workspaceHost
    private val ui by lazy { AgentWorkspaceUiState(host.runtimeScope, app) }
    private val agent by lazy { AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root),
        maxSubagentDepth = 0, appContext = app, runtimeScope = host.runtimeScope) }
    private var visible by mutableStateOf(true)
    private var paneHeight by mutableStateOf(640.dp)
    private var paneWidth by mutableStateOf(360.dp)

    @Before fun openApplicationSession() {
        ApplicationWorkAdmission.initialize(app)
        ApplicationWorkAdmission.reopenFromUser()
    }

    @After fun cleanup() { agent.shutdown(); ui.close(); host.close(); PathSecurity.deleteTreeNoFollow(root) }

    private fun mount(agentPlugins: WorkspaceAgentPluginHost? = null) {
        compose.setContent { DroideTheme { Box(Modifier.width(paneWidth).height(paneHeight)) {
            if (visible) AgentPane(agent, ui, host.coordinator.providerConnections, host.coordinator.localLlamaAgentModels,
                agentPlugins = agentPlugins, onClose = { visible = false })
            else Text("Editor remains available")
        } } }
    }

    private fun closePanel() = compose.onNode(hasContentDescription("Close ", substring = true) and hasClickAction()).performClick()

    private fun assertSinglePrimary(description: String) {
        compose.onAllNodes(
            hasContentDescription("Send") or hasContentDescription("Send instruction") or hasContentDescription("Queue instruction") or
                hasContentDescription("Stop active agent run") or hasContentDescription("Cancel provider preflight"),
        ).assertCountEquals(1)
        compose.onNodeWithContentDescription(description).assertHasClickAction()
    }

    private fun stopFromRunMenu(label: String = "Stop active agent run") {
        compose.onNodeWithContentDescription("Instruction delivery").performScrollTo().performClick()
        compose.onNodeWithText(label).assertIsEnabled().performClick()
    }

    private fun awaitWorkspaceInitialization() {
        compose.runOnIdle { host }
        // Bootstrap installs its catalog asynchronously. Robolectric may lack native runtime assets;
        // this Agent UI fixture uses AgentWorkflowTerminal and only needs bootstrap to settle.
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            host.coordinator.runtime.value != null || host.initError.value != null
        }
    }

    private fun connectLoopbackFixture(fixture: ProviderStreamFixture) {

        val method = host.coordinator.providerConnections.authMethods(fixture.provider.id).first { !it.storesSecret }
        assertTrue(ProviderConnectionStateStore(app).put(ProviderConnectionRecord(fixture.provider.id, method.id, 1L, 1,
            ProviderConnectionIdentity.fingerprint(fixture.provider))))
        runBlocking { AgentPreferences.saveProvider(app, fixture.provider.id, fixture.model) }
        assertEquals(ProviderConnectionBackend.Status.CONNECTED, host.coordinator.providerConnections.snapshot(fixture.provider.id).status)
    }

    @Test fun idleComposerHasOneSendButtonWithAnAccessibleTouchTarget() {
        mount()
        assertSinglePrimary("Send")
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        val bounds = compose.onNodeWithContentDescription("Send").fetchSemanticsNode().boundsInRoot
        val minimum = 48f * app.resources.displayMetrics.density
        assertTrue("Primary action width must allow a 48dp touch target", bounds.width >= minimum)
        assertTrue("Primary action height must allow a 48dp touch target", bounds.height >= minimum)
        compose.onNode(hasSetTextAction()).performTextInput("An unconnected draft")
        assertSinglePrimary("Send")
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        assertEquals("An unconnected draft", ui.input.value)
    }

    @Test fun wrappedPromptGrowsTheInputAndClearingItRestoresTheCompactHeight() {
        mount()
        val field = compose.onNode(hasSetTextAction())
        val initial = field.fetchSemanticsNode().boundsInRoot.height
        val prompt = (1..12).joinToString(" ") { "Investigate the project and explain the next safe change." }
        field.performTextInput(prompt)
        val expanded = field.fetchSemanticsNode().boundsInRoot.height
        val layouts = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Native text layout must really soft-wrap the prompt", (layouts.lastOrNull()?.lineCount ?: 0) > 2)
        assertTrue("Wrapped text must grow beyond the initial few lines: initial=$initial expanded=$expanded lines=${layouts.lastOrNull()?.lineCount} layout=${layouts.lastOrNull()?.size}", expanded > initial * 2)
        assertTrue("The composer must preserve room for the conversation", expanded <= 180f * app.resources.displayMetrics.density + 1f)
        assertEquals(prompt, ui.input.value)
        assertSinglePrimary("Send")
        assertFalse(agent.busy.value)
        field.performTextClearance()
        assertEquals(initial, field.fetchSemanticsNode().boundsInRoot.height, 1f)
    }

    @Test fun aShorterPaneShrinksALongInputWithoutLosingTheDraftOrHidingItsAction() {
        mount()
        val prompt = (1..60).joinToString("\n") { "Line $it: keep this source review instruction." }
        compose.onNode(hasSetTextAction()).performTextInput(prompt)
        val initial = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot.height
        compose.runOnIdle { paneHeight = 260.dp }
        val reduced = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot.height
        assertTrue("The input must adapt when the pane loses vertical room", reduced < initial)
        assertTrue("A short pane still reserves room outside the input", reduced <= 90f * app.resources.displayMetrics.density + 1f)
        val action = compose.onNodeWithContentDescription("Send").fetchSemanticsNode().boundsInRoot
        assertTrue("Send/Stop must stay inside the available pane", action.bottom <= 260f * app.resources.displayMetrics.density + 1f)
        assertEquals(prompt, ui.input.value)
        assertSinglePrimary("Send")
        compose.runOnIdle { paneHeight = 640.dp }
        assertEquals(initial, compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot.height, 1f)
        assertEquals(prompt, ui.input.value)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun boundedMultilinePromptCanBeEditedAtBothEndsAndSurvivesPanelClosure() {
        mount()
        val prompt = (1..80).joinToString("\n") { "Review line $it without removing any instruction." }
        val field = compose.onNode(hasSetTextAction())
        field.performTextInput(prompt)
        field.performTextInputSelection(TextRange(prompt.length))
        field.performTextInput(" End marker")
        field.performTextInputSelection(TextRange(0))
        field.performTextInput("Start marker ")
        val expected = "Start marker $prompt End marker"
        assertEquals(expected, ui.input.value)
        assertTrue(field.fetchSemanticsNode().boundsInRoot.height <= 180f * app.resources.displayMetrics.density + 1f)
        closePanel()
        assertEquals(expected, ui.input.value)
        compose.runOnIdle { visible = true }
        compose.onNode(hasSetTextAction()).assertTextContains(expected)
        assertSinglePrimary("Send")
        assertFalse(agent.busy.value)
    }

    @Test fun composerDraftSurvivesClosingThePanelAndReturningFromTheEditor() {
        mount()
        compose.onNode(hasSetTextAction()).performTextInput("Keep this unsent draft")
        closePanel()
        compose.onNodeWithText("Editor remains available").assertExists()
        assertEquals("Keep this unsent draft", ui.input.value)
        compose.runOnIdle { visible = true }
        compose.onNode(hasSetTextAction()).assertTextContains("Keep this unsent draft")
    }

    @Test fun providerPreparationSurvivesPanelClosureAndCanOnlyBeCanceledWithStop() {
        val release = CompletableDeferred<Unit>()
        val preparation = ui.scope.launch { release.await() }
        ui.ownPreparation(preparation)
        ui.input.value = "Keep the prompt until admission succeeds"
        mount()
        assertSinglePrimary("Send")
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled().performTouchInput { click() }
        assertTrue("A duplicate Send during preparation must not become Stop", preparation.isActive)
        compose.onNodeWithContentDescription("New agent session").assertIsNotEnabled()
        closePanel()
        assertTrue(preparation.isActive)
        compose.runOnIdle { visible = true }
        stopFromRunMenu("Cancel provider preflight")
        compose.waitUntil(5_000) { compose.waitForIdle(); preparation.isCompleted }
        assertTrue(preparation.isCancelled)
        assertSinglePrimary("Send")
        compose.onNodeWithContentDescription("New agent session").assertIsEnabled()
        assertEquals("Keep the prompt until admission succeeds", ui.input.value)
    }

    @Test fun externalAgentCanBeStoppedAfterPanelReopensEvenBeforeDetectionFinishes() {
        val spec = WorkspaceAgentPluginCatalog.entries.first()
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val backend = object : WorkspaceAgentChatBackend {
            override suspend fun runTurn(agentId: String, prompt: String, mode: WorkspaceAgentMode, resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit): WorkspaceAgentTurnResult {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
            override suspend fun prepareSession(agentId: String, resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit) = error("Unused fixture operation")
            override suspend fun selectModel(agentId: String, expectedSessionId: String, modelId: String) = error("Unused fixture operation")
            override suspend fun newSession(agentId: String, onEvent: (WorkspaceAgentEvent) -> Unit) = error("Unused fixture operation")
            override suspend fun cancel(agentId: String, expectedSessionId: String?) = Unit
        }
        ui.backendId.value = spec.id
        ui.input.value = "Keep the external follow-up draft"
        mount()
        compose.runOnIdle { assertTrue(ui.externalController.submit(backend, spec, "Long external operation", WorkspaceAgentMode.EDIT)) }
        compose.waitUntil(5_000) { compose.waitForIdle(); entered.isCompleted }
        closePanel()
        assertTrue(ui.externalController.busy)
        compose.runOnIdle { visible = true }
        // No availability host is supplied: the selected backend must stay external while detection is
        // absent.
        assertSinglePrimary("Send instruction")
        compose.onNodeWithContentDescription("Send instruction").assertIsNotEnabled()
        stopFromRunMenu()
        compose.waitUntil(5_000) { compose.waitForIdle(); cancelled.isCompleted && !ui.externalController.busy }
        assertEquals(spec.id, ui.backendId.value)
        assertFalse(agent.busy.value)
        assertSinglePrimary("Send")
        assertEquals("Keep the external follow-up draft", ui.input.value)
    }

    @Test fun closingTheActualPaneKeepsTheRunAndStopAfterReopeningCancelsIt() {

        awaitWorkspaceInitialization()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        mount()
        withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { fixture ->
            fixture.ignoreExpectedClientDisconnect = true
            fixture.response = { _, _, _ ->
                entered.countDown(); check(finish.await(15, TimeUnit.SECONDS))
                fixture.event("""{"choices":[{"index":0,"delta":{"content":"completed"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n"
            }
            try {
                compose.runOnIdle {
                    val admission = agent.submitAsync(ui.scope, "Long provider task", fixture.config, maxTurns = 1)
                    assertNotNull("Prompt was rejected: ${agent.persistenceError.value}", admission)
                }
                try { compose.waitUntil(10_000) { compose.waitForIdle(); entered.count == 0L && agent.busy.value } }
                catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
                    throw AssertionError("Provider did not start: busy=${agent.busy.value}, error=${agent.persistenceError.value}, state=${agent.messages.value.lastOrNull()?.content}", failure)
                }
                closePanel()
                compose.onNodeWithText("Editor remains available").assertExists()
                assertTrue(agent.busy.value)
                compose.runOnIdle { visible = true }
                assertSinglePrimary("Stop active agent run")
                compose.onNodeWithContentDescription("Stop active agent run").performTouchInput { click() }
                compose.waitUntil(10_000) { compose.waitForIdle(); !agent.busy.value }
                assertTrue(agent.messages.value.none { it.content == "completed" })
                assertEquals(1, fixture.requests.size)
            } finally { finish.countDown() }
        }
    }

    @Test fun openingBuiltinHistoryCannotCancelAnAcceptedLongRunningTask() {
        awaitWorkspaceInitialization()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val saved = runBlocking { agent.sessions.create("Earlier saved conversation") }
        mount()
        withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { fixture ->
            fixture.ignoreExpectedClientDisconnect = true
            fixture.response = { _, _, _ ->
                entered.countDown(); check(finish.await(15, TimeUnit.SECONDS))
                fixture.event("""{"choices":[{"index":0,"delta":{"content":"completed"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n"
            }
            try {
                compose.runOnIdle { assertNotNull(agent.submitAsync(ui.scope, "Keep working", fixture.config, maxTurns = 1)) }
                compose.waitUntil(10_000) { compose.waitForIdle(); entered.count == 0L && agent.busy.value }
                val active = agent.currentSessionId
                var rejected = false
                runBlocking {
                    try { agent.loadSession(saved, requireIdle = true) }
                    catch (_: IllegalStateException) { rejected = true }
                }
                assertTrue(rejected)
                assertTrue(agent.busy.value)
                assertEquals(active, agent.currentSessionId)
                assertEquals(1, fixture.requests.size)
                compose.onNodeWithContentDescription("Stop active agent run").performSemanticsAction(SemanticsActions.OnClick) { it() }
                compose.waitUntil(10_000) { compose.waitForIdle(); !agent.busy.value }
            } finally { finish.countDown() }
        }
    }

    @Test fun primarySendSteersTheWorkingAgentWithoutCancellingOrStartingANewSession() {
        awaitWorkspaceInitialization()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, authRequired = false) { fixture ->
            fixture.ignoreExpectedClientDisconnect = true
            fixture.response = { number, _, _ ->
                if (number == 1) { entered.countDown(); check(finish.await(25, TimeUnit.SECONDS)) }
                fixture.event("""{"choices":[{"index":0,"delta":{"content":"completed"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n"
            }
            connectLoopbackFixture(fixture)
            mount()
            try {
                compose.onNode(hasSetTextAction()).performTextInput("Start the first task")
                try {
                    compose.waitUntil(5_000) { compose.waitForIdle(); compose.onAllNodes(hasContentDescription("Send") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
                    throw AssertionError("Send unavailable: input=${ui.input.value}, preferences=${runBlocking { AgentPreferences.load(app) }}, connected=${host.coordinator.providerConnections.snapshot(fixture.provider.id)}, tree=${compose.onRoot().printToString()}", failure)
                }
                assertSinglePrimary("Send")
                compose.onNodeWithContentDescription("Send").performTouchInput { click() }
                compose.waitUntil(10_000) { compose.waitForIdle(); entered.count == 0L && agent.busy.value && ui.input.value.isEmpty() }
                assertSinglePrimary("Stop active agent run")
                val session = agent.currentSessionId
                val correction = "Use the new requirement while keeping this task running"
                compose.onNode(hasSetTextAction()).performTextInput(correction)
                assertSinglePrimary("Send instruction")
                assertTrue("Typing an instruction must leave the current task running", agent.busy.value)
                assertEquals(correction, ui.input.value)
                assertTrue(agent.pendingInputs.value.isEmpty())
                compose.onNodeWithContentDescription("Send instruction").assertIsEnabled().performTouchInput { click() }
                compose.waitUntil(5_000) { compose.waitForIdle(); agent.pendingInputs.value.singleOrNull()?.text == correction && ui.input.value.isEmpty() }
                assertEquals(AgentPromptDelivery.STEER, agent.pendingInputs.value.single().delivery)
                assertEquals(session, agent.currentSessionId)
                assertTrue(agent.busy.value)
                assertEquals(1, fixture.requests.size)
                assertSinglePrimary("Stop active agent run")
                finish.countDown()
                compose.waitUntil(10_000) { compose.waitForIdle(); fixture.requests.size == 2 && !agent.busy.value && ui.input.value.isEmpty() }
                assertSinglePrimary("Send")
                assertEquals(session, agent.currentSessionId)
                assertEquals(2, fixture.requests.size)
                assertTrue("The next actual model request must contain the instruction", fixture.requests[1].toString().contains(correction))
                assertTrue(agent.pendingInputs.value.isEmpty())
                assertTrue(agent.messages.value.any { it.content == "completed" })
            } finally { finish.countDown() }
        }
    }

    @Test
    @Config(qualifiers = "w720dp-h360dp-land")
    fun landscapeWhitespaceKeepsStopAndAttachmentOnlyDraftCanSteer() {
        assertEquals(android.content.res.Configuration.ORIENTATION_LANDSCAPE, app.resources.configuration.orientation)
        assertEquals(720, app.resources.configuration.screenWidthDp)
        awaitWorkspaceInitialization()
        // Apply layout state immediately before mounting, not before an idling call with no root.
        paneWidth = 720.dp
        paneHeight = 320.dp
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, authRequired = false) { fixture ->
            fixture.ignoreExpectedClientDisconnect = true
            fixture.response = { _, _, _ ->
                entered.countDown(); check(finish.await(25, TimeUnit.SECONDS))
                fixture.event("""{"choices":[{"index":0,"delta":{"content":"completed"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n"
            }
            connectLoopbackFixture(fixture)
            mount()
            try {
                compose.runOnIdle { assertNotNull(agent.submitAsync(ui.scope, "Long provider task", fixture.config, maxTurns = 1)) }
                compose.waitUntil(10_000) { compose.waitForIdle(); entered.count == 0L && agent.busy.value }
                compose.onNode(hasSetTextAction()).performTextInput("  \n  ")
                assertSinglePrimary("Stop active agent run")
                compose.runOnIdle {
                    // Text attachment context only; no image decoder, disk import or provider vision used.
                    ui.attachments.value = listOf(AgentAttachedContext("notes.txt", "Use the attached correction"))
                }
                assertSinglePrimary("Send instruction")
                val action = compose.onNodeWithContentDescription("Send instruction").assertIsEnabled()
                val bounds = action.fetchSemanticsNode().boundsInRoot
                val density = app.resources.displayMetrics.density
                assertTrue(bounds.width >= 48f * density && bounds.height >= 48f * density)
                assertTrue(bounds.bottom <= 320f * density + 1f)
                action.performTouchInput { click() }
                compose.waitUntil(5_000) {
                    compose.waitForIdle()
                    agent.pendingInputs.value.singleOrNull()?.text?.contains("Use the attached correction") == true &&
                        ui.attachments.value.isEmpty() && ui.input.value.isEmpty()
                }
                assertEquals(AgentPromptDelivery.STEER, agent.pendingInputs.value.single().delivery)
                assertTrue(agent.busy.value)
                assertEquals(1, fixture.requests.size)
                assertSinglePrimary("Stop active agent run")
                compose.onNodeWithContentDescription("Stop active agent run").performTouchInput { click() }
                compose.waitUntil(10_000) { compose.waitForIdle(); !agent.busy.value }
            } finally { finish.countDown() }
        }
    }

    @Test fun activeInstructionsCanStillBeSteeredAndQueuedFromTheMenuWithoutAnotherPrimaryButton() {
        awaitWorkspaceInitialization()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, authRequired = false) { fixture ->
            fixture.ignoreExpectedClientDisconnect = true
            fixture.response = { _, _, _ ->
                entered.countDown(); check(finish.await(25, TimeUnit.SECONDS))
                fixture.event("""{"choices":[{"index":0,"delta":{"content":"completed"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n"
            }
            connectLoopbackFixture(fixture)
            mount()
            try {
                compose.runOnIdle { assertNotNull(agent.submitAsync(ui.scope, "Long provider task", fixture.config, maxTurns = 1)) }
                compose.waitUntil(10_000) { compose.waitForIdle(); entered.count == 0L && agent.busy.value }
                compose.onNodeWithContentDescription("Instruction delivery").performScrollTo().performClick()
                compose.onNodeWithText("Send instruction").assertIsNotEnabled()
                compose.onNodeWithText("Steer current run").performClick()
                compose.onNode(hasSetTextAction()).performTextInput("Apply this correction safely")
                compose.onNodeWithContentDescription("Instruction delivery").performScrollTo().performClick()
                compose.onNodeWithText("Send instruction").assertIsEnabled().performClick()
                compose.waitUntil(5_000) { compose.waitForIdle(); agent.pendingInputs.value.any { it.delivery == AgentPromptDelivery.STEER } && ui.input.value.isEmpty() }
                assertSinglePrimary("Stop active agent run")
                compose.onNode(hasSetTextAction()).performTextInput("Do this after the task")
                compose.onNodeWithContentDescription("Instruction delivery").performScrollTo().performClick()
                compose.onNodeWithText("Queue next turn").performClick()
                assertSinglePrimary("Queue instruction")
                compose.onNodeWithContentDescription("Queue instruction").assertIsEnabled().performTouchInput { click() }
                compose.waitUntil(5_000) { compose.waitForIdle(); agent.pendingInputs.value.size == 2 && ui.input.value.isEmpty() }
                assertTrue(agent.pendingInputs.value.any { it.delivery == AgentPromptDelivery.QUEUE })
                assertTrue(agent.busy.value)
                assertEquals(1, fixture.requests.size)
                assertSinglePrimary("Stop active agent run")
                compose.onNodeWithContentDescription("Stop active agent run").performTouchInput { click() }
                compose.waitUntil(10_000) { compose.waitForIdle(); !agent.busy.value }
            } finally { finish.countDown() }
        }
    }

    @Test fun codexPanelSendsSteerAndQueueToTheSameCliSession() = externalPanelSteerAndQueue("codex")
    @Test fun openCodePanelSendsSteerAndQueueToTheSameCliSession() = externalPanelSteerAndQueue("opencode")
    @Test fun claudePanelSendsSteerAndQueueToTheSameCliSession() = externalPanelSteerAndQueue("claude")
    @Test fun geminiPanelSendsSteerAndQueueToTheSameCliSession() = externalPanelSteerAndQueue("gemini")
    @Test fun copilotPanelSendsSteerAndQueueToTheSameCliSession() = externalPanelSteerAndQueue("copilot")

    private fun externalPanelSteerAndQueue(agentId: String) {
        awaitWorkspaceInitialization()
        val spec = WorkspaceAgentPluginCatalog.entries.single { it.id == agentId }
        AgentPaneCliPeer(root, spec, host.runtimeScope).use { cli ->
            ui.backendId.value = spec.id
            mount(cli.host)
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                ui.externalController[spec.id].configuration != null && !ui.externalController.busy
            }
            val nativeSession = ui.externalController[spec.id].sessionId
            assertEquals(cli.sessionId, nativeSession)
            compose.onNode(hasSetTextAction()).performTextInput("Start the CLI task")
            compose.onNodeWithContentDescription("Send").assertIsEnabled().performTouchInput { click() }
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                cli.prompts.size == 1 && ui.externalController.busy && ui.input.value.isEmpty()
            }
            val historyId = ui.externalController[spec.id].historyId
            assertSinglePrimary("Stop active agent run")
            val correction = "Use this correction in the active CLI task"
            compose.onNode(hasSetTextAction()).performTextInput(correction)
            assertSinglePrimary("Send instruction")
            compose.onNodeWithContentDescription("Send instruction").assertIsEnabled().performTouchInput { click() }
            compose.waitUntil(5_000) {
                compose.waitForIdle()
                ui.externalController.pendingInputsFor(spec.id).singleOrNull()?.text == correction && ui.input.value.isEmpty()
            }
            assertEquals(AgentPromptDelivery.STEER, ui.externalController.pendingInputsFor(spec.id).single().delivery)
            assertTrue(ui.externalController.busy)
            assertEquals(nativeSession, ui.externalController[spec.id].sessionId)
            assertEquals(1, cli.prompts.size)
            assertEquals(1, cli.starts.get())
            assertTrue(cli.process.isAlive)
            assertFalse(agent.busy.value)

            // ACP steering waits for a safe output boundary, acknowledges the interrupted turn, then submits
            // the correction to the same native session. It is not the UI Stop path.
            cli.text("Working on the current task")
            compose.waitUntil(5_000) { compose.waitForIdle(); cli.cancellations.size == 1 }
            cli.finish(cli.prompts[0], "cancelled")
            compose.waitUntil(10_000) { compose.waitForIdle(); cli.prompts.size == 2 }
            assertTrue(cli.promptText(cli.prompts[1]).contains(correction))
            assertTrue(ui.externalController.pendingInputsFor(spec.id).isEmpty())
            assertSinglePrimary("Stop active agent run")

            val queued = "Run this after the correction has finished"
            compose.onNode(hasSetTextAction()).performTextInput(queued)
            compose.onNodeWithContentDescription("Instruction delivery").performScrollTo().performClick()
            compose.onNodeWithText("Queue next turn").performClick()
            assertSinglePrimary("Queue instruction")
            compose.onNodeWithContentDescription("Queue instruction").assertIsEnabled().performTouchInput { click() }
            compose.waitUntil(5_000) {
                compose.waitForIdle()
                ui.externalController.pendingInputsFor(spec.id).singleOrNull()?.text == queued && ui.input.value.isEmpty()
            }
            assertEquals(AgentPromptDelivery.QUEUE, ui.externalController.pendingInputsFor(spec.id).single().delivery)
            assertEquals("Queue must not interrupt the current turn", 1, cli.cancellations.size)
            assertEquals(2, cli.prompts.size)
            cli.finish(cli.prompts[1])
            compose.waitUntil(10_000) { compose.waitForIdle(); cli.prompts.size == 3 }
            assertTrue(cli.promptText(cli.prompts[2]).contains(queued))
            cli.text("All instructions completed")
            cli.finish(cli.prompts[2])
            compose.waitUntil(10_000) { compose.waitForIdle(); !ui.externalController.busy }
            assertSinglePrimary("Send")
            assertEquals(nativeSession, ui.externalController[spec.id].sessionId)
            assertEquals(historyId, ui.externalController[spec.id].historyId)
            assertTrue(ui.externalController.pendingInputsFor(spec.id).isEmpty())
            assertEquals(1, cli.starts.get())
            assertEquals(1, cli.methods.count { it == "session/new" })
            assertTrue(cli.prompts.all { cli.promptSession(it) == nativeSession })
            assertTrue(cli.process.isAlive)
            assertNull(ui.externalController[spec.id].connectionError)
            assertFalse(agent.busy.value)
        }
    }
}
