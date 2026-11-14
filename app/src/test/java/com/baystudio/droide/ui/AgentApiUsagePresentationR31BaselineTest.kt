package com.baystudio.droide.ui

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * R31 proposed UI acceptance baselines, AUTHORING ONLY until independently reviewed.
 * Uses existing R30 production APIs only. No provider, AgentService, file, process,
 * device, network, durable ledger, or terminal fixture. At most 21 tiny requests.
 * Names/wording encode the proposed honest presentation contract, not execution results.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentApiUsagePresentationR31BaselineTest {
    @get:Rule val compose = createComposeRule()
    private var compositionHost: AbstractComposeView? = null

    private fun mount(content: @Composable () -> Unit) {
        compose.setContent {
            val view = LocalView.current
            SideEffect {
                compositionHost = generateSequence(view as View?) { it.parent as? View }
                    .filterIsInstance<AbstractComposeView>().firstOrNull()
            }
            DroideTheme { Box(Modifier.size(360.dp, 800.dp)) { content() } }
        }
    }

    @After fun cleanup() {
        compose.runOnUiThread { compositionHost?.disposeComposition() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test(timeout = 20_000) fun providerReportedZeroKeepsItsAuthorityAndHonestScope() {
        val tracker = TokenTracker()
        tracker.add("fixture", "x", """{"usage":{"prompt_tokens":0,"completion_tokens":0,"total_tokens":0,"cost":0}}""")
        val usage = tracker.usage.value
        mount { AgentUsageQuickMenu(usage, false, {}, {}) }
        compose.onNodeWithText("This Agent since reset").assertExists()
        compose.onNodeWithText("This session").assertDoesNotExist()
        compose.onNodeWithText("Provider-reported tokens").assertExists()
        compose.onNodeWithText("Provider-reported cost: $0.00").assertExists()
    }

    @Test(timeout = 20_000) fun missingTelemetryIsNotAnExactZeroOrFreeRequest() {
        mount { AgentUsageQuickMenu(TokenUsage(), false, {}, {}) }
        compose.onNodeWithText("Token usage unavailable").assertExists()
        compose.onNodeWithText("Cost unavailable").assertExists()
        compose.onNodeWithText("$0.00", substring = true).assertDoesNotExist()
    }

    @Test(timeout = 20_000) fun persistedLegacyEstimateIsLabelledWhenRendered() {
        val part = AgentStreamPart(id = "legacy-estimate", step = 1, kind = AgentStreamPartKind.STEP,
            state = AgentStreamPartState.COMPLETED, title = "Step 1", promptTokens = 100,
            completionTokens = 23, totalTokens = 123, tokenUsageAuthority = UsageAuthority.ESTIMATED,
            costAuthority = UsageAuthority.UNKNOWN, startedAtMs = 0L, endedAtMs = 0L)
        mount { AgentStreamPartItem(part, showDetails = true) }
        compose.onNodeWithText("Estimated tokens", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Step 1").performClick()
        compose.onNodeWithText("Estimated tokens", substring = true).assertExists()
        compose.onNodeWithText("Cost unavailable", substring = true).assertExists()
        compose.onNodeWithText("$0.00", substring = true).assertDoesNotExist()
    }

    @Test(timeout = 20_000) fun partialCostIsAKnownSubtotalWithMissingCoverage() {
        val tracker = TokenTracker()
        tracker.add("fixture", "x", """{"usage":{"prompt_tokens":2,"completion_tokens":1,"total_tokens":3,"cost":0.25}}""")
        tracker.add("fixture", "x", """{"usage":{"prompt_tokens":2,"completion_tokens":1,"total_tokens":3}}""")
        val usage = tracker.usage.value
        mount { AgentUsageQuickMenu(usage, false, {}, {}) }
        compose.onNodeWithText("Known cost subtotal: $0.25").assertExists()
        compose.onNodeWithText("Cost unavailable for 1 of 2 requests").assertExists()
        compose.onNodeWithText("≈$0.25", substring = true).assertDoesNotExist()
    }

    @Test(timeout = 20_000) fun providerTotalOnlyDoesNotInventAnInputOutputBreakdown() {
        val tracker = TokenTracker()
        tracker.add("fixture", "x", """{"usage":{"total_tokens":77}}""")
        val usage = tracker.usage.value
        mount { AgentUsageQuickMenu(usage, false, {}, {}) }
        compose.onNodeWithText("77").assertExists()
        compose.onNodeWithText("Provider-reported tokens").assertExists()
        compose.onNodeWithText("Input/output breakdown unavailable").assertExists()
        compose.onNodeWithText("Input").assertDoesNotExist()
        compose.onNodeWithText("Output").assertDoesNotExist()
    }

    @Test(timeout = 20_000) fun suppliedTotalMismatchIsNotRenderedAsFalseEquality() {
        val tracker = TokenTracker()
        tracker.add("fixture", "x", """{"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":15}}""")
        mount { BillingSheet(tracker, {}) }
        compose.onNodeWithText("Total tokens: 15 (prompt 10 + completion 2)").assertDoesNotExist()
        compose.onNodeWithText("prompt 10 + completion 2 = 15").assertDoesNotExist()
        compose.onNodeWithText("Provider total may include tokens outside the input/output breakdown.", substring = true).assertExists()
    }

    @Test(timeout = 20_000) fun visibleHistoryDisclosesItsLimitAndRecordedRequestCount() {
        val tracker = TokenTracker()
        repeat(21) { i -> tracker.add("fixture", "request $i", """{"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""") }
        mount { BillingSheet(tracker, {}) }
        compose.onNodeWithText("21 recorded requests").assertExists()
        compose.onNodeWithText("Showing latest 20 of 21 recorded requests").assertExists()
        compose.onNodeWithText("Cumulative recorded requests, not current context.").assertExists()
    }

    @Test(timeout = 20_000) fun cliContextLabelsCurrencyAndPercentageRemainUnchanged() {
        val cli = WorkspaceAgentUsageSnapshot(96_000L, 128_000L, 1.25, "EUR")
        mount { Column {
            ExternalAgentUsageHeaderButton(cli, 360.dp, {})
            ExternalAgentUsageQuickMenu(cli)
        } }
        compose.onNodeWithContentDescription("CLI context usage").assertExists()
        compose.onNodeWithText("75%").assertExists()
        compose.onNodeWithText("Last reported context usage").assertExists()
        compose.onNodeWithText("96.0k / 128.0k tokens").assertExists()
        compose.onNodeWithText("EUR 1.25").assertExists()
        compose.onNodeWithText("This Agent since reset").assertDoesNotExist()
    }
}
