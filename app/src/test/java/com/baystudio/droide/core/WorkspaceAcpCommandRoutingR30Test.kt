package com.baystudio.droide.core

import java.io.*
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Existing Host API plus the separately reviewed fake-IO constructor seam. No command helper API. */
class WorkspaceAcpCommandRoutingR30Test {
    private class Peer(private var currentModeId: String) : AutoCloseable {
        private val alive = AtomicBoolean(true)
        private val serverOutput = PipedOutputStream()
        private val clientOutput = PipedInputStream(serverOutput, 4_096)
        private val captured = ByteArrayOutputStream()
        val requests = CopyOnWriteArrayList<JsonObject>()
        val commandsAtMode = AtomicReference<List<String>?>(null)
        val process = object : HostedStdioProcess {
            override val stdin = object : OutputStream() {
                @Synchronized override fun write(value: Int) { captured.write(value) }
                @Synchronized override fun write(bytes: ByteArray, offset: Int, length: Int) { captured.write(bytes, offset, length) }
                @Synchronized override fun flush() {
                    val text = captured.toString("UTF-8")
                    if (text.isEmpty()) return
                    check(text.endsWith('\n')); captured.reset()
                    text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                        val request = Json.parseToJsonElement(line).jsonObject
                        requests += request
                        when (request["method"]!!.jsonPrimitive.content) {
                            "initialize" -> respond(request, buildJsonObject {
                                put("protocolVersion", 1); put("agentCapabilities", buildJsonObject { put("loadSession", true) })
                            })
                            "session/new" -> {
                                commands("stale_session", listOf("wrong"))
                                commands("session_one", listOf("superseded"))
                                commands("session_one", emptyList())
                                commands("session_one", listOf("compact"))
                                respond(request, buildJsonObject {
                                    put("sessionId", "session_one")
                                    put("modes", buildJsonObject {
                                        put("currentModeId", currentModeId)
                                        put("availableModes", buildJsonArray {
                                            listOf("build", "plan").forEach { id -> add(buildJsonObject { put("id", id); put("name", id) }) }
                                        })
                                    })
                                })
                            }
                            "session/set_mode" -> {
                                check(request["params"]!!.jsonObject["modeId"]!!.jsonPrimitive.content == "plan")
                                currentModeId = "plan"
                                commandsAtMode.getAndSet(null)?.let { commands("session_one", it) }
                                respond(request, JsonObject(emptyMap()))
                            }
                            "session/prompt" -> respond(request, buildJsonObject { put("stopReason", "end_turn") })
                            else -> error("Unexpected command fixture method")
                        }
                    }
                }
            }
            override val stdout = clientOutput
            override val stderr = ByteArrayInputStream(byteArrayOf())
            override val isAlive get() = alive.get()
            override val exitCode get() = if (isAlive) null else 0
            override suspend fun awaitExit() = 0
            override fun close() = this@Peer.close()
        }
        private fun commands(sessionId: String, names: List<String>) = emit(buildJsonObject {
            put("jsonrpc", "2.0"); put("method", "session/update")
            put("params", buildJsonObject {
                put("sessionId", sessionId); put("update", buildJsonObject {
                    put("sessionUpdate", "available_commands_update")
                    put("availableCommands", buildJsonArray { names.forEach { add(buildJsonObject { put("name", it) }) } })
                })
            })
        })
        private fun respond(request: JsonObject, result: JsonObject) = emit(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", request.getValue("id")); put("result", result)
        })
        @Synchronized private fun emit(frame: JsonObject) {
            val bytes = (frame.toString() + "\n").toByteArray(); check(bytes.size < 1_024)
            serverOutput.write(bytes); serverOutput.flush()
        }
        fun lastPrompt(): JsonObject = requests.last { it["method"]!!.jsonPrimitive.content == "session/prompt" }["params"]!!.jsonObject
        override fun close() {
            if (alive.compareAndSet(true, false)) {
                runCatching { serverOutput.close() }; runCatching { clientOutput.close() }
            }
        }
    }

    private fun fixture(initialMode: String = "plan", block: suspend (WorkspaceAgentPluginHost, Peer) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("droide-r30-command-host-").toFile()
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> uncaught += error })
        val peer = Peer(initialMode)
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
                check(maxOutputBytes == 2_000_000); return BridgeShellResult(0, "", "")
            }
            override suspend fun pull(path: String, output: File) = error("No transfers permitted")
            override suspend fun push(input: File, path: String) = error("No transfers permitted")
        }
        val host = WorkspaceAgentPluginHost(root, File(root, "cache"), FileRepository(root), processes,
            ApprovalManager(), PermissionEngine(), scope, io)
        try {
            withTimeout(5_000) { host.prepareSession("opencode", null, {}); block(host, peer) }
            assertEquals(1, starts)
        } finally {
            host.close(); peer.close(); withTimeout(2_000) { scope.coroutineContext.job.cancelAndJoin() }
            root.deleteRecursively(); assertTrue(uncaught.toString(), uncaught.isEmpty())
        }
    }

    private suspend fun WorkspaceAgentPluginHost.turn(prompt: String) =
        runMediaTurn("opencode", prompt, emptyList(), null, WorkspaceAgentMode.PLAN, "session_one", {})

    @Test(timeout = 8_000) fun advertisedEarlyCommandReachesActualPromptAsOneExactRawBlock() = fixture { host, peer ->
        val raw = "/compact  retain \"this\" exactly\nadditional argument"
        host.turn(raw)
        val params = peer.lastPrompt()
        assertEquals("session_one", params["sessionId"]!!.jsonPrimitive.content)
        assertEquals(buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", raw) }) }, params["prompt"])
        assertEquals(listOf("initialize", "session/new", "session/prompt"), peer.requests.map { it["method"]!!.jsonPrimitive.content })
    }

    @Test(timeout = 8_000) fun ordinaryUnknownAndWrongSessionCommandsKeepScopeWrappedText() = fixture { host, peer ->
        for (raw in listOf("normal question", "/unknown", "/wrong", "/compactness", " /compact", "/workspace/file.kt")) {
            host.turn(raw)
            val blocks = peer.lastPrompt()["prompt"]!!.jsonArray
            assertEquals(1, blocks.size)
            assertEquals(AgentRequestScope.forCliPrompt(raw), blocks[0].jsonObject["text"]!!.jsonPrimitive.content)
        }
    }

    @Test(timeout = 8_000) fun modeResponseRevocationIsAuthoritativeAtFinalPromptAdmission() {
        // Each case needs a real Build -> Plan transition; an already-active Plan is not resent.
        fixture(initialMode = "build") { host, peer ->
            peer.commandsAtMode.set(emptyList())
            host.turn("/compact")
            assertEquals(AgentRequestScope.forCliPrompt("/compact"), peer.lastPrompt()["prompt"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
            assertEquals(1, peer.requests.count { it["method"]!!.jsonPrimitive.content == "session/set_mode" })
        }
        fixture(initialMode = "build") { host, peer ->
            peer.commandsAtMode.set(listOf("help"))
            host.turn("/help detail")
            assertEquals("/help detail", peer.lastPrompt()["prompt"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
            assertEquals(1, peer.requests.count { it["method"]!!.jsonPrimitive.content == "session/set_mode" })
        }
    }
}
