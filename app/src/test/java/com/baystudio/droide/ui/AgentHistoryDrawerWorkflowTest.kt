package com.baystudio.droide.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/** Actual native-rendered history sheet + SQLite; semantic actions, not physical-touch certification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AgentHistoryDrawerWorkflowTest {
    @get:Rule val compose = createComposeRule()
    private val root = Files.createTempDirectory("history-drawer-ui-").toFile()
    private val manager by lazy { SessionManager(root) }
    private val history by lazy { SqlitePluginConversationHistory(root) }
    private var selectionEnabled by mutableStateOf(true)
    private var picked = ""
    private var pickedBackend = ""

    @After fun cleanup() {
        runBlocking { history.close() }
        assertTrue(PathSecurity.deleteTreeNoFollow(root))
    }

    private fun seed(agentId: String, title: String): String = runBlocking {
        val entry = history.beginTurn(null, agentId, title, WorkspaceAgentMode.EDIT, "remote-$agentId")
        history.finish(entry.summary.id, "Saved answer", emptyList(), PluginHistoryStatus.COMPLETED).summary.id
    }

    private fun mount(backend: String = "droide") {
        compose.setContent { DroideTheme {
            Box(Modifier.width(360.dp).height(780.dp)) {
                AgentHistoryDrawer(manager, history, backend, selectionEnabled,
                    onPickDroide = { picked = it.id; pickedBackend = "droide" },
                    onPickPlugin = { picked = it.id; pickedBackend = it.agentId }, onDismiss = {})
            }
        } }
    }
    private fun click(text: String) = compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick) { it() }
    private fun waitFor(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun tabsDispatchToTheCorrectBackendAndKeepBuiltinAndPluginRowsSeparate() {
        val builtin = runBlocking { manager.create("Builtin conversation") }
        val plugin = seed("opencode", "OpenCode conversation")
        mount()
        waitFor("Builtin conversation")
        compose.onNodeWithText("OpenCode conversation").assertDoesNotExist()
        click("Plugin History")
        waitFor("OpenCode conversation")
        compose.onNodeWithText("Builtin conversation").assertDoesNotExist()
        click("OpenCode conversation")
        assertEquals(plugin, picked)
        assertEquals("opencode", pickedBackend)
        click("Droide History")
        waitFor("Builtin conversation")
        click("Builtin conversation")
        compose.waitUntil(5_000) { picked == builtin.id }
        assertEquals("droide", pickedBackend)
    }

    @Test fun pluginFilterAndSearchReadOnlyLocalSavedHistory() {
        seed("opencode", "OpenCode task")
        seed("gemini", "Gemini target")
        seed("gemini", "Another Gemini task")
        mount("opencode")
        waitFor("OpenCode task")
        compose.onNodeWithText("Gemini target").assertDoesNotExist()
        click("OpenCode")
        click("Gemini CLI")
        waitFor("Gemini target")
        compose.onNodeWithText("OpenCode task").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("target")) }
        waitFor("Gemini target")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Another Gemini task").fetchSemanticsNodes().isEmpty() }
        assertEquals("", picked)
    }

    @Test fun runningWorkKeepsHistoryBrowsableButConversationSwitchingDisabled() {
        seed("codex", "Saved Codex work")
        selectionEnabled = false
        mount("codex")
        waitFor("Saved Codex work")
        compose.onNodeWithText("Saved Codex work").assertIsNotEnabled()
        compose.onNodeWithText("A task is running. You can browse history; finish it or use Stop before opening another conversation.").assertExists()
        compose.onNodeWithContentDescription("Delete plugin conversation Saved Codex work").assertIsNotEnabled()
        assertEquals("", picked)
        click("Droide History")
        compose.onNodeWithText("Droide History").assertIsSelected()
    }

    @Test fun deletingALocalTranscriptRequiresConfirmationAndCancelPreservesIt() {
        val id = seed("opencode", "Keep until reviewed")
        mount("opencode")
        waitFor("Keep until reviewed")
        compose.onNodeWithContentDescription("Delete plugin conversation Keep until reviewed").performSemanticsAction(SemanticsActions.OnClick) { it() }
        waitFor("Delete saved conversation?")
        click("Cancel")
        assertNotNull(runBlocking { history.load(id) })
        compose.onNodeWithContentDescription("Delete plugin conversation Keep until reviewed").performSemanticsAction(SemanticsActions.OnClick) { it() }
        click("Delete")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Keep until reviewed").fetchSemanticsNodes().isEmpty() }
        assertNull(runBlocking { history.load(id) })
    }

    @Test fun olderConversationPagesExposeSavedChatsOutsideTheFirstPage() {
        repeat(45) { seed("gemini", "Saved task $it") }
        mount("gemini")
        waitFor("Saved task 44")
        compose.onNode(hasScrollToIndexAction()).performSemanticsAction(SemanticsActions.ScrollToIndex) { it(39) }
        waitFor("Older conversations")
        click("Older conversations")
        waitFor("Latest conversations")
        compose.onNode(hasScrollToIndexAction()).performSemanticsAction(SemanticsActions.ScrollToIndex) { it(44) }
        waitFor("Saved task 0")
        click("Saved task 0")
        assertEquals("gemini", pickedBackend)
        assertEquals("Saved task 0", runBlocking { history.load(picked) }!!.summary.title)
    }
}
