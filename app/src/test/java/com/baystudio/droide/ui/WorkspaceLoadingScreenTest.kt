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
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.WorkspaceStartupPhase
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

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], application = Application::class, qualifiers = "w840dp-h840dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkspaceLoadingScreenTest {
    private val motionScale = object : MotionDurationScale {
        var factor by mutableStateOf(1f)
        override val scaleFactor get() = factor
    }
    @get:Rule val compose = createComposeRule(effectContext = motionScale)
    private val progress = SemanticsMatcher.expectValue(
        SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate,
    )

    @Test fun phaseChangesKeepProgressIndeterminateAndLayoutStable() {
        // Keep the real infinite animation running under a controlled clock; auto-idling cancels
        // infinite animations in Compose tests and captures only the empty track.
        compose.mainClock.autoAdvance = false
        var phase by mutableStateOf(WorkspaceStartupPhase.RUNTIME_REGISTRIES)
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            DroideTheme { Box(Modifier.size(393.dp, 800.dp).testTag("welcome")) {
                WorkspaceLoadingScreen(phase)
            } }
        }
        compose.onNodeWithText("Welcome to Droide").assertIsDisplayed()
        val before = compose.onNode(progress).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { phase = WorkspaceStartupPhase.ACTIVE_RUNTIME }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Opening your workspace").assertIsDisplayed()
        assertEquals(before, compose.onNode(progress).fetchSemanticsNode().boundsInRoot)
        val footer = compose.onNodeWithText("Your workspace, anywhere.").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue(before.bottom < footer.top)
        // Optional host-only artifact; no screenshot/device behavior is added to the app.
        System.getProperty("droide.welcome.screenshot")?.let { destination ->
            compose.mainClock.advanceTimeBy(500)
            val bounds = compose.onNodeWithTag("welcome").fetchSemanticsNode().boundsInRoot
            compose.runOnIdle {
                // Draw the real Compose view with Robolectric's native renderer. PixelCopy waits on a device
                // window redraw that the host runner cannot dispatch.
                val bitmap = Bitmap.createBitmap(bounds.width.roundToInt(), bounds.height.roundToInt(), Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    canvas.translate(-bounds.left, -bounds.top)
                    view.draw(canvas)
                    File(destination).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { bitmap.recycle() }
            }
        }
    }

    @Test fun narrowShortWindowAndLargeTextCanReachStatusWithoutCoveringFooter() {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
            DroideTheme { Box(Modifier.size(320.dp, 280.dp)) {
                WorkspaceLoadingScreen(WorkspaceStartupPhase.FALLBACK_RUNTIME)
            } }
        } }
        compose.onNodeWithText("Opening the recovery workspace").performScrollTo().assertIsDisplayed()
        val status = compose.onNodeWithText("Opening the recovery workspace").fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithText("Your workspace, anywhere.").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue(status.bottom <= footer.top)
        compose.onNodeWithText("Welcome to Droide").performScrollTo().assertIsDisplayed()
    }

    @Test fun landscapeAndRtlKeepProgressAndStatusInsideScreen() {
        compose.setContent { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            DroideTheme { Box(Modifier.size(640.dp, 360.dp).testTag("welcome")) {
                WorkspaceLoadingScreen(WorkspaceStartupPhase.PROJECT_CATALOG)
            } }
        } }
        compose.onNodeWithText("Loading your projects").performScrollTo().assertIsDisplayed()
        val bar = compose.onNode(progress).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val screen = compose.onNodeWithTag("welcome").fetchSemanticsNode().boundsInRoot
        assertTrue(bar.left >= screen.left && bar.right <= screen.right)
        assertTrue(bar.bottom < compose.onNodeWithText("Your workspace, anywhere.").fetchSemanticsNode().boundsInRoot.top)
    }

    @Test fun disablingSystemAnimationsKeepsLoadingAndPhaseUpdatesVisible() {
        motionScale.factor = 0f
        var phase by mutableStateOf(WorkspaceStartupPhase.RUNTIME_REGISTRIES)
        compose.setContent { DroideTheme { Box(Modifier.size(393.dp, 800.dp)) {
            WorkspaceLoadingScreen(phase)
        } } }
        compose.onNode(progress).assertIsDisplayed()
        compose.onNodeWithText("Wait a moment…").assertIsDisplayed()
        compose.runOnIdle { phase = WorkspaceStartupPhase.PUBLISH_RUNTIME }
        compose.onNodeWithText("Getting things ready").assertIsDisplayed()
        compose.onNode(progress).assertIsDisplayed()
    }
}
