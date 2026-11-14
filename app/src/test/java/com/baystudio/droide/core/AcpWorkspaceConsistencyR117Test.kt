package com.baystudio.droide.core

import java.io.*
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real ACP request dispatch, real local mirror/repositories, and an in-memory protocol peer. */
class AcpWorkspaceConsistencyR117Test {
    private class Fixture(val root: File, effect: PermEffect) : AutoCloseable {
        val source = File(root, "project").apply { mkdirs() }
        val cache = File(root, "cache").apply { mkdirs() }
        val failures = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t -> failures += t })
        val local = LocalAgentWorkspaceProcessHost(source, cache, scope, File(root, "agent-workspaces"))
        val io = local.workspaceIo
        val approvals = ApprovalManager()
        val permissions = PermissionEngine(listOf(PermRule("*", "*", effect)))
        var beforeMutation: suspend (FileRepository.Mutation) -> Unit = {}
        var onMutation: suspend (FileRepository.Mutation) -> Unit = {}
        val files = FileRepository(source, beforeMutation = { beforeMutation(it) }, onMutation = { onMutation(it) })
        val replies = CopyOnWriteArrayList<JsonObject>()
        private val alive = AtomicBoolean(true)
        private val peerOutput = PipedOutputStream()
        private val output = PipedInputStream(peerOutput, 8_192)
        private val captured = ByteArrayOutputStream()
        private val process = object : HostedStdioProcess {
            override val stdin = object : OutputStream() {
                @Synchronized override fun write(value: Int) { captured.write(value) }
                @Synchronized override fun write(bytes: ByteArray, offset: Int, length: Int) { captured.write(bytes, offset, length) }
                @Synchronized override fun flush() {
                    val text = captured.toString("UTF-8")
                    if (text.isEmpty()) return
                    check(text.endsWith('\n')); captured.reset()
                    text.lineSequence().filter(String::isNotBlank).forEach { replies += Json.parseToJsonElement(it).jsonObject }
                }
            }
            override val stdout = output
            override val stderr = ByteArrayInputStream(byteArrayOf())
            override val isAlive get() = alive.get()
            override val exitCode: Int? get() = null
            override suspend fun awaitExit() = 0
            override fun close() { if (alive.compareAndSet(true, false)) { peerOutput.close(); output.close() } }
        }
        lateinit var mapper: WorkspacePathMapper
        lateinit var working: File
        lateinit var rpc: AcpNdjsonConnection

        suspend fun start(initial: Map<String, String>) {
            initial.forEach { (path, text) -> File(source, path).apply { parentFile.mkdirs(); writeText(text) } }
            io.refresh()
            mapper = local.pathMapper()
            working = File(mapper.remoteRoot)
            val host = object : StdioProcessHost {
                override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
                override suspend fun resolveExecutable(command: String): String? = error("No guest process in ACP file test")
                override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess =
                    error("No guest process in ACP file test")
                override suspend fun pathMapper() = mapper
            }
            rpc = AcpNdjsonConnection(process, files, mapper, host, approvals, permissions, scope, io)
            rpc.bindSession("session-one", "owner-one")
            rpc.beginTurn(WorkspaceAgentMode.EDIT)
        }

        @Synchronized fun request(id: String, method: String, path: String, content: String? = null) {
            val params = buildJsonObject {
                put("sessionId", "session-one"); put("path", path)
                content?.let { put("content", it) }
            }
            val frame = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) }
            peerOutput.write((frame.toString() + "\n").toByteArray()); peerOutput.flush()
        }
        @Synchronized fun permission(id: String) {
            val frame = buildJsonObject {
                put("jsonrpc", "2.0"); put("id", id); put("method", "session/request_permission")
                put("params", buildJsonObject {
                    put("sessionId", "session-one")
                    put("toolCall", buildJsonObject {
                        put("toolCallId", "write-tool"); put("title", "Write file"); put("kind", "edit")
                        put("rawInput", buildJsonObject { put("path", File(working, "file.txt").path); put("content", "approved") })
                    })
                    put("options", buildJsonArray {
                        add(buildJsonObject { put("optionId", "allow"); put("kind", "allow_once") })
                        add(buildJsonObject { put("optionId", "reject"); put("kind", "reject_once") })
                    })
                })
            }
            peerOutput.write((frame.toString() + "\n").toByteArray()); peerOutput.flush()
        }
        fun read(id: String, relative: String) = request(id, "fs/read_text_file", File(working, relative).path)
        fun write(id: String, relative: String, content: String) = request(id, "fs/write_text_file", File(working, relative).path, content)
        suspend fun reply(id: String): JsonObject = withTimeout(4_000) {
            while (true) {
                replies.firstOrNull { it["id"]?.jsonPrimitive?.contentOrNull == id }?.let { return@withTimeout it }
                delay(5)
            }
            error("unreachable")
        }
        suspend fun pending(): ApprovalRequest = withTimeout(4_000) {
            while (approvals.pending.value == null) delay(5)
            approvals.pending.value!!
        }
        override fun close() {
            if (::rpc.isInitialized) rpc.close()
            process.close(); scope.cancel()
        }
    }

    private fun fixture(
        effect: PermEffect = PermEffect.ALLOW,
        initial: Map<String, String> = mapOf("file.txt" to "original"),
        block: suspend (Fixture) -> Unit,
    ) = runBlocking {
        val root = Files.createTempDirectory("droide-r117-acp-").toFile()
        val f = Fixture(root, effect)
        try { withTimeout(15_000) { f.start(initial); block(f) } }
        finally {
            f.close(); withTimeout(3_000) { f.scope.coroutineContext.job.join() }
            PathSecurity.deleteTreeNoFollow(root)
            assertTrue("Unexpected background failure: ${f.failures}", f.failures.isEmpty())
        }
    }

    private fun content(response: JsonObject) = response.getValue("result").jsonObject.getValue("content").jsonPrimitive.content
    private fun assertError(response: JsonObject) { assertNotNull(response["error"]); assertNull(response["result"]) }

    @Test fun delayedPermissionCannotAuthorizeAfterEndTurn() = fixture(effect = PermEffect.ASK) { f ->
        f.permission("permission")
        val pending = f.pending()
        f.rpc.endTurn()
        f.approvals.resolve(pending.id, ApprovalResolution.ALLOW_ONCE)
        assertError(f.reply("permission"))
    }

    @Test fun delayedPermissionCannotSeedNextEditTurn() = fixture(effect = PermEffect.ASK) { f ->
        f.permission("permission")
        val pending = f.pending()
        f.rpc.beginTurn(WorkspaceAgentMode.EDIT)
        f.approvals.resolve(pending.id, ApprovalResolution.ALLOW_SESSION)
        assertFalse(f.approvals.hasSessionApproval("owner-one", EXTERNAL_ACP_PERMISSION_SCOPE,
            pending.sessionGrantAction, pending.sessionGrantResource))
        assertError(f.reply("permission"))
        f.write("write", "file.txt", "approved")
        val fresh = f.pending()
        assertNotEquals(pending.id, fresh.id)
        f.approvals.resolve(fresh.id, ApprovalResolution.DENY)
        assertError(f.reply("write"))
        assertEquals("original", File(f.source, "file.txt").readText())
    }

    @Test fun delayedDirectWriteCannotGrantNextTurnSessionApproval() = fixture(effect = PermEffect.ASK) { f ->
        f.write("old-write", "file.txt", "approved")
        val pending = f.pending()
        f.rpc.beginTurn(WorkspaceAgentMode.EDIT)
        f.approvals.resolve(pending.id, ApprovalResolution.ALLOW_SESSION)
        assertError(f.reply("old-write"))
        assertFalse(f.approvals.hasSessionApproval("owner-one", EXTERNAL_ACP_PERMISSION_SCOPE,
            pending.sessionGrantAction, pending.sessionGrantResource))
        f.write("new-write", "file.txt", "approved")
        val fresh = f.pending()
        assertNotEquals(pending.id, fresh.id)
        f.approvals.resolve(fresh.id, ApprovalResolution.DENY)
        assertError(f.reply("new-write"))
        assertEquals("original", File(f.source, "file.txt").readText())
    }

    @Test fun acpReadSeesCliShellEditWithoutPublishingIt() = fixture { f ->
        File(f.working, "file.txt").writeText("CLI shell result")
        f.read("read", "file.txt")
        assertEquals("CLI shell result", content(f.reply("read")))
        assertEquals("original", File(f.source, "file.txt").readText())
    }

    @Test fun acpReadSeesCliOnlyFileAndRespectsCliDeletion() = fixture { f ->
        File(f.working, "generated.txt").writeText("created by CLI")
        assertTrue(File(f.working, "file.txt").delete())
        f.read("new", "generated.txt")
        assertEquals("created by CLI", content(f.reply("new")))
        f.read("deleted", "file.txt")
        assertError(f.reply("deleted"))
        assertEquals("original", File(f.source, "file.txt").readText())
        assertFalse(File(f.source, "generated.txt").exists())
    }

    @Test fun excludedDependencyReadUsesActualCliExecutionCopy() = fixture { f ->
        val relative = "node_modules/dependency/index.js"
        File(f.source, relative).apply { parentFile.mkdirs(); writeText("canonical dependency") }
        File(f.working, relative).apply { parentFile.mkdirs(); writeText("CLI installed dependency") }
        f.read("dependency", relative)
        assertEquals("CLI installed dependency", content(f.reply("dependency")))
        assertEquals("canonical dependency", File(f.source, relative).readText())
    }

    @Test fun approvedWriteAfterShellEditPublishesAndUpdatesSameExecutionView() = fixture { f ->
        File(f.working, "file.txt").writeText("CLI intermediate")
        f.write("write", "file.txt", "approved final")
        assertNotNull(f.reply("write")["result"])
        assertEquals("approved final", File(f.source, "file.txt").readText())
        assertEquals("approved final", File(f.working, "file.txt").readText())
        assertEquals(setOf("file.txt"), f.rpc.drainDirectWrites())
        f.read("read", "file.txt")
        assertEquals("approved final", content(f.reply("read")))
    }

    @Test fun acpWritesCreateParentsAndCanUpdateTheirOwnConvergedOutput() = fixture { f ->
        f.write("first", "nested/generated.txt", "one")
        assertNotNull(f.reply("first")["result"])
        f.write("second", "nested/generated.txt", "two")
        assertNotNull(f.reply("second")["result"])
        assertEquals("two", File(f.source, "nested/generated.txt").readText())
        assertEquals("two", File(f.working, "nested/generated.txt").readText())
    }

    @Test fun earlierCanonicalEditCannotBeOverwrittenFromStaleCliView() = fixture { f ->
        File(f.source, "file.txt").writeText("user's newer save")
        f.write("write", "file.txt", "agent replacement")
        assertError(f.reply("write"))
        assertEquals("user's newer save", File(f.source, "file.txt").readText())
        assertEquals("original", File(f.working, "file.txt").readText())
        assertTrue(f.rpc.drainDirectWrites().isEmpty())
    }

    @Test fun canonicalEditWhileApprovalIsPendingSurvives() = fixture(PermEffect.ASK) { f ->
        f.write("write", "file.txt", "agent replacement")
        val approval = f.pending()
        File(f.source, "file.txt").writeText("user during approval")
        assertTrue(f.approvals.resolve(approval.id, ApprovalResolution.ALLOW_ONCE))
        assertError(f.reply("write"))
        assertEquals("user during approval", File(f.source, "file.txt").readText())
        assertEquals("original", File(f.working, "file.txt").readText())
    }

    @Test fun executionEditWhileApprovalIsPendingSurvivesWithoutCanonicalPublication() = fixture(PermEffect.ASK) { f ->
        f.write("write", "file.txt", "agent replacement")
        val approval = f.pending()
        File(f.working, "file.txt").writeText("concurrent CLI edit")
        assertTrue(f.approvals.resolve(approval.id, ApprovalResolution.ALLOW_ONCE))
        assertError(f.reply("write"))
        assertEquals("original", File(f.source, "file.txt").readText())
        assertEquals("concurrent CLI edit", File(f.working, "file.txt").readText())
        assertTrue(f.rpc.drainDirectWrites().isEmpty())
    }

    @Test fun rejectedRebindOfSameSessionAndOwnerInvalidatesPendingWrite() = fixture(PermEffect.ASK) { f ->
        f.write("write", "file.txt", "stale replacement")
        val approval = f.pending()
        assertTrue(runCatching { f.rpc.bindSession("session-one", "owner-one") }.isFailure)
        assertTrue(f.approvals.resolve(approval.id, ApprovalResolution.ALLOW_ONCE))
        assertError(f.reply("write"))
        assertEquals("original", File(f.source, "file.txt").readText())
        assertEquals("original", File(f.working, "file.txt").readText())
    }

    @Test fun newTurnDoesNotReadmitWriteWaitingFromPreviousTurn() = fixture(PermEffect.ASK) { f ->
        f.write("write", "file.txt", "stale replacement")
        val approval = f.pending()
        f.rpc.freezeMutations(); f.rpc.beginTurn(WorkspaceAgentMode.EDIT)
        assertTrue(f.approvals.resolve(approval.id, ApprovalResolution.ALLOW_ONCE))
        assertError(f.reply("write"))
        assertEquals("original", File(f.source, "file.txt").readText())
    }

    @Test fun turnRetiredDuringRepositoryPreparationCannotPublish() = fixture { f ->
        f.beforeMutation = { f.rpc.freezeMutations(); f.rpc.beginTurn(WorkspaceAgentMode.EDIT) }
        f.write("write", "file.txt", "stale replacement")
        assertError(f.reply("write"))
        assertEquals("original", File(f.source, "file.txt").readText())
        assertEquals("original", File(f.working, "file.txt").readText())
    }

    @Test fun projectionRaceRetainsBothPublishedCanonicalAndConcurrentCliVersions() = fixture { f ->
        f.onMutation = { if (it is FileRepository.Mutation.Write) File(f.working, "file.txt").writeText("CLI raced projection") }
        f.write("write", "file.txt", "approved canonical")
        val reply = f.reply("write")
        assertError(reply)
        assertTrue(reply.toString().contains("Project file was saved"))
        assertEquals("approved canonical", File(f.source, "file.txt").readText())
        assertEquals("CLI raced projection", File(f.working, "file.txt").readText())
        assertEquals(setOf("file.txt"), f.rpc.drainDirectWrites())
        f.io.refresh()
        assertEquals("CLI raced projection", File(f.working, "file.txt").readText())
    }

    @Test fun dirtyEditorAdmissionPreventsBothCanonicalAndMirrorWrite() = fixture { f ->
        f.beforeMutation = { error("Unsaved editor changes must be preserved") }
        f.write("write", "file.txt", "agent replacement")
        assertError(f.reply("write"))
        assertEquals("original", File(f.source, "file.txt").readText())
        assertEquals("original", File(f.working, "file.txt").readText())
        assertTrue(f.rpc.drainDirectWrites().isEmpty())
    }

    @Test fun binaryAndOversizedTargetsCannotBeReplacedThroughBoundedTextApi() = fixture { f ->
        File(f.source, "binary.bin").writeBytes(byteArrayOf(0, -1, 1))
        f.write("binary", "binary.bin", "replacement")
        assertError(f.reply("binary"))
        assertArrayEquals(byteArrayOf(0, -1, 1), File(f.source, "binary.bin").readBytes())
        val large = File(f.source, "large.bin")
        RandomAccessFile(large, "rw").use { it.setLength(4L * 1024 * 1024 * 1024 + 1) }
        f.write("large", "large.bin", "replacement")
        assertError(f.reply("large"))
        assertEquals(4L * 1024 * 1024 * 1024 + 1, large.length())
        assertFalse(File(f.working, "large.bin").exists())
    }

    @Test fun differentProjectPathIsRejected() = fixture { f ->
        val other = File(f.root, "other-project").apply { mkdirs() }
        File(other, "file.txt").writeText("other project")
        f.request("other", "fs/write_text_file", File(other, "file.txt").path, "wrong project write")
        assertError(f.reply("other"))
        assertEquals("other project", File(other, "file.txt").readText())
        assertTrue(f.rpc.drainDirectWrites().isEmpty())
    }
}
