package com.baystudio.droide.ui

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.AgentChangeTotals
import com.baystudio.droide.core.AgentFileChanges
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
 * R34 final-API UI acceptance candidate. Authored only until independently reviewed.
 * R36 adapter: visible permission-only label is now "Proposal total".
 * Fixtures, semantic preapproval disclosures, bounds, and callback checks are unchanged.
 * Original baseline inputs/assertions remain; known fixtures now explicitly pass
 * proposedChangeTotals. Keep the separately preserved R33 baseline and its results.
 * Added cases cover clipping, unknown totals, request replacement, and font wrapping.
 * No AgentService, provider, file mutation, process, device, or network fixture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentPermissionCountsR34UiTest {
    @get:Rule val compose = createComposeRule()
    private var compositionHost: AbstractComposeView? = null
    private val size = mutableStateOf(360.dp to 360.dp)
    private val fontScale = mutableStateOf(1f)
    private val pending = mutableStateOf<ApprovalRequest?>(null)

    private fun request(detail: String, totals: AgentChangeTotals? = null) = ApprovalRequest(
        id = 34L, kind = "edit_file", summary = "Edit src/Main.kt", detail = detail,
        action = "edit", resource = "src/Main.kt", permissionScope = "build",
        approvalSessionId = "fixture-session", sessionGrantAction = "edit",
        sessionGrantResource = "src/Main.kt", proposedChangeTotals = totals,
    )

    private fun mount(request: ApprovalRequest, onResolve: (ApprovalResolution) -> Unit = {}) {
        pending.value = request
        compose.setContent {
            val view = LocalView.current
            SideEffect {
                compositionHost = generateSequence(view as View?) { it.parent as? View }
                    .filterIsInstance<AbstractComposeView>().firstOrNull()
            }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                DroideTheme {
                    Box(Modifier.size(size.value.first, size.value.second)) {
                        AgentApprovalContent(requireNotNull(pending.value), size.value.second, onResolve)
                    }
                }
            }
        }
    }

    @After fun cleanup() {
        compose.runOnUiThread { compositionHost?.disposeComposition() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun assertFooterInside(width: Dp, height: Dp) {
        listOf("Reject", "Allow once", "Always allow for this session").forEach { label ->
            val node = compose.onNodeWithText(label).assertIsDisplayed()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue("$label escaped ${width} x $height: $bounds",
                bounds.left >= 0.dp && bounds.top >= 0.dp && bounds.right <= width && bounds.bottom <= height)
        }
    }

    @Test(timeout = 20_000) fun proposedExactCountsAreVisibleBeforeAnyApproval() {
        val change = AgentFileChanges.preview("src/Main.kt",
            "val keep = 1\nval old = 2\n", "val keep = 1\nval revised = 2\nval added = 3\n")
        assertEquals(2, change.addedLines)
        assertEquals(1, change.removedLines)
        assertFalse(change.truncated)
        val decisions = mutableListOf<ApprovalResolution>()
        // Deliberate final-API fixture delta: carry full counts separately from detail.
        mount(request(change.diff, AgentFileChanges.totals(listOf(change))), decisions::add)
        compose.onNodeWithText("Permission required").assertIsDisplayed()
        assertFooterInside(360.dp, 360.dp)
        assertTrue("Preview must precede approval", decisions.isEmpty())
        // One diagnostic preserves all missing expected labels in a baseline failure.
        val missing = listOf("Proposal total", "+2", "-1").filter { label ->
            compose.onAllNodesWithText(label).fetchSemanticsNodes().isEmpty()
        }
        assertTrue("Missing pre-approval preview labels: $missing", missing.isEmpty())
        listOf("Proposal total", "+2", "-1").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test(timeout = 20_000) fun cappedDetailWithoutMetadataNeverInventsExactOrZeroCounts() {
        // About 10 KiB total input, capped exactly as ApprovalManager currently does.
        val change = AgentFileChanges.preview("src/Main.kt", null,
            "val boundedPreviewLine = \"some proposed text\"\n".repeat(200))
        assertEquals(200, change.addedLines)
        assertTrue("Fixture must cross the existing manager cap", change.diff.length > 8_000)
        val detail = change.diff.take(8_000)
        mount(request(detail))
        val exactSignedCount = SemanticsMatcher("a compact exact signed line count") { node ->
            node.config.contains(SemanticsProperties.Text) && node.config[SemanticsProperties.Text].any {
                it.text.matches(Regex("[+-][0-9]+"))
            }
        }
        // No count metadata reaches this legacy request. Even visible diff-line counts
        // are incomplete; displaying those, +0, or -0 as exact totals would be false.
        compose.onAllNodes(exactSignedCount).assertCountEquals(0)
        compose.onNodeWithText("+0").assertDoesNotExist()
        compose.onNodeWithText("-0").assertDoesNotExist()
        assertFooterInside(360.dp, 360.dp)
    }

    @Test(timeout = 20_000)
    @Config(qualifiers = "w720dp-h360dp-land-mdpi")
    fun compactAndLandscapeFooterActionsRemainVisibleAndResolveCorrectly() {
        val change = AgentFileChanges.preview("src/Main.kt", "val old = 1\n", "val revised = 2\n")
        val decisions = mutableListOf<ApprovalResolution>()
        size.value = 320.dp to 170.dp
        mount(request(change.diff, AgentFileChanges.totals(listOf(change))), decisions::add)
        listOf(320.dp to 170.dp, 720.dp to 190.dp).forEach { viewport ->
            compose.runOnIdle { size.value = viewport }
            assertFooterInside(viewport.first, viewport.second)
            compose.onNodeWithText("Allow once").performClick()
            compose.onNodeWithText("Always allow for this session").performClick()
            compose.onNodeWithText("Reject").performClick()
        }
        assertEquals(List(2) {
            listOf(ApprovalResolution.ALLOW_ONCE, ApprovalResolution.ALLOW_SESSION, ApprovalResolution.DENY)
        }.flatten(), decisions)
    }

    @Test(timeout = 20_000) fun unknownMetadataIsDisclosedWithoutFabricatedZeroCounts() {
        mount(request("Preview unavailable: open the editor to review the changes.",
            AgentChangeTotals(null, null)))
        compose.onNodeWithText("Proposal total").assertIsDisplayed()
        compose.onNodeWithText("Count unavailable").assertIsDisplayed()
        compose.onNodeWithText("+0").assertDoesNotExist()
        compose.onNodeWithText("-0").assertDoesNotExist()
        compose.onNodeWithContentDescription(
            "Proposed changes: line counts unavailable. Not applied.").assertIsDisplayed()
        assertFooterInside(360.dp, 360.dp)
    }

    @Test(timeout = 20_000) fun clippedPreviewAndDetailRetainFullKnownCounts() {
        // Same bounded source as the metadata-absent guard, now clipped twice:
        // the preview stream and the existing manager's 8,000-character detail cap.
        val change = AgentFileChanges.preview("src/Main.kt", null,
            "val boundedPreviewLine = \"some proposed text\"\n".repeat(200), limit = 8_192)
        assertEquals(200, change.addedLines)
        assertEquals(0, change.removedLines)
        assertTrue(change.truncated)
        assertTrue(change.diff.length > 8_000)
        val detail = change.diff.take(8_000)
        assertEquals(8_000, detail.length)
        mount(request(detail, AgentFileChanges.totals(listOf(change))))
        compose.onNodeWithText("Proposal total").assertIsDisplayed()
        compose.onNodeWithText("+200").assertIsDisplayed()
        compose.onNodeWithText("-0").assertIsDisplayed()
        compose.onNodeWithText("Count unavailable").assertDoesNotExist()
        compose.onNodeWithContentDescription(
            "Proposed changes: 200 lines added, 0 lines removed. Not applied.").assertIsDisplayed()
        assertFooterInside(360.dp, 360.dp)
    }

    @Test(timeout = 20_000) fun replacingFileApprovalWithNonFileApprovalClearsOldCounts() {
        val change = AgentFileChanges.preview("src/Main.kt", null, "val proposed = 1\n")
        val decisions = mutableListOf<ApprovalResolution>()
        mount(request(change.diff, AgentFileChanges.totals(listOf(change))), decisions::add)
        compose.onNodeWithText("+1").assertIsDisplayed()
        compose.onNodeWithContentDescription(
            "Proposed changes: 1 lines added, 0 lines removed. Not applied.").assertIsDisplayed()
        compose.runOnIdle {
            pending.value = ApprovalRequest(id = 35L, kind = "websearch",
                summary = "Search documentation", detail = "documentation query",
                action = "websearch", resource = "documentation")
        }
        compose.onNodeWithText("Search documentation").assertIsDisplayed()
        compose.onNodeWithTag("agent-proposed-change-counts").assertDoesNotExist()
        compose.onNodeWithText("Proposal total").assertDoesNotExist()
        compose.onNodeWithText("+1").assertDoesNotExist()
        compose.onNodeWithText("-0").assertDoesNotExist()
        compose.onNodeWithContentDescription("Proposed changes:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Always allow for this session").assertDoesNotExist()
        compose.onNodeWithText("Reject").assertIsDisplayed()
        compose.onNodeWithText("Allow once").assertIsDisplayed().performClick()
        assertEquals(listOf(ApprovalResolution.ALLOW_ONCE), decisions)
    }

    @Test(timeout = 20_000) fun narrowLargeFontCountsWrapAndRetainAccessibleProposedMeaning() {
        size.value = 220.dp to 440.dp
        fontScale.value = 1.5f
        // Inert presentation values exercise long numeric labels without a large diff.
        mount(request("Preview omitted by this UI-only fixture.",
            AgentChangeTotals(123456789L, 987654321L)))
        // Counts now belong to the scrollable preview; bring the whole strip into view.
        compose.onNodeWithTag("agent-proposed-change-counts").performScrollTo()
        compose.onNodeWithContentDescription(
            "Proposed changes: 123456789 lines added, 987654321 lines removed. Not applied.")
            .assertIsDisplayed()
        val bounds = listOf("Proposal total", "+123456789", "-987654321").map { label ->
            compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed().getUnclippedBoundsInRoot()
        }
        bounds.forEach { box ->
            assertTrue("Large-font count escaped the narrow viewport: $box",
                box.left >= 0.dp && box.right <= 220.dp && box.top >= 0.dp && box.bottom <= 440.dp)
        }
        assertTrue("The narrow FlowRow should wrap onto more than one row: $bounds",
            bounds.maxOf { it.top.value } > bounds.minOf { it.top.value })
        assertFooterInside(220.dp, 440.dp)
    }
}
