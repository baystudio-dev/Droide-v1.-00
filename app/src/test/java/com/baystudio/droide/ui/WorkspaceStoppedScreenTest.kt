package com.baystudio.droide.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], application = Application::class, qualifiers = "w840dp-h840dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkspaceStoppedScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun stoppedStateHasOneExplicitActionAndDoesNotOpenByItself() {
        var opens = 0
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            DroideTheme { Box(Modifier.size(393.dp, 800.dp).testTag("stopped")) {
                WorkspaceStoppedScreen(canOpen = true, failure = null, onOpen = { opens++ })
            } }
        }
        compose.onNodeWithText("Session stopped").assertIsDisplayed()
        compose.onNodeWithText("Ready when you are").assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        compose.mainClock.advanceTimeBy(5_000)
        compose.runOnIdle { assertEquals(0, opens) }

        val button = compose.onNodeWithText("Open Droide").assertIsDisplayed().assertIsEnabled()
        val label = compose.onNodeWithText("Open Droide", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(button.fetchSemanticsNode().boundsInRoot.center.x, label.center.x, 1f)
        System.getProperty("droide.stopped.screenshot")?.let { destination ->
            val bounds = compose.onNodeWithTag("stopped").fetchSemanticsNode().boundsInRoot
            compose.runOnIdle {
                val bitmap = Bitmap.createBitmap(bounds.width.roundToInt(), bounds.height.roundToInt(), Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    canvas.translate(-bounds.left, -bounds.top)
                    view.draw(canvas)
                    File(destination).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { bitmap.recycle() }
            }
        }
        button.performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test fun disabledActionDoesNotInvokeOwnerUntilEnabled() {
        var canOpen by mutableStateOf(false)
        var opens = 0
        compose.setContent { DroideTheme { Box(Modifier.size(393.dp, 800.dp)) {
            WorkspaceStoppedScreen(canOpen = canOpen, failure = null, onOpen = { opens++ })
        } } }
        compose.onNodeWithText("Open Droide").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, opens); canOpen = true }
        compose.onNodeWithText("Open Droide").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test fun failedOpenReplacesReassuranceAndKeepsRetryReachable() {
        var failure by mutableStateOf<String?>(null)
        var opens = 0
        val reason = "Could not open Droide. " + "Saved work state is unavailable. ".repeat(8)
        compose.setContent { DroideTheme { Box(Modifier.size(320.dp, 280.dp)) {
            WorkspaceStoppedScreen(canOpen = true, failure = failure, onOpen = { opens++ })
        } } }
        compose.runOnIdle { failure = reason }
        compose.onNodeWithText("Your files and recovery drafts", substring = true).assertDoesNotExist()
        compose.onNodeWithText(reason).performScrollTo().assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, reason))
        compose.onNodeWithText("Open Droide").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, opens) }
        compose.onNodeWithText("Your workspace, anywhere.").assertIsDisplayed()
    }

    @Test fun largeTextAndRtlKeepActionAboveFooterInShortAndLandscapeWindows() {
        var dimensions by mutableStateOf(320.dp to 280.dp)
        compose.setContent { CompositionLocalProvider(
            LocalDensity provides Density(1f, fontScale = 2f),
            LocalLayoutDirection provides LayoutDirection.Rtl,
        ) { DroideTheme { Box(Modifier.size(dimensions.first, dimensions.second)) {
            WorkspaceStoppedScreen(canOpen = true, failure = null, onOpen = {})
        } } } }
        fun assertReachableAction() {
            val button = compose.onNodeWithText("Open Droide").performScrollTo().assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            val footer = compose.onNodeWithText("Your workspace, anywhere.").assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            assertTrue(button.height >= 48f)
            assertTrue(button.bottom <= footer.top)
        }
        assertReachableAction()
        compose.runOnIdle { dimensions = 640.dp to 360.dp }
        assertReachableAction()
    }
}
