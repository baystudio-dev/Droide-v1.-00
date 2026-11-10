package com.baystudio.droide.core

import com.baystudio.droide.ui.EditorNativeMutationBinding
import com.baystudio.droide.ui.EditorWorkspaceDocumentAuthority
import com.baystudio.droide.ui.EditorWorkspaceState
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/** Production editor ownership/CAS and Agent consumers; no native renderer or Android execution. */
class EditorGenerationAuthorityTest {
    private class Fixture(val root: File) {
        val files = FileRepository(root)
        val state = EditorWorkspaceState()
        val real = EditorWorkspaceDocumentAuthority(state, Dispatchers.Unconfined)
        var beforeReplace: suspend (String, String) -> Unit = { _, _ -> }
        val authority = object : WorkspaceDocumentAuthority by real {
            override suspend fun replaceText(path: String, expected: WorkspaceDocumentVersion, content: String, reason: String): WorkspaceDocumentMutationResult {
                beforeReplace(path, reason)
                return real.replaceText(path, expected, content, reason)
            }
        }
        val ctx = agentWorkflowContext(root, documents = authority)
        suspend fun load(path: String = "a.txt", text: String = "original") {
            root.resolve(path).writeText(text)
            state.document(path).ensureLoaded(files)
        }
        suspend fun snapshot(path: String = "a.txt") = requireNotNull(authority.snapshot(path))
        suspend fun edit(text: String) {
            val before = snapshot()
            val result = AgentTools.execute("write_file", buildJsonObject {
                put("path", "a.txt"); put("content", text)
                put("expected_revision", before.revision); put("expected_change_version", before.changeVersion)
                put("expected_document_id", before.documentId)
            }, ctx)
            assertTrue(result, result.startsWith("OK:"))
        }

        suspend fun replaceOwner(old: WorkspaceDocumentSnapshot, text: String): WorkspaceDocumentSnapshot {
            state.forgetAtOrUnder(old.path)
            val replacement = state.document(old.path)
            replacement.ensureLoaded(files)
            val initial = snapshot(old.path)
            val steps = old.revision - initial.revision
            assertEquals(steps, old.changeVersion - initial.changeVersion)
            assertTrue("Fixture counter progression must be nonnegative", steps >= 0)
            repeat(steps) { index ->
                replacement.applyWorkspaceText(if (index == steps - 1) text else "replacement intermediate $index")
            }
            val next = snapshot(old.path)
            assertEquals(text, next.content)
            assertEquals(old.revision, next.revision)
            assertEquals(old.changeVersion, next.changeVersion)
            assertNotEquals(old.documentId, next.documentId)
            assertNotEquals(old.version, next.version)
            return next
        }
        fun assertBackup(text: String) {
            assertTrue("Retain original recovery payload", root.resolve(".droide/backups").listFiles().orEmpty()
                .any { it.isFile && it.readText() == text })
        }
        fun close() {
            ctx.searchResultPages.close(); ctx.lspResultPages.close(); ctx.webResearch.close(); ctx.terminal.destroy()
            state.clear(); PathSecurity.deleteTreeNoFollow(root)
        }
    }
    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture(Files.createTempDirectory("droide-generation-").toFile())
        try { withTimeout(10_000) { fixture.load(); fixture.block() } } finally { fixture.close() }
    }

    @Test fun sameCounterReplacementAfterFirstUndoCannotInheritOlderHistory() = fixture {
        edit("first"); edit("second")
        assertTrue(ctx.history.undo().startsWith("Undone:"))
        replaceOwner(snapshot(), "replacement owner")
        val result = ctx.history.undo()
        assertTrue(result, result.startsWith("Undo failed:"))
        assertEquals("replacement owner", snapshot().content)
        assertEquals(1, ctx.history.depth()); assertBackup("original")
        assertEquals("original", root.resolve("a.txt").readText())
    }

    @Test fun equalTextAndCounterReplacementAfterFirstUndoStillCannotInheritHistory() = fixture {
        edit("first"); edit("second")
        assertTrue(ctx.history.undo().startsWith("Undone:"))
        val old = snapshot()
        val next = replaceOwner(old, old.content)
        assertEquals(old.content, next.content)
        val result = ctx.history.undo()
        assertTrue(result, result.startsWith("Undo failed:"))
        assertEquals(next, snapshot()); assertEquals(1, ctx.history.depth()); assertBackup("original")
    }

    @Test fun replacementBetweenUndoPreflightAndAuthorityCasPreservesHistoryAndBackup() = fixture {
        edit("first"); edit("second")
        var replacement: WorkspaceDocumentSnapshot? = null
        beforeReplace = { path, reason ->
            if (path == "a.txt" && reason == "Undo Agent edit") {
                beforeReplace = { _, _ -> }
                val old = snapshot()
                replacement = replaceOwner(old, old.content)
            }
        }
        val result = ctx.history.undo()
        assertTrue(result, result.startsWith("Undo failed:"))
        assertEquals(requireNotNull(replacement), snapshot())
        assertEquals("second", snapshot().content)
        assertEquals(2, ctx.history.depth()); assertBackup("first"); assertBackup("original")
        assertEquals("original", root.resolve("a.txt").readText())
    }

    @Test fun sameDocumentRetainsIdentityAcrossUnloadReloadAndPersistedRemap() = fixture {
        val document = state.document("a.txt")
        val initial = snapshot()
        document.unloadClean()
        val unloaded = snapshot()
        assertFalse(unloaded.loaded)
        assertEquals(initial.documentId, unloaded.documentId)
        assertEquals(initial.revision + 1, unloaded.revision)
        assertEquals(initial.changeVersion, unloaded.changeVersion)
        document.ensureLoaded(files)
        val reloaded = snapshot()
        assertSame(document, state.document("a.txt"))
        assertEquals(initial.documentId, reloaded.documentId)
        assertEquals(unloaded.revision + 1, reloaded.revision)
        assertEquals(initial.changeVersion, reloaded.changeVersion)
        assertEquals(initial.content, reloaded.content)
        assertTrue(root.resolve("a.txt").renameTo(root.resolve("renamed.txt")))
        assertEquals(listOf("a.txt" to "renamed.txt"), state.remapPersistedPath("a.txt", "renamed.txt"))
        val remapped = snapshot("renamed.txt")
        assertSame(document, state.document("renamed.txt"))
        assertNull(authority.snapshot("a.txt"))
        assertEquals(initial.documentId, remapped.documentId)
        assertEquals(reloaded.revision + 1, remapped.revision)
        assertEquals(reloaded.changeVersion, remapped.changeVersion)
        assertEquals(initial.content, remapped.content)
        state.forgetAtOrUnder("renamed.txt")
        state.document("renamed.txt").ensureLoaded(files)
        assertNotEquals(initial.documentId, snapshot("renamed.txt").documentId)
        assertNotSame(document, state.document("renamed.txt"))
    }

    @Test fun actualAuthorityRejectsStaleGenerationWithEqualTextAndCounters() = fixture {
        val old = snapshot()
        val replacement = replaceOwner(old, old.content)
        assertTrue(real.replaceText("a.txt", replacement.version.copy(documentId = null), "unauthorized legacy", "Agent")
            is WorkspaceDocumentMutationResult.Conflict)
        assertEquals(replacement, snapshot())
        val result = real.replaceText("a.txt", old.version, "unauthorized", "Agent")
        assertTrue(result is WorkspaceDocumentMutationResult.Conflict)
        assertEquals(replacement, snapshot())
        assertEquals("original", root.resolve("a.txt").readText())
        assertTrue(real.replaceText("a.txt", replacement.version, "authorized", "Agent") is WorkspaceDocumentMutationResult.Applied)
    }

    @Test fun replacementDuringNativePrepareCannotPublishIntoEitherOwner() = fixture {
        val old = snapshot()
        val document = state.document("a.txt")
        var nativePublishes = 0
        document.nativeMutationBinding = EditorNativeMutationBinding(Any(), { true }, {
            runBlocking { replaceOwner(old, old.content) }
        }, { _, _, publish -> nativePublishes++; publish() })
        val failure = runCatching { real.replaceText("a.txt", old.version, "unauthorized", "Agent") }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(0, nativePublishes)
        assertEquals("original", document.content)
        assertEquals("original", snapshot().content)
        assertNotEquals(old.documentId, snapshot().documentId)
    }

    @Test fun generationIsStableAcrossMutationsAndBothSnapshotProvidersButNotRecreation() = fixture {
        val old = snapshot()
        assertFalse(requireNotNull(old.documentId).isBlank())
        edit("updated")
        assertEquals(old.documentId, snapshot().documentId)
        assertEquals(old.documentId, authority.snapshots().single().documentId)
        val limits = WorkspaceSearchLimits()
        val budget = WorkspaceSearchBudget(limits, EmptyCoroutineContext, WorkspaceRequestDeadline(limits.durationNanos) { 0L })
        val searched = authority.captureSearchSnapshots(WorkspaceSearchCaptureRequest(budget)).snapshots.single()
        assertEquals(snapshot().version, searched.version)
        replaceOwner(snapshot(), "updated")
        assertNotEquals(old.documentId, snapshot().documentId)
    }

    @Test fun readFileTokensAuthorizeAllWriteAndEditAliases() = fixture {
        for (tool in listOf("write_file", "edit_file", "write", "edit")) {
            val read = AgentTools.execute("read_file", buildJsonObject { put("path", "a.txt") }, ctx)
            fun token(name: String) = requireNotNull(Regex("(?:^|[ \\[])${name}=([^ \\]\\r\\n]+)").find(read)) { read }.groupValues[1]
            val id = token("document_id")
            assertEquals(snapshot().documentId, id)
            val previous = snapshot().content
            val next = "content from $tool"
            val result = AgentTools.execute(tool, buildJsonObject {
                put("path", "a.txt")
                if (tool.startsWith("write")) put("content", next)
                else { put("search", previous); put("replace", next) }
                put("expected_revision", token("revision").toInt())
                put("expected_change_version", token("change_version").toInt())
                put("expected_document_id", id)
            }, ctx)
            assertTrue("$tool: $result", result.startsWith("OK:"))
            assertEquals(next, snapshot().content)
        }
        assertEquals("original", root.resolve("a.txt").readText())
        assertEquals(4, ctx.history.depth())
    }

    @Test fun allAliasesRejectMissingOrStaleGenerationWithoutHistoryOrDiskMutation() = fixture {
        val stale = snapshot()
        val current = replaceOwner(stale, stale.content)
        for (tool in listOf("write_file", "edit_file", "write", "edit")) {
            for (id in listOf<String?>(null, "", stale.documentId)) {
                val result = AgentTools.execute(tool, buildJsonObject {
                    put("path", "a.txt")
                    if (tool.startsWith("write")) put("content", "unauthorized")
                    else { put("search", "original"); put("replace", "unauthorized") }
                    put("expected_revision", current.revision); put("expected_change_version", current.changeVersion)
                    if (id != null) put("expected_document_id", id)
                }, ctx)
                assertTrue("$tool: $result", result.startsWith("ERROR:"))
                if (id != "") assertTrue(result, result.contains(if (id == null) "LIVE_DOCUMENT_VERSION_REQUIRED" else "DOCUMENT_CHANGED"))
                assertEquals(current, snapshot()); assertEquals(0, ctx.history.depth())
            }
        }
        assertEquals("original", root.resolve("a.txt").readText())
    }

    @Test fun closedLiveTargetCannotFallThroughToDiskForAnyWriteOrEditAlias() = fixture {
        val old = snapshot()
        state.forgetAtOrUnder("a.txt")
        for (tool in listOf("write_file", "edit_file", "write", "edit")) {
            val result = AgentTools.execute(tool, buildJsonObject {
                put("path", "a.txt")
                if (tool.startsWith("write")) put("content", "unauthorized")
                else { put("search", "original"); put("replace", "unauthorized") }
                put("expected_document_id", old.documentId)
                put("expected_revision", old.revision); put("expected_change_version", old.changeVersion)
            }, ctx)
            assertTrue("$tool: $result", result.startsWith("ERROR:"))
            assertEquals("original", root.resolve("a.txt").readText())
            assertEquals(0, ctx.history.depth()); assertNull(authority.snapshot("a.txt"))
        }
    }

    @Test fun reviewedPatchRejectsEqualTextReplacementGeneration() = fixture {
        val ops = PatchTool.parse("*** Begin Patch\n*** Update File: a.txt\n@@\n-original\n+updated\n*** End Patch")
        val review = PatchTool.reviewWorkspaceSnapshot(ops, files, authority)
        val current = replaceOwner(snapshot(), "original")
        val failure = runCatching { PatchTool.applyWorkspaceReviewed(ops, files, ctx.history, authority, ctx.lsp, review) }.exceptionOrNull()
        assertNotNull(failure); assertTrue(failure!!.message.orEmpty().contains("changed after review"))
        assertEquals(current, snapshot()); assertEquals(0, ctx.history.depth())
        assertEquals("original", root.resolve("a.txt").readText())
    }

    private fun formatter(paths: List<String>) = object : LspManager {
        override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
        override suspend fun diagnose(relPath: String) = ""
        override suspend fun lsp(op: String, path: String, line: Int, col: Int) = ""
        override suspend fun formatDocument(path: String) = LspWorkspaceEdit(
            paths.map { LspTextEdit(it, TextRangeEdit(1, 1, 1, 9, "formatted")) },
            expectedContent = paths.associateWith { "original" })
    }

    @Test fun preparedLspEditRejectsEqualTextReplacementGeneration() = fixture {
        val actions = AgentLspEditActions(files, formatter(listOf("a.txt")), ctx.history, authority)
        val prepared = actions.prepareFormat("a.txt")
        val current = replaceOwner(snapshot(), "original")
        val failure = runCatching { actions.apply(prepared) }.exceptionOrNull()
        assertNotNull(failure); assertTrue(failure!!.message.orEmpty().contains("changed after LSP review"))
        assertEquals(current, snapshot()); assertEquals(0, ctx.history.depth())
        assertEquals("original", root.resolve("a.txt").readText())
    }

    @Test fun lspRollbackCannotOverwriteSameCounterReplacementAndRetainsRecovery() = fixture {
        load("b.txt")
        val actions = AgentLspEditActions(files, formatter(listOf("a.txt", "b.txt")), ctx.history, authority)
        val prepared = actions.prepareFormat("a.txt")
        var replacement: WorkspaceDocumentSnapshot? = null
        beforeReplace = { path, reason ->
            if (path == "b.txt" && !reason.startsWith("Rollback")) {
                replacement = replaceOwner(snapshot(), "replacement owner")
                throw IOException("Second target refused")
            }
        }
        assertTrue(runCatching { actions.apply(prepared) }.isFailure)
        assertEquals(requireNotNull(replacement), snapshot())
        assertEquals("original", snapshot("b.txt").content)
        assertEquals(1, ctx.history.depth()); assertBackup("original")
        assertTrue(ctx.history.undo().startsWith("Undo failed:"))
        assertEquals(replacement, snapshot()); assertEquals(1, ctx.history.depth())
    }

    @Test fun bridgeRebindCannotReusePriorOwnerTokenEvenAtEqualTextAndCounters() = fixture {
        val bridge = WorkspaceDocumentAuthorityBridge()
        val oldBinding = bridge.bind(authority)
        val old = requireNotNull(bridge.snapshot("a.txt"))
        val nextState = EditorWorkspaceState()
        try {
            nextState.document("a.txt").ensureLoaded(files)
            val nextAuthority = EditorWorkspaceDocumentAuthority(nextState, Dispatchers.Unconfined)
            val nextBinding = bridge.bind(nextAuthority)
            try {
                oldBinding.close()
                val current = requireNotNull(bridge.snapshot("a.txt"))
                assertEquals(old.revision, current.revision); assertEquals(old.changeVersion, current.changeVersion)
                assertEquals(old.content, current.content); assertNotEquals(old.documentId, current.documentId)
                assertTrue(bridge.replaceText("a.txt", old.version, "unauthorized", "Agent") is WorkspaceDocumentMutationResult.Conflict)
                assertEquals(current, bridge.snapshot("a.txt"))
            } finally { nextBinding.close() }
        } finally { oldBinding.close(); nextState.clear() }
    }
}
