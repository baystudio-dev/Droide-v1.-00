package com.baystudio.droide.ui

import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.NewProjectFilePublisher
import com.baystudio.droide.core.WorkspaceMutationOutcome
import com.baystudio.droide.core.WorkspaceMutationParticipant
import com.baystudio.droide.core.WorkspaceMutationSession
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** Retained, new-only host fixtures. No Android/UI rendering or ordinary-write capacity bypass. */
class ExplorerMoveNestedHistoryReceiptTest {
    private fun fixture(): File = Files.createTempDirectory(
        File("/tmp/f157-implementation/fixtures").apply { mkdirs() }.toPath(), "happy-"
    ).toFile()

    private fun folder(root: File, path: String, child: String? = "a.txt"): File =
        root.resolve(path).apply {
            mkdirs()
            child?.let { resolve(it).writeText("unchanged") }
            Files.setLastModifiedTime(toPath(), FileTime.fromMillis(1_000L))
        }

    private suspend fun apply(repo: FileRepository, state: ExplorerWorkspaceState,
                              request: FileRepository.MoveObservation, direction: Int = 0) {
        assertTrue(repo.moveObserved(request))
        assertTrue(request.committed)
        assertNull(request.failure)
        assertNotNull(request.undo)
        state.recordMove(request.undo, direction)
    }

    private suspend fun undo(repo: FileRepository, state: ExplorerWorkspaceState) =
        apply(repo, state, repo.observeUndo(state.undoMoves.last()), -1)

    private suspend fun redo(repo: FileRepository, state: ExplorerWorkspaceState) =
        apply(repo, state, repo.observeUndo(state.redoMoves.last()), 1)

    @Test fun nestedParentChildUndoRedoCyclesUseFreshReceipts() = runBlocking {
        val root = fixture(); folder(root, "folder")
        val repo = FileRepository(root); val state = ExplorerWorkspaceState()
        apply(repo, state, repo.observeMove("folder", "moved"))
        val firstParent = state.undoMoves.last()
        apply(repo, state, repo.observeMove("moved/a.txt", "moved/b.txt"))
        assertNotSame(firstParent, state.undoMoves.first())
        repeat(3) {
            undo(repo, state); undo(repo, state)
            assertEquals("unchanged", root.resolve("folder/a.txt").readText())
            assertEquals(0, state.undoMoves.size); assertEquals(2, state.redoMoves.size)
            redo(repo, state); redo(repo, state)
            assertEquals("unchanged", root.resolve("moved/b.txt").readText())
            assertEquals(2, state.undoMoves.size); assertEquals(0, state.redoMoves.size)
        }
    }

    @Test fun distinctParentsBothAdvanceAndReverseAcrossFolderMoves() = runBlocking {
        val root = fixture(); folder(root, "one"); folder(root, "two", null)
        val repo = FileRepository(root); val state = ExplorerWorkspaceState()
        apply(repo, state, repo.observeMove("one", "left"))
        apply(repo, state, repo.observeMove("two", "right"))
        val before = state.undoMoves.toList()
        apply(repo, state, repo.observeMove("left/a.txt", "right/a.txt"))
        assertNotSame(before[0], state.undoMoves[0]); assertNotSame(before[1], state.undoMoves[1])
        repeat(3) { undo(repo, state) }
        assertEquals("unchanged", root.resolve("one/a.txt").readText())
        assertTrue(root.resolve("two").isDirectory)
        repeat(3) { redo(repo, state) }
        assertEquals("unchanged", root.resolve("right/a.txt").readText())
    }

    @Test fun deeperAncestorDoesNotReceiveAnImmediateParentTransition() = runBlocking {
        val root = fixture(); folder(root, "folder/sub")
        val repo = FileRepository(root); val state = ExplorerWorkspaceState()
        apply(repo, state, repo.observeMove("folder", "moved"))
        val ancestor = state.undoMoves.single()
        apply(repo, state, repo.observeMove("moved/sub/a.txt", "moved/sub/b.txt"))
        assertSame(ancestor, state.undoMoves.first())
        undo(repo, state); undo(repo, state)
        assertEquals("unchanged", root.resolve("folder/sub/a.txt").readText())
    }

    @Test fun unrelatedFileHistoryRemainsTheSameToken() = runBlocking {
        val root = fixture(); folder(root, "folder"); root.resolve("x.txt").writeText("x")
        val repo = FileRepository(root); val state = ExplorerWorkspaceState()
        apply(repo, state, repo.observeMove("x.txt", "y.txt"))
        val unrelated = state.undoMoves.single()
        apply(repo, state, repo.observeMove("folder/a.txt", "folder/b.txt"))
        assertSame(unrelated, state.undoMoves.first())
        undo(repo, state); undo(repo, state)
        assertEquals("x", root.resolve("x.txt").readText())
    }

    @Test fun ordinaryFileForwardUndoRedoStillWorks() = runBlocking {
        val root = fixture(); root.resolve("a.txt").writeText("unchanged")
        val repo = FileRepository(root); val state = ExplorerWorkspaceState()
        apply(repo, state, repo.observeMove("a.txt", "b.txt"))
        repeat(3) {
            undo(repo, state); assertEquals("unchanged", root.resolve("a.txt").readText())
            redo(repo, state); assertEquals("unchanged", root.resolve("b.txt").readText())
        }
    }

    @Test fun oversizedParentWithholdsOnlyOptionalRebase() = runBlocking {
        val root = fixture(); val dir = folder(root, "folder")
        repeat(256) { dir.resolve("sibling-$it").createNewFile() }
        Files.setLastModifiedTime(dir.toPath(), FileTime.fromMillis(1_000L))
        val repo = FileRepository(root); val state = ExplorerWorkspaceState()
        apply(repo, state, repo.observeMove("folder", "moved"))
        val before = state.undoMoves.single()
        apply(repo, state, repo.observeMove("moved/a.txt", "moved/b.txt"))
        assertSame(before, state.undoMoves.first())
        undo(repo, state)
        assertEquals("unchanged", root.resolve("moved/a.txt").readText())
        assertSame(before, state.undoMoves.single())
    }

    @Test fun smallSourceParentStillRebasesWhenOtherParentIsOversized() = runBlocking {
        val root = fixture(); folder(root, "folder")
        repeat(257) { root.resolve("sibling-$it").createNewFile() }
        val repo = FileRepository(root); val state = ExplorerWorkspaceState()
        apply(repo, state, repo.observeMove("folder", "moved"))
        val before = state.undoMoves.single()
        apply(repo, state, repo.observeMove("moved/a.txt", "outside.txt"))
        assertNotSame(before, state.undoMoves.first())
        undo(repo, state); undo(repo, state)
        assertEquals("unchanged", root.resolve("folder/a.txt").readText())
    }

    @Test fun symlinkSiblingWithholdsRebaseWithoutBlockingOrdinaryFileMove() = runBlocking {
        val root = fixture(); val dir = folder(root, "folder")
        Files.createSymbolicLink(dir.resolve("link").toPath(), dir.resolve("a.txt").toPath())
        Files.setLastModifiedTime(dir.toPath(), FileTime.fromMillis(1_000L))
        val repo = FileRepository(root); val state = ExplorerWorkspaceState()
        apply(repo, state, repo.observeMove("folder", "moved"))
        val before = state.undoMoves.single()
        apply(repo, state, repo.observeMove("moved/a.txt", "moved/b.txt"))
        assertSame(before, state.undoMoves.first())
        undo(repo, state)
        assertEquals("unchanged", root.resolve("moved/a.txt").readText())
        assertTrue(Files.isSymbolicLink(root.resolve("moved/link").toPath()))
    }

    @Test fun successfulLocalCompletionWithLostIoReturnPublishesNoHistory() = runBlocking {
        val root = fixture(); folder(root, "folder")
        var cancelAtFinish: Job? = null
        val repo = FileRepository(root, newFilePublisher = NewProjectFilePublisher.platform,
            participant = WorkspaceMutationParticipant { guard, mutation ->
                object : WorkspaceMutationSession(guard, mutation) {
                    override fun finish(outcome: WorkspaceMutationOutcome) {
                        assertNull(outcome.failure)
                        cancelAtFinish?.cancel(CancellationException("lost IO return"))
                    }
                }
            })
        val state = ExplorerWorkspaceState()
        apply(repo, state, repo.observeMove("folder", "moved"))
        val request = repo.observeMove("moved/a.txt", "moved/b.txt")
        val operationJob = Job(); cancelAtFinish = operationJob
        var failure: Throwable? = null
        try { withContext(operationJob) { repo.moveObserved(request) } }
        catch (error: Throwable) { failure = error }
        assertTrue(failure is CancellationException)
        assertTrue(request.committed)
        assertNull(request.failure)
        assertNotNull(request.undo)
        // The exact live performMove publication gate includes the outer IO-return
        // failure, even when repository completion was successful before return loss.
        state.recordMove(if (failure == null && request.failure == null) request.undo else null, 0)
        assertTrue(state.undoMoves.isEmpty()); assertTrue(state.redoMoves.isEmpty())
        assertEquals("unchanged", root.resolve("moved/b.txt").readText())
    }

    @Test fun exactSnapshotCapStillSupportsRename() = runBlocking {
        val root = fixture(); val dir = folder(root, "folder")
        repeat(255) { dir.resolve("sibling-$it").createNewFile() }
        Files.setLastModifiedTime(dir.toPath(), FileTime.fromMillis(1_000L))
        val repo = FileRepository(root); val state = ExplorerWorkspaceState()
        apply(repo, state, repo.observeMove("folder", "moved"))
        apply(repo, state, repo.observeMove("moved/a.txt", "moved/b.txt"))
        undo(repo, state); undo(repo, state)
        assertEquals("unchanged", root.resolve("folder/a.txt").readText())
    }
}
