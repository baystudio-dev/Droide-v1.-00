package com.baystudio.droide.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.layout.layout
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrainWidth













@Composable
internal fun droideImeStableWorkbenchLayout(
    constrained: DroideWorkbenchLayout,
    availableWidth: Dp,
    requestedBottomPanelHeight: Dp,
    presentation: DroideImePresentation,
): DroideWorkbenchLayout {
    if (!presentation.imeVisible) return constrained
    val stable = droideWorkbenchLayout(
        availableWidth,
        droidePhysicalWindowHeightDp(),
        requestedBottomPanelHeight,
        physicalLandscape = presentation.landscape,
    )
    return constrained.copy(
        windowClass = stable.windowClass,
        compact = stable.compact,
        medium = stable.medium,
        expanded = stable.expanded,
        // A chat keyboard changes available height, not the desktop toolbar variant.
        shortHeight = if (presentation.landscape) stable.shortHeight else constrained.shortHeight,
        wideHeader = if (presentation.landscape) stable.wideHeader else constrained.wideHeader,
    )
}

internal data class DroideLandscapeImeFocusLayout(
    val userPaneFit: DroidePaneFit,
    val contentPaneFit: DroidePaneFit,
    private val sidebarWidth: State<Dp>,
    private val agentWidth: State<Dp>,
    private val sidebarVisible: State<Boolean>,
    private val agentVisible: State<Boolean>,
) {
    // Width getters belong in measurement, never in the workbench composition.
    val sidebarRegionWidth: Dp get() = sidebarWidth.value
    val agentRegionWidth: Dp get() = agentWidth.value
    val sidebarRegionVisible: Boolean get() = sidebarVisible.value
    val agentRegionVisible: Boolean get() = agentVisible.value
}

@Composable
internal fun rememberDroideLandscapeImeFocusLayout(
    adaptive: DroideWorkbenchLayout,
    availableWidth: Dp,
    sidebarTool: DroideRailTool,
    presentation: DroideImePresentation,
    focusMode: Boolean,
    sidebarVisible: Boolean,
    agentVisible: Boolean,
    requestedAgentWidth: Dp,
): DroideLandscapeImeFocusLayout {
    val userPaneFit = droidePaneFit(adaptive, availableWidth, sidebarTool, sidebarVisible, agentVisible, requestedAgentWidth)
    val contentPaneFit = if (presentation.landscape)
        droidePaneFit(adaptive, availableWidth, sidebarTool, true, true, requestedAgentWidth) else userPaneFit
    val focusActive = presentation.landscape && focusMode
    // Animate visibility, not the requested size. Direct drag/resize follows the finger even
    // when the keyboard is open; it must not chase a new animated width on every pointer event.
    val fraction = if (presentation.landscape) rememberUpdatedState(1f) else animateFloatAsState(
        targetValue = if (focusActive) 0f else 1f,
        animationSpec = tween(DroideMotion.PaneMillis, easing = FastOutSlowInEasing),
        label = "landscapeImePaneVisibility",
    )
    val motionEnabled = rememberDroideMotionEnabled()
    val sidebarFraction = if (presentation.landscape)
        rememberLandscapeVisibility(sidebarVisible && !focusActive, "landscapeSidebar") else fraction
    val agentFraction = if (presentation.landscape)
        rememberLandscapeVisibility(agentVisible && !focusActive, "landscapeAgent") else fraction
    return remember(userPaneFit, contentPaneFit, sidebarVisible, agentVisible, sidebarFraction, agentFraction,
        motionEnabled, focusActive, presentation.landscape) {
        val sidebarWidth = derivedStateOf {
            droideFocusPaneWidth(contentPaneFit.sidebarWidth + 1.dp,
                (presentation.landscape || sidebarVisible) && contentPaneFit.sidebarWidth > 0.dp,
                if (motionEnabled) sidebarFraction.value else if (focusActive || !sidebarVisible) 0f else 1f)
        }
        val agentWidth = derivedStateOf {
            droideFocusPaneWidth(contentPaneFit.agentWidth + 10.dp,
                (presentation.landscape || agentVisible) && contentPaneFit.agentWidth > 0.dp,
                if (motionEnabled) agentFraction.value else if (focusActive || !agentVisible) 0f else 1f)
        }
        DroideLandscapeImeFocusLayout(userPaneFit, contentPaneFit, sidebarWidth, agentWidth,
            derivedStateOf { sidebarWidth.value > 0.dp }, derivedStateOf { agentWidth.value > 0.dp })
    }
}

internal fun droideFocusPaneWidth(fullWidth: Dp, visible: Boolean, fraction: Float): Dp =
    if (!visible) 0.dp else fullWidth.coerceAtLeast(0.dp) * fraction.coerceIn(0f, 1f)

 
@Composable
internal fun RowScope.DroideClippedPaneRegion(
    visibleWidth: Dp,
    fullWidth: Dp,
    alignment: Alignment = Alignment.CenterStart,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier.width(visibleWidth).fillMaxHeight().clipToBounds()
            .then(if (visibleWidth <= 0.dp) Modifier.clearAndSetSemantics {} else Modifier),
        contentAlignment = alignment,
    ) {
        Box(Modifier.requiredWidth(fullWidth).fillMaxHeight()) { content() }
    }
}

/** Defer per-frame reads to measurement; compose only when accessibility visibility changes. */
@Composable
internal fun RowScope.DroideClippedPaneRegion(
    visibleWidth: () -> Dp,
    regionVisible: Boolean,
    fullWidth: Dp,
    alignment: Alignment = Alignment.CenterStart,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier.layout { measurable, constraints ->
            val width = constraints.constrainWidth(visibleWidth().roundToPx().coerceAtLeast(0))
            val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
            layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
        }.fillMaxHeight().clipToBounds()
            .then(if (!regionVisible) Modifier.clearAndSetSemantics {} else Modifier),
        contentAlignment = alignment,
    ) {
        Box(Modifier.requiredWidth(fullWidth).fillMaxHeight()) { content() }
    }
}
