package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.baystudio.droide.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.robolectric.RuntimeEnvironment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.FileRepository
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-port")
class ExplorerExportMenuTest {
    @get:Rule val compose = createComposeRule()
    private val root = Files.createTempDirectory("explorer-export-menu-").toFile()
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun exportAllAlwaysRequestsRootAndPreservesSortingAndRecovery() {
        root.resolve("src").mkdirs()
        val state = ExplorerWorkspaceState().apply { activeDirectory = "src" }
        val requests = mutableListOf<Pair<String, Boolean>>()
        val files = FileRepository(root)
        compose.setContent { DroideTheme { Box(Modifier.size(360.dp, 640.dp)) {
            compose.TestFileExplorer(files, state, onOpen = {}, onExport = { path, directory -> requests += path to directory })
        } } }
        compose.onNodeWithContentDescription("Explorer options").performClick()
        compose.onNodeWithText("Export all").assertIsEnabled()
        compose.onNodeWithText("Name · A to Z").assertExists()
        compose.onNodeWithText("Recover misplaced files").assertExists()
        compose.onNodeWithText("Export all").performClick()
        assertEquals(listOf("" to true), requests)
    }

    @Test fun fileRowPreservesMutationActionsAndRequestsOnlySelectedFile() {
        root.resolve("one.bin").writeBytes(byteArrayOf(0, 1, 2))
        val files = FileRepository(root); val state = ExplorerWorkspaceState()
        val requests = mutableListOf<Pair<String, Boolean>>()
        compose.setContent { DroideTheme { Box(Modifier.size(360.dp, 640.dp)) {
            compose.TestFileExplorer(files, state, onOpen = {}, onExport = { path, directory -> requests += path to directory })
        } } }
        compose.waitUntil(5_000) { state.children[""]?.isNotEmpty() == true }
        compose.onNodeWithContentDescription("File actions").performClick()
        compose.onNodeWithText("Move to…").assertExists()
        compose.onNodeWithText("Rename").assertExists()
        compose.onNodeWithText("Delete").assertExists()
        compose.onNodeWithText("Export file").performClick()
        assertEquals(listOf("one.bin" to false), requests)
        assertTrue(root.resolve("one.bin").exists())
    }

    @Test fun folderRowRequestsRecursiveFolderScope() {
        root.resolve("src").mkdirs()
        val files = FileRepository(root); val state = ExplorerWorkspaceState()
        val requests = mutableListOf<Pair<String, Boolean>>()
        compose.setContent { DroideTheme { Box(Modifier.size(360.dp, 640.dp)) {
            compose.TestFileExplorer(files, state, onOpen = {}, onExport = { path, directory -> requests += path to directory })
        } } }
        compose.waitUntil(5_000) { state.children[""]?.isNotEmpty() == true }
        compose.onNodeWithContentDescription("File actions").performClick()
        compose.onNodeWithText("Export folder").performClick()
        assertEquals(listOf("src" to true), requests)
    }

    @Test fun priorRetainedCaptureBlocksNewConfigurationUntilDiscarded() {
        val previous = com.baystudio.droide.core.ExportJobState("job", "epoch", com.baystudio.droide.core.ExportJobPhase.PARTIAL, retainedCapture = true)
        assertTrue(exportNeedsPriorResolution(previous))
        assertFalse(exportNeedsPriorResolution(previous.copy(retainedCapture = false)))
        assertTrue(exportNeedsPriorResolution(previous.copy(phase = com.baystudio.droide.core.ExportJobPhase.CANCELLING, retainedCapture = false)))
        assertFalse(exportNeedsPriorResolution(null))
    }

    @Test fun phaseLabelsKeepUnverifiedAndPartialDistinctFromSuccess() {
        assertEquals("Unverified · integrity not established", exportPhaseLabel(com.baystudio.droide.core.ExportJobPhase.UNVERIFIED))
        assertEquals("Partial export · incomplete output", exportPhaseLabel(com.baystudio.droide.core.ExportJobPhase.PARTIAL))
        assertFalse(exportPhaseLabel(com.baystudio.droide.core.ExportJobPhase.WRITING).contains("Completed"))
        assertFalse(exportPhaseLabel(com.baystudio.droide.core.ExportJobPhase.VERIFYING).contains("Completed"))
    }

    @Test fun dismissingPartialBannerKeepsHistoryAndNewJobStateVisible() {
        val app = RuntimeEnvironment.getApplication()
        val journal = ExportJournal(app.filesDir.resolve("exports-v1"))
        val previous = ExportJobState("partial-job", "test-epoch", ExportJobPhase.PARTIAL,
            detail = "Retained partial output", retainedCapture = false)
        journal.write(previous)
        ExportController.initialize(app)
        val before = journal.history()
        val files = FileRepository(root)
        val state = ExplorerWorkspaceState()
        val documents = object : WorkspaceDocumentAuthority {
            override suspend fun snapshot(path: String): WorkspaceDocumentSnapshot? = null
            override suspend fun snapshots(): List<WorkspaceDocumentSnapshot> = emptyList()
        }
        val admission = ExportSourceAdmission("banner-test", root, documents, {})
        val restoration = StateRestorationTester(compose)
        restoration.setContent { DroideTheme { Box(Modifier.size(360.dp, 640.dp)) {
            val actions = rememberExplorerExportUi(admission, "Workspace", false,
                saveScope = { EditorSaveReport() }, captureEditor = {})
            compose.TestFileExplorer(files, state, onOpen = {}, onExport = actions.open,
                onExportStatus = actions.showStatus, exportStatusLabel = actions.statusLabel,
                onDismissExportStatus = actions.dismissStatus)
        } } }
        compose.onNodeWithText("Partial export · incomplete output").assertExists()
        compose.onNodeWithContentDescription("Dismiss export status").performClick()
        compose.onNodeWithText("Partial export · incomplete output").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Partial export · incomplete output").assertDoesNotExist()
        assertEquals(previous, ExportController.state.value)
        assertEquals(before, journal.history())
        compose.onNodeWithContentDescription("Explorer options").performClick()
        compose.onNodeWithText("Export status").assertIsEnabled().performClick()
        compose.onNodeWithText("Export status").assertExists()
        compose.onNodeWithText("Retained partial output").assertExists()
        compose.onNodeWithText("Close").performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("explorer-export-sheet").fetchSemanticsNodes().isEmpty()
        }
        @Suppress("UNCHECKED_CAST")
        val flow = ExportController.javaClass.getDeclaredField("mutableState").apply { isAccessible = true }
            .get(ExportController) as MutableStateFlow<ExportJobState?>
        compose.runOnIdle { flow.value = previous.copy(phase = ExportJobPhase.INTERRUPTED) }
        compose.onNodeWithText("Export interrupted").assertExists()
        compose.onNodeWithContentDescription("Dismiss export status").performClick()
        compose.runOnIdle { flow.value = previous.copy(id = "another-partial-job") }
        compose.onNodeWithText("Partial export · incomplete output").assertExists()
        assertEquals(before, journal.history())
    }

}
