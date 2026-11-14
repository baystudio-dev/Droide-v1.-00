package com.baystudio.droide.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.AgentMessage
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real transcript/live renderer paths with <400 chars each; no database, backend, or process. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentCliPreviewTruthfulnessR30Test {
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val clipboard get() = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private var previousClip: ClipData? = null
    private var compositionHost: AbstractComposeView? = null
    private val notice = "Response preview shortened. Select response text to inspect the available text; Copy response is available within clipboard limits."

    @After fun cleanup() {
        try {
            compose.runOnUiThread { compositionHost?.disposeComposition() }
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            previousClip?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip()
        }
    }

    private fun mount(content: @Composable () -> Unit) {
        previousClip = clipboard.primaryClip
        compose.setContent {
            val view = LocalView.current
            SideEffect {
                compositionHost = generateSequence(view as View?) { it.parent as? View }
                    .filterIsInstance<AbstractComposeView>().firstOrNull()
            }
            DroideTheme { Box(Modifier.size(360.dp, 640.dp)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    AgentMessageInteractionHost("r30-available-text", content)
                }
            } }
        }
    }

    private fun assertTruthfulNoticeAndAvailableCopy(available: String) {
        compose.onNodeWithText(notice).assertExists()
        compose.onNodeWithText("full response", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Select response text").assertExists()
        compose.onNodeWithContentDescription("Copy response").performScrollTo().performClick()
        assertEquals(available, clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test(timeout = 20_000) fun loadedTranscriptFragmentDoesNotPromiseAnEntireResponse() {
        val available = "Loaded page fragment\n" + "\n".repeat(MAX_AGENT_MARKDOWN_PREVIEW_LINES) + "AVAILABLE_PAGE_END"
        assertTrue(available.length < 400)
        assertTrue(agentMarkdownPreview(available).shortened)
        mount { AgentTranscriptItem(AgentMessage("assistant", available), false, File("unused-r30-media-root")) }
        assertTruthfulNoticeAndAvailableCopy(available)
    }

    @Test(timeout = 20_000) fun externalLivePreviewDoesNotPromiseEarlierUnloadedOutput() {
        val available = "Live tail fragment\n" + "\n".repeat(MAX_AGENT_MARKDOWN_PREVIEW_LINES) + "AVAILABLE_LIVE_END"
        assertTrue(available.length < 400)
        assertTrue(agentMarkdownPreview(available).shortened)
        mount { ExternalAgentLiveStream("Codex", ExternalAgentConversationState(liveText = available), "r30-live-tail") }
        assertTruthfulNoticeAndAvailableCopy(available)
    }
}
