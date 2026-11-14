package com.baystudio.droide.core

import java.io.*
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real ACP dispatch and approvals, with an in-memory peer and synthetic workspace files. */
class AcpReadPermissionR59Test {
    private class Fixture(
        val permissions: PermissionEngine = PermissionEngine(),
        val approvals: ApprovalManager = ApprovalManager(),
        private val sessionId: String = "session-one",
        approvalOwner: String = "owner-one",
    ) : AutoCloseable {
        val root = Files.createTempDirectory("droide-r59-read-").toFile()
        val failures = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> failures += error })
        private val alive = AtomicBoolean(true)
        private val peerOutput = PipedOutputStream()
        private val output = PipedInputStream(peerOutput, 8_192)
        private val captured = ByteArrayOutputStream()
        val replies = CopyOnWriteArrayList<JsonObject>()
        val process = object : HostedStdioProcess {
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
        private val mapper = WorkspacePathMapper(root, "/fixture/project")
        private val host = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command: String): String? = error("No executable lookup in read fixture")
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess = error("No process execution in read fixture")
            override suspend fun pathMapper() = mapper
        }
        private val io = object : AgentWorkspaceIo {
            override val checksumCommand = "fixture-checksum"
            override fun requirePath(path: String) = Unit
            override suspend fun refresh() = Unit
            override suspend fun shellBounded(command: String, maxOutputBytes: Int): BridgeShellResult = error("No shell in read fixture")
            override suspend fun pull(path: String, output: File) = error("No transfer in read fixture")
            override suspend fun push(input: File, path: String) = error("No transfer in read fixture")
        }
        val rpc = AcpNdjsonConnection(process, FileRepository(root), mapper, host, approvals, permissions, scope, io)
        init { rpc.bindSession(sessionId, approvalOwner); rpc.beginTurn(WorkspaceAgentMode.PLAN) }
        fun write(path: String, content: String = "synthetic read payload") { File(root, path).apply { parentFile.mkdirs(); writeText(content) } }
        @Synchronized fun read(id: String, path: String, line: Int? = null, limit: Int? = null, session: String = sessionId) {
            val frame = buildJsonObject {
                put("jsonrpc", "2.0"); put("id", id); put("method", "fs/read_text_file")
                put("params", buildJsonObject {
                    put("sessionId", session); put("path", path)
                    line?.let { put("line", it) }; limit?.let { put("limit", it) }
                })
            }
            peerOutput.write((frame.toString() + "\n").toByteArray()); peerOutput.flush()
        }
        suspend fun reply(id: String): JsonObject = withTimeout(3_000) {
            while (true) {
                replies.firstOrNull { it["id"]?.jsonPrimitive?.contentOrNull == id }?.let { return@withTimeout it }
                delay(5)
            }
            error("unreachable")
        }
        suspend fun pending(): ApprovalRequest = withTimeout(3_000) {
            while (approvals.pending.value == null) delay(5)
            approvals.pending.value!!
        }
        suspend fun whilePublicationIsQueued(block: suspend () -> Unit) {
            // Hold the real writer boundary so authority can change after the file read.
            val mutex = AcpNdjsonConnection::class.java.getDeclaredField("writeMutex").apply { isAccessible = true }.get(rpc) as kotlinx.coroutines.sync.Mutex
            mutex.lock()
            try {
                block()
            } finally { mutex.unlock() }
        }
        suspend fun awaitQueuedPublication() = withTimeout(3_000) {
            val field = AcpNdjsonConnection::class.java.getDeclaredField("writes").apply { isAccessible = true }
            while ((field.get(rpc) as Set<*>).isEmpty()) delay(5)
        }
        fun deny(path: String) { permissions.replacePolicy(PermissionPolicyDocument(rules = listOf(PermRule("read", path, PermEffect.DENY)))) }
        override fun close() { rpc.close(); process.close(); scope.cancel(); root.deleteRecursively() }
    }

    private fun fixture(
        permissions: PermissionEngine = PermissionEngine(),
        approvals: ApprovalManager = ApprovalManager(),
        sessionId: String = "session-one",
        approvalOwner: String = "owner-one",
        block: suspend (Fixture) -> Unit,
    ) = runBlocking {
        val fixture = Fixture(permissions, approvals, sessionId, approvalOwner)
        try { withTimeout(5_000) { block(fixture) } }
        finally { fixture.close(); withTimeout(2_000) { fixture.scope.coroutineContext.job.join() }; assertTrue(fixture.failures.toString(), fixture.failures.isEmpty()) }
    }
    private fun assertDenied(response: JsonObject) {
        assertNull(response["result"])
        assertNotNull(response["error"])
        assertFalse(response.toString().contains("synthetic read payload"))
    }

    @Test(timeout = 8_000) fun ordinaryPlanReadStillReturnsTheRequestedLines() = fixture { f ->
        f.write("notes.txt", "one\ntwo\nthree\nfour")
        f.read("slice", "/fixture/project/notes.txt", line = 2, limit = 2)
        assertEquals("two\nthree", f.reply("slice")["result"]!!.jsonObject["content"]!!.jsonPrimitive.content)
        assertNull(f.approvals.pending.value)
    }

    @Test(timeout = 8_000) fun explicitDenyBlocksReadWithoutAnApprovalPrompt() = fixture { f ->
        f.write("notes.txt"); f.deny("notes.txt"); f.read("denied", "/fixture/project/notes.txt")
        assertDenied(f.reply("denied")); assertNull(f.approvals.pending.value)
    }

    @Test(timeout = 8_000) fun sensitivePathUsesCanonicalRelativeResourceAndExternalOwner() = fixture { f ->
        f.write("config/.env"); f.read("sensitive", "/fixture/project/config/.env")
        val approval = f.pending()
        assertEquals("read", approval.action); assertEquals("config/.env", approval.resource)
        assertEquals(EXTERNAL_ACP_PERMISSION_SCOPE, approval.permissionScope)
        assertEquals("owner-one", approval.approvalSessionId)
        assertEquals("session-one", approval.provenance.sessionId)
        assertTrue(f.replies.isEmpty())
        assertTrue(f.approvals.resolve(approval.id, ApprovalResolution.ALLOW_ONCE))
        assertEquals("synthetic read payload", f.reply("sensitive")["result"]!!.jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test(timeout = 8_000) fun externalAgentScopeDenyIsUsedInsteadOfBuildScope() = fixture { f ->
        f.write("notes.txt")
        f.permissions.replacePolicy(PermissionPolicyDocument(agents = mapOf(EXTERNAL_ACP_PERMISSION_SCOPE to listOf(PermRule("read", "notes.txt", PermEffect.DENY)))))
        assertEquals(PermEffect.ALLOW, f.permissions.decide("read", "notes.txt", "build"))
        f.read("scope-deny", "/fixture/project/notes.txt")
        assertDenied(f.reply("scope-deny")); assertNull(f.approvals.pending.value)
    }

    @Test(timeout = 8_000) fun symlinkAliasChecksTheCanonicalSensitiveTarget() = fixture { f ->
        f.write(".env")
        Files.createSymbolicLink(File(f.root, "public.txt").toPath(), File(f.root, ".env").toPath())
        f.read("alias", "/fixture/project/public.txt")
        assertEquals(".env", f.pending().resource)
        f.approvals.resolve(false); assertDenied(f.reply("alias"))
    }

    @Test(timeout = 8_000) fun rejectedSensitiveReadNeverPublishesContent() = fixture { f ->
        f.write(".env"); f.read("rejected", "/fixture/project/.env")
        assertTrue(f.approvals.resolve(f.pending().id, ApprovalResolution.DENY))
        assertDenied(f.reply("rejected"))
    }

    @Test(timeout = 8_000) fun policyDenyInstalledDuringApprovalWinsOverAllowOnce() = fixture { f ->
        f.write(".env"); f.read("changed", "/fixture/project/.env")
        val approval = f.pending(); f.deny(".env")
        assertTrue(f.approvals.resolve(approval.id, ApprovalResolution.ALLOW_ONCE))
        assertDenied(f.reply("changed"))
    }

    @Test(timeout = 8_000) fun rejectedSameSessionAndOwnerRebindInvalidatesPendingRead() = fixture { f ->
        f.write(".env"); f.read("stale", "/fixture/project/.env")
        val approval = f.pending()
        assertTrue(runCatching { f.rpc.bindSession("session-one", "owner-one") }.isFailure)
        assertTrue(f.approvals.resolve(approval.id, ApprovalResolution.ALLOW_ONCE))
        assertDenied(f.reply("stale"))
        assertEquals(1, f.replies.count { it["id"]?.jsonPrimitive?.contentOrNull == "stale" })
    }

    @Test(timeout = 8_000) fun sessionReadGrantIsExactAndNewOwnerMustAskAgain() = fixture { f ->
        f.write(".env"); f.write("other.env")
        f.read("first", "/fixture/project/.env")
        assertTrue(f.approvals.resolve(f.pending().id, ApprovalResolution.ALLOW_SESSION)); assertNotNull(f.reply("first")["result"])
        f.read("reused", "/fixture/project/.env"); assertNotNull(f.reply("reused")["result"]); assertNull(f.approvals.pending.value)
        f.read("other", "/fixture/project/other.env")
        assertEquals("other.env", f.pending().resource); f.approvals.resolve(false); assertDenied(f.reply("other"))
        // Native command/session binding is intentionally one-way. A new owner
        // uses a fresh connection, while the approval store remains shared.
        fixture(f.permissions, f.approvals, "session-two", "owner-two") { next ->
            next.write(".env")
            next.read("new-owner", "/fixture/project/.env")
            assertEquals("owner-two", next.pending().approvalSessionId)
            next.approvals.resolve(false); assertDenied(next.reply("new-owner"))
        }
    }

    @Test(timeout = 8_000) fun cancellationWithdrawsApprovalAndSendsOnlyCancelledResponse() = fixture { f ->
        f.write(".env"); f.read("cancelled", "/fixture/project/.env")
        val approval = f.pending(); f.rpc.cancelTurn("session-one")
        val response = f.reply("cancelled"); assertDenied(response)
        assertEquals(-32800, response["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertFalse(f.approvals.resolve(approval.id, ApprovalResolution.ALLOW_ONCE))
        withTimeout(2_000) { while (f.approvals.pending.value != null || f.rpc.hasPendingClientCalls) delay(5) }
        assertEquals(1, f.replies.count { it["id"]?.jsonPrimitive?.contentOrNull == "cancelled" })
    }

    @Test(timeout = 8_000) fun policyDenyAfterReadBeforeWriterPublicationSuppressesContent() = fixture { f ->
        f.write("notes.txt")
        f.whilePublicationIsQueued {
            f.read("queued-deny", "/fixture/project/notes.txt")
            f.awaitQueuedPublication(); f.deny("notes.txt")
        }
        assertDenied(f.reply("queued-deny"))
    }

    @Test(timeout = 8_000) fun rejectedOwnerRebindAfterReadBeforeWriterPublicationSuppressesContent() = fixture { f ->
        f.write("notes.txt")
        f.whilePublicationIsQueued {
            f.read("queued-owner", "/fixture/project/notes.txt")
            f.awaitQueuedPublication()
            assertTrue(runCatching { f.rpc.bindSession("session-one", "owner-one") }.isFailure)
        }
        assertDenied(f.reply("queued-owner"))
        assertEquals(1, f.replies.count { it["id"]?.jsonPrimitive?.contentOrNull == "queued-owner" })
    }

    @Test(timeout = 8_000) fun outOfWorkspaceReadNeverPromptsOrReturnsContent() = fixture { f ->
        f.read("outside", "/etc/fixture-do-not-read")
        assertDenied(f.reply("outside")); assertNull(f.approvals.pending.value)
    }
}
