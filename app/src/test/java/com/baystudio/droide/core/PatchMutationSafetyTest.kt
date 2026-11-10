package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PatchMutationSafetyTest {
    private suspend fun project(block: suspend (File) -> Unit) {
        val root = Files.createTempDirectory("droide-patch-cas-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    private fun update(path: String, moveTo: String? = null) = PatchTool.Op.Update(path, "before", "after", moveTo)

    @Test fun unrelatedPreflightSyncReceiptCannotClaimAnUnpublishedPatchSucceeded() = runBlocking {
        for ((receiptPath, receiptBytes) in listOf("other" to 5L, "a" to 99L)) project { root ->
            root.resolve("a").writeText("before")
            val files = FileRepository(root, beforeMutation = {
                throw ProjectFileSyncException(receiptPath, receiptBytes, IOException("Unrelated sync"))
            })
            val history = EditHistory(files)
            assertTrue(runCatching { PatchTool.apply(listOf(update("a")), files, history) }.isFailure)
            assertEquals("before", root.resolve("a").readText())
            assertEquals(0, history.depth())
            assertEquals("before", root.resolve(".droide/backups").listFiles()!!.single().readText())
        }
    }

    @Test fun unrelatedRollbackSyncReceiptKeepsTheCommittedPatchAndItsUndoToken() = runBlocking {
        for ((receiptPath, receiptBytes) in listOf("other" to 6L, "a" to 99L)) project { root ->
            root.resolve("a").writeText("before")
            var failRestore = true
            val files = FileRepository(root, beforeMutation = { mutation ->
                if (mutation is FileRepository.Mutation.Write) {
                    if (mutation.path == "b") throw IOException("Cannot create b")
                    if (failRestore && mutation.path == "a" && mutation.text == "before")
                        throw ProjectFileSyncException(receiptPath, receiptBytes, IOException("Unrelated sync"))
                }
            })
            val history = EditHistory(files)
            val failure = runCatching {
                PatchTool.apply(listOf(update("a"), PatchTool.Op.Add("b", "added")), files, history)
            }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("partial rollback"))
            assertEquals("after", root.resolve("a").readText())
            assertEquals(1, history.depth())
            failRestore = false
            assertTrue(history.undo().startsWith("Undone:"))
            assertEquals("before", root.resolve("a").readText())
        }
    }

    @Test fun committedWritesWithPendingSyncKeepSuccessfulHistoryAndCanUndo() = runBlocking {
        project { root ->
            root.resolve("a").writeText("before")
            var syncOffline = true
            val files = FileRepository(root, onMutation = {
                if (syncOffline && it is FileRepository.Mutation.Write) throw IOException("Sync offline")
            })
            val history = EditHistory(files)
            val result = PatchTool.apply(listOf(update("a"), PatchTool.Op.Add("b", "added")), files, history)
            assertTrue(result, result.startsWith("OK:"))
            assertTrue(result, result.contains("synchronization pending"))
            assertEquals("after", root.resolve("a").readText())
            assertEquals("added", root.resolve("b").readText())
            assertEquals(1, history.depth())
            syncOffline = false
            assertTrue(history.undo().startsWith("Undone:"))
            assertEquals("before", root.resolve("a").readText())
            assertFalse(root.resolve("b").exists())
        }
    }

    @Test fun lateAddCollisionDoesNotDeleteTheForeignFileDuringRollback() = runBlocking {
        project { root ->
            root.resolve("a").writeText("before")
            val files = FileRepository(root, beforeMutation = {
                if (it is FileRepository.Mutation.Write && it.path == "b") root.resolve("b").writeText("terminal owns b")
            })
            val history = EditHistory(files)
            val failure = runCatching { PatchTool.apply(listOf(update("a"), PatchTool.Op.Add("b", "agent b")), files, history) }.exceptionOrNull()
            assertNotNull(failure)
            assertEquals("before", root.resolve("a").readText())
            assertEquals("terminal owns b", root.resolve("b").readText())
            assertEquals(0, history.depth())
        }
    }

    @Test fun rollbackCommittedLocallyWithPendingSyncIsNotRetainedAsAnUnfinishedUndo() = runBlocking {
        project { root ->
            root.resolve("a").writeText("before")
            val files = FileRepository(root, beforeMutation = {
                if (it is FileRepository.Mutation.Write && it.path == "b") throw IOException("Cannot create b")
            }, onMutation = {
                if (it is FileRepository.Mutation.Write && it.path == "a" && it.text == "before")
                    throw IOException("Rollback saved locally; provider offline")
            })
            val history = EditHistory(files)
            val failure = runCatching { PatchTool.apply(listOf(update("a"), PatchTool.Op.Add("b", "added")), files, history) }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure?.message.orEmpty().contains("1 committed target(s) restored"))
            assertTrue(failure?.message.orEmpty().contains("synchronization pending: a"))
            assertFalse(failure?.message.orEmpty().contains("partial rollback"))
            assertEquals("before", root.resolve("a").readText())
            assertFalse(root.resolve("b").exists())
            assertEquals(0, history.depth())
        }
    }

    @Test fun terminalChangeToAnUnappliedTargetIsNeverOverwrittenByApplyOrRollback() = runBlocking {
        project { root ->
            root.resolve("a").writeText("before"); root.resolve("b").writeText("before")
            val files = FileRepository(root, beforeMutation = {
                if (it is FileRepository.Mutation.Write && it.path == "b") root.resolve("b").writeText("late terminal b")
            })
            val history = EditHistory(files)
            assertTrue(runCatching { PatchTool.apply(listOf(update("a"), update("b")), files, history) }.isFailure)
            assertEquals("before", root.resolve("a").readText())
            assertEquals("late terminal b", root.resolve("b").readText())
        }
    }

    @Test fun rollbackConflictPreservesNewerTerminalWorkAndGuardedUndoBackups() = runBlocking {
        project { root ->
            root.resolve("a").writeText("before")
            val files = FileRepository(root, beforeMutation = {
                if (it is FileRepository.Mutation.Write && it.path == "b") {
                    root.resolve("a").writeText("newer terminal a")
                    throw IOException("b failed before publication")
                }
            })
            val history = EditHistory(files)
            val failure = runCatching { PatchTool.apply(listOf(update("a"), PatchTool.Op.Add("b", "agent b")), files, history) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("partial rollback"))
            assertEquals("newer terminal a", root.resolve("a").readText())
            assertFalse(root.resolve("b").exists())
            assertEquals(1, history.depth())
            assertTrue(history.undo().contains("File changed after Agent edit"))
            assertEquals("newer terminal a", root.resolve("a").readText())
            assertTrue(root.resolve(".droide/backups").listFiles().orEmpty().isNotEmpty())
        }
    }

    @Test fun foreignReplacementDuringFailedSyncIsPreservedWithoutClaimingRollback() = runBlocking {
        project { root ->
            root.resolve("a").writeText("before")
            val files = FileRepository(root, onMutation = {
                if (it is FileRepository.Mutation.Write && it.path == "a") {
                    root.resolve("a").delete()
                    root.resolve("a").writeText("foreign replacement")
                    throw IOException("Sync failed after another writer replaced a")
                }
            })
            val history = EditHistory(files)
            val failure = runCatching { PatchTool.apply(listOf(update("a")), files, history) }.exceptionOrNull()
            assertNotNull(failure)
            assertFalse(failure?.message.orEmpty().startsWith("Patch rolled back"))
            assertTrue(failure?.message.orEmpty().contains("recovery backups retained"))
            assertEquals("foreign replacement", root.resolve("a").readText())
            assertEquals(0, history.depth())
            assertEquals("before", root.resolve(".droide/backups").listFiles()!!.single().readText())
        }
    }

    @Test fun patchMoveDoesNotDeleteSourceChangedAfterDestinationPublication() = runBlocking {
        project { root ->
            root.resolve("a").writeText("before")
            val files = FileRepository(root, onMutation = {
                if (it is FileRepository.Mutation.Write && it.path == "moved") root.resolve("a").writeText("terminal source")
            })
            val history = EditHistory(files)
            assertTrue(runCatching { PatchTool.apply(listOf(update("a", "moved")), files, history) }.isFailure)
            assertEquals("terminal source", root.resolve("a").readText())
            assertFalse(root.resolve("moved").exists())
        }
    }

    @Test fun patchDeleteRechecksTheReviewedVersionAfterAwaitedPreflight() = runBlocking {
        project { root ->
            root.resolve("a").writeText("before")
            val files = FileRepository(root, beforeMutation = {
                if (it is FileRepository.Mutation.Delete) root.resolve("a").writeText("late terminal file")
            })
            assertTrue(runCatching { PatchTool.apply(listOf(PatchTool.Op.Delete("a")), files, EditHistory(files)) }.isFailure)
            assertEquals("late terminal file", root.resolve("a").readText())
        }
    }

    @Test fun workspacePatchRetainsLiveUserChangesWhenLaterDiskFailureCannotBeRolledBack() = runBlocking {
        project { root ->
            root.resolve("a").writeText("disk before")
            val documents = AgentWorkflowDocuments(agentWorkflowSnapshot("a", "before"))
            val files = FileRepository(root, beforeMutation = {
                if (it is FileRepository.Mutation.Write && it.path == "b") {
                    documents.replaceText("a", documents.current.version, "new user buffer", "user typing")
                    throw IOException("b unavailable")
                }
            })
            val history = EditHistory(files, documents)
            val ops = listOf(update("a"), PatchTool.Op.Add("b", "new file"))
            val reviewed = PatchTool.reviewWorkspaceSnapshot(ops, files, documents)
            val failure = runCatching {
                PatchTool.applyWorkspaceReviewed(ops, files, history, documents, agentWorkflowContext(root).lsp, reviewed)
            }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("partial rollback"))
            assertEquals("new user buffer", documents.current.content)
            assertEquals("disk before", root.resolve("a").readText())
            assertFalse(root.resolve("b").exists())
            assertEquals(1, history.depth())
            assertTrue(history.undo().contains("Live document changed"))
        }
    }
}
