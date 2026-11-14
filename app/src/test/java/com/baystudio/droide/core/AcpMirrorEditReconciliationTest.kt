package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import com.baystudio.droide.ui.EditorWorkspaceDocumentAuthority
import com.baystudio.droide.ui.EditorWorkspaceState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real host, repository, durable local mirror, inventory and transfer; only the ACP process is fake. */
class AcpMirrorEditReconciliationTest {
    private class Peer : AutoCloseable {
        var promptCount = 0
        private var currentModeId = "build"
        private val alive = AtomicBoolean(true)
        private val requests = Channel<JsonObject>(8)
        private val replies = Channel<JsonObject>(8)
        private val serverOutput = PipedOutputStream()
        private val clientOutput = PipedInputStream(serverOutput, 4_096)
        private val captured = ByteArrayOutputStream()
        private val clientInput = object : OutputStream() {
            @Synchronized override fun write(value: Int) { captured.write(value) }
            @Synchronized override fun write(bytes: ByteArray, offset: Int, length: Int) { captured.write(bytes, offset, length) }
            @Synchronized override fun flush() {
                val text = captured.toString("UTF-8")
                if (text.isEmpty()) return
                check(text.endsWith('\n'))
                captured.reset()
                text.lineSequence().filter(String::isNotBlank).forEach { line ->
                    val request = Json.parseToJsonElement(line).jsonObject
                    if (!request.containsKey("method")) { check(replies.trySend(request).isSuccess); return@forEach }
                    when (request.getValue("method").jsonPrimitive.content) {
                        "initialize" -> respond(request, buildJsonObject {
                            put("protocolVersion", 1)
                            put("agentCapabilities", buildJsonObject { put("loadSession", true) })
                        })
                        "session/new" -> respond(request, buildJsonObject {
                            put("sessionId", "binary_fixture"); put("modes", modeConfiguration().getValue("modes"))
                        })
                        "session/load" -> respond(request, modeConfiguration())
                        "session/set_mode" -> {
                            val params = request.getValue("params").jsonObject
                            check(params.getValue("sessionId").jsonPrimitive.content == "binary_fixture")
                            val requested = params.getValue("modeId").jsonPrimitive.content
                            check(requested in listOf("build", "plan"))
                            currentModeId = requested
                            respond(request, modeConfiguration())
                        }
                        "session/prompt", "session/cancel" -> {
                            if (request.getValue("method").jsonPrimitive.content == "session/prompt") promptCount++
                            check(requests.trySend(request).isSuccess)
                        }
                        else -> error("Unexpected ACP method")
                    }
                }
            }
        }
        val process = object : HostedStdioProcess {
            override val stdin = clientInput
            override val stdout = clientOutput
            override val stderr = ByteArrayInputStream(byteArrayOf())
            override val isAlive get() = alive.get()
            override val exitCode get() = if (alive.get()) null else 0
            override suspend fun awaitExit() = 0
            override fun close() = this@Peer.close()
        }
        suspend fun prompt(): JsonObject = withTimeout(5_000) {
            val request = requests.receive()
            check(request.getValue("method").jsonPrimitive.content == "session/prompt")
            request
        }
        suspend fun reply(): JsonObject = withTimeout(5_000) { replies.receive() }
        @Synchronized fun permission(path: String, content: String, allowOption: Boolean = true, kind: String = "edit") {
            val frame = buildJsonObject {
                put("jsonrpc", "2.0"); put("id", "edit-permission"); put("method", "session/request_permission")
                put("params", buildJsonObject {
                    put("sessionId", "binary_fixture")
                    put("toolCall", buildJsonObject {
                        put("toolCallId", "exact-edit"); put("title", "Edit exact file"); put("kind", kind)
                        put("rawInput", buildJsonObject { put("path", path); put("content", content) })
                    })
                    put("options", buildJsonArray {
                        if (allowOption) add(buildJsonObject { put("optionId", "allow"); put("kind", "allow_once") })
                        add(buildJsonObject { put("optionId", "deny"); put("kind", "reject_once") })
                    })
                })
            }
            serverOutput.write((frame.toString() + "\n").toByteArray()); serverOutput.flush()
        }
        fun finish(request: JsonObject) = respond(request, buildJsonObject { put("stopReason", "end_turn") })
        private fun modeConfiguration() = buildJsonObject {
            put("modes", buildJsonObject {
                put("currentModeId", currentModeId)
                put("availableModes", buildJsonArray {
                    listOf("build", "plan").forEach { id -> add(buildJsonObject { put("id", id); put("name", id) }) }
                })
            })
        }
        @Synchronized private fun respond(request: JsonObject, result: JsonObject) {
            val frame = buildJsonObject { put("jsonrpc", "2.0"); put("id", request.getValue("id")); put("result", result) }
            serverOutput.write((frame.toString() + "\n").toByteArray())
            serverOutput.flush()
        }
        override fun close() {
            if (alive.compareAndSet(true, false)) {
                runCatching { serverOutput.close() }; runCatching { clientOutput.close() }; requests.close()
            }
        }
    }

    private class ObservedIo(private val actual: AgentWorkspaceIo) : AgentWorkspaceIo by actual {
        var snapshotTransform: (Map<String, String>) -> Map<String, String> = { it }
        var beforePull: suspend (String) -> Unit = {}
        var beforeRefresh: suspend () -> Unit = {}
        var beforeSnapshotEntries: suspend () -> Unit = {}
        var pulls = 0
        override suspend fun refresh() { beforeRefresh(); actual.refresh() }
        override suspend fun snapshot(): Map<String, String>? = actual.snapshot()?.let(snapshotTransform)
        override suspend fun snapshotEntries(): Map<String, WorkspaceSyncManifest.Entry>? {
            beforeSnapshotEntries()
            return actual.snapshotEntries()?.let { entries ->
                snapshotTransform(entries.mapValues { it.value.sha256 }).mapValues { (path, hash) ->
                    WorkspaceSyncManifest.Entry(hash, entries[path]?.mode)
                }
            }
        }
        override suspend fun shellBounded(command: String, maxOutputBytes: Int): BridgeShellResult =
            error("Local reconciliation must use host inventory and trusted baseline without starting Linux")
        override suspend fun pull(path: String, output: File) {
            pulls++
            beforePull(path)
            actual.pull(path, output)
        }
    }

    private class Fixture(
        val source: File,
        val working: File,
        val host: WorkspaceAgentPluginHost,
        val peer: Peer,
        val io: ObservedIo,
        val approvals: ApprovalManager,
        val local: LocalAgentWorkspaceProcessHost,
        val files: FileRepository,
        val documents: WorkspaceDocumentAuthorityBridge,
    ) {
        suspend fun start(mode: WorkspaceAgentMode = WorkspaceAgentMode.EDIT): WorkspaceAgentTurnResult =
            host.runMediaTurn("opencode", "Build the requested project assets", emptyList(), null, mode, "binary_fixture", {})
        suspend fun turn(mode: WorkspaceAgentMode = WorkspaceAgentMode.EDIT, produce: suspend Fixture.() -> Unit): WorkspaceAgentTurnResult = coroutineScope {
            val work = async { start(mode) }
            val request = peer.prompt()
            produce()
            peer.finish(request)
            work.await()
        }
    }

    private fun fixture(
        effect: PermEffect = PermEffect.ALLOW,
        extraRules: List<PermRule> = emptyList(),
        initial: (File) -> Unit = {},
        beforeMutation: (suspend (FileRepository.Mutation) -> Unit)? = null,
        newFilePublisher: NewProjectFilePublisher = NewProjectFilePublisher.platform,
        block: suspend CoroutineScope.(Fixture) -> Unit,
    ) = runBlocking {
        val root = Files.createTempDirectory("droide-binary-host-").toFile()
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val owned = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, failure -> uncaught += failure })
        val source = File(root, "project").apply { mkdirs(); initial(this) }
        val cache = File(root, "cache").apply { mkdirs() }
        val local = LocalAgentWorkspaceProcessHost(source, cache, owned, File(root, "persistent-workspaces"))
        val peer = Peer()
        val processes = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command: String) = "/fixture/$command"
            override suspend fun pathMapper() = local.pathMapper()
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
                check(argv == listOf("/fixture/opencode", "acp"))
                return peer.process
            }
        }
        val io = ObservedIo(local.workspaceIo)
        val approvals = ApprovalManager()
        val documents = WorkspaceDocumentAuthorityBridge()
        val files = FileRepository(source, newFilePublisher = newFilePublisher, beforeMutation = {
            documents.validatePersistedMutation(it)
            beforeMutation?.invoke(it)
        }, onMutation = documents::persistedMutation)
        val host = WorkspaceAgentPluginHost(source, cache, files, processes, approvals,
            PermissionEngine(listOf(PermRule("*", "*", effect)) + extraRules), owned, io)
        try {
            withTimeout(35_000) {
                host.prepareSession("opencode", null, {})
                block(Fixture(source, File(local.pathMapper().remoteRoot), host, peer, io, approvals, local, files, documents))
            }
        } finally {
            host.close(); peer.close()
            withTimeout(3_000) { owned.coroutineContext.job.cancelAndJoin() }
            PathSecurity.deleteTreeNoFollow(root)
            assertTrue("Unexpected background failure: $uncaught", uncaught.isEmpty())
        }
    }

    private suspend fun awaitReviewBeforeCompletion(f: Fixture, work: Deferred<WorkspaceAgentTurnResult>): ApprovalRequest = coroutineScope {
        val waiting = async { f.approvals.pending.filterNotNull().first() }
        try {
            val approval = withTimeout(5_000) {
                kotlinx.coroutines.selects.select<ApprovalRequest?> {
                    waiting.onAwait { it }
                    work.onAwait { null }
                }
            }
            val target = File(f.source, "file.txt")
            assertNotNull("Changed review was applied without a new decision; actual project=" +
                if (target.isFile) target.readText() else "<absent>", approval)
            checkNotNull(approval)
        } finally { waiting.cancelAndJoin() }
    }

    private suspend fun CoroutineScope.grantFirstTurn(
        f: Fixture, resolution: ApprovalResolution = ApprovalResolution.ALLOW_SESSION,
        produce: suspend Fixture.() -> Unit,
    ): Pair<ApprovalRequest, WorkspaceAgentTurnResult> {
        val work = async { f.start() }
        val prompt = f.peer.prompt()
        f.produce()
        f.peer.finish(prompt)
        val approval = awaitReviewBeforeCompletion(f, work)
        assertTrue(f.approvals.resolve(approval.id, resolution))
        return approval to work.await()
    }

    private suspend fun approveEdit(f: Fixture, work: Deferred<WorkspaceAgentTurnResult>,
                                    allowOption: Boolean = true, kind: String = "edit") {
        f.peer.permission(File(f.working, "file.txt").path, "approved", allowOption, kind)
        val approval = awaitReviewBeforeCompletion(f, work)
        assertEquals("agent_plugin_tool", approval.kind)
        assertTrue(f.approvals.resolve(approval.id, ApprovalResolution.ALLOW_ONCE))
        val result = f.peer.reply().getValue("result").jsonObject.getValue("outcome").jsonObject
        assertEquals(if (allowOption) "selected" else "cancelled", result.getValue("outcome").jsonPrimitive.content)
    }

    @Test(timeout = 45_000) fun oneExactApprovalPublishesNativeMirrorEdit() = fixture(
        effect = PermEffect.ASK, initial = { File(it, "file.txt").writeText("original") },
    ) { f ->
        val work = async { f.start() }; val prompt = f.peer.prompt()
        approveEdit(f, work)
        File(f.working, "file.txt").writeText("approved") // Native CLI edit, never fs/write_text_file.
        f.peer.finish(prompt)
        val result = withTimeout(5_000) { work.await() }
        assertEquals(listOf("file.txt"), result.changedFiles)
        assertEquals("approved", File(f.source, "file.txt").readText())
        assertNull(f.approvals.pending.value)
    }

    @Test(timeout = 45_000) fun changedContentStillRequiresSeparateDiffConsent() = fixture(
        effect = PermEffect.ASK, initial = { File(it, "file.txt").writeText("original") },
    ) { f ->
        val work = async { f.start() }; val prompt = f.peer.prompt()
        approveEdit(f, work); File(f.working, "file.txt").writeText("not approved"); f.peer.finish(prompt)
        val review = awaitReviewBeforeCompletion(f, work)
        assertEquals("agent_workspace_reconcile", review.kind)
        assertTrue(f.approvals.resolve(review.id, ApprovalResolution.DENY))
        assertTrue(work.await().changedFiles.isEmpty()); assertEquals("original", File(f.source, "file.txt").readText())
    }

    @Test(timeout = 45_000) fun changedBasePreservesConflictInsteadOfUsingGrant() = fixture(
        effect = PermEffect.ASK, initial = { File(it, "file.txt").writeText("original") },
    ) { f ->
        val work = async { f.start() }; val prompt = f.peer.prompt(); approveEdit(f, work)
        File(f.source, "file.txt").writeText("user edit"); File(f.working, "file.txt").writeText("approved")
        f.peer.finish(prompt); val result = work.await()
        assertTrue(result.changedFiles.isEmpty()); assertTrue("file.txt" in result.conflicts)
        assertEquals("user edit", File(f.source, "file.txt").readText())
    }

    @Test(timeout = 45_000) fun absentAllowOnceDoesNotSeedMirrorConsent() = fixture(
        effect = PermEffect.ASK, initial = { File(it, "file.txt").writeText("original") },
    ) { f ->
        val work = async { f.start() }; val prompt = f.peer.prompt(); approveEdit(f, work, allowOption = false)
        File(f.working, "file.txt").writeText("approved"); f.peer.finish(prompt)
        val review = awaitReviewBeforeCompletion(f, work); assertEquals("agent_workspace_reconcile", review.kind)
        assertTrue(f.approvals.resolve(review.id, ApprovalResolution.DENY)); assertTrue(work.await().changedFiles.isEmpty())
    }

    @Test(timeout = 45_000) fun genericToolApprovalDoesNotAuthorizeMirrorEdit() = fixture(
        effect = PermEffect.ASK, initial = { File(it, "file.txt").writeText("original") },
    ) { f ->
        val work = async { f.start() }; val prompt = f.peer.prompt(); approveEdit(f, work, kind = "execute")
        File(f.working, "file.txt").writeText("approved"); f.peer.finish(prompt)
        val review = awaitReviewBeforeCompletion(f, work); assertEquals("agent_workspace_reconcile", review.kind)
        assertTrue(f.approvals.resolve(review.id, ApprovalResolution.DENY)); assertTrue(work.await().changedFiles.isEmpty())
    }

    @Test(timeout = 45_000) fun modeChangeRequiresNewDiffConsent() = fixture(
        effect = PermEffect.ASK, initial = { File(it, "file.txt").writeText("original") },
    ) { f ->
        val work = async { f.start() }; val prompt = f.peer.prompt(); approveEdit(f, work)
        File(f.working, "file.txt").apply { writeText("approved"); setExecutable(true, true) }; f.peer.finish(prompt)
        val review = awaitReviewBeforeCompletion(f, work); assertEquals("agent_workspace_reconcile", review.kind)
        assertTrue(f.approvals.resolve(review.id, ApprovalResolution.DENY)); assertTrue(work.await().changedFiles.isEmpty())
    }
    @Test(timeout = 45_000) fun sourceTurnGrantSurvivesDirectLedgerTimeout() = fixture(
        effect = PermEffect.ASK, initial = { File(it, "file.txt").writeText("original") },
    ) { f ->
        val work = async { f.start() }; val prompt = f.peer.prompt(); approveEdit(f, work)
        delay(15_100) // Existing direct-write ledger TTL is 15 seconds; this is the same live native turn.
        File(f.working, "file.txt").writeText("approved"); f.peer.finish(prompt)
        assertEquals(listOf("file.txt"), withTimeout(5_000) { work.await() }.changedFiles)
    }

    @Test(timeout = 45_000) fun failedPublicationCannotReuseApprovalOnRetry() = fixture(
        effect = PermEffect.ASK, initial = { File(it, "file.txt").writeText("original") },
    ) { f ->
        val work = async { f.start() }; val prompt = f.peer.prompt(); approveEdit(f, work)
        File(f.working, "file.txt").writeText("approved")
        f.io.beforePull = { error("fixture transfer failure") }; f.peer.finish(prompt)
        assertTrue(withTimeout(5_000) { work.await() }.changedFiles.isEmpty())
        f.io.beforePull = {}
        val retry = async { f.host.reviewWorkspaceChanges("opencode") }
        val review = withTimeout(5_000) { f.approvals.pending.filterNotNull().first() }
        assertEquals("agent_workspace_reconcile", review.kind)
        f.approvals.resolve(review.id, ApprovalResolution.DENY); retry.await()
        assertEquals("original", File(f.source, "file.txt").readText())
    }

    @Test(timeout = 45_000) fun exactApprovalImportsNewOrdinaryFile() = fixture(effect = PermEffect.ASK) { f ->
        val work = async { f.start() }; val prompt = f.peer.prompt(); approveEdit(f, work)
        File(f.working, "file.txt").writeText("approved"); f.peer.finish(prompt)
        assertEquals(listOf("file.txt"), withTimeout(5_000) { work.await() }.changedFiles)
        assertEquals("approved", File(f.source, "file.txt").readText())
        assertFalse(File(f.source, "file.txt").canExecute())
    }

    @Test(timeout = 45_000) fun multipleCandidatesStillRequireSeparateDiffApproval() = fixture(
        effect = PermEffect.ASK, initial = { File(it, "file.txt").writeText("original") },
    ) { f ->
        val work = async { f.start() }; val prompt = f.peer.prompt(); approveEdit(f, work)
        File(f.working, "file.txt").writeText("approved"); File(f.working, "other.txt").writeText("unapproved")
        f.peer.finish(prompt); val review = awaitReviewBeforeCompletion(f, work)
        assertEquals("agent_workspace_reconcile", review.kind)
        f.approvals.resolve(review.id, ApprovalResolution.DENY); assertTrue(work.await().changedFiles.isEmpty())
        assertEquals("original", File(f.source, "file.txt").readText()); assertFalse(File(f.source, "other.txt").exists())
    }

    @Test(timeout = 45_000) fun workspaceDenyOverridesExactWriteApproval() = fixture(
        effect = PermEffect.ASK, extraRules = listOf(PermRule("edit_workspace", "*", PermEffect.DENY)),
        initial = { File(it, "file.txt").writeText("original") },
    ) { f ->
        val work = async { f.start() }; val prompt = f.peer.prompt(); approveEdit(f, work)
        File(f.working, "file.txt").writeText("approved"); f.peer.finish(prompt)
        assertTrue(withTimeout(5_000) { work.await() }.changedFiles.isEmpty())
        assertEquals("original", File(f.source, "file.txt").readText())
    }

}
