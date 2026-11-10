package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Independent real-filesystem rename-only review. All fresh fixtures/evidence retained. */
class IndependentMoveHistoryTest {
    private fun fixture(): File = Files.createTempDirectory(
        File(System.getProperty("droide.f157.review.fixtures",
            File(System.getProperty("java.io.tmpdir"), "droide-f157-review").path)).apply { mkdirs() }.toPath(), "review-").toFile()
    private fun folder(root: File, name: String = "folder") {
        check(root.resolve(name).mkdir())
        root.resolve("$name/a").writeText("sentinel")
        Files.setLastModifiedTime(root.resolve(name).toPath(), FileTime.fromMillis(1_000))
    }
    private suspend fun move(repo: FileRepository, state: ExplorerWorkspaceState,
                             from: String, to: String): FileRepository.MoveUndoToken {
        val request = repo.observeMove(from, to)
        assertTrue(repo.moveObserved(request)); assertTrue(request.committed)
        assertNull(request.failure); assertNotNull(request.undo)
        state.recordMove(request.undo, 0)
        return request.undo!!
    }
    private suspend fun replay(repo: FileRepository, state: ExplorerWorkspaceState, redo: Boolean = false) {
        val token = (if (redo) state.redoMoves else state.undoMoves).last()
        val request = repo.observeUndo(token)
        assertTrue(repo.moveObserved(request)); assertTrue(request.committed)
        assertNull(request.failure); assertNotNull(request.undo)
        state.recordMove(request.undo, if (redo) 1 else -1)
    }
    private suspend fun denied(repo: FileRepository, token: FileRepository.MoveUndoToken) {
        var failure: Throwable? = null
        try { repo.moveObserved(repo.observeUndo(token)) } catch (e: Throwable) { failure = e }
        assertNotNull("Unsafe inverse was allowed", failure)
    }
    private fun repository(root: File, mover: WorkspacePathMove = WorkspacePathMove.platform,
        observer: (suspend (FileRepository.Mutation) -> Unit)? = null,
        participant: WorkspaceMutationParticipant? = null) = FileRepository(root,
            onMutation = observer, newFilePublisher = NewProjectFilePublisher.platform,
            pathMover = mover, participant = participant)

    @Test fun nestedRoundTripAndRedoChain() = runBlocking {
        val root = fixture(); folder(root)
        val repo = FileRepository(root); val state = ExplorerWorkspaceState()
        move(repo, state, "folder", "moved"); move(repo, state, "moved/a", "moved/b")
        replay(repo, state); replay(repo, state)
        assertEquals("sentinel", root.resolve("folder/a").readText())
        assertTrue(state.undoMoves.isEmpty()); assertEquals(2, state.redoMoves.size)
        replay(repo, state, true); replay(repo, state, true)
        assertEquals("sentinel", root.resolve("moved/b").readText())
        replay(repo, state); replay(repo, state)
        assertEquals("sentinel", root.resolve("folder/a").readText())
    }
    @Test fun separateParentsRebaseIndependently() = runBlocking {
        val root = fixture(); folder(root, "left"); check(root.resolve("right").mkdir())
        Files.setLastModifiedTime(root.resolve("right").toPath(), FileTime.fromMillis(2_000))
        val repo = FileRepository(root); val state = ExplorerWorkspaceState()
        move(repo, state, "left", "L"); move(repo, state, "right", "R")
        move(repo, state, "L/a", "R/a")
        replay(repo, state); replay(repo, state); replay(repo, state)
        assertEquals("sentinel", root.resolve("left/a").readText())
        assertTrue(root.resolve("right").isDirectory); assertTrue(state.undoMoves.isEmpty())
    }
    @Test fun externalChangeBeforeChildMoveIsNotBlessed() = runBlocking {
        val root = fixture(); folder(root)
        val repo = FileRepository(root); val state = ExplorerWorkspaceState()
        move(repo, state, "folder", "moved")
        root.resolve("moved/foreign").writeText("external")
        move(repo, state, "moved/a", "moved/b"); replay(repo, state)
        denied(repo, state.undoMoves.last())
        assertEquals("external", root.resolve("moved/foreign").readText())
    }
    @Test fun externalChangeInsideRenameBoundaryIsNotBlessed() = runBlocking {
        val root = fixture(); folder(root)
        var insert = true
        val repo = repository(root, mover = WorkspacePathMove { from, to ->
            if (from.name == "a" && insert) { insert = false; from.parentFile.resolve("foreign").writeText("external") }
            WorkspacePathMove.platform.move(from, to)
        }); val state = ExplorerWorkspaceState()
        move(repo, state, "folder", "moved"); move(repo, state, "moved/a", "moved/b")
        replay(repo, state); denied(repo, state.undoMoves.last())
        assertEquals("external", root.resolve("moved/foreign").readText())
    }
    @Test fun externalChangeInObserverIsNotBlessed() = runBlocking {
        val root = fixture(); folder(root)
        val repo = repository(root, observer = { mutation ->
            if (mutation == FileRepository.Mutation.Move("moved/a", "moved/b"))
                root.resolve("moved/foreign").writeText("observer")
        }); val state = ExplorerWorkspaceState()
        move(repo, state, "folder", "moved"); move(repo, state, "moved/a", "moved/b")
        replay(repo, state); denied(repo, state.undoMoves.last())
        assertEquals("observer", root.resolve("moved/foreign").readText())
    }
    @Test fun externalChangeInParticipantFinishIsNotBlessed() = runBlocking {
        val root = fixture(); folder(root)
        val participant = WorkspaceMutationParticipant { guard, mutation ->
            object : WorkspaceMutationSession(guard, mutation) {
                override fun finish(outcome: WorkspaceMutationOutcome) {
                    if (mutation == FileRepository.Mutation.Move("moved/a", "moved/b"))
                        root.resolve("moved/foreign").writeText("finish")
                }
            }
        }
        val repo = repository(root, participant = participant); val state = ExplorerWorkspaceState()
        move(repo, state, "folder", "moved"); move(repo, state, "moved/a", "moved/b")
        replay(repo, state); denied(repo, state.undoMoves.last())
        assertEquals("finish", root.resolve("moved/foreign").readText())
    }
    @Test fun externalChangeAfterChildUndoStillConflicts() = runBlocking {
        val root = fixture(); folder(root)
        val repo = FileRepository(root); val state = ExplorerWorkspaceState()
        move(repo, state, "folder", "moved"); move(repo, state, "moved/a", "moved/b")
        replay(repo, state)
        root.resolve("moved/foreign").writeText("external")
        Files.setLastModifiedTime(root.resolve("moved").toPath(), FileTime.fromMillis(7_000))
        denied(repo, state.undoMoves.last())
        assertEquals("external", root.resolve("moved/foreign").readText())
    }
    @Test fun receiptFailureNeverPublishesHistory() = runBlocking {
        val root = fixture(); folder(root)
        val participant = WorkspaceMutationParticipant { guard, mutation ->
            object : WorkspaceMutationSession(guard, mutation) {
                override fun localReturned(receipt: WorkspaceLocalReceipt) {
                    if (mutation == FileRepository.Mutation.Move("moved/a", "moved/b")) throw IOException("receipt failed")
                }
            }
        }
        val repo = repository(root, participant = participant); val state = ExplorerWorkspaceState()
        move(repo, state, "folder", "moved")
        val request = repo.observeMove("moved/a", "moved/b")
        try { repo.moveObserved(request); fail("Expected receipt failure") } catch (_: IOException) { }
        assertNull(request.undo); assertNotNull(request.failure)
        denied(repo, state.undoMoves.last())
        assertEquals("sentinel", root.resolve("moved/b").readText())
    }
    @Test fun finishFailureNeverPublishesHistory() = runBlocking {
        val root = fixture(); folder(root)
        val participant = WorkspaceMutationParticipant { guard, mutation ->
            object : WorkspaceMutationSession(guard, mutation) {
                override fun finish(outcome: WorkspaceMutationOutcome) {
                    if (mutation == FileRepository.Mutation.Move("moved/a", "moved/b")) throw IOException("finish failed")
                }
            }
        }
        val repo = repository(root, participant = participant); val state = ExplorerWorkspaceState()
        move(repo, state, "folder", "moved")
        val request = repo.observeMove("moved/a", "moved/b")
        try { repo.moveObserved(request); fail("Expected finish failure") } catch (_: IOException) { }
        assertNull(request.undo); assertNotNull(request.failure)
        denied(repo, state.undoMoves.last())
    }
    @Test fun observerCancellationNeverPublishesHistory() = runBlocking {
        val root = fixture(); folder(root)
        val repo = repository(root, observer = { mutation ->
            if (mutation == FileRepository.Mutation.Move("moved/a", "moved/b")) throw CancellationException("cancel observer")
        }); val state = ExplorerWorkspaceState()
        move(repo, state, "folder", "moved")
        val request = repo.observeMove("moved/a", "moved/b")
        try { repo.moveObserved(request); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertNull(request.undo); denied(repo, state.undoMoves.last())
        assertEquals("sentinel", root.resolve("moved/a").readText())
    }
    @Test fun foreignRepositoryCannotReplayToken() = runBlocking {
        val root = fixture(); folder(root)
        val repo = FileRepository(root); val state = ExplorerWorkspaceState()
        move(repo, state, "folder", "moved")
        denied(FileRepository(root), state.undoMoves.last())
        assertEquals("sentinel", root.resolve("moved/a").readText())
    }
    @Test fun rebasedOldTokenCannotRegainAuthorityByMetadataCoincidence() = runBlocking {
        val root = fixture(); folder(root)
        val repo = FileRepository(root); val state = ExplorerWorkspaceState()
        val old = move(repo, state, "folder", "moved")
        move(repo, state, "moved/a", "moved/b")
        Files.setLastModifiedTime(root.resolve("moved").toPath(), old.version.modified)
        denied(repo, old)
        assertEquals("sentinel", root.resolve("moved/b").readText())
    }
    @Test fun duplicatePublicationDoesNotDuplicateOrPopHistory() = runBlocking {
        val root = fixture(); root.resolve("a").writeText("sentinel")
        val repo = FileRepository(root); val state = ExplorerWorkspaceState()
        val token = move(repo, state, "a", "b")
        try { state.recordMove(token, 0) } catch (_: IllegalStateException) { }
        assertEquals(1, state.undoMoves.size); assertSame(token, state.undoMoves.last())
        replay(repo, state)
        val reverse = state.redoMoves.last()
        try { state.recordMove(reverse, -1) } catch (_: IllegalStateException) { }
        assertEquals(1, state.redoMoves.size); assertTrue(state.undoMoves.isEmpty())
    }
    @Test fun preEffectDestinationConflictRemainsRetryable() = runBlocking {
        val root = fixture(); root.resolve("a").writeText("sentinel")
        val repo = FileRepository(root); val state = ExplorerWorkspaceState()
        move(repo, state, "a", "b")
        root.resolve("a").writeText("foreign")
        denied(repo, state.undoMoves.last())
        Files.move(root.resolve("a").toPath(), root.resolve("retained-foreign").toPath())
        replay(repo, state)
        assertEquals("sentinel", root.resolve("a").readText())
        assertEquals("foreign", root.resolve("retained-foreign").readText())
    }
    @Test fun oversizedOtherParentDoesNotSuppressSmallParentReceipt() = runBlocking {
        val root = fixture(); folder(root)
        repeat(260) { check(root.resolve("empty-$it").createNewFile()) }
        val repo = FileRepository(root); val state = ExplorerWorkspaceState()
        move(repo, state, "folder", "moved"); move(repo, state, "moved/a", "a")
        replay(repo, state); replay(repo, state)
        assertEquals("sentinel", root.resolve("folder/a").readText())
    }
    @Test fun oversizedParentKeepsOrdinaryMoveAndUnrelatedHistoryUsable() = runBlocking {
        val root = fixture(); folder(root)
        repeat(260) { check(root.resolve("folder/empty-$it").createNewFile()) }
        root.resolve("ordinary").writeText("ordinary")
        val repo = FileRepository(root); val state = ExplorerWorkspaceState()
        move(repo, state, "ordinary", "other")
        move(repo, state, "folder/a", "folder/b")
        assertEquals(2, state.undoMoves.size)
        replay(repo, state); replay(repo, state)
        assertEquals("sentinel", root.resolve("folder/a").readText())
        assertEquals("ordinary", root.resolve("ordinary").readText())
    }
    @Test fun sameMetadataParentReplacementNeverGetsOldAuthority() = runBlocking {
        val root = fixture(); folder(root)
        val repo = FileRepository(root); val state = ExplorerWorkspaceState()
        val old = move(repo, state, "folder", "moved")
        Files.move(root.resolve("moved").toPath(), root.resolve("retained").toPath())
        folder(root, "moved")
        Files.setLastModifiedTime(root.resolve("moved").toPath(), old.version.modified)
        move(repo, state, "moved/a", "moved/b"); replay(repo, state)
        denied(repo, state.undoMoves.last())
        assertEquals("sentinel", root.resolve("retained/a").readText())
        assertEquals("sentinel", root.resolve("moved/a").readText())
    }

    @Test fun thrownBeforeEffectCanRetrySameHistoryToken() = runBlocking {
        val root = fixture(); root.resolve("a").writeText("sentinel")
        var failOnce = true
        val repo = repository(root, mover = WorkspacePathMove { from, to ->
            if (from.name == "b" && failOnce) { failOnce = false; throw IOException("before effect") }
            WorkspacePathMove.platform.move(from, to)
        }); val state = ExplorerWorkspaceState()
        move(repo, state, "a", "b")
        val request = repo.observeUndo(state.undoMoves.last())
        try { repo.moveObserved(request); fail("Expected local failure") } catch (_: IOException) { }
        assertFalse(request.committed); assertNull(request.undo)
        assertEquals("sentinel", root.resolve("b").readText())
        replay(repo, state)
        assertEquals("sentinel", root.resolve("a").readText())
    }
    @Test fun thrownAfterRenameCannotBlindlyReplayEvenIfOriginalPathReappears() = runBlocking {
        val root = fixture(); root.resolve("a").writeText("sentinel")
        var failOnce = true
        val repo = repository(root, mover = WorkspacePathMove { from, to ->
            WorkspacePathMove.platform.move(from, to)
            if (from.name == "b" && failOnce) { failOnce = false; throw IOException("after effect") }
        }); val state = ExplorerWorkspaceState()
        move(repo, state, "a", "b")
        val request = repo.observeUndo(state.undoMoves.last())
        try { repo.moveObserved(request); fail("Expected ambiguous failure") } catch (_: IOException) { }
        assertNull(request.undo)
        assertEquals("sentinel", root.resolve("a").readText())
        Files.move(root.resolve("a").toPath(), root.resolve("b").toPath())
        denied(repo, state.undoMoves.last())
        assertEquals("sentinel", root.resolve("b").readText())
    }
    @Test fun unrelatedInverseReceiptCannotPopCurrentHistoryTop() = runBlocking {
        val root = fixture(); root.resolve("a").writeText("first"); root.resolve("c").writeText("second")
        val repo = FileRepository(root); val state = ExplorerWorkspaceState()
        val first = move(repo, state, "a", "b")
        val second = move(repo, state, "c", "d")
        val request = repo.observeUndo(first)
        assertTrue(repo.moveObserved(request)); assertNotNull(request.undo)
        try { state.recordMove(request.undo, -1) } catch (_: IllegalStateException) { }
        assertEquals(2, state.undoMoves.size); assertSame(second, state.undoMoves.last())
        assertTrue(state.redoMoves.isEmpty())
    }

    @Test fun verifiedFileRollbackAllowsRetryDespiteUnrelatedSiblingChange() = runBlocking {
        val root = fixture(); root.resolve("a").writeText("sentinel")
        var failOnce = true
        val repo = repository(root, observer = { mutation ->
            if (mutation == FileRepository.Mutation.Move("b", "a") && failOnce) {
                failOnce = false
                root.resolve("foreign").writeText("external")
                throw IOException("observer failed after successful inverse")
            }
        }); val state = ExplorerWorkspaceState()
        move(repo, state, "a", "b")
        val request = repo.observeUndo(state.undoMoves.last())
        try { repo.moveObserved(request); fail("Expected synchronization failure") } catch (_: IOException) { }
        assertFalse(request.committed); assertFalse(request.uncertain); assertNull(request.undo)
        assertEquals("sentinel", root.resolve("b").readText())
        replay(repo, state)
        assertEquals("sentinel", root.resolve("a").readText())
        assertEquals("external", root.resolve("foreign").readText())
    }

    @Test fun equalLookingButForeignPreparationDoesNotAuthorizeRebase() = runBlocking {
        val root = fixture(); folder(root)
        lateinit var child: FileRepository.MoveObservation
        val repo = repository(root, observer = { mutation ->
            if (mutation == FileRepository.Mutation.Move("moved/a", "moved/b"))
                child.preparation = child.preparation!!.copy()
        }); val state = ExplorerWorkspaceState()
        move(repo, state, "folder", "moved")
        child = repo.observeMove("moved/a", "moved/b")
        assertTrue(repo.moveObserved(child)); assertNotNull(child.undo)
        state.recordMove(child.undo, 0)
        replay(repo, state); denied(repo, state.undoMoves.last())
        assertEquals("sentinel", root.resolve("moved/a").readText())
    }
    @Test fun finalObserverMetadataOnlyChangeDoesNotAuthorizeRebase() = runBlocking {
        val root = fixture(); folder(root)
        val repo = repository(root, observer = { mutation ->
            if (mutation == FileRepository.Mutation.Move("moved/a", "moved/b"))
                Files.setLastModifiedTime(root.resolve("moved").toPath(), FileTime.fromMillis(4_000))
        }); val state = ExplorerWorkspaceState()
        move(repo, state, "folder", "moved"); move(repo, state, "moved/a", "moved/b")
        replay(repo, state); denied(repo, state.undoMoves.last())
    }
    @Test fun thrownRollbackNeverRearmsHistoryByMatchingFinalImage() = runBlocking {
        val root = fixture(); root.resolve("a").writeText("sentinel")
        var rollback = false
        val repo = repository(root, mover = WorkspacePathMove { from, to ->
            WorkspacePathMove.platform.move(from, to)
            if (rollback) throw IOException("rollback return was lost")
        }, observer = { mutation ->
            if (mutation == FileRepository.Mutation.Move("b", "a")) {
                rollback = true; throw IOException("sync failed")
            }
        }); val state = ExplorerWorkspaceState()
        move(repo, state, "a", "b")
        val request = repo.observeUndo(state.undoMoves.last())
        try { repo.moveObserved(request); fail("Expected ambiguous rollback") } catch (_: Exception) { }
        assertNull(request.undo); assertEquals("sentinel", root.resolve("b").readText())
        rollback = false
        denied(repo, state.undoMoves.last())
    }
    @Test fun failedReconciliationAfterRollbackDoesNotRearm() = runBlocking {
        val root = fixture(); root.resolve("a").writeText("sentinel")
        var failOnce = true
        val repo = repository(root, observer = { mutation ->
            if (mutation == FileRepository.Mutation.Move("b", "a") && failOnce) {
                failOnce = false; throw IOException("sync failed")
            }
            if (mutation == FileRepository.Mutation.Sync("b")) throw IOException("reconcile failed")
        }); val state = ExplorerWorkspaceState()
        move(repo, state, "a", "b")
        val request = repo.observeUndo(state.undoMoves.last())
        try { repo.moveObserved(request); fail("Expected sync failure") } catch (_: IOException) { }
        assertNull(request.undo); assertEquals("sentinel", root.resolve("b").readText())
        denied(repo, state.undoMoves.last())
    }
    @Test fun wrongDirectionPublicationDoesNotConsumeCorrectReceipt() = runBlocking {
        val root = fixture(); root.resolve("a").writeText("sentinel")
        val repo = FileRepository(root); val state = ExplorerWorkspaceState()
        val original = move(repo, state, "a", "b")
        val inverse = repo.observeUndo(original)
        assertTrue(repo.moveObserved(inverse)); assertNotNull(inverse.undo)
        state.recordMove(inverse.undo, 1)
        assertEquals(1, state.undoMoves.size); assertSame(original, state.undoMoves.last())
        assertTrue(state.redoMoves.isEmpty())
        state.recordMove(inverse.undo, -1)
        assertTrue(state.undoMoves.isEmpty()); assertEquals(1, state.redoMoves.size)
    }

    @Test fun participantFinishFailureAfterRollbackDoesNotRearm() = runBlocking {
        val root = fixture(); root.resolve("a").writeText("sentinel")
        var failOnce = true
        val participant = WorkspaceMutationParticipant { guard, mutation ->
            object : WorkspaceMutationSession(guard, mutation) {
                override fun finish(outcome: WorkspaceMutationOutcome) {
                    if (mutation == FileRepository.Mutation.Move("b", "a") && outcome.failure != null)
                        throw IOException("finish failed after rollback")
                }
            }
        }
        val repo = repository(root, participant = participant, observer = { mutation ->
            if (mutation == FileRepository.Mutation.Move("b", "a") && failOnce) {
                failOnce = false; throw IOException("sync failed")
            }
        }); val state = ExplorerWorkspaceState()
        move(repo, state, "a", "b")
        val request = repo.observeUndo(state.undoMoves.last())
        try { repo.moveObserved(request); fail("Expected sync failure") } catch (_: IOException) { }
        assertNull(request.undo); assertEquals("sentinel", root.resolve("b").readText())
        denied(repo, state.undoMoves.last())
    }

    @Test fun failedReceiptAfterThrowBeforeEffectDoesNotRearm() = runBlocking {
        val root = fixture(); root.resolve("a").writeText("sentinel")
        var failOnce = true
        val participant = WorkspaceMutationParticipant { guard, mutation ->
            object : WorkspaceMutationSession(guard, mutation) {
                override fun localReturned(receipt: WorkspaceLocalReceipt) {
                    if (mutation == FileRepository.Mutation.Move("b", "a") && !receipt.returned)
                        throw IOException("failed receipt could not be saved")
                }
            }
        }
        val repo = repository(root, participant = participant, mover = WorkspacePathMove { from, to ->
            if (from.name == "b" && failOnce) { failOnce = false; throw IOException("before effect") }
            WorkspacePathMove.platform.move(from, to)
        }); val state = ExplorerWorkspaceState()
        move(repo, state, "a", "b")
        val request = repo.observeUndo(state.undoMoves.last())
        try { repo.moveObserved(request); fail("Expected local failure") } catch (_: IOException) { }
        assertNull(request.undo); assertEquals("sentinel", root.resolve("b").readText())
        denied(repo, state.undoMoves.last())
    }
    @Test fun failedFinishAfterThrowBeforeEffectDoesNotRearm() = runBlocking {
        val root = fixture(); root.resolve("a").writeText("sentinel")
        var failOnce = true
        val participant = WorkspaceMutationParticipant { guard, mutation ->
            object : WorkspaceMutationSession(guard, mutation) {
                override fun finish(outcome: WorkspaceMutationOutcome) {
                    if (mutation == FileRepository.Mutation.Move("b", "a") && outcome.failure != null)
                        throw IOException("finish failed after local refusal")
                }
            }
        }
        val repo = repository(root, participant = participant, mover = WorkspacePathMove { from, to ->
            if (from.name == "b" && failOnce) { failOnce = false; throw IOException("before effect") }
            WorkspacePathMove.platform.move(from, to)
        }); val state = ExplorerWorkspaceState()
        move(repo, state, "a", "b")
        val request = repo.observeUndo(state.undoMoves.last())
        try { repo.moveObserved(request); fail("Expected local failure") } catch (_: IOException) { }
        assertNull(request.undo); assertEquals("sentinel", root.resolve("b").readText())
        denied(repo, state.undoMoves.last())
    }
    @Test fun lateFinishMutationAfterThrowBeforeEffectDoesNotRearm() = runBlocking {
        val root = fixture(); root.resolve("a").writeText("sentinel")
        var failOnce = true
        val participant = WorkspaceMutationParticipant { guard, mutation ->
            object : WorkspaceMutationSession(guard, mutation) {
                override fun finish(outcome: WorkspaceMutationOutcome) {
                    if (mutation == FileRepository.Mutation.Move("b", "a") && outcome.failure != null)
                        root.resolve("foreign").writeText("late finish")
                }
            }
        }
        val repo = repository(root, participant = participant, mover = WorkspacePathMove { from, to ->
            if (from.name == "b" && failOnce) { failOnce = false; throw IOException("before effect") }
            WorkspacePathMove.platform.move(from, to)
        }); val state = ExplorerWorkspaceState()
        move(repo, state, "a", "b")
        val request = repo.observeUndo(state.undoMoves.last())
        try { repo.moveObserved(request); fail("Expected local failure") } catch (_: IOException) { }
        assertNull(request.undo); assertEquals("sentinel", root.resolve("b").readText())
        denied(repo, state.undoMoves.last())
        assertEquals("late finish", root.resolve("foreign").readText())
    }
}
