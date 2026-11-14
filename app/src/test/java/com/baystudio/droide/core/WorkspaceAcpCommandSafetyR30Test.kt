package com.baystudio.droide.core

import java.io.*
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Actual Host checks with synthetic media references and fake ACP requests only. */
class WorkspaceAcpCommandSafetyR30Test {
    private class Peer(private val root: File, private val requestMutation: Boolean) : AutoCloseable {
        private val alive = AtomicBoolean(true)
        private val serverOut = PipedOutputStream()
        private val clientOut = PipedInputStream(serverOut, 4_096)
        private val captured = ByteArrayOutputStream()
        private var pendingPrompt: JsonObject? = null
        val requests = CopyOnWriteArrayList<JsonObject>()
        val clientReplies = CopyOnWriteArrayList<JsonObject>()
        val process = object : HostedStdioProcess {
            override val stdin = object : OutputStream() {
                @Synchronized override fun write(value: Int) { captured.write(value) }
                @Synchronized override fun write(bytes: ByteArray, offset: Int, length: Int) { captured.write(bytes, offset, length) }
                @Synchronized override fun flush() {
                    val text = captured.toString("UTF-8")
                    if (text.isEmpty()) return
                    check(text.endsWith('\n')); captured.reset()
                    text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                        val frame = Json.parseToJsonElement(line).jsonObject
                        val method = (frame["method"] as? JsonPrimitive)?.contentOrNull
                        if (method == null) {
                            clientReplies += frame
                            if (clientReplies.size == 2) respond(requireNotNull(pendingPrompt), buildJsonObject { put("stopReason", "end_turn") })
                        } else {
                            requests += frame
                            when (method) {
                                "initialize" -> respond(frame, buildJsonObject {
                                    put("protocolVersion", 1)
                                    put("agentCapabilities", buildJsonObject { put("promptCapabilities", buildJsonObject { put("image", true); put("embeddedContext", true) }) })
                                })
                                "session/new" -> {
                                    emit(Json.parseToJsonElement("""{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"session_one","update":{"sessionUpdate":"available_commands_update","availableCommands":[{"name":"compact"}]}}}""").jsonObject)
                                    respond(frame, buildJsonObject {
                                        put("sessionId", "session_one")
                                        put("modes", buildJsonObject {
                                            put("currentModeId", "plan")
                                            put("availableModes", buildJsonArray { add(buildJsonObject { put("id", "plan"); put("name", "Plan") }) })
                                        })
                                    })
                                }
                                "session/prompt" -> {
                                    if (requestMutation) {
                                        pendingPrompt = frame
                                        emit(Json.parseToJsonElement("""{"jsonrpc":"2.0","id":"plan-permission","method":"session/request_permission","params":{"sessionId":"session_one","toolCall":{"toolCallId":"write-tool","title":"attempt write","kind":"edit"},"options":[{"optionId":"allow","kind":"allow_once","name":"Allow"},{"optionId":"reject","kind":"reject_once","name":"Reject"}]}}""").jsonObject)
                                        emit(buildJsonObject {
                                            put("jsonrpc", "2.0"); put("id", "plan-write"); put("method", "fs/write_text_file")
                                            put("params", buildJsonObject { put("sessionId", "session_one"); put("path", File(root, "must-not-exist.txt").canonicalPath); put("content", "must never be written") })
                                        })
                                    } else respond(frame, buildJsonObject { put("stopReason", "end_turn") })
                                }
                                else -> error("Unexpected safety-fixture method: $method")
                            }
                        }
                    }
                }
            }
            override val stdout = clientOut
            override val stderr = ByteArrayInputStream(byteArrayOf())
            override val isAlive get() = alive.get()
            override val exitCode get() = if (isAlive) null else 0
            override suspend fun awaitExit() = 0
            override fun close() = this@Peer.close()
        }
        private fun respond(request: JsonObject, result: JsonObject) = emit(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", request.getValue("id")); put("result", result)
        })
        @Synchronized private fun emit(frame: JsonObject) {
            val bytes = (frame.toString() + "\n").toByteArray(); check(bytes.size < 2_048)
            serverOut.write(bytes); serverOut.flush()
        }
        override fun close() { if (alive.compareAndSet(true, false)) { runCatching { serverOut.close() }; runCatching { clientOut.close() } } }
    }

    private fun fixture(requestMutation: Boolean, block: suspend (File, WorkspaceAgentPluginHost, Peer, ApprovalManager) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("droide-r30-command-safety-").toFile()
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> uncaught += error })
        val peer = Peer(root, requestMutation)
        val approvals = ApprovalManager()
        var starts = 0
        val processes = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command: String) = "/fixture/$command"
            override suspend fun pathMapper() = WorkspacePathMapper(root, root.canonicalPath)
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
                check(argv == listOf("/fixture/opencode", "acp")); starts++; check(starts == 1); return peer.process
            }
        }
        val io = object : AgentWorkspaceIo {
            override val checksumCommand = "fixture-checksum"
            override suspend fun baselineEntries(): Map<String, WorkspaceSyncManifest.Entry> = emptyMap()
            override fun requirePath(path: String) { check(path == root.canonicalPath) }
            override suspend fun refresh() = Unit
            override suspend fun shellBounded(command: String, maxOutputBytes: Int): BridgeShellResult {
                check(command.contains("fixture-checksum"))
                return BridgeShellResult(0, "", "")
            }
            override suspend fun pull(path: String, output: File) = error("No real transfer permitted")
            override suspend fun push(input: File, path: String) = error("No real transfer permitted")
        }
        val host = WorkspaceAgentPluginHost(root, File(root, "cache"), FileRepository(root), processes, approvals, PermissionEngine(), scope, io)
        try {
            withTimeout(5_000) { host.prepareSession("opencode", null, {}); block(root, host, peer, approvals) }
            assertEquals(1, starts)
        } finally {
            host.close(); peer.close(); withTimeout(2_000) { scope.coroutineContext.job.cancelAndJoin() }
            root.deleteRecursively(); assertTrue(uncaught.toString(), uncaught.isEmpty())
        }
    }

    @Test(timeout = 8_000) fun nativeCommandWithMediaRejectsBeforeAnyFileReadDecoderOrPrompt() = fixture(false) { _, host, peer, _ ->
        // This deliberately has no backing file. Command admission must reject before media processing.
        val media = ChatMediaRef("fake-media", ".droide/chat-media/fake-media.png", "fake.png", "image/png", 1, 1, Long.MAX_VALUE)
        assertNull(host.mediaAdmissionError("opencode", listOf(media)))
        val error = runCatching { host.runMediaTurn("opencode", "/compact", listOf(media), "fake-owner", WorkspaceAgentMode.PLAN, "session_one", {}) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals("Native CLI commands cannot include attachments. No CLI prompt was sent; send the command separately.", error!!.message)
        assertFalse(peer.requests.any { it["method"]!!.jsonPrimitive.content == "session/prompt" })
        assertTrue(peer.process.isAlive)
    }

    @Test(timeout = 8_000) fun rawNativeCommandStillRejectsPlanPermissionAndDirectFilesystemMutation() = fixture(true) { root, host, peer, approvals ->
        val result = host.runMediaTurn("opencode", "/compact", emptyList(), null, WorkspaceAgentMode.PLAN, "session_one", {})
        val prompt = peer.requests.single { it["method"]!!.jsonPrimitive.content == "session/prompt" }["params"]!!.jsonObject["prompt"]!!.jsonArray
        assertEquals(1, prompt.size)
        assertEquals("/compact", prompt[0].jsonObject["text"]!!.jsonPrimitive.content)
        val permission = peer.clientReplies.single { it["id"]!!.jsonPrimitive.content == "plan-permission" }
        assertEquals("reject", permission["result"]!!.jsonObject["outcome"]!!.jsonObject["optionId"]!!.jsonPrimitive.content)
        val write = peer.clientReplies.single { it["id"]!!.jsonPrimitive.content == "plan-write" }
        assertTrue(write["error"]!!.jsonObject["message"]!!.jsonPrimitive.content.contains("read-only"))
        assertFalse(File(root, "must-not-exist.txt").exists())
        assertTrue(result.changedFiles.isEmpty()); assertNull(approvals.pending.value)
    }
}
