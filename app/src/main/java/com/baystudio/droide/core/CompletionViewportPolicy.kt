package com.baystudio.droide.core

import kotlin.math.ceil
import kotlin.math.floor

internal data class CompletionViewport(val left: Int, val top: Int, val right: Int, val bottom: Int)
internal data class CompletionPopupPlacement(val left: Int, val top: Int, val width: Int, val height: Int, val above: Boolean)

internal object CompletionViewportPolicy {
    fun place(
        viewport: CompletionViewport,
        caretX: Float,
        caretTop: Float,
        caretBottom: Float,
        preferredWidth: Int,
        requestedHeight: Int,
        rowHeight: Int,
        margin: Int,
        gap: Int,
        minimumWidth: Int,
    ): CompletionPopupPlacement? {
        if (!caretX.isFinite() || !caretTop.isFinite() || !caretBottom.isFinite() ||
            caretBottom < caretTop || rowHeight <= 0 || preferredWidth <= 0 || requestedHeight <= 0 ||
            viewport.right <= viewport.left || viewport.bottom <= viewport.top) return null
        if (caretX < viewport.left || caretX > viewport.right || caretBottom <= viewport.top || caretTop >= viewport.bottom) return null
        val inset = margin.coerceAtLeast(0)
        val left = viewport.left + inset
        val right = viewport.right - inset
        val top = viewport.top + inset
        val bottom = viewport.bottom - inset
        val width = minOf(preferredWidth, right - left)
        if (width < minimumWidth.coerceAtLeast(1) || bottom <= top) return null
        val spacing = gap.coerceAtLeast(0)
        val belowStart = ceil(caretBottom.toDouble()).toInt().coerceIn(top, bottom) + spacing
        val aboveEnd = floor(caretTop.toDouble()).toInt().coerceIn(top, bottom) - spacing
        val below = (bottom - belowStart).coerceAtLeast(0)
        val above = (aboveEnd - top).coerceAtLeast(0)
        val wanted = maxOf(requestedHeight, rowHeight)
        val useAbove = below < wanted && above > below
        val height = minOf(wanted, if (useAbove) above else below)
        if (height < rowHeight) return null
        return CompletionPopupPlacement(
            left = floor(caretX.toDouble()).toInt().coerceIn(left, right - width),
            top = if (useAbove) aboveEnd - height else belowStart,
            width = width, height = height, above = useAbove,
        )
    }
}
