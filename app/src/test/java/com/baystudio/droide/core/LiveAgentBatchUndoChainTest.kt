package com.baystudio.droide.core

import com.baystudio.droide.ui.EditorWorkspaceDocumentAuthority
import com.baystudio.droide.ui.EditorWorkspaceState
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real editor versions, with an admission hook only for deterministic late conflicts. */
class LiveAgentBatchUndoChainTest {
    private enum class Batch { PATCH, LSP }

    @Test fun successfulPatchBatchUndoPreservesOlderLiveBatchUndo() = successfulChain(Batch.PATCH)
    @Test fun successfulLspBatchUndoPreservesOlderLiveBatchUndo() = successfulChain(Batch.LSP)
    @Test fun failedPatchBatchRollbackRestoresOlderLiveUndo() = rolledBackChain(Batch.PATCH)
    @Test fun failedLspBatchRollbackRestoresOlderLiveUndo() = rolledBackChain(Batch.LSP)
    @Test fun partialPatchRollbackSurvivingTargetCanUndoThenOlderBatchCanUndo() = partialRollback(Batch.PATCH)
    @Test fun partialLspRollbackSurvivingTargetCanUndoThenOlderBatchCanUndo() = partialRollback(Batch.LSP)
    @Test fun userEditAfterPatchRollbackStillBlocksOlderUndoEvenWhenTextReturns() = userEditAfterRollback(Batch.PATCH)
    @Test fun userEditAfterLspRollbackStillBlocksOlderUndoEvenWhenTextReturns() = userEditAfterRollback(Batch.LSP)

    @Test fun patchRollbackNotificationCancellationRetainsSurvivorAndOlderUndo() = interruptedRollback(Batch.PATCH)
    @Test fun lspRollbackNotificationCancellationRetainsSurvivorAndOlderUndo() = interruptedRollback(Batch.LSP)
    @Test fun patchRollbackFatalNotificationRetainsSurvivorAndOlderUndo() = interruptedRollback(Batch.PATCH, fatal = true)
    @Test fun lspRollbackFatalNotificationRetainsSurvivorAndOlderUndo() = interruptedRollback(Batch.LSP, fatal = true)
    @Test fun patchRollbackFatalOutranksOriginalCancellationWithoutLosingEither() = cancellationThenFatal(Batch.PATCH)
    @Test fun lspRollbackFatalOutranksOriginalCancellationWithoutLosingEither() = cancellationThenFatal(Batch.LSP)

    @Test fun patchRollbackReceiptHandoffPreservesConcurrentNewerEditAndOriginalUndo() = concurrentRollback(Batch.PATCH, false)
    @Test fun lspRollbackReceiptHandoffPreservesConcurrentNewerEditAndOriginalUndo() = concurrentRollback(Batch.LSP, false)
    @Test fun patchRollbackDoesNotRetargetNewerNoOpEntryCommittedBeforeRestore() = concurrentRollback(Batch.PATCH, true)
    @Test fun lspRollbackDoesNotRetargetNewerNoOpEntryCommittedBeforeRestore() = concurrentRollback(Batch.LSP, true)

    private fun concurrentRollback(batch: Batch, pauseBeforeRestore: Boolean) = workspace { f ->
        coroutineScope {
            f.apply(batch, linkedMapOf("a.txt" to "first-A"))
            val paused = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val hook: suspend () -> Unit = {
                paused.complete(Unit)
                withTimeout(5_000) { release.await() }
            }
            if (pauseBeforeRestore) f.beforeRollbackReceipt = hook else f.afterRollbackReceipt = hook
            f.conflictAtLastTarget = true
            val failingBatch = async {
                runCatching { f.apply(batch, linkedMapOf("a.txt" to "second-A", "c.txt" to "second-C")) }
            }
            try {
                withTimeout(5_000) { paused.await() }
                val observed = f.snapshot("a.txt")
                assertEquals(if (pauseBeforeRestore) "second-A" else "first-A", observed.content)
                // C commits while B's rollback call is suspended. The no-op case seals the
                // still-current B after-version; the other case starts at the restored version.
                f.apply(Batch.LSP, linkedMapOf("a.txt" to if (pauseBeforeRestore) "second-A" else "third-A"))
                val concurrentVersion = f.snapshot("a.txt").version
                if (pauseBeforeRestore) assertEquals(observed.version, concurrentVersion)
                else assertNotEquals(observed.version, concurrentVersion)
                assertEquals(2, f.history.depth())
                release.complete(Unit)
                val outcome = withTimeout(5_000) { failingBatch.await() }
                assertTrue("The failed batch must retain its original failure", outcome.isFailure)
                assertEquals(2, f.history.depth())
                f.assertText("c.txt", "user-C")
                if (pauseBeforeRestore) {
                    f.assertText("a.txt", "first-A")
                    assertNotEquals(concurrentVersion, f.snapshot("a.txt").version)
                    val undo = f.history.undo()
                    assertTrue(undo, undo.startsWith("Undo failed:"))
                    assertTrue(undo, undo.contains("Live document changed"))
                    assertEquals(2, f.history.depth())
                    f.assertText("a.txt", "first-A")
                    val backups = f.root.resolve(".droide/backups").listFiles().orEmpty()
                        .filter { it.isFile }.map { it.readText() }
                    assertTrue(backups.contains("second-A"))
                    assertTrue(backups.contains("original-A"))
                } else {
                    f.assertText("a.txt", "third-A")
                    assertEquals(concurrentVersion, f.snapshot("a.txt").version)
                    f.undoSuccessfully()
                    f.assertText("a.txt", "first-A")
                    assertEquals(1, f.history.depth())
                    f.undoSuccessfully()
                    f.assertText("a.txt", "original-A")
                    assertEquals(0, f.history.depth())
                }
                f.assertText("c.txt", "user-C")
                f.assertDiskUnchanged()
            } finally {
                release.complete(Unit)
                withTimeout(5_000) { failingBatch.join() }
            }
        }
    }

    private fun cancellationThenFatal(batch: Batch) = workspace { f ->
        f.apply(batch, linkedMapOf("a.txt" to "first-A", "b.txt" to "first-B"))
        val original = CancellationException("cancelled before third target")
        val fatal = AssertionError("fatal notification after b rollback")
        f.failAtLastTarget = original
        f.rollbackNotificationFailure = fatal
        val failure = runCatching {
            f.apply(batch, linkedMapOf("a.txt" to "second-A", "b.txt" to "second-B", "c.txt" to "second-C"))
        }.exceptionOrNull()
        assertSame(fatal, failure)
        assertTrue("Original cancellation must remain attached", fatal.suppressed.any { it === original })
        f.assertText("a.txt", "second-A"); f.assertText("b.txt", "first-B"); f.assertText("c.txt", "original-C")
        assertEquals(2, f.history.depth())
        f.undoSuccessfully(); f.assertText("a.txt", "first-A")
        f.undoSuccessfully(); f.assertText("a.txt", "original-A"); f.assertText("b.txt", "original-B")
        f.assertDiskUnchanged()
    }

    private fun interruptedRollback(batch: Batch, fatal: Boolean = false) = workspace { f ->
        f.apply(batch, linkedMapOf("a.txt" to "first-A", "b.txt" to "first-B"))
        f.conflictAtLastTarget = true
        val stopped = if (fatal) AssertionError("rollback notification fatal") else CancellationException("rollback notification interrupted")
        f.rollbackNotificationFailure = stopped
        val failure = runCatching {
            f.apply(batch, linkedMapOf("a.txt" to "second-A", "b.txt" to "second-B", "c.txt" to "second-C"))
        }.exceptionOrNull()
        assertSame("The notification interruption must propagate unchanged", stopped, failure)
        assertNull(f.rollbackNotificationFailure)
        f.assertText("a.txt", "second-A")
        f.assertText("b.txt", "first-B")
        f.assertText("c.txt", "user-C")
        assertEquals(2, f.history.depth())
        val restoredBVersion = f.snapshot("b.txt").version
        f.undoSuccessfully()
        f.assertText("a.txt", "first-A")
        assertEquals(restoredBVersion, f.snapshot("b.txt").version)
        assertEquals(1, f.history.depth())
        f.undoSuccessfully()
        f.assertText("a.txt", "original-A")
        f.assertText("b.txt", "original-B")
        f.assertText("c.txt", "user-C")
        assertEquals(0, f.history.depth())
        f.assertDiskUnchanged()
    }

    private fun successfulChain(batch: Batch) = workspace { f ->
        f.apply(batch, linkedMapOf("a.txt" to "first-A", "b.txt" to "first-B"))
        val firstVersion = f.snapshot("a.txt").version
        f.apply(batch, linkedMapOf("a.txt" to "second-A", "b.txt" to "second-B"))
        assertEquals(2, f.history.depth())
        f.undoSuccessfully()
        f.assertText("a.txt", "first-A")
        f.assertText("b.txt", "first-B")
        assertNotEquals(firstVersion, f.snapshot("a.txt").version)
        assertEquals(1, f.history.depth())
        f.undoSuccessfully()
        f.assertText("a.txt", "original-A")
        f.assertText("b.txt", "original-B")
        assertEquals(0, f.history.depth())
        f.assertDiskUnchanged()
    }

    private fun rolledBackChain(batch: Batch) = workspace { f ->
        f.apply(batch, linkedMapOf("a.txt" to "first-A", "b.txt" to "first-B"))
        val beforeFailure = f.snapshot("a.txt").version
        f.conflictAtLastTarget = true
        val failure = runCatching {
            f.apply(batch, linkedMapOf("a.txt" to "second-A", "b.txt" to "second-B", "c.txt" to "second-C"))
        }.exceptionOrNull()
        assertNotNull("Last target must reject the reviewed version", failure)
        val failureMessage = failure!!.message.orEmpty()
        assertTrue(failureMessage, if (batch == Batch.PATCH) failureMessage.contains("2 committed target(s) restored")
            else failureMessage.contains("rolled back", ignoreCase = true))
        f.assertText("a.txt", "first-A")
        f.assertText("b.txt", "first-B")
        f.assertText("c.txt", "user-C")
        assertNotEquals(beforeFailure, f.snapshot("a.txt").version)
        assertEquals("Successful rollback must not leave an extra Undo entry", 1, f.history.depth())
        f.undoSuccessfully()
        f.assertText("a.txt", "original-A")
        f.assertText("b.txt", "original-B")
        f.assertText("c.txt", "user-C")
        assertEquals(0, f.history.depth())
        f.assertDiskUnchanged()
    }

    private fun partialRollback(batch: Batch) = workspace { f ->
        f.apply(batch, linkedMapOf("a.txt" to "first-A", "b.txt" to "first-B"))
        f.conflictAtLastTarget = true
        f.blockOneRollback = true
        val failure = runCatching {
            f.apply(batch, linkedMapOf("a.txt" to "second-A", "b.txt" to "second-B", "c.txt" to "second-C"))
        }.exceptionOrNull()
        assertNotNull(failure)
        val message = failure!!.message.orEmpty()
        assertTrue(message, message.contains("partial rollback") || message.contains("not fully rolled back"))
        assertFalse("The one-shot rollback rejection must have been exercised", f.blockOneRollback)
        f.assertText("a.txt", "first-A")
        f.assertText("b.txt", "second-B")
        f.assertText("c.txt", "user-C")
        assertEquals(2, f.history.depth())
        // Only b survives in the recovery entry; a was already restored and retargeted.
        val restoredAVersion = f.snapshot("a.txt").version
        f.undoSuccessfully()
        assertEquals(restoredAVersion, f.snapshot("a.txt").version)
        f.assertText("b.txt", "first-B")
        assertEquals(1, f.history.depth())
        f.undoSuccessfully()
        f.assertText("a.txt", "original-A")
        f.assertText("b.txt", "original-B")
        f.assertText("c.txt", "user-C")
        assertEquals(0, f.history.depth())
        f.assertDiskUnchanged()
    }

    private fun userEditAfterRollback(batch: Batch) = workspace { f ->
        f.apply(batch, linkedMapOf("a.txt" to "first-A", "b.txt" to "first-B"))
        f.conflictAtLastTarget = true
        assertTrue(runCatching {
            f.apply(batch, linkedMapOf("a.txt" to "second-A", "b.txt" to "second-B", "c.txt" to "second-C"))
        }.isFailure)
        f.state.document("a.txt").capture("user-A")
        f.state.document("a.txt").capture("first-A")
        val result = f.history.undo()
        assertTrue(result, result.startsWith("Undo failed:"))
        assertTrue(result, result.contains("Live document changed"))
        f.assertText("a.txt", "first-A")
        f.assertText("b.txt", "first-B")
        f.assertText("c.txt", "user-C")
        assertEquals(1, f.history.depth())
        val backups = f.root.resolve(".droide/backups").listFiles().orEmpty().filter { it.isFile }.map { it.readText() }
        assertTrue(backups.contains("original-A"))
        assertTrue(backups.contains("original-B"))
        f.assertDiskUnchanged()
    }

    private class Fixture(val root: File) {
        val files = FileRepository(root)
        val state = EditorWorkspaceState()
        private val editor = EditorWorkspaceDocumentAuthority(state, Dispatchers.Unconfined)
        var conflictAtLastTarget = false
        var failAtLastTarget: Throwable? = null
        var blockOneRollback = false
        var rollbackNotificationFailure: Throwable? = null
        var beforeRollbackReceipt: (suspend () -> Unit)? = null
        var afterRollbackReceipt: (suspend () -> Unit)? = null
        private val documents = object : WorkspaceDocumentAuthority by editor {
            override suspend fun replaceText(path: String, expected: WorkspaceDocumentVersion, content: String,
                reason: String): WorkspaceDocumentMutationResult {
                if (path == "c.txt" && content == "second-C") {
                    failAtLastTarget?.let { failure -> failAtLastTarget = null; throw failure }
                }
                if (conflictAtLastTarget && path == "c.txt" && content == "second-C") {
                    conflictAtLastTarget = false
                    state.document(path).capture("user-C")
                }
                if (blockOneRollback && path == "b.txt" && reason.startsWith("Rollback")) {
                    blockOneRollback = false
                    return WorkspaceDocumentMutationResult.ReadOnly(requireNotNull(editor.snapshot(path)))
                }
                if (path == "a.txt" && reason.startsWith("Rollback")) {
                    val before = beforeRollbackReceipt
                    beforeRollbackReceipt = null
                    before?.invoke()
                    val result = editor.replaceText(path, expected, content, reason)
                    val after = afterRollbackReceipt
                    afterRollbackReceipt = null
                    if (result is WorkspaceDocumentMutationResult.Applied) after?.invoke()
                    return result
                }
                return editor.replaceText(path, expected, content, reason)
            }
        }
        private val lsp = object : LspManager {
            override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
            override suspend fun didChange(path: String, text: String) {
                if (path == "b.txt" && text == "first-B") {
                    rollbackNotificationFailure?.let { failure ->
                        rollbackNotificationFailure = null
                        throw failure
                    }
                }
            }
            override suspend fun diagnose(relPath: String) = ""
            override suspend fun lsp(op: String, path: String, line: Int, col: Int) = ""
        }
        val history = EditHistory(files, documents, lsp)

        suspend fun load() {
            for ((path, text) in originals) {
                root.resolve(path).writeText(text)
                state.document(path).ensureLoaded(files)
            }
        }
        suspend fun snapshot(path: String) = requireNotNull(documents.snapshot(path))
        suspend fun assertText(path: String, text: String) = assertEquals(path, text, snapshot(path).content)
        fun assertDiskUnchanged() = originals.forEach { (path, text) -> assertEquals(path, text, root.resolve(path).readText()) }
        suspend fun undoSuccessfully() {
            val result = history.undo()
            assertTrue(result, result.startsWith("Undone:"))
        }
        suspend fun apply(batch: Batch, after: Map<String, String>) {
            val snapshots = after.keys.associateWith { snapshot(it) }
            val before = snapshots.mapValues { it.value.content }
            val result = when (batch) {
                Batch.PATCH -> {
                    val ops = after.map { (path, text) -> PatchTool.Op.Update(path, before.getValue(path), text, moveTo = null) }
                    val reviewed = PatchTool.reviewWorkspaceSnapshot(ops, files, documents)
                    PatchTool.applyWorkspaceReviewed(ops, files, history, documents, lsp, reviewed)
                }
                Batch.LSP -> AgentLspEditActions(files, lsp, history, documents).apply(AgentLspEditActions.Prepared(
                    "Batch format", LspWorkspaceEdit(emptyList()), before, snapshots.mapValues { it.value.version },
                    after, "reviewed live diff", "*.txt"))
            }
            assertTrue(result, result.startsWith("OK:") || result.startsWith("LSP_EDIT_APPLIED"))
        }
        companion object {
            val originals = linkedMapOf("a.txt" to "original-A", "b.txt" to "original-B", "c.txt" to "original-C")
        }
    }

    private fun workspace(block: suspend (Fixture) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("droide-live-batch-chain-").toFile()
        // No AgentTools context, terminal, search-page store, or web client is constructed.
        try { val fixture = Fixture(root); fixture.load(); block(fixture) }
        finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
