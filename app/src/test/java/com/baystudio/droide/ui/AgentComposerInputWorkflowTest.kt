package com.baystudio.droide.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.AgentMentionTarget
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentComposerInputWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val draft = mutableStateOf("")
    private var loads = 0
    private var commandCalls = 0
    private var selected: AgentComposerCommand? = null

    @Before fun before() {
    }

    private fun mount(limit: Int = 64_000) {
        compose.setContent {
            DroideTheme { Surface(color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.BottomCenter) {
                    AgentComposerInput(
                        value = draft.value, catalogKey = "workspace", commands = AgentComposerCommand.entries,
                        loadMentions = { loads++; listOf(AgentMentionTarget("@plugin/github", "GitHub", "Installed plugin"), AgentMentionTarget("@skill/review-code", "Review code", "Skill")) },
                        onValueChange = { text -> if (text.length > limit) false else { draft.value = text; true } },
                        onCommandSelected = { selected = AgentComposerShortcuts.command(draft.value, false); commandCalls++; draft.value = "" },
                        onFocusChanged = {}, placeholder = "Ask Droide…", maxHeight = 96.dp, dense = true,
                        trailingControl = {},
                    )
                }
            } }
        }
    }

    @Test fun slashOpensFeatureWithOneMetadataRead() {
        mount()
        compose.onNodeWithText("/mcp").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput("/mc")
        compose.onNodeWithText("/mcp").performClick()
        compose.runOnIdle {
            assertEquals(AgentComposerCommand.MCP, selected)
            assertEquals(1, commandCalls)
            assertEquals(1, loads)
            assertEquals("", draft.value)
        }
        compose.onNodeWithText("/mcp").assertDoesNotExist()
    }

    @Test fun mentionPreservesDraftAndKeyboardFocusWithoutSubmitting() {
        draft.value = "Use "
        mount()
        compose.onNode(hasSetTextAction()).performTextInput("@git")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("GitHub", substring = false).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("GitHub", substring = false).performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertEquals("Use @plugin/github ", draft.value)
            assertEquals(0, commandCalls)
            assertEquals(1, loads)
        }
    }

    @Test fun rejectedCompletionPreservesTheOriginalDraft() {
        mount(limit = 5)
        compose.onNode(hasSetTextAction()).performTextInput("@git")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("GitHub", substring = false).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("GitHub", substring = false).performClick()
        compose.runOnIdle { assertEquals("@git", draft.value); assertEquals(0, commandCalls) }
    }
    @Test fun selectingSlashSkillKeepsFocusAndNeverSendsTheMessage() {
        mount()
        compose.onNode(hasSetTextAction()).performTextInput("/rev")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Review code", substring = false).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Review code", substring = false).performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle { assertEquals("/skill/review-code ", draft.value); assertEquals(0, commandCalls); assertEquals(1, loads) }
    }
}
