package com.baystudio.droide.ui

import android.content.Context
import android.util.TypedValue
import kotlin.math.roundToInt

internal object NativeTerminalTextSize {
    const val DEFAULT_SP = 10f
    const val MIN_SP = 9f
    const val MAX_SP = 26f

    fun pixels(sizeSp: Float, convertSp: (Float) -> Float): Int {
        require(sizeSp.isFinite())
        val px = convertSp(sizeSp.coerceIn(MIN_SP, MAX_SP))
        require(px.isFinite() && px > 0f)
        return px.roundToInt().coerceAtLeast(8)
    }
}

internal fun nativeTerminalTextSizePixels(context: Context, sizeSp: Float): Int =
    NativeTerminalTextSize.pixels(sizeSp) {

        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, it, context.resources.displayMetrics)
    }
