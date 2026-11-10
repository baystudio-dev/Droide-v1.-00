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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
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

/** Seven bounded acceptance cases, authored only until coordinator validation. */

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentPermissionPreviewCountsUiTest {
    @get:Rule val compose = createComposeRule()
    private var compositionHost: AbstractComposeView? = null
    private val size = mutableStateOf(360.dp to 600.dp)
    private val fontScale = mutableStateOf(1f)
    private val pending = mutableStateOf<ApprovalRequest?>(null)
    private val sharedDiff = mutableStateOf(false)
    private val decisions = mutableListOf<ApprovalResolution>()
    private val hunk = "@@ -1 +1,2 @@"
    private val countTag = "agent-proposed-change-counts"

    private fun diff(path: String = "src/Main.kt") =
        "--- a/$path\n+++ b/$path\n\n$hunk\n-val old = 1\n+val revised = 2\n+val added = 3"

    private fun request(
        path: String = "src/Main.kt",
        detail: String = diff(path),
        totals: AgentChangeTotals? = AgentChangeTotals(2L, 1L),
    ) = ApprovalRequest(
        id = 36L, kind = "edit_file", summary = "Edit $path", detail = detail,
        action = "edit", resource = path, permissionScope = "build",
        approvalSessionId = "fixture-session", sessionGrantAction = "edit",
        sessionGrantResource = path, proposedChangeTotals = totals,
    )

    private fun mount(request: ApprovalRequest) {
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
                        val current = requireNotNull(pending.value)
                        if (sharedDiff.value) DiffText(current.detail, path = current.resource)
                        else AgentApprovalContent(current, size.value.second, decisions::add)
                    }
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

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals("Text must expose its actual measured layout", 1, results.size)
        return results.single()
    }

    private fun assertCompleteText(text: String, color: Color? = null): SemanticsNodeInteraction {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
        val measured = layout(node)
        assertEquals(text, measured.layoutInput.text.text)

        assertTrue("Text must have a visible line: $text", measured.lineCount > 0)
        assertEquals("The final character must remain visible: $text", text.length,
            measured.getLineEnd(measured.lineCount - 1, visibleEnd = true))
        assertFalse("Text was ellipsized: $text",
            (0 until measured.lineCount).any { measured.isLineEllipsized(it) })
        (0 until measured.lineCount).forEach { line ->
            val left = measured.getLineLeft(line)
            val right = measured.getLineRight(line)
            val top = measured.getLineTop(line)
            val bottom = measured.getLineBottom(line)
            assertTrue("Visible line escaped actual Text bounds: $text line=$line " +
                "left=$left right=$right top=$top bottom=$bottom size=${measured.size}",
                left >= 0f && right <= measured.size.width.toFloat() &&
                    top >= 0f && bottom <= measured.size.height.toFloat())
        }
        if (color != null) assertEquals("Signed count color changed: $text", color, measured.layoutInput.style.color)
        return node
    }

    private fun assertKnownCounts(added: Long, removed: Long) {
        compose.onAllNodesWithTag(countTag).assertCountEquals(1)
        compose.onNodeWithTag(countTag).assertIsDisplayed().assertContentDescriptionEquals(
            "Proposed changes: $added lines added, $removed lines removed. Not applied.")
        compose.onAllNodesWithText("Proposal total", useUnmergedTree = true).assertCountEquals(1)
        assertCompleteText("Proposal total")
        assertCompleteText("+$added", DroideColors.Success)
        assertCompleteText("-$removed", DroideColors.Error)
        compose.onNodeWithText("Proposed", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Count unavailable").assertDoesNotExist()
    }

    private fun assertNoSignedCounts() {
        val signedCount = SemanticsMatcher("an exact signed line count") { node ->
            node.config.contains(SemanticsProperties.Text) &&
                node.config[SemanticsProperties.Text].any { it.text.matches(Regex("[+-][0-9]+")) }
        }
        compose.onAllNodes(signedCount, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("+0", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("-0", useUnmergedTree = true).assertDoesNotExist()
    }

    private fun assertFooter(session: Boolean = true) {
        val labels = listOf("Reject", "Allow once") + if (session) listOf("Always allow for this session") else emptyList()
        labels.forEach { label ->
            val bounds = compose.onNodeWithText(label).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("$label escaped ${size.value}: $bounds",
                bounds.left >= 0.dp && bounds.top >= 0.dp &&
                    bounds.right <= size.value.first && bounds.bottom <= size.value.second)
        }
        if (!session) compose.onNodeWithText("Always allow for this session").assertDoesNotExist()
    }

    private fun assertUnresolved(expected: ApprovalRequest) {
        compose.runOnIdle {
            assertSame("Rendering must retain the exact immutable request", expected, pending.value)
            assertTrue("Rendering, resizing, and scrolling must not resolve permission", decisions.isEmpty())
        }
    }

    private fun assertInStrip(path: String, header: String = hunk) {
        val filename = compose.onNodeWithText(path).assertIsDisplayed().getUnclippedBoundsInRoot()
        val counts = compose.onNodeWithTag(countTag).assertIsDisplayed().getUnclippedBoundsInRoot()
        val firstHunk = compose.onNodeWithText(header).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("Counts must follow the actual file header: filename=$filename counts=$counts",
            counts.top >= filename.bottom)

        assertTrue("Counts must immediately precede the first hunk: counts=$counts hunk=$firstHunk",
            counts.bottom <= firstHunk.top && firstHunk.top - counts.bottom <= 1.dp + 3.dp)
        assertTrue("Count strip must stay inside the preview width: counts=$counts filename=$filename",
            counts.left >= filename.left && counts.right <= filename.right)
    }

    @Test(timeout = 20_000) fun exactSignedTotalsReplaceTheBlankStripAndKnownZeroStaysKnown() {
        val original = request()
        mount(original)
        assertKnownCounts(2L, 1L)
        assertInStrip("src/Main.kt")

        compose.onNodeWithText(" ", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("+val revised = 2").assertIsDisplayed()
        assertFooter()
        assertUnresolved(original)

        val zero = request(detail = "(no content changes)", totals = AgentChangeTotals(0L, 0L))
        compose.runOnIdle { pending.value = zero }
        assertKnownCounts(0L, 0L)
        val counts = compose.onNodeWithTag(countTag).getUnclippedBoundsInRoot()
        val fallback = compose.onNodeWithText("diff").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("No-hunk total must remain inside the preview above its excerpt", counts.bottom <= fallback.top)
        compose.onNodeWithText("(no content changes)").assertIsDisplayed()
        assertFooter()
        assertUnresolved(zero)
    }

    @Test(timeout = 20_000) fun fullLongFilenameWrapsInPermissionWhileSharedDiffDefaultsStayUnchanged() {
        val path = "src/feature/" + "deeply_nested_component_".repeat(7) + "Main.kt"
        val original = request(path = path)
        mount(original)
        val measured = layout(assertCompleteText(path))
        assertTrue("The permission filename must wrap instead of hiding its suffix", measured.lineCount > 1)
        assertCompleteText("Edit $path")
        assertInStrip(path)
        assertKnownCounts(2L, 1L)
        assertFooter()
        assertUnresolved(original)

        compose.runOnIdle { sharedDiff.value = true }
        val shared = layout(compose.onNodeWithText(path).assertIsDisplayed())
        assertEquals("Unrelated Git/tool diff defaults must retain their original one-line header", 1, shared.lineCount)
        assertTrue("Default diff header behavior changed outside permission", shared.isLineEllipsized(0))
        compose.onNodeWithTag(countTag).assertDoesNotExist()
        compose.runOnIdle { sharedDiff.value = false }
        assertTrue(layout(assertCompleteText(path)).lineCount > 1)
        assertFooter()
        assertUnresolved(original)
    }

    @Test(timeout = 20_000) fun wholeProposalTotalFollowsLeadingNoopAndPreservesMetadataAndCode() {
        val firstHunk = "@@ -1 +1,2 @@ first"
        val secondHunk = "@@ -0,0 +1,2 @@ second"
        val metadata = "index 1234567..7654321 100644"
        val detail = "--- a/README.md\n+++ b/README.md\nunchanged file metadata\n" +
            "diff --git a/src/Main.kt b/src/Main.kt\n$metadata\n--- a/src/Main.kt\n+++ b/src/Main.kt\n\n \n" +
            "$firstHunk\n-val old = 1\n+val revised = 2\n+val added = 3\n" +
            "diff --git a/src/Other.kt b/src/Other.kt\n--- /dev/null\n+++ b/src/Other.kt\n" +
            "$secondHunk\n+val other = 4\n+val extra = 5"
        val original = request(detail = detail, totals = AgentChangeTotals(4L, 1L))
            .copy(summary = "Apply reviewed patch", action = "", resource = "")
        mount(original)
        assertKnownCounts(4L, 1L)
        assertInStrip("src/Main.kt", firstHunk)
        val counts = compose.onNodeWithTag(countTag).getUnclippedBoundsInRoot()
        listOf("README.md", "unchanged file metadata", metadata, " ").forEach { text ->
            val bounds = compose.onNodeWithText(text, useUnmergedTree = true)
                .assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("Real leading metadata must remain before the total: $text", bounds.bottom <= counts.top)
        }
        // A whitespace-only metadata line is meaningful input and is not the empty-string spacer. Only
        // the actual empty spacer is replaced.
        compose.onAllNodesWithText(" ", useUnmergedTree = true).assertCountEquals(1)
        listOf("-val old = 1", "+val revised = 2", "+val added = 3", "src/Other.kt",
            secondHunk, "+val other = 4", "+val extra = 5").forEach { text ->
            compose.onNodeWithText(text).assertExists()
        }
        val added = compose.onNodeWithText("+val revised = 2").fetchSemanticsNode()
        assertTrue("Code syntax spans must remain intact", added.config[SemanticsProperties.Text].single().spanStyles.isNotEmpty())
        assertFooter()
        assertUnresolved(original)
    }

    @Test(timeout = 20_000) fun unknownNoHunkFallbacksRemainTruthfulAndReplacementClearsCounts() {
        val unknown = request(detail = "Preview unavailable: open the editor to review the changes.",
            totals = AgentChangeTotals(null, null))
        mount(unknown)
        listOf(unknown, unknown.copy(detail = ""), unknown.copy(kind = "future_tool",
            summary = "Review future proposal", detail = "A non-diff proposal excerpt")).forEach { current ->
            compose.runOnIdle { pending.value = current }
            compose.onAllNodesWithTag(countTag).assertCountEquals(1)
            compose.onNodeWithTag(countTag).assertIsDisplayed().assertContentDescriptionEquals(
                "Proposed changes: line counts unavailable. Not applied.")
            compose.onNodeWithText("Proposal total", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("Count unavailable", useUnmergedTree = true).assertIsDisplayed()
            assertNoSignedCounts()
            val counts = compose.onNodeWithTag(countTag).getUnclippedBoundsInRoot()
            val excerpt = when {
                current.kind == "future_tool" -> current.detail
                current.detail.isEmpty() -> "(no diff)"
                else -> "diff"
            }
            val below = compose.onNodeWithText(excerpt).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("No-hunk fallback must precede the visible excerpt inside its preview", counts.bottom <= below.top)
            val resource = compose.onNodeWithText("edit · src/Main.kt").getUnclippedBoundsInRoot()
            assertTrue("Fallback must stay below the outside-preview resource line", counts.top >= resource.bottom)
            assertFooter()
            assertUnresolved(current)
        }
        val known = request(totals = AgentChangeTotals(7L, 4L))
        compose.runOnIdle { pending.value = known }
        assertKnownCounts(7L, 4L)
        val absent = ApprovalRequest(37L, "websearch", "Search documentation", "documentation query",
            action = "websearch", resource = "documentation")
        compose.runOnIdle { pending.value = absent }
        compose.onNodeWithTag(countTag).assertDoesNotExist()
        compose.onNodeWithText("Proposal total", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Count unavailable", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Proposed changes:", substring = true).assertDoesNotExist()
        assertNoSignedCounts()
        compose.onNodeWithText("Search documentation").assertIsDisplayed()
        compose.onNodeWithText("documentation query").assertIsDisplayed()
        assertFooter(session = false)
        assertUnresolved(absent)
    }

    @Test(timeout = 20_000) fun narrowLargeFontAndLongCountsStayExactBoundedAndAccessible() {
        size.value = 220.dp to 600.dp
        val original = request(totals = AgentChangeTotals(123456789L, 987654321L))
        fontScale.value = 1.5f
        mount(original)
        listOf(1.5f to AgentChangeTotals(123456789L, 987654321L),
            2f to AgentChangeTotals(Long.MAX_VALUE, Long.MAX_VALUE)).forEach { (scale, totals) ->

            val current = if (totals == original.proposedChangeTotals) original
                else original.copy(proposedChangeTotals = totals)
            compose.runOnIdle { fontScale.value = scale; pending.value = current }
            compose.onNodeWithTag(countTag).performScrollTo()
            assertKnownCounts(requireNotNull(totals.addedLines), requireNotNull(totals.removedLines))
            val strip = compose.onNodeWithTag(countTag).getUnclippedBoundsInRoot()
            listOf("Proposal total", "+${totals.addedLines}", "-${totals.removedLines}").forEach { text ->
                val bounds = compose.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot()
                assertTrue("Large-font count escaped its strip: $text $bounds strip=$strip",
                    bounds.left >= strip.left && bounds.right <= strip.right &&
                        bounds.top >= strip.top && bounds.bottom <= strip.bottom)
                assertTrue("Large-font count escaped the viewport: $text $bounds",
                    bounds.left >= 0.dp && bounds.right <= 220.dp && bounds.top >= 0.dp && bounds.bottom <= 600.dp)
            }
            assertFooter()
            assertUnresolved(current)
        }
    }

    @Test(timeout = 20_000) fun cappedMetadataPreludeKeepsOneTotalBeforeTheVisibleExcerpt() {
        val metadata = (0 until 210).joinToString("\n") { "fixture metadata $it" }
        val detail = "--- a/src/Main.kt\n+++ b/src/Main.kt\n$metadata\n$hunk\n" +
            "-val old = 1\n+val revised = 2\n+val added = 3"
        assertTrue("Fixture must stay below the existing character cap", detail.length < 8_000)
        val original = request(detail = detail)
        mount(original)
        assertKnownCounts(2L, 1L)
        val counts = compose.onNodeWithTag(countTag).getUnclippedBoundsInRoot()
        val filename = compose.onNodeWithText("src/Main.kt").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("Hidden first hunk must not hide authoritative totals", counts.bottom <= filename.top)
        compose.onNodeWithText("fixture metadata 0").assertIsDisplayed()
        compose.onNodeWithText("fixture metadata 198").assertExists()
        compose.onNodeWithText("fixture metadata 199").assertDoesNotExist()
        compose.onNodeWithText(hunk).assertDoesNotExist()
        compose.onNodeWithText("+val revised = 2").assertDoesNotExist()
        compose.onNodeWithText("Diff preview shortened. Open the file to review the remaining changes.").assertExists()
        assertFooter()
        assertUnresolved(original)
    }

    @Test(timeout = 20_000)
    @Config(qualifiers = "w720dp-h360dp-land-mdpi")
    fun compactAndLandscapeFootersResolveOnlyTheExplicitlyPressedActions() {
        size.value = 320.dp to 170.dp
        val original = request()
        mount(original)
        listOf(320.dp to 170.dp, 720.dp to 190.dp).forEachIndexed { index, viewport ->
            compose.runOnIdle { size.value = viewport }
            // At 170 dp a readable preview and the full footer cannot coexist. The sheet scrolls as a whole;
            // resizing/scrolling still must not resolve the request.
            compose.onNodeWithText("Always allow for this session").performScrollTo()
            assertFooter()
            compose.runOnIdle {
                assertSame(original, pending.value)
                assertEquals("Layout changes must not resolve approval", index * 3, decisions.size)
            }
            compose.onNodeWithText("Allow once").performClick()
            compose.onNodeWithText("Always allow for this session").performClick()
            compose.onNodeWithText("Reject").performClick()
        }
        assertEquals(List(2) {
            listOf(ApprovalResolution.ALLOW_ONCE, ApprovalResolution.ALLOW_SESSION, ApprovalResolution.DENY)
        }.flatten(), decisions)
        assertSame(original, pending.value)
    }
}
