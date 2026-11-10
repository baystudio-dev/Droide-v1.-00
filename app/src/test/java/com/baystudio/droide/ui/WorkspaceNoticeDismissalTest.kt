package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w420dp-h860dp")
class WorkspaceNoticeDismissalTest {
    @get:Rule val compose = createComposeRule()
    private val root = Files.createTempDirectory("notice-preservation-").toFile()
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun preferenceDismissalDoesNotRetryOrClearTheReadFailure() {
        var retries = 0
        val failed = mutableStateOf(true)
        compose.setContent { DroideTheme { PreferenceReadFailureRow("Preferences", failed.value) { retries++ } } }
        compose.onNodeWithContentDescription("Dismiss message").performClick()
        compose.onNodeWithText("Preferences could not be read. Stored data was kept.").assertDoesNotExist()
        assertTrue(failed.value)
        assertEquals(0, retries)
        compose.onNodeWithText("Retry").assertIsDisplayed().performClick()
        assertEquals(1, retries)
    }

    @Test fun browserNoticeCanCloseWithoutClosingBrowserAndNewDialogIsVisible() {
        var opened = 0
        var dialog by mutableStateOf(false)
        compose.setContent { DroideTheme { BrowserRetentionNotice(true, dialog) { opened++ } } }
        compose.onNodeWithContentDescription("Dismiss message").performClick()
        compose.onNodeWithText("Browser kept active · uses RAM").assertDoesNotExist()
        assertEquals(0, opened)
        compose.onNodeWithText("View browser").performClick()
        assertEquals(1, opened)
        compose.runOnIdle { dialog = true }
        compose.onNodeWithText("Browser needs a response").assertIsDisplayed()
    }

    @Test fun explorerNoticeDismissalPreservesFilesAndUnderlyingRecoveryMessage() {
        val file = root.resolve("work.txt").apply { writeText("work to preserve") }
        val files = FileRepository(root)
        val state = ExplorerWorkspaceState().apply { importNotice = "Import needs review; original files retained" }
        compose.setContent { DroideTheme { Box(Modifier.size(420.dp, 800.dp)) {
            compose.TestFileExplorer(files, state, onOpen = {})
        } } }
        compose.waitUntil(5_000) { state.children[""]?.isNotEmpty() == true }
        compose.onNodeWithContentDescription("Dismiss message").performClick()
        compose.onNodeWithText("Import needs review; original files retained").assertDoesNotExist()
        assertEquals("Import needs review; original files retained", state.importNotice)
        assertEquals("work to preserve", file.readText())
        compose.onNodeWithText("work.txt").assertExists()
    }

    @Test fun closingPendingSyncMessageDoesNotPublishAndReviewRemainsAvailable() {
        val state = WorkspaceAgentSyncState(phase = WorkspaceAgentSyncPhase.PENDING, sourceRoot = root.path,
            executionRoot = "persistent-cli-copy", pendingPaths = listOf("work.txt"), pendingCount = 1)
        var reviews = 0
        compose.setContent { DroideTheme { AgentWorkspaceSyncNotice(state, false) { reviews++ } } }
        compose.onNodeWithContentDescription("Dismiss message").performClick()
        compose.onNodeWithText(state.label).assertDoesNotExist()
        assertEquals(0, reviews)
        assertEquals(1, state.pendingCount)
        compose.onNodeWithText("Files").performClick()
        compose.onNodeWithText("work.txt").assertExists()
        compose.onNodeWithText("Review files").performClick()
        assertEquals(1, reviews)
    }

    @Test fun largeFileDismissalPreservesBufferLimitsAndRedisplaysIncreasedSeverity() {
        val original = root.resolve("large.txt").apply { writeText("saved original") }
        val files = FileRepository(root)
        val document = EditorDocument("large.txt")
        runBlocking { document.ensureLoaded(files) }
        document.applyWorkspaceText("x".repeat(EditorLargeFilePolicy.FULL_INTELLIGENCE_MAX_BYTES.toInt() + 1), "test")
        compose.setContent { DroideTheme { EditorLargeFileStatusBar(document) } }
        compose.onNodeWithContentDescription("Dismiss large file message").performClick()
        compose.onNodeWithText("Performance mode · code intelligence and wrap are reduced.").assertDoesNotExist()
        assertTrue(document.dirty)
        assertTrue(document.largeFileOptimized)
        val oversized = "x".repeat(EditorLargeFilePolicy.MAX_EDITABLE_BYTES.toInt() + 1)
        compose.runOnIdle { document.capture(oversized) }
        compose.onNodeWithText("Editor stays active. Reduce below 20 MiB to save.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Dismiss large file message").performClick()
        val failure = runBlocking { document.save(files) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("20 MiB"))
        assertEquals("saved original", original.readText())
        assertEquals(oversized, document.content)
        assertTrue(document.dirty)
    }
}
