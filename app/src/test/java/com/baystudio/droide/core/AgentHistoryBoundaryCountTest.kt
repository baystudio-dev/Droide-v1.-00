package com.baystudio.droide.core
import org.junit.Assert.*
import org.junit.Test

class AgentHistoryBoundaryCountTest {
    @Test fun noEvictionKeepsTheSplit() { assertEquals(3, AgentHistory.retainedBaseMessageCount(3, 5, 5)) }
    @Test fun countCapShiftsTheSplit() { assertEquals(497, AgentHistory.retainedBaseMessageCount(500, 503, 500)) }
    @Test fun characterCapShiftsTheSplit() { assertEquals(2, AgentHistory.retainedBaseMessageCount(4, 5, 3)) }
    @Test fun wholePrefixEvictionLeavesAValidZeroSplit() { assertEquals(0, AgentHistory.retainedBaseMessageCount(2, 6, 3)) }
    @Test fun emptyRetentionLeavesNoFrozenMessages() { assertEquals(0, AgentHistory.retainedBaseMessageCount(3, 5, 0)) }
    @Test fun repeatedPublicationWithoutEvictionCannotShiftAgain() {
        val first = AgentHistory.retainedBaseMessageCount(500, 503, 500)
        assertEquals(first, AgentHistory.retainedBaseMessageCount(first, 500, 500))
    }
    @Test fun equalTextIsNotADeduplicationKey() {
        val source = List(4) { AgentMessage("assistant", "Same intentional reply") }
        val retained = AgentHistory.boundedUi(source, 3, 1_000)
        val split = AgentHistory.retainedBaseMessageCount(2, source.size, retained.size)
        assertEquals(1, split); assertEquals(3, retained.size)
    }
    @Test fun rebasingMatchesEveryRetainedPrefixIntersection() {
        for (size in 0..50) for (base in 0..size) for (retained in 0..size) {
            val expected = (0 until base).count { it >= size-retained }
            assertEquals(expected, AgentHistory.retainedBaseMessageCount(base, size, retained))
        }
    }
}
