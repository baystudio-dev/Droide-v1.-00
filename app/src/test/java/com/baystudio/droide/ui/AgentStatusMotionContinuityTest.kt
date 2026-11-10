package com.baystudio.droide.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentStatusMotionContinuityTest {
    private val scale = mutableStateOf(1f)
    @get:Rule val compose = createComposeRule(effectContext = object : MotionDurationScale {
        override val scaleFactor get() = scale.value
    })
    private val kind = mutableStateOf(AgentActivityKind.WORKING)
    private lateinit var view: View

    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            view = LocalView.current
            DroideTheme { Box(Modifier.width(300.dp).background(Color.Black)) {
                AgentActivityStatus(AgentActivityPresentation(kind.value, "Stable detail"))
            } }
        }
        compose.mainClock.advanceTimeBy(32)
    }

    // Sample the unchanged detail glyphs, not the rotating spinner or new status label.
    private fun ink(): Int {
        val bounds = compose.onNodeWithText("Stable detail").fetchSemanticsNode().boundsInRoot
        return compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                var sum = 0
                for (y in bounds.top.toInt() until bounds.bottom.toInt())
                    for (x in bounds.left.toInt() until bounds.right.toInt())
                        sum += android.graphics.Color.red(bitmap.getPixel(x, y))
                sum
            } finally { bitmap.recycle() }
        }
    }

    @Test fun rapidPhaseUpdatesDoNotRestartTheFadeOrDelayTheLatestLabel() {
        mount()
        val settled = ink()
        compose.runOnIdle { kind.value = AgentActivityKind.THINKING }
        compose.mainClock.advanceTimeBy(80)
        val progressing = ink()
        assertTrue(progressing < settled)
        val samples = mutableListOf(progressing)
        for (next in listOf(AgentActivityKind.PREPARING, AgentActivityKind.READING, AgentActivityKind.RESPONDING)) {
            compose.runOnIdle { kind.value = next }
            compose.mainClock.advanceTimeBy(32)
            compose.onNodeWithText(next.label).assertExists()
            samples += ink()
        }
        assertTrue("A burst must not flash back to its starting opacity: $samples",
            samples.zipWithNext().all { (a, b) -> b >= a - 10 })
        assertEquals("Updates must not extend the first fade", settled.toDouble(), samples.last().toDouble(), 10.0)
    }

    @Test fun terminalStateAndReducedMotionStayImmediateWithoutReplay() {
        mount()
        val settled = ink()
        compose.runOnIdle { kind.value = AgentActivityKind.THINKING }
        compose.mainClock.advanceTimeBy(48)
        compose.runOnIdle { kind.value = AgentActivityKind.STOPPED }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText("Stopped").assertExists()
        assertEquals(settled.toDouble(), ink().toDouble(), 10.0)
        compose.runOnIdle { kind.value = AgentActivityKind.READING; scale.value = 0f }
        compose.mainClock.advanceTimeByFrame(); compose.runOnIdle { }; compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(AgentActivityKind.READING.label).assertExists()
        assertEquals(settled.toDouble(), ink().toDouble(), 10.0)
        compose.runOnIdle { scale.value = 1f }
        compose.mainClock.advanceTimeByFrame(); compose.runOnIdle { }; compose.mainClock.advanceTimeByFrame()
        assertEquals(settled.toDouble(), ink().toDouble(), 10.0)
    }

    @Test fun statusMotionDoesNotChangeDetailGeometry() {
        mount()
        val before = compose.onNodeWithText("Stable detail").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { kind.value = AgentActivityKind.THINKING }
        repeat(8) {
            compose.mainClock.advanceTimeByFrame()
            assertEquals(before, compose.onNodeWithText("Stable detail").fetchSemanticsNode().boundsInRoot)
        }
    }
}
