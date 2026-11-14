package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

internal data class WorkspaceBrowserGeometry(val viewport: Rect, val visible: Rect) {
    val fullyVisible: Boolean get() = visible.left <= viewport.left + 1f && visible.top <= viewport.top + 1f &&
        visible.right >= viewport.right - 1f && visible.bottom >= viewport.bottom - 1f
}

private fun Rect.hasFiniteCoordinates(): Boolean = left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()

/** Geometry reporters never own the native browser, even while the workspace changes layout. */
@Stable
internal class WorkspaceBrowserPlacement {
    private data class SlotBounds(val owner: Any, val geometry: WorkspaceBrowserGeometry? = null)
    private val slots = mutableStateMapOf<WorkspaceBrowserSlot, SlotBounds>()
    private var selectedSlot: WorkspaceBrowserSlot? = null
    var rootBounds by mutableStateOf(Rect.Zero)
        private set
    var lastGeometry by mutableStateOf<WorkspaceBrowserGeometry?>(null)
        private set

    fun updateRoot(bounds: Rect) { if (bounds.hasFiniteCoordinates() && rootBounds != bounds) rootBounds = bounds }

    fun claim(slot: WorkspaceBrowserSlot, owner: Any) {
        if (slots[slot]?.owner !== owner) slots[slot] = SlotBounds(owner)
    }

    fun select(slot: WorkspaceBrowserSlot?) {
        selectedSlot = slot
        slot?.let { slots[it]?.geometry }?.let(::retainViewport)
    }

    fun update(slot: WorkspaceBrowserSlot, owner: Any, viewport: Rect, visible: Rect = viewport) {
        if (slots[slot]?.owner !== owner) return
        if (!viewport.hasFiniteCoordinates() || !visible.hasFiniteCoordinates() || viewport.width <= 0f || viewport.height <= 0f ||
            !viewport.width.isFinite() || !viewport.height.isFinite() || visible.width < 0f || visible.height < 0f) {
            slots[slot] = SlotBounds(owner)
            return
        }
        val geometry = WorkspaceBrowserGeometry(viewport, visible)
        val next = SlotBounds(owner, geometry)
        if (slots[slot] != next) slots[slot] = next
        if (slot == selectedSlot) retainViewport(geometry)
    }

    private fun retainViewport(geometry: WorkspaceBrowserGeometry) {
        // Clipping an existing pane must not turn the retained renderer into a tiny viewport.
        val retained = geometry.copy(visible = geometry.viewport)
        if (lastGeometry != retained) lastGeometry = retained
    }

    fun release(slot: WorkspaceBrowserSlot, owner: Any) {
        if (slots[slot]?.owner === owner) slots.remove(slot)
    }

    fun geometry(slot: WorkspaceBrowserSlot?): WorkspaceBrowserGeometry? = slot?.let { slots[it]?.geometry }
    fun bounds(slot: WorkspaceBrowserSlot?): Rect? = geometry(slot)?.visible?.takeIf { it.width > 0f && it.height > 0f }
}

@Composable
internal fun WorkspaceBrowserPlaceholder(placement: WorkspaceBrowserPlacement, slot: WorkspaceBrowserSlot) {
    val owner = remember(placement, slot) { Any() }
    DisposableEffect(placement, slot, owner) {
        placement.claim(slot, owner)
        onDispose { placement.release(slot, owner) }
    }
    Box(Modifier.fillMaxSize().testTag("browser-slot-${slot.name.lowercase()}")
        .onGloballyPositioned {
            placement.update(slot, owner, Rect(it.positionInRoot(), Size(it.size.width.toFloat(), it.size.height.toFloat())), it.boundsInRoot())
        })
}

/** One stable call site owns one native view. Hidden retention keeps the last nonzero viewport. */
@Composable
internal fun BoxScope.WorkspaceBrowserHost(
    placement: WorkspaceBrowserPlacement,
    selectedSlot: WorkspaceBrowserSlot?,
    keepActive: Boolean,
    stopping: Boolean,
    browser: @Composable (presented: Boolean, captureReady: Boolean) -> Unit,
) {
    SideEffect { placement.select(selectedSlot) }
    val currentGeometry = placement.geometry(selectedSlot)
    val visibleGeometry = currentGeometry?.takeIf { it.visible.width > 0f && it.visible.height > 0f }
    val geometry = visibleGeometry ?: placement.lastGeometry ?: return
    val bounds = geometry.visible
    if (selectedSlot == null && !keepActive && !stopping) return
    // A known fully clipped pane is intentionally hidden. Missing destination
    // measurement during a placement move is only transient and keeps one host.
    if (currentGeometry != null && visibleGeometry == null && !keepActive && !stopping) return
    val presented = selectedSlot != null && visibleGeometry != null
    val density = LocalDensity.current
    val width = with(density) { bounds.width.toDp() }
    val height = with(density) { bounds.height.toDp() }
    val root = placement.rootBounds
    var modifier = Modifier
        .align(AbsoluteAlignment.TopLeft)
        .absoluteOffset { IntOffset((bounds.left - root.left).roundToInt(), (bounds.top - root.top).roundToInt()) }
        .requiredSize(width, height)
        .clipToBounds()
        .zIndex(if (presented) 1f else -1f)
        .graphicsLayer { alpha = if (presented) 1f else 0f }
        .testTag("workspace-browser-host")
    if (!presented) modifier = modifier.clearAndSetSemantics {}.pointerInput(Unit) {
        // The hidden native view is INVISIBLE as well. Consume before any hidden toolbar
        // child so a click through empty workspace space cannot activate browser controls.
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
    val viewport = geometry.viewport
    Box(modifier) {
        Box(Modifier.wrapContentSize(AbsoluteAlignment.TopLeft, unbounded = true)
            .absoluteOffset { IntOffset((viewport.left - bounds.left).roundToInt(), (viewport.top - bounds.top).roundToInt()) }
            .requiredSize(with(density) { viewport.width.toDp() }, with(density) { viewport.height.toDp() })) {
            browser(presented, presented && geometry.fullyVisible)
        }
    }
}

@Composable
internal fun BrowserRetentionNotice(attached: Boolean, dialogPending: Boolean, onOpenBrowser: () -> Unit) {
    Surface(color = DroideColors.Surface) {
        DismissibleNotice(when {
            dialogPending -> "Browser needs a response"
            attached -> "Browser kept active · uses RAM"
            else -> "Keep active is on · browser not loaded"
        }, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, actions = {
            TextButton(onClick = onOpenBrowser, modifier = Modifier.heightIn(min = 48.dp)) { Text("View browser") }
        })
    }
}
