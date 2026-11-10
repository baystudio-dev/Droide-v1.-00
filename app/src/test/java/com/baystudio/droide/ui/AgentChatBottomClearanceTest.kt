package com.baystudio.droide.ui

import org.junit.Before

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.baystudio.droide.DroideApplication
import com.baystudio.droide.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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
class AgentChatBottomClearanceTest {
    @Before
    fun openApplicationWork() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
    }

    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as DroideApplication
    private val host get() = app.workspaceHost
    private val root = Files.createTempDirectory("agent-chat-bottom-clearance-").toFile()
    private val ui by lazy { AgentWorkspaceUiState(host.runtimeScope, app) }
    private val agent by lazy { AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root),
        maxSubagentDepth = 0, appContext = app, runtimeScope = host.runtimeScope) }
    private var paneHeight by mutableStateOf(640.dp)
    private fun history(index: Int) = "Earlier instruction ${index.toString().padStart(3, '0')}"
    private fun position() = ui.chatPosition(AgentChatConversationKey("droide", agent.currentSessionId))

    @After fun cleanup() {
        agent.shutdown()
        ui.close()
        host.close()
        PathSecurity.deleteTreeNoFollow(root)
    }

    private fun mount(lastMessage: String = "Latest source review result", width: Dp = 360.dp) {
        runBlocking {
            val messages = (0..59).map { AgentMessage("user", history(it)) } + AgentMessage("assistant", lastMessage)
            val session = agent.sessions.create("Bottom clearance").copy(messages = messages)
            agent.sessions.save(session)
            agent.loadSession(session)
        }
        compose.setContent {
            DroideTheme {
                Box(Modifier.width(width).height(paneHeight)) {
                    AgentPane(agent, ui, host.coordinator.providerConnections, host.coordinator.localLlamaAgentModels)
                }
            }
        }
        compose.waitUntil(5_000) { compose.waitForIdle(); position() != null }
    }

    private fun append(content: String) {
        val field = AgentService::class.java.getDeclaredField("_messages").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val messages = field.get(agent) as MutableStateFlow<List<AgentMessage>>
        compose.runOnIdle { messages.value = messages.value + AgentMessage("assistant", content) }
        compose.waitForIdle()
    }

    private fun assertLatestClearance(lastParagraph: String) {
        compose.onNodeWithText(lastParagraph).assertIsDisplayed()
        val viewport = compose.onNodeWithContentDescription("Agent conversation").fetchSemanticsNode().boundsInRoot
        val messageBottom = compose.onNodeWithText(lastParagraph).fetchSemanticsNode().boundsInRoot.bottom
        val last = compose.onAllNodesWithContentDescription("Agent response actions").fetchSemanticsNodes()
            .map { it.boundsInRoot }
            .filter { it.top >= messageBottom && it.height > 0f }
            .minByOrNull { it.top }
            ?: error("Actions below the latest response are missing")
        val density = app.resources.displayMetrics.density
        val gap = viewport.bottom - last.bottom
        val expected = bottomTouchClearance((viewport.height / density).dp).value * density
        assertEquals("Latest response actions must leave the available-height touch clearance", expected, gap, 1f)
        assertTrue("Clearance must stay bounded in a small phone viewport: gap=$gap", gap <= 24f * density + 1f)
        assertTrue("Tail following must remain active", position()!!.following)
        assertEquals(0, position()!!.unread)
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithContentDescription("Send").assertIsDisplayed()
    }

    @Test fun portraitLatestMessageLeavesTouchClearanceAboveTheComposer() {
        mount()
        assertLatestClearance("Latest source review result")
    }

    @Test @Config(qualifiers = "w720dp-h360dp-land")
    fun landscapeLatestMessageKeepsClearanceWithoutAHighBlankStrip() {
        paneHeight = 360.dp
        mount(width = 720.dp)
        assertLatestClearance("Latest source review result")
    }

    @Test fun reducingAvailableHeightKeepsTheLatestOutputClearAndTheDraftAvailable() {
        mount()
        compose.onNode(hasSetTextAction()).performTextInput("Keep this unsent source review instruction")
        compose.runOnIdle { paneHeight = 300.dp }
        compose.waitForIdle()
        assertLatestClearance("Latest source review result")
        assertEquals("Keep this unsent source review instruction", ui.input.value)
        append("New output while the available pane is short")
        assertLatestClearance("New output while the available pane is short")
        assertEquals("Keep this unsent source review instruction", ui.input.value)
    }

    @Test fun resizingTheReadingViewportDoesNotPullHistoryToTheLatestOutput() {
        mount()
        compose.onNodeWithContentDescription("Agent conversation").performScrollToIndex(20)
        compose.waitUntil(5_000) { compose.waitForIdle(); position()?.let { it.index >= 20 && !it.following } == true }
        val before = position()!!
        compose.runOnIdle { paneHeight = 320.dp }
        compose.waitForIdle()
        append("Background output while reading an earlier instruction")
        val after = position()!!
        assertEquals(before.anchorKey, after.anchorKey)
        assertEquals(before.offset, after.offset)
        assertFalse(after.following)
        assertTrue("New output should offer Latest instead of moving the reader", after.unread > 0)
        compose.onNode(hasText("new activit", substring = true)).assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { compose.waitForIdle(); position()?.following == true }
        assertLatestClearance("Background output while reading an earlier instruction")
    }

    @Test fun aLastMessageTallerThanTheViewportStillExposesItsEndWithClearance() {
        val paragraphs = (0..79).joinToString("\n\n") { "Source review paragraph $it." }
        mount(paragraphs)
        assertLatestClearance("Source review paragraph 79.")
        compose.runOnIdle { paneHeight = 300.dp }
        compose.waitForIdle()
        assertLatestClearance("Source review paragraph 79.")
    }

    @Test fun frameSteppedKeyboardSizedResizeKeepsTranscriptWidthAndUnsentDraft() {
        val text = "A stable-width response should not wrap again when only the keyboard height changes. " +
            "Keep reading this result while the composer follows the available space."
        mount(text)
        compose.onNode(hasSetTextAction()).performTextInput("Unsent draft stays intact")
        val width = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.width
        repeat(2) {
            for (height in (640 downTo 300 step 20) + (300..640 step 20)) {
                compose.runOnIdle { paneHeight = height.dp }
                compose.waitForIdle()
                assertLatestClearance(text)
                assertEquals(width, compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.width, 1f)
                assertEquals("Unsent draft stays intact", ui.input.value)
            }
        }
    }

    @Test @Config(qualifiers = "w720dp-h360dp-land")
    fun shortLandscapeResizeAndOutputKeepComposerAndCompleteTailVisible() {
        paneHeight = 360.dp
        mount(width = 720.dp)
        compose.onNode(hasSetTextAction()).performTextInput("Landscape draft")
        for ((index, height) in listOf(320, 280, 240, 280, 320, 360).withIndex()) {
            compose.runOnIdle { paneHeight = height.dp }
            append("Landscape resize output $index at $height")
            assertLatestClearance("Landscape resize output $index at $height")
            assertEquals("Landscape draft", ui.input.value)
        }
    }

    @Test fun aClearanceChangeAtTheSameViewportHeightFollowsOnlyTheTailReader() {
        var padding by mutableStateOf(16.dp)
        lateinit var viewport: AgentChatViewport
        val conversation = AgentChatConversationKey("clearance-fixture", "same-height")
        val keys = (0..59).map { "clearance-row-$it" }
        val activity = AgentChatActivity(60, 0, 0, 0, 0, 0, 0, null, false)
        compose.setContent {
            DroideTheme {
                val current = rememberAgentChatViewport(ui, conversation, null, keys, activity, false)
                SideEffect { viewport = current }
                LazyColumn(
                    state = current.listState,
                    modifier = Modifier.width(360.dp).height(220.dp).semantics { contentDescription = "Padding-only conversation" },
                    contentPadding = PaddingValues(bottom = padding),
                ) {
                    itemsIndexed(keys, key = { _, key -> key }) { index, _ ->
                        Text("Clearance result $index", Modifier.height(30.dp))
                    }
                }
            }
        }
        compose.waitUntil(5_000) { compose.waitForIdle(); ui.chatPosition(conversation) != null }
        val initialHeight = viewport.listState.layoutInfo.viewportEndOffset - viewport.listState.layoutInfo.viewportStartOffset
        compose.runOnIdle { padding = 24.dp }
        compose.waitUntil(5_000) { compose.waitForIdle(); !viewport.listState.canScrollForward }
        assertEquals(initialHeight, viewport.listState.layoutInfo.viewportEndOffset - viewport.listState.layoutInfo.viewportStartOffset)
        val bounds = compose.onNodeWithContentDescription("Padding-only conversation").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithText("Clearance result 59").fetchSemanticsNode().boundsInRoot
        assertEquals(24f * app.resources.displayMetrics.density, bounds.bottom - last.bottom, 1f)
        assertTrue(viewport.following)

        compose.onNodeWithContentDescription("Padding-only conversation").performScrollToIndex(20)
        compose.waitUntil(5_000) { compose.waitForIdle(); !viewport.following }
        val before = viewport.position()!!
        compose.runOnIdle { padding = 8.dp }
        compose.waitForIdle()
        val after = viewport.position()!!
        assertEquals(before.anchorKey, after.anchorKey)
        assertEquals(before.offset, after.offset)
        assertFalse(viewport.following)
    }
}
