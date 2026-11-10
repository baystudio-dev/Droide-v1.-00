package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CutoutHeaderLayoutTest {
    @get:Rule val compose = createComposeRule()
    private var cutouts by mutableStateOf(emptyList<Rect>())
    private var top by mutableStateOf(0)
    private var endAlignActions by mutableStateOf(false)
    private var direction by mutableStateOf(LayoutDirection.Ltr)
    private var titleText by mutableStateOf("App.kt")
    private var titleGlyphBounds = Rect.Zero
    private val positions = mutableMapOf<HeaderPart, Rect>()
    private val events = mutableListOf<HeaderPart>()
    private val roles = listOf(HeaderPart.PROJECTS, HeaderPart.MCP, HeaderPart.TITLE, HeaderPart.RUN,
        HeaderPart.SETTINGS, HeaderPart.AGENT, HeaderPart.MORE)

    private fun mount(autoAdvance: Boolean = true) {
        compose.mainClock.autoAdvance = autoAdvance
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction,
                LocalHeaderCutouts provides HeaderCutoutSource { cutouts }) {
                Box(Modifier.fillMaxWidth().offset { IntOffset(0, top) }) {
                    CutoutHeaderRow(endAlignActions = endAlignActions) {
                        roles.forEach { role ->
                            val modifier = Modifier.headerPart(role).then(if (role == HeaderPart.TITLE) Modifier.height(24.dp) else Modifier.size(44.dp))
                                .testTag(role.name).onGloballyPositioned {
                                    positions[role] = Rect(it.positionInWindow(), Size(it.size.width.toFloat(), it.size.height.toFloat()))
                                }
                            if (role == HeaderPart.TITLE) {
                                Row(modifier, horizontalArrangement = Arrangement.Center) {
                                    Text(titleText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.onGloballyPositioned {
                                            titleGlyphBounds = Rect(it.positionInWindow(), Size(it.size.width.toFloat(), it.size.height.toFloat()))
                                        })
                                }
                            } else Box(modifier.clickable { events += role })
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertSafe() {
        compose.onNodeWithTag(HeaderPart.MORE.name).assertIsDisplayed()
        for (role in roles) {
            val node = compose.onAllNodesWithTag(role.name).fetchSemanticsNodes().singleOrNull() ?: continue
            if (!node.layoutInfo.isPlaced) continue
            val rect = positions.getValue(role)
            assertTrue("$role $rect", rect.left >= 0 && rect.right <= 360)
            for (cutout in cutouts) assertFalse("$role $rect intersects $cutout", rect.overlaps(cutout))
        }
    }

    @Test fun centeredCameraAvoidanceIsPresentOnFirstLayoutWithoutAdvancingAnimationClock() {
        cutouts = listOf(Rect(166f, 0f, 194f, 32f))
        mount(autoAdvance = false); assertSafe()
        assertEquals(0L, compose.mainClock.currentTime)
        compose.runOnIdle { assertEquals(44f, positions.getValue(HeaderPart.MORE).height, 0f) }
    }

    @Test fun cornerCameraAndMultipleCutoutsKeepEveryVisibleTouchTargetOutsideHardwareBounds() {
        cutouts = listOf(Rect(0f, 0f, 36f, 35f), Rect(168f, 0f, 192f, 32f), Rect(328f, 0f, 360f, 35f))
        mount(); assertSafe()
        compose.onNodeWithTag(HeaderPart.MORE.name).performClick()
        assertEquals(listOf(HeaderPart.MORE), events)
    }

    @Test fun wideNotchKeepsMoreAndMovesOptionalControlsOutOfTheTouchTree() {
        cutouts = listOf(Rect(50f, 0f, 304f, 38f))
        mount(); assertSafe()
        compose.onNodeWithTag(HeaderPart.SETTINGS.name).assertIsNotDisplayed()
        compose.onNodeWithTag(HeaderPart.RUN.name).assertIsNotDisplayed()
        compose.onNodeWithTag(HeaderPart.MORE.name).performClick()
        assertEquals(listOf(HeaderPart.MORE), events)
    }

    @Test fun windowPositionChangesAreHandledInTheSamePlacementPass() {
        cutouts = listOf(Rect(166f, 0f, 194f, 32f))
        mount(autoAdvance = false); assertSafe()
        for (offset in listOf(10, 20, 40, 0)) {
            compose.runOnIdle { top = offset }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertSafe()
            assertEquals(52f + offset, positions.getValue(HeaderPart.MORE).bottom + 4f, 0f)
        }
    }

    @Test fun rtlMirrorsActionOrderWithoutMirroringThePhysicalCutout() {
        direction = LayoutDirection.Rtl
        cutouts = listOf(Rect(0f, 0f, 36f, 35f))
        mount(); assertSafe()
        assertTrue(positions.getValue(HeaderPart.PROJECTS).left > positions.getValue(HeaderPart.MORE).left)
        compose.onNodeWithTag(HeaderPart.PROJECTS.name).performClick()
        assertEquals(listOf(HeaderPart.PROJECTS), events)
    }

    @Test fun geometryChangeRestoresControlsAndUsesFullHeaderWidth() {
        cutouts = listOf(Rect(50f, 0f, 304f, 38f))
        mount(); assertSafe()
        compose.runOnIdle { cutouts = emptyList() }
        compose.mainClock.advanceTimeByFrame()
        roles.filter { it != HeaderPart.TITLE }.forEach { compose.onNodeWithTag(it.name).assertIsDisplayed() }
        assertSafe()
        assertEquals(356f, positions.getValue(HeaderPart.MORE).right, 0f)
    }

    @Test fun fullWidthPhysicalCutoutHasNoSafeTopRowForAnOverflowControl() {
        val spans = HeaderCutoutGeometry.spans(360, 0f, 0f, 52f,
            listOf(Rect(0f, 0f, 360f, 30f)), 4, 4)
        assertTrue(spans.isEmpty())
        assertNull(HeaderCutoutGeometry.pack(listOf(44), spans, 2))
    }

    @Test fun shortCenteredRowTitleKeepsNaturalWidthAndLongTitleDoesNotMoveTrailingActions() {
        mount()
        val shortTitle = positions.getValue(HeaderPart.TITLE)
        val actions = roles.filter { it != HeaderPart.TITLE }.associateWith { positions.getValue(it) }
        assertEquals(shortTitle.width, titleGlyphBounds.width, 0f)
        assertEquals(shortTitle.left, titleGlyphBounds.left, 0f)
        assertTrue("Short title must not expand into its entire slot", shortTitle.width < 70f)
        compose.runOnIdle { titleText = "A very long project name ".repeat(30) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertTrue(positions.getValue(HeaderPart.TITLE).width > shortTitle.width)
        assertTrue(positions.getValue(HeaderPart.TITLE).right < positions.getValue(HeaderPart.RUN).left)
        actions.forEach { (role, position) -> assertEquals("$role stays anchored", position, positions.getValue(role)) }
    }
    @Test fun suffixAlignmentLeavesEveryLeadingPositionUnchangedAcrossCutoutLayouts() {
        val widths = listOf(44, 0, 182, 44, 44, 44, 44)
        val spans = listOf(HeaderCutoutGeometry.Span(4, 232), HeaderCutoutGeometry.Span(268, 496))
        val packed = checkNotNull(HeaderCutoutGeometry.pack(widths, spans, 2))
        assertEquals(listOf(4, null, 50, 268, 314, 360, 406), packed)
        val aligned = HeaderCutoutGeometry.alignActionSuffix(widths, packed, spans, 2, 3)
        assertEquals(listOf(4, null, 50, 314, 360, 406, 452), aligned)
        assertEquals(packed.take(3), aligned.take(3))
    }

    @Test fun endAlignmentKeepsCutoutSafetyWhenActionsOccupyMoreThanOneSafeSpan() {
        cutouts = listOf(Rect(166f, 0f, 194f, 32f))
        mount()
        val leading = roles.take(3).associateWith { positions.getValue(it) }
        compose.runOnIdle { endAlignActions = true }
        compose.waitForIdle()
        assertSafe()
        assertEquals(356f, positions.getValue(HeaderPart.MORE).right, 0f)
        leading.forEach { (role, position) -> assertEquals(role.name, position, positions.getValue(role)) }
        roles.drop(3).forEach { role ->
            compose.onNodeWithTag(role.name).performTouchInput { click(center) }
        }
        assertEquals(roles.drop(3), events)
    }

}
