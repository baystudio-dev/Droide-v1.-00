package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EditorLspModeTransitionTest {
    private class RecordingLsp : LspManager {
        override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
        val events = mutableListOf<EditorLspAction>()
        override suspend fun diagnose(relPath: String) = ""
        override suspend fun lsp(op: String, path: String, line: Int, col: Int) = ""
        override suspend fun didOpen(path: String, text: String) { events += EditorLspAction.Open(path, text) }
        override suspend fun didChange(path: String, text: String) { events += EditorLspAction.Change(path, text) }
        override suspend fun didClose(path: String) { events += EditorLspAction.Close(path) }
    }

    private class Fixture(val root: File) {
        val path = "Background.kt"
        val initial = "val original = 1\n"
        val file = root.resolve(path).apply { writeText(initial) }
        val files = FileRepository(root)
        val state = EditorWorkspaceState()
        val document = state.document(path)
        val lsp = RecordingLsp()
        // No EditorPane is mounted: background document synchronization must stand alone.
        val authority = EditorWorkspaceDocumentAuthority(state, Dispatchers.Unconfined, files, lsp)

        suspend fun start() { document.ensureLoaded(files) }
        suspend fun publish(text: String, refresh: Boolean = false) {
            file.writeText(text)
            val mutation = if (refresh) FileRepository.Mutation.Sync(path) else FileRepository.Mutation.Write(path, text)
            val result = authority.persistedMutation(mutation)
            assertTrue(result.conflicts.isEmpty())
            assertEquals(text, document.content)
            assertFalse(document.dirty)
        }
    }

    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val root = Files.createTempDirectory("editor-lsp-mode-").toFile()
        try { Fixture(root).apply { start() }.block() }
        finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun persistedWriteClosesAtLineLimitAndReopensWithTheLatestText() = fixture {
        val large = "x\n".repeat(EditorLargeFilePolicy.FULL_INTELLIGENCE_MAX_LINES)
        publish(large)
        assertTrue(document.largeFileOptimized)
        assertEquals(listOf(EditorLspAction.Close(path)), lsp.events)

        lsp.events.clear()
        publish(large + "// still large")
        assertTrue(lsp.events.isEmpty())

        val small = "val current = 2\n"
        publish(small)
        assertTrue(document.fullIntelligence)
        assertEquals(listOf(EditorLspAction.Open(path, small)), lsp.events)
    }

    @Test fun diskRefreshUsesTheSameCloseAndReopenTransitions() = fixture {
        val large = "x\n".repeat(EditorLargeFilePolicy.FULL_INTELLIGENCE_MAX_LINES)
        publish(large, refresh = true)
        assertEquals(listOf(EditorLspAction.Close(path)), lsp.events)
        lsp.events.clear()
        publish(initial, refresh = true)
        assertEquals(listOf(EditorLspAction.Open(path, initial)), lsp.events)
    }

    @Test fun byteLimitRefreshClosesLspAfterTheBoundedPreviewReadPath() = fixture {
        publish("x".repeat(EditorLargeFilePolicy.FULL_INTELLIGENCE_MAX_BYTES.toInt() + 1), refresh = true)
        assertTrue(document.largeFileOptimized)
        assertEquals(listOf(EditorLspAction.Close(path)), lsp.events)
        lsp.events.clear()
        publish(initial, refresh = true)
        assertEquals(listOf(EditorLspAction.Open(path, initial)), lsp.events)
    }

    @Test fun ordinaryWritesChangeTheExistingLspDocumentAndIdenticalWritesStayQuiet() = fixture {
        val changed = "val changed = 3\n"
        publish(changed)
        assertEquals(listOf(EditorLspAction.Change(path, changed)), lsp.events)
        lsp.events.clear()
        publish(changed)
        assertTrue(lsp.events.isEmpty())
    }

    @Test fun rejectedWriteKeepsUnsavedTextAndDoesNotChangeLspLifecycle() = fixture {
        document.capture("val unsaved = 4\n")
        val failure = runCatching {
            authority.persistedMutation(FileRepository.Mutation.Write(path, "x\n".repeat(300_000)))
        }.exceptionOrNull()
        assertTrue(failure is WorkspaceDocumentConflictException)
        assertEquals("val unsaved = 4\n", document.content)
        assertTrue(document.dirty)
        assertTrue(lsp.events.isEmpty())
    }
}
