package com.baystudio.droide.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Run/item identity controls entrance; text updates never replay it. */
@Composable
internal fun Modifier.agentActivityEntrance(
    partId: String,
    policy: AgentActivityEntrancePolicy,
    enabled: Boolean,
): Modifier {
    val systemMotionEnabled = rememberDroideMotionEnabled()
    val permitted = enabled && systemMotionEnabled
    val admitted = remember(policy, partId) { policy.claim(partId, permitted) }
    val progress = remember(policy, partId) { Animatable(if (admitted) 0f else 1f) }
    val travel = with(LocalDensity.current) { 4.dp.toPx() }
    LaunchedEffect(progress, permitted) {
        if (admitted && permitted) {
            progress.animateTo(1f, DroideMotion.ActivitySpring)
        } else progress.snapTo(1f)
    }
    return graphicsLayer {
        alpha = if (permitted) AgentActivityMotion.InitialAlpha +
            (1f - AgentActivityMotion.InitialAlpha) * progress.value else 1f
    }.drawWithContent {
        val offset = if (permitted) travel * (1f - progress.value) else 0f
        clipRect { translate(top = offset) { this@drawWithContent.drawContent() } }
    }
}
