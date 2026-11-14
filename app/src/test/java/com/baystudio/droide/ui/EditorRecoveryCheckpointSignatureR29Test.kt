package com.baystudio.droide.ui

import com.baystudio.droide.core.DiskFileVersion
import com.baystudio.droide.core.FileRepository
import java.io.File
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Exercises the exact cached signature helper used by IdeScreen's periodic checkpoint loop. */
class EditorRecoveryCheckpointSignatureR29Test {
    @get:Rule val temp = TemporaryFolder()

    @Test(timeout = 15_000)
    fun successfulSaveAcknowledgementInvalidatesCheckpointWithUnchangedNewerTextAndVersion() =
        committedBaselineChange(cancelAfterPublication = false)

    @Test(timeout = 15_000)
    fun cancelledCommittedSaveInvalidatesCheckpointWithUnchangedNewerTextAndVersion() =
        committedBaselineChange(cancelAfterPublication = true)

    private fun committedBaselineChange(cancelAfterPublication: Boolean) = runBlocking {
        val root = temp.newFolder()
        val source = File(root, "main.txt").apply { writeText("A") }
        val owner = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        val state = EditorWorkspaceState()
        val document = state.document("main.txt")
        var checkpointBeforeAcknowledgement: String? = null
        var textVersionBeforeAcknowledgement = -1
        val files = FileRepository(root, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Write) {
                withContext(owner) {
                    document.capture("newer typing C")
                    assertEquals(DiskFileVersion.fromText("A"), document.savedDiskVersion)
                    checkpointBeforeAcknowledgement = state.recoveryCheckpointSignature(listOf("main.txt"), "main.txt")
                    textVersionBeforeAcknowledgement = document.changeVersion
                }
                if (cancelAfterPublication) throw CancellationException("fixture after canonical publication")
            }
        })
        document.ensureLoaded(files)
        document.capture("committed B")
        val outcome = runCatching { document.save(files).getOrThrow() }
        if (cancelAfterPublication) assertTrue(outcome.exceptionOrNull() is CancellationException)
        else assertTrue(outcome.isSuccess)
        assertNotNull(checkpointBeforeAcknowledgement)
        assertEquals("newer typing C", document.content)
        assertEquals(textVersionBeforeAcknowledgement, document.changeVersion)
        assertTrue(document.dirty)
        assertEquals("committed B", source.readText())
        assertEquals(DiskFileVersion.fromText("committed B"), document.savedDiskVersion)
        assertNotEquals("The accepted baseline changed, so the old recovery metadata must be replaced",
            checkpointBeforeAcknowledgement, state.recoveryCheckpointSignature(listOf("main.txt"), "main.txt"))
    }

    @Test(timeout = 15_000)
    fun repeatedCompleteCaptureAndSnapshotWithTheSameTokenDoNotInvalidateCheckpoint() = runBlocking {
        val root = temp.newFolder()
        File(root, "main.txt").writeText("A")
        val state = EditorWorkspaceState()
        val document = state.document("main.txt")
        document.ensureLoaded(FileRepository(root)); document.capture("dirty B")
        val token = document.savedDiskVersion
        val signature = state.recoveryCheckpointSignature(listOf("main.txt"), "main.txt")
        repeat(3) {
            document.capture(document.content)
            state.recoverySnapshot(listOf("main.txt"), "main.txt")
            assertSame(token, document.savedDiskVersion)
            assertEquals(signature, state.recoveryCheckpointSignature(listOf("main.txt"), "main.txt"))
        }
    }
}
