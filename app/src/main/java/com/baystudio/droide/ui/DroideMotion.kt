package com.baystudio.droide.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Context-specific motion, shared by the workbench. No timers, model state, or navigation locks. */
internal object DroideMotion {
    const val SelectionMillis = 120
    const val ContentMillis = 180
    const val PaneMillis = 220
    val ContentTravel = 8.dp
    // Critical damping keeps code/text legible without overshoot. The normalized threshold
    // settles an 8 dp trajectory within 0.008 dp; spring time is not a fixed duration.
    val ContentSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 2500f,
        visibilityThreshold = 0.001f,
    )
    // Smaller activity entrances and disclosure glyphs may settle within 1% of their
    // normalized range; keep critical damping so text and alpha never bounce.
    val ActivitySpring = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
        visibilityThreshold = 0.01f,
    )
}

@Composable
internal fun rememberDroideMotionEnabled(): Boolean =
    (rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) > 0f

/** One decorative trajectory per mounted surface, independent of its current content. */
internal class DroideKeyedMotion(
    private val scope: CoroutineScope,
    initiallyEnabled: Boolean,
    private val animateInitially: Boolean = true,
    private val animationSpec: AnimationSpec<Float> = DroideMotion.ContentSpring,
) {
    val progress = Animatable(if (initiallyEnabled && animateInitially) 0f else 1f)
    private var initialized = false
    private var previousKey: Any? = null
    private var animation: Job? = null

    fun update(key: Any?, enabled: Boolean) {
        val wasInitialized = initialized
        val changed = !initialized || previousKey != key
        initialized = true
        previousKey = key
        if (!enabled) {
            animation?.cancel()
            animation = null
            // Consume disabled navigation, so IME dismissal/re-enabling cannot replay it.
            if (progress.value != 1f) scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(1f) }
        } else if (changed && (wasInitialized || animateInitially) && animation?.isActive != true) {
            animation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                progress.snapTo(0f)
                progress.animateTo(1f, animationSpec)
            }
        }
        // New content appears immediately, but rapid taps never reset/extend a running motion.
    }
}

/**
 * Animate the current surface only. Crossfade/AnimatedContent would retain a second Sora editor,
 * terminal, WebView, or Agent and could duplicate listeners while navigating. A new key reuses
 * an in-flight trajectory, never the content's work. Read animation state in the draw phase.
 * Insets and streamed text aren't keys: typing/output must not replay entrance animations.
 */
@Composable
internal fun Modifier.droideContentMotion(transitionKey: Any?, enabled: Boolean = true): Modifier {
    val motionEnabled = enabled && rememberDroideMotionEnabled()
    val scope = rememberCoroutineScope()
    val motion = remember(scope) { DroideKeyedMotion(scope, motionEnabled) }
    val travelPx = with(LocalDensity.current) { DroideMotion.ContentTravel.toPx() }
    SideEffect { motion.update(transitionKey, motionEnabled) }
    return graphicsLayer {
        val fraction = if (motionEnabled) motion.progress.value else 1f
        // Keep content readable and interactive even on the first frame.
        alpha = .94f + .06f * fraction
        translationY = travelPx * (1f - fraction)
    }
}
