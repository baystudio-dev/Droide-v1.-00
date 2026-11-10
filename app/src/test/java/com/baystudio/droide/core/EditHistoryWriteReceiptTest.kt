package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Tiny local-text fixtures only: no terminal, network, provider, or native execution objects. */
class EditHistoryWriteReceiptTest {
    @Test(timeout = 15_000)
    fun foreignMatchingBytesBeforeCancellationRetainUndoAndBackup() = workspace { root ->
        root.resolve(PATH).writeText(BEFORE)
        var undoing = false
        val stopped = CancellationException("before owned undo publication")
        val files = FileRepository(root, beforeMutation = { mutation ->
            if (undoing && mutation is FileRepository.Mutation.Write) {
                root.resolve(PATH).writeText(BEFORE)
                throw stopped
            }
        })
        val history = EditHistory(files)
        val pending = edit(history, files)
        undoing = true

        assertOrigin(stopped, runCatching { history.undo() }.exceptionOrNull())
        assertEquals(BEFORE, root.resolve(PATH).readText())
        assertEquals("An unowned byte match cannot consume undo history", 1, history.depth())
        assertEquals(BEFORE, pending.snapshots.single().backup.readText())
    }

    @Test(timeout = 15_000)
    fun actualPostPublicationCancellationConsumesOnlyOwnedRestoredTarget() = workspace { root ->
        root.resolve("a").writeText("a before")
        root.resolve("b").writeText("b before")
        var undoing = false
        val stopped = CancellationException("after owned undo publication")
        val files = FileRepository(root, onMutation = { mutation ->
            if (undoing && mutation is FileRepository.Mutation.Write) throw stopped
        })
        val history = EditHistory(files)
        val pending = history.prepareBatch("two files", linkedMapOf("a" to "a before", "b" to "b before"))
        files.writeText("a", "a after")
        files.writeText("b", "b after")
        history.commitBatch(pending, diskAfter = mapOf("a" to "a after", "b" to "b after"))
        undoing = true

        assertOrigin(stopped, runCatching { history.undo() }.exceptionOrNull())
        assertEquals("a after", root.resolve("a").readText())
        assertEquals("b before", root.resolve("b").readText())
        assertEquals("a before", pending.snapshots.single { it.path == "a" }.backup.readText())
        assertFalse(pending.snapshots.single { it.path == "b" }.backup.exists())
        assertEquals(1, history.depth())

        undoing = false
        assertTrue(history.undo().startsWith("Undone:"))
        assertEquals("a before", root.resolve("a").readText())
        assertEquals(0, history.depth())
        assertTrue(pending.snapshots.none { it.backup.exists() })
    }

    @Test(timeout = 15_000)
    fun forgedMatchingSyncFailureBeforePublicationRetainsUndoAndBackup() = workspace { root ->
        root.resolve(PATH).writeText(BEFORE)
        var undoing = false
        val files = FileRepository(root, beforeMutation = { mutation ->
            if (undoing && mutation is FileRepository.Mutation.Write) {
                root.resolve(PATH).writeText(BEFORE)
                throw ProjectFileSyncException(PATH, DiskFileVersion.fromText(BEFORE).bytes, IOException("forged receipt"))
            }
        })
        val history = EditHistory(files)
        val pending = edit(history, files)
        undoing = true

        assertFailedWithoutSavedClaim(history.undo())
        assertRetained(root, history, pending)
    }

    @Test(timeout = 15_000)
    fun actualPostPublicationSyncFailureConsumesOwnedRestoredTarget() = workspace { root ->
        root.resolve(PATH).writeText(BEFORE)
        var undoing = false
        var undoWrites = 0
        val files = FileRepository(root, onMutation = { mutation ->
            if (undoing && mutation is FileRepository.Mutation.Write) {
                undoWrites++
                throw IOException("mirror unavailable after owned restore")
            }
        })
        val history = EditHistory(files)
        val pending = edit(history, files)
        undoing = true

        val result = history.undo()
        assertTrue(result, result.startsWith("Undone:"))
        assertTrue(result, result.contains("restored locally; synchronization pending"))
        assertEquals(1, undoWrites)
        assertEquals(BEFORE, root.resolve(PATH).readText())
        assertEquals(0, history.depth())
        assertFalse(pending.snapshots.single().backup.exists())
    }

    @Test(timeout = 15_000)
    fun sameByteForeignReplacementAfterNormalObserverReturnRetainsUndoAndBackup() =
        foreignReplacementAfterPublication(null)

    @Test(timeout = 15_000)
    fun sameByteForeignReplacementAfterSyncFailureRetainsUndoAndBackup() =
        foreignReplacementAfterPublication(IOException("sync failed after foreign replacement"))

    @Test(timeout = 15_000)
    fun sameByteForeignReplacementAfterCancellationRetainsUndoAndBackup() =
        foreignReplacementAfterPublication(CancellationException("cancelled after foreign replacement"))

    @Test(timeout = 15_000)
    fun sameByteForeignReplacementWithForgedSyncReceiptRetainsUndoAndBackup() =
        foreignReplacementAfterPublication(ProjectFileSyncException(PATH,
            DiskFileVersion.fromText(BEFORE).bytes, IOException("forged post-replacement receipt")))

    @Test(timeout = 15_000)
    fun sameByteForeignReplacementAtFinishRetainsUndoAndBackup() = workspace { root ->
        root.resolve(PATH).writeText(BEFORE)
        var undoing = false
        val participant = WorkspaceMutationParticipant { guard, mutation ->
            object : WorkspaceMutationSession(guard, mutation) {
                override fun finish(outcome: WorkspaceMutationOutcome) {
                    if (undoing && mutation is FileRepository.Mutation.Write) replaceWithForeignMatchingBytes(root)
                }
            }
        }
        val files = FileRepository(root, newFilePublisher = NewProjectFilePublisher.platform, participant = participant)
        val history = EditHistory(files)
        val pending = edit(history, files)
        undoing = true

        assertFailedWithoutSavedClaim(history.undo())
        assertRetained(root, history, pending)
    }

    private fun foreignReplacementAfterPublication(failure: Exception?) = workspace { root ->
        root.resolve(PATH).writeText(BEFORE)
        var undoing = false
        val files = FileRepository(root, onMutation = { mutation ->
            if (undoing && mutation is FileRepository.Mutation.Write) {
                replaceWithForeignMatchingBytes(root)
                failure?.let { throw it }
            }
        })
        val history = EditHistory(files)
        val pending = edit(history, files)
        undoing = true

        if (failure is CancellationException) assertOrigin(failure, runCatching { history.undo() }.exceptionOrNull())
        else assertFailedWithoutSavedClaim(history.undo())
        assertRetained(root, history, pending)
    }

    private fun replaceWithForeignMatchingBytes(root: File) {
        val replacement = root.resolve("foreign-stage").apply { writeText(BEFORE) }
        Files.move(replacement.toPath(), root.resolve(PATH).toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun assertFailedWithoutSavedClaim(result: String) {
        assertTrue(result, result.startsWith("Undo failed:"))
        assertFalse(result, result.contains("saved locally", ignoreCase = true))
        assertFalse(result, result.contains("restored locally", ignoreCase = true))
        assertFalse(result, result.contains("synchronization pending", ignoreCase = true))
    }

    private fun assertRetained(root: File, history: EditHistory, pending: EditHistory.PendingBatch) {
        assertEquals(BEFORE, root.resolve(PATH).readText())
        assertEquals(1, history.depth())
        assertEquals(BEFORE, pending.snapshots.single().backup.readText())
    }

    private suspend fun edit(history: EditHistory, files: FileRepository): EditHistory.PendingBatch {
        val pending = history.prepareBatch("edit $PATH", mapOf(PATH to BEFORE))
        files.writeText(PATH, AFTER)
        history.commitBatch(pending, diskAfter = mapOf(PATH to AFTER))
        return pending
    }

    private fun assertOrigin(expected: Throwable, actual: Throwable?) {
        assertTrue("Cancellation must propagate", actual is CancellationException)
        // Coroutine stack-trace recovery may copy cancellation, preserving its original as a cause.
        assertTrue("The originating cancellation must remain in the cause chain",
            generateSequence(actual) { it.cause }.any { it === expected })
    }

    private fun workspace(body: suspend (File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("droide-undo-write-receipt-").toFile()
        try { withTimeout(8_000) { body(root) } } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    companion object {
        private const val PATH = "main.txt"
        private const val BEFORE = "reviewed before"
        private const val AFTER = "agent after"
    }
}
