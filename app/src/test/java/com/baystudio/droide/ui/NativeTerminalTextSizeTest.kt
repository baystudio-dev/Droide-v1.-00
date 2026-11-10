package com.baystudio.droide.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeTerminalTextSizeTest {
    @Test fun defaultFontUsesScreenDensityInsteadOfFourteenPhysicalPixels() {
        assertEquals(14, NativeTerminalTextSize.pixels(14f) { it })
        assertEquals(28, NativeTerminalTextSize.pixels(14f) { it * 2f })
        assertEquals(42, NativeTerminalTextSize.pixels(14f) { it * 3f })
    }

    @Test fun systemFontScaleAndPinchBoundsAreAppliedBeforePixelConversion() {
        assertEquals(53, NativeTerminalTextSize.pixels(14f) { it * 3.75f })
        assertEquals(27, NativeTerminalTextSize.pixels(2f) { it * 3f })
        assertEquals(78, NativeTerminalTextSize.pixels(100f) { it * 3f })
    }

    @Test fun platformConverterCanApplyANonlinearFontCurve() {
        // Verify logical sp is passed to the platform converter without a linear-density assumption.
        assertEquals(44, NativeTerminalTextSize.pixels(14f) { sp -> if (sp == 14f) 44f else error("Wrong logical size") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidDensityCannotCreateAnUnreadableOrOverflowedRenderer() {
        NativeTerminalTextSize.pixels(14f) { Float.NaN }
    }
}
