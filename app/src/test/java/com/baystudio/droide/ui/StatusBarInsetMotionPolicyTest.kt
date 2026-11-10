package com.baystudio.droide.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusBarInsetMotionPolicyTest {
    private fun assertTop(expected: Float, actual: StatusBarInsetMotionSample) =
        assertEquals(expected, actual.topPx, 0.00001f)

    private fun assertDirect(expected: Int, actual: StatusBarInsetMotionSample) {
        assertTop(expected.toFloat(), actual)
        assertNull(actual.fallback)
        assertNotEquals(StatusBarInsetMotionMode.FALLBACK, actual.mode)
    }

    private fun plan(sample: StatusBarInsetMotionSample): StatusBarInsetFallbackPlan {
        assertEquals(StatusBarInsetMotionMode.FALLBACK, sample.mode)
        assertNotNull(sample.fallback)
        return sample.fallback!!
    }

    private fun frame(plan: StatusBarInsetFallbackPlan, top: Float) =
        StatusBarInsetFallbackFrame(plan.generation, top)

    @Test
    fun initialAttachmentAtEitherEndpointHasNoEntranceAnimation() {
        for (top in listOf(0, 100)) {
            val motion = StatusBarInsetMotionController()
            repeat(3) { assertDirect(top, motion.sample(top, 100)) }
        }
    }

    @Test
    fun initialAttachmentMidNativeMotionUsesCurrentPixels() {
        val motion = StatusBarInsetMotionController()
        val initial = motion.sample(37, 100)
        assertDirect(37, initial)
        assertEquals(StatusBarInsetMotionMode.NATIVE, initial.mode)
        assertDirect(61, motion.sample(61, 100))
        assertDirect(100, motion.sample(100, 100))
    }

    @Test
    fun normalNativeShowAndHideFollowEveryPixelWithoutSecondTimer() {
        val motion = StatusBarInsetMotionController()
        for (top in listOf(0, 1, 13, 37, 81, 99, 100, 100, 99, 63, 24, 1, 0, 0)) {
            assertDirect(top, motion.sample(top, 100))
        }
    }

    @Test
    fun nativeReversalsAreDirectInBothDirections() {
        val motion = StatusBarInsetMotionController()
        for (top in listOf(100, 74, 38, 52, 83, 61, 32, 19, 43, 73, 100)) {
            assertDirect(top, motion.sample(top, 100))
        }
    }

    @Test
    fun endpointOnlyShowUsesCallerFramesThenSettles() {
        val motion = StatusBarInsetMotionController()
        assertDirect(0, motion.sample(0, 100))
        val start = motion.sample(100, 100)
        val plan = plan(start)
        assertTop(0f, start)
        assertEquals(0f, plan.sourceTopPx, 0f)
        assertEquals(100f, plan.targetTopPx, 0f)
        for (top in listOf(5f, 23.5f, 66f, 99f)) {
            val sample = motion.sample(100, 100, fallbackFrame = frame(plan, top))
            assertTop(top, sample)
            assertEquals(plan, sample.fallback)
        }
        assertDirect(100, motion.sample(100, 100, fallbackFrame = frame(plan, 100f)))
        // A delayed old frame cannot restart a completed animation.
        assertDirect(100, motion.sample(100, 100, fallbackFrame = frame(plan, 48f)))
    }

    @Test
    fun endpointOnlyHideUsesCallerFramesThenSettles() {
        val motion = StatusBarInsetMotionController()
        assertDirect(100, motion.sample(100, 100))
        val start = motion.sample(0, 100)
        val plan = plan(start)
        assertTop(100f, start)
        assertEquals(100f, plan.sourceTopPx, 0f)
        assertEquals(0f, plan.targetTopPx, 0f)
        for (top in listOf(99f, 77.25f, 34f, 1f)) {
            assertTop(top, motion.sample(0, 100, fallbackFrame = frame(plan, top)))
        }
        assertDirect(0, motion.sample(0, 100, fallbackFrame = frame(plan, 0f)))
        assertDirect(0, motion.sample(0, 100, fallbackFrame = frame(plan, 70f)))
    }

    @Test
    fun repeatedEndpointSamplesDoNotRestartFallback() {
        val motion = StatusBarInsetMotionController()
        motion.sample(100, 100)
        val plan = plan(motion.sample(0, 100))
        motion.sample(0, 100, fallbackFrame = frame(plan, 67f))
        repeat(12) {
            val sample = motion.sample(0, 100)
            assertTop(67f, sample)
            assertEquals(plan, sample.fallback)
        }
    }

    @Test
    fun nativeCompletionAtEitherEndpointNeverStartsAnimatedTail() {
        for ((start, middle, end) in listOf(Triple(0, 1, 100), Triple(100, 99, 0))) {
            val motion = StatusBarInsetMotionController()
            motion.sample(start, 100)
            assertDirect(middle, motion.sample(middle, 100))
            repeat(6) { assertDirect(end, motion.sample(end, 100)) }
        }
    }

    @Test
    fun aNewDiscreteTransitionAfterNativeCompletionCanUseFallback() {
        val motion = StatusBarInsetMotionController()
        motion.sample(0, 100)
        motion.sample(47, 100)
        assertDirect(100, motion.sample(100, 100))
        val hide = plan(motion.sample(0, 100))
        assertEquals(100f, hide.sourceTopPx, 0f)
        assertEquals(0f, hide.targetTopPx, 0f)
    }

    @Test
    fun nativeGeometryInterruptsFallbackAndIgnoresItsRemainingFrames() {
        val motion = StatusBarInsetMotionController()
        motion.sample(100, 100)
        val hide = plan(motion.sample(0, 100))
        assertTop(82f, motion.sample(0, 100, fallbackFrame = frame(hide, 82f)))
        assertDirect(63, motion.sample(63, 100, fallbackFrame = frame(hide, 76f)))
        assertDirect(21, motion.sample(21, 100, fallbackFrame = frame(hide, 59f)))
        assertDirect(0, motion.sample(0, 100, fallbackFrame = frame(hide, 43f)))
        assertDirect(0, motion.sample(0, 100, fallbackFrame = frame(hide, 12f)))
    }

    @Test
    fun endpointFallbackIsCancelledByFirstNativeFrameInEitherDirection() {
        for ((source, target, native) in listOf(Triple(0, 100, 12), Triple(100, 0, 88))) {
            val motion = StatusBarInsetMotionController()
            motion.sample(source, 100)
            val fallback = plan(motion.sample(target, 100))
            assertDirect(native, motion.sample(native, 100, fallbackFrame = frame(fallback, 50f)))
            assertDirect(target, motion.sample(target, 100, fallbackFrame = frame(fallback, 60f)))
            assertDirect(target, motion.sample(target, 100, fallbackFrame = frame(fallback, 40f)))
        }
    }

    @Test
    fun quickEndpointReversalUsesLatestCallerRenderedPosition() {
        val motion = StatusBarInsetMotionController()
        motion.sample(100, 100)
        val hide = plan(motion.sample(0, 100))
        motion.sample(0, 100, fallbackFrame = frame(hide, 70f))

        val reversed = motion.sample(100, 100, fallbackFrame = frame(hide, 62f))
        val show = plan(reversed)
        assertTop(62f, reversed)
        assertEquals(62f, show.sourceTopPx, 0f)
        assertEquals(100f, show.targetTopPx, 0f)
        assertNotEquals(hide.generation, show.generation)
        val stale = motion.sample(100, 100, fallbackFrame = frame(hide, 33f))
        assertTop(62f, stale)
        assertEquals(show, stale.fallback)
        assertTop(86f, motion.sample(100, 100, fallbackFrame = frame(show, 86f)))
        assertDirect(100, motion.sample(100, 100, fallbackFrame = frame(show, 100f)))
    }

    @Test
    fun repeatedRapidReversalsKeepFreshOriginsAndDistinctGenerations() {
        val motion = StatusBarInsetMotionController()
        motion.sample(0, 100)
        var active = plan(motion.sample(100, 100))
        val generations = mutableSetOf(active.generation)
        for ((target, position) in listOf(0 to 40f, 100 to 25f, 0 to 71f, 100 to 59f)) {
            val sample = motion.sample(target, 100, fallbackFrame = frame(active, position))
            assertTop(position, sample)
            active = plan(sample)
            assertEquals(position, active.sourceTopPx, 0f)
            assertEquals(target.toFloat(), active.targetTopPx, 0f)
            assertTrue(generations.add(active.generation))
        }
        assertDirect(100, motion.sample(100, 100, fallbackFrame = frame(active, 100f)))
    }

    @Test
    fun reversalBeforeFallbackMovesNeedsNoAnimationBackToSameOrigin() {
        val motion = StatusBarInsetMotionController()
        motion.sample(100, 100)
        plan(motion.sample(0, 100))
        assertDirect(100, motion.sample(100, 100))
    }

    @Test
    fun orientationOrDensityKeyChangeCancelsFallbackAndSettles() {
        for (newKey in listOf("landscape@2", "portrait@3")) {
            val motion = StatusBarInsetMotionController()
            motion.sample(100, 100, metricsKey = "portrait@2")
            val hide = plan(motion.sample(0, 100, metricsKey = "portrait@2"))
            val reset = motion.sample(0, 100, metricsKey = newKey, fallbackFrame = frame(hide, 82f))
            assertDirect(0, reset)
            assertDirect(0, motion.sample(0, 100, metricsKey = newKey, fallbackFrame = frame(hide, 50f)))
            val fresh = plan(motion.sample(100, 100, metricsKey = newKey))
            assertNotEquals(hide.generation, fresh.generation)
            assertTop(0f, motion.sample(100, 100, metricsKey = newKey, fallbackFrame = frame(hide, 99f)))
        }
    }

    @Test
    fun shownHeightChangeAdoptsNewGeometryWithoutReplayingHideOrShow() {
        val motion = StatusBarInsetMotionController()
        motion.sample(100, 100)
        val hide = plan(motion.sample(0, 100))
        assertDirect(0, motion.sample(0, 140, fallbackFrame = frame(hide, 70f)))
        assertDirect(120, motion.sample(120, 120))
        assertDirect(0, motion.sample(0, 0))
        assertDirect(90, motion.sample(90, 90))
    }

    @Test
    fun metricsResetMidNativeTrajectoryStillFollowsGeometryAndEndpoint() {
        val motion = StatusBarInsetMotionController()
        motion.sample(100, 100, metricsKey = 0)
        motion.sample(60, 100, metricsKey = 0)
        assertDirect(25, motion.sample(25, 80, metricsKey = 1))
        assertDirect(0, motion.sample(0, 80, metricsKey = 1))
        assertDirect(0, motion.sample(0, 80, metricsKey = 1))
    }

    @Test
    fun reducedMotionSnapsEndpointChangesAndActiveFallbackToTarget() {
        val motion = StatusBarInsetMotionController()
        motion.sample(100, 100)
        val hide = plan(motion.sample(0, 100))
        assertTop(72f, motion.sample(0, 100, fallbackFrame = frame(hide, 72f)))
        assertDirect(0, motion.sample(0, 100, reducedMotion = true, fallbackFrame = frame(hide, 64f)))
        assertDirect(100, motion.sample(100, 100, reducedMotion = true))
        assertDirect(0, motion.sample(0, 100, reducedMotion = true))
        // Re-enabling motion without a new endpoint change does not replay anything.
        assertDirect(0, motion.sample(0, 100, fallbackFrame = frame(hide, 41f)))
    }

    @Test
    fun reducedMotionDoesNotInventPixelsDuringNativeTrajectory() {
        val motion = StatusBarInsetMotionController()
        for (top in listOf(100, 78, 51, 19, 0)) {
            assertDirect(top, motion.sample(top, 100, reducedMotion = true))
        }
    }

    @Test
    fun transientNativeRevealAndDismissalFollowObservedGeometry() {
        val motion = StatusBarInsetMotionController()
        for (top in listOf(0, 16, 53, 100, 100, 75, 22, 0)) {
            assertDirect(top, motion.sample(top, 100))
        }
    }

    @Test
    fun overlayOnlyTransientRevealWithZeroInsetsDoesNotReflowContent() {
        val motion = StatusBarInsetMotionController()

        repeat(20) { assertDirect(0, motion.sample(0, 100)) }
    }

    @Test
    fun requestedHideFollowsNativeMotionBeforeLatchingTransientOverlay() {
        val motion = StatusBarInsetMotionController()
        assertDirect(100, motion.sample(100, 100))
        for (top in listOf(100, 82, 35, 1, 0)) {
            assertDirect(top, motion.sample(top, 100, requestedHidden = true))
        }
        for (top in listOf(0, 17, 58, 100, 100, 61, 24, 0)) {
            assertDirect(0, motion.sample(top, 100, requestedHidden = true))
        }
    }

    @Test
    fun initialRequestedHiddenAttachmentDoesNotFakeAHideOrCutOffNativeMotion() {
        val shown = StatusBarInsetMotionController()
        assertDirect(100, shown.sample(100, 100, requestedHidden = true))
        assertDirect(71, shown.sample(71, 100, requestedHidden = true))
        assertDirect(0, shown.sample(0, 100, requestedHidden = true))
        assertDirect(0, shown.sample(40, 100, requestedHidden = true))

        val intermediate = StatusBarInsetMotionController()
        assertDirect(43, intermediate.sample(43, 100, requestedHidden = true))
        assertDirect(0, intermediate.sample(0, 100, requestedHidden = true))
        assertDirect(0, intermediate.sample(100, 100, requestedHidden = true))

        val hidden = StatusBarInsetMotionController()
        assertDirect(0, hidden.sample(0, 100, requestedHidden = true))
        assertDirect(0, hidden.sample(100, 100, requestedHidden = true))
    }

    @Test
    fun requestedHideWaitsForEndpointFallbackToFinishBeforeSuppressingReveals() {
        val motion = StatusBarInsetMotionController()
        motion.sample(100, 100, requestedHidden = true)
        val hide = plan(motion.sample(0, 100, requestedHidden = true))
        assertTop(76f, motion.sample(0, 100, requestedHidden = true, fallbackFrame = frame(hide, 76f)))

        assertDirect(51, motion.sample(51, 100, requestedHidden = true, fallbackFrame = frame(hide, 60f)))
        assertDirect(0, motion.sample(0, 100, requestedHidden = true))
        assertDirect(0, motion.sample(70, 100, requestedHidden = true))
    }

    @Test
    fun finishedEndpointFallbackLatchesRequestedHiddenState() {
        val motion = StatusBarInsetMotionController()
        motion.sample(100, 100)
        val hide = plan(motion.sample(0, 100, requestedHidden = true))
        assertDirect(0, motion.sample(0, 100, requestedHidden = true, fallbackFrame = frame(hide, 0f)))
        assertDirect(0, motion.sample(100, 100, requestedHidden = true, fallbackFrame = frame(hide, 83f)))
    }

    @Test
    fun repeatedLayoutReadsDoNotLatchOrAdvanceEndpointHideFallback() {
        val motion = StatusBarInsetMotionController()
        motion.sample(100, 100)
        val hide = plan(motion.sample(0, 100, requestedHidden = true))
        repeat(10) {
            val sample = motion.sample(0, 100, requestedHidden = true)
            assertTop(100f, sample)
            assertEquals(hide, sample.fallback)
        }
        repeat(10) {
            val sample = motion.sample(0, 100, requestedHidden = true, fallbackFrame = frame(hide, 37f))
            assertTop(37f, sample)
            assertEquals(hide, sample.fallback)
        }
        repeat(10) {
            assertDirect(0, motion.sample(0, 100, requestedHidden = true, fallbackFrame = frame(hide, 0f)))
        }
        assertDirect(0, motion.sample(100, 100, requestedHidden = true))
    }

    @Test
    fun repeatedReversalLayoutReadsKeepTheSameNewPlanAndOrigin() {
        val motion = StatusBarInsetMotionController()
        motion.sample(100, 100)
        val hide = plan(motion.sample(0, 100, requestedHidden = true))
        val reversed = motion.sample(100, 100, fallbackFrame = frame(hide, 44f))
        val show = plan(reversed)
        repeat(10) {
            val sample = motion.sample(100, 100, fallbackFrame = frame(hide, 44f))
            assertTop(44f, sample)
            assertEquals(show, sample.fallback)
        }
    }

    @Test
    fun hideRequestArrivingAtAlreadyHiddenGeometryLatchesWithoutATween() {
        val motion = StatusBarInsetMotionController()
        motion.sample(0, 100)
        assertDirect(0, motion.sample(0, 100, requestedHidden = true))
        assertDirect(0, motion.sample(37, 100, requestedHidden = true))
        assertDirect(0, motion.sample(100, 100, requestedHidden = true))
    }

    @Test
    fun explicitShowReleasesHiddenLatchAndUsesRealNativePixels() {
        val motion = StatusBarInsetMotionController()
        motion.sample(0, 100, requestedHidden = true)
        assertDirect(0, motion.sample(47, 100, requestedHidden = true))
        assertDirect(47, motion.sample(47, 100, requestedHidden = false))
        assertDirect(72, motion.sample(72, 100, requestedHidden = false))
        assertDirect(100, motion.sample(100, 100, requestedHidden = false))
    }

    @Test
    fun explicitShowWithEndpointOnlyInsetUsesFallbackFromLatchedZero() {
        val motion = StatusBarInsetMotionController()
        motion.sample(0, 100, requestedHidden = true)
        assertDirect(0, motion.sample(100, 100, requestedHidden = true))
        val show = plan(motion.sample(100, 100, requestedHidden = false))
        assertEquals(0f, show.sourceTopPx, 0f)
        assertEquals(100f, show.targetTopPx, 0f)
        assertDirect(100, motion.sample(100, 100, fallbackFrame = frame(show, 100f)))
    }

    @Test
    fun showRequestBeforeNativePixelsChangeDoesNotInventATransition() {
        val motion = StatusBarInsetMotionController()
        motion.sample(0, 100, requestedHidden = true)
        assertDirect(0, motion.sample(0, 100, requestedHidden = false))
        assertDirect(0, motion.sample(0, 100, requestedHidden = false))
        assertDirect(23, motion.sample(23, 100, requestedHidden = false))
        assertDirect(100, motion.sample(100, 100, requestedHidden = false))
    }

    @Test
    fun settledHiddenLatchSurvivesMetricChangesWhileRequestRemainsHidden() {
        val motion = StatusBarInsetMotionController()
        motion.sample(0, 100, metricsKey = "portrait@2", requestedHidden = true)
        assertDirect(0, motion.sample(80, 80, metricsKey = "landscape@3", requestedHidden = true))
        assertDirect(0, motion.sample(46, 80, metricsKey = "landscape@3", requestedHidden = true))
        assertDirect(46, motion.sample(46, 80, metricsKey = "landscape@3", requestedHidden = false))
    }

    @Test
    fun endpointOnlyTransientRevealCanReverseBeforeShowFinishes() {
        val motion = StatusBarInsetMotionController()
        motion.sample(0, 100)
        val show = plan(motion.sample(100, 100))
        val sample = motion.sample(0, 100, fallbackFrame = frame(show, 32f))
        assertTop(32f, sample)
        val hide = plan(sample)
        assertEquals(32f, hide.sourceTopPx, 0f)
        assertEquals(0f, hide.targetTopPx, 0f)
        assertDirect(0, motion.sample(0, 100, fallbackFrame = frame(hide, 0f)))
    }

    @Test
    fun malformedNativeInsetsAreClampedBeforeTrajectoryDetection() {
        val motion = StatusBarInsetMotionController()
        assertDirect(0, motion.sample(-50, 100))
        assertDirect(100, motion.sample(Int.MAX_VALUE, 100, reducedMotion = true))
        assertDirect(0, motion.sample(Int.MIN_VALUE, 100, reducedMotion = true))
        assertDirect(0, motion.sample(100, -10))
        assertDirect(0, motion.sample(Int.MAX_VALUE, 0))
    }

    @Test
    fun nonFiniteFallbackFramesRetainLastRenderedPosition() {
        val motion = StatusBarInsetMotionController()
        motion.sample(100, 100)
        val hide = plan(motion.sample(0, 100))
        motion.sample(0, 100, fallbackFrame = frame(hide, 59f))
        for (bad in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
            val sample = motion.sample(0, 100, fallbackFrame = frame(hide, bad))
            assertTop(59f, sample)
            assertEquals(hide, sample.fallback)
        }
    }

    @Test
    fun fallbackFramesAreClampedToTheirPlanSegment() {
        val motion = StatusBarInsetMotionController()
        motion.sample(100, 100)
        val hide = plan(motion.sample(0, 100))
        val show = plan(motion.sample(100, 100, fallbackFrame = frame(hide, 40f)))
        assertTop(40f, motion.sample(100, 100, fallbackFrame = frame(show, -80f)))
        assertTop(40f, motion.sample(100, 100, fallbackFrame = frame(show, 20f)))
        assertDirect(100, motion.sample(100, 100, fallbackFrame = frame(show, 1000f)))
    }

    @Test
    fun staleAnimationFramesCannotMoveANewerPlanEvenAfterMetricReset() {
        val motion = StatusBarInsetMotionController()
        motion.sample(0, 100, metricsKey = "a")
        val old = plan(motion.sample(100, 100, metricsKey = "a"))
        assertDirect(0, motion.sample(0, 100, metricsKey = "b"))
        val fresh = plan(motion.sample(100, 100, metricsKey = "b"))
        assertNotEquals(old.generation, fresh.generation)
        assertTop(0f, motion.sample(100, 100, metricsKey = "b", fallbackFrame = frame(old, 100f)))
        assertTop(53f, motion.sample(100, 100, metricsKey = "b", fallbackFrame = frame(fresh, 53f)))
    }

    @Test
    fun cutoutFloorIsIndependentMaxRatherThanAnAnimatedContribution() {
        val motion = StatusBarInsetMotionController()
        val cutoutTop = 30f
        val expectedRootTop = listOf(100f, 64f, 30f, 30f)
        for ((index, nativeTop) in listOf(100, 64, 20, 0).withIndex()) {
            val statusTop = motion.sample(nativeTop, 100).topPx
            assertEquals(expectedRootTop[index], maxOf(cutoutTop, statusTop), 0f)
        }
    }
    @Test
    fun editorRequestStartsBeforeAnyNativeInsetOrVisibilityCallback() {
        val motion = StatusBarInsetMotionController()
        motion.sample(100, 100)
        val hide = plan(motion.sample(100, 100, requestedHidden = true, anticipateHiddenRequest = true))
        assertEquals(0f, hide.targetTopPx, 0f)
        assertTop(64f, motion.sample(100, 100, requestedHidden = true,
            anticipateHiddenRequest = true, fallbackFrame = frame(hide, 64f)))
        assertDirect(0, motion.sample(100, 100, requestedHidden = true,
            anticipateHiddenRequest = true, fallbackFrame = frame(hide, 0f)))
        for (native in listOf(100, 71, 29, 0, 61, 100)) {
            assertDirect(0, motion.sample(native, 100, requestedHidden = true, anticipateHiddenRequest = true))
        }
    }

    @Test
    fun editorRequestUsesOneClockEvenWhenNativeInsetsArriveLate() {
        val motion = StatusBarInsetMotionController()
        motion.sample(100, 100)
        val hide = plan(motion.sample(100, 100, requestedHidden = true, anticipateHiddenRequest = true))
        assertTop(62f, motion.sample(82, 100, requestedHidden = true,
            anticipateHiddenRequest = true, fallbackFrame = frame(hide, 62f)))
        assertTop(62f, motion.sample(35, 100, requestedHidden = true, anticipateHiddenRequest = true))
        assertDirect(0, motion.sample(35, 100, requestedHidden = true,
            anticipateHiddenRequest = true, fallbackFrame = frame(hide, 0f)))
    }

    @Test
    fun editorRequestReversalStartsAtTheLastRenderedFrameAndIgnoresStaleFrames() {
        val motion = StatusBarInsetMotionController()
        motion.sample(100, 100)
        val hide = plan(motion.sample(100, 100, requestedHidden = true, anticipateHiddenRequest = true))
        assertTop(42f, motion.sample(100, 100, requestedHidden = true,
            anticipateHiddenRequest = true, fallbackFrame = frame(hide, 42f)))
        val reversed = motion.sample(0, 100, fallbackFrame = frame(hide, 10f))
        val show = plan(reversed)
        assertEquals(42f, show.sourceTopPx, 0f)
        assertEquals(100f, show.targetTopPx, 0f)
        assertTop(42f, motion.sample(0, 100, fallbackFrame = frame(hide, 10f)))
        assertDirect(100, motion.sample(0, 100, fallbackFrame = frame(show, 100f)))
        assertDirect(100, motion.sample(48, 100))
        assertDirect(100, motion.sample(100, 100))
    }

    @Test
    fun restoredEditorReducedMotionAndRotationNeverReserveAHiddenStatusStrip() {
        val restored = StatusBarInsetMotionController()
        assertDirect(0, restored.sample(100, 100, requestedHidden = true, anticipateHiddenRequest = true))
        assertDirect(0, restored.sample(80, 80, metricsKey = "rotated", requestedHidden = true))
        val reduced = StatusBarInsetMotionController()
        reduced.sample(100, 100)
        assertDirect(0, reduced.sample(100, 100, requestedHidden = true,
            anticipateHiddenRequest = true, reducedMotion = true))
    }

}
