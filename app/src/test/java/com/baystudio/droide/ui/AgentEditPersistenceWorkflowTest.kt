package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentEditPersistenceWorkflowTest {
    private class Fixture(val root: File, val owner: CoroutineDispatcher) {
        val state = EditorWorkspaceState().apply { completeRecovery() }
        var beforeWrite: suspend (FileRepository.Mutation) -> Unit = {}
        var afterWrite: suspend (FileRepository.Mutation) -> Unit = {}
        val files = FileRepository(root, beforeMutation = { beforeWrite(it) }, onMutation = { afterWrite(it) })
        val lsp = object : LspManager {
            override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
            override suspend fun diagnose(relPath: String): String = error("unexpected diagnose")
            override suspend fun lsp(op: String, path: String, line: Int, col: Int): String = error("unexpected LSP request")
        }
        val authority = EditorWorkspaceDocumentAuthority(state, owner, files = files, lsp = lsp)
        val history = EditHistory(files, authority, lsp)
        var commandCalls = 0
        val terminal = object : ITerminalSession {
            override val output = MutableStateFlow("")
            override fun start() { error("unexpected terminal start") }
            override fun send(cmd: String) { error("unexpected terminal send") }
            override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult {
                commandCalls++; return ExecResult(0, root.resolve("a.kt").readText(), false)
            }
            override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult = error("unexpected argv")
            override fun interrupt() {}
            override fun clear() {}
            override fun destroy() {}
        }
        fun context(mode: AgentMode = AgentMode.BUILD, permissions: PermissionEngine = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW)))) =
            AgentTools.Ctx(files, terminal, GitManager(root), ApprovalManager(), QuestionManager(), history, permissions,
                mode, LlmClient(), AgentConfig(baseUrl = "", apiKey = "", model = "unused"), root, TodoManager(), null,
                lsp, documentAuthority = authority)
        suspend fun arguments(path: String, content: String? = null): JsonObject {
            val snapshot = checkNotNull(authority.snapshot(path))
            return buildJsonObject {
            put("path", path); put("expected_document_id", snapshot.documentId)
            put("expected_revision", snapshot.revision); put("expected_change_version", snapshot.changeVersion)
            content?.let { put("content", it) }
            }
        }

        suspend fun open(path: String, text: String = "original\n"): EditorDocument {
            root.resolve(path).writeText(text)
            return state.document(path).also { it.ensureLoaded(files) }
        }
        suspend fun edit(path: String, text: String): WorkspaceDocumentMutationResult.Applied {
            val before = checkNotNull(authority.snapshot(path))
            return authority.replaceText(path, before.version, text, "Agent test") as WorkspaceDocumentMutationResult.Applied
        }
        fun request(edit: WorkspaceDocumentMutationResult.Applied) = WorkspaceDocumentSaveRequest(edit.after.path, edit.after.version, edit)
        suspend fun save(edit: WorkspaceDocumentMutationResult.Applied) = authority.saveDocuments(listOf(request(edit)))
        suspend fun explicit(path: String) = authority.saveDocuments(listOf(WorkspaceDocumentSaveRequest(path, checkNotNull(authority.snapshot(path)).version)))
    }

    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val root = Files.createTempDirectory("droide-agent-save-").toFile()
        val owner = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try { withContext(owner) { withTimeout(30_000) { Fixture(root, owner).block() } } }
        finally { owner.close(); PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun completedLiveEditSavesAndDoesNotTouchUnrelatedDraft() = fixture {
        val a = open("a.kt"); val b = open("b.kt"); b.capture("user draft")
        val mutation = AgentWorkspaceMutation(files, authority)
        val target = mutation.inspect("a.kt") as AgentWorkspaceMutation.Target.Live
        val undo = history.prepareLive("a.kt", target.content)
        val output = mutation.finalizeLive(history, lsp, undo,
            { mutation.replaceLive(target, target.snapshot.version, "agent change", "Agent edit") }, "a.kt", "edit") { "Applied" }
        assertTrue(output, output.contains("saved=1 attention=0"))
        assertEquals("agent change", root.resolve("a.kt").readText()); assertFalse(a.dirty)
        assertEquals("original\n", root.resolve("b.kt").readText()); assertTrue(b.dirty)
        assertNull(AgentEditPersistence.commandBlocker(authority))
        assertTrue(history.undo().startsWith("Undone:"))
        assertEquals("original\n", a.content); assertEquals(a.content, root.resolve("a.kt").readText()); assertFalse(a.dirty)
    }

    @Test fun preexistingDraftNeedsExplicitVersionedSave() = fixture {
        val a = open("a.kt"); a.capture("user draft")
        val edit = edit("a.kt", "user draft + agent")
        val report = save(edit)
        assertFalse(report.ready); assertTrue(report.issues.single().message.contains("user draft"))
        assertEquals("original\n", root.resolve("a.kt").readText()); assertTrue(a.dirty)
        assertNotNull(AgentEditPersistence.commandBlocker(authority))
        assertTrue(explicit("a.kt").ready)
        assertEquals("user draft + agent", root.resolve("a.kt").readText()); assertNull(AgentEditPersistence.commandBlocker(authority))
    }

    @Test fun batchContinuesAfterDiskConflictWithoutSavingNewerDiskOrDraft() = fixture {
        val a = open("a.kt"); val b = open("b.kt")
        val edits = listOf(edit("a.kt", "agent A"), edit("b.kt", "agent B"))
        root.resolve("a.kt").writeText("terminal change")
        val report = authority.saveDocuments(edits.map(::request))
        assertFalse(report.ready); assertEquals(listOf("b.kt"), report.savedPaths)
        assertTrue(report.issues.any { it.path == "a.kt" }); assertTrue(a.dirty); assertFalse(b.dirty)
        assertEquals("terminal change", root.resolve("a.kt").readText())
        assertEquals("agent B", root.resolve("b.kt").readText())
    }

    @Test fun nativeCaptureChangingVersionCannotSaveUserTyping() = fixture {
        val a = open("a.kt"); val edit = edit("a.kt", "agent")
        state.bindProjectHandoffCapture { a.capture("new typing") }.use {
            val report = save(edit)
            assertFalse(report.ready); assertEquals("original\n", root.resolve("a.kt").readText())
            assertEquals("new typing", a.content)
        }
    }

    @Test fun typingWhileIoPreparesSaveStopsPublication() = fixture {
        val a = open("a.kt"); val edit = edit("a.kt", "agent")
        beforeWrite = { withContext(owner) { a.capture("new typing") } }
        val report = save(edit)
        assertFalse(report.ready); assertEquals("original\n", root.resolve("a.kt").readText())
        assertEquals("new typing", a.content); assertTrue(a.dirty)
    }

    @Test fun authorityRebindWhileSaveSuspendsStopsPublication() = fixture {
        open("a.kt"); val edit = edit("a.kt", "agent")
        val bridge = WorkspaceDocumentAuthorityBridge(); val binding = bridge.bind(authority)
        beforeWrite = { withContext(owner) { binding.close(); bridge.bind(EditorWorkspaceDocumentAuthority(EditorWorkspaceState(), owner, files = files)) } }
        val report = bridge.saveDocuments(listOf(request(edit)))
        assertFalse(report.ready); assertEquals("original\n", root.resolve("a.kt").readText())
    }

    @Test fun permissionRevocationAtFinalPublicationPreservesDisk() = fixture {
        open("a.kt"); val edit = edit("a.kt", "agent"); var allowed = true
        beforeWrite = { allowed = false }
        val report = authority.saveDocuments(listOf(request(edit)), { check(allowed) { "Permission revoked" } })
        assertFalse(report.ready); assertTrue(report.issues.single().message.contains("revoked"))
        assertEquals("original\n", root.resolve("a.kt").readText())
    }

    @Test fun stopDuringPreparationDoesNotWriteAndRemainsPending() = fixture {
        val a = open("a.kt"); val edit = edit("a.kt", "agent")
        beforeWrite = { throw CancellationException("Stop") }
        assertTrue(runCatching { save(edit) }.exceptionOrNull() is CancellationException)
        assertEquals("original\n", root.resolve("a.kt").readText()); assertTrue(a.dirty)
        assertEquals(listOf("a.kt"), authority.pendingAgentSavePaths())
    }

    @Test fun typingAfterPublicationKeepsSavedSnapshotAndNewDirtyBuffer() = fixture {
        val a = open("a.kt"); val edit = edit("a.kt", "agent")
        afterWrite = { withContext(owner) { a.capture("new typing") } }
        val report = save(edit)
        assertFalse(report.ready); assertEquals("agent", root.resolve("a.kt").readText())
        assertEquals("agent", a.savedContent); assertEquals("new typing", a.content); assertTrue(a.dirty)
    }

    @Test fun secondFileSuspensionRechecksTheFirstFile() = fixture {
        val a = open("a.kt"); open("b.kt")
        val edits = listOf(edit("a.kt", "agent A"), edit("b.kt", "agent B"))
        beforeWrite = { if (it is FileRepository.Mutation.Write && it.path == "b.kt") withContext(owner) { a.capture("new A") } }
        val report = authority.saveDocuments(edits.map(::request))
        assertFalse(report.ready); assertTrue(report.issues.any { it.path == "a.kt" })
        assertEquals("agent A", root.resolve("a.kt").readText()); assertEquals("new A", a.content)
    }

    @Test fun scopedSaveSharesTheUiSaveMutex() = fixture {
        val a = open("a.kt"); val edit = edit("a.kt", "agent")
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var writes = 0
        beforeWrite = { writes++; entered.complete(Unit); release.await() }
        coroutineScope {
            val automatic = async { save(edit) }; entered.await()
            val manual = async { EditorSaveCoordinator(state, files, ActiveEditorBridge()).saveDocument(a) }
            yield(); assertFalse(manual.isCompleted)
            release.complete(Unit)
            assertTrue(automatic.await().ready); assertTrue(manual.await().ready)
        }
        assertEquals(1, writes); assertEquals("agent", root.resolve("a.kt").readText())
    }

    @Test fun patchSavesAfterCommitAndUndoRestoresEveryFile() = fixture {
        open("a.kt", "a\n"); open("b.kt", "b\n")
        val ops = PatchTool.parse("*** Begin Patch\n*** Update File: a.kt\n@@\n-a\n+A\n*** Update File: b.kt\n@@\n-b\n+B\n*** End Patch")
        val reviewed = PatchTool.reviewWorkspaceSnapshot(ops, files, authority)
        val output = PatchTool.applyWorkspaceReviewed(ops, files, history, authority, lsp, reviewed)
        assertTrue(output, output.contains("saved=2 attention=0"))
        assertEquals("A\n", root.resolve("a.kt").readText()); assertEquals("B\n", root.resolve("b.kt").readText())
        assertTrue(history.undo().startsWith("Undone:"))
        assertEquals("a\n", root.resolve("a.kt").readText()); assertEquals("b\n", root.resolve("b.kt").readText())
    }

    @Test fun saveFailureAfterPatchCommitMustNotRollbackTheEdits() = fixture {
        open("a.kt", "a\n"); open("b.kt", "b\n")
        beforeWrite = { if (it is FileRepository.Mutation.Write && it.path == "b.kt") error("Disk full") }
        val ops = PatchTool.parse("*** Begin Patch\n*** Update File: a.kt\n@@\n-a\n+A\n*** Update File: b.kt\n@@\n-b\n+B\n*** End Patch")
        val reviewed = PatchTool.reviewWorkspaceSnapshot(ops, files, authority)
        val output = PatchTool.applyWorkspaceReviewed(ops, files, history, authority, lsp, reviewed)
        assertTrue(output, output.contains("attention=1")); assertEquals(1, history.depth())
        assertEquals("A\n", root.resolve("a.kt").readText()); assertEquals("b\n", root.resolve("b.kt").readText())
        assertEquals("B\n", state.peek("b.kt")!!.content)
    }

    @Test fun mutationWithoutExplicitPersistenceRemainsMemoryOnly() = fixture {
        open("a.kt"); edit("a.kt", "agent")
        assertEquals("original\n", root.resolve("a.kt").readText())
        assertTrue(state.peek("a.kt")!!.dirty)
    }

    @Test fun unsupportedSaveAuthorityNeverPretendsSuccess() = fixture {
        open("a.kt"); val edit = edit("a.kt", "agent")
        val unavailable = object : WorkspaceDocumentAuthority {
            override suspend fun snapshot(path: String) = authority.snapshot(path)
            override suspend fun snapshots() = authority.snapshots()
        }
        val report = unavailable.saveDocuments(listOf(request(edit)))
        assertFalse(report.ready); assertTrue(report.savedPaths.isEmpty())
        assertEquals("original\n", root.resolve("a.kt").readText())
    }
    @Test fun actualWriteAndSaveToolsThenCommandSeePersistedBytes() = fixture {
        val document = open("a.kt")
        val context = context()
        val written = AgentTools.execute("write_file", arguments("a.kt", "generated"), context)
        assertTrue(written, written.contains("saved=1 attention=0"))
        assertEquals("generated", root.resolve("a.kt").readText()); assertFalse(document.dirty)
        document.capture("user draft")
        val edited = AgentTools.execute("write_file", arguments("a.kt", "user draft plus agent"), context)
        assertTrue(edited, edited.contains("attention=1"))
        val command = buildJsonObject { put("command", "cat a.kt") }
        val blocked = AgentTools.execute("run_command", command, context)
        assertTrue(blocked, blocked.contains("AGENT_EDITS_NOT_SAVED")); assertEquals(0, commandCalls)
        val currentFile = arguments("a.kt")
        val saved = AgentTools.execute("save_files", buildJsonObject { put("files", JsonArray(listOf(currentFile))) }, context)
        assertTrue(saved, saved.contains("saved=1 attention=0"))
        val result = AgentTools.execute("run_command", command, context)
        assertTrue(result, result.contains("user draft plus agent")); assertEquals(1, commandCalls)
    }

    @Test fun explicitSaveRejectsStaleIdentityDuplicateTargetsAndReadOnlyModes() = fixture {
        val document = open("a.kt"); document.capture("draft")
        val args = arguments("a.kt")
        val filesArg = buildJsonObject { put("files", JsonArray(listOf(args))) }
        for (mode in listOf(AgentMode.PLAN, AgentMode.REVIEW, AgentMode.EXPLORE)) {
            assertTrue(AgentTools.execute("save_files", filesArg, context(mode)).startsWith("DENIED"))
        }
        val deny = PermissionEngine(listOf(PermRule("edit", "*", PermEffect.DENY)))
        assertTrue(AgentTools.execute("save_files", filesArg, context(permissions = deny)).startsWith("DENIED"))
        val duplicate = AgentTools.execute("save_files", buildJsonObject { put("files", JsonArray(listOf(args, args))) }, context())
        assertTrue(duplicate, duplicate.contains("Duplicate"))
        document.capture("newer")
        val stale = AgentTools.execute("save_files", filesArg, context())
        assertTrue(stale, stale.contains("DOCUMENT_CHANGED"))
        assertEquals("original\n", root.resolve("a.kt").readText()); assertTrue(document.dirty)
    }

    @Test fun lspWorkspaceBatchUsesTheSamePersistenceBoundary() = fixture {
        open("a.kt", "a\n")
        val before = checkNotNull(authority.snapshot("a.kt"))
        val prepared = AgentLspEditActions.Prepared("Format", LspWorkspaceEdit(emptyList()),
            mapOf("a.kt" to before.content), mapOf("a.kt" to before.version), mapOf("a.kt" to "A\n"), "", "a.kt")
        val output = AgentLspEditActions(files, lsp, history, authority).apply(prepared)
        assertTrue(output, output.contains("saved=1 attention=0"))
        assertEquals("A\n", root.resolve("a.kt").readText())
    }

}
