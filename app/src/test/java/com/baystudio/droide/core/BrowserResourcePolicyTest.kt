package com.baystudio.droide.core

import android.content.ComponentCallbacks2
import org.junit.Assert.*
import org.junit.Test

class BrowserResourcePolicyTest {
    @Test fun intermediateFuturePressureLevelsAreNotIgnored() {
        for (level in listOf(12, 45)) {
            val policy = BrowserResourcePolicy()
            assertFalse(policy.onMemoryPressure(level, 100))
            assertTrue(runCatching { policy.requireScreenshotAllowed(true, 100) }.isFailure)
            policy.requireScreenshotAllowed(false, 100)
        }
        val critical = BrowserResourcePolicy()
        assertTrue(critical.onMemoryPressure(17, 100))
        assertTrue(runCatching { critical.requireScreenshotAllowed(false, 100) }.isFailure)
    }
    @Test fun ordinaryBackgroundDoesNotPretendMemoryIsLow() {
        val policy = BrowserResourcePolicy()
        assertFalse(policy.onMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, 100))
        policy.requireScreenshotAllowed(true, 100)
    }

    @Test fun lowMemoryKeepsViewportAvailableAndLongCaptureHasBoundedCooldown() {
        val policy = BrowserResourcePolicy()
        assertFalse(policy.onMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, 100))
        policy.requireScreenshotAllowed(false, 100)
        assertTrue(runCatching { policy.requireScreenshotAllowed(true, 100) }.exceptionOrNull()!!.message!!.contains("viewport"))
        policy.requireScreenshotAllowed(true, 100 + BrowserResourcePolicy.PRESSURE_BACKOFF_MS)
    }

    @Test fun criticalPressureDefersAllNewScreenshotsAndLaterLesserSignalDoesNotClearIt() {
        val policy = BrowserResourcePolicy()
        assertTrue(policy.onMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, 100))
        policy.onMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, 120)
        policy.onMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, 130)
        assertTrue(runCatching { policy.requireScreenshotAllowed(false, 150) }.isFailure)
        policy.requireScreenshotAllowed(false, 100 + BrowserResourcePolicy.PRESSURE_BACKOFF_MS)
        assertTrue(runCatching { policy.requireScreenshotAllowed(true, 100 + BrowserResourcePolicy.PRESSURE_BACKOFF_MS) }.isFailure)
        policy.requireScreenshotAllowed(true, 130 + BrowserResourcePolicy.PRESSURE_BACKOFF_MS)
    }
}
