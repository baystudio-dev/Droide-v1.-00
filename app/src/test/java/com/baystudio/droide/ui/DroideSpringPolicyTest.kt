package com.baystudio.droide.ui

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import org.junit.Assert.*
import org.junit.Test

/** Physics checks at normal duration scale; not GPU/frame-time measurements. */
class DroideSpringPolicyTest {
    private fun verify(spec: SpringSpec<Float>, from: Float, to: Float) {
        val animation = TargetBasedAnimation(spec, Float.VectorConverter, from, to)
        assertTrue("Entrance must settle promptly", animation.durationNanos in 1..300_000_000L)
        var previous = from
        var time = 0L
        while (time <= animation.durationNanos + 16_000_000L) {
            val value = animation.getValueFromNanos(time)
            assertTrue("No text/alpha overshoot: $value", value in 0f..1f)
            assertTrue("Monotone trajectory", if (to > from) value >= previous else value <= previous)
            previous = value
            time += 4_000_000L
        }
        assertEquals(to, animation.getValueFromNanos(animation.durationNanos), 0f)
    }
    @Test fun workbenchEntranceIsFiniteAndDoesNotBounce() = verify(DroideMotion.ContentSpring, 0f, 1f)
    @Test fun activityEntranceIsFiniteAndDoesNotBounce() = verify(DroideMotion.ActivitySpring, 0f, 1f)
    @Test fun activityDisclosureClosingIsFiniteAndDoesNotBounce() = verify(DroideMotion.ActivitySpring, 1f, 0f)
}
