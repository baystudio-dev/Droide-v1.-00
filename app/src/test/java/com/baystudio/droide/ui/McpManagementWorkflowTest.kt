package com.baystudio.droide.ui

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.compose.ui.semantics.SemanticsActions
import org.robolectric.Shadows
import org.robolectric.util.ReflectionHelpers
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import com.baystudio.droide.core.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowProcess

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class McpManagementWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val root = Files.createTempDirectory("mcp-ui-").toFile()
    private val manager = McpManager(root)
    private val management = McpManagement(FileRepository(root), manager) { PermEffect.ASK }

    @Before fun before() {
        ShadowProcess.setPid(4242)
    }
    @After fun cleanup() {
        PathSecurity.deleteTreeNoFollow(root)
    }

    // Reuse the established bounded SDK 35 dialog traversal; retain the normal idler.
    private fun traverseNativeDialogWindow() {
        assertTrue("Automatic Compose idling stays enabled", compose.mainClock.autoAdvance)
        var idleAfterTraversal = false
        repeat(2) {
            compose.mainClock.advanceTimeByFrame()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.runOnUiThread {
                val environment = ReflectionHelpers.getField<Any>(compose, "environment")
                val registry = ReflectionHelpers.getField<Any>(environment, "composeRootRegistry")
                val idler = ReflectionHelpers.getField<Any>(environment, "composeIdlingResource")
                ReflectionHelpers.callInstanceMethod<Boolean>(idler, "isIdleNow")
                val owners: Set<Any> = ReflectionHelpers.callInstanceMethod(registry, "getRegisteredComposeRoots")
                for (owner in owners) {
                    val ownerView = ReflectionHelpers.callInstanceMethod<View>(owner, "getView")
                    if (ownerView.rootView !== compose.activity.window.decorView.rootView && ownerView.isAttachedToWindow && ownerView.isShown &&
                        ownerView.windowVisibility == View.VISIBLE &&
                        ReflectionHelpers.callInstanceMethod<Boolean>(owner, "getHasPendingMeasureOrLayout")) {
                        ReflectionHelpers.callInstanceMethod<Void>(ownerView, "measureAndLayoutForTest")
                    }
                }
                idleAfterTraversal = ReflectionHelpers.callInstanceMethod<Boolean>(idler, "isIdleNow")
            }
        }
        assertTrue("Native dialog layout and the normal Compose clock must settle", idleAfterTraversal)
    }

    private fun enterDialogText(label: String, text: String) {
        traverseNativeDialogWindow()
        val input = compose.onNodeWithText(label, substring = false)
        input.performSemanticsAction(SemanticsActions.RequestFocus)
        traverseNativeDialogWindow()
        input.assertIsFocused().performTextReplacement(text)
        traverseNativeDialogWindow()
    }

    private fun capture(name: String) {
        val directory = System.getProperty("droide.mcp.ui.output") ?: return
        File(directory).mkdirs()
        val bitmap = compose.runOnIdle {
            val view = compose.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        File(directory, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private fun mount() {
        compose.setContent {
            val health by McpHealthRegistry.observe(root).collectAsState()
            DroideTheme {
                Surface(color = MaterialTheme.colorScheme.background) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    McpManagementSection(management, health, {}, {}, emptySet())
                } }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Add server").fetchSemanticsNodes().isNotEmpty() &&
            compose.onAllNodes(hasContentDescription("Add server") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun addDisableAndRemoveServerThroughActualControls() {
        mount()
        compose.onNodeWithContentDescription("Add server").performClick()
        enterDialogText("Server name", "local")
        enterDialogText("Command", "node")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { manager.list().any { it.name == "local" } }
        capture("mcp-list.png")
        compose.onNodeWithText("local", substring = false).performScrollTo().performClick()
        val editBounds = compose.onNodeWithText("Edit", substring = false).fetchSemanticsNode().boundsInRoot
        val removeBounds = compose.onNodeWithText("Remove", substring = false).fetchSemanticsNode().boundsInRoot
        assertTrue("Action touch targets must not overlap: $editBounds / $removeBounds",
            editBounds.right <= removeBounds.left || editBounds.bottom <= removeBounds.top)
        capture("mcp-expanded.png")
        compose.onNodeWithText("Enabled").performScrollTo()
        compose.onNode(isToggleable()).performClick()
        compose.waitUntil(5_000) { manager.list().singleOrNull()?.enabled == false }
        compose.onNodeWithText("Remove", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Remove MCP server?").assertExists()
        compose.onAllNodesWithText("Remove", substring = false).onLast().performClick()
        compose.waitUntil(5_000) { manager.list().isEmpty() }
    }

    @Test fun connectionReviewCanBeCancelledAndRejectsChangedConfiguration() {
        runBlocking { management.save(management.load(), McpServerDraft("local", "node"), null) }
        mount()
        compose.onNodeWithText("local", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Connect", substring = false).performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Connect once").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(McpHealthState.CONFIGURED, McpHealthRegistry.observe(root).value.single().state)
        compose.onNodeWithText("Connect", substring = false).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Connect once").fetchSemanticsNodes().isNotEmpty() }
        root.resolve(".droide/mcp.json").writeText("""{"servers":{"local":{"command":"python3"}}}""")
        compose.onNodeWithText("Connect once").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("MCP server configuration changed", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertFalse(McpHealthRegistry.observe(root).value.any { it.state == McpHealthState.CONNECTED })
    }

    @Test fun permissionBlockedStatusCanBeCheckedAgainAfterPolicyChanges() {
        runBlocking { management.save(management.load(), McpServerDraft("local", "node"), null) }
        McpHealthRegistry.permissionDeferred(root, manager.list().single(), denied = true)
        mount()
        compose.onNodeWithText("local", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Connect", substring = false).performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Connect once").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Cancel").performClick()
    }
}
