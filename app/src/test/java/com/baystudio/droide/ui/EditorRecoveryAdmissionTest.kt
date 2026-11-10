package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class EditorRecoveryAdmissionTest {
    private suspend fun project(block: suspend (File, FileRepository) -> Unit) {
        val root = Files.createTempDirectory("droide-recovery-admission-").toFile()
        try { block(root, FileRepository(root)) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun cleanTabRestoresSelectionAndReviewWithoutLoadingTextUntilOpened() = runBlocking {
        project { root, files ->
            root.resolve("a.txt").writeText("saved text")
            val state = EditorWorkspaceState()
            val snapshot = EditorRecoverySnapshot(listOf("a.txt", "missing.txt"), "a.txt", emptyMap(),
                selections = mapOf("a.txt" to EditorSelectionSnapshot(2, 8)), reviewFiles = setOf("a.txt"))
            assertEquals(listOf("a.txt"), EditorTabRetention.restoreDocuments(snapshot, files, state))
            val doc = state.document("a.txt")
            assertFalse(doc.loaded); assertEquals("", doc.content)
            assertEquals(2, doc.selectionStart); assertEquals(8, doc.selectionEnd); assertTrue(doc.reviewMode)
            assertNull(state.peek("missing.txt"))
            root.resolve("a.txt").writeText("new")
            doc.ensureLoaded(files)
            assertEquals("new", doc.content)
            assertEquals(2, doc.selectionStart); assertEquals(3, doc.selectionEnd)
            assertTrue(doc.reviewMode); assertFalse(doc.canEdit); assertFalse(doc.dirty)
        }
    }

    @Test fun newerCleanSelectionAndReviewChoiceWinOverTheCheckpointMetadata() = runBlocking {
        project { root, files ->
            root.resolve("a.txt").writeText("saved text")
            val state = EditorWorkspaceState()
            val doc = state.document("a.txt")
            doc.ensureLoaded(files)
            val admission = state.beginRecovery()
            doc.captureSelection(4, 7); doc.setReviewMode(true)
            val checkpoint = EditorRecoverySnapshot(listOf("a.txt"), "a.txt", emptyMap(),
                selections = mapOf("a.txt" to EditorSelectionSnapshot(0, 0)))
            EditorTabRetention.restoreDocuments(checkpoint, files, state, admission)
            assertEquals(4, doc.selectionStart); assertEquals(7, doc.selectionEnd); assertTrue(doc.reviewMode)
            assertEquals("saved text", doc.content)
        }
    }

    @Test fun editThenSaveDuringDelayedRecoveryKeepsNewerSourceAndPersistsTheOlderRecoveryCopy() = runBlocking {
        project { root, files ->
            root.resolve("a.txt").writeText("saved baseline")
            val state = EditorWorkspaceState()
            val doc = state.document("a.txt")
            doc.ensureLoaded(files)
            val admission = state.beginRecovery()
            doc.capture("newer user save")
            assertTrue(doc.save(files).isSuccess)
            assertFalse(doc.dirty)
            val checkpoint = EditorRecoverySnapshot(listOf("a.txt"), "a.txt", mapOf("a.txt" to "older unsaved recovery"))
            EditorTabRetention.restoreDocuments(checkpoint, files, state, admission)
            assertEquals("newer user save", doc.content)
            assertEquals("newer user save", root.resolve("a.txt").readText())
            val saved = checkNotNull(state.recoverySnapshot(listOf("a.txt"), "a.txt"))
            assertEquals(mapOf("a.txt" to "older unsaved recovery"), saved.conflictBuffers)
            assertTrue(saved.dirtyBuffers.isEmpty())
            val restarted = EditorWorkspaceState()
            EditorTabRetention.restoreDocuments(saved, files, restarted)
            restarted.document("a.txt").ensureLoaded(files)
            assertEquals("newer user save", restarted.document("a.txt").content)
            assertEquals(saved.conflictBuffers, checkNotNull(restarted.recoverySnapshot(emptyList(), "")).conflictBuffers)
        }
    }

    @Test fun deferredConflictRemainsLazyAndAThirdVersionCannotReplaceItSilently() = runBlocking {
        project { root, files ->
            root.resolve("a.txt").writeText("saved")
            var reads = 0
            val older = EditorRecoveryBuffer("a.txt", "older.bin", null, Any()) { reads++; "old recovery" }
            val state = EditorWorkspaceState()
            val admission = state.beginRecovery()
            val doc = state.document("a.txt")
            doc.ensureLoaded(files); doc.capture("newer user work")
            val checkpoint = EditorRecoverySnapshot(listOf("a.txt"), "a.txt", emptyMap(), deferredBuffers = mapOf("a.txt" to older))
            EditorTabRetention.restoreDocuments(checkpoint, files, state, admission)
            val saved = checkNotNull(state.recoverySnapshot(listOf("a.txt"), "a.txt"))
            assertSame(older, saved.deferredConflictBuffers["a.txt"])
            assertEquals("newer user work", saved.dirtyBuffers["a.txt"])
            assertEquals(0, reads)
            assertTrue(runCatching { state.retainRecoveryConflict("a.txt", "third version", null) }.isFailure)
            assertSame(older, checkNotNull(state.recoverySnapshot(emptyList(), "")).deferredConflictBuffers["a.txt"])
        }
    }

    @Test fun delayedRecoveryKeepsNewerTabsAndTerminalSurfaceButAddsRecoveredDirtyFiles() = runBlocking {
        project { root, files ->
            root.resolve("old.txt").writeText("old")
            root.resolve("new.txt").writeText("new")
            val state = EditorWorkspaceState()
            val initial = EditorWorkspaceState.Navigation(listOf("old.txt"), "old.txt", 1)
            state.retainNavigation(initial.openFiles, initial.activeFile, initial.tab)
            val admission = state.beginRecovery(initial)
            val current = EditorWorkspaceState.Navigation(listOf("new.txt"), "new.txt", 2)
            // No SideEffect has run yet: the immediate navigation tuple must still protect this event.
            val recovered = EditorRecoverySnapshot(listOf("old.txt", "unsaved.txt"), "old.txt", mapOf("unsaved.txt" to "recovery"))
            val visible = EditorTabRetention.restoreDocuments(recovered, files, state, admission)
            val next = EditorTabRetention.recoveryNavigation(state, admission, current, recovered, visible, "old.txt")
            assertEquals(listOf("new.txt", "unsaved.txt"), next.openFiles)
            assertEquals("new.txt", next.activeFile); assertEquals(2, next.tab)
            assertEquals("recovery", state.document("unsaved.txt").content)
        }
    }

    @Test fun navigatedAwayAndBackStillDoesNotReopenOldCleanTabs() = runBlocking {
        project { root, files ->
            root.resolve("a").writeText("a"); root.resolve("old").writeText("old")
            val state = EditorWorkspaceState()
            val initial = EditorWorkspaceState.Navigation(listOf("a"), "a", 1)
            state.retainNavigation(initial.openFiles, initial.activeFile, initial.tab)
            val admission = state.beginRecovery(initial)
            state.retainNavigation(listOf("a"), "a", 2)
            state.retainNavigation(initial.openFiles, initial.activeFile, initial.tab)
            val recovered = EditorRecoverySnapshot(listOf("a", "old"), "old", emptyMap())
            val visible = EditorTabRetention.restoreDocuments(recovered, files, state, admission)
            assertEquals(initial, EditorTabRetention.recoveryNavigation(state, admission, initial, recovered, visible, "old"))
        }
    }

    @Test fun unchangedStartupRestoresNavigationAndAbnormalStartupStillDefersReopen() = runBlocking {
        project { root, files ->
            root.resolve("a").writeText("a")
            for (active in listOf("a", "")) {
                val state = EditorWorkspaceState()
                val initial = EditorWorkspaceState.Navigation(emptyList(), "", 0)
                val admission = state.beginRecovery(initial)
                val recovered = EditorRecoverySnapshot(listOf("a"), "a", emptyMap())
                val visible = EditorTabRetention.restoreDocuments(recovered, files, state, admission)
                val next = EditorTabRetention.recoveryNavigation(state, admission, initial, recovered, visible, active)
                assertEquals(listOf("a"), next.openFiles)
                assertEquals(active, next.activeFile); assertEquals(if (active.isBlank()) 0 else 1, next.tab)
            }
        }
    }

    @Test fun obsoleteRecoveryOwnerCannotPublishAndDirtyOverflowKeepsActiveCleanTab() = runBlocking {
        project { root, files ->
            root.resolve("active").writeText("a")
            val state = EditorWorkspaceState()
            val initial = EditorWorkspaceState.Navigation(emptyList(), "", 0)
            val stale = state.beginRecovery(initial)
            val admission = state.beginRecovery(initial)
            val recovered = EditorRecoverySnapshot(emptyList(), "", (1..40).associate { "dirty$it" to "unsaved $it" })
            assertTrue(runCatching { EditorTabRetention.restoreDocuments(recovered, files, state, stale) }.exceptionOrNull() is CancellationException)
            assertTrue(state.allDocuments().isEmpty())
            val visible = EditorTabRetention.restoreDocuments(recovered, files, state, admission)
            val current = EditorWorkspaceState.Navigation(listOf("active"), "active", 1)
            val next = EditorTabRetention.recoveryNavigation(state, admission, current, recovered, visible, "")
            assertEquals(41, next.openFiles.size); assertEquals("active", next.activeFile)
            assertTrue(next.openFiles.containsAll(recovered.dirtyPaths))
            state.cancelRecovery(admission)
            assertTrue(runCatching { EditorTabRetention.recoveryNavigation(state, admission, current, recovered, visible, "") }.isFailure)
        }
    }
}
