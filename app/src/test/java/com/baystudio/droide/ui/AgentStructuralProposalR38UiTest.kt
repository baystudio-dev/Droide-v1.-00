package com.baystudio.droide.ui

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
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

/** One inert producer-to-view case, no ApprovalManager, services, backend, device or native rebuild. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentStructuralProposalR38UiTest {
    @get:Rule val compose = createComposeRule()
    private var host: AbstractComposeView? = null
    @After fun cleanup() {
        try { compose.runOnUiThread { host?.disposeComposition() } }
        finally { shadowOf(Looper.getMainLooper()).idle() }
    }
    @Test(timeout = 20_000) fun moveOnlyHeaderShowsCompleteSourceAndDestinationWithOneKnownZeroTotal() {
        val old = "src/" + "source_component_".repeat(4) + "Old.kt"
        val new = "dst/" + "destination_component_".repeat(4) + "New.kt"
        val change = AgentFileChanges.preview(new, "same\n", "same\n", old)
        val request = ApprovalRequest(38L, "apply_patch", "Move reviewed file", change.diff,
            action = "edit", resource = "$old, $new", proposedChangeTotals = AgentFileChanges.totals(listOf(change)))
        val decisions = mutableListOf<ApprovalResolution>()
        compose.setContent {
            val view = LocalView.current
            SideEffect { host = generateSequence(view as View?) { it.parent as? View }.filterIsInstance<AbstractComposeView>().firstOrNull() }
            DroideTheme { Box(Modifier.size(360.dp, 600.dp)) { AgentApprovalContent(request, 600.dp, decisions::add) } }
        }
        val label = "$old → $new"
        val node = compose.onNodeWithText(label).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(label, layout.layoutInput.text.text)
        assertTrue(layout.lineCount > 1)
        assertEquals(label.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        assertFalse((0 until layout.lineCount).any { layout.isLineEllipsized(it) })
        compose.onAllNodesWithText(label).assertCountEquals(1)
        compose.onAllNodesWithTag("agent-proposed-change-counts").assertCountEquals(1)
        val total = compose.onNodeWithTag("agent-proposed-change-counts").assertIsDisplayed()
            .assertContentDescriptionEquals("Proposed changes: 0 lines added, 0 lines removed. Not applied.")
        assertTrue(total.getUnclippedBoundsInRoot().bottom <= node.getUnclippedBoundsInRoot().top)
        compose.onNodeWithText("+0", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("-0", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Moved file (no content changes)").assertIsDisplayed()
        compose.onNodeWithText("Reject").assertIsDisplayed()
        compose.onNodeWithText("Allow once").assertIsDisplayed()
        assertTrue(decisions.isEmpty())
    }
}
