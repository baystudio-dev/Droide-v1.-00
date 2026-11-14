package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.baystudio.droide.core.DroideSession
import com.baystudio.droide.core.SessionManager
import com.baystudio.droide.core.AgentMessage
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val directory = Files.createTempDirectory("sessions-ui-").toFile()
    private val manager = SessionManager(directory)

    @After fun release() { directory.deleteRecursively() }

    private fun showDrawer(onPick: (DroideSession) -> Unit = {}) {
        // Keep the Android host at the device viewport while disk results change
        // the drawer's content size, as in IdeScreen's bounded screen host.
        compose.setContent {
            DroideTheme {
                Box(Modifier.fillMaxSize()) {
                    SessionDrawer(manager, onPick = onPick)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun awaitSessionUi(condition: () -> Boolean) {
        try {
            compose.waitUntil(5_000, condition)
        } catch (timeout: ComposeTimeoutException) {
            println("SESSION_LIST_STORAGE: ${runBlocking { manager.listSummaries() }}")
            val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
            println("SESSION_LIST_UI: ${runCatching {
                roots.fetchSemanticsNodes().indices.joinToString("\n") { roots[it].printToString() }
            }.getOrNull()}")
            throw timeout
        }
    }

    @Test fun emptyDrawerKeepsAVisibleViewportWhileSessionsLoadAsynchronously() {
        compose.setContent { DroideTheme { SessionDrawer(manager, onPick = {}) } }
        compose.waitForIdle()
        val viewport = compose.onNode(hasScrollAction()).getUnclippedBoundsInRoot()
        assertTrue("The list must retain width before disk results arrive", viewport.right.value > viewport.left.value)
        assertTrue("The list must retain height before disk results arrive", viewport.bottom.value > viewport.top.value)
        compose.onNodeWithText("No saved sessions").assertIsDisplayed()
    }

    @Test fun restoreFailureIsVisibleAndRefreshRemainsClickable() {
        runBlocking { manager.create("Saved session") }
        showDrawer { throw IOException("Cannot restore session") }
        awaitSessionUi { compose.onAllNodesWithText("Saved session").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Saved session").assertIsDisplayed().performClick()
        awaitSessionUi { compose.onAllNodesWithText("Cannot restore session").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Refresh sessions").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Cannot restore session").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Saved session").assertExists()
        assertEquals(1, runBlocking { manager.count() })
    }

    @Test fun forkAndDeleteUseRealSessionStorageWithoutClosingTheScreen() {
        runBlocking {
            val session = manager.create("Saved session")
            val messages = listOf(AgentMessage("user", "Hello"), AgentMessage("assistant", "Ready"))
            val history = messages.map { message -> buildJsonObject { put("role", message.role); put("content", message.content) } }
            manager.save(session.copy(messages = messages, apiHistory = history, forkHistory = history))
        }
        showDrawer()
        awaitSessionUi { compose.onAllNodesWithContentDescription("Fork").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithContentDescription("Fork").assertIsDisplayed().performClick()
        awaitSessionUi { compose.onAllNodesWithContentDescription("Delete").fetchSemanticsNodes().size == 2 }
        assertEquals(2, runBlocking { manager.count() })
        compose.onAllNodesWithContentDescription("Delete")[0].performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Delete").fetchSemanticsNodes().size == 1 }
        assertEquals(1, runBlocking { manager.count() })
        compose.onNodeWithContentDescription("Refresh sessions").assertIsEnabled()
    }

    @Test fun emptySessionForkExplainsWhyAndLeavesDeleteUsable() {
        runBlocking { manager.create("Empty session") }
        showDrawer()
        awaitSessionUi { compose.onAllNodesWithContentDescription("Fork").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithContentDescription("Fork").assertIsDisplayed().performClick()
        awaitSessionUi {
            compose.onAllNodesWithText("Cannot fork this session. It needs a complete conversation history.").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, runBlocking { manager.count() })
        compose.onNodeWithContentDescription("Delete").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Delete").fetchSemanticsNodes().isEmpty() }
        assertEquals(0, runBlocking { manager.count() })
        compose.onNodeWithContentDescription("Refresh sessions").assertIsEnabled()
    }
}
