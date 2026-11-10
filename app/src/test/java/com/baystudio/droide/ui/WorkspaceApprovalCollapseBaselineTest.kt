package com.baystudio.droide.ui

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.ApprovalDecision
import com.baystudio.droide.core.ApprovalManager
import com.baystudio.droide.core.ApprovalRequest
import com.baystudio.droide.core.BrowserApprovalContext
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

/** Only the real ApprovalManager and WorkspaceApprovalHost are exercised. */

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkspaceApprovalCollapseBaselineTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val approvals = ApprovalManager()
    private val caller = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var compositionHost: AbstractComposeView? = null
    private var density = 1f
    private var touchSlop = 0f
    private var browserOpenCalls = 0

    private fun request(browser: Boolean = false): Deferred<ApprovalDecision> = caller.async {
        approvals.requestApproval(
            kind = if (browser) "webfetch" else "run_command",
            summary = "Synthetic permission", detail = "Inert approval payload",
            action = if (browser) "webfetch" else "shell", resource = "fixture-only",
            browserContext = if (browser) BrowserApprovalContext(
                operation = "read", url = "https://example.test/", reason = "Synthetic browser review"
            ) else null,
        )
    }

    private fun mount(activeBrowser: Boolean = false) {
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
            DroideTheme {
                Column(Modifier.width(360.dp).height(640.dp)) {
                    Box(Modifier.weight(1f).fillMaxWidth()) { Text("Inert workspace") }
                    WorkspaceApprovalHost(approvals, browserVisible = activeBrowser,
                        onOpenBrowser = { browserOpenCalls++ },
                        activeSurface = if (activeBrowser) WorkspaceApprovalSurface.BROWSER
                            else WorkspaceApprovalSurface.OTHER)
                }
            }
        }
    }

    private fun dismissDetails() {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .performSemanticsAction(SemanticsActions.Dismiss) { it() }
        compose.waitForIdle()
    }

    private fun assertPending(original: ApprovalRequest, result: Deferred<ApprovalDecision>) {
        assertSame("Collapsing must retain the exact pending object", original, approvals.pending.value)
        assertFalse("Collapsing must not resume the approval caller", result.isCompleted)
        assertEquals("Presentation must not navigate", 0, browserOpenCalls)
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

    @Test(timeout = 20_000) fun browserDetailsDismissesToCompactIndicator() {
        val result = request(browser = true)
        mount(activeBrowser = true)
        val original = requireNotNull(approvals.pending.value)
        compose.onNodeWithTag("browser-approval-dock").assertIsDisplayed()
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithTag("agent-approval-content").assertIsDisplayed()
        dismissDetails()
        assertPending(original, result)

        compose.onNodeWithTag("workspace-approval-indicator").assertIsDisplayed()
        compose.onNodeWithTag("browser-approval-dock").assertDoesNotExist()
    }

    @Test(timeout = 20_000) fun upwardSwipeOnIndicatorReopensTheSameApproval() {
        val result = request()
        mount()
        val original = requireNotNull(approvals.pending.value)
        compose.onNodeWithText("Agent waiting for permission").performTouchInput {
            swipe(center, center + Offset(0f, -(touchSlop + 48f * density)), durationMillis = 240)
        }
        assertPending(original, result)

        compose.onNodeWithTag("agent-approval-content").assertIsDisplayed()
        compose.onNodeWithTag("workspace-approval-indicator").assertDoesNotExist()
    }

    @Test(timeout = 20_000) fun dismissingGenericDetailsAlreadyKeepsApprovalPending() {
        val result = request()
        mount()
        val original = requireNotNull(approvals.pending.value)
        compose.onNodeWithText("Review approval").performClick()
        compose.onNodeWithTag("agent-approval-content").assertIsDisplayed()
        dismissDetails()
        compose.onNodeWithTag("workspace-approval-indicator").assertIsDisplayed()
        compose.onNodeWithTag("agent-approval-content").assertDoesNotExist()
        assertPending(original, result)
    }
}
