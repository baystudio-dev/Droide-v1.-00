package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class EditHistorySafetyTest {
    private suspend fun <T> project(block: suspend (File) -> T): T {
        val root = Files.createTempDirectory("droide-safe-undo-").toFile()
        return try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private suspend fun edit(history: EditHistory, files: FileRepository, path: String, after: String) {
        val before = if (files.exists(path)) files.readTextForEdit(path) else null
        history.snapshot(path, before)
        files.writeText(path, after)
        history.commitLast(mapOf(path to after))
    }

    @Test fun unrelatedSyncReceiptCannotConsumeAnUnpublishedUndo() = runBlocking {
        for ((receiptPath, receiptBytes) in listOf("other" to 6L, "a" to 99L)) project { root ->
            root.resolve("a").writeText("before")
            var undoing = false
            val files = FileRepository(root, beforeMutation = {
                if (undoing) throw ProjectFileSyncException(receiptPath, receiptBytes, java.io.IOException("Unrelated sync"))
            })
            val history = EditHistory(files)
            edit(history, files, "a", "after")
            undoing = true
            assertTrue(history.undo().startsWith("Undo failed:"))
            assertEquals("after", root.resolve("a").readText())
            assertEquals(1, history.depth())
            undoing = false
            assertTrue(history.undo().startsWith("Undone:"))
            assertEquals("before", root.resolve("a").readText())
        }
    }

    @Test fun cancelledSyncAfterCanonicalRestoreConsumesOnlyTheCompletedUndoTarget() = runBlocking {
        project { root ->
            var undoing = false
            val cancellation = CancellationException("Stop during synchronization")
            val files = FileRepository(root, onMutation = {
                if (undoing && it is FileRepository.Mutation.Write) throw cancellation
            })
            root.resolve("a").writeText("a before"); root.resolve("b").writeText("b before")
            val history = EditHistory(files)
            val pending = history.prepareBatch("two files", linkedMapOf("a" to "a before", "b" to "b before"))
            files.writeText("a", "a after"); files.writeText("b", "b after")
            history.commitBatch(pending, diskAfter = mapOf("a" to "a after", "b" to "b after"))
            undoing = true
            assertOriginatingFailure(cancellation, runCatching { history.undo() }.exceptionOrNull())
            assertEquals("a after", root.resolve("a").readText())
            assertEquals("b before", root.resolve("b").readText())
            assertTrue(pending.snapshots.single { it.path == "a" }.backup.exists())
            assertFalse(pending.snapshots.single { it.path == "b" }.backup.exists())
            assertEquals(1, history.depth())
            undoing = false
            assertTrue(history.undo().startsWith("Undone:"))
            assertEquals("a before", root.resolve("a").readText())
            assertEquals(0, history.depth())
        }
    }

    @Test fun cancellationBeforeRestoreKeepsTheUndoTokenAndBackup() = runBlocking {
        project { root ->
            var undoing = false
            val files = FileRepository(root, beforeMutation = {
                if (undoing) throw CancellationException("Stop before publication")
            })
            root.resolve("a").writeText("before")
            val history = EditHistory(files)
            edit(history, files, "a", "after")
            undoing = true
            assertTrue(runCatching { history.undo() }.exceptionOrNull() is CancellationException)
            assertEquals("after", root.resolve("a").readText())
            assertEquals(1, history.depth())
            assertEquals("before", root.resolve(".droide/backups").listFiles()!!.single().readText())
        }
    }

    @Test fun cancelledSyncWithForeignReplacementNeverConsumesRecoveryHistory() = runBlocking {
        project { root ->
            var undoing = false
            val files = FileRepository(root, onMutation = {
                if (undoing && it is FileRepository.Mutation.Write) {
                    root.resolve("a").writeText("newer terminal content")
                    throw CancellationException("Stop after foreign change")
                }
            })
            root.resolve("a").writeText("before")
            val history = EditHistory(files)
            edit(history, files, "a", "after")
            undoing = true
            assertTrue(runCatching { history.undo() }.exceptionOrNull() is CancellationException)
            assertEquals("newer terminal content", root.resolve("a").readText())
            assertEquals(1, history.depth())
            assertEquals("before", root.resolve(".droide/backups").listFiles()!!.single().readText())
        }
    }

    @Test fun openingNewHistoryPreservesExistingAndOtherLiveSessionsBackups() = runBlocking {
        project { root ->
            val recovery = root.resolve(".droide/backups/recovery.bak")
            recovery.parentFile.mkdirs()
            recovery.writeText("user recovery data")
            root.resolve("a").writeText("before")
            val files = FileRepository(root)
            val first = EditHistory(files)
            edit(first, files, "a", "after")
            val firstBackups = recovery.parentFile.listFiles()!!.toList()
            val second = EditHistory(FileRepository(root))
            val pending = second.prepareLive("b", "other edit")
            assertEquals("user recovery data", recovery.readText())
            assertTrue(firstBackups.all { it.isFile })
            second.discardLive(pending)
            assertTrue(first.undo().startsWith("Undone:"))
            assertEquals("before", root.resolve("a").readText())
            assertEquals("user recovery data", recovery.readText())
        }
    }

    @Test fun diskUndoRefusesSameLengthUserChangeEvenWhenTimestampIsUnchanged() = runBlocking {
        project { root ->
            root.resolve("a").writeText("old")
            val files = FileRepository(root)
            val history = EditHistory(files)
            edit(history, files, "a", "new")
            val stamp = root.resolve("a").lastModified()
            root.resolve("a").writeText("usr")
            root.resolve("a").setLastModified(stamp)
            val backups = root.resolve(".droide/backups").listFiles()!!.toList()
            assertTrue(history.undo().contains("File changed after Agent edit"))
            assertEquals("usr", root.resolve("a").readText())
            assertEquals(1, history.depth())
            assertTrue(backups.all { it.isFile })
        }
    }

    @Test fun undoNeverDeletesAgentCreatedFileThatUserSubsequentlyChanged() = runBlocking {
        project { root ->
            val files = FileRepository(root)
            val history = EditHistory(files)
            edit(history, files, "new.kt", "agent")
            root.resolve("new.kt").writeText("user owns this now")
            assertTrue(history.undo().startsWith("Undo failed:"))
            assertEquals("user owns this now", root.resolve("new.kt").readText())
        }
    }

    @Test fun undoDeletionRefusesRecreatedFileButRestoresWhenStillAbsent() = runBlocking {
        project { root ->
            val files = FileRepository(root)
            val history = EditHistory(files)
            root.resolve("a").writeText("before deletion")
            val pending = history.prepareBatch("delete a", mapOf("a" to "before deletion"))
            files.delete("a")
            history.commitBatch(pending, diskAfter = mapOf("a" to null))
            root.resolve("a").writeText("recreated by user")
            assertTrue(history.undo().startsWith("Undo failed:"))
            assertEquals("recreated by user", root.resolve("a").readText())
            root.resolve("a").delete()
            assertTrue(history.undo().startsWith("Undone:"))
            assertEquals("before deletion", root.resolve("a").readText())
        }
    }

    @Test fun committedUndoWithPendingSyncConsumesItsHistoryWithoutReplayingTheRestore() = runBlocking {
        project { root ->
            var failSync = false
            var restores = 0
            val files = FileRepository(root, onMutation = { mutation ->
                if (failSync && mutation is FileRepository.Mutation.Write) {
                    restores++
                    throw java.io.IOException("Provider unavailable after local restore")
                }
            })
            root.resolve("a").writeText("original")
            val history = EditHistory(files)
            edit(history, files, "a", "agent change")
            failSync = true
            val result = history.undo()
            assertTrue(result, result.startsWith("Undone:"))
            assertTrue(result, result.contains("synchronization pending"))
            assertEquals("original", root.resolve("a").readText())
            assertEquals(0, history.depth())
            assertTrue(root.resolve(".droide/backups").listFiles().orEmpty().isEmpty())
            history.undo()
            assertEquals(1, restores)
        }
    }

    @Test fun locallyRestoredTargetIsRemovedFromPartialUndoEvenWhenItsSyncFailed() = runBlocking {
        project { root ->
            var undoing = false
            val files = FileRepository(root, beforeMutation = { mutation ->
                if (undoing && mutation is FileRepository.Mutation.Write && mutation.path == "a")
                    root.resolve("a").writeText("new terminal a")
            }, onMutation = { mutation ->
                if (undoing && mutation is FileRepository.Mutation.Write && mutation.path == "b")
                    throw java.io.IOException("Sync offline")
            })
            root.resolve("a").writeText("a before"); root.resolve("b").writeText("b before")
            val history = EditHistory(files)
            val pending = history.prepareBatch("two files", linkedMapOf("a" to "a before", "b" to "b before"))
            files.writeText("a", "a after"); files.writeText("b", "b after")
            history.commitBatch(pending, diskAfter = mapOf("a" to "a after", "b" to "b after"))
            undoing = true
            val result = history.undo()
            assertTrue(result, result.contains("1 file(s) already restored"))
            assertTrue(result, result.contains("synchronization pending: b"))
            assertEquals("new terminal a", root.resolve("a").readText())
            assertEquals("b before", root.resolve("b").readText())
            assertTrue(pending.snapshots.single { it.path == "a" }.backup.exists())
            assertFalse(pending.snapshots.single { it.path == "b" }.backup.exists())
            assertEquals(1, history.depth())
        }
    }

    @Test fun batchPreflightConflictDoesNotPartiallyUndoOtherFiles() = runBlocking {
        project { root ->
            val files = FileRepository(root)
            root.resolve("a").writeText("a before")
            root.resolve("b").writeText("b before")
            val history = EditHistory(files)
            val pending = history.prepareBatch("two files", linkedMapOf("a" to "a before", "b" to "b before"))
            files.writeText("a", "a after"); files.writeText("b", "b after")
            history.commitBatch(pending, diskAfter = mapOf("a" to "a after", "b" to "b after"))
            root.resolve("a").writeText("a user")
            assertTrue(history.undo().startsWith("Undo failed:"))
            assertEquals("a user", root.resolve("a").readText())
            assertEquals("b after", root.resolve("b").readText())
            assertTrue(pending.snapshots.all { it.backup.isFile })
        }
    }

    @Test fun expectedAfterPayloadCannotBlessExternalChangeBetweenWriteAndHistoryCommit() = runBlocking {
        project { root ->
            val files = FileRepository(root)
            root.resolve("a").writeText("before")
            val history = EditHistory(files)
            history.snapshot("a", "before")
            files.writeText("a", "agent")
            root.resolve("a").writeText("terminal later")
            history.commitLast(mapOf("a" to "agent"))
            assertTrue(history.undo().startsWith("Undo failed:"))
            assertEquals("terminal later", root.resolve("a").readText())
        }
    }

    @Test fun suspendedValidationRacePreservesNewerDataBeforeUndoWrite() = runBlocking {
        project { root ->
            var undoing = false
            val files = FileRepository(root, beforeMutation = { mutation ->
                if (undoing && mutation is FileRepository.Mutation.Write) root.resolve("a").writeText("late user")
            })
            root.resolve("a").writeText("before")
            val history = EditHistory(files)
            edit(history, files, "a", "agent")
            undoing = true
            assertTrue(history.undo().startsWith("Undo failed:"))
            assertEquals("late user", root.resolve("a").readText())
            assertEquals(1, history.depth())
            assertEquals(1, root.resolve(".droide/backups").listFiles()!!.size)
        }
    }

    @Test fun suspendedValidationRacePreservesUserDataBeforeUndoDelete() = runBlocking {
        project { root ->
            var undoing = false
            val files = FileRepository(root, beforeMutation = { mutation ->
                if (undoing && mutation is FileRepository.Mutation.Delete) root.resolve("a").writeText("late user")
            })
            val history = EditHistory(files)
            edit(history, files, "a", "agent")
            undoing = true
            assertTrue(history.undo().startsWith("Undo failed:"))
            assertEquals("late user", root.resolve("a").readText())
            assertEquals(1, history.depth())
        }
    }

    @Test fun lateBatchConflictRetainsOnlyUnfinishedUndoAndCanRetrySafely() = runBlocking {
        project { root ->
            var undoing = false
            val files = FileRepository(root, beforeMutation = { mutation ->
                if (undoing && mutation is FileRepository.Mutation.Write && mutation.path == "b")
                    root.resolve("a").writeText("late user a")
            })
            root.resolve("a").writeText("a before"); root.resolve("b").writeText("b before")
            val history = EditHistory(files)
            val pending = history.prepareBatch("two files", linkedMapOf("a" to "a before", "b" to "b before"))
            files.writeText("a", "a after"); files.writeText("b", "b after")
            history.commitBatch(pending, diskAfter = mapOf("a" to "a after", "b" to "b after"))
            undoing = true
            assertTrue(history.undo().contains("1 file(s) already restored"))
            assertEquals("late user a", root.resolve("a").readText())
            assertEquals("b before", root.resolve("b").readText())
            assertTrue(pending.snapshots.first { it.path == "a" }.backup.isFile)
            assertFalse(pending.snapshots.first { it.path == "b" }.backup.exists())
            root.resolve("a").writeText("a after")
            undoing = false
            assertTrue(history.undo().startsWith("Undone:"))
            assertEquals("a before", root.resolve("a").readText())
            assertEquals("b before", root.resolve("b").readText())
            assertEquals(0, history.depth())
        }
    }

    @Test fun corruptedBackupPreventsMutationAndIsPreservedForInspection() = runBlocking {
        project { root ->
            val files = FileRepository(root)
            root.resolve("a").writeText("before")
            val history = EditHistory(files)
            edit(history, files, "a", "after")
            val backup = root.resolve(".droide/backups").listFiles()!!.single()
            backup.writeText("tampered")
            assertTrue(history.undo().contains("backup is missing or changed"))
            assertEquals("after", root.resolve("a").readText())
            assertEquals("tampered", backup.readText())
        }
    }

    @Test fun uncommittedSnapshotCannotUndoPendingOrFailedMutation() = runBlocking {
        project { root ->
            val files = FileRepository(root)
            root.resolve("a").writeText("before")
            val history = EditHistory(files)
            history.snapshot("a", "before")
            root.resolve("a").writeText("external")
            assertTrue(history.undo().contains("has not committed"))
            assertEquals("external", root.resolve("a").readText())
        }
    }

    @Test fun diskUndoRefusesUnsavedEditorOpenedAfterAgentEdit() = runBlocking {
        project { root ->
            val files = FileRepository(root)
            root.resolve("a").writeText("before")
            val documents = AgentWorkflowDocuments(agentWorkflowSnapshot("other", "unrelated"))
            val history = EditHistory(files, documents)
            edit(history, files, "a", "after")
            documents.current = agentWorkflowSnapshot("a", "unsaved user")
            assertTrue(history.undo().contains("unsaved editor buffer"))
            assertEquals("after", root.resolve("a").readText())
            assertEquals("unsaved user", documents.current.content)
        }
    }

    @Test fun liveUserChangeAfterAgentEditRemainsCasProtected() = runBlocking {
        project { root ->
            val files = FileRepository(root)
            val documents = AgentWorkflowDocuments(agentWorkflowSnapshot("a", "before"))
            val history = EditHistory(files, documents)
            val pending = history.prepareLive("a", "before")
            val applied = documents.replaceText("a", documents.current.version, "agent", "agent") as WorkspaceDocumentMutationResult.Applied
            history.commitLive(pending, EditHistory.LiveTransition.from(applied))
            documents.replaceText("a", documents.current.version, "user", "user")
            assertTrue(history.undo().contains("Live document changed"))
            assertEquals("user", documents.current.content)
            assertTrue(pending.backup.isFile)
        }
    }

    @Test fun repositoriesForSameWorkspaceSerializeMutationsThroughAwaitedHooks() = runBlocking {
        project { root ->
            val firstEntered = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            val first = FileRepository(root, beforeMutation = {
                firstEntered.complete(Unit)
                releaseFirst.await()
                synchronized(order) { order += "first" }
            })
            val second = FileRepository(root, beforeMutation = { synchronized(order) { order += "second" } })
            val firstWrite = async { first.writeText("a", "first") }
            withTimeout(5_000) { firstEntered.await() }
            val secondStarted = CompletableDeferred<Unit>()
            val secondWrite = async { secondStarted.complete(Unit); second.writeText("a", "second") }
            secondStarted.await()
            assertTrue(synchronized(order) { order.isEmpty() })
            releaseFirst.complete(Unit)
            withTimeout(5_000) { firstWrite.await(); secondWrite.await() }
            assertEquals(listOf("first", "second"), order)
            assertEquals("second", root.resolve("a").readText())
        }
    }
}
