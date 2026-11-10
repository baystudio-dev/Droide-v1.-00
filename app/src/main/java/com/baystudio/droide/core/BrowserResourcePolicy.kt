package com.baystudio.droide.core

import android.content.ComponentCallbacks2

/** Event-driven admission, not a claim about current free memory. No polling or timers. */
internal class BrowserResourcePolicy {
    private var fullPageBlockedUntil = 0L
    private var screenshotBlockedUntil = 0L

    fun onMemoryPressure(level: Int, now: Long): Boolean {
        val critical = level in ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL until ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE
        val low = level in ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE until ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        // UI_HIDDEN is a visibility event, not evidence of exhausted memory.
        if (low) fullPageBlockedUntil = maxOf(fullPageBlockedUntil, now + PRESSURE_BACKOFF_MS)
        if (critical) screenshotBlockedUntil = maxOf(screenshotBlockedUntil, now + PRESSURE_BACKOFF_MS)
        return critical
    }

    fun isPressureSignal(level: Int): Boolean =
        level in ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE until ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND

    fun requireScreenshotAllowed(fullPage: Boolean, now: Long) {
        check(now >= screenshotBlockedUntil) {
            "Browser screenshot deferred after Android reported memory pressure. Use a text read or retry later."
        }
        check(!fullPage || now >= fullPageBlockedUntil) {
            "Full-page screenshot deferred after Android reported memory pressure. Use a text read or viewport screenshot, or retry later."
        }
    }

    companion object { const val PRESSURE_BACKOFF_MS = 30_000L }
}
