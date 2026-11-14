package com.baystudio.droide.ui

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.AgentRequestProvenance
import com.baystudio.droide.core.ApprovalDecision
import com.baystudio.droide.core.ApprovalManager
import com.baystudio.droide.core.ApprovalRequest
import com.baystudio.droide.core.ApprovalResolution
import com.baystudio.droide.core.BrowserApprovalContext
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * R35 synthetic host regression candidate. Narrowly reuses WorkspaceApprovalHostTest's
 * real manager, reserved footer, editable surface, and modal-dismiss setup. It does not
 * construct an AgentPane/service, WebView, Explorer backend, provider, terminal or file.
 * The tab/file controls below are inert workspace interaction targets, not production
 * Explorer end-to-end coverage. Real Compose pointer events exercise the real host/sheet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkspaceApprovalCollapseR35Test {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val approvals = ApprovalManager()
    private val caller = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var compositionHost: AbstractComposeView? = null
    private var activeSurface by mutableStateOf(WorkspaceApprovalSurface.OTHER)
    private var browserVisible by mutableStateOf(false)
    private var selectedTab by mutableStateOf("Editor")
    private var editorText by mutableStateOf("Current editor draft")
    private var density = 1f
    private var touchSlop = 0f
    private var browserOpenCalls = 0
    private var workspaceBackCalls = 0
    private var explorerOpenCalls = 0
    private val keyboard = RecordingKeyboard()

    private class RecordingKeyboard : SoftwareKeyboardController {
        var hideCalls = 0
        override fun show() = Unit
        override fun hide() { hideCalls++ }
    }

    private fun request(kind: String = "run_command", browser: Boolean = false): Deferred<ApprovalDecision> = caller.async {
        val outputOnly = kind == "browser_unverified_output"
        val action = if (outputOnly) "webfetch" else kind
        val resource = if (browser) {
            if (kind == "browser_local") "http://127.0.0.1:8080/fixture" else "https://example.test/fixture"
        } else "fixture-only:$kind"
        approvals.requestApproval(
            kind = kind, summary = "Synthetic $kind permission",
            detail = if (kind in setOf("write_file", "edit_file", "apply_patch", "lsp_edit"))
                "--- a/main.kt\n+++ b/main.kt\n@@ -1 +1 @@\n-old\n+new" else "Inert approval payload",
            action = action, resource = resource,
            permissionScope = if (kind.startsWith("agent_")) "external-acp-agent" else "build",
            provenance = AgentRequestProvenance.primary("r35-fixture"),
            approvalSessionId = if (outputOnly) "" else "r35-fixture",
            sessionGrantAction = if (outputOnly) "" else action,
            sessionGrantResource = if (outputOnly) "" else resource,
            browserContext = if (browser) BrowserApprovalContext("read", resource,
                reason = "Synthetic browser review",
                outputScope = if (outputOnly) "One synthetic output" else null) else null,
        )
    }

    private fun selectTab(tab: String) {
        selectedTab = tab
        browserVisible = tab == "Browser"
        activeSurface = when (tab) {
            "Browser" -> WorkspaceApprovalSurface.BROWSER
            "Agent" -> WorkspaceApprovalSurface.AGENT
            else -> WorkspaceApprovalSurface.OTHER
        }
    }

    private fun mount() {
        compose.setContent {
            val view = LocalView.current
            val currentDensity = LocalDensity.current
            val configuration = LocalViewConfiguration.current
            SideEffect {
                compositionHost = generateSequence(view as View?) { it.parent as? View }
                    .filterIsInstance<AbstractComposeView>().firstOrNull()
                density = currentDensity.density
                touchSlop = configuration.touchSlop
            }
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                DroideTheme {
                    BackHandler { workspaceBackCalls++ }
                    Column(Modifier.width(360.dp).height(640.dp).testTag("r35-workspace-root")) {
                        Row(Modifier.fillMaxWidth()) {
                            listOf("Explorer", "Editor", "Agent", "Browser").forEach { tab ->
                                TextButton(onClick = { selectTab(tab) }, modifier = Modifier.weight(1f)) { Text(tab) }
                            }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth().testTag("workspace-surface")) {
                            when (selectedTab) {
                                "Explorer" -> TextButton(onClick = {
                                    explorerOpenCalls++
                                    selectTab("Editor")
                                }) { Text("Open main.kt") }
                                "Editor" -> BasicTextField(editorText, onValueChange = { editorText = it },
                                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("workspace-input"))
                                else -> Text("Synthetic $selectedTab surface")
                            }
                        }
                        WorkspaceApprovalHost(approvals, browserVisible,
                            onOpenBrowser = { browserOpenCalls++ }, activeSurface = activeSurface)
                    }
                }
            }
        }
    }

    private fun pending(): ApprovalRequest = requireNotNull(approvals.pending.value)
    private fun workspace() = compose.onNodeWithTag("r35-workspace-root")
    private fun dialogRoot() = compose.onNode(isRoot() and hasAnyDescendant(hasTestTag("agent-approval-content")))

    private fun assertCollapsed() {
        compose.onAllNodesWithTag("workspace-approval-indicator").assertCountEquals(1)
        compose.onNodeWithText("Review approval").assertIsDisplayed()
        compose.onNodeWithTag("browser-approval-dock").assertDoesNotExist()
        compose.onNodeWithTag("agent-approval-content").assertDoesNotExist()
        compose.onNodeWithText("Reject").assertDoesNotExist()
        compose.onNodeWithText("Allow once").assertDoesNotExist()
    }

    private fun assertSingleDetails() {
        compose.onAllNodesWithTag("agent-approval-content").assertCountEquals(1)
        compose.onNodeWithTag("workspace-approval-indicator").assertDoesNotExist()
        compose.onNodeWithTag("browser-approval-dock").assertDoesNotExist()
        compose.onAllNodesWithText("Reject").assertCountEquals(1)
        compose.onAllNodesWithText("Allow once").assertCountEquals(1)
    }

    private fun assertNoApprovalUi() {
        compose.onNodeWithTag("workspace-approval-indicator").assertDoesNotExist()
        compose.onNodeWithTag("browser-approval-dock").assertDoesNotExist()
        compose.onNodeWithTag("agent-approval-content").assertDoesNotExist()
    }

    private fun assertPending(original: ApprovalRequest, result: Deferred<ApprovalDecision>) {
        assertSame("Presentation must retain the same approval object and id", original, approvals.pending.value)
        assertFalse("Only an explicit decision or caller cancellation may finish this request", result.isCompleted)
        assertFalse("Collapse/review must not create session authority", approvals.hasSessionApproval(
            original.approvalSessionId, original.permissionScope, original.sessionGrantAction, original.sessionGrantResource))
        assertEquals("Review must not navigate to the browser", 0, browserOpenCalls)
    }

    private fun dismissSemantically() {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .performSemanticsAction(SemanticsActions.Dismiss) { it() }
        compose.waitForIdle()
    }

    private fun swipeSheetDown() {
        val handle = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).fetchSemanticsNode()
        val root = dialogRoot()
        val rootBounds = root.fetchSemanticsNode().boundsInRoot
        val start = handle.boundsInRoot.center - rootBounds.topLeft
        val end = Offset(start.x, rootBounds.height - 2f * density)
        assertTrue("Fixture must provide a real downward drag path", end.y - start.y > touchSlop + 48f * density)
        root.performTouchInput { swipe(start, end, durationMillis = 160) }
        compose.waitForIdle()
    }

    private fun tapScrim() {
        val handle = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).fetchSemanticsNode().boundsInRoot
        val root = dialogRoot()
        val rootBounds = root.fetchSemanticsNode().boundsInRoot
        assertTrue("Scrim tap must be visibly above the sheet handle", handle.top - rootBounds.top > 24f * density)
        root.performTouchInput { click(Offset(centerX, 8f * density)) }
        compose.waitForIdle()
    }

    private fun pressDialogBack() {
        compose.runOnIdle {
            (requireNotNull(ShadowDialog.getLatestDialog()) as ComponentDialog)
                .onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
    }

    private fun cancelRequest(result: Deferred<ApprovalDecision>) {
        compose.runOnIdle { result.cancel() }
        assertTrue(result.isCancelled)
        assertNull(approvals.pending.value)
        assertNoApprovalUi()
    }

    private fun decision(result: Deferred<ApprovalDecision>): ApprovalDecision {
        assertTrue("Explicit decisions must complete the matching caller", result.isCompleted)
        assertFalse(result.isCancelled)
        return runBlocking { result.await() }
    }

    @After fun cleanup() {
        try {
            compose.runOnUiThread {
                caller.cancel()
                compositionHost?.disposeComposition()
            }
        } finally {
            caller.cancel()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test(timeout = 20_000) fun nativeAndExternalPermissionFamiliesAllCollapseWithoutResolving() {
        activeSurface = WorkspaceApprovalSurface.AGENT
        mount()
        // Both native rendering branches, external ACP families, and an open-ended fallback.
        // Routing is kind-agnostic: this table never invokes the corresponding tool backend.
        val kinds = listOf("run_command", "write_file", "edit_file", "agent_plugin_tool", "agent_plugin_write",
            "agent_plugin_terminal", "agent_media_read", "agent_workspace_reconcile", "future_unknown_permission")
        kinds.forEach { kind ->
            val result = request(kind)
            assertSingleDetails()
            val original = pending()
            assertEquals(kind, original.kind)
            dismissSemantically()
            assertCollapsed()
            assertPending(original, result)
            cancelRequest(result)
        }
    }

    @Test(timeout = 20_000) fun allBrowserFamiliesStayCollapsedAcrossWorkspaceTabs() {
        selectTab("Browser")
        mount()
        listOf("webfetch", "browser_interact", "browser_local", "browser_unverified_output").forEach { kind ->
            val result = request(kind, browser = true)
            val original = pending()
            compose.onNodeWithTag("browser-approval-dock").assertIsDisplayed()
            compose.onNodeWithText("Details").performClick()
            assertSingleDetails()
            dismissSemantically()
            assertCollapsed()
            listOf("Explorer", "Editor", "Agent", "Browser").forEach { tab ->
                compose.onNodeWithText(tab).performClick()
                assertCollapsed()
                assertPending(original, result)
            }
            compose.onNodeWithText("Review approval").performClick()
            assertSingleDetails()
            assertPending(original, result)
            dismissSemantically()
            assertCollapsed()
            cancelRequest(result)
        }
    }

    @Test(timeout = 20_000) fun actualSwipeDownScrimAndBackCollapseNativePluginAndBrowserDetails() {
        mount()
        listOf("run_command" to false, "agent_plugin_tool" to false, "webfetch" to true).forEach { (kind, browser) ->
            compose.runOnIdle { selectTab(if (browser) "Browser" else "Agent") }
            val result = request(kind, browser)
            if (browser) compose.onNodeWithText("Details").performClick()
            val original = pending()
            listOf<() -> Unit>(::swipeSheetDown, ::tapScrim, ::pressDialogBack).forEachIndexed { index, close ->
                if (index > 0) compose.onNodeWithText("Review approval").performClick()
                assertSingleDetails()
                close()
                assertCollapsed()
                assertPending(original, result)
                assertEquals("Modal Back must not navigate the workspace", 0, workspaceBackCalls)
            }
            cancelRequest(result)
        }
    }

    @Test(timeout = 20_000) fun collapsedPermissionLeavesExplorerSelectionAndEditorInputUsable() {
        val result = request("agent_plugin_tool")
        mount()
        val original = pending()
        compose.onNodeWithText("Review approval").performClick()
        dismissSemantically()
        assertCollapsed()
        val workspaceBounds = compose.onNodeWithTag("workspace-surface").getUnclippedBoundsInRoot()
        val footerBounds = compose.onNodeWithTag("workspace-approval-indicator").getUnclippedBoundsInRoot()
        assertTrue("Collapsed footer must reserve space instead of covering the workspace", footerBounds.top >= workspaceBounds.bottom)
        compose.onNodeWithText("Explorer").performClick()
        compose.onNodeWithText("Open main.kt").performTouchInput { click(center) }
        assertEquals(1, explorerOpenCalls)
        compose.onNodeWithTag("workspace-input").performTextReplacement("Edited while permission remains pending")
        compose.onNodeWithTag("workspace-input").assertTextEquals("Edited while permission remains pending")
        compose.onNodeWithTag("workspace-input").assertIsFocused()
        compose.onNodeWithTag("workspace-surface").performTouchInput {
            swipe(center, center + Offset(0f, -(touchSlop + 48f * density)), durationMillis = 240)
        }
        assertCollapsed()
        assertPending(original, result)
        assertEquals("Collapse must not hide the editor keyboard", 0, keyboard.hideCalls)
    }

    @Test(timeout = 20_000) fun upwardDragsFromStatusAndButtonOpenOneSheetOnlyAfterRelease() {
        val result = request()
        mount()
        val original = pending()
        listOf("Agent waiting for permission", "Review approval").forEach { label ->
            assertCollapsed()
            compose.onNodeWithText(label).performTouchInput {
                down(center)
                moveBy(Offset(0f, -(touchSlop + 48f * density)))
            }
            assertCollapsed() // Crossing the threshold while held must not open a modal.
            assertPending(original, result)
            workspace().performTouchInput { up() }
            assertSingleDetails()
            assertPending(original, result)
            dismissSemantically()
            assertCollapsed()
            compose.mainClock.advanceTimeBy(700)
            assertCollapsed() // No queued duplicate opening after this release/dismissal.
        }
        val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        compose.onAllNodes(polite).assertCountEquals(1)
        compose.onNodeWithText("Review approval").assertHeightIsAtLeast(48.dp).performClick()
        assertSingleDetails() // Ordinary accessibility/semantic click remains available.
        assertPending(original, result)
    }

    @Test(timeout = 20_000) fun downwardHorizontalShortReversedAndCancelledGesturesStayCollapsed() {
        val result = request()
        mount()
        val original = pending()
        val indicator = compose.onNodeWithTag("workspace-approval-indicator")
        val gestures = listOf<TouchInjectionScope.() -> Unit>(
            { down(Offset(centerX, 1f)); moveBy(Offset(0f, touchSlop + 28f * density)); up() },
            { down(center); moveBy(Offset(touchSlop + 70f * density, 0f)); up() },
            { down(center); moveBy(Offset(0f, -touchSlop * .5f)); up() },
            { down(center); moveBy(Offset(0f, -(touchSlop + 23f * density))); up() },
            { down(center); moveBy(Offset(0f, -(touchSlop + 48f * density))); moveBy(Offset(0f, 64f * density)); up() },
            { down(center); moveBy(Offset(0f, -(touchSlop + 48f * density))); cancel() },
        )
        gestures.forEach { gesture ->
            indicator.performTouchInput(gesture)
            assertCollapsed()
            assertPending(original, result)
        }
        compose.onNodeWithText("Review approval").performClick()
        assertSingleDetails()
    }

    @Test(timeout = 20_000) fun replacingRequestDuringDragCannotOpenOrResolveTheReplacement() {
        val first = request("run_command")
        mount()
        val firstRequest = pending()
        val staleReview = requireNotNull(compose.onNodeWithText("Review approval").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action)
        compose.onNodeWithText("Agent waiting for permission").performTouchInput {
            down(center)
            moveBy(Offset(0f, -(touchSlop + 48f * density)))
        }
        assertCollapsed()
        lateinit var second: Deferred<ApprovalDecision>
        compose.runOnIdle {
            first.cancel()
            second = request("agent_plugin_tool")
        }
        assertCollapsed()
        val replacement = pending()
        assertNotEquals(firstRequest.id, replacement.id)
        workspace().performTouchInput { up() }
        compose.runOnIdle { staleReview() }
        assertTrue(first.isCancelled)
        assertCollapsed()
        assertPending(replacement, second)
        compose.onNodeWithText("Review approval").performClick()
        assertSingleDetails()
        assertPending(replacement, second)
    }

    @Test(timeout = 20_000) fun onlyExplicitRejectAllowOnceOrCallerCancellationFinishTheCurrentRequest() {
        mount()
        listOf("run_command" to false, "agent_plugin_tool" to false, "webfetch" to true).forEach { (kind, browser) ->
            listOf("Reject" to ApprovalDecision.Denied, "Allow once" to ApprovalDecision.Approved).forEach { (label, expected) ->
                compose.runOnIdle { selectTab(if (browser) "Browser" else "Editor") }
                val result = request(kind, browser)
                val completions = AtomicInteger()
                result.invokeOnCompletion { completions.incrementAndGet() }
                val original = pending()
                compose.onNodeWithText(if (browser) "Details" else "Review approval").performClick()
                dismissSemantically()
                assertCollapsed()
                assertPending(original, result)
                compose.onNodeWithText("Review approval").performClick()
                val staleDecision = requireNotNull(compose.onNodeWithText(label).fetchSemanticsNode()
                    .config[SemanticsActions.OnClick].action)
                compose.onNodeWithText(label).performClick()
                assertEquals(expected, decision(result))
                assertEquals("A single explicit decision must resume its caller once", 1, completions.get())
                assertNull(approvals.pending.value)
                assertNoApprovalUi()
                assertFalse(approvals.resolve(original.id, ApprovalResolution.ALLOW_ONCE))
                assertFalse(approvals.hasSessionApproval(original.approvalSessionId, original.permissionScope,
                    original.sessionGrantAction, original.sessionGrantResource))
                val replacementResult = request(kind, browser)
                val replacement = pending()
                compose.runOnIdle { staleDecision() }
                assertPending(replacement, replacementResult)
                assertEquals(1, completions.get())
                compose.onNodeWithText(if (browser) "Details" else "Review approval").performClick()
                dismissSemantically()
                assertCollapsed()
                cancelRequest(replacementResult)
            }
        }
    }
}
