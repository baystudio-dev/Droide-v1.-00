package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Independent F150 consumer tests: only exact repository receipts can advance patch history. */
class PatchDeleteReceiptIndependentTest {
    private fun workspace(block: suspend (File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("droide-patch-delete-independent-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    private class Probe(val root: File) : WorkspaceMutationParticipant {
        val deletes = linkedMapOf<String, WorkspaceLocalReceipt>()
        val attempts = linkedMapOf<String, Int>()
        var sync: (FileRepository.Mutation) -> Unit = {}
        var finish: (FileRepository.Mutation) -> Unit = {}
        var returned: (FileRepository.Mutation) -> Unit = {}
        fun stage(path: String): File = root.resolve(requireNotNull(deletes[path]).preparation.relativePath)
        override suspend fun begin(guard: WorkspaceMutationGuard, mutation: FileRepository.Mutation) =
            object : WorkspaceMutationSession(guard, mutation) {
                override val retainLocalOnSyncFailure = true
                override fun localReturned(receipt: WorkspaceLocalReceipt) {
                    if (mutation is FileRepository.Mutation.Delete) {
                        deletes[mutation.path] = receipt
                        attempts[mutation.path] = (attempts[mutation.path] ?: 0) + 1
                    }
                    returned(mutation)
                }
                override suspend fun synchronize() { sync(mutation) }
                override fun finish(outcome: WorkspaceMutationOutcome) { finish(mutation) }
            }
    }
    private fun repository(root: File, probe: Probe, before: (FileRepository.Mutation) -> Unit = {}) =
        FileRepository(root, newFilePublisher = NewProjectFilePublisher.platform, participant = probe, beforeMutation = { before(it) })
    private fun replace(file: File, text: String) {
        val sibling = File(file.parentFile, "replacement-${java.util.UUID.randomUUID()}").apply { writeText(text) }
        Files.move(sibling.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    private fun backups(root: File) = root.resolve(".droide/backups").listFiles().orEmpty()
    private fun assertUnknown(root: File, history: EditHistory, failure: Throwable?) {
        assertNotNull(failure)
        assertTrue(failure?.message.orEmpty(), failure?.message.orEmpty().contains("recovery backups retained"))
        assertEquals(0, history.depth())
        assertEquals("original", backups(root).single().readText())
    }

    @Test fun rollbackRetainedDeleteCompletesWithoutADeadUndoAndLeavesOlderUndoUsable() = workspace { root ->
        val probe = Probe(root).apply { sync = { if (it is FileRepository.Mutation.Delete && it.path == "added") throw IOException("offline") } }
        val files = repository(root, probe) { if (it is FileRepository.Mutation.Write && it.path == "blocked") throw IOException("blocked add") }
        val history = EditHistory(files)
        root.resolve("older").writeText("old")
        val older = history.prepareBatch("older", mapOf("older" to "old"))
        files.writeText("older", "new"); history.commitBatch(older, diskAfter = mapOf("older" to "new"))
        val caught = runCatching { PatchTool.apply(listOf(PatchTool.Op.Add("added", "agent"), PatchTool.Op.Add("blocked", "no")), files, history) }.exceptionOrNull()
        assertTrue(caught?.message.orEmpty(), caught?.message.orEmpty().contains("1 committed target(s) restored"))
        assertFalse(caught?.message.orEmpty(), caught?.message.orEmpty().contains("partial rollback"))
        assertTrue(caught?.message.orEmpty(), caught?.message.orEmpty().contains("synchronization pending: added"))
        assertFalse(root.resolve("added").exists()); assertEquals("agent", probe.stage("added").readText())
        assertEquals(1, probe.attempts["added"]); assertEquals(1, history.depth())
        assertTrue(history.undo().startsWith("Undone:")); assertEquals("old", root.resolve("older").readText())
        assertEquals(0, history.depth()); assertEquals("agent", probe.stage("added").readText())
    }

    @Test fun rollbackCancellationConsumesExactDeleteAndSealsOnlyUnfinishedPriorTarget() = workspace { root ->
        val stopped = CancellationException("stop while rolling back b")
        val probe = Probe(root).apply { sync = { if (it is FileRepository.Mutation.Delete && it.path == "b") throw stopped } }
        val files = repository(root, probe) { if (it is FileRepository.Mutation.Write && it.path == "c") throw IOException("blocked c") }
        val history = EditHistory(files)
        val caught = runCatching { PatchTool.apply(listOf(PatchTool.Op.Add("a", "a data"), PatchTool.Op.Add("b", "b data"), PatchTool.Op.Add("c", "c data")), files, history) }.exceptionOrNull()
        assertSame(stopped, caught)
        assertTrue(caught!!.suppressed.any { it.message == "blocked c" })
        assertFalse(root.resolve("b").exists()); assertEquals("b data", probe.stage("b").readText())
        assertEquals("a data", root.resolve("a").readText()); assertEquals(1, history.depth())
        assertFalse(probe.attempts.containsKey("a")); assertEquals(1, probe.attempts["b"])
        probe.sync = {}
        assertTrue(history.undo().startsWith("Undone:")); assertFalse(root.resolve("a").exists())
        assertEquals(0, history.depth()); assertEquals("b data", probe.stage("b").readText())
    }

    @Test fun forwardCancellationRetainsGuardedUndoWhenRestorationIsRejected() = workspace { root ->
        root.resolve("source").writeText("original")
        val stopped = CancellationException("stop after owned source delete")
        val probe = Probe(root).apply { sync = { if (it is FileRepository.Mutation.Delete) throw stopped } }
        var rejectRestore = true
        val files = repository(root, probe) { if (rejectRestore && it is FileRepository.Mutation.Write) throw IOException("restore admission unavailable") }
        val history = EditHistory(files)
        assertSame(stopped, runCatching { PatchTool.apply(listOf(PatchTool.Op.Delete("source")), files, history) }.exceptionOrNull())
        assertFalse(root.resolve("source").exists()); assertEquals("original", probe.stage("source").readText())
        assertEquals(1, history.depth()); assertEquals(1, probe.attempts["source"])
        rejectRestore = false; probe.sync = {}
        assertTrue(history.undo().startsWith("Undone:")); assertEquals("original", root.resolve("source").readText())
        assertEquals(0, history.depth()); assertEquals("original", probe.stage("source").readText())
    }

    @Test(timeout = 15_000) fun cancellationAtSuccessfulIoReturnDoesNotLoseTheCompletedDelete() = workspace { root ->
        withTimeout(8_000) {
            root.resolve("source").writeText("original")
            val owner = Job(); val stopped = CancellationException("cancel successful delete return")
            val probe = Probe(root).apply { finish = { if (it is FileRepository.Mutation.Delete) owner.cancel(stopped) } }
            var rejectRestore = true
            val files = repository(root, probe) { if (rejectRestore && it is FileRepository.Mutation.Write) throw IOException("restore blocked") }
            val history = EditHistory(files)
            val work = async(owner) { PatchTool.apply(listOf(PatchTool.Op.Delete("source")), files, history) }
            try {
                val caught = runCatching { work.await() }.exceptionOrNull(); work.join()
                assertTrue(generateSequence(caught) { it.cause }.any { it === stopped })
                assertFalse(root.resolve("source").exists()); assertFalse(probe.stage("source").exists())
                assertEquals(1, history.depth()); assertEquals("original", backups(root).single().readText())
                rejectRestore = false; probe.finish = {}
                assertTrue(history.undo().startsWith("Undone:")); assertEquals("original", root.resolve("source").readText())
            } finally { owner.cancel(); withContext(NonCancellable) { work.cancelAndJoin() } }
        }
    }

    @Test fun sameBytesStageReplacementDuringSyncOrFinishCannotBecomeSuccessfulPatch() = workspace { root ->
        repeat(2) { phase ->
            root.resolve("source").writeText("original")
            val probe = Probe(root)
            probe.sync = { if (it is FileRepository.Mutation.Delete) {
                if (phase == 0) replace(probe.stage("source"), "original")
                throw IOException("offline")
            } }
            probe.finish = { if (phase == 1 && it is FileRepository.Mutation.Delete) replace(probe.stage("source"), "original") }
            val files = repository(root, probe); val history = EditHistory(files)
            val caught = runCatching { PatchTool.apply(listOf(PatchTool.Op.Delete("source")), files, history) }.exceptionOrNull()
            assertUnknown(root, history, caught); assertFalse(root.resolve("source").exists())
            assertEquals("original", probe.stage("source").readText())
            // Reset only this synthetic fixture's retained backups between independent phases.
            backups(root).forEach { assertTrue(it.delete()) }
        }
    }

    @Test fun successfulRepositoryReturnWithSourceRecreatedAtFinishIsNotKnownDeletion() = workspace { root ->
        root.resolve("source").writeText("original")
        val probe = Probe(root).apply { finish = { if (it is FileRepository.Mutation.Delete) root.resolve("source").writeText("original") } }
        val files = repository(root, probe); val history = EditHistory(files)
        val caught = runCatching { PatchTool.apply(listOf(PatchTool.Op.Delete("source")), files, history) }.exceptionOrNull()
        assertUnknown(root, history, caught); assertEquals("original", root.resolve("source").readText())
    }

    @Test fun sourceAbsenceBeforeOwnDeleteDoesNotFabricateHistory() = workspace { root ->
        root.resolve("source").writeText("original")
        val probe = Probe(root)
        val files = repository(root, probe) { if (it is FileRepository.Mutation.Delete) {
            Files.move(root.resolve("source").toPath(), root.resolve("external-retained").toPath())
            throw IOException("not our deletion")
        } }
        val history = EditHistory(files)
        assertUnknown(root, history, runCatching { PatchTool.apply(listOf(PatchTool.Op.Delete("source")), files, history) }.exceptionOrNull())
        assertEquals("original", root.resolve("external-retained").readText()); assertTrue(probe.deletes.isEmpty())
    }

    @Test fun moveThatThrowsAfterMovingHasNoCompletedReceipt() = workspace { root ->
        root.resolve("source").writeText("original")
        val probe = Probe(root)
        val files = FileRepository(root, newFilePublisher = NewProjectFilePublisher.platform, participant = probe, pathMover = WorkspacePathMove { from, to ->
            Files.move(from.toPath(), to.toPath()); throw IOException("lost local return")
        })
        val history = EditHistory(files)
        assertUnknown(root, history, runCatching { PatchTool.apply(listOf(PatchTool.Op.Delete("source")), files, history) }.exceptionOrNull())
        assertFalse(probe.deletes["source"]!!.returned); assertEquals("original", probe.stage("source").readText())
    }

    @Test fun receiptOrFinishFailureCannotBeDowngradedToSyncWarning() = workspace { root ->
        repeat(2) { phase ->
            root.resolve("source").writeText("original")
            val probe = Probe(root).apply {
                sync = { if (it is FileRepository.Mutation.Delete) throw IOException("offline") }
                if (phase == 0) returned = { if (it is FileRepository.Mutation.Delete) throw IOException("receipt rejected") }
                else finish = { if (it is FileRepository.Mutation.Delete) throw IOException("finish rejected") }
            }
            val files = repository(root, probe); val history = EditHistory(files)
            assertUnknown(root, history, runCatching { PatchTool.apply(listOf(PatchTool.Op.Delete("source")), files, history) }.exceptionOrNull())
            assertEquals("original", probe.stage("source").readText())
            backups(root).forEach { assertTrue(it.delete()) }
        }
    }

    @Test fun fatalDeleteFailureRemainsFatalAndRetainsRecoveryWithoutInventingUndo() = workspace { root ->
        root.resolve("source").writeText("original")
        val fatal = AssertionError("fatal provider state")
        val probe = Probe(root).apply { sync = { if (it is FileRepository.Mutation.Delete) throw fatal } }
        val files = repository(root, probe); val history = EditHistory(files)
        assertSame(fatal, runCatching { PatchTool.apply(listOf(PatchTool.Op.Delete("source")), files, history) }.exceptionOrNull())
        assertEquals(0, history.depth()); assertEquals("original", backups(root).single().readText())
        assertEquals("original", probe.stage("source").readText())
    }

    @Test fun newerSourceAfterKnownMovePreventsRollbackOverwriteAndKeepsBothRecoveryVersions() = workspace { root ->
        root.resolve("source").writeText("original")
        val probe = Probe(root).apply { sync = { if (it is FileRepository.Mutation.Delete && it.path == "source") throw IOException("offline") } }
        val files = repository(root, probe) { if (it is FileRepository.Mutation.Write && it.path == "blocked") {
            root.resolve("source").writeText("new owner data"); throw IOException("blocked add")
        } }
        val history = EditHistory(files)
        val ops = listOf(PatchTool.Op.Update("source", "original", "updated", "destination"), PatchTool.Op.Add("blocked", "no"))
        val caught = runCatching { PatchTool.apply(ops, files, history) }.exceptionOrNull()
        assertNotNull(caught); assertEquals("new owner data", root.resolve("source").readText())
        assertFalse(root.resolve("destination").exists()); assertEquals("original", probe.stage("source").readText())
        assertEquals(1, history.depth()); assertTrue(history.undo().contains("File changed after Agent edit"))
        assertEquals("new owner data", root.resolve("source").readText()); assertEquals("original", probe.stage("source").readText())
    }

    @Test fun ordinaryMixedPatchStillUsesOneUndoAndSyncFailureWithRollbackIsNotSuccess() = workspace { root ->
        root.resolve("delete").writeText("delete original"); root.resolve("move").writeText("before"); root.resolve("edit").writeText("before")
        val files = FileRepository(root); val history = EditHistory(files)
        val ops = listOf(PatchTool.Op.Delete("delete"), PatchTool.Op.Update("move", "before", "after", "destination"), PatchTool.Op.Update("edit", "before", "after", null), PatchTool.Op.Add("added", "new"))
        assertTrue(PatchTool.apply(ops, files, history).startsWith("OK:")); assertEquals(1, history.depth())
        assertTrue(history.undo().startsWith("Undone:")); assertEquals(0, history.depth())
        assertEquals("delete original", root.resolve("delete").readText()); assertEquals("before", root.resolve("move").readText())
        assertEquals("before", root.resolve("edit").readText()); assertFalse(root.resolve("destination").exists()); assertFalse(root.resolve("added").exists())
        var fail = true
        val rollbackFiles = FileRepository(root, onMutation = { if (fail && it is FileRepository.Mutation.Delete) throw IOException("observer offline") })
        val retryHistory = EditHistory(rollbackFiles)
        assertTrue(runCatching { PatchTool.apply(listOf(PatchTool.Op.Delete("delete")), rollbackFiles, retryHistory) }.isFailure)
        assertEquals("delete original", root.resolve("delete").readText()); assertEquals(0, retryHistory.depth())
        fail = false
        assertTrue(PatchTool.apply(listOf(PatchTool.Op.Delete("delete")), rollbackFiles, retryHistory).startsWith("OK:"))
        assertTrue(retryHistory.undo().startsWith("Undone:")); assertEquals("delete original", root.resolve("delete").readText())
    }
}
