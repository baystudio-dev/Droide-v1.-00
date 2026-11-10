package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Authored by static review only; these tests have not been compiled or executed. */

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w640dp-h640dp")
class WorkspaceApprovalAttentionTest {
    @get:Rule val compose = createComposeRule()

    private fun route(
        lastActive: WorkspaceApprovalSurface = WorkspaceApprovalSurface.OTHER,
        chatVisible: Boolean = false,
        chatFullscreen: Boolean = false,
        browserVisible: Boolean = false,
        browserExclusive: Boolean = false,
        otherUiActive: Boolean = false,
    ): WorkspaceApprovalSurface = workspaceApprovalSurface(
        lastActive, chatVisible, chatFullscreen, browserVisible, browserExclusive, otherUiActive,
    )

    @Test fun noVisibleSurfaceUsesTheIndicatorRegardlessOfStaleAttention() {
        WorkspaceApprovalSurface.entries.forEach { stale ->
            assertEquals(WorkspaceApprovalSurface.OTHER, route(lastActive = stale))
        }
    }

    @Test fun aWideAgentPaneBeingVisibleDoesNotMakeItActive() {
        assertEquals(WorkspaceApprovalSurface.OTHER, route(chatVisible = true))
        assertEquals(WorkspaceApprovalSurface.OTHER,
            route(chatVisible = true, browserVisible = true))
    }

    @Test fun activeBrowserWinsOverAMerelyVisibleWideAgentPane() {
        assertEquals(WorkspaceApprovalSurface.BROWSER, route(
            lastActive = WorkspaceApprovalSurface.BROWSER,
            chatVisible = true,
            browserVisible = true,
        ))
    }

    @Test fun activeAgentWinsOverAMerelyVisibleBrowser() {
        assertEquals(WorkspaceApprovalSurface.AGENT, route(
            lastActive = WorkspaceApprovalSurface.AGENT,
            chatVisible = true,
            browserVisible = true,
        ))
    }

    @Test fun returningToEditorOrTerminalKeepsWideChatNonmodal() {
        listOf(false, true).forEach { browserVisible ->
            assertEquals(WorkspaceApprovalSurface.OTHER, route(
                lastActive = WorkspaceApprovalSurface.OTHER,
                chatVisible = true,
                browserVisible = browserVisible,
            ))
        }
    }

    @Test fun aHiddenOrFullyClippedChatCannotReuseItsPreviousAgentAttention() {
        assertEquals(WorkspaceApprovalSurface.OTHER, route(lastActive = WorkspaceApprovalSurface.AGENT))
        assertEquals(WorkspaceApprovalSurface.OTHER, route(
            lastActive = WorkspaceApprovalSurface.AGENT,
            browserVisible = true,
        ))
    }

    @Test fun aHiddenBrowserCannotReuseItsPreviousBrowserAttention() {
        assertEquals(WorkspaceApprovalSurface.OTHER, route(lastActive = WorkspaceApprovalSurface.BROWSER))
        assertEquals(WorkspaceApprovalSurface.OTHER, route(
            lastActive = WorkspaceApprovalSurface.BROWSER,
            chatVisible = true,
        ))
    }

    @Test fun aVisibleFullscreenAgentIsActiveEvenAfterAnotherPaneWasLastUsed() {
        WorkspaceApprovalSurface.entries.forEach { stale ->
            assertEquals(WorkspaceApprovalSurface.AGENT, route(
                lastActive = stale,
                chatVisible = true,
                chatFullscreen = true,
            ))
        }
    }

    @Test fun aFullscreenFlagCannotActivateAnInvisibleAgentPane() {
        assertEquals(WorkspaceApprovalSurface.OTHER, route(chatFullscreen = true))
        assertEquals(WorkspaceApprovalSurface.BROWSER, route(
            lastActive = WorkspaceApprovalSurface.BROWSER,
            chatFullscreen = true,
            browserVisible = true,
        ))
    }

    @Test fun anExclusiveBrowserRequiresAnActuallyVisibleBrowserViewport() {
        WorkspaceApprovalSurface.entries.forEach { stale ->
            assertEquals(WorkspaceApprovalSurface.OTHER,
                route(lastActive = stale, browserExclusive = true))
            assertEquals(WorkspaceApprovalSurface.BROWSER,
                route(lastActive = stale, browserVisible = true, browserExclusive = true))
        }
    }

    @Test fun aModalOrTerminalTypingSurfaceOverridesBothVisiblePanes() {
        WorkspaceApprovalSurface.entries.forEach { stale ->
            listOf(false, true).forEach { fullscreen ->
                assertEquals(WorkspaceApprovalSurface.OTHER, route(
                    lastActive = stale,
                    chatVisible = true,
                    chatFullscreen = fullscreen,
                    browserVisible = true,
                    browserExclusive = true,
                    otherUiActive = true,
                ))
            }
        }
    }

    @Test fun revealingABrowserWithoutUserAttentionDoesNotActivateTheDock() {
        assertEquals(WorkspaceApprovalSurface.OTHER, route(browserVisible = false))
        assertEquals(WorkspaceApprovalSurface.OTHER, route(browserVisible = true))
        assertEquals(WorkspaceApprovalSurface.AGENT, route(
            lastActive = WorkspaceApprovalSurface.AGENT,
            chatVisible = true,
            browserVisible = true,
        ))
    }

    @Test fun mountingAnAttentionObserverDoesNotAnnounceActivity() {
        val active = mutableListOf<WorkspaceApprovalSurface>()
        compose.setContent {
            Box(Modifier.size(120.dp).workspaceApprovalAttention(WorkspaceApprovalSurface.AGENT) { active += it }
                .testTag("observed-pane"))
        }
        compose.onNodeWithTag("observed-pane").assertExists()
        compose.runOnIdle { assertTrue(active.isEmpty()) }
    }

    @Test fun pointerDownIsObservedBeforeTheChildConsumesItWithoutConsumingTheChildInput() {
        val calls = mutableListOf<String>()
        compose.setContent {
            Box(Modifier.size(120.dp)
                .workspaceApprovalAttention(WorkspaceApprovalSurface.BROWSER) { calls += "active:$it" }
                .testTag("observed-pane")) {
                Box(Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.filter { it.pressed && !it.previousPressed }.forEach { change ->
                                calls += "child-consumed:${change.isConsumed}"
                                change.consume()
                            }
                        }
                    }
                })
            }
        }
        compose.onNodeWithTag("observed-pane").performTouchInput { down(center) }
        compose.runOnIdle {
            assertEquals(listOf("active:BROWSER", "child-consumed:false"), calls)
        }
        compose.onNodeWithTag("observed-pane").performTouchInput { up() }
    }

    @Test fun movementAndReleaseDoNotCreateExtraAttentionChanges() {
        val active = mutableListOf<WorkspaceApprovalSurface>()
        compose.setContent {
            Box(Modifier.size(120.dp).workspaceApprovalAttention(WorkspaceApprovalSurface.OTHER) { active += it }
                .testTag("observed-pane"))
        }
        compose.onNodeWithTag("observed-pane").performTouchInput { down(center) }
        compose.runOnIdle { assertEquals(listOf(WorkspaceApprovalSurface.OTHER), active) }
        compose.onNodeWithTag("observed-pane").performTouchInput {
            moveBy(Offset(10f, 5f))
            moveBy(Offset(-5f, 10f))
            up()
        }
        compose.runOnIdle { assertEquals(listOf(WorkspaceApprovalSurface.OTHER), active) }
    }

    @Test fun observingPointerInputPreservesTheDescendantClickAction() {
        val active = mutableListOf<WorkspaceApprovalSurface>()
        var clicks = 0
        compose.setContent {
            Box(Modifier.size(120.dp).workspaceApprovalAttention(WorkspaceApprovalSurface.AGENT) { active += it }) {
                Box(Modifier.fillMaxSize().testTag("child-action").clickable { clicks++ })
            }
        }
        compose.onNodeWithTag("child-action").performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(1, clicks)
            assertTrue(active.contains(WorkspaceApprovalSurface.AGENT))
        }
    }

    @Test fun recompositionUsesTheLatestCallbackWithoutRequiringPointerHandlerReplacement() {
        val calls = mutableListOf<String>()
        var callbackVersion by mutableStateOf("first")
        compose.setContent {
            val versionAtComposition = callbackVersion
            Box(Modifier.size(120.dp)
                .workspaceApprovalAttention(WorkspaceApprovalSurface.BROWSER) { calls += versionAtComposition }
                .testTag("observed-pane"))
        }
        compose.onNodeWithTag("observed-pane").performTouchInput { click(center) }
        compose.runOnIdle { callbackVersion = "second" }
        compose.onNodeWithTag("observed-pane").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(listOf("first", "second"), calls) }
    }

    @Test fun changingTheSurfaceDoesNotClaimAttentionUntilNewInputArrives() {
        val active = mutableListOf<WorkspaceApprovalSurface>()
        var surface by mutableStateOf(WorkspaceApprovalSurface.AGENT)
        compose.setContent {
            Box(Modifier.size(120.dp).workspaceApprovalAttention(surface) { active += it }
                .testTag("observed-pane"))
        }
        compose.onNodeWithTag("observed-pane").performTouchInput { click(center) }
        compose.runOnIdle { surface = WorkspaceApprovalSurface.BROWSER }
        compose.runOnIdle { assertEquals(listOf(WorkspaceApprovalSurface.AGENT), active) }
        compose.onNodeWithTag("observed-pane").performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(listOf(WorkspaceApprovalSurface.AGENT, WorkspaceApprovalSurface.BROWSER), active)
        }
    }

    @Test fun descendantKeyboardFocusActivatesTheWholePaneAndCanMoveBetweenPanes() {
        val agentFocus = FocusRequester()
        val otherFocus = FocusRequester()
        val active = mutableListOf<WorkspaceApprovalSurface>()
        compose.setContent {
            Column {
                Box(Modifier.size(120.dp).workspaceApprovalAttention(WorkspaceApprovalSurface.AGENT) { active += it }) {
                    Box(Modifier.size(48.dp).focusRequester(agentFocus).focusable().testTag("agent-focus"))
                }
                Box(Modifier.size(120.dp).workspaceApprovalAttention(WorkspaceApprovalSurface.OTHER) { active += it }) {
                    Box(Modifier.size(48.dp).focusRequester(otherFocus).focusable().testTag("other-focus"))
                }
            }
        }
        compose.runOnIdle { agentFocus.requestFocus() }
        compose.onNodeWithTag("agent-focus").assertIsFocused()
        compose.runOnIdle { assertEquals(WorkspaceApprovalSurface.AGENT, active.last()); otherFocus.requestFocus() }
        compose.onNodeWithTag("other-focus").assertIsFocused()
        compose.runOnIdle { assertEquals(WorkspaceApprovalSurface.OTHER, active.last()); agentFocus.requestFocus() }
        compose.onNodeWithTag("agent-focus").assertIsFocused()
        compose.runOnIdle { assertEquals(WorkspaceApprovalSurface.AGENT, active.last()) }
    }

    @Test fun losingDescendantFocusDoesNotReassertThePreviousPane() {
        val agentFocus = FocusRequester()
        val otherFocus = FocusRequester()
        val active = mutableListOf<WorkspaceApprovalSurface>()
        compose.setContent {
            Column {
                Box(Modifier.size(120.dp).workspaceApprovalAttention(WorkspaceApprovalSurface.AGENT) { active += it }) {
                    Box(Modifier.size(48.dp).focusRequester(agentFocus).focusable())
                }
                Box(Modifier.size(48.dp).focusRequester(otherFocus).focusable().testTag("outside-focus"))
            }
        }
        compose.runOnIdle { agentFocus.requestFocus() }
        compose.runOnIdle { assertEquals(listOf(WorkspaceApprovalSurface.AGENT), active); otherFocus.requestFocus() }
        compose.onNodeWithTag("outside-focus").assertIsFocused()
        compose.runOnIdle { assertEquals(listOf(WorkspaceApprovalSurface.AGENT), active) }
    }

    @Test fun mountingAndTouchingAnotherPaneDoesNotRequestOrClearExistingFocusOrControlTheKeyboard() {
        val editorFocus = FocusRequester()
        val active = mutableListOf<WorkspaceApprovalSurface>()
        var showObserved by mutableStateOf(false)
        var keyboardShowCalls = 0
        var keyboardHideCalls = 0
        val keyboard = object : SoftwareKeyboardController {
            override fun show() { keyboardShowCalls++ }
            override fun hide() { keyboardHideCalls++ }
        }
        compose.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                Column {
                    Box(Modifier.size(48.dp).focusRequester(editorFocus).focusable().testTag("editor-focus"))
                    if (showObserved) {
                        Box(Modifier.size(120.dp)
                            .workspaceApprovalAttention(WorkspaceApprovalSurface.BROWSER) { active += it }
                            .testTag("observed-pane"))
                    }
                }
            }
        }
        compose.runOnIdle { editorFocus.requestFocus() }
        compose.onNodeWithTag("editor-focus").assertIsFocused()
        compose.runOnIdle { showObserved = true }
        compose.onNodeWithTag("observed-pane").assertExists()
        compose.onNodeWithTag("editor-focus").assertIsFocused()
        compose.runOnIdle { assertTrue(active.isEmpty()) }
        compose.onNodeWithTag("observed-pane").performTouchInput { click(center) }
        compose.onNodeWithTag("editor-focus").assertIsFocused()
        compose.runOnIdle {
            assertEquals(listOf(WorkspaceApprovalSurface.BROWSER), active)
            assertEquals(0, keyboardShowCalls)
            assertEquals(0, keyboardHideCalls)
        }
    }

    @Test fun aRetainedBrowserCallbackUsesTheCurrentPresentedGuardForFocusChanges() {
        val browserFocus = FocusRequester()
        val otherFocus = FocusRequester()
        var presented by mutableStateOf(true)
        val active = mutableListOf<WorkspaceApprovalSurface>()
        compose.setContent {
            // The real workspace also disables native hidden browser focus. This fixture verifies that an
            // obsolete callback cannot bypass its additional presented guard.
            val presentedAtComposition = presented
            Column {
                Box(Modifier.size(120.dp).workspaceApprovalAttention(WorkspaceApprovalSurface.BROWSER) {
                    if (presentedAtComposition) active += it
                }) {
                    Box(Modifier.size(48.dp).focusRequester(browserFocus).focusable())
                }
                Box(Modifier.size(48.dp).focusRequester(otherFocus).focusable())
            }
        }
        compose.runOnIdle { browserFocus.requestFocus() }
        compose.runOnIdle {
            assertEquals(listOf(WorkspaceApprovalSurface.BROWSER), active)
            otherFocus.requestFocus()
            presented = false
        }
        compose.runOnIdle { browserFocus.requestFocus() }
        compose.runOnIdle { assertEquals(listOf(WorkspaceApprovalSurface.BROWSER), active) }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun attentionObservationDoesNotConsumeDescendantKeyboardInput() {
        val focus = FocusRequester()
        var keyDowns = 0
        val active = mutableListOf<WorkspaceApprovalSurface>()
        compose.setContent {
            Box(Modifier.size(120.dp).workspaceApprovalAttention(WorkspaceApprovalSurface.AGENT) { active += it }) {
                Box(Modifier.size(48.dp).focusRequester(focus).onKeyEvent {
                    if (it.type == KeyEventType.KeyDown) keyDowns++
                    true
                }.focusable().testTag("keyboard-target"))
            }
        }
        compose.runOnIdle { focus.requestFocus() }
        compose.onNodeWithTag("keyboard-target").performKeyInput { pressKey(Key.A) }
        compose.runOnIdle {
            assertEquals(1, keyDowns)
            assertEquals(WorkspaceApprovalSurface.AGENT, active.last())
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun typingInAnAlreadyFocusedAgentDescendantRestoresAttentionAfterAPointerOnlyPaneChange() {
        val agentFocus = FocusRequester()
        val active = mutableListOf<WorkspaceApprovalSurface>()
        var deliveredKeyDowns = 0
        compose.setContent {
            Column {
                Box(Modifier.size(120.dp)
                    .workspaceApprovalAttention(WorkspaceApprovalSurface.AGENT) { active += it }) {
                    Box(Modifier.size(48.dp).focusRequester(agentFocus).onKeyEvent {
                        if (it.type == KeyEventType.KeyDown) deliveredKeyDowns++
                        true
                    }.focusable().testTag("agent-keyboard-target"))
                }
                Box(Modifier.size(120.dp)
                    .workspaceApprovalAttention(WorkspaceApprovalSurface.OTHER) { active += it }
                    .testTag("nonfocusable-other-pane"))
            }
        }
        compose.runOnIdle { agentFocus.requestFocus() }
        compose.onNodeWithTag("agent-keyboard-target").assertIsFocused()
        compose.runOnIdle { assertEquals(WorkspaceApprovalSurface.AGENT, active.last()) }
        compose.onNodeWithTag("nonfocusable-other-pane").performTouchInput { click(center) }
        compose.onNodeWithTag("agent-keyboard-target").assertIsFocused()
        compose.runOnIdle { assertEquals(WorkspaceApprovalSurface.OTHER, active.last()) }

        // There is no second focus transition. The key-down itself must restore attention, and the
        // ordinary child handler must still receive exactly the original event.
        compose.onNodeWithTag("agent-keyboard-target").performKeyInput { pressKey(Key.A) }
        compose.onNodeWithTag("agent-keyboard-target").assertIsFocused()
        compose.runOnIdle {
            assertEquals(WorkspaceApprovalSurface.AGENT, active.last())
            assertEquals(1, deliveredKeyDowns)
        }
    }
}
