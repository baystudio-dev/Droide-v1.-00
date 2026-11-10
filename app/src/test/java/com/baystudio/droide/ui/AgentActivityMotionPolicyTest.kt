package com.baystudio.droide.ui

import org.junit.Assert.*
import org.junit.Test

class AgentActivityMotionPolicyTest {
    @Test fun initialSnapshotIsNeverAdmitted() {
        val policy = AgentActivityEntrancePolicy(listOf("tool:1:0", "tool:2:0"))
        assertFalse(policy.claim("tool:1:0", true))
        assertFalse(policy.claim("tool:2:0", true))
    }
    @Test fun liveToolHasAtMostOneEntrance() {
        val policy = AgentActivityEntrancePolicy(emptyList())
        assertTrue(policy.claim("tool:1:0", true))
        repeat(100) { assertFalse(policy.claim("tool:1:0", true)) }
        assertEquals(1, policy.rememberedCount)
    }
    @Test fun disablingMotionConsumesTheEntrance() {
        val policy = AgentActivityEntrancePolicy(emptyList())
        assertFalse(policy.claim("tool:1:0", false))
        assertFalse(policy.claim("tool:1:0", true))
    }
    @Test fun readingHistoryConsumesTheEntranceWithoutAQueuedAnimation() {
        val policy = AgentActivityEntrancePolicy(emptyList())
        assertFalse(policy.claim("tool:1:0", false))
        assertTrue(policy.claim("tool:1:1", true))
        assertFalse(policy.claim("tool:1:0", true))
    }
    @Test fun reorderingAndLazyRebindingDoNotReplay() {
        val policy = AgentActivityEntrancePolicy(emptyList())
        listOf("a", "b", "c").forEach { assertTrue(policy.claim(it, true)) }
        listOf("c", "a", "b", "a").forEach { assertFalse(policy.claim(it, true)) }
    }
    @Test fun remountSeedsEveryCurrentToolIncludingOffscreenTools() {
        val ids = (0..200).map { "tool:1:$it" }
        val restored = AgentActivityEntrancePolicy(ids)
        ids.reversed().forEach { assertFalse(restored.claim(it, true)) }
        assertTrue(restored.claim("tool:2:0", true))
    }
    @Test fun aNewRunCanUseTheSameRunLocalPartId() {
        val first = AgentActivityEntrancePolicy(emptyList())
        val second = AgentActivityEntrancePolicy(emptyList())
        assertTrue(first.claim("tool:1:0", true))
        assertTrue(second.claim("tool:1:0", true))
        assertNotEquals(agentActivityItemKey(1, "tool:1:0"), agentActivityItemKey(2, "tool:1:0"))
    }
    @Test fun newConversationGetsIndependentDecorativeState() {
        val first = AgentActivityEntrancePolicy(listOf("tool:1:0"))
        val second = AgentActivityEntrancePolicy(emptyList())
        assertFalse(first.claim("tool:1:0", true))
        assertTrue(second.claim("tool:1:0", true))
    }
    @Test fun keyPreservesIdentityAndNeverUsesOutputOrPosition() {
        assertEquals("stream:23:tool:1:0", agentActivityItemKey(23, "tool:1:0"))
        assertEquals(agentActivityItemKey(23, "a:b"), agentActivityItemKey(23, "a:b"))
        assertNotEquals(agentActivityItemKey(2, "3:a"), agentActivityItemKey(23, "a"))
    }
    @Test fun capFailsClosedAndDoesNotGrow() {
        val policy = AgentActivityEntrancePolicy(emptyList(), capacity = 3)
        repeat(3) { assertTrue(policy.claim("tool:$it", true)) }
        repeat(10_000) { assertFalse(policy.claim("overflow:$it", true)) }
        assertFalse(policy.claim("tool:0", true))
        assertEquals(3, policy.rememberedCount)
    }
    @Test fun oversizedInitialSnapshotFailsClosed() {
        val policy = AgentActivityEntrancePolicy((0..99).map(Int::toString), capacity = 3)
        assertFalse(policy.claim("0", true))
        assertFalse(policy.claim("99", true))
        assertFalse(policy.claim("new", true))
        assertEquals(3, policy.rememberedCount)
    }
    @Test fun duplicateSeedIdsDoNotWasteCapacity() {
        val policy = AgentActivityEntrancePolicy(listOf("a", "a", "b"), capacity = 3)
        assertTrue(policy.claim("c", true))
        assertEquals(3, policy.rememberedCount)
    }
    @Test fun falseClaimsCountTowardTheCap() {
        val policy = AgentActivityEntrancePolicy(emptyList(), capacity = 1)
        assertFalse(policy.claim("a", false))
        assertFalse(policy.claim("b", true))
        assertEquals(1, policy.rememberedCount)
    }
    @Test(expected = IllegalArgumentException::class) fun zeroCapacityRejected() {
        AgentActivityEntrancePolicy(emptyList(), capacity = 0)
    }
    @Test(expected = IllegalArgumentException::class) fun negativeCapacityRejected() {
        AgentActivityEntrancePolicy(emptyList(), capacity = -1)
    }
    @Test fun timingsAreShortAndAlphaNeverStartsHidden() {
        assertEquals(180, AgentActivityMotion.EntranceMillis)
        assertEquals(120, AgentActivityMotion.StatusMillis)
        assertEquals(180, AgentActivityMotion.DisclosureMillis)
        assertTrue(AgentActivityMotion.InitialAlpha in .7f..1f)
        assertEquals(1024, AgentActivityMotion.MaxRememberedEntrances)
    }
}
