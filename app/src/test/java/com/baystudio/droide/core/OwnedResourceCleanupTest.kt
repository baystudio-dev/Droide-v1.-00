package com.baystudio.droide.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OwnedResourceCleanupTest {
    @Test fun failureStillReleasesRemainingResourcesAndRetainsEvidence() {
        val order = mutableListOf<String>(); val first = IllegalStateException("extension failed"); val second = LinkageError("native cleanup failed")
        val result = OwnedResourceCleanup.run(
            { order += "extensions"; throw first },
            { order += "terminal"; throw second },
            { order += "scope" },
        )
        assertEquals(listOf("extensions", "terminal", "scope"), order)
        assertSame(first, result.exceptionOrNull()); assertArrayEquals(arrayOf(second), first.suppressed)
    }
    @Test fun gracefulCleanupRetainsFailuresAndCompletesRemainingSteps() = runBlocking {
        val order = mutableListOf<Int>()
        val result = OwnedResourceCleanup.runSuspend({ order += 1; error("failed") }, { order += 2 })
        assertTrue(result.isFailure); assertEquals(listOf(1, 2), order)
    }
}
