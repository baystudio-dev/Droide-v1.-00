package com.baystudio.droide.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.baystudio.droide.DroideApplication
import com.baystudio.droide.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = DroideApplication::class, qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentChatScrollWorkflowTest {
    @org.junit.Before fun openApplicationSession() {
        ApplicationWorkAdmission.initialize(org.robolectric.RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
    }

    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as DroideApplication
    private val host get() = app.workspaceHost
    private val root = Files.createTempDirectory("agent-chat-scroll-").toFile()
    private val ui by lazy { AgentWorkspaceUiState(host.runtimeScope, app) }
    private val agent by lazy { AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root),
        maxSubagentDepth = 0, appContext = app, runtimeScope = host.runtimeScope) }
    private lateinit var renderingView: android.view.View
    private var visible by mutableStateOf(true)
    private var height by mutableStateOf(640.dp)
    private var fontScale by mutableStateOf(1f)
    private fun label(index: Int) = "Source instruction ${index.toString().padStart(3, '0')}"
    private fun key(): AgentChatConversationKey {
        val backend = ui.backendId.value
        return AgentChatConversationKey(backend, if (backend == "droide") agent.currentSessionId else ui.externalController[backend].sessionId)
    }
    private fun position() = ui.chatPosition(key())

    @After fun cleanup() { agent.shutdown(); ui.close(); host.close(); PathSecurity.deleteTreeNoFollow(root) }

    private fun session(title: String = "History", messages: List<AgentMessage> = (0..79).map { AgentMessage("user", label(it)) }): DroideSession = runBlocking {
        val session = agent.sessions.create(title).copy(messages = messages)
        agent.sessions.save(session)
        agent.loadSession(session)
        assertEquals(messages, agent.messages.value)
        session
    }

    private fun mount(plugins: WorkspaceAgentPluginHost? = null) {
        compose.setContent { renderingView = androidx.compose.ui.platform.LocalView.current; CompositionLocalProvider(LocalDensity provides androidx.compose.ui.unit.Density(LocalDensity.current.density, fontScale)) { DroideTheme { Box(Modifier.width(360.dp).height(height)) {
            if (visible) AgentPane(agent, ui, host.coordinator.providerConnections, host.coordinator.localLlamaAgentModels,
                agentPlugins = plugins, onClose = { visible = false })
            else Text("Editor remains available")
        } } } }
        compose.waitUntil(5_000) { compose.waitForIdle(); position() != null }
    }

    private fun readHistory(index: Int = 20): AgentChatPosition {
        compose.onNodeWithContentDescription("Agent conversation").performScrollToIndex(index)
        compose.waitUntil(5_000) { compose.waitForIdle(); position()?.let { it.index >= index && !it.following } == true }
        return position()!!
    }

    private fun close() {
        compose.onNode(hasContentDescription("Close ", substring = true) and hasClickAction()).performClick()
        compose.onNodeWithText("Editor remains available").assertExists()
    }

    private fun reopen() { compose.runOnIdle { visible = true }; compose.waitForIdle() }

    private fun append(message: AgentMessage) {
        // Seed the production read model, not a test-only list renderer.
        val field = AgentService::class.java.getDeclaredField("_messages").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val flow = field.get(agent) as MutableStateFlow<List<AgentMessage>>
        compose.runOnIdle { flow.value = flow.value + message }
    }

    private fun screenshot(name: String) {
        val directory = System.getProperty("droide.headerEvidenceDir") ?: return
        val file = java.io.File(directory, "$name.png").apply { parentFile.mkdirs() }
        compose.runOnIdle {
            val bitmap = android.graphics.Bitmap.createBitmap(renderingView.width, renderingView.height, android.graphics.Bitmap.Config.ARGB_8888)
            renderingView.draw(android.graphics.Canvas(bitmap))
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun statusState(state: AgentStreamState) {
        val field = AgentService::class.java.getDeclaredField("_streamState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val flow = field.get(agent) as MutableStateFlow<AgentStreamState>
        compose.runOnIdle { flow.value = state }
    }

    private fun latest() {
        compose.onNodeWithContentDescription("Scroll to latest activity").let {
            if (compose.onAllNodesWithContentDescription("Scroll to latest activity").fetchSemanticsNodes().isNotEmpty()) it.performClick()
        }
        compose.waitForIdle()
    }

    @Test fun fullPaneFilesControlRemainsReachableAtLargeFont() = filesControlFixture(false)

    @Test @Config(qualifiers = "w640dp-h360dp-land")
    fun fullPaneFilesControlRemainsReachableWithLandscapeIme() = filesControlFixture(true)

    private fun filesControlFixture(landscape: Boolean) {
        session()
        val process = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command: String): String? = "/fixture/bin/$command"
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess = error("No process launch permitted")
        }
        val io = java.lang.reflect.Proxy.newProxyInstance(AgentWorkspaceIo::class.java.classLoader,
            arrayOf(AgentWorkspaceIo::class.java)) { _, method, _ -> if (method.name == "synchronizationNotice") null else error("Unexpected workspace operation: ${method.name}") } as AgentWorkspaceIo
        val plugins = WorkspaceAgentPluginHost(root, java.io.File(root, "cache"), FileRepository(root), process,
            agent.approvals, PermissionEngine(), host.runtimeScope, io)
        try {
            fontScale = 1.5f

            @Suppress("UNCHECKED_CAST") val states = ExternalAgentSessionController::class.java.getDeclaredField("states")
                .apply { isAccessible = true }.get(ui.externalController) as MutableMap<String, ExternalAgentConversationState>
            states["codex"] = ExternalAgentConversationState(sessionId = "files-ui-fixture", historyRestored = true)
            ui.backendId.value = "codex"
            mount(plugins)
            compose.onAllNodesWithText("Files").assertCountEquals(1)
            compose.onNodeWithText("Files").assertIsDisplayed().performClick()
            compose.onNodeWithText("CLI workspace synchronization").assertIsDisplayed()
            compose.onNodeWithText("Review files").assertExists()
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Files").assertIsDisplayed()
            val width = compose.onNodeWithContentDescription("Agent conversation").fetchSemanticsNode().boundsInRoot.right
            for (label in listOf("New agent session", "Agent sessions")) {
                val node = compose.onNodeWithContentDescription(label).assertIsDisplayed().fetchSemanticsNode()
                assertTrue("$label stays within pane", node.boundsInRoot.right <= width)
            }
            compose.onNode(hasContentDescription("Close ", substring = true) and hasClickAction()).assertIsDisplayed()
            screenshot("files-header-large-font")
            if (landscape) {
                compose.onNode(hasSetTextAction()).performClick()
                compose.runOnIdle {
                    val density = app.resources.displayMetrics.density
                    androidx.core.view.ViewCompat.dispatchApplyWindowInsets(renderingView,
                        androidx.core.view.WindowInsetsCompat.Builder()
                            .setInsets(androidx.core.view.WindowInsetsCompat.Type.ime(), androidx.core.graphics.Insets.of(0, 0, 0, (120 * density).toInt()))
                            .setVisible(androidx.core.view.WindowInsetsCompat.Type.ime(), true).build())
                }
                compose.waitForIdle()
                compose.onAllNodesWithText("Files").assertCountEquals(1)
                compose.onNodeWithText("Files").assertIsDisplayed().performClick()
                compose.onNodeWithText("CLI workspace synchronization").assertIsDisplayed()
                compose.onNodeWithText("Close").performClick()
                compose.onNode(hasSetTextAction()).assertIsDisplayed()
                screenshot("files-landscape-ime")
            }
        } finally { plugins.close() }
    }

    @Test fun nativeStatusIsSingleFlowingTailAndDoesNotFollowDetachedReader() {
        session(); mount()
        val part = AgentStreamPart("tail", 1, AgentStreamPartKind.TEXT, AgentStreamPartState.RUNNING, text = "Last activity")
        var state = AgentStreamState(runId = 7, sessionId = agent.currentSessionId.orEmpty(),
            status = AgentStreamState.Status.BUSY, parts = listOf(part), modelActivity = AgentModelActivity.THINKING)
        statusState(state); latest()
        compose.onAllNodesWithText("Thinking…").assertCountEquals(1)
        val status = compose.onNodeWithText("Thinking…").assertIsDisplayed().fetchSemanticsNode()
        val last = compose.onNodeWithText("Last activity").fetchSemanticsNode()
        val list = compose.onNodeWithContentDescription("Agent conversation").fetchSemanticsNode()
        assertTrue(status.boundsInRoot.top >= last.boundsInRoot.bottom)
        assertTrue(status.boundsInRoot.bottom <= list.boundsInRoot.bottom)
        screenshot("native-flowing-tail")
        val before = readHistory()
        compose.onNodeWithText("Thinking…").assertDoesNotExist()
        state = state.copy(modelActivity = AgentModelActivity.PREPARING_TOOL,
            parts = listOf(part.copy(text = "More output. ".repeat(400))))
        statusState(state); compose.waitForIdle()
        compose.onNodeWithText("Preparing tool…").assertDoesNotExist()
        assertEquals(before.anchorKey, position()!!.anchorKey)
        assertEquals(before.offset, position()!!.offset)
        assertFalse(position()!!.following)
        latest()
        compose.onAllNodesWithText("Preparing tool…").assertCountEquals(1)
        compose.onNodeWithText("Preparing tool…").assertIsDisplayed()
        statusState(state.copy(status = AgentStreamState.Status.CANCELLED))
        compose.onNodeWithText("Stopped").assertIsDisplayed()
        compose.onNodeWithText("Preparing tool…").assertDoesNotExist()
    }

    @Test fun externalStatusFlowsAfterActivityAndDoesNotLeakAcrossBackendSwitchOrCompletion() {
        session(); mount(); close()
        val controller = ui.externalController
        @Suppress("UNCHECKED_CAST") val states = ExternalAgentSessionController::class.java.getDeclaredField("states")
            .apply { isAccessible = true }.get(controller) as MutableMap<String, ExternalAgentConversationState>
        @Suppress("UNCHECKED_CAST") val run = ExternalAgentSessionController::class.java.getDeclaredField("activeRun\$delegate")
            .apply { isAccessible = true }.get(controller) as MutableState<ExternalAgentActiveRun?>
        compose.runOnIdle {
            states["codex"] = ExternalAgentConversationState(sessionId = "external-status",
                messages = (0..79).map { AgentMessage("user", label(it)) },
                streamParts = listOf(AgentStreamPart("external-tail", 1, AgentStreamPartKind.TEXT, AgentStreamPartState.RUNNING, text = "External last activity")),
                progress = ExternalAgentActivity(phase = AgentActivityKind.SEARCHING))
            run.value = ExternalAgentActiveRun(19, "codex", "external-status")
            ui.backendId.value = "codex"
        }
        reopen(); latest()
        compose.onAllNodesWithText("Searching workspace…").assertCountEquals(1)
        val status = compose.onNodeWithText("Searching workspace…").assertIsDisplayed().fetchSemanticsNode()
        assertTrue(status.boundsInRoot.top >= compose.onNodeWithText("External last activity").fetchSemanticsNode().boundsInRoot.bottom)
        assertTrue(status.boundsInRoot.bottom <= compose.onNodeWithContentDescription("Agent conversation").fetchSemanticsNode().boundsInRoot.bottom)
        screenshot("external-flowing-tail")
        val before = readHistory()
        compose.runOnIdle { states["codex"] = states.getValue("codex").copy(progress = ExternalAgentActivity(phase = AgentActivityKind.EDITING)) }
        compose.onNodeWithText("Editing files…").assertDoesNotExist()
        assertEquals(before.anchorKey, position()!!.anchorKey)
        assertEquals(before.offset, position()!!.offset)
        latest(); compose.onNodeWithText("Editing files…").assertIsDisplayed()
        close(); compose.runOnIdle { ui.backendId.value = "droide" }; reopen()
        compose.onNodeWithText("Editing files…").assertDoesNotExist()
        close(); compose.runOnIdle { ui.backendId.value = "codex" }; reopen(); latest()
        compose.onNodeWithText("Editing files…").assertIsDisplayed()
        compose.runOnIdle { run.value = null }
        compose.onNodeWithText("Editing files…").assertDoesNotExist()
    }

    @Test fun externalStatusOnlyGrowthKeepsTailVisibleAtLargeFont() {
        fontScale = 1.8f
        session(); mount(); close()
        val controller = ui.externalController
        @Suppress("UNCHECKED_CAST") val states = ExternalAgentSessionController::class.java.getDeclaredField("states")
            .apply { isAccessible = true }.get(controller) as MutableMap<String, ExternalAgentConversationState>
        @Suppress("UNCHECKED_CAST") val run = ExternalAgentSessionController::class.java.getDeclaredField("activeRun\$delegate")
            .apply { isAccessible = true }.get(controller) as MutableState<ExternalAgentActiveRun?>
        compose.runOnIdle {
            states["codex"] = ExternalAgentConversationState(sessionId = "external-growth",
                messages = (0..79).map { AgentMessage("user", label(it)) },
                streamParts = listOf(AgentStreamPart("external-growth-tail", 1, AgentStreamPartKind.TEXT, AgentStreamPartState.RUNNING, text = "Last activity")),
                progress = ExternalAgentActivity(phase = AgentActivityKind.THINKING))
            run.value = ExternalAgentActiveRun(29, "codex", "external-growth")
            ui.backendId.value = "codex"
        }
        reopen(); latest()
        compose.onNodeWithText("Thinking…").assertIsDisplayed()
        compose.runOnIdle {
            states["codex"] = states.getValue("codex").copy(progress = ExternalAgentActivity(phase = AgentActivityKind.TOOL,
                tools = mapOf("tool" to ExternalToolActivity("in_progress", "execute", "Building current project"))))
        }
        compose.waitForIdle()
        val detail = compose.onNodeWithText("Building current project").assertIsDisplayed().fetchSemanticsNode()
        val viewport = compose.onNodeWithContentDescription("Agent conversation").fetchSemanticsNode()
        assertTrue("Status-only growth stays visible for a tail reader", detail.boundsInRoot.bottom <= viewport.boundsInRoot.bottom)
        screenshot("external-status-only-growth")
    }

    @Test fun tailDisclosureAndKeyboardSizedViewportKeepComposerUsableWithoutForcingLatest() {
        session(); mount()
        val part = AgentStreamPart("tail-tool", 1, AgentStreamPartKind.TOOL, AgentStreamPartState.COMPLETED,
            title = "read file", toolName = "read_file", output = "One\nTwo\nThree\nFour", detail = "Succeeded")
        statusState(AgentStreamState(runId = 9, status = AgentStreamState.Status.BUSY, parts = listOf(part)))
        latest()
        val before = position()!!
        compose.onNodeWithContentDescription("Show stream details").performClick()
        compose.waitForIdle()
        assertFalse(position()!!.following)
        assertEquals(before.anchorKey, position()!!.anchorKey)
        assertEquals(before.offset, position()!!.offset)
        compose.runOnIdle { height = 320.dp; fontScale = 1.3f }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        assertFalse(position()!!.following)
        screenshot("short-viewport-flowing-tail")
    }

    @Test fun returningFromEditorRestoresTheExactHistoryAnchorAndOffset() {
        session(); mount()
        val before = readHistory()
        val top = compose.onNodeWithText(label(before.index)).fetchSemanticsNode().positionInRoot.y
        close(); reopen()
        val after = position()!!
        assertEquals(before.anchorKey, after.anchorKey)
        assertEquals(before.index, after.index)
        assertEquals(before.offset, after.offset)
        assertFalse(after.following)
        assertEquals(top, compose.onNodeWithText(label(before.index)).fetchSemanticsNode().positionInRoot.y, 1f)
    }

    @Test fun newOutputWhilePanelIsHiddenPreservesTheReaderAndOffersLatest() {
        session(); mount()
        val before = readHistory()
        close()
        append(AgentMessage("user", "New instruction while hidden"))
        reopen()
        assertEquals(before.anchorKey, position()!!.anchorKey)
        assertEquals(before.offset, position()!!.offset)
        compose.onNode(hasText("new activit", substring = true)).assertExists().performClick()
        compose.waitUntil(5_000) { compose.waitForIdle(); position()?.let { it.following && it.unread == 0 && it.index > before.index } == true }
        compose.onNodeWithText("New instruction while hidden").assertIsDisplayed()
    }

    @Test fun outputAppendsDoNotPullAReaderAwayFromHistoryButTailReadersFollow() {
        session(); mount()
        val before = readHistory()
        append(AgentMessage("user", "Background progress"))
        compose.waitForIdle()
        assertEquals(before.anchorKey, position()!!.anchorKey)
        assertEquals(before.offset, position()!!.offset)
        compose.onNode(hasText("new activit", substring = true)).performClick()
        compose.waitUntil(5_000) { compose.waitForIdle(); position()?.following == true }
        append(AgentMessage("user", "Latest result"))
        compose.waitForIdle()
        compose.onNodeWithText("Latest result").assertIsDisplayed()
        assertTrue(position()!!.following)
    }

    @Test fun eachSavedSessionKeepsItsOwnReadingPosition() {
        val first = session("First"); mount(); val firstPosition = readHistory(15)
        val second = runBlocking { agent.sessions.create("Second").copy(messages = (0..79).map { AgentMessage("user", "Other ${label(it)}") }) }
        compose.runOnIdle { host.runtimeScope.launch { agent.sessions.save(second); agent.loadSession(second) } }
        compose.waitUntil(5_000) { compose.waitForIdle(); agent.currentSessionId == second.id && position() != null }
        val secondPosition = readHistory(35)
        compose.runOnIdle { host.runtimeScope.launch { agent.loadSession(first) } }
        compose.waitUntil(5_000) { compose.waitForIdle(); agent.currentSessionId == first.id && position()?.anchorKey == firstPosition.anchorKey }
        assertEquals(firstPosition.offset, position()!!.offset)
        compose.onNodeWithText(label(firstPosition.index)).assertExists()
        compose.runOnIdle { host.runtimeScope.launch { agent.loadSession(second) } }
        compose.waitUntil(5_000) { compose.waitForIdle(); agent.currentSessionId == second.id && position()?.anchorKey == secondPosition.anchorKey }
        assertEquals(secondPosition.offset, position()!!.offset)
    }

    @Test fun aTallLastMessageIsNotMistakenForTheBottom() {
        session(messages = listOf(AgentMessage("user", (0..99).joinToString("\n") { "Tall paragraph $it" })))
        mount()
        compose.onNodeWithContentDescription("Agent conversation").performScrollToIndex(0)
        compose.waitUntil(5_000) { compose.waitForIdle(); position()?.following == false }
        val before = position()!!
        append(AgentMessage("user", "New tail after tall content"))
        compose.waitForIdle()
        assertEquals(before.anchorKey, position()!!.anchorKey)
        assertEquals(before.offset, position()!!.offset)
        assertFalse(position()!!.following)
        close(); reopen()
        assertEquals(before.offset, position()!!.offset)
        assertFalse(position()!!.following)
    }

    @Test fun typingALongDraftDoesNotMoveAHistoryReader() {
        session(); mount(); val before = readHistory()
        compose.onNode(hasSetTextAction()).performTextInput((1..30).joinToString("\n") { "Draft instruction $it" })
        assertEquals(before.anchorKey, position()!!.anchorKey)
        assertEquals(before.offset, position()!!.offset)
        compose.runOnIdle { height = 520.dp }
        assertEquals(before.anchorKey, position()!!.anchorKey)
        close(); reopen()
        assertEquals(before.anchorKey, position()!!.anchorKey)
        assertEquals(before.offset, position()!!.offset)
    }

    @Test fun updatingVisibleMessageTextDoesNotChangeItsAnchorIdentity() {
        session(); mount(); val before = readHistory()
        val field = AgentService::class.java.getDeclaredField("_messages").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val flow = field.get(agent) as MutableStateFlow<List<AgentMessage>>
        compose.runOnIdle { flow.value = flow.value.toMutableList().also { it[before.index] = it[before.index].copy(content = it[before.index].content + "\nMore detail below the same source instruction.") } }
        assertEquals(before.anchorKey, position()!!.anchorKey)
        assertEquals(before.offset, position()!!.offset)
        close(); reopen()
        assertEquals(before.anchorKey, position()!!.anchorKey)
        assertEquals(before.offset, position()!!.offset)
    }

    @Test fun externalAndBuiltinAgentPositionsAreKeptSeparately() {
        session(); mount(); val builtin = readHistory(15)
        close()
        val spec = WorkspaceAgentPluginCatalog.entries.first()
        val backend = object : WorkspaceAgentChatBackend {
            override suspend fun runTurn(agentId: String, prompt: String, mode: WorkspaceAgentMode, resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit) =
                WorkspaceAgentTurnResult(agentId, "external-scroll-fixture", (1..160).joinToString("\n") { "External source review paragraph $it." }, "end_turn", emptyList(), emptyList())
            override suspend fun prepareSession(agentId: String, resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit) = error("Unused fixture operation")
            override suspend fun selectModel(agentId: String, expectedSessionId: String, modelId: String) = error("Unused fixture operation")
            override suspend fun newSession(agentId: String, onEvent: (WorkspaceAgentEvent) -> Unit) = error("Unused fixture operation")
            override suspend fun cancel(agentId: String, expectedSessionId: String?) = Unit
        }
        compose.runOnIdle { assertTrue(ui.externalController.submit(backend, spec, "Review external source", WorkspaceAgentMode.EDIT)) }
        try { compose.waitUntil(5_000) { compose.waitForIdle(); !ui.externalController.busy && ui.externalController[spec.id].sessionId != null } } catch (failure: Throwable) { throw AssertionError("External fixture state: ${ui.externalController[spec.id]}, busy=${ui.externalController.busy}", failure) }
        compose.runOnIdle { ui.backendId.value = spec.id }
        reopen()
        val external = readHistory(0)
        close()
        compose.runOnIdle { ui.backendId.value = "droide" }
        reopen()
        assertEquals(builtin.anchorKey, position()!!.anchorKey)
        assertEquals(builtin.offset, position()!!.offset)
        close()
        compose.runOnIdle { ui.backendId.value = spec.id }
        reopen()
        assertEquals(external.anchorKey, position()!!.anchorKey)
        assertEquals(external.offset, position()!!.offset)
    }

    @Test fun userDisclosureKeepsTheTailAnchorThroughAnimationAndNewOutput() {
        val part = AgentStreamPart("last-tool", 1, AgentStreamPartKind.TOOL, AgentStreamPartState.COMPLETED,
            title = "read file", toolName = "read_file", output = "One\nTwo\nThree\nFour", detail = "Succeeded")
        session(messages = (0..25).map { AgentMessage("user", label(it)) } + AgentMessage("assistant", "", streamPart = part))
        mount(); compose.mainClock.autoAdvance = false
        fun top() = compose.onNodeWithText("Succeeded · Read").fetchSemanticsNode().boundsInRoot.top
        val before = top()
        compose.onNodeWithContentDescription("Expand").performClick()
        for (time in listOf(32L, 48L, 140L)) {
            compose.mainClock.advanceTimeBy(time)
            assertEquals(before, top(), 1f)
        }
        assertFalse(position()!!.following)
        assertFalse(position()!!.following)
        append(AgentMessage("assistant", "New result while details are open"))
        compose.mainClock.advanceTimeByFrame()
        assertEquals(before, top(), 1f)
        compose.onNodeWithContentDescription("Collapse").performClick()
        for (time in listOf(32L, 48L, 140L)) {
            compose.mainClock.advanceTimeBy(time)
            assertEquals(before, top(), 1f)
        }
        compose.onNodeWithContentDescription("Scroll to latest activity").performClick()
        compose.mainClock.advanceTimeBy(240)
        assertEquals(0, position()!!.disclosureSpacePx)
        assertTrue(position()!!.following)
        compose.onNodeWithText("New result while details are open").assertIsDisplayed()
    }

    @Test fun accessibilityScrollReleasesTheDisclosureReadingHold() {
        val part = AgentStreamPart("last-tool", 1, AgentStreamPartKind.TOOL, AgentStreamPartState.COMPLETED,
            title = "read file", toolName = "read_file", output = "One\nTwo\nThree", detail = "Succeeded")
        session(messages = (0..25).map { AgentMessage("user", label(it)) } + AgentMessage("assistant", "", streamPart = part))
        mount()
        compose.onNodeWithContentDescription("Expand").performClick()
        compose.waitForIdle()
        assertFalse(position()!!.following)
        compose.onNodeWithContentDescription("Agent conversation").performScrollToIndex(10)
        compose.waitForIdle()
        assertEquals(0, position()!!.disclosureSpacePx)
        val before = position()!!
        append(AgentMessage("assistant", "New output while reading earlier messages"))
        compose.waitForIdle()
        assertEquals(before.anchorKey, position()!!.anchorKey)
        assertEquals(before.offset, position()!!.offset)
    }

    @Test fun disclosureReadingPositionSurvivesPageReentry() {
        val part = AgentStreamPart("last-tool", 1, AgentStreamPartKind.TOOL, AgentStreamPartState.COMPLETED,
            title = "read file", toolName = "read_file", output = "One\nTwo\nThree", detail = "Succeeded")
        session(messages = (0..25).map { AgentMessage("user", label(it)) } + AgentMessage("assistant", "", streamPart = part))
        mount()
        compose.onNodeWithContentDescription("Expand").performClick()
        compose.onNodeWithContentDescription("Collapse").performClick()
        compose.waitForIdle()
        val before = position()!!
        close(); reopen()
        val after = position()!!
        assertEquals(before.anchorKey, after.anchorKey)
        assertEquals(before.offset, after.offset)
        assertEquals(before.disclosureSpacePx, after.disclosureSpacePx)
        compose.onNodeWithContentDescription("Scroll to latest activity").performClick()
        compose.waitForIdle()
        assertEquals(0, position()!!.disclosureSpacePx)
    }

    @Test fun dragInteractionsReleaseTheHoldWithoutRestoringTailFollowing() {
        lateinit var viewport: AgentChatViewport
        val activity = AgentChatActivity(40, 0, 0, 0, 0, 0, 0, null, false)
        val conversation = AgentChatConversationKey("droide", "drag-fixture")
        val keys = (0..39).map { "item:$it" }
        compose.setContent { DroideTheme {
            viewport = rememberAgentChatViewport(ui, conversation, null, keys, activity, false)
            val density = LocalDensity.current
            val space = with(density) { viewport.disclosureSpacePx.toDp() }
            SideEffect { viewport.measuredDisclosurePadding(0, with(density) { space.roundToPx() }) }
            LazyColumn(Modifier.width(360.dp).height(500.dp).testTag("drag-list"), state = viewport.listState,
                contentPadding = PaddingValues(bottom = space)) {
                items(40, key = { keys[it] }) { Text("Row $it", Modifier.height(60.dp)) }
            }
        } }
        compose.waitUntil(5_000) { compose.waitForIdle(); viewport.ready }
        compose.runOnIdle { viewport.beginDisclosure() }
        assertFalse(viewport.following)
        val events = viewport.listState.interactionSource as MutableInteractionSource
        val start = DragInteraction.Start()
        compose.runOnIdle { assertTrue(events.tryEmit(start)) }
        compose.waitUntil(5_000) { compose.waitForIdle(); viewport.dragging }
        assertEquals(0, viewport.disclosureSpacePx)
        assertFalse(viewport.following)
        compose.onNodeWithTag("drag-list").performScrollToIndex(10)
        compose.runOnIdle { assertTrue(events.tryEmit(DragInteraction.Stop(start))) }
        compose.waitUntil(5_000) { compose.waitForIdle(); !viewport.dragging }
        assertFalse(viewport.following)
        assertEquals(10, viewport.position()!!.index)
    }

}
