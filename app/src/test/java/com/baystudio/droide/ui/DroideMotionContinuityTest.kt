package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Host Compose continuity/counter checks, not device frame-time benchmarks. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DroideMotionContinuityTest {
    private val scale = mutableStateOf(1f)
    @get:Rule val compose = createComposeRule(effectContext = object : MotionDurationScale {
        override val scaleFactor get() = scale.value
    })
    private val page = mutableStateOf("Files")
    private val enabled = mutableStateOf(true)
    private var compositions = 0
    private var mounts = 0
    private var live = 0

    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.size(240.dp, 160.dp).testTag("parent")) {
                Box(Modifier.size(240.dp, 160.dp).droideContentMotion(page.value, enabled.value).testTag("surface")) {
                    SideEffect { compositions++ }
                    DisposableEffect(Unit) { mounts++; live++; onDispose { live-- } }
                    Text(page.value)
                }
            }
        }
        compose.mainClock.advanceTimeByFrame(); compose.runOnIdle { }; compose.mainClock.advanceTimeByFrame()
    }

    private fun top() = compose.onNodeWithTag("surface").fetchSemanticsNode().boundsInRoot.top
    private fun finalTop() = compose.onNodeWithTag("parent").fetchSemanticsNode().boundsInRoot.top

    @Test fun rapidNavigationContinuesTheInFlightTrajectoryInsteadOfJumpingBack() {
        mount()
        compose.mainClock.advanceTimeBy(64)
        val offsets = mutableListOf(top())
        for (next in listOf("Terminal", "Editor", "Settings")) {
            compose.runOnIdle { page.value = next }
            compose.mainClock.advanceTimeByFrame(); compose.runOnIdle { }; compose.mainClock.advanceTimeByFrame()
            offsets += top()
            compose.onNodeWithText(next).assertIsDisplayed()
        }
        assertTrue("Rapid switches must not restart the displacement: $offsets",
            offsets.zipWithNext().all { (before, after) -> after <= before + .01f })
        compose.mainClock.advanceTimeBy(240)
        assertEquals(finalTop(), top(), .01f)
        assertEquals(1, mounts)
        assertEquals(1, live)
    }

    @Test fun rapidSwitchesDoNotExtendTheOriginalEntranceDeadline() {
        mount()
        repeat(5) { index ->
            compose.runOnIdle { page.value = "Page $index" }
            compose.mainClock.advanceTimeByFrame(); compose.runOnIdle { }; compose.mainClock.advanceTimeByFrame()
        }
        compose.mainClock.advanceTimeBy(64)
        assertEquals("The animation must settle even while navigation changes", finalTop(), top(), .01f)
    }

    @Test fun aNewNavigationAfterSettlingStillGetsItsNormalEntrance() {
        mount()
        compose.mainClock.advanceTimeBy(240)
        assertEquals(finalTop(), top(), .01f)
        compose.runOnIdle { page.value = "Terminal" }
        compose.mainClock.advanceTimeByFrame(); compose.runOnIdle { }; compose.mainClock.advanceTimeByFrame()
        assertTrue(top() > finalTop())
        compose.mainClock.advanceTimeBy(240)
        assertEquals(finalTop(), top(), .01f)
    }

    @Test fun animationTicksDoNotRecomposeContentOrRemountItsSurface() {
        mount()
        val before = compositions
        compose.mainClock.advanceTimeBy(96)
        assertEquals(before, compositions)
        assertEquals(1, mounts)
    }

    @Test fun disablingMidFlightSnapsAndReenablingDoesNotReplayConsumedNavigation() {
        mount()
        compose.mainClock.advanceTimeBy(48)
        compose.runOnIdle { enabled.value = false }
        compose.mainClock.advanceTimeByFrame(); compose.runOnIdle { }; compose.mainClock.advanceTimeByFrame()
        assertEquals(finalTop(), top(), .01f)
        compose.runOnIdle { page.value = "Editor" }
        compose.mainClock.advanceTimeByFrame(); compose.runOnIdle { }; compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { enabled.value = true }
        compose.mainClock.advanceTimeByFrame(); compose.runOnIdle { }; compose.mainClock.advanceTimeByFrame()
        assertEquals(finalTop(), top(), .01f)
        compose.onNodeWithText("Editor").assertIsDisplayed()
    }

    @Test fun systemReducedMotionDuringRapidNavigationSnapsWithoutReplay() {
        mount()
        compose.runOnIdle { scale.value = 0f; page.value = "Editor" }
        compose.mainClock.advanceTimeByFrame(); compose.runOnIdle { }; compose.mainClock.advanceTimeByFrame()
        assertEquals(finalTop(), top(), .01f)
        compose.runOnIdle { scale.value = 1f }
        compose.mainClock.advanceTimeByFrame(); compose.runOnIdle { }; compose.mainClock.advanceTimeByFrame()
        assertEquals(finalTop(), top(), .01f)
    }
}
