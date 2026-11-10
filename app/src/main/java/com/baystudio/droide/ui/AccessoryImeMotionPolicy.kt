package com.baystudio.droide.ui

/** Read all three insets in the same layout pass; source/target are endpoints, not previous
 * frames. */
internal class AccessoryImeMotionController {
    private var source = 0
    private var target = 0
    private var sourceFraction = 0f
    private var lastFraction = 0f
    private var nativeTransitionActive = false
    private var nativeGeometryObserved = false
    private var nativeClosingObserved = false
    private var hiddenObservedSinceClosing = false

    fun observeRawVisibility(rawImeVisible: Boolean) {
        if (!nativeClosingObserved) return
        if (!rawImeVisible) {
            hiddenObservedSinceClosing = true
        } else if (hiddenObservedSinceClosing) {
            nativeClosingObserved = false
            hiddenObservedSinceClosing = false
            nativeGeometryObserved = false
        }
    }

    fun permitsFallback(rawImeVisible: Boolean): Boolean =
        closingCycleAllows(rawImeVisible) && (rawImeVisible || !nativeGeometryObserved)

    fun allowsInput(
        rawImeVisible: Boolean,
        currentBottomPx: Int,
        sourceBottomPx: Int,
        targetBottomPx: Int,
    ): Boolean = accessoryImeAllowsInput(rawImeVisible, currentBottomPx, sourceBottomPx, targetBottomPx) &&
        (targetBottomPx > 0 || closingCycleAllows(rawImeVisible))

    private fun closingCycleAllows(rawImeVisible: Boolean): Boolean =
        !nativeClosingObserved || (rawImeVisible && hiddenObservedSinceClosing)

    fun sample(
        currentBottomPx: Int,
        sourceBottomPx: Int,
        targetBottomPx: Int,
        fallbackFraction: Float,
        rawImeVisible: Boolean,
    ): Float {
        observeRawVisibility(rawImeVisible)
        val current = currentBottomPx.coerceAtLeast(0)
        val nextSource = sourceBottomPx.coerceAtLeast(0)
        val nextTarget = targetBottomPx.coerceAtLeast(0)

        if (current == 0 && nextSource == 0 && nextTarget == 0) {
            nativeTransitionActive = false
            val fallbackPermitted = permitsFallback(rawImeVisible)
            if (rawImeVisible && fallbackPermitted) nativeGeometryObserved = false
            lastFraction = when {
                !fallbackPermitted -> 0f
                fallbackFraction.isFinite() -> fallbackFraction.coerceIn(0f, 1f)
                else -> 0f
            }
            return lastFraction
        }
        nativeGeometryObserved = true
        if (nextTarget > 0) {
            nativeClosingObserved = false
            hiddenObservedSinceClosing = false
        } else if (nextSource > 0) {
            nativeClosingObserved = true
            hiddenObservedSinceClosing = !rawImeVisible
        }

        // Equal endpoints provide no native trajectory (including reduced motion). A positive current
        // inset still shows the accessory on OEMs without endpoints.
        if (nextSource == nextTarget) {
            nativeTransitionActive = false
            lastFraction = if (current > 0) 1f else 0f
            return lastFraction
        }

        if (!nativeTransitionActive || source != nextSource || target != nextTarget) {
            sourceFraction = when {
                nextSource == 0 -> 0f
                nativeTransitionActive -> lastFraction
                else -> 1f
            }
            source = nextSource
            target = nextTarget
        }

        val progress = ((current.toDouble() - source.toDouble()) /
            (target.toDouble() - source.toDouble())).toFloat().coerceIn(0f, 1f)
        val targetFraction = if (target > 0) 1f else 0f
        lastFraction = (sourceFraction + (targetFraction - sourceFraction) * progress).coerceIn(0f, 1f)

        nativeTransitionActive = progress < 1f
        return lastFraction
    }
}

/** Focus ownership and expanded mode must still be checked by the caller at dispatch time. */

internal fun accessoryImeAllowsInput(
    rawImeVisible: Boolean,
    currentBottomPx: Int,
    sourceBottomPx: Int,
    targetBottomPx: Int,
): Boolean {
    val source = sourceBottomPx.coerceAtLeast(0)
    val target = targetBottomPx.coerceAtLeast(0)
    if (source > 0 && target == 0) return false
    return rawImeVisible || target > 0 || currentBottomPx > 0
}
