package com.baystudio.droide.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Keep one workspace owner while the chat enters/exits; outgoing panes lose input immediately. */
@Composable
internal fun PortraitAgentPanel(
    visible: Boolean,
    workspace: @Composable () -> Unit,
    agent: @Composable () -> Unit,
) {
    val presented = LocalWorkspacePresented.current
    val motionEnabled = rememberDroideMotionEnabled()
    val progress = animateFloatAsState(if (visible) 1f else 0f,
        tween(if (motionEnabled) DroideMotion.PaneMillis else 0, easing = FastOutSlowInEasing),
        label = "portraitAgentPanel")
    val workspaceDrawn by remember { derivedStateOf { progress.value < 1f } }
    val agentDrawn by remember { derivedStateOf { progress.value > 0f } }
    val travel = with(LocalDensity.current) { 32.dp.toPx() }
    Box(Modifier.fillMaxSize().clipToBounds()) {
        RetainedWorkspaceContent(presented && !visible, drawWhileHidden = presented && workspaceDrawn,
            content = workspace)
        if (visible || agentDrawn) {
            RetainedWorkspaceContent(presented && visible, drawWhileHidden = presented && agentDrawn,
                modifier = Modifier.fillMaxSize().testTag("portrait-agent-panel").graphicsLayer {
                    alpha = progress.value
                    translationX = travel * (1f - progress.value)
                }.background(DroideColors.Background), content = agent)
        }
    }
}
