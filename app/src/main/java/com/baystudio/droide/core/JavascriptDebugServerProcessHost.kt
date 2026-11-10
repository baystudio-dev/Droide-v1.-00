package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Each js-debug target needs its own DAP connection to the same owned server. */
internal interface MultiSessionDebugProcessHost : StdioProcessHost {
    fun childSessionHost(): StdioProcessHost
}

internal class JavascriptDebugServerProcessHost(
    private val delegate: StdioProcessHost,
    private val scope: CoroutineScope,
) : MultiSessionDebugProcessHost, AutoCloseable {
    private val startupMutex = Mutex()
    private val hostJob = SupervisorJob(scope.coroutineContext[Job])
    private val hostScope = CoroutineScope(scope.coroutineContext + hostJob)
    private val closed = AtomicBoolean(false)
    private val sockets = linkedSetOf<Socket>()
    @Volatile private var server: HostedStdioProcess? = null
    @Volatile private var ownedServer: HostedStdioProcess? = null
    @Volatile private var port: Int? = null
    private var stdoutDrain: Job? = null
    override val executionScope get() = delegate.executionScope

    init {
        require(delegate.executionScope == ProcessExecutionScope.LOCAL_LINUX_ARM64) { "JavaScript debugger must execute in Ubuntu ARM64" }
        hostScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { close() }
        }
    }
    override suspend fun resolveExecutable(command: String) = delegate.resolveExecutable(command)
    override suspend fun pathMapper() = delegate.pathMapper()

    override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess = withContext(Dispatchers.IO) {
        startupMutex.withLock {
            check(!closed.get() && server == null) { "JavaScript debug server already started or closed" }
            require(argv.takeLast(2) == listOf("0", LOOPBACK)) { "JavaScript debug server must request an ephemeral IPv4 loopback port" }
            val process = delegate.start(argv, environment, resourceLimits)
            // Keep ownership even if Stop wins before endpoint publication, or close() has already detached
            // the active server from its sockets.
            ownedServer = process
            synchronized(sockets) {
                if (closed.get() || !hostScope.isActive) {
                    process.close()
                    error("JavaScript debug server was canceled during spawn")
                }
                server = process
            }
            var endpointReader: Deferred<Int>? = null
            try {
                endpointReader = hostScope.async(Dispatchers.IO) { readEndpoint(process.stdout) }
                val endpoint = withTimeout(30_000) { endpointReader.await() }
                currentCoroutineContext().ensureActive()
                check(scope.isActive && process.isAlive && !closed.get()) { "JavaScript debug server exited during startup" }
                port = endpoint
                val connected = connect(root = true)
                stdoutDrain = hostScope.launch(Dispatchers.IO) {
                    try {
                        val buffer = ByteArray(4096)
                        while (isActive && process.stdout.read(buffer) >= 0) { }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { close() }
                }
                connected
            } catch (failure: Throwable) {
                close()
                withContext(NonCancellable) { endpointReader?.cancelAndJoin() }
                throw failure
            }
        }
    }

    override fun childSessionHost(): StdioProcessHost = object : StdioProcessHost {
        override val executionScope get() = this@JavascriptDebugServerProcessHost.executionScope
        override suspend fun resolveExecutable(command: String) = delegate.resolveExecutable(command)
        override suspend fun pathMapper() = delegate.pathMapper()
        override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
            require(environment.isEmpty() && resourceLimits == null) { "Child DAP connection cannot mutate server execution context" }
            return withContext(Dispatchers.IO) { connect(root = false) }
        }
    }

    private fun connect(root: Boolean): HostedStdioProcess {
        val process = server ?: error("JavaScript debug server is unavailable")
        val endpoint = port ?: error("JavaScript debug server did not publish its endpoint")
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.connect(InetSocketAddress(LOOPBACK, endpoint), 3000)
            synchronized(sockets) {
                check(!closed.get() && process.isAlive) { "JavaScript debug server closed during connection" }
                require(sockets.size < MAX_CONNECTIONS) { "Too many JavaScript debug target connections" }
                sockets += socket
            }
            return object : HostedStdioProcess {
                private val connectionClosed = AtomicBoolean(false)
                override val stdin: OutputStream get() = socket.getOutputStream()
                override val stdout: InputStream get() = socket.getInputStream()
                override val stderr: InputStream = if (root) process.stderr else ByteArrayInputStream(byteArrayOf())
                override val isAlive get() = !connectionClosed.get() && !closed.get() && !socket.isClosed && process.isAlive
                override val exitCode get() = process.exitCode
                override suspend fun awaitExit() = process.awaitExit()
                override fun close() {
                    if (!connectionClosed.compareAndSet(false, true)) return
                    synchronized(sockets) { sockets.remove(socket) }
                    runCatching { socket.close() }
                    if (root) this@JavascriptDebugServerProcessHost.close()
                }
            }
        } catch (failure: Throwable) { runCatching { socket.close() }; throw failure }
    }

    private fun readEndpoint(input: InputStream): Int {
        repeat(16) {
            val out = ByteArrayOutputStream(128)
            while (true) {
                val next = input.read()
                check(next >= 0) { "JavaScript debug server closed stdout before binding" }
                if (next == '\n'.code) break
                require(out.size() < 4096 && next in 0x09..0x7e) { "Unsafe JavaScript debug startup output" }
                out.write(next)
            }
            val line = out.toString(Charsets.UTF_8.name()).trim()
            val match = ENDPOINT.matchEntire(line) ?: return@repeat
            return match.groupValues[1].toInt().also { require(it in 1..65535) { "Invalid JavaScript debug port" } }
        }
        error("JavaScript debug server did not announce a private loopback endpoint")
    }

    internal suspend fun closeAndAwaitExit(timeoutMs: Long = 5_000) {
        require(timeoutMs in 1..30_000) { "Invalid debug cleanup timeout" }
        withContext(NonCancellable) {
            close()
            val settled = withTimeoutOrNull(timeoutMs) {

                val owned = startupMutex.withLock { ownedServer }
                owned?.awaitExit()
                true
            }
            check(settled == true) { "JavaScript debug server cleanup did not complete within ${timeoutMs}ms" }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(sockets) { sockets.forEach { runCatching { it.close() } }; sockets.clear() }
        stdoutDrain?.cancel(); stdoutDrain = null
        runCatching { server?.close() }; server = null; port = null
        hostJob.cancel()
    }

    companion object {
        const val MAX_CONNECTIONS = 9
        private const val LOOPBACK = "127.0.0.1"
        private val ENDPOINT = Regex("^Debug server listening at 127\\.0\\.0\\.1:(\\d{1,5})$")
    }
}
