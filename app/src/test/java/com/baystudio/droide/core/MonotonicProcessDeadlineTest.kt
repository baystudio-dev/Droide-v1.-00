package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class MonotonicProcessDeadlineTest {
    @Test fun deadlineUsesElapsedRuntimeEvenWhenSchedulerPauses() {
        val launched = 5_000_000_000L
        val policy = MonotonicProcessDeadline(launched, 150)
        assertFalse(policy.expired(launched))
        assertFalse(policy.expired(launched + 149_999_999L))
        assertTrue(policy.expired(launched + 150_000_000L))
        assertTrue(policy.expired(launched + 3_000_000_000L))
        assertFalse(MonotonicProcessDeadline(launched, 0).expired(launched + 86_400_000_000_000L))
    }

    @Test fun maximumAndInvalidDeadlinesRemainBounded() {
        assertFalse(MonotonicProcessDeadline(0, 86_400_000).expired(86_399_999_000_000L))
        assertTrue(MonotonicProcessDeadline(0, 86_400_000).expired(86_400_000_000_000L))
        assertThrows(IllegalArgumentException::class.java) { MonotonicProcessDeadline(0, 86_400_001) }
    }
}
