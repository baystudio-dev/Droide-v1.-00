package com.baystudio.droide.ui

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal const val StatusBarFallbackMillis = 160

private class StatusBarInsetAnimation(
    private val scope: CoroutineScope,
    private val onFrame: (StatusBarInsetFallbackFrame) -> Unit,
) {
    private var plan: StatusBarInsetFallbackPlan? = null
    private var job: Job? = null

    fun update(next: StatusBarInsetFallbackPlan?) {
        if (plan == next) return
        job?.cancel()
        plan = next
        job = next?.let { segment ->
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                Animatable(segment.sourceTopPx).animateTo(
                    segment.targetTopPx,
                    tween(StatusBarFallbackMillis, easing = LinearOutSlowInEasing),
                ) {
                    onFrame(StatusBarInsetFallbackFrame(segment.generation, value))
                }
            }
        }
    }

    fun cancel() = job?.cancel()
}

/** One main-window inset owner, including startup/error content and the temporary-startup banner. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DroideWindowInsetsHost(
    statusBarRequestedHidden: Boolean,
    reclaimTopCutout: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val view = LocalView.current
    val displayCutout = WindowInsets.displayCutout
    val metricsKey = listOf(configuration.orientation, configuration.screenWidthDp, density.density, density.fontScale)
    val headerCutouts = remember(view, displayCutout, density, reclaimTopCutout, metricsKey) {
        HeaderCutoutSource {

            if (!reclaimTopCutout || Build.VERSION.SDK_INT < Build.VERSION_CODES.P || displayCutout.getTop(density) == 0) emptyList()
            else view.rootWindowInsets?.displayCutout?.boundingRects.orEmpty().map {
                Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
            }
        }
    }
    StatusBarInsetsHost(
        status = WindowInsets.statusBars,
        shownStatus = WindowInsets.statusBarsIgnoringVisibility,
        system = WindowInsets.systemBars,
        navigation = WindowInsets.navigationBars,
        caption = WindowInsets.captionBar,
        cutout = displayCutout,
        ime = WindowInsets.ime,
        // Legacy adjustResize remains owned by this root; modern panes own their IME residual.
        legacyImeResize = Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM,
        statusBarRequestedHidden = statusBarRequestedHidden,
        reclaimTopCutout = reclaimTopCutout,
        metricsKey = metricsKey,
        motionEnabled = rememberDroideMotionEnabled(),
        modifier = modifier,
        protectedTopPx = {
            val bounds = headerCutouts.rectangles()
            val location = IntArray(2).also(view::getLocationInWindow)
            val left = displayCutout.getLeft(density, LayoutDirection.Ltr)
            val right = displayCutout.getRight(density, LayoutDirection.Ltr)
            val windowWidth = view.width.takeIf { it > 0 } ?: with(density) { configuration.screenWidthDp.dp.roundToPx() }
            val width = (windowWidth - left - right).coerceAtLeast(0)
            val padding = with(density) { 4.dp.roundToPx() }
            val touch = with(density) { DroideDimensions.PrimaryTouch.roundToPx() }
            val spans = HeaderCutoutGeometry.spans(width, (location[0] + left).toFloat(), location[1].toFloat(),
                location[1] + with(density) { DroideDimensions.TopBar.toPx() }, bounds, padding, padding)
            if (bounds.isNotEmpty() && width > 0 && spans.none { it.width >= touch }) displayCutout.getTop(density) else 0
        },
        content = { CompositionLocalProvider(LocalHeaderCutouts provides headerCutouts) { content() } },
    )
}

@Composable
internal fun StatusBarInsetsHost(
    status: WindowInsets,
    shownStatus: WindowInsets,
    system: WindowInsets,
    navigation: WindowInsets,
    caption: WindowInsets,
    cutout: WindowInsets,
    ime: WindowInsets,
    legacyImeResize: Boolean,
    statusBarRequestedHidden: Boolean,
    metricsKey: Any?,
    motionEnabled: Boolean,
    reclaimTopCutout: Boolean = false,
    modifier: Modifier = Modifier,
    protectedTopPx: () -> Int = { 0 },
    content: @Composable BoxScope.() -> Unit,
) {
    val controller = remember(status, shownStatus) { StatusBarInsetMotionController() }
    val requestedHidden = rememberUpdatedState(statusBarRequestedHidden)
    val reclaimCutout = rememberUpdatedState(reclaimTopCutout)
    val currentMetrics = rememberUpdatedState(metricsKey)
    val currentMotionEnabled = rememberUpdatedState(motionEnabled)
    val protectedTop = rememberUpdatedState(protectedTopPx)
    var frame by remember(controller) { mutableStateOf<StatusBarInsetFallbackFrame?>(null) }
    val scope = rememberCoroutineScope()
    val animation = remember(controller, scope) { StatusBarInsetAnimation(scope) { frame = it } }
    DisposableEffect(animation) {
        onDispose { animation.cancel() }
    }
    val rawSafe = remember(system, cutout) { system.union(cutout) }
    val displayedSafe = remember(status, shownStatus, navigation, caption, cutout, rawSafe, controller, animation) {
        object : WindowInsets {
            override fun getLeft(density: Density, layoutDirection: LayoutDirection) = rawSafe.getLeft(density, layoutDirection)
            override fun getRight(density: Density, layoutDirection: LayoutDirection) = rawSafe.getRight(density, layoutDirection)
            override fun getBottom(density: Density) = rawSafe.getBottom(density)
            override fun getTop(density: Density): Int {
                // Insets and fallback frames are read in layout, not per-pixel composition.
                val sample = controller.sample(
                    currentTopPx = status.getTop(density),
                    shownTopPx = shownStatus.getTop(density),
                    metricsKey = currentMetrics.value,
                    reducedMotion = !currentMotionEnabled.value,
                    fallbackFrame = frame,
                    requestedHidden = requestedHidden.value,
                    anticipateHiddenRequest = reclaimCutout.value,
                )
                animation.update(sample.fallback)
                return max(protectedTop.value(), max(sample.topPx.roundToInt().coerceAtLeast(0),
                    max(if (reclaimCutout.value || controller.reclaimsTopInset) 0 else cutout.getTop(density),
                        max(caption.getTop(density), navigation.getTop(density)))))
            }
        }
    }
    Box(
        modifier.fillMaxSize()
            .windowInsetsPadding(displayedSafe)
            // Consume raw input so a child cannot restore the animated top reservation.
            .consumeWindowInsets(rawSafe)
            .then(if (legacyImeResize) Modifier.windowInsetsPadding(ime) else Modifier),
        content = content,
    )
}

@Composable
internal fun ReclaimedCutoutHeader(content: @Composable () -> Unit) {
    Layout(content = content) { measurables, constraints ->
        val child = measurables.single().measure(constraints.copy(minHeight = 0))
        val height = constraints.constrainHeight(maxOf(DroideDimensions.TopBar.roundToPx(), child.height))
        val y = (height - child.height) / 2
        layout(child.width, height) { child.placeRelative(0, y) }
    }
}
