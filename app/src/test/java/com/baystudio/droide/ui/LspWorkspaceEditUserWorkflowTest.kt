package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LspWorkspaceEditUserWorkflowTest {
    private fun edit(expected: Map<String, String>) = LspWorkspaceEdit(
        expected.keys.map { LspTextEdit(it, TextRangeEdit(1, 1, 1, 1, "renamed ")) }, expected
    )

    private suspend fun apply(edit: LspWorkspaceEdit, state: EditorWorkspaceState, files: FileRepository) {
        val prepared = LspWorkspaceEditPreflight.prepare(edit, state, files)
        LspWorkspaceEditPreflight.requireCurrent(edit)
        prepared.forEach { (path, updated) -> state.document(path).applyWorkspaceText(updated) }
    }

    @Test fun staleCapturedOwnerRejectsBeforeLoadingOrDirtyingAnyBuffer() = runBlocking {
        val root = Files.createTempDirectory("lsp-owner-stale-").toFile()
        try {
            root.resolve("A.svelte").writeText("a")
            val state = EditorWorkspaceState()
            val guarded = edit(mapOf("A.svelte" to "a")).copy(applicationGuard = { false })
            assertTrue(runCatching { apply(guarded, state, FileRepository(root)) }.isFailure)
            assertFalse(state.document("A.svelte").loaded)
            assertFalse(state.document("A.svelte").dirty)
            assertEquals("a", root.resolve("A.svelte").readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun ownerRetirementDuringDeferredLoadRejectsWholeBatch() = runBlocking {
        val root = Files.createTempDirectory("lsp-owner-retired-").toFile()
        try {
            var current = true
            val files = FileRepository(root); val state = EditorWorkspaceState()
            root.resolve("A.svelte").writeText("a"); root.resolve("B.ts").writeText("b")
            state.document("B.ts").restoreDeferred(EditorRecoveryBuffer("B.ts", "fixture", null, this) {
                current = false
                "b"
            })
            val guarded = edit(linkedMapOf("A.svelte" to "a", "B.ts" to "b")).copy(applicationGuard = { current })
            assertTrue(runCatching { apply(guarded, state, files) }.isFailure)
            assertEquals("a", state.document("A.svelte").content)
            assertFalse(state.document("A.svelte").dirty)
            assertEquals("b", state.document("B.ts").content)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun ownerRetirementBetweenPreflightAndAcceptanceRejectsWithoutMutation() = runBlocking {
        val root = Files.createTempDirectory("lsp-owner-acceptance-").toFile()
        try {
            var current = true
            root.resolve("A.svelte").writeText("a")
            val state = EditorWorkspaceState()
            val guarded = edit(mapOf("A.svelte" to "a")).copy(applicationGuard = { current })
            LspWorkspaceEditPreflight.prepare(guarded, state, FileRepository(root))
            current = false
            assertTrue(runCatching { LspWorkspaceEditPreflight.requireCurrent(guarded) }.isFailure)
            assertEquals("a", state.document("A.svelte").content)
            assertFalse(state.document("A.svelte").dirty)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun throwingOwnerGuardFailsClosed() {
        val guarded = edit(mapOf("A.svelte" to "a")).copy(applicationGuard = { error("observer unavailable") })
        assertTrue(runCatching { LspWorkspaceEditPreflight.requireCurrent(guarded) }.isFailure)
        LspWorkspaceEditPreflight.requireCurrent(edit(mapOf("A.kt" to "a")))
    }

    @Test fun currentCapturedOwnerAllowsReviewedMultiFileBufferApplication() = runBlocking {
        val root = Files.createTempDirectory("lsp-owner-current-").toFile()
        try {
            val files = FileRepository(root); val state = EditorWorkspaceState()
            val expected = linkedMapOf("A.svelte" to "a", "B.ts" to "b")
            expected.forEach { (path, text) -> root.resolve(path).writeText(text) }
            apply(edit(expected).copy(applicationGuard = { true }), state, files)
            expected.forEach { (path, text) ->
                assertEquals("renamed $text", state.document(path).content)
                assertTrue(state.document(path).dirty)
                assertEquals(text, root.resolve(path).readText())
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun equalTextAndCounterReplacementDuringDeferredLoadRejectsWholeBatch() = runBlocking {
        val root = Files.createTempDirectory("lsp-replaced-owner-").toFile()
        try {
            val files = FileRepository(root); val state = EditorWorkspaceState()
            root.resolve("A.kt").writeText("a"); root.resolve("B.kt").writeText("b")
            val original = state.document("A.kt"); original.ensureLoaded(files)
            val revision = original.revision; val version = original.changeVersion
            state.document("B.kt").restoreDeferred(EditorRecoveryBuffer("B.kt", "fixture", null, this) {
                state.forgetAtOrUnder("A.kt")
                val replacement = state.document("A.kt"); replacement.ensureLoaded(files)
                assertEquals(revision, replacement.revision); assertEquals(version, replacement.changeVersion)
                assertEquals(original.content, replacement.content)
                assertNotEquals(original.documentId, replacement.documentId)
                "b"
            })
            val failure = runCatching { apply(edit(linkedMapOf("A.kt" to "a", "B.kt" to "b")), state, files) }.exceptionOrNull()
            assertNotNull("A replacement must not inherit the prepared mutation", failure)
            assertEquals("a", original.content)
            assertEquals("a", state.document("A.kt").content); assertFalse(state.document("A.kt").dirty)
            assertEquals("b", state.document("B.kt").content)
            assertEquals("a", root.resolve("A.kt").readText()); assertEquals("b", root.resolve("B.kt").readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun reviewModeChangedDuringRecoveryLoadPreventsPartialRename() = runBlocking {
        val root = Files.createTempDirectory("lsp-rename-review-").toFile()
        try {
            val files = FileRepository(root); val state = EditorWorkspaceState()
            val expected = linkedMapOf("A.kt" to "a", "B.kt" to "b", "C.kt" to "c")
            expected.forEach { (path, text) -> root.resolve(path).writeText(text) }
            state.document("A.kt").ensureLoaded(files); state.document("B.kt").ensureLoaded(files)
            state.document("C.kt").restoreDeferred(EditorRecoveryBuffer("C.kt", "fixture", null, this) {
                state.document("B.kt").setReviewMode(true)
                "c"
            })
            assertTrue(runCatching { apply(edit(expected), state, files) }.isFailure)
            assertEquals("a", state.document("A.kt").content)
            assertFalse("Rejected multi-file rename must not partly dirty its first file", state.document("A.kt").dirty)
            assertEquals("b", state.document("B.kt").content); assertTrue(state.document("B.kt").reviewMode)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun unloadedEmptyBufferDuringRecoveryPreventsPartialRename() = runBlocking {
        val root = Files.createTempDirectory("lsp-rename-unload-").toFile()
        try {
            val files = FileRepository(root); val state = EditorWorkspaceState()
            val expected = linkedMapOf("A.kt" to "a", "B.kt" to "", "C.kt" to "c")
            expected.forEach { (path, text) -> root.resolve(path).writeText(text) }
            state.document("A.kt").ensureLoaded(files); state.document("B.kt").ensureLoaded(files)
            state.document("C.kt").restoreDeferred(EditorRecoveryBuffer("C.kt", "fixture", null, this) {
                state.document("B.kt").unloadClean()
                "c"
            })
            assertTrue(runCatching { apply(edit(expected), state, files) }.isFailure)
            assertEquals("a", state.document("A.kt").content); assertFalse(state.document("A.kt").dirty)
            assertFalse(state.document("B.kt").loaded)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun validMultiFileRenameChangesBuffersForReviewWithoutWritingDisk() = runBlocking {
        val root = Files.createTempDirectory("lsp-rename-valid-").toFile()
        try {
            val files = FileRepository(root); val state = EditorWorkspaceState()
            val expected = linkedMapOf("A.kt" to "a", "B.kt" to "b")
            expected.forEach { (path, text) -> root.resolve(path).writeText(text) }
            apply(edit(expected), state, files)
            expected.forEach { (path, text) ->
                assertEquals("renamed $text", state.document(path).content); assertTrue(state.document(path).dirty)
                assertEquals(text, root.resolve(path).readText())
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
