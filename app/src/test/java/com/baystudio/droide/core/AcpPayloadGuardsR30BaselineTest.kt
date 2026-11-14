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

/** Tiny current-API decoder baselines. No provider, shell, PTY, OS process, or network. */
class AcpPayloadGuardsR30BaselineTest {
    private class Peer(private val root: File) : AutoCloseable {
        private val alive = AtomicBoolean(true)
        private val outbound = Channel<JsonObject>(16)
        private val serverOutput = PipedOutputStream()
        private val clientOutput = PipedInputStream(serverOutput, 4_096)
        private val captured = ByteArrayOutputStream()
        private val clientInput = object : OutputStream() {
            @Synchronized override fun write(value: Int) { captured.write(value) }
            @Synchronized override fun write(bytes: ByteArray, offset: Int, length: Int) {
                captured.write(bytes, offset, length)
            }
            @Synchronized override fun flush() {
                val text = captured.toString("UTF-8")
                if (text.isEmpty()) return
                check(text.endsWith('\n')) { "Fixture expects a complete NDJSON write" }
                captured.reset()
                text.lineSequence().filter { it.isNotBlank() }.forEach {
                    check(outbound.trySend(Json.parseToJsonElement(it).jsonObject).isSuccess)
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
        val mapper = WorkspacePathMapper(root, root.canonicalPath)
        val host = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command: String) = error("No executable resolution in fixture")
            override suspend fun pathMapper() = mapper
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?) =
                error("No process launch in fixture")
        }
        val io = object : AgentWorkspaceIo {
            override val checksumCommand = "unused"
            override fun requirePath(path: String) = Unit
            override suspend fun refresh() = Unit
            override suspend fun shellBounded(command: String, maxOutputBytes: Int): BridgeShellResult = error("No shell in fixture")
            override suspend fun pull(path: String, output: File) = error("No file transfer in fixture")
            override suspend fun push(input: File, path: String) = error("No file transfer in fixture")
        }
        suspend fun next(method: String): JsonObject = withTimeout(1_500) {
            outbound.receive().also { assertEquals(method, it["method"]!!.jsonPrimitive.content) }
        }
        fun respond(request: JsonObject, stop: String = "end_turn") = emit(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", request.getValue("id"))
            put("result", buildJsonObject { put("stopReason", stop) })
        })
        fun text(text: String, session: String = "session_one") = emit(buildJsonObject {
            put("jsonrpc", "2.0"); put("method", "session/update")
            put("params", buildJsonObject {
                put("sessionId", session)
                put("update", buildJsonObject {
                    put("sessionUpdate", "agent_message_chunk")
                    put("content", buildJsonObject { put("type", "text"); put("text", text) })
                })
            })
        })
        fun raw(bytes: ByteArray) {
            check(bytes.size < 1_024)
            serverOutput.write(bytes); serverOutput.flush()
        }
        private fun emit(message: JsonObject) = raw((message.toString() + "\n").toByteArray())
        override fun close() {
            if (alive.compareAndSet(true, false)) {
                runCatching { serverOutput.close() }; runCatching { clientOutput.close() }
                outbound.close()
            }
        }
    }

    private fun prompt(label: String) = buildJsonObject {
        put("sessionId", "session_one")
        put("prompt", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", label) }) })
    }
    private fun fixture(block: suspend CoroutineScope.(Peer, AcpNdjsonConnection) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("droide-r30-acp-boundary-").toFile()
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val transportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t -> uncaught += t })
        val peer = Peer(root)
        val rpc = AcpNdjsonConnection(peer.process, FileRepository(root), peer.mapper, peer.host,
            ApprovalManager(), PermissionEngine(), transportScope, peer.io)
        try {
            rpc.bindSession("session_one", "approval_one")
            withTimeout(5_000) { block(peer, rpc) }
        } finally {
            rpc.close(); peer.close()
            withTimeout(2_000) { transportScope.coroutineContext.job.cancelAndJoin() }
            root.deleteRecursively()
            assertTrue("Protocol jobs escaped the transport boundary: $uncaught", uncaught.isEmpty())
        }
    }

    private fun nestedReply(request: JsonObject, arrays: Int): ByteArray =
        ("{\"jsonrpc\":\"2.0\",\"id\":" + request.getValue("id") +
            ",\"result\":{\"nested\":" + "[".repeat(arrays) + "0" + "]".repeat(arrays) + "}}\n").toByteArray()

    @Test(timeout = 10_000) fun depth129MustFailBeforeDecodingAndRetireTransport() = fixture { peer, rpc ->
        val response = async { runCatching { rpc.request("fixture/request", JsonObject(emptyMap()), 1_500) } }
        val request = peer.next("fixture/request")
        peer.raw(nestedReply(request, 127)) // Root object + result object + 127 arrays = depth 129.
        assertTrue("An ACP payload beyond the supported JSON depth must be rejected", response.await().isFailure)
        withTimeout(1_000) { while (peer.process.isAlive) delay(1) }
        assertFalse(peer.process.isAlive)
    }

    @Test(timeout = 10_000) fun depth128RemainsAccepted() = fixture { peer, rpc ->
        val response = async { rpc.request("fixture/request", JsonObject(emptyMap()), 1_500) }
        val request = peer.next("fixture/request")
        peer.raw(nestedReply(request, 126))
        assertTrue(response.await()["nested"] is JsonArray)
        assertTrue(peer.process.isAlive)
    }

    @Test(timeout = 10_000) fun malformedUtf8MustNotSilentlyBecomeReplacementText() = fixture { peer, rpc ->
        val response = async { runCatching { rpc.request("fixture/request", JsonObject(emptyMap()), 1_500) } }
        val request = peer.next("fixture/request")
        val prefix = ("{\"jsonrpc\":\"2.0\",\"id\":" + request.getValue("id") + ",\"result\":{\"text\":\"").toByteArray()
        peer.raw(prefix + byteArrayOf(0xc3.toByte(), 0x28) + "\"}}\n".toByteArray())
        assertTrue("Invalid UTF-8 must fail explicitly instead of changing JSON text", response.await().isFailure)
        withTimeout(1_000) { while (peer.process.isAlive) delay(1) }
        assertFalse(peer.process.isAlive)
    }

    @Test(timeout = 10_000) fun validUtf8QuotedBracketsAndOrdinaryBannerStillWork() = fixture { peer, rpc ->
        val response = async { rpc.request("fixture/request", JsonObject(emptyMap()), 1_500) }
        val request = peer.next("fixture/request")
        val expected = "caf\u00e9 \ud83d\udc3e " + "[".repeat(140)
        peer.raw("CLI preparing\n".toByteArray())
        peer.raw((buildJsonObject {
            put("jsonrpc", "2.0"); put("id", request.getValue("id"))
            put("result", buildJsonObject { put("text", expected) })
        }.toString() + "\n").toByteArray())
        assertEquals(expected, response.await()["text"]!!.jsonPrimitive.content)
        assertTrue(peer.process.isAlive)
    }
}
