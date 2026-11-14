package com.baystudio.droide.core

import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/** Report-only R34 candidate; bounded local files and in-memory approvals, never a real backend. */
class AgentProposedChangeWorkflowR34Test {
    @Test(timeout = 30_000)
    fun diskWriteAndEditExposeCountsBeforeDenyCancelStaleOrAllow() =
        verifyGates(listOf(Gate.DISK_WRITE, Gate.DISK_EDIT))

    @Test(timeout = 30_000)
    fun liveWriteAndEditCountUnsavedTextWithoutTouchingDisk() =
        verifyGates(listOf(Gate.LIVE_WRITE, Gate.LIVE_EDIT))

    @Test(timeout = 30_000)
    fun patchAggregatesCreateMoveAndDeleteBeforeAnyMutation() =
        verifyGates(listOf(Gate.PATCH))

    @Test(timeout = 30_000)
    fun formattingAndCodeActionUsePreparedLspCountsBeforeMutation() =
        verifyGates(listOf(Gate.LSP_FORMAT, Gate.LSP_ACTION))

    private fun verifyGates(gates: List<Gate>) {
        // Fixed finite table: 7 producers x 4 outcomes across the four methods above.
        for (gate in gates) for (outcome in Outcome.entries) verifyProposal(gate, outcome)
    }

    private fun verifyProposal(gate: Gate, outcome: Outcome) = runBlocking {
        val root = Files.createTempDirectory("droide-r34-proposal-").toFile()
        val terminal = NoBackendTerminal()
        val forbiddenCalls = AtomicInteger()
        val web = AgentWebResearch(root,
            AgentWebTransport { _, _ ->
                forbiddenCalls.incrementAndGet()
                throw AssertionError("Network transport is forbidden in R34 fixtures")
            }, object : AgentWebPdfBackend {
                private fun forbidden(): Nothing {
                    forbiddenCalls.incrementAndGet()
                    throw AssertionError("PDF/device backend is forbidden in R34 fixtures")
                }
                override fun pageCount(file: File): Int = forbidden()
                override fun text(file: File, page: Int): String? = forbidden()
                override fun screenshot(file: File, page: Int, output: OutputStream): AgentWebImageSize = forbidden()
            })
        var childrenStopped = true
        try {
            val initialDisk = linkedMapOf<String, String?>(
                "main.txt" to if (gate.live) "disk only one\ndisk only two\n" else "before\n",
                "moved.txt" to null,
                "created.txt" to null,
                "deleted.txt" to if (gate == Gate.PATCH) "a\nb\nc\n" else null,
            )
            initialDisk.forEach { (path, text) -> if (text != null) root.resolve(path).writeText(text) }
            val documents = if (gate.live) AgentWorkflowDocuments(agentWorkflowSnapshot("main.txt", "unsaved\n")) else null
            val originalDocument = documents?.current
            val progress = CopyOnWriteArrayList<AgentToolProgress>()
            val lsp = FixtureLsp()
            val base = agentWorkflowContext(root,
                documents = documents,
                permissions = PermissionEngine(listOf(
                    PermRule("*", "*", PermEffect.ALLOW),
                    PermRule("edit", "*", PermEffect.ASK),
                    PermRule("lsp_edit", "*", PermEffect.ASK),
                )),
                terminal = terminal, webResearch = web,
                provenance = AgentRequestProvenance.primary("r34-session"),
                onProgress = progress::add)
            val ctx = AgentTools.Ctx(
                files = base.files, terminal = terminal, git = base.git, approvals = base.approvals,
                questions = base.questions, history = base.history, perms = base.perms, mode = base.mode,
                llm = base.llm, llmConfig = base.llmConfig, workDir = root, todos = base.todos,
                sessionId = base.sessionId, lsp = lsp, provenance = base.provenance,
                documentAuthority = documents,
                lspEditActions = AgentLspEditActions(base.files, lsp, base.history, documents),
                webResearch = web, onProgress = progress::add,
            )
            val expectedTotals = if (gate == Gate.PATCH) AgentChangeTotals(3L, 4L) else AgentChangeTotals(1L, 1L)
            withTimeout(8_000) { coroutineScope {
                val work = async(start = CoroutineStart.UNDISPATCHED) {
                    AgentTools.execute(gate.tool, arguments(gate, originalDocument), ctx)
                }
                childrenStopped = false
                try {
                    val pending = requireNotNull(ctx.approvals.pending.first { it != null })
                    assertEquals("$gate/$outcome", expectedTotals, pending.proposedChangeTotals)
                    assertTrue(requireNotNull(pending.proposedChangeTotals).known)
                    assertEquals(if (gate.lsp) "lsp_edit" else "edit", pending.action)
                    assertEquals("build", pending.permissionScope)
                    assertEquals("r34-session", pending.approvalSessionId)
                    assertTrue(pending.canAllowForSession)
                    assertFalse(work.isCompleted)
                    assertDisk(root, initialDisk)
                    assertEquals(originalDocument, documents?.current)
                    assertTrue(progress.none { it.fileChanges != null })
                    assertEquals(0, ctx.history.depth())
                    assertEquals(0, terminal.calls.get())
                    assertEquals(if (gate.lsp) 1 else 0, lsp.preparations.get())

                    val expectedDisk = initialDisk.toMutableMap()
                    var expectedDocument = originalDocument
                    when (outcome) {
                        Outcome.DENY -> {
                            assertTrue(ctx.approvals.resolve(pending.id, ApprovalResolution.DENY))
                            assertTrue(work.await().startsWith("DENIED"))
                        }
                        Outcome.CANCEL -> {
                            work.cancelAndJoin()
                            assertTrue(work.isCancelled)
                            assertFalse(ctx.approvals.resolve(pending.id, ApprovalResolution.ALLOW_SESSION))
                        }
                        Outcome.STALE -> {
                            if (documents != null) {
                                documents.current = documents.current.copy(content = "newer user\n",
                                    changeVersion = documents.current.changeVersion + 1)
                                expectedDocument = documents.current
                            } else {
                                root.resolve("main.txt").writeText("newer user\n")
                                expectedDisk["main.txt"] = "newer user\n"
                            }
                            assertTrue(ctx.approvals.resolve(pending.id, ApprovalResolution.ALLOW_ONCE))
                            val result = work.await()
                            assertTrue(result, result.startsWith("ERROR:"))
                        }
                        Outcome.ALLOW -> {
                            assertTrue(ctx.approvals.resolve(pending.id, ApprovalResolution.ALLOW_ONCE))
                            val result = work.await()
                            assertTrue(result, result.startsWith(if (gate.lsp) "LSP_EDIT_APPLIED" else "OK:"))
                            if (documents != null) {
                                assertEquals("reviewed\n", documents.current.content)
                                assertEquals(requireNotNull(originalDocument).changeVersion + 1, documents.current.changeVersion)
                                expectedDocument = documents.current
                            } else if (gate == Gate.PATCH) {
                                expectedDisk["main.txt"] = null
                                expectedDisk["moved.txt"] = "reviewed\n"
                                expectedDisk["created.txt"] = "one\ntwo\n"
                                expectedDisk["deleted.txt"] = null
                            } else expectedDisk["main.txt"] = "reviewed\n"
                            val published = progress.mapNotNull { it.fileChanges }.single()
                            assertEquals(expectedTotals, AgentFileChanges.totals(published))
                            if (gate == Gate.PATCH) {
                                assertEquals(listOf("created.txt", "moved.txt", "deleted.txt"), published.map { it.path })
                                assertEquals("main.txt", published[1].previousPath)
                            }
                            assertTrue(ctx.history.depth() > 0)
                        }
                    }
                    assertDisk(root, expectedDisk)
                    assertEquals(expectedDocument, documents?.current)
                    if (outcome != Outcome.ALLOW) {
                        assertTrue(progress.none { it.fileChanges != null })
                        assertEquals(0, ctx.history.depth())
                    }
                    assertNull(ctx.approvals.pending.value)
                    assertFalse(ctx.approvals.hasSessionApproval("r34-session", "build", pending.action, pending.resource))
                    assertEquals(0, terminal.calls.get())
                    assertEquals(0, forbiddenCalls.get())
                } finally {
                    // Do not let directory deletion race a suspended approval or disk mutation.
                    withContext(NonCancellable) { withTimeout(2_000) { work.cancelAndJoin() } }
                    childrenStopped = true
                }
            } }
        } finally {
            web.close()
            check(childrenStopped) { "R34 child did not stop; fixture directory retained at $root" }
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    private fun arguments(gate: Gate, document: WorkspaceDocumentSnapshot?): JsonObject = buildJsonObject {
        when (gate) {
            Gate.DISK_WRITE, Gate.LIVE_WRITE -> { put("path", "main.txt"); put("content", "reviewed\n") }
            Gate.DISK_EDIT, Gate.LIVE_EDIT -> {
                put("path", "main.txt"); put("search", if (gate.live) "unsaved" else "before"); put("replace", "reviewed")
            }
            Gate.PATCH -> put("patchText", "*** Begin Patch\n*** Add File: created.txt\n+one\n+two\n" +
                "*** Update File: main.txt\n*** Move to: moved.txt\n@@\n-before\n+reviewed\n" +
                "*** Delete File: deleted.txt\n*** End Patch")
            Gate.LSP_FORMAT, Gate.LSP_ACTION -> {
                put("path", "main.txt"); put("operation", if (gate == Gate.LSP_FORMAT) "format" else "apply_action")
                if (gate == Gate.LSP_ACTION) put("title", "Fixture replacement")
            }
        }
        if (document != null) {
            put("expected_document_id", document.documentId)
            put("expected_revision", document.revision)
            put("expected_change_version", document.changeVersion)
        }
    }

    private fun assertDisk(root: File, expected: Map<String, String?>) {
        expected.forEach { (path, content) ->
            val file = root.resolve(path)
            if (content == null) assertFalse("Unexpected file: $path", file.exists())
            else assertArrayEquals("Bytes changed for $path", content.toByteArray(Charsets.UTF_8), file.readBytes())
        }
    }

    private class FixtureLsp : LspManager {
        override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
        val preparations = AtomicInteger()
        private fun edit() = LspWorkspaceEdit(
            listOf(LspTextEdit("main.txt", TextRangeEdit(1, 1, 1, 7, "reviewed"))),
            expectedContent = mapOf("main.txt" to "before\n"))
        override suspend fun diagnose(relPath: String): String = throw AssertionError("Unexpected LSP diagnose")
        override suspend fun lsp(op: String, path: String, line: Int, col: Int): String = throw AssertionError("Unexpected LSP backend")
        override suspend fun formatDocument(path: String): LspWorkspaceEdit {
            assertEquals("main.txt", path); preparations.incrementAndGet(); return edit()
        }
        override suspend fun codeActionsAt(path: String, line: Int, col: Int): List<LspCodeAction> {
            assertEquals("main.txt", path); preparations.incrementAndGet()
            return listOf(LspCodeAction("Fixture replacement", edit = edit()))
        }
    }

    private class NoBackendTerminal : ITerminalSession {
        override val output = MutableStateFlow("")
        override val supportsCommandInput = false
        val calls = AtomicInteger()
        private fun forbidden(): Nothing { calls.incrementAndGet(); throw AssertionError("Terminal backend is forbidden in R34 fixtures") }
        override suspend fun prepareCommandInput(): Boolean = forbidden()
        override fun start() = forbidden()
        override fun send(cmd: String) = forbidden()
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = forbidden()
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult = forbidden()
        override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult = forbidden()
        override suspend fun execStreamingWithInput(cmd: String, timeoutMs: Long, input: ProcessInputBuffer,
            onOutput: (String) -> Unit): ExecResult = forbidden()
        override fun interrupt() = forbidden()
        override fun clear() = forbidden()
        override fun destroy() = forbidden()
    }

    private enum class Gate(val tool: String, val live: Boolean = false, val lsp: Boolean = false) {
        DISK_WRITE("write_file"), DISK_EDIT("edit_file"),
        LIVE_WRITE("write_file", live = true), LIVE_EDIT("edit_file", live = true),
        PATCH("apply_patch"), LSP_FORMAT("lsp_edit", lsp = true), LSP_ACTION("lsp_edit", lsp = true),
    }
    private enum class Outcome { DENY, CANCEL, STALE, ALLOW }
}
