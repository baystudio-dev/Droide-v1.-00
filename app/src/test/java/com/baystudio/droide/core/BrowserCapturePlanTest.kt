package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class BrowserCapturePlanTest {
    private fun geometry(width: Int = 1080, height: Int = 720, sourceHeight: Int = 2500,
        scale: Double? = 1.0, x: Int = 0, y: Int = 0) =
        AgentBrowserCapture.Geometry(width, height, sourceHeight, scale, x, y)

    @Test fun viewportKeepsItsOriginalPositionWithoutUpscaling() {
        val plan = AgentBrowserCapture.plan(geometry(120, 100, x = 13, y = 47), false)
        assertEquals(13, plan.captureLeft)
        assertEquals(47, plan.startY)
        assertEquals(100, plan.captureHeight)
        assertEquals(120, plan.outputWidth)
        assertEquals(100, plan.outputHeight)
        assertEquals(1, plan.tileCount)
        assertTrue(plan.complete)
    }

    @Test fun smallViewportUsesOneQuarterOfThePreviousUpscaledRasterPixels() {
        val plan = AgentBrowserCapture.plan(geometry(640, 640), false)
        assertEquals(640, plan.outputWidth)
        assertEquals(640, plan.outputHeight)
        assertEquals(640L * 640 * 4, plan.outputWidth.toLong() * plan.outputHeight * 4)
    }

    @Test fun largeViewportStillDownsamplesWithinTheExistingQualityAndMemoryBounds() {
        val plan = AgentBrowserCapture.plan(geometry(1440, 3200), false)
        assertEquals(720, plan.outputWidth)
        assertEquals(1600, plan.outputHeight)
        assertTrue(plan.outputWidth.toLong() * plan.outputHeight <= 2_000_000)
    }

    @Test fun fullDocumentUsesBottomOverlapRatherThanUnreachableFinalScrollOffset() {
        val plan = AgentBrowserCapture.plan(geometry(120, 100, 250, x = 12, y = 20), true)
        assertEquals(0, plan.captureLeft)
        assertEquals(0, plan.startY)
        assertEquals(250, plan.captureHeight)
        assertEquals(120, plan.outputWidth)
        assertEquals(250, plan.outputHeight)
        assertEquals(3, plan.tileCount)
        assertEquals(0, plan.scrollYFor(0))
        assertEquals(100, plan.scrollYFor(100))
        assertEquals(150, plan.scrollYFor(200))
        assertTrue(plan.complete)
    }

    @Test fun tallPhonePageKeepsUsefulWidthAndReportsTheCapturedPrefix() {
        val plan = AgentBrowserCapture.plan(geometry(sourceHeight = 100_000), true)
        assertFalse(plan.complete)
        assertTrue(plan.captureHeight < 100_000)
        assertTrue(plan.outputWidth >= 540)
        assertTrue(plan.outputHeight <= AgentBrowserCapture.MAX_OUTPUT_HEIGHT)
        assertTrue(plan.outputWidth.toLong() * plan.outputHeight * 4 <= AgentBrowserCapture.MAX_BITMAP_BYTES)
        assertTrue(plan.tileCount <= AgentBrowserCapture.MAX_TILES)
        assertTrue(plan.limitReasons.contains("pixel_limit"))
    }

    @Test fun veryShortViewportCannotCauseUnboundedTileCount() {
        val plan = AgentBrowserCapture.plan(geometry(160, 1, 100_000), true)
        assertEquals(24, plan.captureHeight)
        assertEquals(24, plan.tileCount)
        assertTrue(plan.limitReasons.contains("tile_limit"))
        assertFalse(plan.complete)
    }

    @Test fun invalidOrMissingScaleNeverPretendsTheViewportIsTheWholePage() {
        for (scale in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val plan = AgentBrowserCapture.plan(geometry(sourceHeight = 5000, scale = scale), true)
            assertNull(plan.sourceHeight)
            assertEquals(720, plan.captureHeight)
            assertEquals(listOf("scale_unavailable"), plan.limitReasons)
            assertFalse(plan.complete)
        }
    }

    @Test fun cachedScaleConvertsCssHeightToPhysicalScrollableExtent() {
        val plan = AgentBrowserCapture.plan(geometry(120, 100, 250, 2.0), true)
        assertEquals(500L, plan.sourceHeight)
        assertEquals(500, plan.captureHeight)
        assertTrue(plan.complete)
    }

    @Test fun extremeDimensionsRemainBoundedWithoutIntegerOverflow() {
        val plan = AgentBrowserCapture.plan(geometry(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, 200.0), true)
        assertTrue(plan.captureHeight in 1..AgentBrowserCapture.MAX_SOURCE_HEIGHT)
        assertTrue(plan.outputWidth in 1..AgentBrowserCapture.MAX_OUTPUT_WIDTH)
        assertTrue(plan.outputHeight in 1..AgentBrowserCapture.MAX_OUTPUT_HEIGHT)
        assertTrue(plan.outputWidth.toLong() * plan.outputHeight * 4 <= AgentBrowserCapture.MAX_BITMAP_BYTES)
        assertTrue(plan.tileCount <= AgentBrowserCapture.MAX_TILES)
        assertFalse(plan.complete)
    }
}
