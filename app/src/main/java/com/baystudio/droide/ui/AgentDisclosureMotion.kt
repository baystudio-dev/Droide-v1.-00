package com.baystudio.droide.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

internal val LocalAgentDisclosureStarted = staticCompositionLocalOf<() -> Unit> { {} }

internal val LocalAgentDisclosureMotion = staticCompositionLocalOf<(Any, Boolean) -> Unit> { { _, _ -> } }

internal class AgentDisclosureMotion(private val currentContentKey: () -> Any? = { Unit }) {
    private var startedContentKey: Any? = Unit

    // Structural stream snapshots, never animation progress or the live wall clock.
    fun contentUnchanged(): Boolean = startedContentKey == currentContentKey()
    var generation by mutableIntStateOf(0)
        private set
    var active by mutableStateOf(false)
        internal set

    fun begin() {
        startedContentKey = currentContentKey()
        generation++
        active = true
    }
}

@Composable
internal fun rememberAgentDisclosureMotion(contentKey: Any? = Unit): AgentDisclosureMotion {
    val latestContentKey = rememberUpdatedState(contentKey)
    val motion = remember { AgentDisclosureMotion { latestContentKey.value } }
    val enabled = rememberDroideMotionEnabled()
    val onMotion = LocalAgentDisclosureMotion.current
    DisposableEffect(motion, motion.active, onMotion) {
        onMotion(motion, motion.active)
        onDispose { onMotion(motion, false) }
    }
    // Only the first content invalidation cancels the window. Later streamed
    // snapshots must not launch another coroutine after decoration has stopped.
    val contentUnchanged = motion.active && motion.contentUnchanged()
    LaunchedEffect(motion.generation, enabled, contentUnchanged) {
        if (enabled && contentUnchanged) {
            Animatable(0f).animateTo(1f, tween(AgentActivityMotion.DisclosureMillis))
        }
        motion.active = false
    }
    return motion
}

@Composable
internal fun Modifier.agentDisclosureSize(motion: AgentDisclosureMotion): Modifier {
    val enabled = rememberDroideMotionEnabled()
    return animateContentSize(animationSpec = if (enabled && motion.active && motion.contentUnchanged())
        tween(AgentActivityMotion.DisclosureMillis, easing = LinearOutSlowInEasing) else snap())
}
