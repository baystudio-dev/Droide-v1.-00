package com.baystudio.droide.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

internal data class AgentChatConversationKey(val backendId: String, val sessionId: String?)

internal data class AgentChatActivity(
    val messages: Int,
    val parts: Int,
    val textBucket: Int,
    val outputBucket: Int,
    val terminalParts: Int,
    val parallel: Int,
    val external: Int,
    val runId: String?,
    val busy: Boolean,
) {
    fun addedSince(previous: AgentChatActivity): Int {
        val rows = (messages - previous.messages).coerceAtLeast(0) + (parts - previous.parts).coerceAtLeast(0)
        return rows + if (rows == 0 && this != previous) 1 else 0
    }
}

/** Data only: no Activity, layout, coroutine, or LazyListState is retained by the workspace. */
internal data class AgentChatPosition(
    val anchorKey: String?,
    val index: Int,
    val offset: Int,
    val following: Boolean,
    val unread: Int,
    val activity: AgentChatActivity,
    val showDetails: Boolean,
    val disclosureSpacePx: Int = 0,
)

/** This object belongs to the composition. Only [position] is saved outside it. */
internal class AgentChatViewport(
    val listState: LazyListState,
    val saved: AgentChatPosition?,
    activity: AgentChatActivity,
) {
    var ready by mutableStateOf(false)
    var following by mutableStateOf(saved?.following ?: true)
    var unread by mutableIntStateOf(saved?.unread ?: 0)
    var dragging by mutableStateOf(false)
    var appScrolls by mutableIntStateOf(0)
    var disclosureSpacePx by mutableIntStateOf(saved?.disclosureSpacePx ?: 0)
        private set
    private var disclosurePadding: Pair<Int, Int>? = null
    private var disclosureSettled = true
    private val activeDisclosures = mutableSetOf<Any>()
    var disclosureMotions by mutableIntStateOf(0)
        private set
    var disclosureGeneration by mutableIntStateOf(0)
        private set

    fun measuredDisclosurePadding(base: Int, extra: Int) {
        disclosurePadding = base to extra
    }

    fun disclosureMotion(owner: Any, active: Boolean) {
        if (active) { disclosureSettled = false; activeDisclosures.add(owner) } else activeDisclosures.remove(owner)
        disclosureMotions = activeDisclosures.size
    }
    var lastActivity = saved?.activity ?: activity
    var showDetails = saved?.showDetails ?: false
    var lastHeight = 0
    var lastBottomPadding = 0
    var lastItemCount = 0

    fun position(): AgentChatPosition? {
        if (!ready || listState.layoutInfo.visibleItemsInfo.isEmpty()) return null
        val anchor = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == listState.firstVisibleItemIndex }
        return AgentChatPosition(anchor?.key as? String, listState.firstVisibleItemIndex,
            listState.firstVisibleItemScrollOffset, following, unread, lastActivity, showDetails, disclosureSpacePx)
    }

    fun beginDisclosure() {
        if (!ready) return
        following = false
        val info = listState.layoutInfo
        disclosureSettled = false
        disclosureGeneration++
        disclosureSpacePx = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(0)
    }

    fun releaseDisclosure() { disclosureSpacePx = 0 }

    /** User input releases the reading hold, not the space supporting the current anchor. */

    fun settleDisclosure() {
        if (!ready || disclosureMotions > 0) return
        disclosureSettled = true
        trimDisclosureSpace()
    }

    fun trimDisclosureSpace() {
        if (!ready || !disclosureSettled || disclosureSpacePx == 0 || disclosureMotions > 0) return
        val info = listState.layoutInfo
        val (normalPadding, appliedSpace) = disclosurePadding ?: return
        // Composition can publish new padding before the LazyColumn has measured it. Never derive a base
        // gutter by subtracting a stale/unclamped reservation.
        if (appliedSpace == 0 || info.afterContentPadding != normalPadding + appliedSpace) return
        val tail = info.visibleItemsInfo.lastOrNull() ?: return
        val required = if (tail.index < info.totalItemsCount - 1) 0 else {
            (info.viewportEndOffset.toLong() - tail.offset - tail.size - normalPadding)
                .coerceIn(0L, minOf(disclosureSpacePx, appliedSpace).toLong()).toInt()
        }
        disclosureSpacePx = required
    }

    suspend fun latest() {
        releaseDisclosure()
        appScrolls++
        try {
            val last = listState.layoutInfo.totalItemsCount - 1
            if (last < 0) return
            listState.scrollToItem(last)

            val info = listState.layoutInfo
            info.visibleItemsInfo.lastOrNull()?.let { tail ->
                val overflow = tail.offset.toLong() + tail.size + info.afterContentPadding - info.viewportEndOffset
                if (overflow > 0) listState.scrollBy(overflow.toFloat())
            }
            following = true
            unread = 0
        } finally { appScrolls-- }
    }

    /** Automatic resize/output following does not jump a visible final row back to its start. */
    suspend fun followLatest() {
        if (!ready || !following || dragging) return
        appScrolls++
        try {

            withFrameNanos { }
            if (!following || dragging) return
            val info = listState.layoutInfo
            val tail = info.visibleItemsInfo.lastOrNull()
            val adjustment = agentChatTailAdjustment(following, dragging, info.totalItemsCount,
                tail?.index, tail?.offset ?: 0, tail?.size ?: 0, info.afterContentPadding, info.viewportEndOffset)
            if (adjustment.jumpToLast) {
                listState.scrollToItem(info.totalItemsCount - 1)

                if (!following || dragging) return
                val updated = listState.layoutInfo
                updated.visibleItemsInfo.lastOrNull()?.takeIf { it.index == updated.totalItemsCount - 1 }?.let {
                    val overflow = it.offset.toLong() + it.size + updated.afterContentPadding - updated.viewportEndOffset
                    if (overflow > 0) listState.scrollBy(overflow.toFloat())
                }
            } else if (adjustment.scrollByPixels > 0) listState.scrollBy(adjustment.scrollByPixels.toFloat())
            if (following && !dragging) unread = 0
        } catch (cancelled: CancellationException) {
            // User input may cancel a scroll mutation without cancelling this observer's owner. Do not lose
            // future resize/output observations, or swallow disposal cancellation.
            currentCoroutineContext().ensureActive()
        } finally { appScrolls-- }
    }
}

private data class ChatViewportObservation(
    val index: Int, val offset: Int, val forward: Boolean, val height: Int, val bottomPadding: Int, val count: Int,
    val scrolling: Boolean, val appScrolls: Int, val ready: Boolean, val unread: Int, val following: Boolean,
)

private data class ChatViewportUpdate(val activity: AgentChatActivity, val height: Int, val bottomPadding: Int, val count: Int, val ready: Boolean)

@Composable
internal fun rememberAgentChatViewport(
    owner: AgentWorkspaceUiState,
    conversation: AgentChatConversationKey,
    saved: AgentChatPosition?,
    keys: List<String>,
    activity: AgentChatActivity,
    showDetails: Boolean,
): AgentChatViewport {
    val viewport = remember(owner, conversation) {
        val index = saved?.anchorKey?.let(keys::indexOf)?.takeIf { it >= 0 }
            ?: (saved?.index ?: 0).coerceIn(0, keys.lastIndex.coerceAtLeast(0))
        AgentChatViewport(LazyListState(index, saved?.offset ?: 0), saved, activity)
    }
    val currentDetails by rememberUpdatedState(showDetails)
    val currentActivity by rememberUpdatedState(activity)
    DisposableEffect(owner, conversation, viewport) {
        onDispose {
            viewport.showDetails = currentDetails
            viewport.position()?.let { owner.saveChatPosition(conversation, it) }
        }
    }
    LaunchedEffect(viewport, keys) {
        if (viewport.ready) return@LaunchedEffect
        // An existing session may publish its messages after its ID. Do not overwrite its anchor with a
        // temporary welcome row while restore is still preparing the real transcript.
        if (saved?.anchorKey != null && saved.anchorKey != "welcome" && keys == listOf("welcome")) return@LaunchedEffect
        snapshotFlow { viewport.listState.layoutInfo.totalItemsCount }.first { it == keys.size }
        viewport.appScrolls++
        try {
            withFrameNanos { }
            if (saved != null) {
                val index = saved.anchorKey?.let(keys::indexOf)?.takeIf { it >= 0 }
                    ?: saved.index.coerceIn(0, keys.lastIndex.coerceAtLeast(0))
                viewport.listState.scrollToItem(index, saved.offset)

                viewport.following = saved.following && !viewport.listState.canScrollForward
                viewport.unread = (saved.unread + activity.addedSince(saved.activity)).coerceAtMost(10_000)
            } else viewport.latest()
            viewport.lastActivity = activity
            viewport.lastHeight = viewport.listState.layoutInfo.viewportEndOffset - viewport.listState.layoutInfo.viewportStartOffset
            viewport.lastBottomPadding = viewport.listState.layoutInfo.afterContentPadding
            viewport.lastItemCount = viewport.listState.layoutInfo.totalItemsCount
            viewport.ready = true
        } finally { viewport.appScrolls-- }
    }
    LaunchedEffect(viewport) {
        viewport.listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    viewport.dragging = true
                    viewport.following = false
                    viewport.trimDisclosureSpace()
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    viewport.dragging = false
                    viewport.following = viewport.disclosureSpacePx == 0 && !viewport.listState.canScrollForward
                    if (viewport.following) viewport.unread = 0
                }
            }
        }
    }
    // Retire only surplus clearance after the disclosure transition settles. Keep the remaining
    // space supporting the reader, including across touch-down and cancellation.
    LaunchedEffect(viewport, viewport.ready, viewport.disclosureGeneration, viewport.disclosureMotions) {
        if (viewport.disclosureMotions == 0) {
            withFrameNanos { }
            withFrameNanos { }
            viewport.settleDisclosure()
        }
    }
    LaunchedEffect(owner, conversation, viewport) {
        var previous: ChatViewportObservation? = null
        snapshotFlow {
            val list = viewport.listState
            ChatViewportObservation(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset,
                list.canScrollForward, list.layoutInfo.viewportEndOffset - list.layoutInfo.viewportStartOffset,
                list.layoutInfo.afterContentPadding, list.layoutInfo.totalItemsCount, list.isScrollInProgress, viewport.appScrolls,
                viewport.ready, viewport.unread, viewport.following)
        }.collect { now ->
            val old = previous
            if (now.ready && now.appScrolls == 0) viewport.trimDisclosureSpace()
            val moved = old != null && (old.index != now.index || old.offset != now.offset)
            val userScrollEnded = old?.scrolling == true && !now.scrolling && old.appScrolls == 0
            if (now.ready && now.appScrolls == 0 && !viewport.dragging && viewport.disclosureSpacePx == 0 &&
                (userScrollEnded || (moved && (now.scrolling ||
                    (old!!.height == now.height && old.bottomPadding == now.bottomPadding && old.count == now.count))))) {

                viewport.following = if (now.scrolling) false else !now.forward
            }
            if (now.ready && !now.forward && !viewport.dragging) viewport.unread = 0
            viewport.showDetails = currentDetails
            viewport.position()?.let { owner.saveChatPosition(conversation, it) }
            previous = now
        }
    }
    // Observe measurements outside composition. IME pixels do not restart this effect or make the
    // entire AgentPane recompose just to request another scroll to the same final row.
    LaunchedEffect(viewport) {
        snapshotFlow {
            val info = viewport.listState.layoutInfo
            ChatViewportUpdate(currentActivity, info.viewportEndOffset - info.viewportStartOffset,
                info.afterContentPadding, info.totalItemsCount, viewport.ready)
        }.collect { update ->
            if (!update.ready) return@collect
            val changed = update.activity != viewport.lastActivity
            // Activity can be published before the newly appended row is measured. Observe the measured
            // count as well, so that first observation cannot consume its only trigger.
            val resized = update.height != viewport.lastHeight || update.bottomPadding != viewport.lastBottomPadding ||
                update.count != viewport.lastItemCount
            val added = update.activity.addedSince(viewport.lastActivity)
            viewport.lastActivity = update.activity
            viewport.lastHeight = update.height
            viewport.lastBottomPadding = update.bottomPadding
            viewport.lastItemCount = update.count
            if (changed || resized) {
                if (viewport.following && !viewport.dragging) viewport.followLatest()
                // A drag may have interrupted following while the scroll mutation suspended.
                if (changed && (!viewport.following || viewport.dragging)) {
                    viewport.unread = (viewport.unread + added).coerceAtMost(10_000)
                }
            }
        }
    }
    return viewport
}
