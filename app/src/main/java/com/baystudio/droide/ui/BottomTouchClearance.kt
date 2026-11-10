package com.baystudio.droide.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Small content gutter; Android's existing inset owner still handles the keyboard. */
internal fun bottomTouchClearance(availableHeight: Dp): Dp {
    if (!availableHeight.value.isFinite()) return if (availableHeight == Dp.Infinity) 24.dp else 0.dp
    if (availableHeight <= 0.dp) return 0.dp

    return (availableHeight * .10f).coerceIn(8.dp, 24.dp).coerceAtMost(availableHeight * .25f)
}
