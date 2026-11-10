package com.baystudio.droide.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import com.baystudio.droide.core.*
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class EditorLspDocumentEffectTest {
    private class RecordingLsp(private val suspendOpen: Boolean = false) : LspManager {
        override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
        val calls = mutableListOf<String>()
        var lastText: String? = null
        override suspend fun diagnose(relPath: String) = ""
        override suspend fun lsp(op: String, path: String, line: Int, col: Int) = ""
        override suspend fun didOpen(path: String, text: String) {
            calls += "open:$path"; lastText = text
            if (suspendOpen) awaitCancellation()
        }
        override suspend fun didChange(path: String, text: String) { calls += "change:$path"; lastText = text }
        override suspend fun didClose(path: String) { calls += "close:$path"; lastText = null }
    }

    private class NoUiApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = error("This effect must not emit UI")
        override fun insertBottomUp(index: Int, instance: Unit) = error("This effect must not emit UI")
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    private fun fixture(
        lsp: RecordingLsp = RecordingLsp(),
        block: suspend (EditorDocument, FileRepository, RecordingLsp, suspend (Int) -> Unit) -> Unit,
    ) = runBlocking {
        val root = Files.createTempDirectory("editor-lsp-effect-").toFile()
        val files = FileRepository(root)
        val path = "Main.kt"
        root.resolve(path).writeText("val initial = 1\n")
        val document = EditorWorkspaceState().document(path).also { it.ensureLoaded(files) }
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val worker = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoUiApplier(), recomposer)
        suspend fun awaitCalls(count: Int) = withTimeout(3_000) {
            while (lsp.calls.size < count) {
                Snapshot.sendApplyNotifications()
                clock.sendFrame(System.nanoTime())
                delay(1)
            }
        }
        try {
            composition.setContent { EditorLspDocumentEffect(document, path, lsp) }
            awaitCalls(1)
            block(document, files, lsp, ::awaitCalls)
            val beforeDispose = lsp.calls.toList()
            composition.dispose()
            yield()
            assertEquals("Switching away from the pane must keep workspace documents open", beforeDispose, lsp.calls)
        } finally {
            composition.dispose(); recomposer.cancel(); worker.cancelAndJoin()
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    @Test fun liveEditingClosesAtTheLimitAndReopensWhenTheDocumentShrinks() = fixture { document, _, lsp, await ->
        assertEquals(listOf("open:Main.kt"), lsp.calls)
        document.capture("x\n".repeat(EditorLargeFilePolicy.FULL_INTELLIGENCE_MAX_LINES))
        await(2)
        assertEquals(listOf("open:Main.kt", "close:Main.kt"), lsp.calls)
        assertNull(lsp.lastText)
        document.capture("val smaller = 2\n")
        await(3)
        assertEquals(listOf("open:Main.kt", "close:Main.kt", "open:Main.kt"), lsp.calls)
        assertEquals(document.content, lsp.lastText)
    }

    @Test fun aDiskReloadSynchronizesEvenWhenTheTypingVersionDidNotChange() = fixture { document, files, lsp, await ->
        val typingVersion = document.changeVersion
        files.root.resolve("Main.kt").writeText("val reloaded = 3\n")
        document.reload(files)
        assertEquals(typingVersion, document.changeVersion)
        await(2)
        assertEquals(listOf("open:Main.kt", "change:Main.kt"), lsp.calls)
        assertEquals("val reloaded = 3\n", lsp.lastText)
    }

    @Test fun cancellingAnOpenMidwayStillClosesItsRegisteredDocument() = fixture(RecordingLsp(suspendOpen = true)) { document, _, lsp, await ->
        document.capture("x\n".repeat(EditorLargeFilePolicy.FULL_INTELLIGENCE_MAX_LINES))
        await(2)
        assertEquals(listOf("open:Main.kt", "close:Main.kt"), lsp.calls)
        assertNull(lsp.lastText)
    }
}
