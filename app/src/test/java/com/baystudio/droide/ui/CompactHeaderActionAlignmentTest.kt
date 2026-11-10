package com.baystudio.droide.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Host Compose geometry/dispatch coverage; this is not physical-device validation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w500dp-h900dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompactHeaderActionAlignmentTest {
    @get:Rule val compose = createComposeRule()
    private var cutouts by mutableStateOf(emptyList<Rect>())
    private var title by mutableStateOf("Default")
    private var direction by mutableStateOf(LayoutDirection.Ltr)
    private var agentOpen by mutableStateOf(false)
    private var running by mutableStateOf(false)
    private val events = mutableListOf<String>()
    private val actions = listOf("Run current file", "Settings", "Open AI", "More")
    private val camera = Rect(236f, 0f, 264f, 32f)
    private lateinit var hostView: View

    private fun mount() {
        compose.setContent {
            val view = LocalView.current
            SideEffect { hostView = view }
            CompositionLocalProvider(LocalLayoutDirection provides direction,
                LocalHeaderCutouts provides HeaderCutoutSource { cutouts }) {
                DroideTheme {
                    Column(Modifier.fillMaxWidth().testTag("header-and-content")) {
                        DroideTopBar(
                            projectName = title, activeFile = "README.md", compact = true,
                            agentOpen = agentOpen, landscape = false, focusMode = false,
                            running = running, runLabel = "Run", runDescription = "Run current file",
                            onProjects = { events += "Projects" }, onRun = { events += "Run" },
                            showProfessionalGroups = false, buildActions = emptyList(),
                            debugActions = emptyList(), toolActions = emptyList(), onDevice = {},
                            onSettings = { events += "Settings" }, onAgent = { events += "AI" },
                            onFocusMode = {}, mcpHealth = emptyList(), onMcpRetry = {}, onManageMcp = {},
                            onMore = { events += "More" },
                        )
                        Box(Modifier.fillMaxWidth().height(40.dp).testTag("unchanged-content"))
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(description: String) = compose.onNodeWithContentDescription(description)
        .fetchSemanticsNode().boundsInRoot

    private fun recordScreenshot(name: String) {
        val directory = System.getProperty("droide.headerEvidenceDir") ?: return
        val target = File(directory, "$name.png").apply { parentFile?.mkdirs() }
        val area = compose.onNodeWithTag("header-and-content").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(area.width.toInt(), area.height.toInt(), Bitmap.Config.ARGB_8888)
            hostView.draw(Canvas(bitmap))
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        File(directory, "$name.txt").writeText(actions.joinToString("\n") { "$it=${bounds(it)}" })
    }

    private fun assertRightAligned() {
        actions.forEachIndexed { index, label ->
            val rect = bounds(label)
            assertEquals("$label left", 314f + index * 46f, rect.left, 0f)
            assertEquals("$label width", 44f, rect.width, 0f)
            assertEquals("$label top", 4f, rect.top, 0f)
            cutouts.forEach { assertFalse("$label overlaps camera", rect.overlaps(it)) }
        }
    }

    @Test fun centeredCameraKeepsOnlyTheFourActionsAtTheSafeRightEdge() {
        cutouts = listOf(camera)
        mount()
        recordScreenshot("centered-camera")
        assertRightAligned()
        assertEquals(4f, bounds("Projects").left, 0f)
        assertEquals(52f, compose.onNodeWithText("Default").fetchSemanticsNode().boundsInRoot.left, 0f)
    }

    @Test fun cameraChangesLeaveProjectsTitleAndLowerContentInPlace() {
        mount()
        val projects = bounds("Projects")
        val titleBounds = compose.onNodeWithText("Default").fetchSemanticsNode().boundsInRoot
        val content = compose.onNodeWithTag("unchanged-content").fetchSemanticsNode().boundsInRoot
        val originalActions = actions.associateWith(::bounds)
        for (sample in listOf(listOf(camera), emptyList(), listOf(camera), emptyList())) {
            compose.runOnIdle { cutouts = sample }
            compose.waitForIdle()
            assertEquals(projects, bounds("Projects"))
            assertEquals(titleBounds, compose.onNodeWithText("Default").fetchSemanticsNode().boundsInRoot)
            assertEquals(content, compose.onNodeWithTag("unchanged-content").fetchSemanticsNode().boundsInRoot)
            actions.forEach { assertEquals(it, originalActions.getValue(it), bounds(it)) }
        }
    }

    @Test fun longTitleAndRepeatedClicksKeepTheSameActionTargets() {
        cutouts = listOf(camera)
        mount()
        compose.runOnIdle { title = "A very long project name ".repeat(30) }
        assertRightAligned()
        repeat(3) {
            actions.forEach { label -> compose.onNodeWithContentDescription(label).performTouchInput { click(center) } }
        }
        assertEquals(List(3) { listOf("Run", "Settings", "AI", "More") }.flatten(), events)
        compose.runOnIdle { running = true; agentOpen = true }
        compose.onNodeWithContentDescription("Run current file").assertIsNotEnabled()
        assertEquals(406f, bounds("Close AI").left, 0f)
        compose.onNodeWithContentDescription("Close AI").performTouchInput { click(center) }
        assertEquals("AI", events.last())
    }

    @Test fun rtlMirrorsOnlyTheLogicalActionEdgeAndStillAvoidsTheCamera() {
        direction = LayoutDirection.Rtl
        cutouts = listOf(camera)
        mount()
        actions.forEachIndexed { index, label ->
            val rect = bounds(label)
            assertEquals(142f - index * 46f, rect.left, 0f)
            assertFalse(rect.overlaps(camera))
            compose.onNodeWithContentDescription(label).performTouchInput { click(center) }
        }
        assertEquals(listOf("Run", "Settings", "AI", "More"), events)
        assertEquals(452f, bounds("Projects").left, 0f)
    }

    @Test fun rightCameraStopsActionsAtTheSafeEdgeRatherThanUnderHardware() {
        cutouts = listOf(camera, Rect(470f, 0f, 500f, 35f))
        mount()
        assertEquals(466f, bounds("More").right, 0f)
        actions.forEach { label ->
            cutouts.forEach { assertFalse(bounds(label).overlaps(it)) }
            compose.onNodeWithContentDescription(label).performTouchInput { click(center) }
        }
        assertEquals(listOf("Run", "Settings", "AI", "More"), events)
    }

    @Test fun wideNotchRetainsOverflowAndDoesNotResurrectHiddenControls() {
        cutouts = listOf(Rect(50f, 0f, 444f, 38f))
        mount()
        compose.onNodeWithContentDescription("Settings").assertIsNotDisplayed()
        compose.onNodeWithContentDescription("Run current file").assertIsNotDisplayed()
        compose.onNodeWithContentDescription("More").performTouchInput { click(center) }
        assertEquals(listOf("More"), events)
        assertEquals(496f, bounds("More").right, 0f)
    }
}
