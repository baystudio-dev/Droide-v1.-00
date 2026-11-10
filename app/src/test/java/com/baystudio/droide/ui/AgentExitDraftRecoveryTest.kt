package com.baystudio.droide.ui

import android.content.Context
import android.content.ContextWrapper
import com.baystudio.droide.core.*
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentExitDraftRecoveryTest {
    @Test(timeout = 10_000) fun restoreCopiesAllTextKeepsBackendAndArchiveAndDoesNotSend() = fixture { f ->
        val record = ExitDraftRecord("codex", "old-session", "Preserve \uD83D\uDE80\n" + "x".repeat(60_000))
        f.store.preserve(listOf(record))
        val ui = f.ui()
        ui.backendId.value = "droide"
        assertTrue(ui.restoreExitDraft(record))
        assertEquals(record.text, ui.input.value)
        assertEquals("droide", ui.backendId.value)
        assertEquals(listOf(record), ui.recoveredExitDrafts.value)
        assertEquals(listOf(record), f.store.load())
        assertTrue(ui.attachments.value.isEmpty())
        assertFalse(ui.externalController.busy)
        assertNotNull(ui.exitDraftNotice.value)
    }

    @Test(timeout = 10_000) fun occupiedOrActiveComposerRejectsRestoreWithoutThrowingOrReplacingData() = fixture { f ->
        val record = ExitDraftRecord("codex", null, "recovered")
        f.store.preserve(listOf(record))
        val ui = f.ui()
        ui.input.value = "existing unsent text"
        assertFalse(ui.restoreExitDraft(record))
        assertEquals("existing unsent text", ui.input.value)
        assertNotNull(ui.exitDraftError.value)
        ui.input.value = ""
        ui.attachments.value = listOf(AgentAttachedContext("existing.txt", "existing file preview"))
        assertFalse(ui.restoreExitDraft(record))
        assertEquals("existing.txt", ui.attachments.value.single().name)
        ui.attachments.value = emptyList()
        ui.mediaPickerPending.value = true
        assertFalse(ui.restoreExitDraft(record))
        ui.mediaPickerPending.value = false
        val preflight = Job()
        ui.preflightJob.value = preflight
        try { assertFalse(ui.restoreExitDraft(record)) } finally { preflight.cancelAndJoin() }
        ui.preflightJob.value = null
        assertTrue(ui.restoreExitDraft(record))
        assertEquals("recovered", ui.input.value)
    }

    @Test(timeout = 10_000) fun overSendLimitRestoreStillKeepsFullSavedTextAndWarns() = fixture { f ->
        val record = ExitDraftRecord("droide", null, "x".repeat(64_001))
        f.store.preserve(listOf(record))
        val ui = f.ui()
        assertTrue(ui.restoreExitDraft(record))
        assertEquals(record.text, ui.input.value)
        assertTrue(ui.exitDraftNotice.value.orEmpty().contains("exceeds the current Send limit"))
    }

    @Test(timeout = 10_000) fun selectedSavedCopyCanBeDeletedWhileFrozenAndCapacityRetryPreservesCurrentText() = fixture { f ->
        val old = (0 until ExitDraftCodec.MAX_RECORDS).map { ExitDraftRecord("droide", null, "old-$it") }
        f.store.preserve(old)
        val ui = f.ui()
        ui.input.value = "current unsent text"
        rejected { ui.preserveDraftsForExit() }
        assertTrue(ui.exitDraftsFrozen.value)
        assertEquals("current unsent text", ui.input.value)
        assertFalse(ui.restoreExitDraft(old.first()))
        assertTrue(ui.deleteSavedExitDraft(old[4]))
        assertEquals("current unsent text", ui.input.value)
        ui.preserveDraftsForExit()
        assertEquals(ExitDraftCodec.MAX_RECORDS, f.store.load().size)
        assertFalse(f.store.load().contains(old[4]))
        assertTrue(f.store.load().any { it.text == "current unsent text" })
    }

    @Test(timeout = 10_000) fun failedDeletionIsVisibleAndLeavesComposerAndShownRecoveryIntact() = fixture { f ->
        val old = ExitDraftRecord("droide", null, "retained copy")
        f.store.preserve(listOf(old))
        val ui = f.ui()
        ui.input.value = "current composer"
        f.file.writeText("invalid encrypted archive")
        val before = f.file.readBytes()
        assertFalse(ui.deleteSavedExitDraft(old))
        assertNotNull(ui.exitDraftError.value)
        assertEquals("current composer", ui.input.value)
        assertEquals(listOf(old), ui.recoveredExitDrafts.value)
        assertArrayEquals(before, f.file.readBytes())
    }

    @Test(timeout = 10_000) fun unavailableMediaRequiresExactSnapshotApprovalBeforeReferenceOnlyExit() = fixture { f ->
        val ui = f.ui()
        val ref = ChatMediaRef("m-unavailable", ".droide/chat-media/blobs/m-unavailable/original.txt", "notes.txt", "text/plain", 42, 10, Long.MAX_VALUE)
        ui.input.value = "Keep this user text"
        ui.attachments.value = listOf(AgentAttachedContext("notes.txt", "saved full preview", media = ref, previewTruncated = true))
        rejected { ui.preserveDraftsForExit() }
        val firstFailure = ui.unconfirmedExitMediaDrafts.value.single()
        assertTrue(f.store.load().isEmpty())
        assertEquals(ref, firstFailure.attachments.single().media)
        assertTrue(ui.allowReferenceOnlyExitDrafts(listOf(firstFailure)))
        // A changed live snapshot is a new decision, never covered by the earlier approval.
        ui.input.value = "Changed user text"
        rejected { ui.preserveDraftsForExit() }
        assertFalse(ui.allowReferenceOnlyExitDrafts(listOf(firstFailure)))
        assertTrue(ui.allowReferenceOnlyExitDrafts(ui.unconfirmedExitMediaDrafts.value.toList()))
        ui.preserveDraftsForExit()
        val saved = f.store.load().single()
        assertEquals("Changed user text", saved.text)
        assertEquals("saved full preview", saved.attachments.single().preview)
        assertTrue(saved.attachments.single().previewTruncated)
        assertEquals(ref, saved.attachments.single().media)
        assertEquals(ref, ui.attachments.value.single().media)
    }

    @Test(timeout = 10_000) fun legacyComposerImageKeepsAvailablePreviewWithoutClaimingOriginalBytes() = fixture { f ->
        val ui = f.ui()
        ui.attachments.value = listOf(AgentAttachedContext("legacy.png", "legacy image preview", imagePath = "/unknown/legacy.png"))
        ui.preserveDraftsForExit()
        val attachment = f.store.load().single().attachments.single()
        assertEquals("legacy.png", attachment.name)
        assertEquals("legacy image preview", attachment.preview)
        assertNull(attachment.media)
        assertTrue(exitDraftAttachmentDescription(attachment, 0).contains("original file is not stored"))
    }

    @Test(timeout = 10_000) fun onlyUserOriginPendingTextIsArchivedAndLegacyImageMetadataIsRetained() = fixture { f ->
        val ui = f.ui()
        val user = AgentPendingInput("user", "user-authored queued input", AgentPromptDelivery.QUEUE, "fixture", "fixture",
            images = listOf(AgentImageRef("images/legacy.png", "fixture-sha", "image/png", 24)))
        val worker = AgentPendingInput("worker", "private worker output must not enter draft recovery", AgentPromptDelivery.QUEUE,
            "fixture", "fixture", origin = AgentInputOrigin.SUBAGENT)
        ui.preserveDraftsForExit(listOf(user, worker))
        val saved = f.store.load().single()
        assertEquals(user.text, saved.text)
        assertEquals("legacy.png", saved.attachments.single().name)
        assertTrue(saved.attachments.single().preview.contains("images/legacy.png"))
        assertNull(saved.attachments.single().media)
    }

    @Test fun displayExcerptsDoNotSplitSurrogatesOrMutateSavedText() {
        val text = "a".repeat(239) + "\uD83E\uDDEA" + "tail"
        assertEquals("a".repeat(239), exitDraftDisplayPreview(text))
        assertEquals(245, text.length)
        assertEquals("exact", exitDraftDisplayPreview("exact"))
        val ref = ChatMediaRef("m", "path", "a.txt", "text/plain", 7, 1, 20)
        assertTrue(exitDraftAttachmentDescription(ExitDraftAttachment("a.txt", "full preview", ref), 21).contains("Expired reference"))
        assertTrue(exitDraftAttachmentDescription(ExitDraftAttachment("a.txt", "full preview", ref), 19).contains("availability is not checked"))
    }

    private suspend fun rejected(block: suspend () -> Unit): Throwable = try { block(); throw AssertionError("Expected rejection") }
        catch (failure: Exception) { failure }
    private fun fixture(block: suspend (Fixture) -> Unit) = runBlocking {
        val f = Fixture()
        try { block(f) } finally { f.close() }
    }
    private class Fixture {
        val root = Files.createTempDirectory("r64-draft-state-").toFile()
        private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
        }
        private val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        val store = ExitDraftStore(context, "ui-project", object : ExitDraftCipher {
            override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD(aad)
                return cipher.iv + cipher.doFinal(plain)
            }
            override fun decrypt(encrypted: ByteArray, aad: ByteArray): ByteArray {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(0, 12))); cipher.updateAAD(aad)
                return cipher.doFinal(encrypted.copyOfRange(12, encrypted.size))
            }
        }) { directory -> FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) } }
        val file = File(root, ".droide/exit-drafts/ui-project.bin")
        private val owner = SupervisorJob()
        private val scope = CoroutineScope(owner + Dispatchers.Unconfined)
        private val instances = mutableListOf<AgentWorkspaceUiState>()
        suspend fun ui(): AgentWorkspaceUiState {
            val ui = AgentWorkspaceUiState(scope, exitDraftStoreOverride = store)
            instances += ui
            withTimeout(5_000) { while (ui.exitDraftLoading.value) delay(1) }
            assertNull(ui.exitDraftError.value)
            return ui
        }
        suspend fun close() {
            instances.forEach { it.close() }
            owner.cancelAndJoin()
            PathSecurity.deleteTreeNoFollow(root)
        }
    }
}
