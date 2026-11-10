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
class WorkspaceAgentBinaryReconciliationTest {
    private class Peer : AutoCloseable {
        var promptCount = 0
        private var currentModeId = "build"
        private val alive = AtomicBoolean(true)
        private val requests = Channel<JsonObject>(8)
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
        val startFailure: java.util.concurrent.atomic.AtomicReference<Exception?>,
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
        val startFailure = java.util.concurrent.atomic.AtomicReference<Exception?>()
        val processes = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command: String) = "/fixture/$command"
            override suspend fun pathMapper() = local.pathMapper()
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
                startFailure.get()?.let { throw it }
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
            PermissionEngine(listOf(PermRule("*", "*", effect))), owned, io)
        try {
            withTimeout(35_000) {
                host.prepareSession("opencode", null, {})
                block(Fixture(source, File(local.pathMapper().remoteRoot), host, peer, io, approvals, local, files, documents, startFailure))
            }
        } finally {
            host.close(); peer.close()
            withTimeout(3_000) { owned.coroutineContext.job.cancelAndJoin() }
            PathSecurity.deleteTreeNoFollow(root)
            assertTrue("Unexpected background failure: $uncaught", uncaught.isEmpty())
        }
    }

    // Error-surface injection at StdioProcessHost.start, not a native/schema6 ledger reproduction.
    @Test(timeout = 45_000) fun nextLaunchFailurePreservesPreviouslySyncedFilesAndStatus() = fixture { f ->
        f.turn { File(working, "completed.txt").writeText("completed work") }
        assertEquals(WorkspaceAgentSyncPhase.SYNCED, f.host.workspaceSyncState.value.phase)
        val prompts = f.peer.promptCount
        f.peer.close()
        val failure = IllegalStateException("CLI cleanup from an earlier runtime is unconfirmed; existing writers and retained data require recovery before another launch")
        f.startFailure.set(failure)
        try {
            f.start()
            fail("A rejected launch must not appear successful")
        } catch (actual: IllegalStateException) {
            // Coroutine stacktrace recovery may copy an exception across suspension.
            assertEquals(failure.javaClass, actual.javaClass)
            assertEquals("Keep the launch error visible", failure.message, actual.message)
        }
        assertEquals("No new prompt was delivered", prompts, f.peer.promptCount)
        assertEquals("completed work", File(f.source, "completed.txt").readText())
        assertEquals(WorkspaceAgentSyncPhase.SYNCED, f.host.workspaceSyncState.value.phase)
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

    @Test(timeout = 45_000) fun sessionReviewGrantDoesNotAuthorizeTheSameProposalAgainstNewCanonicalContent() = fixture(
        effect = PermEffect.ASK, initial = { File(it, "file.txt").writeText("original") },
    ) { f ->
        val first = grantFirstTurn(f) { File(working, "file.txt").writeText("CLI proposal") }
        assertEquals(listOf("file.txt"), first.second.changedFiles)
        File(f.source, "file.txt").writeText("new user content")
        val work = async { f.start() }
        val prompt = f.peer.prompt()
        assertEquals("new user content", File(f.working, "file.txt").readText())
        File(f.working, "file.txt").writeText("CLI proposal")
        f.peer.finish(prompt)
        val second = awaitReviewBeforeCompletion(f, work)
        assertNotEquals(first.first.sessionGrantResource, second.sessionGrantResource)
        assertTrue(second.detail.contains("-new user content"))
        assertTrue(f.approvals.resolve(second.id, ApprovalResolution.DENY))
        assertTrue(work.await().changedFiles.isEmpty())
        assertEquals("new user content", File(f.source, "file.txt").readText())
        assertEquals("CLI proposal", File(f.working, "file.txt").readText())
    }

    @Test(timeout = 45_000) fun sessionDeletionGrantDoesNotAuthorizeDeletingARecreatedDifferentFile() = fixture(
        effect = PermEffect.ASK, initial = { File(it, "file.txt").writeText("original") },
    ) { f ->
        val first = grantFirstTurn(f) { check(File(working, "file.txt").delete()) }
        assertEquals(listOf("file.txt"), first.second.changedFiles)
        assertFalse(File(f.source, "file.txt").exists())
        File(f.source, "file.txt").writeText("recreated user work")
        val work = async { f.start() }
        val prompt = f.peer.prompt()
        assertEquals("recreated user work", File(f.working, "file.txt").readText())
        assertTrue(File(f.working, "file.txt").delete())
        f.peer.finish(prompt)
        val second = awaitReviewBeforeCompletion(f, work)
        assertNotEquals(first.first.sessionGrantResource, second.sessionGrantResource)
        assertTrue(f.approvals.resolve(second.id, ApprovalResolution.DENY))
        assertEquals(listOf("file.txt"), work.await().conflicts)
        assertEquals("recreated user work", File(f.source, "file.txt").readText())
        assertFalse(File(f.working, "file.txt").exists())
    }

    @Test(timeout = 45_000) fun sessionReviewGrantIncludesCanonicalExecutableState() = fixture(
        effect = PermEffect.ASK, initial = { File(it, "file.txt").apply { writeText("original"); check(setExecutable(false, false)) } },
    ) { f ->
        val first = grantFirstTurn(f) { File(working, "file.txt").apply { writeText("CLI proposal"); check(setExecutable(false, false)) } }
        val target = File(f.source, "file.txt")
        target.writeText("original"); assertTrue(target.setExecutable(true, true))
        val work = async { f.start() }
        val prompt = f.peer.prompt()
        assertTrue(Files.isExecutable(File(f.working, "file.txt").toPath()))
        File(f.working, "file.txt").apply { writeText("CLI proposal"); check(setExecutable(false, false)) }
        f.peer.finish(prompt)
        val second = awaitReviewBeforeCompletion(f, work)
        assertNotEquals(first.first.sessionGrantResource, second.sessionGrantResource)
        assertTrue(f.approvals.resolve(second.id, ApprovalResolution.DENY))
        assertTrue(work.await().changedFiles.isEmpty())
        assertEquals("original", target.readText())
        assertTrue(Files.isExecutable(target.toPath()))
    }

    @Test(timeout = 45_000) fun unchangedPendingReviewCanReuseItsExactSessionGrant() {
        var blocked = true
        fixture(effect = PermEffect.ASK, initial = { File(it, "file.txt").writeText("original") }, beforeMutation = {
            if (blocked && it is FileRepository.Mutation.Write) throw WorkspaceDocumentConflictException(listOf(it.path))
        }) { f ->
            val first = grantFirstTurn(f) { File(working, "file.txt").writeText("CLI proposal") }
            assertEquals(listOf("file.txt"), first.second.conflicts)
            assertEquals("original", File(f.source, "file.txt").readText())
            blocked = false
            val result = withTimeout(5_000) { f.turn {} }
            assertEquals(listOf("file.txt"), result.changedFiles)
            assertNull(f.approvals.pending.value)
            assertEquals("CLI proposal", File(f.source, "file.txt").readText())
        }
    }

    @Test(timeout = 45_000) fun changedProposalStillRequiresANewSessionDecision() = fixture(
        effect = PermEffect.ASK, initial = { File(it, "file.txt").writeText("original") },
    ) { f ->
        val first = grantFirstTurn(f) { File(working, "file.txt").writeText("first proposal") }
        val work = async { f.start() }
        val prompt = f.peer.prompt()
        File(f.working, "file.txt").writeText("different proposal")
        f.peer.finish(prompt)
        val second = awaitReviewBeforeCompletion(f, work)
        assertNotEquals(first.first.sessionGrantResource, second.sessionGrantResource)
        assertTrue(f.approvals.resolve(second.id, ApprovalResolution.ALLOW_ONCE))
        assertEquals(listOf("file.txt"), work.await().changedFiles)
        assertEquals("different proposal", File(f.source, "file.txt").readText())
    }

    @Test(timeout = 45_000) fun allowOnceDoesNotBecomeASessionGrantForAnUnchangedRetry() {
        var blocked = true
        fixture(effect = PermEffect.ASK, initial = { File(it, "file.txt").writeText("original") }, beforeMutation = {
            if (blocked && it is FileRepository.Mutation.Write) throw WorkspaceDocumentConflictException(listOf(it.path))
        }) { f ->
            val first = grantFirstTurn(f, ApprovalResolution.ALLOW_ONCE) { File(working, "file.txt").writeText("CLI proposal") }
            assertEquals(listOf("file.txt"), first.second.conflicts)
            blocked = false
            val work = async { f.start() }
            val prompt = f.peer.prompt(); f.peer.finish(prompt)
            val second = awaitReviewBeforeCompletion(f, work)
            assertEquals(first.first.sessionGrantResource, second.sessionGrantResource)
            assertTrue(f.approvals.resolve(second.id, ApprovalResolution.ALLOW_ONCE))
            assertEquals(listOf("file.txt"), work.await().changedFiles)
        }
    }

    private suspend fun CoroutineScope.reviewPathChange(
        f: Fixture, path: String, proposal: String?, mutateCanonical: (Fixture) -> Unit,
    ): WorkspaceAgentTurnResult {
        val work = async { f.start() }
        val prompt = f.peer.prompt()
        val remote = File(f.working, path)
        if (proposal == null) check(remote.delete()) else remote.writeText(proposal)
        f.peer.finish(prompt)
        val approval = awaitReviewBeforeCompletion(f, work)
        mutateCanonical(f)
        assertTrue(f.approvals.resolve(approval.id, ApprovalResolution.ALLOW_ONCE))
        val result = work.await()
        assertTrue(result.changedFiles.isEmpty())
        assertTrue(result.conflicts.contains(path))
        assertEquals(WorkspaceAgentSyncPhase.PENDING, f.host.workspaceSyncState.value.phase)
        if (proposal == null) assertFalse(remote.exists()) else assertEquals(proposal, remote.readText())
        return result
    }

    @Test(timeout = 45_000) fun reviewCannotOverwriteACanonicalFileCreatedWhileCreationApprovalWasOpen() = fixture(
        effect = PermEffect.ASK,
    ) { f ->
        reviewPathChange(f, "file.txt", "CLI creation") { File(it.source, "file.txt").writeText("new user creation") }
        assertEquals("new user creation", File(f.source, "file.txt").readText())
    }

    @Test(timeout = 45_000) fun reviewCannotResurrectACanonicalFileDeletedWhileWriteApprovalWasOpen() = fixture(
        effect = PermEffect.ASK, initial = { File(it, "file.txt").writeText("original") },
    ) { f ->
        reviewPathChange(f, "file.txt", "CLI proposal") { Files.delete(File(it.source, "file.txt").toPath()) }
        assertFalse(File(f.source, "file.txt").exists())
    }

    @Test(timeout = 45_000) fun reviewCannotDeleteRenamedCanonicalWorkAndAConvergedRetryClearsTheNotice() = fixture(
        effect = PermEffect.ASK, initial = { File(it, "file.txt").writeText("original") },
    ) { f ->
        reviewPathChange(f, "file.txt", null) {
            Files.move(File(it.source, "file.txt").toPath(), File(it.source, "renamed.txt").toPath())
        }
        assertFalse(File(f.source, "file.txt").exists())
        assertEquals("original", File(f.source, "renamed.txt").readText())
        val retry = f.host.reviewWorkspaceChanges("opencode")
        assertTrue(retry.changedFiles.isEmpty())
        assertTrue(retry.pendingFiles.isEmpty())
        assertEquals(WorkspaceAgentSyncPhase.SYNCED, f.host.workspaceSyncState.value.phase)
        assertNull(f.approvals.pending.value)
        assertEquals("original", File(f.working, "renamed.txt").readText())
    }

    private fun replaceCanonicalFileWithDirectory(f: Fixture) {
        val target = File(f.source, "file.txt")
        Files.delete(target.toPath()); Files.createDirectory(target.toPath())
        File(target, "child.txt").writeText("new directory work")
    }

    @Test(timeout = 45_000) fun reviewWriteCannotReplaceACanonicalDirectoryCreatedDuringApproval() = fixture(
        effect = PermEffect.ASK, initial = { File(it, "file.txt").writeText("original") },
    ) { f ->
        reviewPathChange(f, "file.txt", "CLI proposal", ::replaceCanonicalFileWithDirectory)
        assertEquals("new directory work", File(f.source, "file.txt/child.txt").readText())
    }

    @Test(timeout = 45_000) fun reviewDeletionCannotRecursivelyDeleteACanonicalDirectoryCreatedDuringApproval() = fixture(
        effect = PermEffect.ASK, initial = { File(it, "file.txt").writeText("original") },
    ) { f ->
        reviewPathChange(f, "file.txt", null, ::replaceCanonicalFileWithDirectory)
        assertEquals("new directory work", File(f.source, "file.txt/child.txt").readText())
    }

    @Test(timeout = 45_000) fun reviewCannotPublishThroughAReplacedCanonicalParentWithIdenticalFileBytes() = fixture(
        effect = PermEffect.ASK,
        initial = { File(it, "nested/file.txt").apply { parentFile.mkdirs(); writeText("original") } },
    ) { f ->
        reviewPathChange(f, "nested/file.txt", "CLI proposal") {
            Files.move(File(it.source, "nested").toPath(), File(it.source, "retained").toPath())
            File(it.source, "nested/file.txt").apply { parentFile.mkdirs(); writeText("original") }
        }
        assertEquals("original", File(f.source, "nested/file.txt").readText())
        assertEquals("original", File(f.source, "retained/file.txt").readText())
    }

    @Test(timeout = 45_000) fun acceptedCliFilesReachTheActualEditorAuthorityAndExplorerRepository() = fixture(
        initial = { File(it, "file.txt").writeText("original") },
        newFilePublisher = NewProjectFilePublisher.native(),
    ) { f ->
        val editor = EditorWorkspaceState()
        val document = editor.document("file.txt").also { it.ensureLoaded(f.files) }
        val binding = f.documents.bind(EditorWorkspaceDocumentAuthority(editor, Dispatchers.Unconfined, f.files))
        try {
            val revision = f.documents.persistedRevision.value
            val result = f.turn {
                File(working, "file.txt").writeText("reviewed CLI content")
                File(working, "src/new.kt").apply { parentFile.mkdirs(); writeText("fun main() = Unit") }
            }
            assertTrue(result.conflicts.isEmpty())
            assertEquals(setOf("file.txt", "src/new.kt"), result.changedFiles.toSet())
            assertEquals("reviewed CLI content", document.content)
            assertFalse(document.dirty)
            assertTrue(f.documents.persistedRevision.value > revision)
            assertTrue(f.files.listFiles().any { it.path == "src" && it.isDir })
            assertEquals(listOf("src/new.kt"), f.files.listFiles("src").map { it.path })
            assertTrue(f.files.retainedWork.value.notices.isEmpty())
            assertEquals("fun main() = Unit", f.files.readEditorText("src/new.kt"))
            assertEquals("reviewed CLI content", File(f.working, "file.txt").readText())
        } finally { binding.close() }
    }

    @Test(timeout = 45_000) fun acceptCannotOverwriteAnActualEditorBufferDirtiedDuringReviewAndRetryNeedsNoNewTurn() = fixture(
        effect = PermEffect.ASK, initial = { File(it, "file.txt").writeText("original") },
    ) { f ->
        val editor = EditorWorkspaceState()
        val document = editor.document("file.txt").also { it.ensureLoaded(f.files) }
        val binding = f.documents.bind(EditorWorkspaceDocumentAuthority(editor, Dispatchers.Unconfined, f.files))
        try {
            val turn = async { f.start() }
            val request = f.peer.prompt()
            File(f.working, "file.txt").writeText("CLI proposal")
            f.peer.finish(request)
            val first = withTimeout(5_000) { f.approvals.pending.filterNotNull().first() }
            document.capture("unsaved user edit")
            assertTrue(f.approvals.resolve(first.id, ApprovalResolution.ALLOW_ONCE))
            assertEquals(listOf("file.txt"), turn.await().conflicts)
            assertEquals("unsaved user edit", document.content)
            assertTrue(document.dirty)
            assertEquals("original", File(f.source, "file.txt").readText())
            assertEquals("CLI proposal", File(f.working, "file.txt").readText())
            document.capture("original")
            assertFalse(document.dirty)
            val retry = async { f.host.reviewWorkspaceChanges("opencode") }
            val second = withTimeout(5_000) { f.approvals.pending.filterNotNull().first() }
            assertTrue(f.approvals.resolve(second.id, ApprovalResolution.ALLOW_ONCE))
            assertEquals(listOf("file.txt"), retry.await().changedFiles)
            assertEquals("CLI proposal", document.content)
            assertFalse(document.dirty)
            assertEquals(1, f.peer.promptCount)
        } finally { binding.close() }
    }

    @Test(timeout = 45_000) fun acceptedCliDeletionPreservesAnActualDirtyBufferUntilASeparateReviewedRetry() = fixture(
        effect = PermEffect.ASK, initial = { File(it, "file.txt").writeText("original") },
    ) { f ->
        val editor = EditorWorkspaceState()
        val document = editor.document("file.txt").also { it.ensureLoaded(f.files) }
        val binding = f.documents.bind(EditorWorkspaceDocumentAuthority(editor, Dispatchers.Unconfined, f.files))
        try {
            val turn = async { f.start() }
            val request = f.peer.prompt()
            assertTrue(File(f.working, "file.txt").delete())
            f.peer.finish(request)
            val first = withTimeout(5_000) { f.approvals.pending.filterNotNull().first() }
            document.capture("unsaved deletion conflict")
            assertTrue(f.approvals.resolve(first.id, ApprovalResolution.ALLOW_ONCE))
            assertEquals(listOf("file.txt"), turn.await().conflicts)
            assertSame(document, editor.peek("file.txt"))
            assertEquals("unsaved deletion conflict", document.content)
            assertTrue(document.dirty)
            assertEquals("original", File(f.source, "file.txt").readText())
            assertFalse(File(f.working, "file.txt").exists())
            document.capture("original")
            val retry = async { f.host.reviewWorkspaceChanges("opencode") }
            val second = withTimeout(5_000) { f.approvals.pending.filterNotNull().first() }
            assertTrue(f.approvals.resolve(second.id, ApprovalResolution.ALLOW_ONCE))
            assertEquals(listOf("file.txt"), retry.await().changedFiles)
            assertNull(editor.peek("file.txt"))
            assertFalse(f.files.listFiles().any { it.path == "file.txt" })
            assertFalse(File(f.working, "file.txt").exists())
            assertEquals(1, f.peer.promptCount)
        } finally { binding.close() }
    }

    @Test(timeout = 45_000) fun deniedNewNestedFileCanBeReviewedAndAcceptedWithoutAnotherModelTurn() = fixture(effect = PermEffect.ASK) { f ->
        val turn = async { f.start() }
        val request = f.peer.prompt()
        File(f.working, "ok/apex-drift.html").apply { parentFile.mkdirs(); writeText("<h1>Game ready</h1>") }
        f.peer.finish(request)
        val firstApproval = withTimeout(5_000) { f.approvals.pending.filterNotNull().first() }
        f.approvals.resolve(firstApproval.id, ApprovalResolution.DENY)
        assertEquals(listOf("ok/apex-drift.html"), turn.await().conflicts)
        assertFalse(File(f.source, "ok/apex-drift.html").exists())
        assertEquals(WorkspaceAgentSyncPhase.PENDING, f.host.workspaceSyncState.value.phase)
        val retry = async { f.host.reviewWorkspaceChanges("opencode") }
        val approval = withTimeout(5_000) { f.approvals.pending.filterNotNull().first() }
        assertTrue(approval.detail.contains("Destination: ${f.source.absolutePath}"))
        assertTrue(approval.detail.contains("+<h1>Game ready</h1>"))
        assertTrue(f.approvals.resolve(approval.id, ApprovalResolution.ALLOW_ONCE))
        val result = retry.await()
        assertEquals(listOf("ok/apex-drift.html"), result.changedFiles)
        assertTrue(result.pendingFiles.isEmpty())
        assertEquals("<h1>Game ready</h1>", File(f.source, "ok/apex-drift.html").readText())
        assertEquals(1, f.peer.promptCount)
        assertEquals(WorkspaceAgentSyncPhase.SYNCED, f.host.workspaceSyncState.value.phase)
    }

    @Test(timeout = 45_000) fun cliChangesDuringApprovalRemainPendingWithoutUnreviewedPublication() = fixture(
        effect = PermEffect.ASK, initial = { File(it, "removed.txt").writeText("retain source until review") },
    ) { f ->
        val turn = async { f.start() }
        val request = f.peer.prompt()
        File(f.working, "approved.txt").writeText("reviewed")
        f.peer.finish(request)
        val approval = withTimeout(5_000) { f.approvals.pending.filterNotNull().first() }
        File(f.working, "late.txt").writeText("unreviewed CLI output")
        assertTrue(File(f.working, "removed.txt").delete())
        assertTrue(f.approvals.resolve(approval.id, ApprovalResolution.ALLOW_ONCE))
        val result = turn.await()
        assertEquals(listOf("approved.txt"), result.changedFiles)
        assertEquals("reviewed", File(f.source, "approved.txt").readText())
        assertFalse(File(f.source, "late.txt").exists())
        assertEquals("unreviewed CLI output", File(f.working, "late.txt").readText())
        assertEquals("retain source until review", File(f.source, "removed.txt").readText())
        assertFalse(File(f.working, "removed.txt").exists())
        assertEquals(setOf("late.txt", "removed.txt"), result.conflicts.toSet())
        assertEquals(WorkspaceAgentSyncPhase.PENDING, f.host.workspaceSyncState.value.phase)
        assertEquals(result.conflicts.toSet(), f.host.workspaceSyncState.value.pendingPaths.toSet())
        assertEquals(2, f.host.workspaceSyncState.value.pendingCount)
    }

    @Test(timeout = 45_000) fun cliFileCreatedDuringDeniedApprovalIsIncludedInPendingState() = fixture(effect = PermEffect.ASK) { f ->
        val turn = async { f.start() }
        val request = f.peer.prompt()
        File(f.working, "first.txt").writeText("first proposal")
        f.peer.finish(request)
        val approval = withTimeout(5_000) { f.approvals.pending.filterNotNull().first() }
        File(f.working, "late.txt").writeText("later proposal")
        assertTrue(f.approvals.resolve(approval.id, ApprovalResolution.DENY))
        val result = turn.await()
        assertTrue(result.changedFiles.isEmpty())
        assertFalse(File(f.source, "first.txt").exists())
        assertFalse(File(f.source, "late.txt").exists())
        assertEquals("first proposal", File(f.working, "first.txt").readText())
        assertEquals("later proposal", File(f.working, "late.txt").readText())
        assertEquals(setOf("first.txt", "late.txt"), result.conflicts.toSet())
        assertEquals(WorkspaceAgentSyncPhase.PENDING, f.host.workspaceSyncState.value.phase)
        assertEquals(2, f.host.workspaceSyncState.value.pendingCount)
    }

    @Test(timeout = 45_000) fun cliFileCreatedAfterPlanInventoryIsIncludedInPendingState() = fixture { f ->
        var created = false
        f.io.snapshotTransform = { inventory ->
            if (!created) { created = true; File(f.working, "late.txt").writeText("later plan output") }
            inventory
        }
        val result = f.turn(WorkspaceAgentMode.PLAN) { File(working, "first.txt").writeText("first plan output") }
        assertTrue(result.changedFiles.isEmpty())
        assertFalse(File(f.source, "first.txt").exists())
        assertFalse(File(f.source, "late.txt").exists())
        assertEquals("first plan output", File(f.working, "first.txt").readText())
        assertEquals("later plan output", File(f.working, "late.txt").readText())
        assertEquals(setOf("first.txt", "late.txt"), result.conflicts.toSet())
        assertEquals(WorkspaceAgentSyncPhase.PENDING, f.host.workspaceSyncState.value.phase)
    }

    @Test(timeout = 45_000) fun cliRewriteAfterTransferRemainsPending() {
        var rewrite: (() -> Unit)? = null
        fixture(initial = { File(it, "file.txt").writeText("original") }, beforeMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Write) rewrite?.invoke()
        }) { f ->
            rewrite = { File(f.working, "file.txt").writeText("new CLI version after transfer") }
            val result = f.turn { File(working, "file.txt").writeText("reviewed version") }
            assertEquals(listOf("file.txt"), result.changedFiles)
            assertEquals("reviewed version", File(f.source, "file.txt").readText())
            assertEquals("new CLI version after transfer", File(f.working, "file.txt").readText())
            assertEquals(listOf("file.txt"), result.conflicts)
            assertEquals(WorkspaceAgentSyncPhase.PENDING, f.host.workspaceSyncState.value.phase)
        }
    }

    @Test(timeout = 45_000) fun finalInventoryFailureCannotReportSynchronized() = fixture { f ->
        var snapshots = 0
        f.io.beforeSnapshotEntries = {
            snapshots++
            if (snapshots == 2) throw java.io.IOException("post-publication inventory unavailable")
        }
        val result = f.turn { File(working, "file.txt").writeText("approved output") }
        assertEquals("approved output", File(f.source, "file.txt").readText())
        assertEquals("approved output", File(f.working, "file.txt").readText())
        assertEquals(listOf("file.txt"), result.changedFiles)
        assertTrue(f.host.workspaceSyncState.value.notice.orEmpty().contains("post-publication inventory unavailable"))
        assertEquals(WorkspaceAgentSyncPhase.INTERRUPTED, f.host.workspaceSyncState.value.phase)
    }

    @Test(timeout = 45_000) fun recreatedCliFileDuringApprovalCannotAuthorizeCanonicalDeletion() = fixture(
        effect = PermEffect.ASK, initial = { File(it, "file.txt").writeText("original") },
    ) { f ->
        val turn = async { f.start() }
        val request = f.peer.prompt()
        assertTrue(File(f.working, "file.txt").delete())
        f.peer.finish(request)
        val approval = withTimeout(5_000) { f.approvals.pending.filterNotNull().first() }
        File(f.working, "file.txt").writeText("CLI recreated it")
        f.approvals.resolve(approval.id, ApprovalResolution.ALLOW_ONCE)
        val result = turn.await()
        assertTrue(result.changedFiles.isEmpty())
        assertEquals(listOf("file.txt"), result.conflicts)
        assertEquals("original", File(f.source, "file.txt").readText())
        assertEquals("CLI recreated it", File(f.working, "file.txt").readText())
    }

    @Test(timeout = 45_000) fun acceptedWriteCannotOverwriteCanonicalEditMadeWhileApprovalWasOpen() = fixture(
        effect = PermEffect.ASK, initial = { File(it, "file.txt").writeText("original") },
    ) { f ->
        val turn = async { f.start() }
        val request = f.peer.prompt()
        File(f.working, "file.txt").writeText("CLI proposal")
        f.peer.finish(request)
        val approval = withTimeout(5_000) { f.approvals.pending.filterNotNull().first() }
        File(f.source, "file.txt").writeText("user edit after preview")
        f.approvals.resolve(approval.id, ApprovalResolution.ALLOW_ONCE)
        val result = turn.await()
        assertEquals(listOf("file.txt"), result.conflicts)
        assertEquals("user edit after preview", File(f.source, "file.txt").readText())
        assertEquals("CLI proposal", File(f.working, "file.txt").readText())
    }

    @Test(timeout = 45_000) fun unsavedEditorConflictCanBeRetriedWithoutRegeneratingCliWork() {
        var dirty = true
        fixture(initial = { File(it, "file.txt").writeText("original") }, beforeMutation = {
            if (dirty && it is FileRepository.Mutation.Write) throw WorkspaceDocumentConflictException(listOf(it.path))
        }) { f ->
            val result = f.turn { File(working, "file.txt").writeText("proposed") }
            assertEquals(listOf("file.txt"), result.conflicts)
            assertEquals("original", File(f.source, "file.txt").readText())
            dirty = false
            val retry = f.host.reviewWorkspaceChanges("opencode")
            assertEquals(listOf("file.txt"), retry.changedFiles)
            assertEquals("proposed", File(f.source, "file.txt").readText())
            assertEquals(1, f.peer.promptCount)
        }
    }

    @Test(timeout = 45_000) fun runningIsolatedTurnReportsBothRootsAndPendingPublication() = fixture { f ->
        val turn = async { f.start() }
        val request = f.peer.prompt()
        val state = f.host.workspaceSyncState.value
        assertEquals(WorkspaceAgentSyncPhase.RUNNING, state.phase)
        assertEquals(f.source.absolutePath, state.sourceRoot)
        assertEquals(f.working.absolutePath, state.executionRoot)
        f.peer.finish(request)
        turn.await()
    }

    @Test(timeout = 45_000) fun newExecutableScriptAndChmodOnlyChangesArePublishedAndConverge() = fixture { f ->
        val created = f.turn {
            File(working, "start.sh").apply { writeText("#!/bin/sh\nprintf ready\\n\n"); assertTrue(setExecutable(true, true)) }
        }
        assertEquals(listOf("start.sh"), created.changedFiles)
        assertTrue(File(f.source, "start.sh").canExecute())
        assertEquals(493, f.io.baselineEntries()!!["start.sh"]!!.mode)
        val digest = hash(File(f.source, "start.sh"))
        val disabled = f.turn { assertTrue(File(working, "start.sh").setExecutable(false, true)) }
        assertEquals(listOf("start.sh"), disabled.changedFiles)
        assertFalse(File(f.source, "start.sh").canExecute())
        assertEquals(digest, hash(File(f.source, "start.sh")))
        assertEquals(420, f.io.baselineEntries()!!["start.sh"]!!.mode)
        val enabled = f.turn { assertTrue(File(working, "start.sh").setExecutable(true, true)) }
        assertEquals(listOf("start.sh"), enabled.changedFiles)
        assertTrue(File(f.source, "start.sh").canExecute())
        assertTrue(f.turn {}.changedFiles.isEmpty())
    }

    @Test(timeout = 45_000) fun executablePermissionChangedDuringApprovalIsNotAppliedUnreviewed() = fixture(
        effect = PermEffect.ASK, initial = { File(it, "start.sh").writeText("#!/bin/sh\ntrue\n") },
    ) { f ->
        val turn = async { f.start() }
        val request = f.peer.prompt()
        assertTrue(File(f.working, "start.sh").setExecutable(true, true))
        f.peer.finish(request)
        val approval = withTimeout(5_000) { f.approvals.pending.filterNotNull().first() }
        assertTrue(approval.detail.contains("executable=yes"))
        assertTrue(File(f.working, "start.sh").setExecutable(false, true))
        f.approvals.resolve(approval.id, ApprovalResolution.ALLOW_ONCE)
        val result = turn.await()
        assertTrue(result.changedFiles.isEmpty())
        assertEquals(listOf("start.sh"), result.conflicts)
        assertFalse(File(f.source, "start.sh").canExecute())
    }

    @Test(timeout = 45_000) fun vanishedSourceInvalidatesPreviousSyncStatusBeforeAnotherPrompt() = fixture(
        initial = { File(it, "file.txt").writeText("original source") },
    ) { f ->
        f.turn {}
        assertEquals(WorkspaceAgentSyncPhase.SYNCED, f.host.workspaceSyncState.value.phase)
        File(f.working, "pending.txt").writeText("unpublished CLI output")
        val retained = File(f.source.parentFile, "retained-source")
        Files.move(f.source.toPath(), retained.toPath())
        try { f.start(); fail("Unavailable source was used") }
        catch (_: IllegalStateException) { }
        assertEquals(WorkspaceAgentSyncPhase.INTERRUPTED, f.host.workspaceSyncState.value.phase)
        assertEquals(1, f.peer.promptCount)
        assertFalse(f.source.exists())
        assertEquals("original source", File(retained, "file.txt").readText())
        assertEquals("unpublished CLI output", File(f.working, "pending.txt").readText())
    }

    @Test(timeout = 45_000) fun binaryRootAssetsAndNestedBuildApkPublishExactBytes() = fixture { f ->
        val paths = listOf("kucing.glb", "preview.png", "lumen-debug.apk", "app/build/outputs/apk/debug/app-debug.apk")
        val result = f.turn {
            writePattern(File(working, paths[0]), 332 * 1024, 0x67)
            writePattern(File(working, paths[1]), 120 * 1024, 0x89)
            writePattern(File(working, paths[2]), 17 * 1024 * 1024, 0x50)
            File(working, paths[3]).apply { parentFile.mkdirs(); File(working, paths[2]).copyTo(this) }
        }
        assertEquals(paths.toSet(), result.changedFiles.toSet())
        assertTrue(result.conflicts.toString(), result.conflicts.isEmpty())
        paths.forEach { path ->
            assertEquals(path, File(f.working, path).length(), File(f.source, path).length())
            assertEquals(path, hash(File(f.working, path)), hash(File(f.source, path)))
        }
        assertEquals(paths.size, f.io.pulls)
        assertTrue(f.turn {}.changedFiles.isEmpty())
        assertEquals("Converged files must not be pulled again", paths.size, f.io.pulls)
    }

    @Test(timeout = 45_000) fun existingBinaryLargerThanEditorLimitCanBeUpdated() = fixture(
        initial = { writePattern(File(it, "scene.glb"), 21 * 1024 * 1024, 0x21) },
    ) { f ->
        val old = hash(File(f.source, "scene.glb"))
        val result = f.turn { writePattern(File(working, "scene.glb"), 21 * 1024 * 1024, 0x42) }
        assertEquals(listOf("scene.glb"), result.changedFiles)
        assertTrue(result.conflicts.isEmpty())
        assertNotEquals(old, hash(File(f.source, "scene.glb")))
        assertEquals(hash(File(f.working, "scene.glb")), hash(File(f.source, "scene.glb")))
    }

    @Test(timeout = 45_000) fun divergentCanonicalAndPendingCliBytesSurviveFollowingTurn() = fixture(
        initial = { File(it, "scene.glb").writeBytes(byteArrayOf(0, 1, 2)) },
    ) { f ->
        val first = f.turn {
            File(working, "scene.glb").writeBytes(byteArrayOf(0, 3, 4))
            File(source, "scene.glb").writeBytes(byteArrayOf(0, 5, 6))
        }
        assertTrue(first.changedFiles.isEmpty())
        assertTrue("scene.glb" in first.conflicts)
        val next = f.turn {}
        assertTrue("scene.glb" in next.conflicts)
        assertArrayEquals(byteArrayOf(0, 3, 4), File(f.working, "scene.glb").readBytes())
        assertArrayEquals(byteArrayOf(0, 5, 6), File(f.source, "scene.glb").readBytes())
        assertEquals(0, f.io.pulls)
    }

    @Test(timeout = 45_000) fun planModeRetainsBinaryForLaterApprovedEditTurn() = fixture { f ->
        val result = f.turn(WorkspaceAgentMode.PLAN) { writePattern(File(working, "planned.apk"), 3 * 1024 * 1024, 7) }
        assertTrue(result.changedFiles.isEmpty())
        assertTrue("planned.apk" in result.conflicts)
        assertFalse(File(f.source, "planned.apk").exists())
        val digest = hash(File(f.working, "planned.apk"))
        val approved = f.turn {}
        assertEquals(listOf("planned.apk"), approved.changedFiles)
        assertEquals(digest, hash(File(f.source, "planned.apk")))
    }

    @Test(timeout = 45_000) fun explicitDeniedApprovalRetainsUnpublishedBinary() = fixture(effect = PermEffect.ASK) { f ->
        val work = async { f.start() }
        val request = f.peer.prompt()
        writePattern(File(f.working, "denied.apk"), 3 * 1024 * 1024, 8)
        val digest = hash(File(f.working, "denied.apk"))
        f.peer.finish(request)
        val approval = withTimeout(5_000) { f.approvals.pending.filterNotNull().first() }
        assertEquals("agent_workspace_reconcile", approval.kind)
        assertTrue(f.approvals.resolve(approval.id, ApprovalResolution.DENY))
        val result = work.await()
        assertTrue(result.changedFiles.isEmpty())
        assertTrue("denied.apk" in result.conflicts)
        assertFalse(File(f.source, "denied.apk").exists())
        f.io.refresh()
        assertEquals(digest, hash(File(f.working, "denied.apk")))
        assertEquals(0, f.io.pulls)
    }

    @Test(timeout = 45_000) fun forgedInventoryHashDoesNotPublishOrDiscardActualOutput() = fixture { f ->
        f.io.snapshotTransform = { values -> values.mapValues { "a".repeat(64) } }
        val result = f.turn { File(working, "asset.bin").writeBytes(byteArrayOf(0, -1, 12)) }
        assertTrue(result.changedFiles.isEmpty())
        assertTrue("asset.bin" in result.conflicts)
        assertFalse(File(f.source, "asset.bin").exists())
        assertArrayEquals(byteArrayOf(0, -1, 12), File(f.working, "asset.bin").readBytes())
        f.io.snapshotTransform = { it }
        assertEquals(listOf("asset.bin"), f.turn {}.changedFiles)
    }

    @Test(timeout = 45_000) fun cliChangeBetweenInventoryAndPullIsRetainedForNextTurn() = fixture { f ->
        var mutated = false
        f.io.beforePull = { path ->
            if (!mutated) { mutated = true; File(path).writeBytes(byteArrayOf(0, -1, 99)) }
        }
        val result = f.turn { File(working, "asset.bin").writeBytes(byteArrayOf(0, -1, 12)) }
        assertTrue(result.changedFiles.isEmpty())
        assertTrue("asset.bin" in result.conflicts)
        assertFalse(File(f.source, "asset.bin").exists())
        assertArrayEquals(byteArrayOf(0, -1, 99), File(f.working, "asset.bin").readBytes())
        assertEquals(listOf("asset.bin"), f.turn {}.changedFiles)
        assertArrayEquals(byteArrayOf(0, -1, 99), File(f.source, "asset.bin").readBytes())
    }

    @Test(timeout = 45_000) fun cancellationDuringTransferRetainsCliOutputAndCanonicalFile() = fixture(
        initial = { File(it, "asset.bin").writeBytes(byteArrayOf(0, 1, 2)) },
    ) { f ->
        val pulling = CompletableDeferred<Unit>()
        f.io.beforePull = { pulling.complete(Unit); awaitCancellation() }
        val work = async { f.start() }
        val request = f.peer.prompt()
        File(f.working, "asset.bin").writeBytes(byteArrayOf(0, 3, 4))
        f.peer.finish(request)
        withTimeout(5_000) { pulling.await() }
        work.cancelAndJoin()
        assertEquals(WorkspaceAgentSyncPhase.INTERRUPTED, f.host.workspaceSyncState.value.phase)
        assertArrayEquals(byteArrayOf(0, 1, 2), File(f.source, "asset.bin").readBytes())
        assertArrayEquals(byteArrayOf(0, 3, 4), File(f.working, "asset.bin").readBytes())
        f.io.beforePull = {}
        assertEquals(listOf("asset.bin"), f.turn {}.changedFiles)
        assertArrayEquals(byteArrayOf(0, 3, 4), File(f.source, "asset.bin").readBytes())
    }

    @Test(timeout = 45_000) fun partialReviewKeepsADirtyBufferAndRetriesOnlyItsFileWithoutAnotherModelTurn() = fixture(
        effect = PermEffect.ASK,
        initial = { root -> listOf("a.txt", "b.txt").forEach { File(root, it).writeText("original $it") } },
    ) { f ->
        val editor = EditorWorkspaceState()
        val a = editor.document("a.txt").also { it.ensureLoaded(f.files) }
        val b = editor.document("b.txt").also { it.ensureLoaded(f.files) }
        val binding = f.documents.bind(EditorWorkspaceDocumentAuthority(editor, Dispatchers.Unconfined, f.files))
        try {
            val work = async { f.start() }
            val prompt = f.peer.prompt()
            listOf("a.txt", "b.txt").forEach { File(f.working, it).writeText("CLI $it") }
            f.peer.finish(prompt)
            val approval = awaitReviewBeforeCompletion(f, work)
            b.capture("unsaved user work")
            assertTrue(f.approvals.resolve(approval.id, ApprovalResolution.ALLOW_ONCE))
            val partial = work.await()
            assertEquals(listOf("a.txt"), partial.changedFiles)
            assertEquals(listOf("b.txt"), partial.conflicts)
            assertEquals("CLI a.txt", a.content)
            assertEquals("unsaved user work", b.content)
            assertTrue(b.dirty)
            assertEquals("original b.txt", File(f.source, "b.txt").readText())
            assertEquals("CLI b.txt", File(f.working, "b.txt").readText())
            assertEquals(hash(File(f.source, "a.txt")), f.io.baselineEntries()!!.getValue("a.txt").sha256)
            assertEquals(hash(File(f.source, "b.txt")), f.io.baselineEntries()!!.getValue("b.txt").sha256)
            b.capture("original b.txt")
            val retry = async { f.host.reviewWorkspaceChanges("opencode") }
            val second = withTimeout(5_000) { f.approvals.pending.filterNotNull().first() }
            assertTrue(second.detail.contains("b.txt"))
            assertFalse(second.detail.contains("a.txt"))
            assertTrue(f.approvals.resolve(second.id, ApprovalResolution.ALLOW_ONCE))
            val result = retry.await()
            assertEquals(listOf("b.txt"), result.changedFiles)
            assertTrue(result.pendingFiles.isEmpty())
            assertEquals("CLI b.txt", b.content)
            assertFalse(b.dirty)
            assertEquals(WorkspaceAgentSyncPhase.SYNCED, f.host.workspaceSyncState.value.phase)
            assertEquals(1, f.peer.promptCount)
        } finally { binding.close() }
    }

    @Test(timeout = 45_000) fun failedMiddleTransferPreservesItsProposalAndDoesNotRepublishSuccessfulFiles() = fixture(
        initial = { root -> listOf("a.txt", "b.txt", "c.txt").forEach { File(root, it).writeText("original $it") } },
    ) { f ->
        f.io.beforePull = { path -> if (path.endsWith("/b.txt")) throw java.io.IOException("fixture transfer unavailable") }
        val result = f.turn { listOf("a.txt", "b.txt", "c.txt").forEach { File(working, it).writeText("CLI $it") } }
        assertEquals(setOf("a.txt", "c.txt"), result.changedFiles.toSet())
        assertEquals(listOf("b.txt"), result.conflicts)
        assertEquals("original b.txt", File(f.source, "b.txt").readText())
        assertEquals("CLI b.txt", File(f.working, "b.txt").readText())
        val published = listOf("a.txt", "c.txt").associateWith {
            Files.readAttributes(File(f.source, it).toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
        }
        f.io.beforePull = {}
        val retry = f.host.reviewWorkspaceChanges("opencode")
        assertEquals(listOf("b.txt"), retry.changedFiles)
        assertTrue(retry.pendingFiles.isEmpty())
        assertEquals(4, f.io.pulls)
        published.forEach { (path, key) -> assertEquals(key,
            Files.readAttributes(File(f.source, path).toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()) }
        assertTrue(f.host.reviewWorkspaceChanges("opencode").changedFiles.isEmpty())
        assertEquals(4, f.io.pulls)
        assertEquals(1, f.peer.promptCount)
    }

    @Test(timeout = 45_000) fun cancellationAfterFirstPublicationRetainsTheRemainderAndRetryDoesNotReplayIt() = fixture(
        initial = { root -> listOf("a.txt", "b.txt").forEach { File(root, it).writeText("original $it") } },
    ) { f ->
        val blocked = CompletableDeferred<Unit>()
        f.io.beforePull = { path -> if (path.endsWith("/b.txt")) { blocked.complete(Unit); awaitCancellation() } }
        val work = async { f.start() }
        val prompt = f.peer.prompt()
        listOf("a.txt", "b.txt").forEach { File(f.working, it).writeText("CLI $it") }
        f.peer.finish(prompt)
        withTimeout(5_000) { blocked.await() }
        work.cancelAndJoin()
        assertEquals("CLI a.txt", File(f.source, "a.txt").readText())
        assertEquals("original b.txt", File(f.source, "b.txt").readText())
        assertEquals("CLI b.txt", File(f.working, "b.txt").readText())
        assertEquals(WorkspaceAgentSyncPhase.INTERRUPTED, f.host.workspaceSyncState.value.phase)
        val key = Files.readAttributes(File(f.source, "a.txt").toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
        f.io.beforePull = {}
        val retry = f.host.reviewWorkspaceChanges("opencode")
        assertEquals(listOf("b.txt"), retry.changedFiles)
        assertTrue(retry.pendingFiles.isEmpty())
        assertEquals(key, Files.readAttributes(File(f.source, "a.txt").toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey())
        assertEquals(3, f.io.pulls)
        assertEquals(WorkspaceAgentSyncPhase.SYNCED, f.host.workspaceSyncState.value.phase)
    }

    @Test(timeout = 45_000) fun finalInventoryFailureAfterPartialPublicationCanRetryWithoutLosingEitherSide() = fixture(
        initial = { root -> listOf("a.txt", "b.txt").forEach { File(root, it).writeText("original $it") } },
    ) { f ->
        var snapshots = 0
        f.io.beforeSnapshotEntries = { if (++snapshots == 2) throw java.io.IOException("fixture final inventory failed") }
        f.io.beforePull = { path -> if (path.endsWith("/b.txt")) throw java.io.IOException("fixture transfer failed") }
        val result = f.turn { listOf("a.txt", "b.txt").forEach { File(working, it).writeText("CLI $it") } }
        assertEquals(listOf("a.txt"), result.changedFiles)
        assertEquals(listOf("b.txt"), result.conflicts)
        assertTrue(f.host.workspaceSyncState.value.notice.orEmpty().contains("fixture final inventory failed"))
        assertEquals(WorkspaceAgentSyncPhase.INTERRUPTED, f.host.workspaceSyncState.value.phase)
        assertEquals("CLI a.txt", File(f.source, "a.txt").readText())
        assertEquals("original b.txt", File(f.source, "b.txt").readText())
        assertEquals("CLI b.txt", File(f.working, "b.txt").readText())
        f.io.beforeSnapshotEntries = {}; f.io.beforePull = {}
        val retry = f.host.reviewWorkspaceChanges("opencode")
        assertEquals(listOf("b.txt"), retry.changedFiles)
        assertTrue(retry.pendingFiles.isEmpty())
        assertEquals("CLI b.txt", File(f.source, "b.txt").readText())
        assertEquals(3, f.io.pulls)
        assertEquals(1, f.peer.promptCount)
    }

    @Test(timeout = 45_000) fun cancelledApprovalReportsInterruptedAndRetainsUnpublishedFiles() = fixture(effect = PermEffect.ASK) { f ->
        val work = async { f.start() }
        val prompt = f.peer.prompt()
        File(f.working, "pending.txt").writeText("unpublished CLI work")
        f.peer.finish(prompt)
        awaitReviewBeforeCompletion(f, work)
        work.cancelAndJoin()
        assertEquals(WorkspaceAgentSyncPhase.INTERRUPTED, f.host.workspaceSyncState.value.phase)
        assertFalse(File(f.source, "pending.txt").exists())
        assertEquals("unpublished CLI work", File(f.working, "pending.txt").readText())
        assertNull(f.approvals.pending.value)
    }

    @Test(timeout = 45_000) fun interruptionIsVisibleBeforeCancellableTurnFinishesItsCleanup() = fixture(
        initial = { File(it, "file.txt").writeText("original") },
    ) { f ->
        val pulling = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        f.io.beforePull = { pulling.complete(Unit); awaitCancellation() }
        val work = async { f.start() }
        val prompt = f.peer.prompt()
        File(f.working, "file.txt").writeText("CLI proposal")
        f.peer.finish(prompt)
        withTimeout(5_000) { pulling.await() }
        f.io.beforeRefresh = { cleanup.complete(Unit); releaseCleanup.await() }
        work.cancel()
        try {
            withTimeout(5_000) { cleanup.await() }
            assertEquals(WorkspaceAgentSyncPhase.INTERRUPTED, f.host.workspaceSyncState.value.phase)
            assertEquals("original", File(f.source, "file.txt").readText())
            assertEquals("CLI proposal", File(f.working, "file.txt").readText())
        } finally {
            releaseCleanup.complete(Unit)
            work.cancelAndJoin()
        }
    }

    @Test(timeout = 45_000) fun convergedDirectoryDoesNotKeepReviewPermanentlyPending() = fixture(
        initial = { File(it, "item").writeText("original file") },
    ) { f ->
        val first = f.turn {
            for (root in listOf(source, working)) {
                assertTrue(File(root, "item").delete()); assertTrue(File(root, "item").mkdir())
                File(root, "item/child").writeText("same directory work")
            }
        }
        assertTrue(first.changedFiles.isEmpty())
        repeat(2) {
            val review = f.host.reviewWorkspaceChanges("opencode")
            assertTrue(review.changedFiles.isEmpty())
            assertTrue("Both directories agree; stale file baseline must not require another review", review.pendingFiles.isEmpty())
            assertEquals(WorkspaceAgentSyncPhase.SYNCED, f.host.workspaceSyncState.value.phase)
        }
        assertEquals("same directory work", File(f.source, "item/child").readText())
        assertEquals("same directory work", File(f.working, "item/child").readText())
        assertEquals(0, f.io.pulls)
        assertEquals(1, f.peer.promptCount)
    }

    @Test(timeout = 45_000) fun agreedDirectoryKeepsOnlyItsDivergentChildInReview() = fixture(
        initial = { File(it, "item").writeText("original file") },
    ) { f ->
        f.turn {
            for (root in listOf(source, working)) {
                assertTrue(File(root, "item").delete()); assertTrue(File(root, "item").mkdir())
            }
            File(source, "item/child").writeText("user child")
            File(working, "item/child").writeText("CLI child")
        }
        val review = f.host.reviewWorkspaceChanges("opencode")
        assertTrue(review.changedFiles.isEmpty())
        assertEquals(listOf("item/child"), review.pendingFiles)
        assertEquals(WorkspaceAgentSyncPhase.PENDING, f.host.workspaceSyncState.value.phase)
        assertEquals(listOf("item/child"), f.host.workspaceSyncState.value.pendingPaths)
        assertEquals("user child", File(f.source, "item/child").readText())
        assertEquals("CLI child", File(f.working, "item/child").readText())
        assertEquals(0, f.io.pulls)
    }

    companion object {
        private fun writePattern(file: File, size: Int, salt: Int) {
            file.parentFile.mkdirs()
            file.outputStream().use { output ->
                val block = ByteArray(64 * 1024) { ((it + salt) % 256).toByte() }
                var left = size
                while (left > 0) { val count = minOf(left, block.size); output.write(block, 0, count); left -= count }
            }
        }
        private fun hash(file: File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val block = ByteArray(64 * 1024)
                while (true) { val count = input.read(block); if (count < 0) break; digest.update(block, 0, count) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
