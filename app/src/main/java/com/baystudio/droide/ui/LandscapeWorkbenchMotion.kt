package com.baystudio.droide.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import kotlin.math.roundToInt

private enum class LandscapeFocusTarget { Normal, Editor, Terminal }

internal class LandscapeWorkbenchMotion(
    val normal: State<Float>,
    val terminal: State<Float>,
    val panel: State<Float>,
)

@Composable
internal fun rememberLandscapeVisibility(visible: Boolean, label: String): State<Float> =
    if (!rememberDroideMotionEnabled()) rememberUpdatedState(if (visible) 1f else 0f)
    else animateFloatAsState(if (visible) 1f else 0f,
        tween(DroideMotion.PaneMillis, easing = FastOutSlowInEasing), label = label)

/** Animate allocation fractions only; IME insets, window resizing and divider drags stay direct. */
@Composable
internal fun rememberLandscapeWorkbenchMotion(
    editorTyping: Boolean,
    terminalExpanded: Boolean,
    panelVisible: Boolean,
): LandscapeWorkbenchMotion {
    val target = when {
        terminalExpanded -> LandscapeFocusTarget.Terminal
        editorTyping -> LandscapeFocusTarget.Editor
        else -> LandscapeFocusTarget.Normal
    }
    val panel = rememberLandscapeVisibility(panelVisible, "landscapePanel")
    val normal: State<Float>
    val terminal: State<Float>
    if (rememberDroideMotionEnabled()) {
        val transition = updateTransition(target, label = "landscapeFocus")
        normal = transition.animateFloat(
            transitionSpec = { tween(DroideMotion.PaneMillis, easing = FastOutSlowInEasing) },
            label = "normalSpace",
        ) { if (it == LandscapeFocusTarget.Normal) 1f else 0f }
        terminal = transition.animateFloat(
            transitionSpec = { tween(DroideMotion.PaneMillis, easing = FastOutSlowInEasing) },
            label = "terminalSpace",
        ) { if (it == LandscapeFocusTarget.Terminal) 1f else 0f }
    } else {
        normal = rememberUpdatedState(if (target == LandscapeFocusTarget.Normal) 1f else 0f)
        terminal = rememberUpdatedState(if (target == LandscapeFocusTarget.Terminal) 1f else 0f)
    }
    return remember(normal, terminal, panel) { LandscapeWorkbenchMotion(normal, terminal, panel) }
}

/** Keep natural control sizes while reclaiming their space; exiting controls cannot receive input. */
@Composable
internal fun LandscapeChromeReveal(
    fraction: () -> Float,
    interactive: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val currentFraction = rememberUpdatedState(fraction)
    val drawn by remember { derivedStateOf { currentFraction.value() > 0f } }
    Layout(modifier = modifier.clipToBounds(), content = {
        RetainedWorkspaceContent(interactive, drawWhileHidden = drawn,
            modifier = Modifier.fillMaxWidth(), content = content)
    }) { measurables, constraints ->
        val child = measurables.single().measure(constraints.copy(minHeight = 0))
        val progress = fraction().coerceIn(0f, 1f)
        val height = (child.height * progress).roundToInt()
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(child.width, height) {
            if (height > 0) child.placeRelativeWithLayer(0, height - child.height) { alpha = progress }
        }
    }
}
