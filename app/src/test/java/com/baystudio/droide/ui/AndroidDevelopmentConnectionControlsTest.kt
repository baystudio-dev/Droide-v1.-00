package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidDevelopmentConnectionControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sameConnectControlBecomesEnabledCancelEvenWhileOtherControlsAreBusy() {
        val connecting = mutableStateOf(false)
        var starts = 0
        var cancels = 0
        compose.setContent {
            MaterialTheme {
                DeviceBridgeConnectButton(connecting.value, !connecting.value,
                    onConnect = { starts++; connecting.value = true },
                    onCancel = { cancels++; connecting.value = false })
            }
        }
        compose.onNodeWithText("Connect").performClick()
        compose.onNodeWithText("Cancel").assertIsEnabled().performClick()
        compose.onNodeWithText("Connect").assertIsEnabled()
        assertEquals(1, starts)
        assertEquals(1, cancels)
    }

    @Test fun cancelCanBeRetriedWithoutTriggeringAnotherConnect() {
        var starts = 0
        var cancels = 0
        compose.setContent {
            MaterialTheme {
                DeviceBridgeConnectButton(true, false, onConnect = { starts++ }, onCancel = { cancels++ })
            }
        }
        compose.onNodeWithText("Cancel").performClick().performClick()
        assertEquals(0, starts)
        assertEquals(2, cancels)
    }
}
