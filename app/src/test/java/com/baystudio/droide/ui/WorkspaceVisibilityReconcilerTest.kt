package com.baystudio.droide.ui

import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.PathSecurity
import com.baystudio.droide.core.WorkspaceDocumentAuthority
import com.baystudio.droide.core.WorkspaceDocumentConflictException
import com.baystudio.droide.core.WorkspaceDocumentSnapshot
import com.baystudio.droide.core.WorkspacePersistedReconciliation
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class WorkspaceVisibilityReconcilerTest {
    private fun visible(path: String = "active.txt", explorer: Boolean = true) =
        WorkspaceVisibilityReconciler.Visibility(true, true, explorer, path)

    @Test fun returningFromTerminalRefreshesOnlyTheActiveFileWithoutOutboundRepositoryWrites() = runTest {
        val root = Files.createTempDirectory("droide-visible-refresh-").toFile()
        try {
            root.resolve("active.txt").writeText("before")
            root.resolve("other.txt").writeText("other before")
            var outboundWrites = 0
            val files = FileRepository(root, onMutation = { outboundWrites++ })
            val state = EditorWorkspaceState()
            val active = state.document("active.txt").also { it.ensureLoaded(files) }
            val other = state.document("other.txt").also { it.ensureLoaded(files) }
            root.resolve("active.txt").writeText("written in the human terminal")
            root.resolve("other.txt").writeText("unrelated terminal write")
            root.resolve("created.txt").writeText("new file")
            val actual = EditorWorkspaceDocumentAuthority(state, Dispatchers.Unconfined, files)
            val calls = mutableListOf<FileRepository.Mutation>()
            val authority = object : WorkspaceDocumentAuthority by actual {
                override suspend fun persistedMutation(mutation: FileRepository.Mutation): WorkspacePersistedReconciliation {
                    calls += mutation
                    return actual.persistedMutation(mutation)
                }
                override suspend fun reconcilePersistedWorkspace(): WorkspacePersistedReconciliation =
                    error("Visibility must never scan every document")
            }
            val refreshed = CompletableDeferred<Unit>()
            val explorer = ExplorerWorkspaceState()
            val failures = mutableListOf<Exception>()
            val owner = WorkspaceVisibilityReconciler(this, authority, {}, {
                explorer.requestRefresh(); refreshed.complete(Unit)
            }, { _, failure -> failures += failure })
            owner.update(visible())
            owner.update(visible())
            refreshed.await()
            assertEquals("written in the human terminal", active.content)
            assertEquals("other before", other.content)
            assertEquals(listOf(FileRepository.Mutation.Sync("active.txt")), calls)
            assertEquals(1, explorer.refreshToken)
            assertEquals(0, outboundWrites)
            assertTrue(failures.isEmpty())
            owner.close()
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun returningWithUnsavedEditsReportsTheConflictAndPreservesBothVersions() = runTest {
        val root = Files.createTempDirectory("droide-visible-conflict-").toFile()
        try {
            root.resolve("active.txt").writeText("saved baseline")
            val files = FileRepository(root)
            val state = EditorWorkspaceState()
            val active = state.document("active.txt").also { it.ensureLoaded(files) }
            active.capture("unsaved editor work")
            root.resolve("active.txt").writeText("new terminal work")
            val refreshed = CompletableDeferred<Unit>()
            val failures = mutableListOf<Pair<String, Exception>>()
            val owner = WorkspaceVisibilityReconciler(this,
                EditorWorkspaceDocumentAuthority(state, Dispatchers.Unconfined, files), {},
                { refreshed.complete(Unit) }, { path, error -> failures += path to error })
            owner.update(visible())
            refreshed.await()
            assertEquals("unsaved editor work", active.content)
            assertTrue(active.dirty)
            assertEquals("new terminal work", root.resolve("active.txt").readText())
            assertEquals("active.txt", failures.single().first)
            assertTrue(failures.single().second is WorkspaceDocumentConflictException)
            owner.close()
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun duplicatePaneResumeAndFocusEventsCoalesceAndHiddenUiDoesNoWork() = runTest {
        val paths = mutableListOf<String>()
        var captures = 0
        var refreshes = 0
        val authority = object : StubAuthority() {
            override suspend fun persistedMutation(mutation: FileRepository.Mutation): WorkspacePersistedReconciliation {
                paths += (mutation as FileRepository.Mutation.Sync).path
                return WorkspacePersistedReconciliation()
            }
        }
        val owner = WorkspaceVisibilityReconciler(this, authority, { captures++ }, { refreshes++ },
            { _, error -> throw AssertionError(error) })
        owner.update(visible().copy(resumed = false))
        owner.update(visible().copy(editor = false, explorer = false))
        advanceUntilIdle()
        assertTrue(paths.isEmpty())
        owner.update(visible(explorer = false))
        owner.update(visible())
        owner.update(visible().copy(editorFocused = true))
        owner.update(visible().copy(editorFocused = true))
        advanceUntilIdle()
        assertEquals(listOf("active.txt"), paths)
        assertEquals(1, captures)
        assertEquals(1, refreshes)
        owner.update(visible().copy(resumed = false))
        owner.update(visible())
        advanceUntilIdle()
        assertEquals(listOf("active.txt", "active.txt"), paths)
        assertEquals(2, refreshes)
        owner.close()
    }

    @Test fun replacingOrClosingTheWorkspaceOwnerSuppressesLateRefreshAndErrors() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var refreshes = 0
        var failures = 0
        val authority = object : StubAuthority() {
            override suspend fun persistedMutation(mutation: FileRepository.Mutation): WorkspacePersistedReconciliation {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                throw IOException("old workspace read completed late")
            }
        }
        val owner = WorkspaceVisibilityReconciler(this, authority, {}, { refreshes++ }, { _, _ -> failures++ })
        owner.update(visible())
        runCurrent()
        entered.await()
        owner.close()
        release.complete(Unit)
        advanceUntilIdle()
        owner.update(visible("another-workspace.txt"))
        advanceUntilIdle()
        assertEquals(0, refreshes)
        assertEquals(0, failures)
    }

    @Test fun aSupersededPathRequestDoesNotRefreshThePreviousDocument() = runTest {
        val paths = mutableListOf<String>()
        val authority = object : StubAuthority() {
            override suspend fun persistedMutation(mutation: FileRepository.Mutation): WorkspacePersistedReconciliation {
                paths += (mutation as FileRepository.Mutation.Sync).path
                return WorkspacePersistedReconciliation()
            }
        }
        val owner = WorkspaceVisibilityReconciler(this, authority, {}, {}, { _, error -> throw AssertionError(error) })
        owner.update(visible("old.txt"))
        owner.update(visible("current.txt"))
        advanceUntilIdle()
        assertEquals(listOf("current.txt"), paths)
        owner.close()
    }

    private open class StubAuthority : WorkspaceDocumentAuthority {
        override suspend fun snapshot(path: String): WorkspaceDocumentSnapshot? = null
        override suspend fun snapshots(): List<WorkspaceDocumentSnapshot> = emptyList()
    }
}
