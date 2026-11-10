package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Authored UI contract. Requires the restored Android/Compose test toolchain before execution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RetainedWorkspaceContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun hiddenWorkspaceKeepsCompositionButHasNoClickableSemantics() {
        var presented by mutableStateOf(true)
        var created = 0
        var disposed = 0
        var clicks = 0
        val observedPresentation = mutableListOf<Boolean>()
        compose.setContent {
            RetainedWorkspaceContent(presented) {
                val token = remember { Any().also { created++ } }
                observedPresentation += LocalWorkspacePresented.current
                DisposableEffect(token) { onDispose { disposed++ } }
                Box(Modifier.fillMaxSize().testTag("normal-workspace-action").clickable { clicks++ }) {
                    Text("Normal workspace")
                }
            }
        }
        compose.onNodeWithTag("normal-workspace-action").performClick()
        repeat(5) {
            compose.runOnIdle { presented = false }
            compose.onNodeWithTag("normal-workspace-action").assertDoesNotExist()
            compose.runOnIdle { assertEquals(1, created); assertEquals(0, disposed); presented = true }
            compose.onNodeWithTag("normal-workspace-action").assertIsDisplayed()
        }
        compose.runOnIdle {
            assertEquals(1, clicks)
            assertEquals(1, created)
            assertEquals(0, disposed)
            assertTrue(observedPresentation.contains(false))
        }
    }
}
