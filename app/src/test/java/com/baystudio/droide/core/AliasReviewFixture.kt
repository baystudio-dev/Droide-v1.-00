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

/** Diagnostic fixture: real host/repository/retained mirror; only ACP process is an in-memory peer. */
internal class AliasReviewFixture private constructor(
    val root: File, val source: File, val working: File,
    val host: WorkspaceAgentPluginHost, val approvals: ApprovalManager,
    val files: FileRepository, val scope: CoroutineScope, val peer: Peer,
    val fault: Fault, private val failures: CopyOnWriteArrayList<Throwable>,
) {
    class Fault(val point: Point = Point.NONE) {
        enum class Point { NONE, FORCE_FILE, FORCE_DIRECTORY, OBSERVER, SYNCHRONIZE, FINISH }
        @Volatile var enabled = true
        var hits = 0
        var committedReceipts = 0
        var publicationCalls = 0
        var writeAdmissions = 0
        fun check(point: Point) { if (enabled && this.point == point) { hits++; throw java.io.IOException("manual-fixture-$point") } }
    }
    val promptCount get() = peer.promptCount
    suspend fun seedPending(proposals: Map<String, String> = mapOf("file.txt" to "CLI proposal")) = coroutineScope {
        val turn = async { host.runMediaTurn("opencode", "fixture proposal", emptyList(), null,
            WorkspaceAgentMode.PLAN, "binary_fixture", {}) }
        val request = peer.prompt()
        proposals.forEach { (name, value) -> File(working, name).apply { parentFile.mkdirs(); writeText(value) } }
        peer.finish(request)
        val result = turn.await()
        assertEquals(proposals.keys.sorted(), result.conflicts.sorted())
        assertEquals(WorkspaceAgentSyncPhase.PENDING, host.workspaceSyncState.value.phase)
        assertNull(approvals.pending.value)
        assertEquals(1, promptCount)
    }
    suspend fun reviewApproved(): WorkspaceAgentSyncResult = coroutineScope {
        val review = async { host.reviewWorkspaceChanges("opencode") }
        val approval = withTimeout(5_000) { approvals.pending.filterNotNull().first() }
        assertEquals("agent_workspace_reconcile", approval.kind)
        assertTrue(approval.detail.contains("Destination:"))
        assertTrue(approvals.resolve(approval.id, ApprovalResolution.ALLOW_ONCE))
        review.await()
    }
    suspend fun close() {
        try { host.close() } finally {
            peer.close()
            withTimeout(3_000) { scope.coroutineContext.job.cancelAndJoin() }
            PathSecurity.deleteTreeNoFollow(root)
        }
        assertTrue("Unexpected background failures: $failures", failures.isEmpty())
    }
    companion object {
        suspend fun open(existing: Boolean = true, fault: Fault = Fault(), nativePublisher: Boolean = false): AliasReviewFixture {
            val root = Files.createTempDirectory("manual-review-fixture-").toFile().canonicalFile
            val failures = CopyOnWriteArrayList<Throwable>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, failure -> failures += failure })
            val data = File(root, "real-app-data").apply { mkdirs() }
            val alias = File(root, "alias-app-data")
            Files.createSymbolicLink(alias.toPath(), data.toPath())
            val appFiles = File(alias, "app/files").apply { mkdirs() }
            val source = File(appFiles, "project").apply { mkdirs() }.canonicalFile
            if (existing) File(source, "file.txt").writeText("original")
            val cache = File(appFiles, "cache").apply { mkdirs() }
            val local = LocalAgentWorkspaceProcessHost(source, cache, scope, File(appFiles, "persistent-workspaces"))
            val peer = Peer()
            val processes = object : StdioProcessHost {
                override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
                override suspend fun resolveExecutable(command: String) = "/fixture/$command"
                override suspend fun pathMapper() = local.pathMapper()
                override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
                    check(argv == listOf("/fixture/opencode", "acp")); return peer.process
                }
            }
            val io = object : AgentWorkspaceIo by local.workspaceIo {
                override suspend fun shellBounded(command: String, maxOutputBytes: Int): BridgeShellResult =
                    error("Manual fixture cannot launch Linux")
            }
            val documents = WorkspaceDocumentAuthorityBridge()
            val durability = object : StreamPublicationDurability {
                override fun forceFile(channel: java.nio.channels.FileChannel) {
                    fault.check(Fault.Point.FORCE_FILE); StreamPublicationDurability.platform.forceFile(channel)
                }
                override fun forceDirectory(directory: File) {
                    fault.check(Fault.Point.FORCE_DIRECTORY); StreamPublicationDurability.platform.forceDirectory(directory)
                }
            }
            val participant = if (fault.point in setOf(Fault.Point.SYNCHRONIZE, Fault.Point.FINISH))
                WorkspaceMutationParticipant { guard, mutation -> object : WorkspaceMutationSession(guard, mutation) {
                    override fun localReturned(receipt: WorkspaceLocalReceipt) {
                        if (receipt.preparation.effect == WorkspaceLocalEffect.WRITE && receipt.successful) fault.committedReceipts++
                    }
                    override suspend fun synchronize() { fault.check(Fault.Point.SYNCHRONIZE) }
                    override fun finish(outcome: WorkspaceMutationOutcome) { fault.check(Fault.Point.FINISH) }
                } } else null
            val selectedPublisher = if (nativePublisher) NewProjectFilePublisher.native() else NewProjectFilePublisher.platform
            val files = FileRepository(source, createRoot = false,
                beforeMutation = {
                    documents.validatePersistedMutation(it)
                    if (it is FileRepository.Mutation.Write) fault.writeAdmissions++
                },
                onMutation = { documents.persistedMutation(it); fault.check(Fault.Point.OBSERVER) },
                newFilePublisher = NewProjectFilePublisher { stage, target ->
                    fault.publicationCalls++; selectedPublisher.publish(stage, target)
                }, streamDurability = durability, participant = participant)
            val approvals = ApprovalManager()
            val host = WorkspaceAgentPluginHost(source, cache, files, processes, approvals,
                PermissionEngine(listOf(PermRule("*", "*", PermEffect.ASK))), scope, io)
            try {
                host.prepareSession("opencode", null, {})
                return AliasReviewFixture(root, source, File(local.pathMapper().remoteRoot), host,
                    approvals, files, scope, peer, fault, failures)
            } catch (failure: Throwable) {
                host.close(); peer.close(); scope.coroutineContext.job.cancelAndJoin()
                PathSecurity.deleteTreeNoFollow(root); throw failure
            }
        }
    }
    internal class Peer : AutoCloseable {
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

}
