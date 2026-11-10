package com.baystudio.droide.core

import com.baystudio.droide.ui.EditorWorkspaceState
import com.baystudio.droide.ui.EditorWorkspaceDocumentAuthority
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class LiveAgentUndoChainTest {
    private class TestLsp : LspManager {
        override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
        var notify: suspend (String, String) -> Unit = { _, _ -> }
        override suspend fun didChange(relPath: String, text: String) = notify(relPath, text)
        override suspend fun diagnose(relPath: String) = ""
        override suspend fun lsp(op: String, path: String, line: Int, col: Int) = ""
    }

    private class Fixture(val root: File) {
        val files = FileRepository(root)
        val state = EditorWorkspaceState()
        val real = EditorWorkspaceDocumentAuthority(state, Dispatchers.Unconfined)
        var beforeReplace: suspend (String, WorkspaceDocumentVersion, String, String) -> WorkspaceDocumentMutationResult? = { _, _, _, _ -> null }
        val authority = object : WorkspaceDocumentAuthority by real {
            override suspend fun replaceText(path: String, expected: WorkspaceDocumentVersion, content: String, reason: String): WorkspaceDocumentMutationResult =
                beforeReplace(path, expected, content, reason) ?: real.replaceText(path, expected, content, reason)
        }
        val lsp = TestLsp()
        val history = EditHistory(files, authority, lsp)
        private val base = agentWorkflowContext(root, documents = authority)
        val ctx = AgentTools.Ctx(files, base.terminal, base.git, base.approvals, base.questions, history,
            base.perms, base.mode, base.llm, base.llmConfig, root, base.todos, base.sessionId, lsp,
            documentAuthority = authority, webResearch = base.webResearch)

        suspend fun load(path: String = "a.txt", content: String = "original") {
            root.resolve(path).writeText(content)
            state.document(path).ensureLoaded(files)
        }
        suspend fun edit(text: String, path: String = "a.txt") {
            val snapshot = requireNotNull(authority.snapshot(path))
            val outcome = AgentTools.execute("write_file", buildJsonObject {
                put("path", path); put("content", text)
                put("expected_document_id", snapshot.documentId)
                put("expected_revision", snapshot.revision); put("expected_change_version", snapshot.changeVersion)
            }, ctx)
            assertTrue(outcome, outcome.startsWith("OK:"))
        }
        suspend fun batch(contents: Map<String, String>) {
            val before = contents.keys.associateWith { requireNotNull(authority.snapshot(it)).content }
            val pending = history.prepareBatch("live batch", before)
            val applied = contents.mapValues { (path, text) ->
                val expected = requireNotNull(authority.snapshot(path)).version
                val result = authority.replaceText(path, expected, text, "Agent batch") as WorkspaceDocumentMutationResult.Applied
                EditHistory.LiveTransition.from(result)
            }
            history.commitBatch(pending, applied)
        }
        suspend fun undo(expected: String, path: String = "a.txt") {
            val result = history.undo()
            assertTrue(result, result.startsWith("Undone:"))
            assertEquals(expected, state.document(path).content)
        }
        fun close() {
            ctx.searchResultPages.close(); ctx.lspResultPages.close()
            base.searchResultPages.close(); base.lspResultPages.close()
            ctx.webResearch.close(); ctx.terminal.destroy(); state.clear()
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture(Files.createTempDirectory("droide-live-undo-chain-").toFile())
        try { fixture.load(); fixture.block() } finally { fixture.close() }
    }

    @Test fun twoAgentEditsCanBothBeUndoneThroughProductionEditorAuthority() = fixture {
        edit("first"); edit("second")
        assertEquals(2, history.depth())
        undo("first"); undo("original")
        assertEquals("original", root.resolve("a.txt").readText())
        assertEquals(0, history.depth())
    }

    @Test fun threeEditsIncludingNoOpKeepVersionContinuityAndDiskUnchanged() = fixture {
        edit("first"); edit("first"); edit("second")
        undo("first"); undo("first"); undo("original")
        assertEquals("original", root.resolve("a.txt").readText())
        assertEquals(0, history.depth())
    }

    @Test fun unrelatedHistoryEntriesDoNotInterruptSameDocumentChain() = fixture {
        load("b.txt", "other")
        edit("first"); edit("other edit", "b.txt"); edit("second")
        undo("first"); undo("other", "b.txt"); undo("original")
        assertEquals(0, history.depth())
    }

    @Test fun userEditAfterFirstUndoStillBlocksOlderUndoAndKeepsBackup() = fixture {
        edit("first"); edit("second"); undo("first")
        state.document("a.txt").capture("user")
        val result = history.undo()
        assertTrue(result, result.startsWith("Undo failed:"))
        assertEquals("user", state.document("a.txt").content)
        assertEquals(1, history.depth())
        assertTrue(root.resolve(".droide/backups").listFiles().orEmpty().any { it.isFile && it.readText() == "original" })
    }

    @Test fun equalTextUserInterventionBeforeSecondAgentEditDoesNotAuthorizeOlderUndo() = fixture {
        edit("first")
        state.document("a.txt").capture("temporary user edit")
        state.document("a.txt").capture("first")
        edit("second"); undo("first")
        val result = history.undo()
        assertTrue(result, result.startsWith("Undo failed:"))
        assertEquals("first", state.document("a.txt").content)
        assertEquals(1, history.depth())
    }

    @Test fun reopenedDocumentWithDifferentVersionCannotInheritRebasedUndo() = fixture {
        edit("first"); edit("second"); undo("first")
        state.forgetAtOrUnder("a.txt")
        state.document("a.txt").ensureLoaded(files)
        val result = history.undo()
        assertTrue(result, result.startsWith("Undo failed:"))
        assertEquals("original", state.document("a.txt").content)
        assertEquals(1, history.depth())
    }

    @Test fun cancellationAfterAppliedLiveRestoreConsumesOnlyItsOwnedTarget() = fixture {
        edit("first"); edit("second")
        val cancelled = CancellationException("LSP cancelled after local Undo")
        lsp.notify = { _, _ -> throw cancelled }
        assertOrigin(cancelled, runCatching { history.undo() }.exceptionOrNull())
        assertEquals("first", state.document("a.txt").content)
        assertEquals(1, history.depth())
        lsp.notify = { _, _ -> }
        undo("original")
    }

    @Test fun fatalNotificationAfterAppliedRestoreSettlesLocalHistoryAndRethrowsSameFailure() = fixture {
        edit("first"); edit("second")
        val fatal = AssertionError("LSP fatal after local Undo")
        lsp.notify = { _, _ -> throw fatal }
        assertOrigin(fatal, runCatching { history.undo() }.exceptionOrNull())
        assertEquals("first", state.document("a.txt").content)
        assertEquals(1, history.depth())
        lsp.notify = { _, _ -> }
        undo("original")
    }

    @Test fun ordinaryLspFailureReportsPendingButDoesNotStrandOlderUndo() = fixture {
        edit("first"); edit("second")
        lsp.notify = { _, _ -> throw IOException("LSP offline") }
        val result = history.undo()
        assertTrue(result, result.startsWith("Undone:"))
        assertTrue(result, result.contains("synchronization pending"))
        lsp.notify = { _, _ -> }
        undo("original")
    }

    @Test fun authorityFailureBeforeAppliedDoesNotConsumeOrRebaseHistory() = fixture {
        edit("first"); edit("second")
        beforeReplace = { _, _, _, _ -> throw IOException("editor refused before mutation") }
        assertTrue(history.undo().startsWith("Undo failed:"))
        assertEquals("second", state.document("a.txt").content)
        assertEquals(2, history.depth())
        beforeReplace = { _, _, _, _ -> null }
        undo("first"); undo("original")
    }

    @Test fun partialLiveBatchRestoreRetainsUnfinishedTargetAndBothOlderChains() = fixture {
        load("b.txt", "other")
        batch(linkedMapOf("a.txt" to "first", "b.txt" to "other first"))
        batch(linkedMapOf("a.txt" to "second", "b.txt" to "other second"))
        var refuse = true
        beforeReplace = { path, _, _, reason ->
            if (refuse && path == "a.txt" && reason == "Undo Agent edit") {
                refuse = false
                WorkspaceDocumentMutationResult.Conflict(requireNotNull(real.snapshot(path)))
            } else null
        }
        val result = history.undo()
        assertTrue(result, result.contains("1 file(s) already restored"))
        assertEquals("other first", state.document("b.txt").content)
        assertEquals("second", state.document("a.txt").content)
        assertEquals(2, history.depth())
        undo("first")
        undo("original")
        assertEquals("other", state.document("b.txt").content)
        assertEquals(0, history.depth())
    }

    @Test fun samePathCommitDuringUndoNotificationIsNotRetargetedAsAnOlderEntry() = fixture {
        edit("first"); edit("second")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var hold = true
        lsp.notify = { _, text ->
            if (hold && text == "first") { hold = false; entered.complete(Unit); withTimeout(5_000) { release.await() } }
        }
        coroutineScope {
            val undoing = async { history.undo() }
            try {
                withTimeout(5_000) { entered.await() }
                edit("third")
            } finally { release.complete(Unit) }
            assertTrue(undoing.await().startsWith("Undone:"))
        }
        assertEquals("third", state.document("a.txt").content)
        assertEquals(2, history.depth())
        undo("first"); undo("original")
        assertEquals(0, history.depth())
    }

    @Test fun userEditDuringUndoNotificationNeverBecomesTheExpectedUndoVersion() = fixture {
        edit("first"); edit("second")
        lsp.notify = { _, _ -> state.document("a.txt").capture("late user") }
        assertTrue(history.undo().startsWith("Undone:"))
        assertEquals("late user", state.document("a.txt").content)
        lsp.notify = { _, _ -> }
        assertTrue(history.undo().startsWith("Undo failed:"))
        assertEquals("late user", state.document("a.txt").content)
        assertEquals(1, history.depth())
    }

    @Test fun nearerDiskFrontierPreventsRetargetingAnOlderCoincidentLiveVersion() = fixture {
        edit("original")
        val disk = history.prepareBatch("disk frontier", mapOf("a.txt" to "original"))
        files.writeText("a.txt", "original")
        history.commitBatch(disk, diskAfter = mapOf("a.txt" to "original"))
        edit("first")
        undo("original")
        // The nearer disk snapshot is not a live transition. Undo must not search past it.
        undo("original")
        val result = history.undo()
        assertTrue(result, result.startsWith("Undo failed:"))
        assertEquals(1, history.depth())
        assertEquals("original", state.document("a.txt").content)
    }

    @Test fun mixedDiskLivePartialUndoPreservesLateDiskEditAndLivePredecessor() = fixture {
        root.resolve("disk.txt").writeText("original-D")
        suspend fun mixed(live: String, disk: String) {
            val before = requireNotNull(authority.snapshot("a.txt"))
            val pending = history.prepareBatch("mixed batch", linkedMapOf(
                "disk.txt" to root.resolve("disk.txt").readText(), "a.txt" to before.content))
            files.writeText("disk.txt", disk)
            val applied = authority.replaceText("a.txt", before.version, live, "Agent mixed batch") as WorkspaceDocumentMutationResult.Applied
            history.commitBatch(pending, mapOf("a.txt" to EditHistory.LiveTransition.from(applied)),
                mapOf("disk.txt" to disk))
        }
        mixed("first", "first-D"); mixed("second", "second-D")
        lsp.notify = { path, _ -> if (path == "a.txt") root.resolve("disk.txt").writeText("late disk user") }
        val failed = history.undo()
        assertTrue(failed, failed.contains("1 file(s) already restored"))
        assertEquals("first", state.document("a.txt").content)
        assertEquals("late disk user", root.resolve("disk.txt").readText())
        assertEquals(2, history.depth())
        val restoredVersion = requireNotNull(authority.snapshot("a.txt")).version
        lsp.notify = { _, _ -> }
        root.resolve("disk.txt").writeText("second-D")
        assertTrue(history.undo().startsWith("Undone:"))
        assertEquals(restoredVersion, requireNotNull(authority.snapshot("a.txt")).version)
        assertEquals("first-D", root.resolve("disk.txt").readText())
        undo("original")
        assertEquals("original-D", root.resolve("disk.txt").readText())
        assertEquals(0, history.depth())
    }

    @Test fun cancellationAfterFirstLiveTargetRetainsOnlyUnfinishedBatchTargets() = fixture {
        load("b.txt", "other")
        batch(linkedMapOf("a.txt" to "first", "b.txt" to "other first"))
        batch(linkedMapOf("a.txt" to "second", "b.txt" to "other second"))
        val stopped = CancellationException("LSP cancelled after first target")
        lsp.notify = { path, _ -> if (path == "b.txt") throw stopped }
        assertOrigin(stopped, runCatching { history.undo() }.exceptionOrNull())
        assertEquals("other first", state.document("b.txt").content)
        assertEquals("second", state.document("a.txt").content)
        assertEquals(2, history.depth())
        val restoredVersion = requireNotNull(authority.snapshot("b.txt")).version
        lsp.notify = { _, _ -> }
        undo("first")
        assertEquals(restoredVersion, requireNotNull(authority.snapshot("b.txt")).version)
        undo("original")
        assertEquals("other", state.document("b.txt").content)
        assertEquals(0, history.depth())
    }

    private fun assertOrigin(expected: Throwable, actual: Throwable?) {
        var current = actual
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        while (current != null && seen.add(current)) {
            if (current === expected) return
            current = current.cause
        }
        fail("Original failure must survive: expected=$expected actual=$actual")
    }
}
