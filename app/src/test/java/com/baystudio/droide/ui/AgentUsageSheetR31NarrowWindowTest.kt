package com.baystudio.droide.ui

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Short/narrow real sheet and legacy STEP controls, no network or AgentService. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentUsageSheetR31NarrowWindowTest {
    @get:Rule val compose = createComposeRule()
    private var compositionHost: AbstractComposeView? = null

    private fun mount(content: @Composable () -> Unit) {
        compose.setContent {
            val view = LocalView.current
            SideEffect {
                compositionHost = generateSequence(view as View?) { it.parent as? View }
                    .filterIsInstance<AbstractComposeView>().firstOrNull()
            }
            DroideTheme { Box(Modifier.size(220.dp, 260.dp)) { content() } }
        }
    }

    @After fun cleanup() {
        compose.runOnUiThread { compositionHost?.disposeComposition() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test(timeout = 20_000) fun resetAndCloseStayReachableWhenDisclosuresAndHistoryNeedScrolling() {
        val tracker = TokenTracker()
        repeat(21) { tracker.add("fixture", """{"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""") }
        var closed = false
        mount { BillingSheet(tracker) { closed = true } }
        compose.onNodeWithContentDescription("Close Token Usage").assertIsDisplayed()
        val scrollable = compose.onNode(hasScrollAction())
        // No warning: scope, summary and history heading are 3 items; the history
        // projection contains 20 rows, so reset is item 3 + 20 = 23 (zero-based).
        scrollable.performScrollToIndex(23)
        val reset = compose.onNodeWithText("Reset usage history")
        reset.assertIsDisplayed()
        reset.performClick()
        compose.runOnIdle {
            assertEquals(0L, tracker.snapshot.value.usage.requestCount)
            assertTrue(tracker.snapshot.value.history.isEmpty())
        }
        compose.onNodeWithContentDescription("Close Token Usage").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(closed) }
    }

    @Test(timeout = 20_000) fun persistedProviderZeroRemainsKnownInStepDetails() {
        val part = AgentStreamPart(id = "provider-zero", step = 1, kind = AgentStreamPartKind.STEP,
            state = AgentStreamPartState.COMPLETED, title = "Step 1", tokenUsageAuthority = UsageAuthority.AUTHORITATIVE,
            costAuthority = UsageAuthority.AUTHORITATIVE, startedAtMs = 0L, endedAtMs = 0L)
        mount { AgentStreamPartItem(part, showDetails = true) }
        compose.onNodeWithText("Provider-reported tokens").assertDoesNotExist()
        compose.onNodeWithText("Step 1").performClick()
        compose.onNodeWithText("0 tokens").assertExists()
        compose.onNodeWithText("Provider-reported tokens").assertExists()
        compose.onNodeWithText("Provider-reported cost: $0.00").assertExists()
        compose.onNodeWithText("Cost unavailable").assertDoesNotExist()
    }
}
