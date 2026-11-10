package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EditHistoryDeleteReceiptTest {
    private suspend fun created(history: EditHistory, files: FileRepository, path: String = "new.txt"): EditHistory.PendingBatch {
        val pending = history.prepareBatch("create $path", mapOf(path to null))
        files.writeText(path, "agent")
        history.commitBatch(pending, diskAfter = mapOf(path to "agent"))
        return pending
    }
    private fun <T> workspace(block: suspend (File) -> T): T = runBlocking {
        val root = Files.createTempDirectory("droide-undo-delete-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun completedDeleteSyncFailureConsumesOnlyNewFileAndUnblocksOlderUndo() = workspace { root ->
        var deleting = false
        var receipt: WorkspaceLocalReceipt? = null
        var attempts = 0
        val participant = WorkspaceMutationParticipant { guard, mutation ->
            object : WorkspaceMutationSession(guard, mutation) {
                override val retainLocalOnSyncFailure = true
                override fun localReturned(value: WorkspaceLocalReceipt) { if (mutation is FileRepository.Mutation.Delete) receipt = value }
                override suspend fun synchronize() {
                    if (deleting && mutation is FileRepository.Mutation.Delete) { attempts++; throw IOException("provider unavailable") }
                }
            }
        }
        val files = FileRepository(root, newFilePublisher = NewProjectFilePublisher.platform, participant = participant)
        val history = EditHistory(files)
        root.resolve("old.txt").writeText("before")
        val older = history.prepareBatch("older edit", mapOf("old.txt" to "before"))
        files.writeText("old.txt", "after"); history.commitBatch(older, diskAfter = mapOf("old.txt" to "after"))
        val pending = created(history, files)
        deleting = true
        val result = history.undo()
        assertTrue(result, result.startsWith("Undone:"))
        assertTrue(result, result.contains("synchronization pending"))
        assertFalse(root.resolve("new.txt").exists()); assertEquals(1, history.depth()); assertEquals(1, attempts)
        assertFalse(pending.snapshots.single().backup.exists())
        val retained = root.resolve(receipt!!.preparation.relativePath)
        assertEquals("agent", retained.readText())
        deleting = false
        assertTrue(history.undo().startsWith("Undone:"))
        assertEquals("before", root.resolve("old.txt").readText()); assertEquals(0, history.depth())
        assertEquals("agent", retained.readText())
    }

    @Test fun completedDeleteCancellationConsumesOnlyOwnedTargetBeforePropagating() = workspace { root ->
        val cancelled = CancellationException("cancel after deletion")
        var deleting = false
        var receipt: WorkspaceLocalReceipt? = null
        val files = FileRepository(root, newFilePublisher = NewProjectFilePublisher.platform,
            participant = WorkspaceMutationParticipant { guard, mutation -> object : WorkspaceMutationSession(guard, mutation) {
                override val retainLocalOnSyncFailure = true
                override fun localReturned(value: WorkspaceLocalReceipt) { if (mutation is FileRepository.Mutation.Delete) receipt = value }
                override suspend fun synchronize() { if (deleting && mutation is FileRepository.Mutation.Delete) throw cancelled }
            } })
        val history = EditHistory(files)
        val pending = history.prepareBatch("two creates", linkedMapOf("a" to null, "b" to null))
        files.writeText("a", "agent"); files.writeText("b", "agent")
        history.commitBatch(pending, diskAfter = mapOf("a" to "agent", "b" to "agent"))
        deleting = true
        val caught = runCatching { history.undo() }.exceptionOrNull()
        assertTrue(caught is CancellationException); assertEquals(cancelled.message, caught?.message)
        assertFalse(root.resolve("b").exists()); assertTrue(root.resolve("a").exists())
        assertFalse(pending.snapshots.single { it.path == "b" }.backup.exists())
        assertTrue(pending.snapshots.single { it.path == "a" }.backup.exists())
        assertEquals("agent", root.resolve(receipt!!.preparation.relativePath).readText())
        deleting = false
        assertTrue(history.undo().startsWith("Undone:")); assertEquals(0, history.depth())
    }
    private class Probe(val root: File) : WorkspaceMutationParticipant {
        var receipt: WorkspaceLocalReceipt? = null
        var retained = true
        var keepStage = false
        var selected: String? = null
        var before: () -> Unit = {}
        var prepare: () -> Unit = {}
        var returned: () -> Unit = {}
        var sync: () -> Unit = { throw IOException("sync unavailable") }
        var finish: () -> Unit = {}
        var attempts = 0
        var finishes = 0
        val stage: File get() = root.resolve(requireNotNull(receipt).preparation.relativePath)
        override suspend fun begin(guard: WorkspaceMutationGuard, mutation: FileRepository.Mutation) =
            object : WorkspaceMutationSession(guard, mutation) {
                override val retainLocalOnSyncFailure: Boolean get() = retained
                override suspend fun prepareDeleteStage(): String? = selected?.also { root.resolve(it).parentFile!!.mkdirs() }
                override suspend fun prepareLocal(preparation: WorkspaceLocalPreparation) { prepare() }
                override fun beforeLocalAttempt(preparation: WorkspaceLocalPreparation) { attempts++; before() }
                override fun localReturned(value: WorkspaceLocalReceipt) { receipt = value; returned() }
                override suspend fun synchronize() { sync() }
                override fun retainsDeleteStage(receipt: WorkspaceLocalReceipt): Boolean = keepStage
                override fun finish(outcome: WorkspaceMutationOutcome) { finishes++; finish() }
            }
    }
    private fun repository(root: File, probe: Probe, mover: WorkspacePathMove = WorkspacePathMove.platform) =
        FileRepository(root, newFilePublisher = NewProjectFilePublisher.platform, pathMover = mover, participant = probe)
    private suspend fun recorded(files: FileRepository): Pair<EditHistory, EditHistory.PendingBatch> {
        val history = EditHistory(files)
        val pending = history.prepareBatch("create new", mapOf("new.txt" to null))
        files.root.resolve("new.txt").writeText("agent")
        history.commitBatch(pending, diskAfter = mapOf("new.txt" to "agent"))
        return history to pending
    }
    private fun assertKept(history: EditHistory, pending: EditHistory.PendingBatch, result: String) {
        assertTrue(result, result.startsWith("Undo failed:"))
        assertFalse(result, result.contains("synchronization pending"))
        assertEquals(1, history.depth()); assertTrue(pending.snapshots.single().backup.exists())
    }
    private fun replace(file: File, text: String) {
        val foreign = File(file.parentFile, "foreign-${java.util.UUID.randomUUID()}").apply { writeText(text) }
        Files.move(foreign.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    @Test fun ordinaryObservedDeleteStillCompletesWithoutCallbacks() = workspace { root ->
        val files = FileRepository(root)
        val (history, pending) = recorded(files)
        assertTrue(history.undo().startsWith("Undone:"))
        assertEquals(0, history.depth()); assertFalse(root.resolve("new.txt").exists())
        assertFalse(pending.snapshots.single().backup.exists())
        assertFalse(root.listFiles()!!.any { it.name.startsWith(".droide-tmp-") })
    }

    @Test fun protectedCustodyStageSurvivesSyncFailureAndCancellation() = workspace { root ->
        for (failure in listOf(IOException("offline"), CancellationException("stop"))) {
            val probe = Probe(root).apply {
                selected = ".droide/saf-delete-custody/${java.util.UUID.randomUUID()}/original"
                sync = { throw failure }
            }
            val (history, pending) = recorded(repository(root, probe))
            val outcome = runCatching { history.undo() }
            if (failure is CancellationException) assertEquals(failure.message, outcome.exceptionOrNull()?.message)
            else assertTrue(outcome.getOrThrow().contains("synchronization pending"))
            assertEquals(0, history.depth()); assertFalse(pending.snapshots.single().backup.exists())
            assertEquals("agent", probe.stage.readText()); assertEquals(1, probe.attempts); assertEquals(1, probe.finishes)
        }
    }

    @Test fun successfulProtectedCustodyIsNotDeletedByObservation() = workspace { root ->
        val probe = Probe(root).apply {
            selected = ".droide/saf-delete-custody/${java.util.UUID.randomUUID()}/original"
            sync = {}; keepStage = true
        }
        val (history, _) = recorded(repository(root, probe))
        assertTrue(history.undo().startsWith("Undone:")); assertEquals("agent", probe.stage.readText())
        assertEquals(0, history.depth())
    }

    @Test fun nonParticipantSyncRollbackKeepsOriginalUndoTarget() = workspace { root ->
        var fail = true
        val files = FileRepository(root, onMutation = { if (fail && it is FileRepository.Mutation.Delete) throw IOException("observer failed") })
        val (history, pending) = recorded(files)
        assertKept(history, pending, history.undo()); assertEquals("agent", root.resolve("new.txt").readText())
        fail = false
        assertTrue(history.undo().startsWith("Undone:")); assertEquals(0, history.depth())
    }

    @Test fun foreignAbsenceBeforeAttemptCannotConsumeHistory() = workspace { root ->
        val files = FileRepository(root, beforeMutation = { if (it is FileRepository.Mutation.Delete) {
            Files.move(root.resolve("new.txt").toPath(), root.resolve("foreign-retained").toPath())
            throw IOException("foreign change before own operation")
        } })
        val (history, pending) = recorded(files)
        assertKept(history, pending, history.undo())
        assertFalse(root.resolve("new.txt").exists()); assertEquals("agent", root.resolve("foreign-retained").readText())
    }

    @Test fun moverThatMovesThenThrowsNeverInventsSuccessfulReceipt() = workspace { root ->
        val probe = Probe(root)
        val mover = WorkspacePathMove { from, to -> Files.move(from.toPath(), to.toPath()); throw IOException("move return lost") }
        val (history, pending) = recorded(repository(root, probe, mover))
        assertKept(history, pending, history.undo())
        assertFalse(root.resolve("new.txt").exists()); assertEquals("agent", probe.stage.readText())
        assertFalse(probe.receipt!!.returned)
    }

    @Test fun recreatedSourceAfterSyncOrFinishCannotConsumeHistory() = workspace { root ->
        repeat(2) { phase ->
            val probe = Probe(root)
            if (phase == 0) probe.sync = { root.resolve("new.txt").writeText("agent"); throw IOException("offline") }
            else { probe.sync = {}; probe.finish = { root.resolve("new.txt").writeText("agent") } }
            val (history, pending) = recorded(repository(root, probe))
            assertKept(history, pending, history.undo()); assertEquals("agent", root.resolve("new.txt").readText())
        }
    }

    @Test fun replacedStageEvenWithMatchingBytesCannotConsumeHistory() = workspace { root ->
        val probe = Probe(root).apply { sync = { replace(stage, "agent"); throw IOException("offline") } }
        val (history, pending) = recorded(repository(root, probe))
        assertKept(history, pending, history.undo()); assertEquals("agent", probe.stage.readText())
    }

    @Test fun sameInodeSameSizeStageDamageIsPreservedBeforeCleanup() = workspace { root ->
        val probe = Probe(root).apply { sync = {
            val stamp = Files.getLastModifiedTime(stage.toPath())
            stage.writeText("other"); Files.setLastModifiedTime(stage.toPath(), stamp)
        } }
        val (history, pending) = recorded(repository(root, probe))
        assertKept(history, pending, history.undo()); assertEquals("other", probe.stage.readText())
    }

    @Test fun sameInodeSourceDamageDuringPreparationNeverDeletesChangedData() = workspace { root ->
        val probe = Probe(root).apply { prepare = {
            val file = root.resolve("new.txt"); val stamp = Files.getLastModifiedTime(file.toPath())
            file.writeText("other"); Files.setLastModifiedTime(file.toPath(), stamp)
        } }
        val (history, pending) = recorded(repository(root, probe))
        assertKept(history, pending, history.undo()); assertEquals("other", root.resolve("new.txt").readText())
    }

    @Test fun missingRetainedStageAndStageSymlinkRemainUnverified() = workspace { root ->
        repeat(2) { mode ->
            val probe = Probe(root).apply { sync = {
                val saved = root.resolve("retained-$mode")
                Files.move(stage.toPath(), saved.toPath())
                if (mode == 1) Files.createSymbolicLink(stage.toPath(), saved.toPath())
                throw IOException("offline")
            } }
            val (history, pending) = recorded(repository(root, probe))
            assertKept(history, pending, history.undo()); assertEquals("agent", root.resolve("retained-$mode").readText())
        }
    }

    @Test fun receiptOrFinishEvidenceFailureNeverBecomesSyncPendingSuccess() = workspace { root ->
        repeat(2) { phase ->
            val probe = Probe(root)
            if (phase == 0) probe.returned = { throw IOException("receipt not persisted") }
            else probe.finish = { throw IOException("finish not persisted") }
            val (history, pending) = recorded(repository(root, probe))
            assertKept(history, pending, history.undo()); assertEquals("agent", probe.stage.readText())
        }
    }

    @Test fun foreignStageAfterSuccessfulCleanupPreventsCompletionClaim() = workspace { root ->
        val probe = Probe(root).apply { sync = {}; finish = { stage.writeText("foreign") } }
        val (history, pending) = recorded(repository(root, probe))
        assertKept(history, pending, history.undo()); assertEquals("foreign", probe.stage.readText())
    }

    @Test fun fatalSynchronizationErrorPropagatesAndKeepsRecovery() = workspace { root ->
        val fatal = AssertionError("fatal provider error")
        val probe = Probe(root).apply { sync = { throw fatal } }
        val (history, pending) = recorded(repository(root, probe))
        assertSame(fatal, runCatching { history.undo() }.exceptionOrNull())
        assertEquals(1, history.depth()); assertTrue(pending.snapshots.single().backup.exists())
        assertEquals("agent", probe.stage.readText())
    }

    @Test fun observedDeleteIsSingleUseAndRepositoryBound() = workspace { root ->
        val files = FileRepository(root); root.resolve("new.txt").writeText("agent")
        val observation = files.prepareDeleteObservation("new.txt", DiskFileVersion.fromText("agent"))
        assertTrue(runCatching { FileRepository(root).deleteFileIfUnchanged(observation) }.isFailure)
        assertTrue(files.deleteFileIfUnchanged(observation)); assertTrue(observation.ownedCurrent)
        root.resolve("new.txt").writeText("new user data")
        assertTrue(runCatching { files.deleteFileIfUnchanged(observation) }.isFailure)
        assertEquals("new user data", root.resolve("new.txt").readText())
    }

    @Test fun protectedStageParentReplacementPreservesBothGenerations() = workspace { root ->
        lateinit var oldParent: File
        val probe = Probe(root).apply {
            selected = ".droide/saf-delete-custody/${java.util.UUID.randomUUID()}/original"
            sync = {
                val parent = stage.parentFile!!
                oldParent = File(parent.parentFile, "retained-original-container")
                Files.move(parent.toPath(), oldParent.toPath()); parent.mkdirs()
                stage.writeText("foreign")
                throw IOException("provider offline")
            }
        }
        val (history, pending) = recorded(repository(root, probe))
        assertKept(history, pending, history.undo())
        assertEquals("agent", oldParent.resolve("original").readText()); assertEquals("foreign", probe.stage.readText())
    }

    @Test fun rootReplacementCannotConsumeUndoFromAnotherWorkspace() = workspace { root ->
        val oldRoot = File(root.parentFile, root.name + "-retained")
        try {
            val probe = Probe(root).apply { sync = {
                Files.move(root.toPath(), oldRoot.toPath()); root.mkdirs()
                root.resolve("new.txt").writeText("new workspace")
                throw IOException("root replaced")
            } }
            val (history, pending) = recorded(repository(root, probe))
            val relativeBackup = root.toPath().relativize(pending.snapshots.single().backup.toPath()).toString()
            val result = history.undo()
            assertTrue(result.startsWith("Undo failed:")); assertEquals(1, history.depth())
            assertTrue(oldRoot.resolve(relativeBackup).exists())
            assertEquals("new workspace", root.resolve("new.txt").readText())
            assertEquals("agent", oldRoot.resolve(probe.receipt!!.preparation.relativePath).readText())
        } finally { PathSecurity.deleteTreeNoFollow(oldRoot) }
    }

    @Test fun sourceSymlinkToOwnedStageDoesNotProveCompletedDeletion() = workspace { root ->
        val probe = Probe(root).apply { sync = {
            Files.createSymbolicLink(root.resolve("new.txt").toPath(), stage.toPath())
            throw IOException("source aliased")
        } }
        val (history, pending) = recorded(repository(root, probe))
        assertKept(history, pending, history.undo())
        assertTrue(Files.isSymbolicLink(root.resolve("new.txt").toPath())); assertEquals("agent", probe.stage.readText())
    }

    @Test fun originalDescriptorRemainsPinnedThroughFinishAndClosesAfterObservation() = workspace { root ->
        val probe = Probe(root)
        val files = repository(root, probe)
        root.resolve("new.txt").writeText("agent")
        val observation = files.prepareDeleteObservation("new.txt", DiskFileVersion.fromText("agent"))
        var pin: java.io.InputStream? = null
        probe.finish = {
            pin = requireNotNull(observation.identityPin)
            assertEquals('a'.code, pin!!.read())
        }
        assertTrue(runCatching { files.deleteFileIfUnchanged(observation) }.isFailure)
        assertTrue(observation.ownedCurrent); assertTrue(observation.synchronizationFailed)
        assertNull(observation.identityPin); assertNull(observation.guard)
        assertTrue(runCatching { pin!!.read() }.isFailure)
        assertEquals("agent", probe.stage.readText())
    }

    @Test fun sourceParentReplacementNeverConsumesOtherDirectoryState() = workspace { root ->
        val parent = root.resolve("dir").apply { mkdirs() }
        val retainedParent = root.resolve("old-dir")
        val probe = Probe(root).apply { sync = {
            Files.move(parent.toPath(), retainedParent.toPath()); parent.mkdirs()
            throw IOException("parent changed")
        } }
        val files = repository(root, probe)
        val history = EditHistory(files)
        val pending = history.prepareBatch("nested create", mapOf("dir/new.txt" to null))
        parent.resolve("new.txt").writeText("agent")
        history.commitBatch(pending, diskAfter = mapOf("dir/new.txt" to "agent"))
        assertKept(history, pending, history.undo())
        assertEquals("agent", retainedParent.resolve(File(probe.receipt!!.preparation.relativePath).name).readText())
        assertFalse(parent.resolve("new.txt").exists())
    }

    @Test fun cancellationWithForeignAbsenceBeforeOwnEffectKeepsHistory() = workspace { root ->
        val cancelled = CancellationException("cancel before deletion")
        val files = FileRepository(root, beforeMutation = { if (it is FileRepository.Mutation.Delete) {
            Files.move(root.resolve("new.txt").toPath(), root.resolve("foreign-retained").toPath())
            throw cancelled
        } })
        val (history, pending) = recorded(files)
        assertEquals(cancelled.message, runCatching { history.undo() }.exceptionOrNull()?.message)
        assertEquals(1, history.depth()); assertTrue(pending.snapshots.single().backup.exists())
        assertEquals("agent", root.resolve("foreign-retained").readText())
    }

    @Test fun retainedStageReplacementDuringFinishFailsFinalIdentityCheck() = workspace { root ->
        val probe = Probe(root).apply { finish = { replace(stage, "agent") } }
        val (history, pending) = recorded(repository(root, probe))
        assertKept(history, pending, history.undo()); assertEquals("agent", probe.stage.readText())
    }

}
