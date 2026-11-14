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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.AgentChangeTotals
import com.baystudio.droide.core.ApprovalRequest
import com.baystudio.droide.core.ApprovalResolution
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * R36 current/R35-API baseline: two expected failures and one positive control.
 * The requested placement is inside the preview, between its filename header
 * and first hunk. This is deliberately not the superseded beside-summary plan.
 * Keep this file unchanged when testing repaired source. Authored, not executed.
 * Inert UI only: no service, manager, filesystem mutation, process, or network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentPermissionPreviewCountsR36BaselineTest {
    @get:Rule val compose = createComposeRule()
    private var compositionHost: AbstractComposeView? = null
    private val decisions = mutableListOf<ApprovalResolution>()
    private val hunk = "@@ -1 +1,2 @@"

    private fun request(path: String, totals: AgentChangeTotals?) = ApprovalRequest(
        id = 36L, kind = "edit_file", summary = "Edit $path",
        // One real blank metadata row represents the black strip in the screenshot.
        detail = "--- a/$path\n+++ b/$path\n\n$hunk\n-val old = 1\n+val revised = 2\n+val added = 3",
        action = "edit", resource = path, permissionScope = "build",
        approvalSessionId = "fixture-session", sessionGrantAction = "edit",
        sessionGrantResource = path, proposedChangeTotals = totals,
    )

    private fun mount(request: ApprovalRequest) {
        compose.setContent {
            val view = LocalView.current
            SideEffect {
                compositionHost = generateSequence(view as View?) { it.parent as? View }
                    .filterIsInstance<AbstractComposeView>().firstOrNull()
            }
            DroideTheme {
                Box(Modifier.size(360.dp, 440.dp)) {
                    AgentApprovalContent(request, 440.dp, decisions::add)
                }
            }
        }
    }

    @After fun cleanup() {
        try {
            compose.runOnUiThread { compositionHost?.disposeComposition() }
        } finally {
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun assertFooterAndNoResolution() {
        listOf("Reject", "Allow once", "Always allow for this session").forEach { label ->
            val bounds = compose.onNodeWithText(label).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("$label escaped the viewport: $bounds",
                bounds.left >= 0.dp && bounds.top >= 0.dp &&
                    bounds.right <= 360.dp && bounds.bottom <= 440.dp)
        }
        assertTrue("Rendering the proposal must not resolve it", decisions.isEmpty())
    }

    @Test(timeout = 20_000) fun totalsOccupyThePreviewStripBetweenFilenameAndFirstHunk() {
        val path = "src/Main.kt"
        mount(request(path, AgentChangeTotals(2L, 1L)))
        assertFooterAndNoResolution()
        compose.onNodeWithText("+2", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("-1", useUnmergedTree = true).assertIsDisplayed()
        val filename = compose.onNodeWithText(path).assertIsDisplayed().getUnclippedBoundsInRoot()
        val counts = compose.onNodeWithTag("agent-proposed-change-counts")
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        val firstHunk = compose.onNodeWithText(hunk).assertIsDisplayed().getUnclippedBoundsInRoot()
        // Expected R35 failure: counts precede the preview filename, outside its strip.
        assertTrue("Counts must follow the preview filename: filename=$filename counts=$counts hunk=$firstHunk",
            counts.top >= filename.bottom)
        assertTrue("Counts must precede the first hunk: counts=$counts hunk=$firstHunk",
            counts.bottom <= firstHunk.top)
    }

    @Test(timeout = 20_000) fun permissionPreviewKeepsTheWholeLongFilenameWithoutEllipsis() {
        val path = "src/feature/" + "deeply_nested_component_".repeat(7) + "Main.kt"
        mount(request(path, AgentChangeTotals(2L, 1L)))
        assertFooterAndNoResolution()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(path).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("The actual filename Text must expose one measured layout", 1, layouts.size)
        val layout = layouts.single()
        assertEquals("Semantics must retain the exact filename", path, layout.layoutInput.text.text)
        // Expected R35 failure: DiffRow.FileHeader is one line with Ellipsis.
        assertFalse("Full filename must not be ellipsized; lineCount=${layout.lineCount}",
            (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
        assertTrue("This bounded long filename must wrap onto multiple lines", layout.lineCount > 1)
        assertFalse("Wrapped filename must not have hidden overflow", layout.hasVisualOverflow)
    }

    @Test(timeout = 20_000) fun missingMetadataDoesNotFabricateCountsOrResolveApproval() {
        mount(request("src/Main.kt", totals = null))
        compose.onNodeWithText("src/Main.kt").assertIsDisplayed()
        compose.onNodeWithText(hunk).assertIsDisplayed()
        compose.onNodeWithTag("agent-proposed-change-counts").assertDoesNotExist()
        val signedCount = SemanticsMatcher("an exact signed line count") { node ->
            node.config.contains(SemanticsProperties.Text) &&
                node.config[SemanticsProperties.Text].any { it.text.matches(Regex("[+-][0-9]+")) }
        }
        compose.onAllNodes(signedCount, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithContentDescription("Proposed changes:", substring = true).assertDoesNotExist()
        assertFooterAndNoResolution()
    }
}
