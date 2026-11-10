package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Structural performance invariant, not a RAM/GPU benchmark. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AgentActivitySpringRenderingTest {
    @get:Rule val compose = createComposeRule()
    @Test fun activitySpringDoesNotRecomposeOrRemountContentOnEachFrame() {
        compose.mainClock.autoAdvance = false
        val policy = AgentActivityEntrancePolicy(emptyList())
        var compositions = 0
        var mounts = 0
        var live = 0
        compose.setContent {
            Box(Modifier.size(80.dp).agentActivityEntrance("tool:1:0", policy, true)) {
                SideEffect { compositions++ }
                DisposableEffect(Unit) { mounts++; live++; onDispose { live-- } }
                Text("Working")
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { }
        val initial = compositions
        repeat(20) { compose.mainClock.advanceTimeByFrame(); compose.runOnIdle { } }
        assertEquals(initial, compositions)
        assertEquals(1, mounts)
        assertEquals(1, live)
    }
}
