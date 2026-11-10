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
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Required stopReason baseline on the actual Host; tiny fake process/IO only. */
class WorkspaceAcpHostStopReasonTest {
    private class Peer(private val supportsLoad: Boolean = true) : AutoCloseable {
        private val alive = AtomicBoolean(true)
        private val outgoing = Channel<JsonObject>(16)
        private val serverOutput = PipedOutputStream()
        private val clientOutput = PipedInputStream(serverOutput, 4_096)
        private val captured = ByteArrayOutputStream()
        val methods = CopyOnWriteArrayList<String>()
        val loadedIds = CopyOnWriteArrayList<String>()
        private val clientInput = object : OutputStream() {
            @Synchronized override fun write(value: Int) { captured.write(value) }
            @Synchronized override fun write(bytes: ByteArray, offset: Int, length: Int) { captured.write(bytes, offset, length) }
            @Synchronized override fun flush() {
                val text = captured.toString("UTF-8")
                if (text.isEmpty()) return
                check(text.endsWith('\n'))
                captured.reset()
                text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                    val request = Json.parseToJsonElement(line).jsonObject
                    val method = request["method"]!!.jsonPrimitive.content
                    methods += method
                    when (method) {
                        "initialize" -> respond(request, buildJsonObject {
                            put("protocolVersion", 1)
                            put("agentCapabilities", buildJsonObject { put("loadSession", supportsLoad) })
                        })
                        "session/new" -> respond(request, buildJsonObject {
                            put("sessionId", "session_one"); put("modes", planConfiguration().getValue("modes"))
                        })
                        "session/load" -> {
                            loadedIds += request["params"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content
                            respond(request, planConfiguration())
                        }
                        "session/prompt", "session/cancel" -> check(outgoing.trySend(request).isSuccess)
                        else -> error("Unexpected fake ACP request: $method")
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
        suspend fun next(method: String): JsonObject = withTimeout(2_000) {
            while (true) {
                val frame = outgoing.receive()
                val actual = frame["method"]!!.jsonPrimitive.content
                if (actual == method) return@withTimeout frame
                check(actual == "session/cancel") { "Unexpected frame while waiting for $method: $actual" }
            }
            error("Unreachable")
        }
        fun finish(request: JsonObject, stop: String = "end_turn") =
            respond(request, buildJsonObject { put("stopReason", stop) })
        fun finishRaw(request: JsonObject, result: JsonObject) = respond(request, result)
        private fun planConfiguration() = buildJsonObject {
            put("modes", buildJsonObject {
                put("currentModeId", "plan")
                put("availableModes", buildJsonArray { add(buildJsonObject { put("id", "plan"); put("name", "Plan") }) })
            })
        }
        private fun respond(request: JsonObject, result: JsonObject) = emit(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", request.getValue("id")); put("result", result)
        })
        fun text(text: String) = emit(buildJsonObject {
            put("jsonrpc", "2.0"); put("method", "session/update")
            put("params", buildJsonObject {
                put("sessionId", "session_one")
                put("update", buildJsonObject {
                    put("sessionUpdate", "agent_message_chunk")
                    put("content", buildJsonObject { put("type", "text"); put("text", text) })
                })
            })
        })
        @Synchronized private fun emit(value: JsonObject) {
            val bytes = (value.toString() + "\n").toByteArray()
            check(bytes.size < 1_024)
            serverOutput.write(bytes); serverOutput.flush()
        }
        override fun close() {
            if (alive.compareAndSet(true, false)) {
                runCatching { serverOutput.close() }; runCatching { clientOutput.close() }
                outgoing.close()
            }
        }
    }

    private class Processes(private val root: File) : StdioProcessHost, AutoCloseable {
        val peers = CopyOnWriteArrayList<Peer>()
        var supportsLoad = true
        private val created = Channel<Peer>(4)
        override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
        override suspend fun resolveExecutable(command: String) = "/fixture/$command"
        override suspend fun pathMapper() = WorkspacePathMapper(root, root.canonicalPath)
        override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
            check(argv == listOf("/fixture/opencode", "acp"))
            return Peer(supportsLoad).also { peers += it; check(created.trySend(it).isSuccess) }.process
        }
        suspend fun nextPeer(): Peer = withTimeout(2_000) { created.receive() }
        override fun close() { peers.forEach(Peer::close); created.close() }
    }

    private fun fixture(block: suspend CoroutineScope.(WorkspaceAgentPluginHost, Processes, Peer) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("droide-r30-host-boundary-").toFile()
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val ownedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t -> uncaught += t })
        val processes = Processes(root)
        val io = object : AgentWorkspaceIo {
            override val checksumCommand = "fixture-checksum"
            override suspend fun baselineEntries(): Map<String, WorkspaceSyncManifest.Entry> = emptyMap()
            override fun requirePath(path: String) { check(path == root.canonicalPath) }
            override suspend fun refresh() = Unit
            override suspend fun shellBounded(command: String, maxOutputBytes: Int): BridgeShellResult {

                check(command.contains("fixture-checksum"))
                check(maxOutputBytes == 2_000_000)
                return BridgeShellResult(0, "", "")
            }
            override suspend fun pull(path: String, output: File) = error("No transfer permitted")
            override suspend fun push(input: File, path: String) = error("No transfer permitted")
        }
        val host = WorkspaceAgentPluginHost(root, File(root, "cache"), FileRepository(root), processes,
            ApprovalManager(), PermissionEngine(), ownedScope, io)
        try {
            withTimeout(7_000) {
                val config = host.prepareSession("opencode", null, {})
                assertEquals("session_one", config.sessionId)
                block(host, processes, processes.nextPeer())
            }
        } finally {
            host.close(); processes.close()
            withTimeout(2_000) { ownedScope.coroutineContext.job.cancelAndJoin() }
            root.deleteRecursively()
            assertTrue("Uncaught Host/transport task failure: $uncaught", uncaught.isEmpty())
        }
    }

    private suspend fun WorkspaceAgentPluginHost.turn(label: String, event: (WorkspaceAgentEvent) -> Unit = {}) =
        runMediaTurn("opencode", label, emptyList(), null, WorkspaceAgentMode.PLAN, "session_one", event)

    private fun invalidStopReason(result: JsonObject) = fixture { host, _, peer ->
        val turn = async { runCatching { host.turn("first") } }
        val request = peer.next("session/prompt")
        peer.text("PARTIAL"); peer.finishRaw(request, result)
        val outcome = turn.await()
        assertTrue("Missing or non-string stopReason must not be treated as successful completion", outcome.isFailure)
        assertTrue(outcome.exceptionOrNull()!!.message.orEmpty().contains("stop", ignoreCase = true))
        assertFalse("Malformed terminal response must not leave a reusable connection", peer.process.isAlive)
    }

    @Test(timeout = 12_000) fun missingStopReasonCannotBecomeEndTurn() = invalidStopReason(JsonObject(emptyMap()))
    @Test(timeout = 12_000) fun booleanStopReasonCannotBecomeSuccess() = invalidStopReason(buildJsonObject { put("stopReason", true) })

    @Test(timeout = 12_000) fun unknownStringReasonIsPreservedForControllerPolicy() = fixture { host, _, peer ->
        val turn = async { host.turn("first") }
        val request = peer.next("session/prompt")
        peer.text("ANSWER"); peer.finish(request, "future_fixture_reason")
        val result = turn.await()
        assertEquals("future_fixture_reason", result.stopReason)
        assertEquals("ANSWER", result.text)
        assertTrue(peer.process.isAlive)
    }

    @Test(timeout = 12_000) fun explicitEndTurnRemainsSuccessfulAndReusable() = fixture { host, processes, peer ->
        for (label in listOf("FIRST", "SECOND")) {
            val turn = async { host.turn(label) }
            val request = peer.next("session/prompt")
            peer.text(label); peer.finish(request, "end_turn")
            val result = turn.await()
            assertEquals("end_turn", result.stopReason)
            assertEquals(label, result.text)
        }
        assertEquals(1, processes.peers.size)
        assertTrue(peer.process.isAlive)
    }

    @Test(timeout = 12_000) fun acknowledgedSteerStopReasonStillPreservesSession() = fixture { host, processes, peer ->
        val first = async { host.turn("first") }
        val request = peer.next("session/prompt")
        assertTrue(host.requestSteer("opencode", "session_one"))
        peer.text("TAIL"); peer.next("session/cancel"); peer.finish(request, "cancelled")
        assertEquals("cancelled", first.await().stopReason)
        val second = async { host.turn("redirect") }
        val next = peer.next("session/prompt")
        peer.text("REDIRECT"); peer.finish(next)
        assertEquals("REDIRECT", second.await().text)
        assertEquals(1, processes.peers.size)
        assertTrue(peer.process.isAlive)
    }
}
