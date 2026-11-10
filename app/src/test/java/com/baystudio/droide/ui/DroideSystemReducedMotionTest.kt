package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DroideSystemReducedMotionTest {
    @get:Rule val compose = createComposeRule(effectContext = object : MotionDurationScale { override val scaleFactor = 0f })

    @Test fun androidMotionDurationScaleZeroHasNoInitialTranslationOrDelayedControls() {
        compose.mainClock.autoAdvance = false
        compose.setContent { Box(Modifier.size(240.dp, 160.dp).testTag("layout")) {
            Box(Modifier.size(240.dp, 160.dp).droideContentMotion("settings").testTag("surface")) { Text("Settings") }
        } }
        assertEquals(compose.onNodeWithTag("layout").fetchSemanticsNode().boundsInRoot,
            compose.onNodeWithTag("surface").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("Settings").assertIsDisplayed()
    }
}
