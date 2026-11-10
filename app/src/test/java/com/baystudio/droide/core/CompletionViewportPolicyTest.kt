package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class CompletionViewportPolicyTest {
    private fun place(v: CompletionViewport = CompletionViewport(0, 0, 600, 400), x: Float = 570f,
        top: Float = 80f, bottom: Float = 104f, width: Int = 320, height: Int = 180, row: Int = 48,
        margin: Int = 6, gap: Int = 4, minimum: Int = 96) = CompletionViewportPolicy.place(v, x, top, bottom, width, height, row, margin, gap, minimum)

    @Test fun rightEdgeMovesTheWholeListLeftWithoutNarrowingItsLabels() {
        val p = requireNotNull(place())
        assertEquals(320, p.width)
        assertEquals(274, p.left)
        assertEquals(594, p.left + p.width)
        assertFalse(p.above)
    }

    @Test fun leftEdgeAndPartiallyClippedParentsUseTheVisiblePane() {
        val p = requireNotNull(place(CompletionViewport(50, 20, 350, 360), x = 51f))
        assertEquals(56, p.left)
        assertEquals(288, p.width)
        assertTrue(p.top >= 26)
        assertTrue(p.left + p.width <= 344)
    }

    @Test fun keyboardConstrainedBottomCaretPlacesSuggestionsAboveWithoutScrollingCode() {
        val p = requireNotNull(place(CompletionViewport(0, 0, 360, 220), x = 348f, top = 176f, bottom = 200f))
        assertTrue(p.above)
        assertEquals(172, p.top + p.height)
        assertEquals(166, p.height)
        assertEquals(320, p.width)
    }

    @Test fun smallResultsPreferBelowAndTallResultsChooseTheLargerSide() {
        val short = requireNotNull(place(top = 220f, bottom = 244f, height = 48))
        assertFalse(short.above)
        val tall = requireNotNull(place(top = 220f, bottom = 244f, height = 240))
        assertTrue(tall.above)
        assertEquals(210, tall.height)
    }

    @Test fun aNarrowPaneShrinksOnlyToItsAvailableWidthAndNeverPastItsBoundary() {
        val p = requireNotNull(place(CompletionViewport(0, 0, 200, 400), x = 190f))
        assertEquals(188, p.width)
        assertEquals(6, p.left)
    }

    @Test fun noRoomNeverCreatesZeroHeightOrUnusableOnePixelSuggestionStrips() {
        assertNull(place(CompletionViewport(0, 0, 360, 60), top = 20f, bottom = 44f))
        assertNull(place(CompletionViewport(0, 0, 60, 400), x = 50f))
        assertNull(place(CompletionViewport(0, 0, 0, 400), x = 0f))
        assertNull(place(row = 0))
    }

    @Test fun offscreenOrNonfiniteCaretsAndEmptyResultsAreNotDisplayed() {
        assertNull(place(x = Float.NaN))
        assertNull(place(top = Float.POSITIVE_INFINITY))
        assertNull(place(x = 601f))
        assertNull(place(top = 410f, bottom = 434f))
        assertNull(place(top = -30f, bottom = -6f))
        assertNull(place(top = 30f, bottom = 20f))
        assertNull(place(height = 0))
    }

    @Test fun densityAndNonzeroPaneOriginsPreservePixelGeometry() {
        for (scale in listOf(1, 2, 3, 4)) {
            val p = requireNotNull(place(CompletionViewport(30 * scale, 40 * scale, 390 * scale, 340 * scale),
                x = 380f * scale, top = 280f * scale, bottom = 304f * scale,
                width = 320 * scale, height = 180 * scale, row = 48 * scale,
                margin = 6 * scale, gap = 4 * scale, minimum = 96 * scale))
            assertEquals(320 * scale, p.width)
            assertTrue(p.above)
            assertEquals(384 * scale, p.left + p.width)
            assertEquals(276 * scale, p.top + p.height)
        }
    }

    @Test fun aBroadPortraitLandscapeAndImeMatrixKeepsEveryVisiblePopupInsideItsPane() {
        var cases = 0
        var visible = 0
        for (width in listOf(60, 120, 200, 360, 600, 1000)) for (height in listOf(40, 100, 180, 300, 640))
            for (origin in listOf(0, 31, 120)) for (fractionX in listOf(0f, .1f, .5f, .95f, 1f))
                for (fractionY in listOf(0f, .1f, .5f, .9f, 1f)) for (wanted in listOf(48, 144, 240)) {
                    val v = CompletionViewport(origin, origin, origin + width, origin + height)
                    val caretBottom = origin + height * fractionY
                    val p = place(v, origin + width * fractionX, caretBottom - 24, caretBottom, height = wanted)
                    cases++
                    if (p != null) {
                        visible++
                        assertTrue(p.left >= v.left + 6)
                        assertTrue(p.top >= v.top + 6)
                        assertTrue(p.left + p.width <= v.right - 6)
                        assertTrue(p.top + p.height <= v.bottom - 6)
                        assertTrue(p.width >= 96 && p.height >= 48)
                        if (p.above) assertTrue(p.top + p.height <= caretBottom - 24 - 4)
                        else assertTrue(p.top >= caretBottom + 4)
                    }
                }
        assertEquals(6750, cases)
        assertTrue(visible > 1000)
    }
}
