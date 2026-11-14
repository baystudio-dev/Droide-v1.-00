package com.baystudio.droide.ui

internal enum class StatusBarInsetMotionMode {
    SETTLED,
    NATIVE,
    FALLBACK,
}

/** A caller-owned animation must start at [sourceTopPx], keyed by [generation]. */
internal data class StatusBarInsetFallbackPlan(
    val generation: Long,
    val sourceTopPx: Float,
    val targetTopPx: Float,
)

/** Tag animation frames so a cancelled animation cannot move a newer transition. */
internal data class StatusBarInsetFallbackFrame(
    val generation: Long,
    val topPx: Float,
)

internal data class StatusBarInsetMotionSample(
    val topPx: Float,
    val mode: StatusBarInsetMotionMode,
    val fallback: StatusBarInsetFallbackPlan? = null,
)

/** Native inset motion stays authoritative unless the editor explicitly owns the transition clock. */
internal class StatusBarInsetMotionController {
    private var initialized = false
    private var metricsKey: Any? = null
    private var shownTop = 0
    private var lastNativeTop = 0
    private var lastRenderedTop = 0f
    private var nativeTrajectory = false
    private var settledHidden = false
    private var generation = 0L
    private var fallback: StatusBarInsetFallbackPlan? = null
    private var requestDriven = false
    private var requestTarget = 0
    val reclaimsTopInset: Boolean get() = requestDriven

    fun sample(
        currentTopPx: Int,
        shownTopPx: Int,
        metricsKey: Any? = null,
        reducedMotion: Boolean = false,
        requestedHidden: Boolean = false,
        fallbackFrame: StatusBarInsetFallbackFrame? = null,
        anticipateHiddenRequest: Boolean = false,
    ): StatusBarInsetMotionSample {
        val shown = shownTopPx.coerceAtLeast(0)
        val current = currentTopPx.coerceIn(0, shown)
        val intermediate = current > 0 && current < shown
        if ((anticipateHiddenRequest && requestedHidden) || requestDriven) {
            return sampleRequest(current, shown, metricsKey, reducedMotion,
                requestedHidden, fallbackFrame)
        }
        if (!requestedHidden) settledHidden = false

        if (!initialized || this.metricsKey != metricsKey || shownTop != shown) {
            initialized = true
            this.metricsKey = metricsKey
            shownTop = shown
            return if (settledHidden) adoptNative(0, false, requestedHidden)
            else adoptNative(current, intermediate, requestedHidden)
        }

        // A swipe-revealed bar is an overlay once immersive hiding has completed.
        // Keep the latch over metric changes; only an actual show request releases it.
        if (settledHidden) return adoptNative(0, false, requestedHidden)

        // Native samples always win, including when a fallback has just started.
        // Reduced motion disables our timer, not the system's actual geometry.
        if (intermediate || nativeTrajectory || reducedMotion) {
            return adoptNative(current, intermediate, requestedHidden)
        }

        val previousPlan = fallback
        if (previousPlan != null && fallbackFrame?.generation == previousPlan.generation &&
            fallbackFrame.topPx.isFinite()
        ) {
            lastRenderedTop = fallbackFrame.topPx.coerceIn(
                minOf(previousPlan.sourceTopPx, previousPlan.targetTopPx),
                maxOf(previousPlan.sourceTopPx, previousPlan.targetTopPx),
            )
            if (lastRenderedTop == previousPlan.targetTopPx) fallback = null
        }

        if (current != lastNativeTop) {
            // Both samples are endpoints; otherwise adoptNative handled this above.
            fallback = if (lastRenderedTop != current.toFloat()) {
                StatusBarInsetFallbackPlan(++generation, lastRenderedTop, current.toFloat())
            } else {
                null
            }
        }
        lastNativeTop = current

        val plan = fallback
        return if (plan != null) {
            StatusBarInsetMotionSample(lastRenderedTop, StatusBarInsetMotionMode.FALLBACK, plan)
        } else {
            lastRenderedTop = current.toFloat()
            settledHidden = requestedHidden && current == 0
            StatusBarInsetMotionSample(lastRenderedTop, StatusBarInsetMotionMode.SETTLED)
        }
    }

    private fun sampleRequest(
        current: Int,
        shown: Int,
        metrics: Any?,
        reducedMotion: Boolean,
        hidden: Boolean,
        frame: StatusBarInsetFallbackFrame?,
    ): StatusBarInsetMotionSample {
        val target = if (hidden) 0 else shown
        if (!initialized || metricsKey != metrics || shownTop != shown) {
            initialized = true
            metricsKey = metrics
            shownTop = shown
            requestDriven = hidden || current != shown
            requestTarget = target
            return adoptNative(target, false, hidden)
        }
        val old = fallback
        // Retarget from the last layout, without consuming a queued frame from the previous direction.
        val retarget = !requestDriven || requestTarget != target
        if (!retarget && old != null && frame?.generation == old.generation && frame.topPx.isFinite()) {
            lastRenderedTop = frame.topPx.coerceIn(minOf(old.sourceTopPx, old.targetTopPx),
                maxOf(old.sourceTopPx, old.targetTopPx))
            if (lastRenderedTop == old.targetTopPx) fallback = null
        }
        if (retarget) {
            fallback = if (!reducedMotion && lastRenderedTop != target.toFloat()) {
                StatusBarInsetFallbackPlan(++generation, lastRenderedTop, target.toFloat())
            } else null
        }
        requestDriven = true
        requestTarget = target
        lastNativeTop = current
        nativeTrajectory = false
        if (reducedMotion) fallback = null
        val plan = fallback
        if (plan != null) return StatusBarInsetMotionSample(lastRenderedTop,
            StatusBarInsetMotionMode.FALLBACK, plan)
        lastRenderedTop = target.toFloat()
        settledHidden = hidden
        if (!hidden && current == shown) requestDriven = false
        return StatusBarInsetMotionSample(lastRenderedTop, StatusBarInsetMotionMode.SETTLED)
    }

    private fun adoptNative(
        current: Int,
        intermediate: Boolean,
        requestedHidden: Boolean,
    ): StatusBarInsetMotionSample {
        fallback = null
        nativeTrajectory = intermediate
        settledHidden = requestedHidden && current == 0
        lastNativeTop = current
        lastRenderedTop = current.toFloat()
        return StatusBarInsetMotionSample(
            lastRenderedTop,
            if (intermediate) StatusBarInsetMotionMode.NATIVE else StatusBarInsetMotionMode.SETTLED,
        )
    }
}
