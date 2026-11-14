package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class AgentLspDiskCommitTest {
    private val lsp = object : LspManager {
        override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
        override suspend fun diagnose(relPath: String) = ""
        override suspend fun lsp(op: String, path: String, line: Int, col: Int) = ""
    }

    @Test fun staleOwnerCannotProduceAnAgentReviewProposal() = workspace { root ->
        root.resolve("a.svelte").writeText("before")
        val files = FileRepository(root)
        val guarded = object : LspManager by lsp {
            override suspend fun formatDocument(path: String, options: LspFormattingOptions) = formatDocument(path)
            override suspend fun formatDocument(path: String) = LspWorkspaceEdit(
                listOf(LspTextEdit(path, TextRangeEdit(1, 1, 1, 1, "new-"))),
                mapOf(path to "before"), applicationGuard = { false },
            )
        }
        assertTrue(runCatching { AgentLspEditActions(files, guarded, EditHistory(files), null).prepareFormat("a.svelte") }.isFailure)
        assertEquals("before", root.resolve("a.svelte").readText())
    }

    @Test fun preparingAgentReviewRechecksOwnerAfterCollectingTargets() = workspace { root ->
        root.resolve("a.svelte").writeText("before")
        val files = FileRepository(root)
        var checks = 0
        val guarded = object : LspManager by lsp {
            override suspend fun formatDocument(path: String, options: LspFormattingOptions) = formatDocument(path)
            override suspend fun formatDocument(path: String) = LspWorkspaceEdit(
                listOf(LspTextEdit(path, TextRangeEdit(1, 1, 1, 1, "new-"))),
                mapOf(path to "before"), applicationGuard = { ++checks == 1 },
            )
        }
        assertTrue(runCatching { AgentLspEditActions(files, guarded, EditHistory(files), null).prepareFormat("a.svelte") }.isFailure)
        assertEquals(2, checks)
        assertEquals("before", root.resolve("a.svelte").readText())
    }

    @Test fun staleOwnerRejectsReviewedEditBeforeAnyWriteOrHistoryEntry() = workspace { root ->
        root.resolve("a.svelte").writeText("before")
        val files = FileRepository(root)
        val history = EditHistory(files)
        val edit = prepared(mapOf("a.svelte" to "before"), mapOf("a.svelte" to "after"))
            .copy(workspaceEdit = LspWorkspaceEdit(emptyList(), applicationGuard = { false }))
        assertTrue(runCatching { AgentLspEditActions(files, lsp, history, null).apply(edit) }.isFailure)
        assertEquals("before", root.resolve("a.svelte").readText())
        assertEquals(0, history.depth())
        assertFalse(root.resolve(".droide/backups").exists())
    }

    @Test fun acceptanceRechecksOwnerAfterSuspendingPreparation() = workspace { root ->
        root.resolve("a.svelte").writeText("before")
        val files = FileRepository(root)
        val history = EditHistory(files)
        var checks = 0
        val edit = prepared(mapOf("a.svelte" to "before"), mapOf("a.svelte" to "after"))
            .copy(workspaceEdit = LspWorkspaceEdit(emptyList(), applicationGuard = { ++checks == 1 }))
        assertTrue(runCatching { AgentLspEditActions(files, lsp, history, null).apply(edit) }.isFailure)
        assertEquals(2, checks)
        assertEquals("before", root.resolve("a.svelte").readText())
        assertEquals(0, history.depth())
        assertTrue(root.resolve(".droide/backups").listFiles().orEmpty().isEmpty())
    }

    @Test fun acceptedMultiFileBatchDoesNotRecheckCaptureInvalidatedByItsOwnWrites() = workspace { root ->
        val before = linkedMapOf("a.svelte" to "A", "b.ts" to "B")
        val after = linkedMapOf("a.svelte" to "new-A", "b.ts" to "new-B")
        before.forEach { (path, text) -> root.resolve(path).writeText(text) }
        var current = true
        var checks = 0
        val files = FileRepository(root, onMutation = { if (it is FileRepository.Mutation.Write) current = false })
        val edit = prepared(before, after).copy(workspaceEdit = LspWorkspaceEdit(emptyList(), applicationGuard = { checks++; current }))
        val result = actions(files).apply(edit)
        assertTrue(result, result.startsWith("LSP_EDIT_APPLIED"))
        assertEquals(2, checks)
        after.forEach { (path, text) -> assertEquals(text, root.resolve(path).readText()) }
    }

    @Test fun throwingOwnerGuardCannotGrantApplication() = workspace { root ->
        root.resolve("a.svelte").writeText("before")
        val files = FileRepository(root)
        val edit = prepared(mapOf("a.svelte" to "before"), mapOf("a.svelte" to "after"))
            .copy(workspaceEdit = LspWorkspaceEdit(emptyList(), applicationGuard = { error("observer unavailable") }))
        assertTrue(runCatching { actions(files).apply(edit) }.isFailure)
        assertEquals("before", root.resolve("a.svelte").readText())
    }

    @Test fun reviewedWriteRechecksDiskAfterMutationAdmission() = workspace { root ->
        root.resolve("a.kt").writeText("before")
        val files = FileRepository(root, beforeMutation = {
            if (it is FileRepository.Mutation.Write) root.resolve("a.kt").writeText("terminal")
        })
        assertTrue(runCatching { actions(files).apply(prepared(mapOf("a.kt" to "before"), mapOf("a.kt" to "after"))) }.isFailure)
        assertEquals("terminal", root.resolve("a.kt").readText())
    }

    @Test fun canonicalSyncPendingCommitsBatchAndRetainsUndo() = workspace { root ->
        root.resolve("a.kt").writeText("before")
        var failSync = true
        val files = FileRepository(root, onMutation = { if (failSync && it is FileRepository.Mutation.Write) error("mirror offline") })
        val history = EditHistory(files)
        val result = AgentLspEditActions(files, lsp, history, null)
            .apply(prepared(mapOf("a.kt" to "before"), mapOf("a.kt" to "after")))
        assertTrue(result, result.startsWith("LSP_EDIT_APPLIED"))
        assertTrue(result, result.contains("sync_pending=true"))
        assertEquals("after", root.resolve("a.kt").readText())
        failSync = false
        history.undo()
        assertEquals("before", root.resolve("a.kt").readText())
    }

    @Test fun laterFailureCannotRollbackConcurrentTerminalChanges() = workspace { root ->
        root.resolve("a.kt").writeText("A")
        root.resolve("b.kt").writeText("B")
        val files = FileRepository(root, beforeMutation = {
            if (it is FileRepository.Mutation.Write && it.path == "b.kt") {
                root.resolve("a.kt").writeText("terminal-A")
                root.resolve("b.kt").writeText("terminal-B")
            }
        })
        val history = EditHistory(files)
        val result = runCatching { AgentLspEditActions(files, lsp, history, null).apply(prepared(linkedMapOf("a.kt" to "A", "b.kt" to "B"),
            linkedMapOf("a.kt" to "new-A", "b.kt" to "new-B"))) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("not fully rolled back"))
        assertEquals("terminal-A", root.resolve("a.kt").readText())
        assertEquals("terminal-B", root.resolve("b.kt").readText())
        assertEquals(1, history.depth()) // Guarded recovery is retained, never a blind undo.
        history.undo()
        assertEquals(1, history.depth())
        assertEquals("terminal-A", root.resolve("a.kt").readText())
    }

    @Test fun laterFailureRollsBackOnlyUnchangedOwnedSnapshots() = workspace { root ->
        root.resolve("a.kt").writeText("A")
        root.resolve("b.kt").writeText("B")
        val files = FileRepository(root, beforeMutation = {
            if (it is FileRepository.Mutation.Write && it.path == "b.kt") error("second edit blocked")
        })
        val result = runCatching { actions(files).apply(prepared(linkedMapOf("a.kt" to "A", "b.kt" to "B"),
            linkedMapOf("a.kt" to "new-A", "b.kt" to "new-B"))) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("recovery backups retained"))
        assertEquals("A", root.resolve("a.kt").readText())
        assertEquals("B", root.resolve("b.kt").readText())
    }

    @Test fun cancellationAfterCanonicalPublicationPreservesBytesAndRecoveryBackup() = workspace { root ->
        root.resolve("a.kt").writeText("before")
        val stopped = CancellationException("stop fixture")
        val files = FileRepository(root, onMutation = { if (it is FileRepository.Mutation.Write) throw stopped })
        val history = EditHistory(files)
        val result = runCatching { AgentLspEditActions(files, lsp, history, null)
            .apply(prepared(mapOf("a.kt" to "before"), mapOf("a.kt" to "after"))) }
        assertOriginatingFailure(stopped, result.exceptionOrNull())
        assertEquals("after", root.resolve("a.kt").readText())
        assertEquals(0, history.depth()) // The ambiguous call has no invented after-token.
        assertTrue(root.resolve(".droide/backups").listFiles().orEmpty().any { it.isFile && it.readText() == "before" })
    }

    @Test fun fatalMutationErrorKeepsItsIdentityAndRecoverySnapshot() = workspace { root ->
        root.resolve("a.kt").writeText("before")
        val fatal = AssertionError("fatal fixture")
        val files = FileRepository(root, beforeMutation = { throw fatal })
        val result = runCatching { actions(files).apply(prepared(mapOf("a.kt" to "before"), mapOf("a.kt" to "after"))) }
        assertOriginatingFailure(fatal, result.exceptionOrNull())
        assertEquals("before", root.resolve("a.kt").readText())
        assertTrue(root.resolve(".droide/backups").listFiles().orEmpty().any { it.isFile && it.readText() == "before" })
    }

    private fun actions(files: FileRepository) = AgentLspEditActions(files, lsp, EditHistory(files), null)
    private fun prepared(before: Map<String, String>, after: Map<String, String>) = AgentLspEditActions.Prepared(
        "Format", LspWorkspaceEdit(emptyList()), before, emptyMap(), after, "reviewed diff", "*.kt")
    private fun workspace(block: suspend (File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("droide-lsp-commit-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
