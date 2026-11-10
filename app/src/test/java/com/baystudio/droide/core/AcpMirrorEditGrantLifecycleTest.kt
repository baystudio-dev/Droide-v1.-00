package com.baystudio.droide.core

import java.io.*
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AcpMirrorEditGrantLifecycleTest {
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

    private suspend fun grant(f: Fixture): AcpMirrorEditGrant {
        f.permission("approve"); assertNotNull(f.reply("approve")["result"])
        return requireNotNull(f.rpc.freezeForReconciliation())
    }
    private suspend fun consume(f: Fixture, grant: AcpMirrorEditGrant, content: String = "approved", mode: Int? = 420) =
        grant.consume(File(f.working, "file.txt").path, "file.txt", WorkspaceSyncManifest.Entry(AcpMirrorEditGrant.contentHash(content), mode))

    @Test fun consumeIsOnceEvenIfPublicationNeverOccurs() = fixture { f ->
        val grant = grant(f); assertTrue(consume(f, grant)); assertFalse(consume(f, grant)); grant.revoke()
        assertTrue(runCatching { grant.requireCurrent() }.isFailure)
    }
    @Test fun changedContentConsumesWithoutAuthorizingRetry() = fixture { f ->
        val grant = grant(f); assertFalse(consume(f, grant, "changed")); assertFalse(consume(f, grant))
    }
    @Test fun changedSameBytesVersionInvalidatesGrant() = fixture { f ->
        val grant = grant(f)
        val old = File(f.source, "file.txt"); val replacement = File(f.source, "replacement")
        replacement.writeText("original"); Files.move(replacement.toPath(), old.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertFalse(consume(f, grant))
    }
    @Test fun unknownModeCannotUseGrant() = fixture { f -> assertFalse(consume(f, grant(f), mode = null)) }
    @Test fun wrongPathCannotUseGrant() = fixture { f ->
        val grant = grant(f)
        assertFalse(grant.consume(File(f.working, "other.txt").path, "other.txt",
            WorkspaceSyncManifest.Entry(AcpMirrorEditGrant.contentHash("approved"), 420)))
    }
    @Test fun cancelledFrozenTurnInvalidatesGrant() = fixture { f ->
        val grant = grant(f); f.rpc.cancelTurn("session-one"); assertFalse(consume(f, grant))
    }
    @Test fun cancellationAfterConsumptionPreventsPublication() = fixture { f ->
        val grant = grant(f); assertTrue(consume(f, grant)); f.rpc.cancelTurn("session-one")
        assertTrue(runCatching { grant.requireCurrent() }.isFailure)
    }
    @Test fun laterTurnInvalidatesFrozenGrant() = fixture { f ->
        val grant = grant(f); f.rpc.beginTurn(WorkspaceAgentMode.EDIT); assertFalse(consume(f, grant))
    }
    @Test fun sessionRebindInvalidatesFrozenGrant() = fixture { f ->
        val grant = grant(f); runCatching { f.rpc.bindSession("session-two", "owner-two") }; assertFalse(consume(f, grant))
    }
    @Test fun transportClosureInvalidatesFrozenGrant() = fixture { f ->
        val grant = grant(f); f.rpc.close(); assertFalse(consume(f, grant))
    }
    @Test fun ordinaryFreezeCannotTransferGrant() = fixture { f ->
        f.permission("approve"); f.reply("approve"); f.rpc.freezeMutations()
        assertNull(f.rpc.freezeForReconciliation())
    }
    @Test fun directWriteConsumesMirrorCounterpart() = fixture { f ->
        f.permission("approve"); f.reply("approve"); f.write("write", "file.txt", "approved")
        assertNotNull(f.reply("write")["result"]); assertNull(f.rpc.freezeForReconciliation())
    }
    @Test fun cancelledApprovalCannotSeedNextTurn() = fixture(effect = PermEffect.ASK) { f ->
        f.permission("approve"); val pending = f.pending(); f.rpc.cancelTurn("session-one")
        f.approvals.resolve(pending.id, ApprovalResolution.ALLOW_ONCE)
        f.rpc.beginTurn(WorkspaceAgentMode.EDIT); assertNull(f.rpc.freezeForReconciliation())
    }
    @Test fun laterDeniedPermissionRetiresOldGrant() = fixture(effect = PermEffect.ASK) { f ->
        f.permission("first"); f.approvals.resolve(f.pending().id, ApprovalResolution.ALLOW_ONCE); f.reply("first")
        f.permission("second"); f.approvals.resolve(f.pending().id, ApprovalResolution.DENY); f.reply("second")
        assertNull(f.rpc.freezeForReconciliation())
    }
    @Test fun laterPermissionRevokesAlreadyTransferredGrant() = fixture { f ->
        val grant = grant(f); f.permission("later"); f.reply("later")
        assertFalse(consume(f, grant))
    }

}
