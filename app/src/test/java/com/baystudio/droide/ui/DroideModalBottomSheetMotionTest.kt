package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
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
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class, ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h720dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DroideModalBottomSheetMotionTest {
    private val scale = mutableStateOf(1f)
    @get:Rule val compose = createComposeRule(effectContext = object : MotionDurationScale {
        override val scaleFactor get() = scale.value
    })
    private val visible = mutableStateOf(true)
    private val managed = mutableStateOf(true)
    private var closes = 0

    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent { DroideTheme {
            Text("Workspace")
            if (visible.value) {
                val close = { closes++; visible.value = false }
                if (managed.value) DroideModalBottomSheet(onDismissRequest = close) { dismiss ->
                    Column(Modifier.fillMaxWidth().height(120.dp).testTag("body")) {
                        Text("Panel"); Button(onClick = dismiss) { Text("Close") }
                    }
                } else ModalBottomSheet(onDismissRequest = close) {
                    Column(Modifier.fillMaxWidth().height(120.dp).testTag("body")) {
                        Text("Panel"); Button(onClick = close) { Text("Close") }
                    }
                }
            }
        } }
        compose.mainClock.advanceTimeBy(1_000)
    }

    @Test fun closeButtonMovesExistingSurfaceOutBeforeUnmounting() {
        mount()
        val before = compose.onNodeWithTag("body").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("Close").performClick()
        assertEquals(0, closes)
        compose.mainClock.advanceTimeBy(48)
        val during = compose.onNodeWithTag("body").fetchSemanticsNode().boundsInRoot
        assertTrue("Sheet should move down during exit: $before -> $during", during.top > before.top)
        assertEquals(before.width, during.width, .01f)
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("body").assertDoesNotExist()
        assertEquals(1, closes)
    }

    @Test fun managedSheetKeepsTheStockMaterialOpenGeometry() {
        managed.value = false; mount()
        val stock = compose.onNodeWithTag("body").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { managed.value = true }
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(stock, compose.onNodeWithTag("body").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun systemDisabledMotionHasNoAddedCloseDelay() {
        scale.value = 0f; mount()
        compose.onNodeWithText("Close").performClick()
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithTag("body").assertDoesNotExist()
        assertEquals(1, closes)
    }
}
