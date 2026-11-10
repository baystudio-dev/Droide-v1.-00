package com.baystudio.droide.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt

internal const val AccessoryMotionMillis = 160

/** Only the accessory's pixels use this state. It cannot change the IME, focus, or a session. */
internal class AccessoryImeMotion(
    private val ime: WindowInsets,
    private val source: WindowInsets,
    private val target: WindowInsets,
    private val density: Density,
    private val rawVisible: State<Boolean>,
    private val fallback: State<Float>,
    private val controller: AccessoryImeMotionController,
) {
    fun fraction(): Float = controller.sample(
        currentBottomPx = ime.getBottom(density),
        sourceBottomPx = source.getBottom(density),
        targetBottomPx = target.getBottom(density),
        fallbackFraction = fallback.value,
        rawImeVisible = rawVisible.value,
    )

    fun allowsInput(): Boolean = controller.allowsInput(rawVisible.value,
        ime.getBottom(density), source.getBottom(density), target.getBottom(density))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun rememberAccessoryImeMotion(): AccessoryImeMotion = rememberAccessoryImeMotion(
    WindowInsets.ime, WindowInsets.imeAnimationSource, WindowInsets.imeAnimationTarget, WindowInsets.isImeVisible,
)

@Composable
internal fun rememberAccessoryImeMotion(
    ime: WindowInsets,
    source: WindowInsets,
    target: WindowInsets,
    rawImeVisible: Boolean,
): AccessoryImeMotion {
    val density = LocalDensity.current
    val landscape = droidePhysicalLandscape()
    val motionEnabled = rememberDroideMotionEnabled()
    val visible = rememberUpdatedState(rawImeVisible)
    val controller = remember(ime, source, target, density, landscape) { AccessoryImeMotionController() }
    // Remember a genuine hide/show boundary even while this pane does not own input or measure a
    // deck.
    SideEffect { controller.observeRawVisibility(rawImeVisible) }
    // This is a mode boundary, never a composition dependency on each keyboard pixel.
    val nativeCoordinates by remember(ime, source, target, density) {
        derivedStateOf { ime.getBottom(density) > 0 || source.getBottom(density) > 0 || target.getBottom(density) > 0 }
    }
    val fallback = animateFloatAsState(
        targetValue = if (rawImeVisible && !nativeCoordinates && controller.permitsFallback(rawImeVisible)) 1f else 0f,
        animationSpec = if (nativeCoordinates || !motionEnabled) snap() else tween(AccessoryMotionMillis),
        label = "AccessoryFloatingImeVisibility",
    )
    return remember(ime, source, target, density, visible, fallback, controller) {
        AccessoryImeMotion(ime, source, target, density, visible, fallback, controller)
    }
}

internal val LocalAccessoryInputAllowed = staticCompositionLocalOf<() -> Boolean> { { true } }

/** Measure the full key deck, then reveal real layout height without squashing its touch geometry. */
@Composable
internal fun AccessoryRevealLayout(
    fullHeight: Dp,
    fraction: () -> Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier.clipToBounds()) { measurables, constraints ->
        val full = fullHeight.roundToPx().coerceAtLeast(0)
        val progress = fraction().coerceIn(0f, 1f)
        val child = measurables.single().measure(constraints.copy(minHeight = full, maxHeight = full))
        val height = (full * progress).roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(child.width, height) {
            child.placeRelativeWithLayer(0, height - child.height) { alpha = progress }
        }
    }
}

/** Header handle shrinks horizontally; it does not reserve a hidden permanent action slot. */
@Composable
internal fun AccessoryHandleRevealLayout(
    fraction: () -> Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier.clipToBounds()) { measurables, constraints ->
        val child = measurables.single().measure(constraints.copy(minWidth = 0))
        val progress = fraction().coerceIn(0f, 1f)
        val width = (child.width * progress).roundToInt().coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, child.height) {
            child.placeRelativeWithLayer(width - child.width, 0) { alpha = progress }
        }
    }
}

/** The decorative branch has no pointer/indication node and does not alter ScrollState, so its
 * full measured row and logical scroll position survive a rapid hide/show reversal. */
internal fun Modifier.accessoryHorizontalScroll(
    state: ScrollState,
    inputEnabled: Boolean,
): Modifier = if (inputEnabled) horizontalScroll(state) else clipToBounds().layout { measurable, constraints ->
    val child = measurable.measure(constraints.copy(maxWidth = Constraints.Infinity))
    val width = child.width.coerceAtMost(constraints.maxWidth)
    val height = child.height.coerceAtMost(constraints.maxHeight)
    layout(width, height) {
        val offset = state.value.coerceIn(0, (child.width - width).coerceAtLeast(0))
        child.placeRelativeWithLayer(-offset, 0)
    }
}
