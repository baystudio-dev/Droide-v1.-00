package com.baystudio.droide.ui

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp", shadows = [AgentSelectionMagnifierShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentMarkdownPreviewInteractionTest {
    @get:Rule val compose = createComposeRule()

    private fun mount(density: Density? = null, content: @Composable () -> Unit) {
        compose.setContent { DroideTheme {
            CompositionLocalProvider(LocalDensity provides (density ?: LocalDensity.current)) {
                Box(Modifier.width(360.dp).height(640.dp)) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        AgentMessageInteractionHost("bounded-fixture", content)
                    }
                }
            }
        } }
    }

    private fun assertFiniteBody() {
        val size = compose.onNodeWithContentDescription("Agent response preview").fetchSemanticsNode().size
        assertTrue(size.height <= MAX_AGENT_PREVIEW_LAYOUT_PX)
    }

    @Test fun shortNormalResponseIsNotClippedOrMarkedAsShortened() {
        mount { AgentMarkdownContent("A short response 😄.") }
        compose.onNodeWithText("A short response 😄.").assertIsDisplayed()
        compose.onNodeWithText("Response preview shortened", substring = true).assertDoesNotExist()
        assertFiniteBody()
    }

    @Test fun newlineHeavyLiveAndFinalRenderingIsFiniteAndStillCopiesFullSource() {
        val source = "- x\n".repeat(MAX_AGENT_MARKDOWN_PREVIEW_LINES + 1) + "FULL_SOURCE_SENTINEL"
        var completed by mutableStateOf(false)
        mount(Density(3f, 2f)) { AgentStreamingMarkdownContent(source, "large-lines", completed) }
        assertFiniteBody()
        compose.onNodeWithText("Response preview shortened", substring = true).assertExists()
        compose.onNodeWithContentDescription("Copy response").performScrollTo().performClick()
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(source, clipboard.primaryClip!!.getItemAt(0).text.toString())
        compose.runOnIdle { completed = true }
        assertFiniteBody()
        compose.onNodeWithContentDescription("Copy response").performScrollTo().performClick()
        assertEquals(source, clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test fun expandedLongCodeHasFinitePixelDimensionsAtLargeDensityAndFontScale() {
        val code = "x".repeat(60_000)
        val source = "```text\n$code\n```"
        mount(Density(3f, 2f)) { AgentMarkdownContent(source) }
        compose.onNodeWithText("Show code preview").performScrollTo().performClick()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(code).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertTrue(layout.size.width <= MAX_AGENT_PREVIEW_LAYOUT_PX)
        assertTrue(layout.size.height <= MAX_AGENT_CODE_PREVIEW_HEIGHT_PX)
        assertTrue(layout.multiParagraph.width <= MAX_AGENT_PREVIEW_LAYOUT_PX)
        assertTrue(layout.lineCount <= MAX_AGENT_MARKDOWN_PREVIEW_LINES)
        assertFiniteBody()
        compose.onNodeWithText("Response preview shortened", substring = true).assertExists()
        compose.onNodeWithContentDescription("Copy response").performScrollTo().performClick()
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(source, clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test fun oversizedClipboardCopyLeavesThePreviousClipboardIntact() {
        val context = RuntimeEnvironment.getApplication()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertNull(copyAgentText(context, "previous clipboard", "test"))
        val source = "x".repeat(MAX_AGENT_CLIPBOARD_TEXT_CHARS + 1)
        val failure = copyAgentText(context, source, "test")
        assertTrue(failure.orEmpty().contains("too large"))
        assertEquals("previous clipboard", clipboard.primaryClip!!.getItemAt(0).text.toString())
        val selection = AgentMessageSelection.fromMarkdown(source)
        assertTrue(selection.sourceMode)
        assertSame(source, selection.text)
        assertTrue(selection.pages.indices.all { selection.page(it).length <= AgentMessageSelection.PAGE_CHARS })
    }

    @Test fun shortenedFinalRenderingStillCopiesAndSelectsTheCompleteResponse() {
        val source = "```text\n" + "x".repeat(MAX_AGENT_MARKDOWN_PREVIEW_CHARS + 1) + "\n```\n\nFULL_SOURCE_SENTINEL_😄"
        compose.setContent { DroideTheme {
            Box(Modifier.width(360.dp).height(640.dp)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    AgentMessageInteractionHost("bounded-final") { AgentMarkdownContent(source) }
                }
            }
        } }
        compose.onNodeWithText("FULL_SOURCE_SENTINEL_😄").assertDoesNotExist()
        compose.onNodeWithContentDescription("Copy code preview").assertExists()
        compose.onNodeWithContentDescription("Copy code").assertDoesNotExist()
        compose.onNodeWithText("Response preview shortened", substring = true).assertExists()
        compose.onNodeWithContentDescription("Copy response").performScrollTo().performClick()
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(source, clipboard.primaryClip!!.getItemAt(0).text.toString())
        compose.onNodeWithContentDescription("Select response text").performScrollTo().performClick()
        fun selectedView(): TextView? = WindowInspector.getGlobalWindowViews()
            .firstNotNullOfOrNull { it.findViewWithTag<TextView>(AGENT_SELECTION_TEXT_TAG) }
        compose.waitUntil(5_000) { compose.waitForIdle(); selectedView() != null }
        val selection = AgentMessageSelection.fromMarkdown(source)
        repeat(selection.pages.size - 1) { compose.onNodeWithText("Next").performClick() }
        compose.waitForIdle()
        assertTrue(selectedView()!!.text.toString().endsWith("FULL_SOURCE_SENTINEL_😄"))
        compose.onNodeWithContentDescription("Close text selection").performClick()
    }
}
