package com.baystudio.droide.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentMotionBaselineTest {
    @get:Rule val compose = createComposeRule()
    private val tool = AgentStreamPart("t", 1, AgentStreamPartKind.TOOL, AgentStreamPartState.COMPLETED,
        title = "read file", toolName = "read_file", input = "{}", output = "One\nTwo\nThree\nFour", detail = "Succeeded")
    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent { DroideTheme { Box(Modifier.width(300.dp)) { AgentStreamPartItem(tool, false, Modifier.testTag("tool")) } } }
    }
    private fun height() = compose.onNodeWithTag("tool").fetchSemanticsNode().boundsInRoot.height

    @Test fun expansionGrowsAcrossFrames() {
        mount()
        val collapsed = height()
        compose.onNodeWithContentDescription("Expand").performClick()
        compose.mainClock.advanceTimeBy(32)
        val first = height()
        compose.mainClock.advanceTimeBy(64)
        val middle = height()
        compose.mainClock.advanceTimeBy(240)
        val settled = height()
        assertTrue("Expansion must not jump straight to full height", first < settled)
        assertTrue(first >= collapsed)
        assertTrue(middle > first)
        assertTrue(settled >= middle)
    }

    @Test fun collapseRetainsOnlyVisualExitUntilSettled() {
        mount()
        val collapsed = height()
        compose.onNodeWithContentDescription("Expand").performClick(); compose.mainClock.advanceTimeBy(250)
        val expanded = height()
        compose.onNodeWithContentDescription("Collapse").performClick(); compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText("CODE").assertDoesNotExist()
        val first = height()
        compose.mainClock.advanceTimeBy(64)
        val middle = height()
        compose.mainClock.advanceTimeBy(250)
        assertTrue("Collapse should retain space on its initial frame", first > collapsed && first <= expanded)
        assertTrue("Collapse should release the occupied space smoothly", middle > collapsed && middle < first)
        assertEquals(collapsed, height(), 1f)
    }

    @Test fun statusChangeFadesOnlyCurrentContent() {
        compose.mainClock.autoAdvance = false
        var kind by mutableStateOf(AgentActivityKind.WORKING)
        lateinit var view: View
        compose.setContent { view = LocalView.current; DroideTheme {
            Box(Modifier.width(280.dp).height(48.dp).background(Color.Black)) { AgentActivityStatus(AgentActivityPresentation(kind)) }
        } }
        compose.runOnIdle { kind = AgentActivityKind.THINKING }; compose.mainClock.advanceTimeByFrame()
        fun ink(): Long {
            val b = compose.onNodeWithText("Thinking…").fetchSemanticsNode().boundsInRoot
            return compose.runOnIdle {
                val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    view.draw(Canvas(image))
                    var sum = 0L
                    for (y in b.top.toInt() until b.bottom.toInt()) for (x in b.left.toInt() until b.right.toInt()) {
                        val pixel = image.getPixel(x, y)
                        sum += android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel)
                    }
                    sum
                } finally { image.recycle() }
            }
        }
        val entering = ink()
        compose.mainClock.advanceTimeBy(240)
        val settled = ink()
        assertTrue("Only the current label should ease into its final appearance", entering > 0 && entering < settled * .98)
        compose.onNodeWithText("Working…").assertDoesNotExist()
    }
}
