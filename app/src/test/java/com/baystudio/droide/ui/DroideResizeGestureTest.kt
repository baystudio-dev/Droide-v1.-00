package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w800dp-h600dp-mdpi")
class DroideResizeGestureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun bottomPanelDragContinuesAcrossRecompositionsAndUsesUpdatedBounds() {
        val height = mutableStateOf(200.dp)
        val max = mutableStateOf(360.dp)
        compose.setContent { DroideTheme { Box(Modifier.size(700.dp, 500.dp)) {
            DroideBottomPanelFrame(DroideBottomTool.Terminal, height.value, max.value,
                { height.value = it }, {}, {}) { Text("Terminal session") }
        } } }
        val handle = compose.onNodeWithContentDescription("Resize bottom panel")
        handle.performTouchInput { down(center); moveBy(Offset(0f, -40f)) }
        compose.waitForIdle()
        val first = height.value
        assertTrue(first > 200.dp)
        handle.performTouchInput { moveBy(Offset(0f, -30f)) }
        compose.waitForIdle()
        assertEquals("Same pointer must keep dragging after the height changed", first.value + 30f, height.value.value, .01f)
        compose.runOnIdle { max.value = height.value + 5.dp }
        handle.performTouchInput { moveBy(Offset(0f, -80f)); up() }
        compose.waitForIdle()
        assertEquals(max.value, height.value)
        compose.onNodeWithText("Terminal session").assertExists()
    }

    @Test fun agentPanelDragFollowsTheFingerWithoutRestartingOrAccumulatingStaleWidths() {
        val width = mutableStateOf(300.dp)
        compose.setContent { DroideTheme { Box(Modifier.size(700.dp, 500.dp)) {
            DroideResizableAgentPanel(width.value, 264.dp, 500.dp, { width.value = it }) { Text("Active Agent") }
        } } }
        val handle = compose.onNodeWithContentDescription("Resize agent panel")
        handle.performTouchInput { down(center); moveBy(Offset(-40f, 0f)) }
        compose.waitForIdle()
        val first = width.value
        assertTrue(first > 300.dp)
        handle.performTouchInput { moveBy(Offset(-30f, 0f)) }
        compose.waitForIdle()
        assertEquals(first.value + 30f, width.value.value, .01f)
        handle.performTouchInput { moveBy(Offset(400f, 0f)); up() }
        compose.waitForIdle()
        assertEquals(264.dp, width.value)
    }

    @Test fun resizeCanUseAccessibilityAndKeyboardActionsWithoutDragging() {
        val width = mutableStateOf(300.dp)
        compose.setContent { DroideTheme { Box(Modifier.size(700.dp, 500.dp)) {
            DroideResizableAgentPanel(width.value, 264.dp, 500.dp, { width.value = it }) { Text("Active Agent") }
        } } }
        val handle = compose.onNodeWithContentDescription("Resize agent panel")
        handle.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(360f)) }
        compose.waitForIdle()
        assertEquals(360.dp, width.value)
        handle.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        handle.performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
        assertEquals(376.dp, width.value)
        handle.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(900f)); assertFalse(it(Float.NaN)) }
        compose.waitForIdle()
        assertEquals(500.dp, width.value)
    }
}
