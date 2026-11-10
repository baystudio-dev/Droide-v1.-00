package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DeferredEditorRecoveryTest {
    @Test fun repeatedRecoveryKeepsTheExistingDeferredBufferWithoutReadingIt() = runBlocking {
        val root = Files.createTempDirectory("droide-recovery-repeat-").toFile()
        try {
            var reads = 0
            val ref = EditorRecoveryBuffer("one.txt", "fake.bin", null, Any()) { reads++; "unsaved" }
            val state = EditorWorkspaceState()
            val snapshot = EditorRecoverySnapshot(listOf("one.txt"), "one.txt", emptyMap(), deferredBuffers = mapOf("one.txt" to ref))
            val files = FileRepository(root)
            assertEquals(listOf("one.txt"), EditorTabRetention.restoreDocuments(snapshot, files, state))
            assertEquals(listOf("one.txt"), EditorTabRetention.restoreDocuments(snapshot, files, state))
            assertSame(ref, state.document("one.txt").pendingRecoveryBuffer)
            assertEquals(0, reads)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun staleRecoveryNeverReplacesNewerLiveEditsAndStillRestoresOtherFiles() = runBlocking {
        val root = Files.createTempDirectory("droide-recovery-live-").toFile()
        try {
            root.resolve("one.txt").writeText("saved")
            val files = FileRepository(root)
            val state = EditorWorkspaceState()
            val live = state.document("one.txt")
            live.ensureLoaded(files)
            live.capture("newer live changes")
            live.captureSelection(2, 8)
            live.setReviewMode(true)
            val snapshot = EditorRecoverySnapshot(listOf("one.txt", "two.txt"), "one.txt",
                mapOf("one.txt" to "older checkpoint", "two.txt" to "other unsaved file"),
                selections = mapOf("one.txt" to EditorSelectionSnapshot(0, 0)))
            EditorTabRetention.restoreDocuments(snapshot, files, state)
            assertEquals("newer live changes", live.content)
            assertEquals("saved", live.savedContent)
            assertEquals(2, live.selectionStart)
            assertEquals(8, live.selectionEnd)
            assertTrue(live.reviewMode)
            assertTrue(live.dirty)
            assertEquals("other unsaved file", state.document("two.txt").content)
            assertEquals("saved", root.resolve("one.txt").readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun staleDeferredRecoveryDoesNotReplaceNewerLoadedEdits() = runBlocking {
        val root = Files.createTempDirectory("droide-recovery-stale-deferred-").toFile()
        try {
            root.resolve("one.txt").writeText("saved")
            val files = FileRepository(root)
            val state = EditorWorkspaceState()
            val live = state.document("one.txt")
            live.ensureLoaded(files)
            live.capture("live")
            var reads = 0
            val ref = EditorRecoveryBuffer("one.txt", "fake.bin", null, Any()) { reads++; "older" }
            val snapshot = EditorRecoverySnapshot(listOf("one.txt"), "one.txt", emptyMap(), deferredBuffers = mapOf("one.txt" to ref))
            EditorTabRetention.restoreDocuments(snapshot, files, state)
            assertEquals("live", live.content)
            assertNull(live.pendingRecoveryBuffer)
            assertEquals(0, reads)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun manyRecoveredTabsDoNotReadAnyBodyUntilOpened() = runBlocking {
        val root = Files.createTempDirectory("droide-lazy-recovery-").toFile()
        try {
            var reads = 0
            val refs = (1..40).associate { index ->
                val path = "file$index.txt"
                path to EditorRecoveryBuffer(path, "fake.bin", null, Any()) { reads++; "unsaved" }
            }
            val state = EditorWorkspaceState()
            val snapshot = EditorRecoverySnapshot(refs.keys.toList(), "file1.txt", emptyMap(), deferredBuffers = refs)
            val tabs = EditorTabRetention.restoreDocuments(snapshot, FileRepository(root), state)
            assertEquals(40, tabs.size)
            assertEquals(40, state.dirtyDocuments().size)
            assertEquals(0, reads)
            assertTrue(state.allDocuments().none { it.loaded })
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun openingOneBufferRestoresItsBaselineSelectionAndReviewMode() = runBlocking {
        val root = Files.createTempDirectory("droide-recovery-open-").toFile()
        try {
            root.resolve("one.txt").writeText("original")
            var reads = 0
            val ref = EditorRecoveryBuffer("one.txt", "fake.bin", null, Any()) { reads++; "recovered changes" }
            val doc = EditorDocument("one.txt")
            doc.restoreDeferred(ref, EditorSelectionSnapshot(3, 8), review = true)
            doc.ensureLoaded(FileRepository(root))
            assertEquals(1, reads)
            assertEquals("recovered changes", doc.content)
            assertEquals("original", doc.savedContent)
            assertEquals(3, doc.selectionStart); assertEquals(8, doc.selectionEnd)
            assertTrue(doc.reviewMode); assertTrue(doc.dirty); assertNull(doc.pendingRecoveryBuffer)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun unreadableRecoveryStaysDirtyAndBlocksPersistedOverwrite() = runBlocking {
        val root = Files.createTempDirectory("droide-recovery-failed-").toFile()
        try {
            val files = FileRepository(root)
            val state = EditorWorkspaceState()
            val doc = state.document("one.txt")
            doc.restoreDeferred(EditorRecoveryBuffer("one.txt", "fake.bin", null, Any()) { error("blob unavailable") })
            doc.ensureLoaded(files)
            assertTrue(doc.dirty); assertNotNull(doc.pendingRecoveryBuffer)
            val authority = EditorWorkspaceDocumentAuthority(state, Dispatchers.Unconfined, files)
            assertTrue(runCatching { authority.validatePersistedMutation(FileRepository.Mutation.Write("one.txt", "disk")) }.exceptionOrNull() is WorkspaceDocumentConflictException)
            assertTrue(doc.save(files).isFailure)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun discardingOneDeferredBufferPreservesTheOther() {
        val state = EditorWorkspaceState()
        for (path in listOf("one.txt", "two.txt")) state.document(path).restoreDeferred(EditorRecoveryBuffer(path, "fake.bin", null, Any()) { "unsaved" })
        state.document("one.txt").discardUnsaved()
        assertEquals(listOf("two.txt"), state.dirtyDocuments().map { it.path })
        assertEquals(setOf("two.txt"), state.deferredRecoveryBuffers().keys)
    }
}
