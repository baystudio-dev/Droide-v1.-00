package com.baystudio.droide.ui

/** A layout observation, not a new animation: keep following only the real final row. */
internal data class AgentChatTailAdjustment(val jumpToLast: Boolean = false, val scrollByPixels: Long = 0)

internal fun agentChatTailAdjustment(
    following: Boolean,
    dragging: Boolean,
    totalItems: Int,
    lastVisibleIndex: Int?,
    lastVisibleOffset: Int,
    lastVisibleSize: Int,
    afterContentPadding: Int,
    viewportEndOffset: Int,
): AgentChatTailAdjustment {
    if (!following || dragging || totalItems <= 0) return AgentChatTailAdjustment()
    // A newly appended final row can be off screen. An earlier visible row is not the tail.
    if (lastVisibleIndex != totalItems - 1) return AgentChatTailAdjustment(jumpToLast = true)
    val overflow = lastVisibleOffset.toLong() + lastVisibleSize + afterContentPadding - viewportEndOffset
    return AgentChatTailAdjustment(scrollByPixels = overflow.coerceAtLeast(0))
}
