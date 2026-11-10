package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w640dp-h640dp-mdpi")
class DroideNavigationMotionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rapidTabTapsSelectImmediatelyAndKeepReadableTouchTargets() {
        compose.mainClock.autoAdvance = false
        val selected = mutableStateOf(0)
        val size = mutableStateOf(360.dp to 640.dp)
        compose.setContent { DroideTheme { Box(Modifier.size(size.value.first, size.value.second)) {
            DroideCompactNavigation(selected.value) { selected.value = it }
        } } }
        for ((label, id) in listOf("Terminal" to 2, "Git" to 3, "Files" to 0, "Extensions" to 6)) {
            compose.onNodeWithText(label).performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText(label).assertIsSelected()
            assertTrue(compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.height >= 58f)
            assertTrue(selected.value == id)
        }
        compose.runOnIdle { size.value = 640.dp to 360.dp }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Extensions").assertIsSelected()
        compose.onAllNodes(hasText("Extensions")).assertCountEquals(1)
    }

    @Test fun largeSystemTextGetsAdditionalNavigationHeightInsteadOfClipping() {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 3f)) {
            DroideTheme { Box(Modifier.size(640.dp, 360.dp)) { DroideCompactNavigation(2) {} } }
        } }
        compose.onNodeWithText("Terminal").assertIsSelected().assertIsDisplayed()
        assertTrue(compose.onNodeWithText("Terminal").fetchSemanticsNode().boundsInRoot.height > 58f)
    }
}
