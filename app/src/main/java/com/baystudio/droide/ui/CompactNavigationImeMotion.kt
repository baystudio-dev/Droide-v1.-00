package com.baystudio.droide.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlin.math.roundToInt

/**
 * The navigation follows the same native IME trajectory as the accessory deck,
 * in reverse. No visibility grace or second height animation follows keyboard
 * dismissal. Read animated insets during measurement, not in composition.
 */
@Composable
internal fun CompactNavigationImeReveal(
    typingTarget: Boolean,
    inputEnabled: Boolean,
    motion: AccessoryImeMotion = rememberAccessoryImeMotion(),
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    CompactNavigationRevealLayout(
        fraction = { if (typingTarget) 1f - motion.fraction() else 1f },
        inputEnabled = inputEnabled,
        modifier = modifier,
        content = content,
    )
}

/** Natural height includes large-font labels and the existing navigation insets. */
@Composable
internal fun CompactNavigationRevealLayout(
    fraction: () -> Float,
    inputEnabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val semantics = if (inputEnabled) Modifier else Modifier.clearAndSetSemantics { }
    Layout(content = content, modifier = modifier.then(semantics).clipToBounds()) { measurables, constraints ->
        val child = measurables.single().measure(constraints.copy(minHeight = 0))
        val progress = fraction().coerceIn(0f, 1f)
        val height = (child.height * progress).roundToInt()
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(child.width, height) {
            child.placeRelativeWithLayer(0, height - child.height) { alpha = progress }
        }
    }
}
