package com.baystudio.droide.core

import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DebugShutdownCancellationRegressionTest {
    @Test fun closingTransportReportsSessionFailureWithoutCancellingCaller() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val host = DebugProtocolFixture()
        val process = DapProcess(scope, File("."), listOf("adapter"), processHost = host)
        try {
            process.start()
            val pending = process.requestAsync("threads")
            withTimeout(3000) { host.requests.receive() }
            process.close()
            val failure = runCatching { pending.await() }.exceptionOrNull()
            assertNotNull(failure)
            assertFalse("Transport closure must not masquerade as caller cancellation", failure is CancellationException)
            assertTrue(scope.isActive)
            assertTrue(currentCoroutineContext().isActive)
        } finally { process.close(); host.close(); scope.cancel() }
    }

    @Test fun silentDisconnectTimesOutButStopCompletesAndReleasesTransport() = runBlocking {
        withDebugger { debugger, host, _ ->
            withTimeout(5000) { debugger.stop() }
            assertEquals(DebugState.IDLE, debugger.state.value)
            assertTrue(host.closed.get())
            assertTrue(currentCoroutineContext().isActive)
        }
    }

    @Test fun userCancellationDuringDisconnectStillCleansWithoutCancellingIde() = runBlocking {
        withDebugger { debugger, host, disconnect ->
            val stopping = launch { debugger.stop() }
            withTimeout(3000) { disconnect.await() }
            stopping.cancelAndJoin()
            assertTrue(stopping.isCancelled)
            assertEquals(DebugState.IDLE, debugger.state.value)
            assertTrue(host.closed.get())
            assertTrue(currentCoroutineContext().isActive)
        }
    }

    @Test(timeout = 10_000) fun stopReturnsOnlyAfterOwnedLeaseCleanupCompletes() = runBlocking {
        val cleanup = CompletableDeferred<Int>()
        withDebugger(cleanup) { debugger, host, _ ->
            val stopping = async { debugger.stop() }
            withTimeout(4_000) { while (!host.closed.get()) delay(10) }
            assertFalse(stopping.isCompleted)
            cleanup.complete(143)
            stopping.await()
            assertEquals(DebugState.IDLE, debugger.state.value)
        }
    }

    @Test(timeout = 10_000) fun failedOwnedCleanupIsReportedInsteadOfSuccessfulStop() = runBlocking {
        val cleanup = CompletableDeferred<Int>()
        withDebugger(cleanup) { debugger, _, _ ->
            cleanup.completeExceptionally(IllegalStateException("Owned lease failed"))
            val failure = runCatching { debugger.stop() }.exceptionOrNull()
            assertEquals("Debug adapter cleanup incomplete", failure?.message)
            assertEquals(DebugState.ERROR, debugger.state.value)
            assertTrue(debugger.status.value.contains("Owned lease failed"))
        }
    }

    @Test(timeout = 10_000) fun cancelledJavascriptStartupAwaitsServerBeforeAnyDapSocketWasPublished() = runBlocking {
        val root = Files.createTempDirectory("debug-js-unpublished-").toFile()
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val endpointRead = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Int>()
        val closed = AtomicBoolean(false)
        val input = object : InputStream() {
            private val released = CountDownLatch(1)
            override fun read(): Int {
                endpointRead.complete(Unit)
                released.await()
                return -1
            }
            override fun close() { released.countDown() }
        }
        val host = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command: String) = "/usr/bin/$command"
            override suspend fun pathMapper() = WorkspacePathMapper(root, "/workspace")
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?) =
                object : HostedStdioProcess {
                    override val stdin = ByteArrayOutputStream()
                    override val stdout = input
                    override val stderr = ByteArrayInputStream(byteArrayOf())
                    override val isAlive get() = !closed.get()
                    override val exitCode: Int? get() = if (closed.get()) 143 else null
                    override suspend fun awaitExit(): Int {
                        cleanupEntered.complete(Unit)
                        return cleanup.await()
                    }
                    override fun close() { closed.set(true); input.close() }
                }
        }
        val manager = DebugManager(scope, FileRepository(root), TerminalManager(scope, root), remoteProcessHost = host)
        val startup = launch(Dispatchers.Default) {
            manager.start(DebugConfiguration(
                "unpublished JS", "launch", listOf("js-debug-adapter", "0", "127.0.0.1"),
                buildJsonObject {}, "test", adapterTransport = DebugAdapterTransport.JAVASCRIPT_DEBUG_TCP_SERVER,
            ))
        }
        try {
            withTimeout(3_000) { endpointRead.await() }
            startup.cancel()
            withTimeout(3_000) { cleanupEntered.await() }
            assertTrue(closed.get())
            assertFalse("Cancelled startup must retain its unpublished server until cleanup settles", startup.isCompleted)
            cleanup.complete(143)
            withTimeout(3_000) { startup.join() }
            manager.stop()
            assertEquals(DebugState.IDLE, manager.state.value)
            assertTrue(scope.isActive)
        } finally {
            cleanup.complete(143)
            input.close()
            startup.cancelAndJoin()
            manager.close()
            scope.cancel()
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    private suspend fun withDebugger(
        cleanup: CompletableDeferred<Int>? = null,
        block: suspend (DebugManager, DebugProtocolFixture, CompletableDeferred<Unit>) -> Unit,
    ) {
        val root = Files.createTempDirectory("debug-stop-cancellation-").toFile()
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val host = DebugProtocolFixture(root)
        val disconnect = CompletableDeferred<Unit>()
        val processHost = if (cleanup == null) host else object : StdioProcessHost by host {
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
                val peer = host.start(argv, environment, resourceLimits)
                return object : HostedStdioProcess by peer {
                    override suspend fun awaitExit(): Int = cleanup.await()
                }
            }
        }
        val manager = DebugManager(scope, FileRepository(root), TerminalManager(scope, root), remoteProcessHost = processHost)
        val adapter = scope.launch {
            for (request in host.requests) {
                when (request["command"]?.jsonPrimitive?.content) {
                    "launch" -> {
                        host.reply(request)
                        host.send(buildJsonObject { put("seq", 100); put("type", "event"); put("event", "initialized") })
                    }
                    "disconnect" -> disconnect.complete(Unit) // Deliberately no response: bounded best-effort shutdown.
                    else -> host.reply(request)
                }
            }
        }
        try {
            manager.start(DebugConfiguration("fixture", "launch", listOf("/usr/bin/adapter"), buildJsonObject {}, "test"))
            block(manager, host, disconnect)
            assertTrue("Debugger cleanup must preserve the IDE owner", scope.isActive)
        } finally { manager.close(); host.close(); adapter.cancel(); scope.cancel(); PathSecurity.deleteTreeNoFollow(root) }
    }
}
