package com.baystudio.droide.ui

/**
 * Projects sampled IME geometry onto the accessory's reveal fraction. Read all three
 * insets in the same layout pass; source/target are endpoints, not previous frames.
 * Native motion uses only that geometry, never a second animation or a duration.
 *
 * This object belongs to one mounted accessory and retains only ephemeral geometry.
 * An interrupted transition starts from the last rendered fraction, including when
 * a closing keyboard reverses with a positive source inset. A first observation
 * halfway through a transition instead derives its fraction from the endpoints.
 * Positive-to-positive keyboard resizing leaves an already shown accessory full size.
 *
 * With no native geometry, [sample] uses the caller's standard animated fallback.
 * An observed native close blocks fallback, even when raw visibility stays stale
 * true after the endpoints become zero. A subsequently observed hidden/show cycle
 * or a positive native target releases that block. Switching directly from a
 * settled docked keyboard to a floating keyboard still permits fallback. Call
 * [observeRawVisibility] from a post-composition effect even when not measuring;
 * [permitsFallback] and [allowsInput] are read-only composition/dispatch queries.
 */
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

    // A new raw show can be used in composition before its SideEffect commits it.
    // Neither this query nor allowsInput mutates the trajectory or closing latch.
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

        // Equal endpoints provide no native trajectory (including reduced motion).
        // A positive current inset still shows the accessory on OEMs without endpoints.
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
        // Reaching an endpoint, or an equal/all-zero sample above, ends continuity.
        // A later transition with the same endpoints therefore gets a fresh origin.
        nativeTransitionActive = progress < 1f
        return lastFraction
    }
}

/**
 * Presentation can finish exiting while input is disabled immediately. Raw IME
 * visibility may lag native closing intent, so a positive source targeting zero
 * takes precedence. Floating keyboards use raw visibility; a positive current
 * inset covers OEMs that omit trajectory endpoints. Focus ownership and expanded
 * mode must still be checked by the caller at dispatch time. This stateless helper
 * handles native intent only; use the controller's allowsInput for closing-cycle
 * protection after trajectory coordinates have reset to zero.
 */
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
