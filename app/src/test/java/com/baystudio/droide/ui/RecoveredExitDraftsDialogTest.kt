package com.baystudio.droide.ui

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.baystudio.droide.core.ExitDraftAttachment
import com.baystudio.droide.core.ExitDraftRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp")
class RecoveredExitDraftsDialogTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ui = AgentWorkspaceUiState(scope)
    @After fun cleanup() { ui.close(); scope.cancel() }

    private fun mount(record: ExitDraftRecord) {
        ui.recoveredExitDrafts.value = listOf(record)
        compose.setContent { DroideTheme { RecoveredExitDraftsDialog(ui) {} } }
    }

    @Test fun restoreCopiesFullTextWithoutChangingAgentOrRemovingSavedCopy() {
        val record = ExitDraftRecord("codex", "old-session", "User text \uD83E\uDDEA " + "x".repeat(1_000))
        mount(record)
        compose.onNodeWithText("Restore text here").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(record.text, ui.input.value)
            assertEquals("droide", ui.backendId.value)
            assertEquals(listOf(record), ui.recoveredExitDrafts.value)
            assertFalse(ui.externalController.busy)
        }
        compose.onNodeWithText("Restore text here").assertIsNotEnabled()
        compose.onNodeWithText("Send", substring = false).assertDoesNotExist()
    }

    @Test fun deletionNeedsConfirmationAndStorageFailureRemainsVisible() {
        val record = ExitDraftRecord("droide", null, "keep until selected deletion succeeds")
        mount(record)
        compose.onNodeWithText("Delete saved copy").performScrollTo().performClick()
        compose.onNodeWithText("Delete this saved copy?").assertExists()
        compose.runOnIdle { assertEquals(listOf(record), ui.recoveredExitDrafts.value) }
        compose.onNodeWithText("Keep saved copy").performClick()
        compose.onNodeWithText("Delete this saved copy?").assertDoesNotExist()
        compose.onNodeWithText("Delete saved copy").performScrollTo().performClick()
        compose.onAllNodesWithText("Delete saved copy").onLast().performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(record), ui.recoveredExitDrafts.value)
            assertEquals("Saved draft storage is unavailable. Nothing was deleted.", ui.exitDraftError.value)
        }
        compose.onNodeWithText("Delete this saved copy?").assertExists()
    }

    @Test fun pausedExitAllowsReviewAndDeleteButDisablesRestore() {
        ui.exitDraftsFrozen.value = true
        mount(ExitDraftRecord("droide", null, "saved text"))
        compose.onNodeWithText("Restore text here").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Delete saved copy").assertIsEnabled().performClick()
        compose.onNodeWithText("Delete this saved copy?").assertExists()
    }

    @Test fun displayExcerptCopiesTheCompleteSavedAttachmentPreview() {
        val preview = "a".repeat(159) + "\uD83E\uDDEA" + "important tail".repeat(100)
        mount(ExitDraftRecord("droide", null, "message", listOf(ExitDraftAttachment("notes.txt", preview, previewTruncated = true))))
        compose.onNodeWithText("Copy full saved preview").performScrollTo().performClick()
        compose.runOnIdle {
            val app = RuntimeEnvironment.getApplication()
            val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(preview, clipboard.primaryClip!!.getItemAt(0).text.toString())
            assertEquals(preview, ui.recoveredExitDrafts.value.single().attachments.single().preview)
        }
    }
}
