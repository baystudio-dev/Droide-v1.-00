package com.baystudio.droide.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
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
import java.io.File

/** Compare original eager width reads against deferred measurement with the same animation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LandscapePaneFrameReadTest {
    @get:Rule val compose = createComposeRule()
    private val ime = mutableStateOf(false)
    private val requested = mutableStateOf(300.dp)
    private val compositions = IntArray(2)
    private val measurements = IntArray(2)
    private val mounts = IntArray(2)
    private val live = IntArray(2)
    private lateinit var hostView: View

    @Composable private fun Fixture(deferred: Boolean) {
        val index = if (deferred) 1 else 0
        val focus = rememberDroideLandscapeImeFocusLayout(
            droideWorkbenchLayout(1280.dp, 800.dp, 210.dp), 1280.dp, DroideRailTool.Files,
            droideImePresentation(true, false, ime.value, true, true), ime.value, true, true, requested.value,
        )
        // These boundary reads mirror browser ownership and approval visibility in IdeScreen.
        val sidebarVisible = focus.sidebarRegionVisible
        val agentVisible = focus.agentRegionVisible
        val oldSidebar = if (deferred) 0.dp else focus.sidebarRegionWidth
        val oldAgent = if (deferred) 0.dp else focus.agentRegionWidth
        SideEffect { compositions[index]++ }
        Row(Modifier.size(1280.dp, 200.dp).testTag("row$index").layout { measurable, constraints ->
            measurements[index]++
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
        }) {
            val sidebar: @Composable () -> Unit = {
                DisposableEffect(Unit) { mounts[index]++; live[index]++; onDispose { live[index]-- } }
                Box(Modifier.fillMaxSize().background(Color.Blue)) { Text("Sidebar") }
            }
            if (deferred) DroideClippedPaneRegion({ focus.sidebarRegionWidth }, sidebarVisible, focus.userPaneFit.sidebarWidth + 1.dp, content = sidebar)
            else DroideClippedPaneRegion(oldSidebar, focus.userPaneFit.sidebarWidth + 1.dp, content = sidebar)
            Box(Modifier.weight(1f).fillMaxHeight().background(Color.Gray).testTag("editor$index"))
            val agent: @Composable () -> Unit = { Box(Modifier.fillMaxSize().background(Color.Green)) { Text("Agent") } }
            if (deferred) DroideClippedPaneRegion({ focus.agentRegionWidth }, agentVisible, focus.userPaneFit.agentWidth + 10.dp, androidx.compose.ui.Alignment.CenterEnd, agent)
            else DroideClippedPaneRegion(oldAgent, focus.userPaneFit.agentWidth + 10.dp, androidx.compose.ui.Alignment.CenterEnd, agent)
        }
    }
    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            hostView = LocalView.current
            Column { Fixture(false); Fixture(true) }
        }
        compose.waitForIdle()
    }
    private fun equalBounds() {
        val a = compose.onNodeWithTag("editor0").fetchSemanticsNode().boundsInRoot
        val b = compose.onNodeWithTag("editor1").fetchSemanticsNode().boundsInRoot
        assertEquals(a.left, b.left, 0f)
        assertEquals(a.right, b.right, 0f)
        assertEquals(a.height, b.height, 0f)
    }
    private fun frame() { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle(); equalBounds() }
    private fun screenshot(name: String) {
        val dir = System.getProperty("droide.headerEvidenceDir") ?: return
        File(dir, "$name-bounds.txt").apply { parentFile?.mkdirs() }.writeText(
            (0..1).joinToString("\n") { "editor$it=" + compose.onNodeWithTag("editor$it").fetchSemanticsNode().boundsInRoot })
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(1280, 400, Bitmap.Config.ARGB_8888)
            hostView.draw(Canvas(bitmap))
            // Fixed 1280x200 mdpi crops: original top row y=0; deferred bottom row y=200.
            // Identical labels allow exact comparison of every pixel, including native text.
            val before = Bitmap.createBitmap(bitmap, 0, 0, 1280, 200)
            val after = Bitmap.createBitmap(bitmap, 0, 200, 1280, 200)
            assertTrue("$name whole-pane pixel parity", before.sameAs(after))
            for ((label, crop) in listOf("before" to before, "after" to after)) {
                File(dir, "$name-$label.png").outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
                crop.recycle()
            }
            File(dir, "$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @Test fun framesMeasureWithoutRecomposingWorkbenchAndKeepExactGeometry() {
        mount(); screenshot("expanded")
        compose.runOnIdle { ime.value = true }
        repeat(3) { frame() }
        val startC = compositions.copyOf(); val startM = measurements.copyOf()
        repeat(7) { frame() }
        val oldFrames = compositions[0] - startC[0]; val newFrames = compositions[1] - startC[1]
        assertTrue("Original composition frames=$oldFrames", oldFrames >= 5)
        assertEquals("Deferred workbench must not compose during interior frames", 0, newFrames)
        val oldMeasures = measurements[0] - startM[0]; val newMeasures = measurements[1] - startM[1]
        assertTrue(newMeasures >= 5)
        screenshot("intermediate")
        repeat(12) { frame() }
        compose.onAllNodesWithText("Sidebar").assertCountEquals(0)
        compose.onAllNodesWithText("Agent").assertCountEquals(0)
        screenshot("collapsed")
        compose.runOnIdle { ime.value = false }
        repeat(20) { frame() }
        compose.onAllNodesWithText("Sidebar").assertCountEquals(2)
        compose.onAllNodesWithText("Agent").assertCountEquals(2)
        assertArrayEquals(intArrayOf(1, 1), mounts); assertArrayEquals(intArrayOf(1, 1), live)
        screenshot("restored")
        val dir = System.getProperty("droide.headerEvidenceDir")
        if (dir != null) File(dir, "counters.txt").writeText("interior original compositions=$oldFrames; deferred compositions=$newFrames; original measurements=$oldMeasures; deferred measurements=$newMeasures\n")
    }
    @Test fun rapidImeReversalsAndDirectResizePreserveSingleSession() {
        mount()
        repeat(8) { n ->
            compose.runOnIdle { ime.value = n % 2 == 0 }
            repeat(4) { frame() }
        }
        compose.runOnIdle { ime.value = false; requested.value = 380.dp }
        repeat(22) { frame() }
        assertArrayEquals(intArrayOf(1, 1), mounts); assertArrayEquals(intArrayOf(1, 1), live)
        compose.onAllNodesWithText("Sidebar").assertCountEquals(2)
        screenshot("rapid-reversal-resized")
    }
}
