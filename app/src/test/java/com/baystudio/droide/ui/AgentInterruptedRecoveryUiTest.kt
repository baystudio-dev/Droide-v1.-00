package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.baystudio.droide.core.*
import java.nio.file.Files
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h640dp")
class AgentInterruptedRecoveryUiTest {
    @get:Rule val compose = createComposeRule()
    private val root = Files.createTempDirectory("interrupted-ui-").toFile()
    private val source = root.resolve("source").apply { mkdir() }
    private val working = root.resolve("working").apply { mkdir() }
    private val owner = MirrorInterruptedRecovery(source, working)
    private val operation = MirrorMetadataAuthority.Intent("00000000-0000-0000-0000-000000000001", "file.kt", "create",
        "00000000-0000-0000-0000-000000000001", MirrorMetadataAuthority.Phase.PREPARED,
        MirrorMetadataAuthority.Node.absent, MirrorMetadataAuthority.Node.file(WorkspaceSyncManifest.Entry("ab".repeat(32), 420)))
    private val finished = mutableListOf<Boolean>()
    private var archiveMode = false
    private val archives = mutableListOf<String>()
    private lateinit var controller: AgentRetainedRecoveryController
    private fun show(complete: Boolean) {
        val review = MirrorInterruptedRecovery.Review(owner, 0, operation, emptyList(), complete)
        val folder = MirrorRetainedRecovery.Item(operation, MirrorRetainedRecovery.Role.ORIGINAL, AnchoredWorkspaceFiles.Kind.DIRECTORY, null)
        val actions = object : AgentRetainedRecoveryActions {
            override val supportsDirectoryArchive = archiveMode
            override suspend fun archive(item: MirrorRetainedRecovery.Item, destination: String): Long {
                assertEquals(folder, item); archives += destination; return 10
            }
            override suspend fun list(cursor: MirrorRetainedRecovery.Cursor?) = MirrorRetainedRecovery.Page(if (archiveMode) listOf(folder) else emptyList(), null,
                if (!archiveMode && finished.isEmpty()) listOf(operation) else emptyList())
            override suspend fun inspect(item: MirrorRetainedRecovery.Item): MirrorRetainedRecovery.Inspection = error("No file selected")
            override suspend fun copy(inspection: MirrorRetainedRecovery.Inspection, destination: String): Long = error("No copy requested")
            override suspend fun inspectInterrupted(operation: MirrorMetadataAuthority.Intent) = review
            override suspend fun finishInterrupted(review: MirrorInterruptedRecovery.Review, complete: Boolean) { finished += complete }
        }
        compose.setContent { DroideTheme {
            val scope = rememberCoroutineScope()
            controller = remember { AgentRetainedRecoveryController(scope, actions) { true } }
            AgentRetainedRecoveryDialog(controller)
        } }
        compose.runOnIdle { controller.open() }
        compose.onNodeWithText(if (archiveMode) "CLI version · Folder (save TAR copy)" else "Review interrupted change · file.kt").performClick()
    }
    @After fun cleanup() {
        if (::controller.isInitialized) compose.runOnIdle { controller.close() }
        assertTrue(PathSecurity.deleteTreeNoFollow(root))
    }
    @Test fun inspectionNeverCompletesUntilUserPressesTheExplicitAction() {
        show(true)
        compose.onNodeWithText("Complete verified change").assertIsEnabled()
        compose.runOnIdle { assertTrue(finished.isEmpty()) }
        compose.onNodeWithText("Complete verified change").performClick()
        compose.runOnIdle { assertEquals(listOf(true), finished); assertNull(controller.state.interrupted) }
    }
    @Test fun unverifiedChangeOffersOnlyPreservingConflict() {
        show(false)
        compose.onNodeWithText("Complete verified change").assertIsNotEnabled()
        compose.onNodeWithText("Keep as conflict").performClick()
        compose.runOnIdle { assertEquals(listOf(false), finished) }
        compose.onNodeWithText("Interrupted change is now protected for manual review. Every file version was kept.").assertExists()
    }
    @Test fun folderArchiveRequiresExplicitSaveAndKeepsTheTarName() {
        archiveMode = true
        show(false)
        compose.onNodeWithText("Save TAR copy").assertIsEnabled()
        compose.runOnIdle { assertTrue(archives.isEmpty()); assertEquals("file.kt-recovered.tar", controller.state.destination) }
        compose.onNodeWithText("Save TAR copy").performClick()
        compose.runOnIdle { assertEquals(listOf("file.kt-recovered.tar"), archives); assertNull(controller.state.directory) }
    }

}
