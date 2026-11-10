package com.baystudio.droide.ui

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.text.Selection
import android.text.Spannable
import android.view.inspector.WindowInspector
import android.widget.TextView
import android.widget.Magnifier
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation

/** Actual response controls, text layout, native selection and clipboard, without network/CLI calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp", shadows = [AgentSelectionMagnifierShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentMessageInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()
    private fun clipboard(): String = (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .primaryClip!!.getItemAt(0).text.toString()
    private fun mount(content: @Composable () -> Unit) {
        compose.setContent { DroideTheme {
            Box(Modifier.width(360.dp).height(640.dp)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .semantics { contentDescription = "Response fixture" }) { content() }
            }
        } }
    }
    private fun selectView(): TextView? = WindowInspector.getGlobalWindowViews()
        .firstNotNullOfOrNull { it.findViewWithTag<TextView>(AGENT_SELECTION_TEXT_TAG) }
    private fun waitForSelection(): TextView {
        compose.onNodeWithContentDescription("Close text selection").assertExists()
        try {
            compose.waitUntil(5_000) { compose.waitForIdle(); selectView() != null }
        } catch (failure: Throwable) {
            println(compose.onAllNodes(isRoot()).printToString(maxDepth = 8))
            println("Window roots: ${WindowInspector.getGlobalWindowViews().size}")
            throw failure
        }
        return selectView()!!
    }

    @Test fun copyResponseAndCopyCodeUseFullSourceDespiteCompactedCodePreview() {
        val code = "complete code " + "x".repeat(20_000)
        val markdown = "**Result**\n\n```text\n$code\n```\n\n[Docs](https://example.test/docs)"
        mount { AgentMessageInteractionHost("copy") { AgentMarkdownContent(markdown) } }
        compose.onNodeWithContentDescription("Copy code").performClick()
        assertEquals(code, clipboard())
        compose.onNodeWithContentDescription("Copy response").performScrollTo().performClick()
        assertEquals(markdown, clipboard())
        assertEquals("Copied", compose.onNodeWithContentDescription("Copy response").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
    }

    @Test fun builtInLiveResponseCopiesTheCurrentTextAndKeepsSelectionStableWhenItFinishes() {
        var text by mutableStateOf("First paragraph.\n\nSecond paragraph.")
        var completed by mutableStateOf(false)
        mount { AgentMessageInteractionHost("native-stream") {
            AgentStreamingMarkdownContent(text, "stable-run", completed)
        } }
        compose.onNodeWithContentDescription("Copy response").performClick()
        assertEquals(text, clipboard())
        compose.onNodeWithContentDescription("Select response text").performClick()
        val view = waitForSelection()
        assertTrue(view.isTextSelectable)
        assertFalse("Selection must not summon an editing keyboard", view.onCheckIsTextEditor())
        compose.runOnIdle { Selection.setSelection(view.text as Spannable, 6, 28) }
        val selected = view.text.subSequence(6, 28).toString()
        compose.runOnIdle { text += " More streaming text."; completed = true }
        compose.waitForIdle()
        assertSame(view, selectView())
        assertEquals("First paragraph.\n\nSecond paragraph.", view.text.toString())
        assertEquals(6, view.selectionStart)
        assertEquals(28, view.selectionEnd)
        compose.onNodeWithText("Copy selection").performClick()
        assertEquals(selected, clipboard())
        compose.onNodeWithContentDescription("Close text selection").performClick()
        compose.onNodeWithContentDescription("Copy response").performScrollTo().performClick()
        assertEquals(text, clipboard())
        assertFalse(view.isAttachedToWindow)
    }

    @Test fun cliLiveSelectionSurvivesReplacementByThePersistedAnswerAndSessionChangeDismissesIt() {
        var state by mutableStateOf(ExternalAgentConversationState(liveText = "CLI response to select."))
        var live by mutableStateOf(true)
        var conversation by mutableStateOf("cli-history-one")
        mount { AgentMessageInteractionHost(conversation) {
            if (live) ExternalAgentLiveStream("Codex", state, "cli-run-1") else AgentMarkdownContent(state.liveText)
        } }
        compose.onNodeWithContentDescription("Copy response").performClick()
        assertEquals(state.liveText, clipboard())
        compose.onNodeWithContentDescription("Select response text").performClick()
        val view = waitForSelection()
        compose.runOnIdle { Selection.setSelection(view.text as Spannable, 4, 12) }
        compose.runOnIdle { state = state.copy(liveText = state.liveText + " Completed."); live = false }
        compose.waitForIdle()
        assertSame(view, selectView())
        assertEquals("CLI response to select.", view.text.toString())
        assertEquals(4, view.selectionStart)
        assertEquals(12, view.selectionEnd)
        compose.onNodeWithText("Copy selection").performClick()
        assertEquals("response", clipboard())
        compose.runOnIdle { conversation = "cli-history-two" }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Close text selection").assertDoesNotExist()
        assertFalse(view.isAttachedToWindow)
    }

    @Test fun aTapOnTheRenderedLinkLaunchesTheExactUrl() {
        val url = "https://example.test/auth?state=A%2BB%3D&redirect_uri=http%3A%2F%2Flocalhost"
        mount { AgentMessageInteractionHost("link") { AgentMarkdownContent("**[Docs]($url)**") } }
        compose.onNodeWithText("Docs", useUnmergedTree = true).performTouchInput { click(center) }
        assertEquals(url, shadowOf(app).nextStartedActivity.dataString)
    }

    @Test fun longPressingALinkSelectsItsLabelWithoutOpeningTheBrowser() {
        val toolbar = SelectionTestToolbar()
        mount { CompositionLocalProvider(LocalTextToolbar provides toolbar) {
            AgentMessageInteractionHost("link-selection") {
                AgentMarkdownContent("[Documentation](https://example.test/docs)")
            }
        } }
        compose.onNodeWithText("Documentation", useUnmergedTree = true).performTouchInput { longClick(center) }
        compose.waitForIdle()
        assertNotNull(toolbar.copy)
        assertNull(shadowOf(app).nextStartedActivity)
        compose.runOnIdle { toolbar.copy!!.invoke() }
        assertEquals("Documentation", clipboard())
        // A native semantic click has no new pointer sequence; the gesture gate must reset.
        val nativeLink = compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasText("Documentation")), useUnmergedTree = true)
        nativeLink.assertCountEquals(1)
        nativeLink[0].performClick()
        assertEquals("https://example.test/docs", shadowOf(app).nextStartedActivity.dataString)
    }

    @Test fun tappingTheBlankEndOfAWrappedLinkParagraphDoesNotOpenOrCrash() {
        val markdown = "Before ".repeat(35) + "[Docs](https://example.test/docs)"
        val displayed = "Before ".repeat(35) + "Docs"
        mount { AgentMessageInteractionHost("link-padding") { AgentMarkdownContent(markdown) } }
        compose.onNodeWithText(displayed).performTouchInput {
            click(androidx.compose.ui.geometry.Offset(width - 1f, height - 2f))
        }
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun changingSelectionPageCopiesTheNewRangeAndReleasesThePreviousView() {
        val code = "A".repeat(24_000) + "B".repeat(24_000) + "C".repeat(1_000)
        mount { AgentMessageInteractionHost("pages") { AgentMarkdownContent("```text\n$code\n```") } }
        compose.onNodeWithContentDescription("Select response text").performScrollTo().performClick()
        val first = waitForSelection()
        assertEquals(24_000, first.text.length)
        compose.onNodeWithText("Next").performClick()
        compose.waitForIdle()
        val second = selectView()!!
        assertNotSame(first, second)
        assertFalse(first.isAttachedToWindow)
        assertEquals("B".repeat(24_000), second.text.toString())
        compose.runOnIdle { Selection.setSelection(second.text as Spannable, 1, 6) }
        compose.onNodeWithText("Copy selection").performClick()
        assertEquals("BBBBB", clipboard())
        compose.onNodeWithText("Next").performClick()
        compose.waitForIdle()
        assertEquals("C".repeat(1_000), selectView()!!.text.toString())
        compose.onNodeWithText("Next").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Close text selection").performClick()
        compose.waitForIdle()
        assertNull(selectView())
    }

    @Test fun oneLongPressDragSelectsAcrossParagraphsWithoutCopyingActionLabels() {
        val toolbar = SelectionTestToolbar()
        mount { CompositionLocalProvider(LocalTextToolbar provides toolbar) {
            AgentMessageInteractionHost("selection") { AgentMarkdownContent("Alpha first paragraph.\n\nBeta second paragraph.") }
        } }
        fun glyph(text: String, offset: Int): androidx.compose.ui.geometry.Offset {
            val node = compose.onNodeWithText(text)
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return node.fetchSemanticsNode().boundsInRoot.topLeft + layouts.single().getBoundingBox(offset).center
        }
        val first = glyph("Alpha first paragraph.", 1)
        val last = glyph("Beta second paragraph.", 10)
        val fixture = compose.onNodeWithContentDescription("Response fixture")
        val origin = fixture.fetchSemanticsNode().boundsInRoot.topLeft
        fixture.performTouchInput {
            down(first - origin)
            advanceEventTime(700)
            moveTo(last - origin, delayMillis = 100)
            up()
        }
        compose.waitForIdle()
        assertNotNull("Long press must offer the Android copy action", toolbar.copy)
        compose.runOnIdle { toolbar.copy!!.invoke() }
        val selected = clipboard()
        assertTrue(selected, selected.contains("first paragraph."))
        assertTrue(selected, selected.contains("Beta"))
        assertFalse(selected.contains("Copy response"))
        assertFalse(selected.contains("Select response text"))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test @Config(qualifiers = "w720dp-h360dp-land")
    fun landscapeSelectionRemainsScrollableAndControlsRemainAvailable() {
        val text = (1..120).joinToString("\n") { "Line $it for selection" }
        mount { AgentMessageInteractionHost("landscape") { AgentMarkdownContent(text) } }
        compose.onNodeWithContentDescription("Select response text").performScrollTo().performClick()
        val view = waitForSelection()
        assertTrue(view.height > view.parent.let { it as android.widget.ScrollView }.height)
        compose.runOnIdle { Selection.setSelection(view.text as Spannable, 0, 6) }
        compose.onNodeWithText("Copy selection").assertIsDisplayed().performClick()
        assertEquals("Line 1", clipboard())
        compose.onNodeWithContentDescription("Close text selection").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertFalse(view.isAttachedToWindow)
    }
}

@Implements(Magnifier::class)
class AgentSelectionMagnifierShadow {
    @Implementation fun show(sourceCenterX: Float, sourceCenterY: Float) = Unit
    @Implementation fun show(sourceCenterX: Float, sourceCenterY: Float, magnifierCenterX: Float, magnifierCenterY: Float) = Unit
    @Implementation fun update() = Unit
    @Implementation fun dismiss() = Unit
}

private class SelectionTestToolbar : TextToolbar {
    private var currentStatus = TextToolbarStatus.Hidden
    override val status get() = currentStatus
    var copy: (() -> Unit)? = null
    override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
        currentStatus = TextToolbarStatus.Shown
        copy = onCopyRequested
    }
    override fun hide() { currentStatus = TextToolbarStatus.Hidden }
}
