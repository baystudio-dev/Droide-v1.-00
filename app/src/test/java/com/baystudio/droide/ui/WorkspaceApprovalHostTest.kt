package com.baystudio.droide.ui

import android.app.Application
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.size
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.baystudio.droide.core.AgentRequestProvenance
import com.baystudio.droide.core.ApprovalDecision
import com.baystudio.droide.core.ApprovalManager
import com.baystudio.droide.core.ApprovalRequest
import com.baystudio.droide.core.BrowserApprovalContext
import com.baystudio.droide.core.BrowserOutputConsent
import com.baystudio.droide.core.ApprovalResolution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkspaceApprovalHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val approvals = ApprovalManager()
    private val caller = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var compact by mutableStateOf(false)
    private var browserVisible by mutableStateOf(false)
    private var agentVisible by mutableStateOf(false)
    private var browserDialogPending by mutableStateOf(false)
    private var activeSurface by mutableStateOf(WorkspaceApprovalSurface.OTHER)
    private var reviewRequest by mutableStateOf<Pair<Long, Long>?>(null)
    private var hostHeight by mutableStateOf(640.dp)
    private var browserOpenCalls = 0
    private var workspaceBackCalls = 0
    private var density = 1f
    private var receivedImeBottom = 0
    private lateinit var rootView: View
    private val keyboard = RecordingKeyboard()

    private class RecordingKeyboard : SoftwareKeyboardController {
        var showCalls = 0
        var hideCalls = 0
        override fun show() { showCalls++ }
        override fun hide() { hideCalls++ }
    }

    private enum class Presentation { INDICATOR, DOCK, DETAILS }

    @After fun cleanup() { caller.cancel() }

    private fun browserRequest(
        summary: String = "Read the requested browser page",
        reason: String = "Read this browser page or its output.",
    ): Deferred<ApprovalDecision> = caller.async {
        approvals.requestApproval(
            kind = "webfetch", summary = summary, detail = "Review the exact page before continuing.",
            action = "webfetch", resource = "https://example.test/docs?chapter=2#intro",
            permissionScope = "build", provenance = AgentRequestProvenance.primary("browser-session"),
            sessionGrantAction = "webfetch", sessionGrantResource = "https://example.test/docs?chapter=2#intro",
            browserContext = BrowserApprovalContext("read", "https://example.test/docs?chapter=2#intro",
                target = "main", reason = reason),
        )
    }

    private fun genericRequest(summary: String = "Run the requested command"): Deferred<ApprovalDecision> = caller.async {
        approvals.requestApproval("shell", summary, "pwd", action = "shell", resource = "pwd")
    }

    private fun activateBrowser() {
        browserVisible = true
        activeSurface = WorkspaceApprovalSurface.BROWSER
    }

    private fun mount(useDefaultSurface: Boolean = false, editable: Boolean = false) {
        compose.setContent {
            val currentDensity = LocalDensity.current
            val currentView = LocalView.current
            val imeBottom = WindowInsets.ime.getBottom(currentDensity)
            SideEffect {
                density = currentDensity.density
                rootView = currentView
                receivedImeBottom = imeBottom
            }
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                DroideTheme {
                    BackHandler { workspaceBackCalls++ }
                    // Mirrors reserved footer space: approval may resize the viewport, but its controls do not cover
                    // the workspace's remaining content.
                    Column(Modifier.width(360.dp).height(hostHeight)) {
                        Box(Modifier.weight(1f).fillMaxWidth().testTag("workspace-surface")) {
                            Text(if (browserVisible) "Browser surface" else "Editor surface")
                            if (agentVisible) Text("Visible Agent pane", Modifier.align(Alignment.TopEnd))
                            if (editable) {
                                var input by remember { mutableStateOf("Current editor draft") }
                                BasicTextField(input, onValueChange = { input = it },
                                    modifier = Modifier.padding(top = 32.dp).fillMaxWidth().height(48.dp)
                                        .testTag("workspace-input"))
                            }
                        }
                        if (useDefaultSurface) {
                            WorkspaceApprovalHost(approvals, browserVisible,
                                onOpenBrowser = { browserOpenCalls++ },
                                browserDialogPending = browserDialogPending,
                                reviewRequest = reviewRequest, compact = compact)
                        } else {
                            WorkspaceApprovalHost(approvals, browserVisible,
                                onOpenBrowser = { browserOpenCalls++ },
                                browserDialogPending = browserDialogPending,
                                activeSurface = activeSurface, reviewRequest = reviewRequest, compact = compact)
                        }
                    }
                }
            }
        }
    }

    /** authored only: compact presentation never resolves or replaces the pending request. */
    @Test fun expandedCompactFooterRetainsOnePendingResponderAcrossPresentationChanges() {
        activateBrowser()
        val result = browserRequest()
        mount()
        val original = pending()
        assertPresentation(Presentation.DOCK)
        repeat(3) {
            compose.runOnIdle { compact = true }
            assertPresentation(Presentation.INDICATOR)
            assertPending(original, result)
            compose.runOnIdle { compact = false }
            assertPresentation(Presentation.DOCK)
            assertPending(original, result)
        }
        compose.runOnIdle { compact = true }
        compose.onNodeWithText("Review approval").performClick()
        assertPresentation(Presentation.DETAILS)
        assertPending(original, result)
        compose.onNodeWithText("Reject").performClick()
        assertTrue(result.isCompleted)
        assertNull(approvals.pending.value)
    }

    private fun pending(): ApprovalRequest = requireNotNull(approvals.pending.value)

    private fun assertPresentation(presentation: Presentation) {
        val details = presentation == Presentation.DETAILS
        val dock = presentation == Presentation.DOCK
        val indicator = presentation == Presentation.INDICATOR
        compose.onAllNodesWithText("Allow once").assertCountEquals(if (indicator) 0 else 1)
        compose.onAllNodesWithText("Reject").assertCountEquals(if (indicator) 0 else 1)
        compose.onAllNodesWithText("Review approval").assertCountEquals(if (indicator) 1 else 0)
        compose.onAllNodesWithTag("workspace-approval-indicator").assertCountEquals(if (indicator) 1 else 0)
        compose.onAllNodesWithTag("browser-approval-dock").assertCountEquals(if (dock) 1 else 0)
        compose.onAllNodesWithTag("agent-approval-content").assertCountEquals(if (details) 1 else 0)
    }

    private fun assertNoApprovalUi() {
        compose.onNodeWithTag("workspace-approval-indicator").assertDoesNotExist()
        compose.onNodeWithTag("browser-approval-dock").assertDoesNotExist()
        compose.onNodeWithTag("agent-approval-content").assertDoesNotExist()
    }

    private fun dismissDetails() {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .performSemanticsAction(SemanticsActions.Dismiss) { it() }
        compose.waitForIdle()
    }

    private fun decision(result: Deferred<ApprovalDecision>): ApprovalDecision {
        assertTrue("The selected request should finish immediately", result.isCompleted)
        return runBlocking { result.await() }
    }

    private fun assertPending(original: ApprovalRequest, result: Deferred<ApprovalDecision>) {
        assertSame(original, pending())
        assertFalse(result.isCompleted)
        assertEquals("Presentation must not navigate to the browser", 0, browserOpenCalls)
    }

    private fun assertActionWithinViewport(label: String, bottom: Float) {
        val bounds = compose.onNodeWithText(label).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("$label is above the viewport: $bounds", bounds.top.value >= 0f)
        assertTrue("$label is below the viewport: $bounds", bounds.bottom.value <= bottom + 1f)
        assertTrue("$label needs a 48 dp touch target: $bounds", bounds.size.height.value >= 48f)
        assertTrue("$label needs a 48 dp touch target: $bounds", bounds.size.width.value >= 48f)
    }

    private fun dispatchIme(heightDp: Int) {
        val pixels = (heightDp * density).roundToInt()
        compose.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(rootView, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, pixels))
                .setVisible(WindowInsetsCompat.Type.ime(), heightDp > 0).build())
        }
        compose.waitForIdle()
        assertEquals("The fixture must receive the injected IME insets", pixels, receivedImeBottom)
    }

    @Test fun noPendingRequestCreatesNoApprovalUi() {
        mount()
        assertNoApprovalUi()
        compose.onNodeWithText("Editor surface").assertIsDisplayed()
    }

    @Test fun defaultSurfaceKeepsBrowserApprovalInAPersistentIndicator() {
        val result = browserRequest()
        mount(useDefaultSurface = true)
        val original = pending()
        assertPresentation(Presentation.INDICATOR)
        compose.onNodeWithText("Agent waiting for permission").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(60_000)
        assertPresentation(Presentation.INDICATOR)
        assertPending(original, result)
    }

    @Test fun defaultSurfaceDoesNotAutomaticallyOpenGenericPermission() {
        val result = genericRequest()
        mount(useDefaultSurface = true)
        assertPresentation(Presentation.INDICATOR)
        assertFalse(result.isCompleted)
    }

    @Test fun aMerelyVisibleAgentPaneDoesNotOpenDetailsOverOtherWork() {
        agentVisible = true
        genericRequest()
        mount()
        compose.onNodeWithText("Visible Agent pane").assertIsDisplayed()
        assertPresentation(Presentation.INDICATOR)
        compose.runOnIdle { activeSurface = WorkspaceApprovalSurface.AGENT }
        assertPresentation(Presentation.DETAILS)
    }

    @Test fun browserDockRequiresBothBrowserAttentionAndVisibility() {
        val result = browserRequest()
        browserVisible = true
        mount()
        val original = pending()
        assertPresentation(Presentation.INDICATOR)
        compose.runOnIdle { activeSurface = WorkspaceApprovalSurface.BROWSER }
        assertPresentation(Presentation.DOCK)
        compose.runOnIdle { browserVisible = false }
        assertPresentation(Presentation.INDICATOR)
        assertPending(original, result)
    }

    @Test fun activeBrowserDockExposesItsActionSiteAndReasonWithoutNavigation() {
        activateBrowser()
        val result = browserRequest()
        mount()
        assertPresentation(Presentation.DOCK)
        compose.onNodeWithText("Action: read").assertIsDisplayed()
        compose.onNodeWithText("Site: https://example.test").assertIsDisplayed()
        compose.onNodeWithText("Target: main").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Permission reason: Read this browser page or its output.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Details").assertIsDisplayed()
        compose.onNodeWithText("View browser").assertDoesNotExist()
        compose.onNodeWithText("Always allow for this session").assertDoesNotExist()
        assertEquals(0, browserOpenCalls)
        assertFalse(result.isCompleted)
    }

    @Test fun genericPermissionOnActiveBrowserStillRequiresExplicitReview() {
        activateBrowser()
        genericRequest()
        mount()
        assertPresentation(Presentation.INDICATOR)
        compose.onNodeWithText("Review approval").performClick()
        assertPresentation(Presentation.DETAILS)
    }

    @Test fun compactAllowOnceUsesTheCapturedRequestIdAndCannotApproveTheNextRequest() {
        activateBrowser()
        val first = browserRequest("First page read")
        mount()
        val firstId = pending().id
        val staleAllow = requireNotNull(compose.onNodeWithText("Allow once").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action)
        compose.onNodeWithText("Allow once").performClick()
        assertEquals(ApprovalDecision.Approved, decision(first))
        assertNull(approvals.pending.value)
        val second = browserRequest("Second page read")
        compose.waitForIdle()
        val secondRequest = pending()
        assertNotEquals(firstId, secondRequest.id)
        compose.runOnIdle { staleAllow() }
        assertPending(secondRequest, second)
        assertPresentation(Presentation.DOCK)
        assertFalse(approvals.hasSessionApproval("browser-session", "build", "webfetch", secondRequest.resource))
        compose.onNodeWithText("Reject").performClick()
        assertEquals(ApprovalDecision.Denied, decision(second))
    }

    @Test fun compactRejectUsesTheCapturedRequestIdAndCannotRejectTheNextRequest() {
        activateBrowser()
        val first = browserRequest("First page read")
        mount()
        val firstId = pending().id
        val staleReject = requireNotNull(compose.onNodeWithText("Reject").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action)
        compose.onNodeWithText("Reject").performClick()
        assertEquals(ApprovalDecision.Denied, decision(first))
        val second = browserRequest("Second page read")
        compose.waitForIdle()
        val secondRequest = pending()
        assertNotEquals(firstId, secondRequest.id)
        compose.runOnIdle { staleReject() }
        assertPending(secondRequest, second)
        assertPresentation(Presentation.DOCK)
    }

    @Test fun detailsCanBeClosedAndReopenedWithoutResolvingOrReplacingTheRequest() {
        activateBrowser()
        val result = browserRequest()
        mount()
        val original = pending()
        repeat(2) { index ->
            compose.onNodeWithText(if (index == 0) "Details" else "Review approval").performClick()
            assertPresentation(Presentation.DETAILS)
            compose.onNodeWithText("Page: https://example.test/docs?chapter=2#intro").assertExists()
            compose.onNodeWithText("Target: main").assertExists()
            compose.onNodeWithText("Always allow for this session").assertIsDisplayed()
            dismissDetails()
            assertPresentation(Presentation.INDICATOR)
            assertPending(original, result)
        }
        compose.onNodeWithText("Review approval").performClick()
        compose.onNodeWithText("Reject").performClick()
        assertEquals(ApprovalDecision.Denied, decision(result))
    }

    @Test fun indicatorReviewAndDismissRetainThePendingRequestUntilExplicitReject() {
        val result = genericRequest()
        mount()
        val original = pending()
        repeat(2) {
            compose.onNodeWithText("Review approval").performClick()
            assertPresentation(Presentation.DETAILS)
            compose.onNodeWithText("Run the requested command").assertIsDisplayed()
            dismissDetails()
            assertPresentation(Presentation.INDICATOR)
            assertPending(original, result)
        }
        compose.onNodeWithText("Review approval").performClick()
        compose.onNodeWithText("Reject").performClick()
        assertEquals(ApprovalDecision.Denied, decision(result))
        assertNoApprovalUi()
    }

    @Test fun oneOutputDisclosureIsCompleteInDockAndDetailsWithoutSessionChoice() {
        activateBrowser()
        val scope = "Selected console snapshot, lines 1 to 2 (end exclusive)."
        val result = caller.async {
            approvals.requestApproval("browser_unverified_output", "Allow one console output?", "No payload preview",
                action = "webfetch", resource = "https://example.test/", permissionScope = "build",
                provenance = AgentRequestProvenance.primary("browser-session"),
                approvalSessionId = "", sessionGrantAction = "", sessionGrantResource = "",
                browserContext = BrowserApprovalContext("console", "https://example.test/",
                    reason = "Include this one output in the Agent result.", outputScope = scope))
        }
        mount()
        val request = pending()
        assertPresentation(Presentation.DOCK)
        compose.onNodeWithText(BrowserOutputConsent.DISCLOSURE).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Output scope: $scope").assertExists()
        assertFalse(request.canAllowForSession)
        assertFalse(approvals.resolve(request.id, ApprovalResolution.ALLOW_SESSION))
        compose.onNodeWithText("Details").performClick()
        assertPresentation(Presentation.DETAILS)
        compose.onNodeWithText(BrowserOutputConsent.DISCLOSURE).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Always allow for this session").assertDoesNotExist()
        compose.onNodeWithText("Allow once").performClick()
        assertEquals(ApprovalDecision.Approved, decision(result))
        assertFalse(approvals.hasSessionApproval("browser-session", "build", "webfetch", "https://example.test/"))
        assertNoApprovalUi()
    }

    @Test fun sessionChoiceExistsOnlyInDetailsAndKeepsTheOriginalPermissionScope() {
        activateBrowser()
        val result = browserRequest()
        mount()
        val original = pending()
        compose.onNodeWithText("Always allow for this session").assertDoesNotExist()
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Always allow for this session").performClick()
        assertEquals(ApprovalDecision.Approved, decision(result))
        assertNull(approvals.pending.value)
        assertTrue(approvals.hasSessionApproval(original.approvalSessionId, original.permissionScope,
            original.sessionGrantAction, original.sessionGrantResource))
        assertFalse(approvals.hasSessionApproval(original.approvalSessionId, original.permissionScope,
            original.sessionGrantAction, "https://another.test/"))
        assertNoApprovalUi()
    }

    @Test fun activeAgentAutomaticallyOpensDetailsThenCollapsesWhenAttentionLeaves() {
        activeSurface = WorkspaceApprovalSurface.AGENT
        agentVisible = true
        val result = browserRequest()
        mount()
        val original = pending()
        assertPresentation(Presentation.DETAILS)
        compose.runOnIdle { activeSurface = WorkspaceApprovalSurface.OTHER }
        compose.onNodeWithText("Visible Agent pane").assertIsDisplayed()
        assertPresentation(Presentation.INDICATOR)
        assertPending(original, result)
        compose.runOnIdle { activateBrowser() }
        assertPresentation(Presentation.DOCK)
        assertPending(original, result)
    }

    @Test fun dismissingAutomaticDetailsRemembersTheChoiceForThatRequest() {
        activeSurface = WorkspaceApprovalSurface.AGENT
        val result = genericRequest()
        mount()
        val original = pending()
        assertPresentation(Presentation.DETAILS)
        dismissDetails()
        assertPresentation(Presentation.INDICATOR)
        compose.runOnIdle { browserDialogPending = true; browserVisible = true }
        assertPresentation(Presentation.INDICATOR)
        compose.runOnIdle { activeSurface = WorkspaceApprovalSurface.OTHER }
        compose.runOnIdle { activeSurface = WorkspaceApprovalSurface.AGENT }
        assertPresentation(Presentation.INDICATOR)
        assertPending(original, result)
        compose.onNodeWithText("Review approval").performClick()
        assertPresentation(Presentation.DETAILS)
    }

    @Test fun explicitDetailsStayOpenAcrossAttentionAndVisibilityChanges() {
        val result = browserRequest()
        mount()
        val original = pending()
        compose.onNodeWithText("Review approval").performClick()
        listOf(WorkspaceApprovalSurface.BROWSER, WorkspaceApprovalSurface.AGENT, WorkspaceApprovalSurface.OTHER).forEach {
            compose.runOnIdle { activeSurface = it; browserVisible = !browserVisible }
            assertPresentation(Presentation.DETAILS)
            assertPending(original, result)
        }
        dismissDetails()
        assertPresentation(Presentation.INDICATOR)
    }

    @Test fun aNewRequestDoesNotInheritAutomaticDetailsDismissal() {
        activeSurface = WorkspaceApprovalSurface.AGENT
        val first = genericRequest("First command")
        mount()
        dismissDetails()
        assertPresentation(Presentation.INDICATOR)
        compose.runOnIdle { first.cancel() }
        assertNoApprovalUi()
        genericRequest("Second command")
        assertPresentation(Presentation.DETAILS)
        compose.onNodeWithText("Second command").assertIsDisplayed()
    }

    @Test fun backFromCompactDockLeavesTheApprovalPendingAndReachesWorkspaceNavigation() {
        activateBrowser()
        val result = browserRequest()
        mount()
        val original = pending()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, workspaceBackCalls)
        assertPresentation(Presentation.DOCK)
        assertPending(original, result)
    }

    @Test fun backFromBackgroundIndicatorDoesNotDenyTheRequest() {
        val result = genericRequest()
        mount()
        val original = pending()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, workspaceBackCalls)
        assertPresentation(Presentation.INDICATOR)
        assertPending(original, result)
    }

    @Test fun backFromDetailedSheetCollapsesWithoutDenyingTheRequest() {
        val result = genericRequest()
        mount()
        val original = pending()
        compose.onNodeWithText("Review approval").performClick()
        assertPresentation(Presentation.DETAILS)
        compose.runOnIdle {
            val dialog = requireNotNull(ShadowDialog.getLatestDialog()) as ComponentDialog
            dialog.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
        assertPresentation(Presentation.INDICATOR)
        assertPending(original, result)
        assertEquals(0, workspaceBackCalls)
    }

    @Test fun cancellationRemovesTheCompactDockWithoutAResponse() {
        activateBrowser()
        val result = browserRequest()
        mount()
        compose.runOnIdle { result.cancel() }
        assertNoApprovalUi()
        assertTrue(result.isCancelled)
        assertNull(approvals.pending.value)
    }

    @Test fun cancellationRemovesTheBackgroundIndicatorWithoutAResponse() {
        val result = genericRequest()
        mount()
        compose.runOnIdle { result.cancel() }
        assertNoApprovalUi()
        assertTrue(result.isCancelled)
        assertNull(approvals.pending.value)
    }

    @Test fun cancellationRemovesDetailsAndTheNextRequestStartsCompact() {
        activateBrowser()
        val first = browserRequest("Cancelled page read")
        mount()
        compose.onNodeWithText("Details").performClick()
        assertPresentation(Presentation.DETAILS)
        compose.runOnIdle { first.cancel() }
        assertNoApprovalUi()
        browserRequest("Fresh page read")
        assertPresentation(Presentation.DOCK)
        compose.onNodeWithText("Fresh page read").assertIsDisplayed()
    }

    @Test fun websiteDialogNeverAutomaticallyOpensApprovalDetails() {
        browserDialogPending = true
        val result = browserRequest()
        mount()
        val original = pending()
        assertPresentation(Presentation.INDICATOR)
        compose.runOnIdle { browserVisible = true }
        assertPresentation(Presentation.INDICATOR)
        compose.runOnIdle { activeSurface = WorkspaceApprovalSurface.BROWSER }
        assertPresentation(Presentation.DOCK)
        compose.runOnIdle { browserDialogPending = false }
        assertPresentation(Presentation.DOCK)
        assertPending(original, result)
    }

    @Test fun visibleWebsiteDialogSuppressesStaleAgentAttentionButAllowsExplicitReview() {
        activeSurface = WorkspaceApprovalSurface.AGENT
        browserVisible = true
        browserDialogPending = true
        val result = browserRequest()
        mount()
        val original = pending()
        assertPresentation(Presentation.INDICATOR)
        compose.runOnIdle { reviewRequest = original.id to 1L }
        assertPresentation(Presentation.DETAILS)
        assertPending(original, result)
        assertTrue(browserDialogPending)
        dismissDetails()
        assertPresentation(Presentation.INDICATOR)
        assertPending(original, result)
    }

    @Test fun aHiddenWebsiteDialogDoesNotSuppressActiveAgentApproval() {
        activeSurface = WorkspaceApprovalSurface.AGENT
        browserDialogPending = true
        browserRequest()
        mount()
        assertPresentation(Presentation.DETAILS)
    }

    @Test fun websiteDialogReviewEventExplicitlyOpensOnlyItsMatchingApproval() {
        activateBrowser()
        browserDialogPending = true
        val result = browserRequest()
        mount()
        val original = pending()
        assertPresentation(Presentation.DOCK)
        compose.runOnIdle { reviewRequest = original.id to 1L }
        assertPresentation(Presentation.DETAILS)
        assertPending(original, result)
        assertTrue("Review must not consume the website's dialog", browserDialogPending)
        dismissDetails()
        assertPresentation(Presentation.INDICATOR)
        // The same event and unrelated recomposition cannot reopen a dismissed sheet.
        compose.runOnIdle { reviewRequest = original.id to 1L; agentVisible = true }
        assertPresentation(Presentation.INDICATOR)
        compose.runOnIdle { reviewRequest = original.id to 2L }
        assertPresentation(Presentation.DETAILS)
        compose.onNodeWithText("Reject").performClick()
        assertEquals(ApprovalDecision.Denied, decision(result))
        assertTrue(browserDialogPending)
    }

    @Test fun staleExternalReviewEventCannotOpenOrResolveTheNextRequest() {
        val first = genericRequest("First command")
        mount()
        val firstId = pending().id
        compose.runOnIdle { reviewRequest = firstId to 1L }
        assertPresentation(Presentation.DETAILS)
        compose.onNodeWithText("Reject").performClick()
        assertEquals(ApprovalDecision.Denied, decision(first))
        val second = genericRequest("Second command")
        compose.waitForIdle()
        val current = pending()
        assertNotEquals(firstId, current.id)
        assertPresentation(Presentation.INDICATOR)
        compose.runOnIdle { reviewRequest = firstId to 2L }
        assertPresentation(Presentation.INDICATOR)
        assertPending(current, second)
    }

    @Test fun indicatorHasOnePoliteStableAnnouncementAndAnExplicit48DpReviewAction() {
        genericRequest()
        mount()
        val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        compose.onAllNodes(polite).assertCountEquals(1)
        compose.onNode(polite).assertTextEquals("Agent waiting for permission")
        assertActionWithinViewport("Review approval", 640f)
    }

    @Test fun compactDockHasOnePoliteStableAnnouncementAnd48DpActions() {
        activateBrowser()
        browserRequest()
        mount()
        val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        compose.onAllNodes(polite).assertCountEquals(1)
        compose.onNode(polite).assertTextEquals("Permission required")
        listOf("Details", "Reject", "Allow once").forEach { assertActionWithinViewport(it, 640f) }
    }

    @Test fun footerReservesSpaceInsteadOfCoveringTheWorkspace() {
        activateBrowser()
        mount()
        val before = compose.onNodeWithTag("workspace-surface").getUnclippedBoundsInRoot()
        val result = browserRequest()
        assertPresentation(Presentation.DOCK)
        val workspace = compose.onNodeWithTag("workspace-surface").getUnclippedBoundsInRoot()
        val dock = compose.onNodeWithTag("browser-approval-dock").getUnclippedBoundsInRoot()
        assertTrue("Approval footer should reserve viewport space", workspace.bottom < before.bottom)
        assertTrue("Approval footer must not cover workspace content", dock.top >= workspace.bottom)
        compose.onNodeWithText("Allow once").performClick()
        assertEquals(ApprovalDecision.Approved, decision(result))
        assertEquals(before, compose.onNodeWithTag("workspace-surface").getUnclippedBoundsInRoot())
    }

    @Test fun shortBrowserViewportUsesReviewIndicatorToPreserveWorkspaceRoom() {
        activateBrowser()
        hostHeight = 190.dp
        browserRequest(reason = "Review this permission before continuing. ".repeat(80))
        mount()
        assertActionWithinViewport("Review approval", 190f)
        assertPresentation(Presentation.INDICATOR)
        val workspace = compose.onNodeWithTag("workspace-surface").getUnclippedBoundsInRoot()
        assertTrue("The compact status must leave room for the browser", workspace.size.height >= 100.dp)
        compose.onNodeWithText("Review approval").performClick()
        assertPresentation(Presentation.DETAILS)
    }

    @Test fun shortViewportKeepsBackgroundReviewOutsideTheLongSummary() {
        hostHeight = 100.dp
        genericRequest("Review the requested command. ".repeat(80))
        mount()
        assertActionWithinViewport("Review approval", 100f)
        assertPresentation(Presentation.INDICATOR)
    }

    @Test fun browserDockUsesAtMostFortyPercentOfAvailableHeightCappedAt220Dp() {
        activateBrowser()
        browserRequest(reason = "Review this permission before continuing. ".repeat(80))
        mount()
        assertTrue(compose.onNodeWithTag("browser-approval-dock").getUnclippedBoundsInRoot().size.height <= 220.dp)
        compose.runOnIdle { hostHeight = 400.dp }
        assertTrue(compose.onNodeWithTag("browser-approval-dock").getUnclippedBoundsInRoot().size.height <= 160.dp)
        listOf("Details", "Reject", "Allow once").forEach { assertActionWithinViewport(it, 400f) }
        compose.runOnIdle { hostHeight = 299.dp }
        assertPresentation(Presentation.INDICATOR)
    }

    @Test fun keyboardReducedBrowserViewportFallsBackWithoutResolvingOrHidingTheIme() {
        activateBrowser()
        val result = browserRequest()
        mount()
        val original = pending()
        assertPresentation(Presentation.DOCK)
        val hideCalls = keyboard.hideCalls
        dispatchIme(400)
        assertPresentation(Presentation.INDICATOR)
        assertActionWithinViewport("Review approval", 240f)
        assertEquals(hideCalls, keyboard.hideCalls)
        assertPending(original, result)
        dispatchIme(0)
        assertPresentation(Presentation.DOCK)
        assertPending(original, result)
    }

    @Test fun backgroundArrivalPreservesEditorFocusDraftAndInjectedImeWithoutKeyboardCalls() {
        mount(editable = true)
        val input = compose.onNodeWithTag("workspace-input")
        input.performClick().performTextReplacement("Keep editing this draft")
        dispatchIme(300)
        input.assertIsFocused()
        val hideCalls = keyboard.hideCalls
        val showCalls = keyboard.showCalls
        val result = genericRequest()
        val original = pending()
        assertPresentation(Presentation.INDICATOR)
        input.assertIsFocused().assertTextEquals("Keep editing this draft")
        assertEquals(hideCalls, keyboard.hideCalls)
        assertEquals(showCalls, keyboard.showCalls)
        assertEquals((300 * density).roundToInt(), receivedImeBottom)
        assertActionWithinViewport("Review approval", 340f)
        assertPending(original, result)
        compose.onNodeWithText("Review approval").performClick()
        assertPresentation(Presentation.DETAILS)
        assertEquals("Workspace review must not explicitly hide the keyboard", hideCalls, keyboard.hideCalls)
    }

    @Test fun browserDockArrivalPreservesExistingInputFocusAndInjectedIme() {
        activateBrowser()
        mount(editable = true)
        val input = compose.onNodeWithTag("workspace-input")
        input.performClick().performTextReplacement("Browser form draft")
        dispatchIme(250)
        val hideCalls = keyboard.hideCalls
        val showCalls = keyboard.showCalls
        val result = browserRequest(reason = "Review this permission before continuing. ".repeat(80))
        val original = pending()
        assertPresentation(Presentation.DOCK)
        input.assertIsFocused().assertTextEquals("Browser form draft")
        assertEquals(hideCalls, keyboard.hideCalls)
        assertEquals(showCalls, keyboard.showCalls)
        assertEquals((250 * density).roundToInt(), receivedImeBottom)
        listOf("Details", "Reject", "Allow once").forEach { assertActionWithinViewport(it, 390f) }
        assertPending(original, result)
    }
}
