package com.baystudio.droide.ui

import org.junit.Assert.*
import org.junit.Test

class AgentChatTailPolicyTest {
    private fun adjustment(following: Boolean = true, dragging: Boolean = false, count: Int = 60,
        index: Int? = 59, offset: Int = 400, size: Int = 176, padding: Int = 24, end: Int = 600) =
        agentChatTailAdjustment(following, dragging, count, index, offset, size, padding, end)

    @Test fun alignedTailNeedsNoScrollOrJump() { assertEquals(AgentChatTailAdjustment(), adjustment()) }
    @Test fun emptyListNeverJumps() { assertEquals(AgentChatTailAdjustment(), adjustment(count = 0, index = null)) }
    @Test fun welcomeRowSmallerThanViewportNeedsNoScroll() {
        assertEquals(AgentChatTailAdjustment(), adjustment(count = 1, index = 0, offset = 12, size = 48))
    }
    @Test fun shrinkCorrectsOnlyOverflow() { assertEquals(AgentChatTailAdjustment(scrollByPixels = 10), adjustment(end = 590)) }
    @Test fun growDoesNotJumpTailToStart() { assertEquals(AgentChatTailAdjustment(), adjustment(end = 700)) }
    @Test fun tallFinalRowIsAlignedByItsEnd() {
        assertEquals(AgentChatTailAdjustment(scrollByPixels = 624), adjustment(offset = -1000, size = 2200))
    }
    @Test fun afterPaddingOnlyChangeIsIncluded() {
        assertEquals(AgentChatTailAdjustment(scrollByPixels = 16), adjustment(padding = 40))
    }
    @Test fun realNewOffscreenFinalRowRequiresJump() { assertEquals(AgentChatTailAdjustment(jumpToLast = true), adjustment(count = 61)) }
    @Test fun earlierVisibleRowCannotMasqueradeAsFinalRow() {
        assertEquals(AgentChatTailAdjustment(jumpToLast = true), adjustment(index = 30, offset = 0, size = 30))
    }
    @Test fun missingVisibleRowsRequireJumpOnlyWhenFollowing() {
        assertEquals(AgentChatTailAdjustment(jumpToLast = true), adjustment(index = null))
        assertEquals(AgentChatTailAdjustment(), adjustment(index = null, following = false))
    }
    @Test fun readingAnchorIsNeverAdjusted() {
        for (end in 150..650 step 5) assertEquals(AgentChatTailAdjustment(), adjustment(following = false, end = end))
    }
    @Test fun userDragTakesPriorityOverFollowAndNewOutput() {
        assertEquals(AgentChatTailAdjustment(), adjustment(dragging = true, end = 100))
        assertEquals(AgentChatTailAdjustment(), adjustment(dragging = true, count = 61))
    }
    @Test fun overflowArithmeticDoesNotWrapInt() {
        assertEquals(AgentChatTailAdjustment(scrollByPixels = 3L * Int.MAX_VALUE),
            adjustment(offset = Int.MAX_VALUE, size = Int.MAX_VALUE, padding = Int.MAX_VALUE, end = 0))
    }
    @Test fun frameSteppedShrinkDoesNotRequestRepeatedFullJumps() {
        var tailOffset = 400
        var fullJumps = 0
        var corrected = 0L
        for (end in 600 downTo 300 step 5) {
            val action = adjustment(offset = tailOffset, end = end)
            if (action.jumpToLast) fullJumps++
            corrected += action.scrollByPixels
            tailOffset -= action.scrollByPixels.toInt()
        }
        assertEquals(0, fullJumps)
        assertEquals(300L, corrected)
    }
    @Test fun repeatedEmptyChatShowHideProducesNoAutomaticScrollCommands() {
        repeat(5) {
            for (end in (600 downTo 150 step 5) + (150..600 step 5)) {
                assertEquals(AgentChatTailAdjustment(), adjustment(count = 1, index = 0, offset = 12, size = 48, end = end))
            }
        }
    }
}
