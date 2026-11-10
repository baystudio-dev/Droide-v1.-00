package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.AgentStreamPart
import com.baystudio.droide.core.AgentStreamPartKind
import com.baystudio.droide.core.AgentStreamPartState
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp")
class AgentActionPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val diff = """--- a/src/Main.kt
        |+++ b/src/Main.kt
        |@@ -1 +1 @@
        |-val old = 1
        |+val revised = 2
        |""".trimMargin()

    @Test fun diffPreviewInsideScrollingPermissionParentIsSafe() {
        compose.setContent {
            DroideTheme {
                Column(Modifier.height(200.dp).verticalScroll(rememberScrollState())) {
                    DiffText(diff)
                }
            }
        }
        compose.onNodeWithText("src/Main.kt").assertIsDisplayed()
        compose.onNodeWithText("+val revised = 2").assertIsDisplayed()
    }

    @Test fun expandedAgentEditResultRendersActualDiff() {
        compose.setContent {
            DroideTheme {
                AgentCompletedToolPart("edit_file", "{\"path\":\"src/Main.kt\"}", diff)
            }
        }
        compose.onNodeWithText("Succeeded · Edit").performClick()
        compose.onNodeWithText("+val revised = 2").assertIsDisplayed()
    }

    @Test fun streamedLongCommandArgumentsRenderWithoutParserFailure() {
        val arguments = buildJsonObject { put("command", "printf '%s' '" + "x".repeat(32_000) + "'") }.toString()
        compose.setContent {
            DroideTheme {
                AgentStreamPartItem(
                    AgentStreamPart("command", 1, AgentStreamPartKind.TOOL, AgentStreamPartState.RUNNING,
                        toolName = "run_command", input = arguments), false,
                )
            }
        }
        compose.onNodeWithText("Running · Terminal").assertIsDisplayed()
    }

    @Test fun malformedDiffHunkDoesNotCrashPermissionPreview() {
        val malformed = "--- a/Main.kt\n+++ b/Main.kt\n@@ -99999999999999999999 +1 @@\n-old\n+new"
        compose.setContent {
            DroideTheme {
                Column(Modifier.height(200.dp).verticalScroll(rememberScrollState())) { DiffText(malformed) }
            }
        }
        compose.onNodeWithText("Main.kt").assertIsDisplayed()
    }

    @Test fun standaloneDiffCanScrollBeyondInlinePreviewLimit() {
        val large = "--- a/Main.kt\n+++ b/Main.kt\n@@ -0,0 +1,300 @@\n" + (0..299).joinToString("\n") { "+line $it" }
        compose.setContent {
            DroideTheme { Box(Modifier.height(160.dp)) { DiffText(large, scrollable = true) } }
        }
        compose.onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToIndex(301)
        compose.onNodeWithText("+line 299").assertIsDisplayed()
    }

    @Test fun unusuallyWideDiffLinesRemainSafeToMeasure() {
        val line = "x".repeat(32_000)
        compose.setContent {
            DroideTheme {
                Column(Modifier.height(200.dp).verticalScroll(rememberScrollState())) {
                    DiffText("--- a/Main.kt\n+++ b/Main.kt\n@@ -0,0 +1 @@\n+$line")
                }
            }
        }
        compose.onNodeWithText("Main.kt").assertIsDisplayed()
        compose.onNodeWithText("+$line").assertIsDisplayed()
    }
}
