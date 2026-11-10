package com.baystudio.droide.core

import java.io.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal class DebugProtocolFixture(private val workspace: File? = null) : StdioProcessHost, AutoCloseable {
    override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
    val closed = AtomicBoolean(false)
    private val serverWriter = PipedOutputStream()
    private val clientReader = PipedInputStream(serverWriter, 65536)
    private val clientWriter = PipedOutputStream()
    private val serverReader = PipedInputStream(clientWriter, 65536)
    private val backendScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writerMutex = Mutex()
    val requests = Channel<JsonObject>(64)
    var stderrFailure = false
    var startGate: CompletableDeferred<Unit>? = null
    val startEntered = CompletableDeferred<Unit>()
    private val process = object : HostedStdioProcess {
        override val stdin: OutputStream get() = clientWriter
        override val stdout: InputStream get() = clientReader
        override val stderr: InputStream = object : InputStream() {
            override fun read(): Int { if (stderrFailure) throw IOException("stderr transport failed"); return -1 }
        }
        override val isAlive get() = !closed.get()
        override val exitCode: Int? get() = if (closed.get()) 0 else null
        override suspend fun awaitExit(): Int { while (!closed.get()) delay(10); return 0 }
        override fun close() = this@DebugProtocolFixture.close()
    }
    override suspend fun resolveExecutable(command: String) = "/usr/bin/$command"
    override suspend fun pathMapper(): WorkspacePathMapper? = workspace?.let { WorkspacePathMapper(it, "/workspace") }
    override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
        startEntered.complete(Unit)
        startGate?.await()
        backendScope.launch {
            try {
                while (isActive) {
                    val message = ContentLengthProtocol.readMessage(serverReader) ?: break
                    requests.send(Json.parseToJsonElement(message).jsonObject)
                }
            } catch (_: Exception) { }
        }
        return process
    }
    suspend fun send(message: JsonObject) = writerMutex.withLock {
        withContext(Dispatchers.IO) { ContentLengthProtocol.writeMessage(serverWriter, message.toString()) }
    }
    suspend fun reply(request: JsonObject, body: JsonObject = buildJsonObject {}) = send(buildJsonObject {
        put("seq", 1000); put("type", "response"); put("success", true)
        put("request_seq", request["seq"]!!); put("command", request["command"]!!); put("body", body)
    })
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        listOf(clientReader, serverReader, serverWriter, clientWriter).forEach { runCatching { it.close() } }
        backendScope.cancel(); requests.close()
    }
}

class DapTransportOwnershipRegressionTest {
    @Test fun stderrFailureDoesNotCancelRegularIdeParentJob() = runBlocking {
        val host = DebugProtocolFixture().apply { stderrFailure = true }
        val parent = Job()
        val scope = CoroutineScope(parent + Dispatchers.Default)
        val dap = DapProcess(scope, File("."), listOf("fake-dap"), processHost = host)
        try {
            dap.start()
            withTimeout(3000) { while (!host.closed.get()) delay(10) }
            assertTrue(parent.isActive)
            assertFalse(dap.isRunning)
        } finally { dap.close(); host.close(); scope.cancel() }
    }

    @Test fun parentCancellationClosesBlockingProtocolStreams() = runBlocking {
        val host = DebugProtocolFixture()
        val parent = Job()
        val scope = CoroutineScope(parent + Dispatchers.Default)
        val dap = DapProcess(scope, File("."), listOf("fake-dap"), processHost = host)
        try {
            dap.start(); parent.cancel()
            withTimeout(3000) { while (!host.closed.get()) delay(10) }
            assertFalse(dap.isRunning)
        } finally { dap.close(); host.close(); scope.cancel() }
    }

    @Test fun reverseRequestHandlerCanAwaitForwardResponseWithoutBlockingReader() = runBlocking {
        val host = DebugProtocolFixture()
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        lateinit var dap: DapProcess
        dap = DapProcess(scope, File("."), listOf("fake-dap"), processHost = host,
            reverseRequestHandler = { _, _ ->
                dap.request("threads", timeoutMs = 3000)
                DapProcess.ReverseResponse(true)
            })
        try {
            dap.start()
            host.send(buildJsonObject { put("seq", 47); put("type", "request"); put("command", "custom") })
            val forward = withTimeout(3000) { host.requests.receive() }
            assertEquals("threads", forward["command"]!!.jsonPrimitive.content)
            host.reply(forward)
            val reverseReply = withTimeout(3000) { host.requests.receive() }
            assertEquals("response", reverseReply["type"]!!.jsonPrimitive.content)
            assertEquals(47, reverseReply["request_seq"]!!.jsonPrimitive.int)
            assertTrue(reverseReply["success"]!!.jsonPrimitive.boolean)
            assertTrue(scope.isActive)
        } finally { dap.close(); host.close(); scope.cancel() }
    }

    @Test fun reverseRequestFloodFailsOwnedTransportRatherThanIdeScope() = runBlocking {
        val host = DebugProtocolFixture()
        val parent = Job()
        val scope = CoroutineScope(parent + Dispatchers.Default)
        val blocked = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val dap = DapProcess(scope, File("."), listOf("fake-dap"), processHost = host,
            reverseRequestHandler = { _, _ -> entered.complete(Unit); blocked.await(); DapProcess.ReverseResponse(true) })
        try {
            dap.start()
            host.send(buildJsonObject { put("seq", 1); put("type", "request"); put("command", "custom") })
            withTimeout(3000) { entered.await() }
            for (index in 2..20) {
                if (host.closed.get()) break
                runCatching { host.send(buildJsonObject { put("seq", index); put("type", "request"); put("command", "custom") }) }
            }
            withTimeout(3000) { while (!host.closed.get()) delay(10) }
            assertTrue(parent.isActive)
        } finally { dap.close(); host.close(); blocked.complete(Unit); scope.cancel() }
    }

    @Test fun cancelledStartupWaiterDoesNotCloseAnotherCallersTransport() = runBlocking {
        val host = DebugProtocolFixture().apply { startGate = CompletableDeferred() }
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val dap = DapProcess(scope, File("."), listOf("fake-dap"), processHost = host)
        try {
            val owner = async { dap.start() }
            host.startEntered.await()
            val waiter = launch(start = CoroutineStart.UNDISPATCHED) { dap.start() }
            waiter.cancelAndJoin()
            host.startGate!!.complete(Unit)
            withTimeout(3000) { owner.await() }
            assertTrue(dap.isRunning)
            assertFalse(host.closed.get())
        } finally { dap.close(); host.close(); scope.cancel() }
    }
}
