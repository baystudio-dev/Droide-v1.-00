package com.baystudio.droide.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val RETURN_TO_LATEST_MILLIS = 220

/** Animate the final viewport of an explicit navigation; restores remain immediate. */
internal suspend fun LazyListState.scrollToChatTail(animate: Boolean = false) {
    if (layoutInfo.totalItemsCount == 0) return
    val motionEnabled = animate && (currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f) > 0f
    if (motionEnabled) {
        val info = layoutInfo
        val travelLimit = (info.viewportEndOffset.toLong() - info.viewportStartOffset)
            .coerceAtLeast(0).toFloat() * .6f
        val distance = chatTailOverflow()
        val travel = if (distance != null && distance <= travelLimit) distance else {
            // Long histories skip to the final screen instead of animating through every row.
            alignChatTail()
            currentCoroutineContext().ensureActive()
            -scrollBy(-travelLimit)
        }
        if (travel > 0f) animateScrollBy(travel, tween(RETURN_TO_LATEST_MILLIS, easing = FastOutSlowInEasing))
    }
    currentCoroutineContext().ensureActive()
    // Streaming and keyboard resizing may have moved the tail during the gesture.
    alignChatTail()
}

private suspend fun LazyListState.alignChatTail() {
    if (layoutInfo.totalItemsCount == 0) return
    if (chatTailOverflow() == null) scrollToItem(layoutInfo.totalItemsCount - 1)
    chatTailOverflow()?.takeIf { it > 0f }?.let { scrollBy(it) }
}

private fun LazyListState.chatTailOverflow(): Float? {
    val info = layoutInfo
    val tail = info.visibleItemsInfo.lastOrNull()?.takeIf { it.index == info.totalItemsCount - 1 } ?: return null
    return (tail.offset.toLong() + tail.size + info.afterContentPadding - info.viewportEndOffset)
        .coerceAtLeast(0).toFloat()
}
