package com.baystudio.droide.ui

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baystudio.droide.DroideApplication
import com.baystudio.droide.MainActivity
import com.baystudio.droide.core.*
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentChatScrollDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app = ApplicationProvider.getApplicationContext<Context>() as DroideApplication
    private val root = Files.createTempDirectory(app.cacheDir.toPath(), "droide-chat-scroll-").toFile()
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ui = AgentWorkspaceUiState(runtimeScope, app)
    private val terminal = object : ITerminalSession {
        override val output = MutableStateFlow("")
        override fun start() = Unit
        override fun send(cmd: String) = error("Commands are disabled in the scroll fixture")
        override suspend fun execOnce(cmd: String, timeoutMs: Long) = error("Commands are disabled in the scroll fixture")
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long) = error("Commands are disabled in the scroll fixture")
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun destroy() = Unit
    }
    private val agent = AgentService(FileRepository(root), terminal, GitManager(root),
        maxSubagentDepth = 0, appContext = app, runtimeScope = runtimeScope)
    private var visible by mutableStateOf(true)
    private fun position() = ui.chatPosition(AgentChatConversationKey("droide", agent.currentSessionId))

    @After fun release() {
        compose.runOnUiThread { visible = false }
        agent.shutdown()
        ui.close()
        runtimeScope.cancel()
        PathSecurity.deleteTreeNoFollow(root)
    }

    @Test fun fingerScrollPreservesTheReaderDuringOutputAndWhenReturningFromEditor() {
        runBlocking {
            val saved = agent.sessions.create("Device scroll history").copy(
                messages = (0..79).map { AgentMessage("user", "Source instruction $it") })
            agent.sessions.save(saved)
            agent.loadSession(saved)
        }
        compose.runOnUiThread {
            compose.activity.setContent {
                DroideTheme { Box(Modifier.fillMaxSize()) {
                    if (visible) AgentPane(agent, ui, app.workspaceHost.coordinator.providerConnections,
                        app.workspaceHost.coordinator.localLlamaAgentModels, onClose = { visible = false })
                    else Text("Editor remains available")
                } }
            }
        }
        compose.waitUntil(5_000) { compose.mainClock.advanceTimeByFrame(); position() != null }
        compose.onNodeWithContentDescription("Agent conversation").performTouchInput {
            down(center)
            moveTo(center + Offset(0f, height / 3f), delayMillis = 200)
            moveTo(center + Offset(0f, height / 3f), delayMillis = 500)
            advanceEventTime(16)
            up()
        }
        compose.waitUntil(5_000) { compose.mainClock.advanceTimeByFrame(); position()?.following == false }
        compose.waitForIdle()
        val before = position()!!
        val field = AgentService::class.java.getDeclaredField("_messages").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val messages = field.get(agent) as MutableStateFlow<List<AgentMessage>>
        compose.runOnIdle { messages.value = messages.value + AgentMessage("user", "Result while reading history") }
        assertEquals(before.anchorKey, position()!!.anchorKey)
        assertEquals(before.offset, position()!!.offset)
        compose.onNode(hasContentDescription("Close ", substring = true) and hasClickAction()).performClick()
        compose.onNodeWithText("Editor remains available").assertExists()
        compose.runOnIdle { visible = true }
        compose.waitForIdle()
        assertEquals(before.anchorKey, position()!!.anchorKey)
        assertEquals(before.offset, position()!!.offset)
        assertFalse(position()!!.following)
    }
}
