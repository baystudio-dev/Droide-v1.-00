package com.baystudio.droide.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Keep the normal chrome size, but leave room for the actual scaled text line.
 * TextUnit.toDp uses the platform font converter, including nonlinear accessibility scaling.
 */
@Composable
internal fun textControlHeight(style: TextStyle, minimum: Dp, verticalPadding: Dp = 4.dp): Dp =
    with(LocalDensity.current) { maxOf(minimum, style.lineHeight.toDp() + verticalPadding) }

/** Preserve room for a filename and its actions even far down a nested tree. */
internal fun explorerIndent(depth: Int, availableWidth: Dp, reservedWidth: Dp): Dp =
    minOf(14.dp * depth.coerceAtLeast(0).toFloat(), (availableWidth - reservedWidth).coerceAtLeast(0.dp))
