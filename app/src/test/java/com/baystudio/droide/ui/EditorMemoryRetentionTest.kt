package com.baystudio.droide.ui

import com.baystudio.droide.core.FileRepository
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EditorMemoryRetentionTest {
    @Test fun repeatedLargeCleanFileOpensRetainOnlyActiveTextAndPreserveTabState() = runBlocking {
        val root = Files.createTempDirectory("editor-retention-").toFile()
        try {
            val state = EditorWorkspaceState()
            val files = FileRepository(root)
            repeat(40) { index ->
                val path = "file$index.txt"
                root.resolve(path).writeText("x".repeat(256 * 1024))
                state.protectActiveEditor(path)
                state.trimInactiveCleanDocuments()
                val document = state.document(path)
                document.ensureLoaded(files)
                document.captureSelection(40, 45)
                document.setReviewMode(true)
                assertEquals(1, state.allDocuments().count { it.loaded })
                assertEquals(256 * 1024, state.allDocuments().sumOf { it.content.length })
            }
            assertEquals(40, state.allDocuments().size)
            val first = state.document("file0.txt")
            assertEquals(40, first.selectionStart)
            assertEquals(45, first.selectionEnd)
            assertTrue(first.reviewMode)
            assertFalse(first.loaded)
            assertEquals("", first.content)
            assertEquals("", first.savedContent)
            state.protectActiveEditor(first.path)
            state.trimInactiveCleanDocuments()
            first.ensureLoaded(files)
            assertEquals(256 * 1024, first.content.length)
            assertTrue(first.reviewMode)
            assertEquals(40, first.selectionStart)
        } finally { root.deleteRecursively() }
    }

    @Test fun trimmingCannotDiscardUnsavedTextOrItsConflictBaseline() = runBlocking {
        val root = Files.createTempDirectory("editor-dirty-retention-").toFile()
        try {
            root.resolve("dirty.kt").writeText("saved baseline")
            root.resolve("active.kt").writeText("active")
            root.resolve("clean.kt").writeText("clean")
            val state = EditorWorkspaceState()
            val files = FileRepository(root)
            val dirty = state.document("dirty.kt").also { it.ensureLoaded(files); it.capture("unsaved edit") }
            val active = state.document("active.kt").also { it.ensureLoaded(files) }
            val clean = state.document("clean.kt").also { it.ensureLoaded(files) }
            state.protectActiveEditor(active.path)
            assertEquals(listOf(clean.path), state.trimInactiveCleanDocuments())
            assertTrue(active.loaded)
            assertTrue(dirty.loaded)
            assertTrue(dirty.dirty)
            assertEquals("unsaved edit", dirty.content)
            assertEquals("saved baseline", dirty.savedContent)
            root.resolve(dirty.path).writeText("external change")
            assertTrue(dirty.save(files).isFailure)
            assertEquals("external change", root.resolve(dirty.path).readText())
            assertEquals("unsaved edit", dirty.content)
        } finally { root.deleteRecursively() }
    }

    @Test fun reopeningUnloadedCleanTabReadsExternalEditsInsteadOfStaleCache() = runBlocking {
        val root = Files.createTempDirectory("editor-reload-retention-").toFile()
        try {
            val file = root.resolve("file.kt").apply { writeText("old text") }
            val files = FileRepository(root)
            val state = EditorWorkspaceState()
            val document = state.document("file.kt").also { it.ensureLoaded(files) }
            state.protectActiveEditor("other.kt")
            assertEquals(listOf(document.path), state.trimInactiveCleanDocuments())
            file.writeText("updated by terminal")
            state.protectActiveEditor(document.path)
            document.ensureLoaded(files)
            assertEquals("updated by terminal", document.content)
            assertFalse(document.dirty)
        } finally { root.deleteRecursively() }
    }

    @Test fun memoryTrimDuringSaveKeepsTheSavedSnapshotUntilCommitCompletes() = runBlocking {
        val root = Files.createTempDirectory("editor-saving-retention-").toFile()
        try {
            root.resolve("file.kt").writeText("baseline")
            val state = EditorWorkspaceState()
            val document = state.document("file.kt")
            var trimmedDuringCommit: List<String>? = null
            val files = FileRepository(root, onMutation = {
                trimmedDuringCommit = state.trimInactiveCleanDocuments()
                assertTrue(document.loaded)
            })
            document.ensureLoaded(files)
            document.capture("saved edit")
            state.protectActiveEditor("other.kt")
            assertTrue(document.save(files).isSuccess)
            assertEquals(emptyList<String>(), trimmedDuringCommit)
            assertEquals("saved edit", root.resolve("file.kt").readText())
            assertEquals(listOf(document.path), state.trimInactiveCleanDocuments())
        } finally { root.deleteRecursively() }
    }
}
