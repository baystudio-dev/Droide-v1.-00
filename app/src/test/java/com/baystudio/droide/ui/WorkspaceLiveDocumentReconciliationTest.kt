package com.baystudio.droide.ui

import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.PathSecurity
import com.baystudio.droide.core.WorkspaceDocumentAuthority
import com.baystudio.droide.core.WorkspaceDocumentConflictException
import com.baystudio.droide.core.WorkspaceDocumentSnapshot
import com.baystudio.droide.core.WorkspacePersistedReconciliation
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class WorkspaceLiveDocumentReconciliationTest {
    private class Watch : WorkspaceDocumentWatchFactory {
        lateinit var event: (Boolean) -> Unit
        var closes = 0
        override fun open(path: String, invalidate: (Boolean) -> Unit): AutoCloseable {
            event = invalidate
            return AutoCloseable { closes++ }
        }
    }
    private fun visible() = WorkspaceVisibilityReconciler.Visibility(true, true, false, "active.txt")

    @Test fun backgroundTerminalWriteRefreshesVisibleDocumentWithoutPaneSwitchOrOutboundSafWrite() = runTest {
        val root = Files.createTempDirectory("live-document-").toFile()
        try {
            root.resolve("active.txt").writeText("before")
            root.resolve("other.txt").writeText("other before")
            var outbound = 0
            val files = FileRepository(root, onMutation = { outbound++ })
            val state = EditorWorkspaceState()
            val active = state.document("active.txt").also { it.ensureLoaded(files) }
            val other = state.document("other.txt").also { it.ensureLoaded(files) }
            val actual = EditorWorkspaceDocumentAuthority(state, Dispatchers.Unconfined, files)
            val watch = Watch()
            val updates = mutableListOf<String>()
            var finished = CompletableDeferred<Unit>()
            val authority = object : WorkspaceDocumentAuthority by actual {
                override suspend fun persistedMutation(mutation: FileRepository.Mutation): WorkspacePersistedReconciliation {
                    updates += (mutation as FileRepository.Mutation.Sync).path
                    return actual.persistedMutation(mutation).also { finished.complete(Unit) }
                }
                override suspend fun reconcilePersistedWorkspace(): WorkspacePersistedReconciliation =
                    error("Visible watcher must not scan other documents")
            }
            val owner = WorkspaceVisibilityReconciler(this, authority, {}, {},
                { _, failure -> throw AssertionError(failure) }, watch, StandardTestDispatcher(testScheduler))
            try {
                owner.update(visible()); runCurrent(); finished.await()
                finished = CompletableDeferred()
                root.resolve("active.txt").writeText("background terminal content")
                root.resolve("other.txt").writeText("unrelated disk content")
                repeat(100) { watch.event(false) }
                advanceUntilIdle(); finished.await()
                assertEquals("background terminal content", active.content)
                assertEquals("other before", other.content)
                assertEquals(listOf("active.txt", "active.txt"), updates)
                assertEquals(0, outbound)
            } finally { owner.close(); runCurrent() }
            assertEquals(1, watch.closes)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun backgroundWritePreservesDirtyBufferAndReportsConflictWithoutOverwrite() = runTest {
        val root = Files.createTempDirectory("live-document-conflict-").toFile()
        try {
            root.resolve("active.txt").writeText("baseline")
            val files = FileRepository(root)
            val state = EditorWorkspaceState()
            val active = state.document("active.txt").also { it.ensureLoaded(files) }
            val actual = EditorWorkspaceDocumentAuthority(state, Dispatchers.Unconfined, files)
            val first = CompletableDeferred<Unit>()
            val conflict = CompletableDeferred<Exception>()
            val authority = object : WorkspaceDocumentAuthority by actual {
                override suspend fun persistedMutation(mutation: FileRepository.Mutation): WorkspacePersistedReconciliation =
                    actual.persistedMutation(mutation).also { first.complete(Unit) }
            }
            val watch = Watch()
            val owner = WorkspaceVisibilityReconciler(this, authority, {}, {}, { _, failure -> conflict.complete(failure) },
                watch, StandardTestDispatcher(testScheduler))
            try {
                owner.update(visible()); runCurrent(); first.await()
                active.capture("unsaved editor changes")
                root.resolve("active.txt").writeText("external changes")
                watch.event(false); advanceUntilIdle()
                assertTrue(conflict.await() is WorkspaceDocumentConflictException)
                assertEquals("unsaved editor changes", active.content)
                assertTrue(active.dirty)
                assertEquals("external changes", root.resolve("active.txt").readText())
            } finally { owner.close(); runCurrent() }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun coalescedHintsSerializeSlowReadsAndIgnoreRetiredWorkspaceEvents() = runTest {
        val firstEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var reads = 0
        var active = 0
        var peak = 0
        val authority = object : WorkspaceDocumentAuthority {
            override suspend fun snapshot(path: String): WorkspaceDocumentSnapshot? = null
            override suspend fun snapshots(): List<WorkspaceDocumentSnapshot> = emptyList()
            override suspend fun persistedMutation(mutation: FileRepository.Mutation): WorkspacePersistedReconciliation {
                reads++; active++; peak = maxOf(peak, active)
                try {
                    if (reads == 1) {
                        firstEntered.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                    }
                    return WorkspacePersistedReconciliation()
                } finally { active-- }
            }
        }
        val watch = Watch()
        val owner = WorkspaceVisibilityReconciler(this, authority, {}, {}, { _, error -> throw AssertionError(error) },
            watch, StandardTestDispatcher(testScheduler))
        owner.update(visible()); runCurrent(); firstEntered.await()
        repeat(100) { watch.event(false) }
        advanceUntilIdle()
        assertEquals(1, reads)
        release.complete(Unit); advanceUntilIdle()
        assertEquals(2, reads)
        assertEquals(1, peak)
        val oldEvent = watch.event
        owner.close(); runCurrent()
        oldEvent(true); advanceUntilIdle()
        assertEquals(2, reads)
        assertEquals(1, watch.closes)
    }
}
