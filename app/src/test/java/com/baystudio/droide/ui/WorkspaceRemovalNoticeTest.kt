package com.baystudio.droide.ui

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.DroideProject
import com.baystudio.droide.core.ProjectManager
import com.baystudio.droide.core.PathSecurity
import com.baystudio.droide.core.ProjectStore
import com.baystudio.droide.core.FileProjectStore
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkspaceRemovalNoticeTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var mainWindowRoot: View

    @Test fun drawerRemoveLeavesFilesAtTheirOriginalPath() {
        val app = Files.createTempDirectory("droide-drawer-remove-").toFile()
        try {
            val manager = ProjectManager(app)
            val project = runBlocking {
                manager.init()
                manager.add("Sample").also { manager.restoreToRecent(it.id) }
            }
            val source = java.io.File(project.rootPath, "pending.txt").apply { writeText("pending user work") }
            mountDrawer(manager)
            compose.onNodeWithContentDescription("Project actions").performClick()
            compose.onNodeWithText("Remove Workspace…").performClick()
            traverseNativeDialogWindow()
            compose.onNodeWithText("Remove Workspace").performClick()
            compose.waitUntil(10_000) { manager.projects.value.none { it.id == project.id && !it.workspaceRemoved } }
            compose.waitForIdle()
            assertTrue("Remove must leave the original path readable", source.isFile)
            assertEquals("pending user work", source.readText())
            assertFalse(java.io.File(source.parentFile.parentFile, ".delete-${project.id}").exists())
        } finally { PathSecurity.deleteTreeNoFollow(app) }
    }

    @Test fun drawerExplainsRemovalFailureInsideTheConfirmationDialog() {
        val app = Files.createTempDirectory("droide-drawer-remove-failure-").toFile()
        try {
            var failWrites = false
            val delegate = FileProjectStore(app)
            val store = object : ProjectStore by delegate {
                override suspend fun save(all: List<DroideProject>) {
                    if (failWrites) throw IOException("Catalog unavailable")
                    delegate.save(all)
                }
            }
            val manager = ProjectManager(app, store)
            val project = runBlocking {
                manager.init()
                manager.add("Sample").also { manager.restoreToRecent(it.id) }
            }
            val source = java.io.File(project.rootPath, "pending.txt").apply { writeText("pending user work") }
            failWrites = true
            mountDrawer(manager)
            compose.onNodeWithContentDescription("Project actions").performClick()
            compose.onNodeWithText("Remove Workspace…").performClick()
            traverseNativeDialogWindow()
            compose.onNodeWithText("Remove Workspace").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Catalog unavailable").fetchSemanticsNodes().isNotEmpty() }
            traverseNativeDialogWindow()
            compose.onNodeWithText("Catalog unavailable").assertIsDisplayed()
            compose.onNodeWithText("Remove Workspace").assertIsEnabled()
            compose.onNodeWithText("Workspace removed").assertDoesNotExist()
            assertTrue(manager.projects.value.any { it.id == project.id })
            assertEquals("pending user work", source.readText())
        } finally { PathSecurity.deleteTreeNoFollow(app) }
    }

    private fun mountDrawer(manager: ProjectManager) {
        compose.setContent {
            mainWindowRoot = LocalView.current.rootView
            DroideTheme { Box(Modifier.size(360.dp, 640.dp)) {
                ProjectDrawer(manager, onPickSaf = {}, onSwitch = {})
            } }
        }
    }

    @Test fun storedProjectRestoresAndOpensThroughTheExistingCallbackWithTheSameId() {
        val app = Files.createTempDirectory("droide-drawer-restore-").toFile()
        try {
            val manager = ProjectManager(app)
            val original = runBlocking { manager.init(); manager.add("Saved sample").also { manager.removeWorkspace(it.id) } }
            val restarted = ProjectManager(app).also { runBlocking { it.init() } }
            var selectedId: String? = null
            compose.setContent {
                mainWindowRoot = LocalView.current.rootView
                DroideTheme { Box(Modifier.size(360.dp, 640.dp)) {
                    ProjectDrawer(restarted, onPickSaf = {}, onSwitch = { selectedId = it })
                } }
            }
            compose.onNodeWithText("Stored projects (1)").performClick()
            compose.onNodeWithText("Saved sample").assertIsDisplayed()
            compose.onNodeWithText("Restore").performClick()
            compose.waitUntil(10_000) { restarted.storedProjects.value.isEmpty() }
            compose.waitForIdle()
            compose.onNodeWithText("Stored projects (1)").assertDoesNotExist()
            compose.onNodeWithText("Saved sample").performClick()
            compose.runOnIdle { assertEquals(original.id, selectedId) }
            assertEquals(original, restarted.projects.value.single { it.id == original.id })
            assertEquals("default", restarted.activeId.value)
        } finally { PathSecurity.deleteTreeNoFollow(app) }
    }

    @Test fun missingStoredFolderShowsRestoreErrorWithoutLosingItsEntry() {
        val app = Files.createTempDirectory("droide-drawer-restore-missing-").toFile()
        try {
            val manager = ProjectManager(app)
            val original = runBlocking { manager.init(); manager.add("Saved sample").also { manager.removeWorkspace(it.id) } }
            val root = java.io.File(original.rootPath)
            val held = java.io.File(app, "held-source")
            Files.move(root.toPath(), held.toPath())
            mountDrawer(manager)
            compose.onNodeWithText("Stored projects (1)").performClick()
            compose.onNodeWithText("Restore").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Stored project folder is unavailable:", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Stored project folder is unavailable:", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Saved sample").assertIsDisplayed()
            compose.onNodeWithText("Restore").assertIsEnabled()
            assertEquals(original.copy(workspaceRemoved = true), manager.storedProjects.value.single())
            assertFalse(root.exists())
            assertTrue(held.isDirectory)
        } finally { PathSecurity.deleteTreeNoFollow(app) }
    }

    @Test fun originalLocationExplainsRemoveWithoutClaimingRecoveryOrDeletion() {
        val path = "/data/user/0/com.baystudio.droide/files/projects/p-owned"
        var closes = 0
        mount(DroideProject("p-owned", "Sample", path)) { closes++ }

        compose.onNodeWithText("Workspace removed").assertExists()
        compose.onNodeWithText("Sample was removed from Droide's project list.").assertExists()
        compose.onNodeWithText("This action does not move or delete files and does not free storage space.").assertExists()
        compose.onNodeWithText(path).performScrollTo()
        traverseNativeDialogWindow()
        compose.onNodeWithText(path).assertIsDisplayed()
        compose.onNodeWithText("Original project location:").performScrollTo()
        traverseNativeDialogWindow()
        compose.onNodeWithText("Original project location:").assertIsDisplayed()
        assertOnlyCloseAction()
        compose.runOnIdle { assertEquals(0, closes) }
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertEquals(1, closes) }
        compose.onNodeWithText("Workspace removed").assertDoesNotExist()
    }

    @Test fun locationHintDoesNotClaimTheFolderStillExists() {
        var closes = 0
        mount(DroideProject("p-empty", "Sample", "/missing/project")) { closes++ }

        compose.onNodeWithText("Workspace removed").assertExists()
        compose.onNodeWithText("Sample was removed from Droide's project list.").assertExists()
        compose.onNodeWithText("Original project location:").assertExists()
        compose.onNodeWithText("/missing/project").assertExists()
        compose.onNodeWithText("This action does not move or delete files and does not free storage space.").assertExists()
        compose.onNodeWithText("Files were kept", substring = true).assertDoesNotExist()
        compose.onNodeWithText("continue to use device storage", substring = true).assertDoesNotExist()
        assertOnlyCloseAction()
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertEquals(1, closes) }
        compose.onNodeWithText("Workspace removed").assertDoesNotExist()
    }

    @Test fun alreadyAbsentRegistrationDoesNotInventARemovalOrFileRecoveryOutcome() {
        var closes = 0
        mount(null) { closes++ }

        compose.onNodeWithText("Workspace not registered").assertExists()
        compose.onNodeWithText("Sample is no longer registered in Droide.").assertExists()
        compose.onNodeWithText("Workspace removed").assertDoesNotExist()
        compose.onNodeWithText("Sample was removed from Droide's project list.").assertDoesNotExist()
        compose.onNodeWithText("No workspace folder was detached. No recovery location was created.").assertDoesNotExist()
        compose.onNodeWithText("Files were kept", substring = true).assertDoesNotExist()
        assertOnlyCloseAction()
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertEquals(1, closes) }
        compose.onNodeWithText("Workspace not registered").assertDoesNotExist()
    }

    private fun mount(outcome: DroideProject?, onClose: () -> Unit) {
        var visible by mutableStateOf(false)
        compose.setContent {
            mainWindowRoot = LocalView.current.rootView
            DroideTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    if (visible) WorkspaceRemovalNotice("Sample", outcome) {
                        visible = false
                        onClose()
                    }
                }
            }
        }
        compose.runOnIdle { visible = true }
        traverseNativeDialogWindow()
    }

    private fun assertOnlyCloseAction() {
        compose.onNodeWithText("Close").assertIsDisplayed().assertIsEnabled()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).assertCountEquals(1)
        listOf("Delete", "Delete Workspace", "Purge", "Restore", "Retry").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
    }

    private fun traverseNativeDialogWindow() {
        assertTrue("Automatic Compose idling stays enabled", compose.mainClock.autoAdvance)
        var quietFrames = 0
        var modalSeen = false
        val samples = mutableListOf<String>()
        repeat(20) { frame ->
            compose.mainClock.advanceTimeByFrame()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.runOnUiThread {
                val environment = ReflectionHelpers.getField<Any>(compose, "environment")
                val registry = ReflectionHelpers.getField<Any>(environment, "composeRootRegistry")
                val idler = ReflectionHelpers.getField<Any>(environment, "composeIdlingResource")
                ReflectionHelpers.callInstanceMethod<Boolean>(idler, "isIdleNow")
                val owners: Set<Any> = ReflectionHelpers.callInstanceMethod(registry, "getRegisteredComposeRoots")
                var layouts = 0
                var modals = 0
                for (owner in owners) {
                    val view = ReflectionHelpers.callInstanceMethod<View>(owner, "getView")
                    if (view.rootView !== mainWindowRoot && view.isAttachedToWindow && view.isShown &&
                        view.windowVisibility == View.VISIBLE) {
                        modals++
                        if (ReflectionHelpers.callInstanceMethod<Boolean>(owner, "getHasPendingMeasureOrLayout")) {
                            layouts++
                            ReflectionHelpers.callInstanceMethod<Void>(view, "measureAndLayoutForTest")
                        }
                    }
                }
                modalSeen = modalSeen || modals > 0
                val idle = ReflectionHelpers.callInstanceMethod<Boolean>(idler, "isIdleNow")
                quietFrames = if (modals > 0 && layouts == 0 && idle) quietFrames + 1 else 0
                samples += "$frame:modals=$modals,layouts=$layouts,idle=$idle"
            }
        }
        println("WORKSPACE_REMOVAL_DIALOG_TRAVERSAL " + samples.joinToString(";"))
        assertTrue("The real modal owner must register: $samples", modalSeen)
        assertTrue("Dialog must remain idle for five frames without manual layout: $samples", quietFrames >= 5)
    }
}
