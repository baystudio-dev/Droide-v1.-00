package com.baystudio.droide.ui

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Authored R58 interaction coverage. Requires the normal Compose/Robolectric toolchain. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp", shadows = [AgentSelectionMagnifierShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentPublicTextHierarchyR58Test {
    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()
    private fun mount(content: @Composable () -> Unit) {
        compose.setContent { DroideTheme {
            Column(Modifier.width(360.dp).height(640.dp).verticalScroll(rememberScrollState())) {
                AgentPublicTextInteractionHost("r58-conversation", content)
            }
        } }
    }
    private fun clipboard(): String = (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .primaryClip!!.getItemAt(0).text.toString()
    private fun update(text: String, state: AgentStreamPartState = AgentStreamPartState.COMPLETED) = AgentStreamPart(
        id = "live-public-part", step = 1, kind = AgentStreamPartKind.TEXT, state = state, text = text,
        textPresentation = AgentTextPresentation.UPDATE, presentationId = "public-persisted-id",
    )

    @Test fun completedUpdateHasAccessibleDisclosureAndNoHiddenCodeTree() {
        val source = "Checked the project.\n\n```text\nexact code\n```"
        mount { AgentStreamPartItem(update(source), showDetails = false) }
        compose.onNodeWithContentDescription("Show update").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Copy code").assertDoesNotExist()
        compose.onNodeWithContentDescription("Copy response").performClick()
        assertEquals(source, clipboard())
        compose.onNodeWithContentDescription("Show update").performClick()
        compose.onNodeWithContentDescription("Copy code").assertExists().performClick()
        assertEquals("exact code", clipboard())
        compose.onNodeWithContentDescription("Hide update").performClick()
        compose.onNodeWithContentDescription("Copy code").assertDoesNotExist()
        compose.onAllNodesWithContentDescription("Copy response").assertCountEquals(1)
    }

    @Test fun runningUpdateIsOpenAndExplicitExpansionSurvivesCompletion() {
        var state by mutableStateOf(AgentStreamPartState.RUNNING)
        var source by mutableStateOf("```text\nstream content\n```\n\n")
        mount { AgentStreamPartItem(update(source, state), showDetails = false) }
        compose.onNodeWithContentDescription("Update in progress").assertExists()
        compose.onNodeWithContentDescription("Copy code").assertExists()
        compose.runOnIdle { state = AgentStreamPartState.COMPLETED }
        compose.onNodeWithContentDescription("Copy code").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show update").performClick()
        compose.runOnIdle { state = AgentStreamPartState.RUNNING; source += "More public text." }
        compose.runOnIdle { state = AgentStreamPartState.COMPLETED }
        compose.onNodeWithContentDescription("Hide update").assertExists()
        compose.onNodeWithContentDescription("Copy response").performScrollTo().performClick()
        assertEquals(source, clipboard())
    }

    @Test fun expandedLiveUpdateSurvivesDifferentDurableItemKeyAndSelectionRemainsOpen() {
        val source = "```text\nselected update code\n```"
        var live by mutableStateOf(true)
        mount { key(live) {
            if (live) AgentStreamPartItem(update(source), showDetails = false)
            else AgentTranscriptItem(AgentMessage("assistant", source,
                textPresentation = AgentTextPresentation.UPDATE, presentationId = "public-persisted-id"), false, app.cacheDir)
        } }
        compose.onNodeWithContentDescription("Show update").performClick()
        compose.onNodeWithContentDescription("Select response text").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Close text selection").assertExists()
        compose.runOnIdle { live = false }
        compose.onNodeWithContentDescription("Close text selection").assertExists().performClick()
        compose.onNodeWithContentDescription("Hide update").assertExists()
        compose.onNodeWithContentDescription("Copy code").assertExists()
    }

    @Test fun typedPhasesKeepAnUpdateCompactAndOnlyTheCompletedAnswerPrimary() {
        val commentary = "Checked the files.\n\n"
        val answer = "The result is ready."
        val source = commentary + answer
        val spans = listOf(AgentPublicTextSpan(0, commentary.length, ProviderPublicTextPhase.COMMENTARY),
            AgentPublicTextSpan(commentary.length, source.length, ProviderPublicTextPhase.FINAL_ANSWER))
        var state by mutableStateOf(AgentStreamPartState.RUNNING)
        mount { AgentPublicTextContent(source, AgentTextPresentation.ANSWER, "mixed", spans, state, "mixed-live") }
        compose.onNodeWithContentDescription("Show update").assertExists()
        compose.onNodeWithText("Response").assertExists()
        compose.onNodeWithText("Answer").assertDoesNotExist()
        compose.runOnIdle { state = AgentStreamPartState.COMPLETED }
        compose.onNodeWithText("Answer").assertExists()
        compose.onNodeWithText(answer).assertExists()
        compose.onAllNodesWithContentDescription("Copy response").assertCountEquals(1)
        compose.onNodeWithContentDescription("Copy response").performClick()
        assertEquals(source, clipboard())
    }

    @Test fun errorAndCancellationNeverHideTextOrStatusBehindUpdateDisclosure() {
        var state by mutableStateOf(AgentStreamPartState.ERROR)
        val source = "```text\npartial public output\n```"
        mount { AgentStreamPartItem(update(source, state).copy(detail = "Permission was not granted."), false) }
        compose.onNodeWithText("Response").assertExists()
        compose.onNodeWithText("Response interrupted").assertExists()
        compose.onNodeWithText("Permission was not granted.").assertExists()
        compose.onNodeWithContentDescription("Show update").assertDoesNotExist()
        compose.onNodeWithContentDescription("Copy code").assertExists()
        compose.runOnIdle { state = AgentStreamPartState.CANCELLED }
        compose.onNodeWithText("Response stopped").assertExists()
        compose.onNodeWithText("Answer").assertDoesNotExist()
        compose.onNodeWithContentDescription("Copy code").assertExists()
    }

    @Test fun legacyTranscriptKeepsItsExistingHeaderAndFullMarkdown() {
        mount { AgentTranscriptItem(AgentMessage("assistant", "```text\nlegacy code\n```"), false, app.cacheDir) }
        compose.onNodeWithText("DROIDE").assertExists()
        compose.onNodeWithText("Update").assertDoesNotExist()
        compose.onNodeWithText("Answer").assertDoesNotExist()
        compose.onNodeWithText("Response").assertDoesNotExist()
        compose.onNodeWithContentDescription("Copy code").assertExists()
        compose.onNodeWithContentDescription("Copy response").assertExists()
    }

    @Test fun explicitPublicTextIsNotReinterpretedAsALegacyToolRecord() {
        val source = "TOOL|read_file|example.txt|This is quoted public output."
        mount { AgentTranscriptItem(AgentMessage("assistant", source,
            textPresentation = AgentTextPresentation.ANSWER, presentationId = "literal-tool-prefix"), false, app.cacheDir) }
        compose.onNodeWithText("Answer").assertExists()
        compose.onNodeWithText(source).assertExists()
        compose.onNodeWithContentDescription("Copy response").performClick()
        assertEquals(source, clipboard())
    }

    @Test fun invalidMetadataFallsBackToVisibleNeutralContent() {
        val source = "All original public text remains visible."
        mount { AgentPublicTextContent(source, AgentTextPresentation.UPDATE, "invalid",
            listOf(AgentPublicTextSpan(4, source.length, ProviderPublicTextPhase.COMMENTARY))) }
        compose.onNodeWithText("Response").assertExists()
        compose.onNodeWithText(source).assertExists()
        compose.onNodeWithContentDescription("Show update").assertDoesNotExist()
    }

    @Test fun wholeMessageTruncationLabelsCodePreviewButCopiesTheOriginalSource() {
        val source = "```text\n" + "x".repeat(MAX_AGENT_MARKDOWN_PREVIEW_CHARS + 50) + "\n```"
        mount { AgentPublicTextContent(source, AgentTextPresentation.ANSWER, "long-answer", emptyList()) }
        compose.onNodeWithContentDescription("Copy code preview").assertExists()
        compose.onNodeWithContentDescription("Copy code").assertDoesNotExist()
        compose.onNodeWithContentDescription("Copy response").performScrollTo().performClick()
        assertEquals(source, clipboard())
    }

    @Test fun answerAndExpandedUpdateUseReadableThemeText() {
        var presentation by mutableStateOf(AgentTextPresentation.ANSWER)
        val source = "Readable public message body."
        mount { AgentPublicTextContent(source, presentation, "contrast", emptyList()) }
        fun bodyLayout(): TextLayoutResult {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(source, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single()
        }
        val answer = bodyLayout().layoutInput.style
        assertEquals(DroideColors.Text, answer.color)
        compose.runOnIdle { presentation = AgentTextPresentation.UPDATE }
        compose.onNodeWithContentDescription("Show update").performClick()
        val update = bodyLayout().layoutInput.style
        assertEquals(DroideColors.Text, update.color)
        assertTrue(answer.fontSize.value > update.fontSize.value)
    }

    @Test fun answerUsesAvailableMessageWidthWithoutAnEnclosingBubbleInset() {
        val source = "Answer text stays aligned with the message area."
        mount { AgentPublicTextContent(source, AgentTextPresentation.ANSWER, "unboxed-answer", emptyList(),
            modifier = Modifier.fillMaxWidth().testTag("answer-message-area")) }
        val message = compose.onNodeWithTag("answer-message-area").fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithText(source, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("Answer must not reserve bubble padding", message.left, body.left, 0.5f)
        compose.onNodeWithContentDescription("Copy response").performClick()
        assertEquals(source, clipboard())
    }

}
