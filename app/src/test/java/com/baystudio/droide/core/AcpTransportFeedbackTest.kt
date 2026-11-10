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

/** Real bounded pipes reproduce log backpressure, blocked input and stale notifications. */
class AcpTransportFeedbackTest {
    private class Peer(private val root: File, inputCapacity: Int = 1_024) : AutoCloseable {
        val clientInput = PipedOutputStream()
        val serverInput = PipedInputStream(clientInput, inputCapacity)
        val serverOutput = PipedOutputStream()
        val clientOutput = PipedInputStream(serverOutput, 1_024)
        val serverError = PipedOutputStream()
        val clientError = PipedInputStream(serverError, 1_024)
        private val alive = AtomicBoolean(true)
        private var worker: Thread? = null
        val failure = AtomicReference<Throwable?>()
        val process = object : HostedStdioProcess {
            override val stdin = clientInput
            override val stdout = clientOutput
            override val stderr = clientError
            override val isAlive get() = alive.get()
            override val exitCode get() = if (alive.get()) null else 0
            override suspend fun awaitExit() = 0
            override fun close() { this@Peer.close() }
        }
        val mapper = WorkspacePathMapper(root, root.canonicalPath)
        val host = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command: String) = command
            override suspend fun pathMapper() = mapper
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?) = process
        }
        val io = object : AgentWorkspaceIo {
            override val checksumCommand = "sha256sum"
            override fun requirePath(path: String) = Unit
            override suspend fun refresh() = Unit
            override suspend fun shellBounded(command: String, maxOutputBytes: Int): BridgeShellResult = error("No execution in transport fixture")
            override suspend fun pull(path: String, output: File) = error("No transfer in transport fixture")
            override suspend fun push(input: File, path: String) = error("No transfer in transport fixture")
        }
        fun run(block: Peer.() -> Unit) {
            worker = Thread({ try { block() } catch (error: Exception) { if (alive.get()) failure.set(error) } }, "droide-acp-transport-test")
                .apply { isDaemon = true; start() }
        }
        fun respond(request: JsonObject, result: String = "{}") {
            serverOutput.write("""{"jsonrpc":"2.0","id":${request["id"]},"result":$result}
""".toByteArray()); serverOutput.flush()
        }
        override fun close() {
            if (alive.compareAndSet(true, false)) {
                worker?.interrupt()
                listOf(clientInput, serverInput, serverOutput, clientOutput, serverError, clientError).forEach { runCatching { it.close() } }
            }
        }
        fun join() { worker?.join(2_000); check(worker?.isAlive != true) }
    }

    private fun fixture(inputCapacity: Int = 1_024, block: suspend CoroutineScope.(Peer, AcpNdjsonConnection) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("droide-acp-feedback-").toFile()
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> uncaught += error })
        val peer = Peer(root, inputCapacity)
        val rpc = AcpNdjsonConnection(peer.process, FileRepository(root), peer.mapper, peer.host, ApprovalManager(), PermissionEngine(), scope, peer.io)
        try { withTimeout(5_000) { block(peer, rpc) }; assertNull(peer.failure.get()) }
        finally {
            rpc.close(); peer.close(); peer.join(); scope.coroutineContext.job.cancelAndJoin(); root.deleteRecursively()
            assertTrue("Protocol tasks escaped their boundary: $uncaught", uncaught.isEmpty())
        }
    }

    @Test(timeout = 8_000) fun stderrIsDrainedBeforeInitializeReplyEvenWhenThePipeFills() = fixture { peer, rpc ->
        peer.run {
            serverError.write("CLI startup diagnostics\n".repeat(12_000).toByteArray()); serverError.flush()
            val request = Json.parseToJsonElement(serverInput.bufferedReader().readLine()).jsonObject
            respond(request, """{"protocolVersion":1}""")
            Thread.sleep(4_000)
        }
        val result = rpc.request("initialize", JsonObject(emptyMap()), 3_000)
        assertEquals(1, result["protocolVersion"]!!.jsonPrimitive.int)
    }

    @Test(timeout = 8_000) fun timeoutIncludesBoundedRedactedDiagnosticsAndIsNotUserCancellation() = fixture { peer, rpc ->
        val logged = CompletableDeferred<Unit>()
        peer.run {
            serverInput.bufferedReader().readLine()
            serverError.write(("startup log\n".repeat(500) + "API_KEY=sk-privatefixture12345\nAuthentication required\n").toByteArray())
            serverError.flush(); logged.complete(Unit)
            Thread.sleep(4_000)
        }
        val request = async { runCatching { rpc.request("initialize", JsonObject(emptyMap()), 700) } }
        logged.await()
        val error = request.await().exceptionOrNull()
        assertTrue(error is AcpRequestTimeoutException)
        assertTrue(error!!.message!!.contains("Authentication required"))
        assertTrue(error.message!!.contains("[redacted]"))
        assertFalse(error.message!!.contains("privatefixture12345"))
        assertTrue(error.message!!.length < 2_200)
    }

    @Test(timeout = 8_000) fun blockedStdinWriteHonorsDeadlineWithoutBlockingTheCallerThread() = fixture(inputCapacity = 8) { peer, rpc ->
        val started = System.nanoTime()
        val result = async { runCatching { rpc.request("initialize", buildJsonObject { put("padding", "x".repeat(32_000)) }, 200) } }
        // A blocking write on the runBlocking event loop would prevent this coroutine from running.
        val pulse = async { delay(20); "responsive" }
        assertEquals("responsive", withTimeout(1_000) { pulse.await() })
        assertTrue(result.await().exceptionOrNull() is AcpRequestTimeoutException)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
        assertFalse(peer.process.isAlive)
        val late = runCatching { withTimeout(300) { rpc.request("after_write_failure", JsonObject(emptyMap()), 0) } }
        assertFalse(late.exceptionOrNull() is TimeoutCancellationException)
    }

    @Test(timeout = 8_000) fun configurationAndTextNotificationsFromAnotherSessionAreIgnored() = fixture { peer, rpc ->
        val updates = CopyOnWriteArrayList<String>()
        rpc.bindSession("owned_session", "approval_owner")
        rpc.configurationListener = { updates += it["currentModelId"]!!.jsonPrimitive.content }
        peer.run {
            val request = Json.parseToJsonElement(serverInput.bufferedReader().readLine()).jsonObject
            listOf("stale_session" to "wrong_model", "owned_session" to "right_model").forEach { (session, model) ->
                serverOutput.write("""{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"$session","update":{"sessionUpdate":"current_model_update","currentModelId":"$model"}}}
""".toByteArray())
            }
            respond(request)
            Thread.sleep(4_000)
        }
        rpc.request("test_barrier", JsonObject(emptyMap()), 3_000)
        assertEquals(listOf("right_model"), updates)
    }
}
