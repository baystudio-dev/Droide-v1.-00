package com.baystudio.droide.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessoryImeMotionPolicyTest {
    private fun assertFraction(expected: Float, actual: Float) = assertEquals(expected, actual, 0.00001f)

    @Test
    fun openingFollowsNativeGeometryWithConstantEndpoints() {
        val motion = AccessoryImeMotionController()
        for (current in listOf(0, 15, 75, 180, 299, 300)) {
            assertFraction(current / 300f, motion.sample(current, 0, 300, 0.91f, rawImeVisible = false))
        }
    }

    @Test
    fun closingFollowsNativeGeometryEvenWhileFallbackIsFullyVisible() {
        val motion = AccessoryImeMotionController()
        for (current in listOf(300, 240, 150, 45, 1, 0)) {
            assertFraction(current / 300f, motion.sample(current, 300, 0, 1f, rawImeVisible = false))
        }
    }

    @Test
    fun firstSampleMidOpeningDoesNotWaitForAStartFrame() {
        assertFraction(0.4f, AccessoryImeMotionController().sample(120, 0, 300, 0f, rawImeVisible = false))
    }

    @Test
    fun firstSampleMidClosingDoesNotJumpToFullyVisible() {
        assertFraction(0.4f, AccessoryImeMotionController().sample(120, 300, 0, 1f, rawImeVisible = false))
    }

    @Test
    fun closeToOpenReversalKeepsCurrentFractionDespitePositiveSource() {
        val motion = AccessoryImeMotionController()
        assertFraction(0.4f, motion.sample(120, 300, 0, 1f, rawImeVisible = false))
        assertFraction(0.4f, motion.sample(120, 120, 300, 0f, rawImeVisible = false))
        assertFraction(0.7f, motion.sample(210, 120, 300, 0f, rawImeVisible = false))
        assertFraction(1f, motion.sample(300, 120, 300, 0f, rawImeVisible = false))
    }

    @Test
    fun openToCloseReversalKeepsCurrentFractionAndReachesZero() {
        val motion = AccessoryImeMotionController()
        assertFraction(0.4f, motion.sample(120, 0, 300, 1f, rawImeVisible = false))
        assertFraction(0.4f, motion.sample(120, 120, 0, 1f, rawImeVisible = false))
        assertFraction(0.2f, motion.sample(60, 120, 0, 1f, rawImeVisible = false))
        assertFraction(0f, motion.sample(0, 120, 0, 1f, rawImeVisible = false))
    }

    @Test
    fun repeatedRapidReversalsRemainContinuous() {
        val motion = AccessoryImeMotionController()
        assertFraction(0.5f, motion.sample(150, 300, 0, 1f, rawImeVisible = false))
        assertFraction(0.75f, motion.sample(225, 150, 300, 0f, rawImeVisible = false))
        assertFraction(0.75f, motion.sample(225, 225, 0, 1f, rawImeVisible = false))
        assertFraction(0.25f, motion.sample(75, 225, 0, 1f, rawImeVisible = false))
        assertFraction(0.25f, motion.sample(75, 75, 300, 0f, rawImeVisible = false))
        assertFraction(1f, motion.sample(300, 75, 300, 0f, rawImeVisible = false))
    }

    @Test
    fun visibleKeyboardHeightChangesKeepTheFullAccessory() {
        val motion = AccessoryImeMotionController()
        for (current in listOf(300, 330, 360, 420)) {
            assertFraction(1f, motion.sample(current, 300, 420, 0f, rawImeVisible = false))
        }
        for (current in listOf(420, 380, 300, 240)) {
            assertFraction(1f, motion.sample(current, 420, 240, 0f, rawImeVisible = false))
        }
    }

    @Test
    fun settledAndReducedMotionSamplesDoNotStartASecondAnimation() {
        val motion = AccessoryImeMotionController()
        assertFraction(0f, motion.sample(0, 0, 0, 0f, rawImeVisible = false))
        assertFraction(1f, motion.sample(300, 300, 300, 0f, rawImeVisible = false))
        assertFraction(0f, motion.sample(0, 300, 0, 1f, rawImeVisible = false))
        assertFraction(0f, motion.sample(0, 0, 0, 0f, rawImeVisible = false))
        assertFraction(1f, motion.sample(300, 0, 300, 0f, rawImeVisible = false))
        assertFraction(1f, motion.sample(300, 300, 300, 0f, rawImeVisible = false))
    }

    @Test
    fun positiveCurrentWithoutNativeEndpointsStillShowsAccessory() {
        assertFraction(1f, AccessoryImeMotionController().sample(220, 0, 0, 0f, rawImeVisible = false))
    }

    @Test
    fun allZeroFloatingInsetsUseOnlySuppliedFallbackFraction() {
        val motion = AccessoryImeMotionController()
        for (fraction in listOf(0f, 0.15f, 0.7f, 1f, 0.6f, 0f)) {
            assertFraction(fraction, motion.sample(0, 0, 0, fraction, rawImeVisible = true))
        }
    }

    @Test
    fun staleFallbackCannotResurrectCompletedNativeDismissalWhenRawHidden() {
        val motion = AccessoryImeMotionController()
        assertFraction(0.5f, motion.sample(150, 300, 0, 1f, rawImeVisible = false))
        assertFraction(0f, motion.sample(0, 300, 0, 1f, rawImeVisible = false))
        // Even a lagging fallback cannot revive a keyboard that is now hidden.
        assertFraction(0f, motion.sample(0, 0, 0, 1f, rawImeVisible = false))
        assertFraction(0f, motion.sample(0, 0, 0, 0.7f, rawImeVisible = false))

        assertFraction(0.2f, motion.sample(0, 0, 0, 0.2f, rawImeVisible = true))
        assertFraction(0.1f, motion.sample(0, 0, 0, 0.1f, rawImeVisible = false))
    }

    @Test
    fun skippedNativeClosingFramesStillSuppressStaleFallbackAtHiddenEndpoint() {
        val motion = AccessoryImeMotionController()
        assertFraction(1f, motion.sample(300, 300, 300, 1f, rawImeVisible = true))
        assertFraction(0f, motion.sample(0, 0, 0, 1f, rawImeVisible = false))
    }

    @Test
    fun dockedToFloatingKeyboardPermitsFallbackWhenRawVisibilityStaysTrue() {
        val motion = AccessoryImeMotionController()
        assertFraction(1f, motion.sample(300, 300, 300, 0f, rawImeVisible = true))
        assertFraction(0.5f, motion.sample(0, 0, 0, 0.5f, rawImeVisible = true))
        assertFraction(1f, motion.sample(0, 0, 0, 1f, rawImeVisible = true))
    }

    @Test
    fun callerZeroFallbackHandlesVisibilityLagAfterNativeClose() {
        val motion = AccessoryImeMotionController()
        assertFraction(0f, motion.sample(0, 300, 0, 1f, rawImeVisible = true))
        assertFraction(0f, motion.sample(0, 0, 0, 0f, rawImeVisible = true))
    }

    @Test
    fun staleVisibleNativeCloseBlocksEveryFallbackFrameAndInput() {
        val motion = AccessoryImeMotionController()
        assertFraction(0f, motion.sample(0, 300, 0, 1f, rawImeVisible = true))
        for (fallback in listOf(0f, 0.5f, 1f, 1f)) {
            assertFalse(motion.permitsFallback(rawImeVisible = true))
            assertFalse(motion.allowsInput(true, 0, 0, 0))
            assertFraction(0f, motion.sample(0, 0, 0, fallback, rawImeVisible = true))
        }
    }

    @Test
    fun observedHiddenShowCycleResetsCloseEvenWithoutLayoutSamplesBetween() {
        val motion = AccessoryImeMotionController()
        motion.sample(0, 300, 0, 1f, rawImeVisible = true)
        motion.observeRawVisibility(rawImeVisible = false)
        assertFalse(motion.permitsFallback(rawImeVisible = false))

        assertTrue(motion.permitsFallback(rawImeVisible = true))
        assertTrue(motion.allowsInput(true, 0, 0, 0))
        // The above getters have not mutated the latch.
        assertFalse(motion.permitsFallback(rawImeVisible = false))
        motion.observeRawVisibility(rawImeVisible = true)
        assertFraction(0.5f, motion.sample(0, 0, 0, 0.5f, rawImeVisible = true))
        assertTrue(motion.allowsInput(true, 0, 0, 0))
    }

    @Test
    fun positiveNativeTrajectoryReleasesStaleClosingCycle() {
        val motion = AccessoryImeMotionController()
        motion.sample(0, 300, 0, 1f, rawImeVisible = true)
        assertFalse(motion.permitsFallback(rawImeVisible = true))
        assertTrue(motion.allowsInput(true, 0, 0, 300))
        assertFraction(0.5f, motion.sample(150, 0, 300, 0f, rawImeVisible = true))
        assertTrue(motion.permitsFallback(rawImeVisible = true))
        assertTrue(motion.allowsInput(true, 150, 0, 300))
        assertFraction(1f, motion.sample(300, 0, 300, 0f, rawImeVisible = true))
        assertFraction(0.5f, motion.sample(0, 0, 0, 0.5f, rawImeVisible = true))
    }

    @Test
    fun rawVisibilityCycleDoesNotBreakNativeReversalContinuity() {
        val motion = AccessoryImeMotionController()
        assertFraction(0.4f, motion.sample(120, 300, 0, 1f, rawImeVisible = false))
        motion.observeRawVisibility(rawImeVisible = true)
        assertFraction(0.4f, motion.sample(120, 120, 300, 0f, rawImeVisible = true))
        assertFraction(0.7f, motion.sample(210, 120, 300, 0f, rawImeVisible = true))
    }

    @Test
    fun controllerAllowsFloatingAndMissingTrajectoryInputWithoutClosingHistory() {
        val motion = AccessoryImeMotionController()
        assertTrue(motion.permitsFallback(rawImeVisible = true))
        assertTrue(motion.allowsInput(true, 0, 0, 0))
        assertFalse(motion.allowsInput(false, 0, 0, 0))
        motion.sample(220, 0, 0, 0f, rawImeVisible = false)
        assertTrue(motion.allowsInput(false, 220, 0, 0))
    }

    @Test
    fun repeatedSamplesAreIdempotent() {
        val motion = AccessoryImeMotionController()
        repeat(10) { assertFraction(0.4f, motion.sample(120, 0, 300, 1f, rawImeVisible = false)) }
    }

    @Test
    fun endpointCompletionResetsContinuityForLaterSameTrajectory() {
        val motion = AccessoryImeMotionController()
        assertFraction(0.4f, motion.sample(120, 0, 300, 1f, rawImeVisible = false))
        assertFraction(0f, motion.sample(0, 120, 0, 1f, rawImeVisible = false))
        assertFraction(1f, motion.sample(120, 120, 0, 0f, rawImeVisible = false))
        assertFraction(0.5f, motion.sample(60, 120, 0, 0f, rawImeVisible = false))
    }

    @Test
    fun allZeroOrSettledSampleClearsInterruptedTransitionHistory() {
        val motion = AccessoryImeMotionController()
        assertFraction(0.4f, motion.sample(120, 300, 0, 1f, rawImeVisible = false))
        assertFraction(0f, motion.sample(0, 0, 0, 0f, rawImeVisible = false))
        assertFraction(1f, motion.sample(300, 300, 420, 0f, rawImeVisible = false))
        assertFraction(0.4f, motion.sample(120, 300, 0, 1f, rawImeVisible = false))
        assertFraction(1f, motion.sample(300, 300, 300, 0f, rawImeVisible = false))
        assertFraction(1f, motion.sample(300, 300, 420, 0f, rawImeVisible = false))
    }

    @Test
    fun outOfRangeCoordinatesClampToTransitionEndpoints() {
        assertFraction(0f, AccessoryImeMotionController().sample(-100, 0, 300, 1f, rawImeVisible = false))
        assertFraction(1f, AccessoryImeMotionController().sample(400, 0, 300, 0f, rawImeVisible = false))
        assertFraction(1f, AccessoryImeMotionController().sample(400, 300, 0, 0f, rawImeVisible = false))
        assertFraction(0f, AccessoryImeMotionController().sample(-100, 300, 0, 1f, rawImeVisible = false))
    }

    @Test
    fun malformedNegativeInsetsAreClampedBeforeClassification() {
        assertFraction(0.5f, AccessoryImeMotionController().sample(150, -20, 300, 1f, rawImeVisible = false))
        assertFraction(0.5f, AccessoryImeMotionController().sample(150, 300, -20, 0f, rawImeVisible = false))
        assertFraction(0.6f, AccessoryImeMotionController().sample(-30, -20, -10, 0.6f, rawImeVisible = false))
    }

    @Test
    fun fallbackFractionsRemainFiniteAndBounded() {
        val motion = AccessoryImeMotionController()
        assertFraction(0f, motion.sample(0, 0, 0, -1f, rawImeVisible = false))
        assertFraction(1f, motion.sample(0, 0, 0, 2f, rawImeVisible = false))
        for (fraction in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertFraction(0f, motion.sample(0, 0, 0, fraction, rawImeVisible = true))
        }
    }

    @Test
    fun largeInsetCoordinatesDoNotOverflow() {
        assertFraction(1f, AccessoryImeMotionController().sample(Int.MAX_VALUE, 0, Int.MAX_VALUE, 0f, rawImeVisible = false))
        assertFraction(0f, AccessoryImeMotionController().sample(0, Int.MAX_VALUE, 0, 1f, rawImeVisible = false))
        assertFraction(0f, AccessoryImeMotionController().sample(Int.MIN_VALUE, Int.MAX_VALUE, 0, 1f, rawImeVisible = false))
        assertFraction(1f, AccessoryImeMotionController().sample(Int.MAX_VALUE, Int.MAX_VALUE - 1, Int.MAX_VALUE, 0f, rawImeVisible = true))
    }

    @Test
    fun nativeClosingDisablesInputBeforeRawVisibilityDrops() {
        assertFalse(accessoryImeAllowsInput(true, 300, 300, 0))
        assertFalse(accessoryImeAllowsInput(true, 120, 300, 0))
        assertFalse(accessoryImeAllowsInput(false, 0, 300, 0))
    }

    @Test
    fun floatingKeyboardAllowsInputButHiddenHardwareKeyboardDoesNot() {
        assertTrue(accessoryImeAllowsInput(true, 0, 0, 0))
        assertFalse(accessoryImeAllowsInput(false, 0, 0, 0))
    }

    @Test
    fun nativeOpeningAndPositiveCurrentWithoutTrajectoryAllowInput() {
        assertTrue(accessoryImeAllowsInput(false, 0, 0, 300))
        assertTrue(accessoryImeAllowsInput(false, 150, 0, 300))
        assertTrue(accessoryImeAllowsInput(false, 300, 300, 300))
        assertTrue(accessoryImeAllowsInput(false, 150, 0, 0))
    }

    @Test
    fun malformedInsetsCannotCreateHiddenInputOrOverrideClosingIntent() {
        assertFalse(accessoryImeAllowsInput(false, -1, -2, -3))
        assertFalse(accessoryImeAllowsInput(true, 150, 300, -1))
        assertTrue(accessoryImeAllowsInput(true, -1, -2, -3))
    }
}
