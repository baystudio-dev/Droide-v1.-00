package com.baystudio.droide.core

import java.io.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Real loopback transport with a deterministic server fixture, without downloaded toolchains. */
class JavascriptDebugServerTransportTest {
    @Test fun targetsConnectToOneOwnedServerAndClosingRootClosesEveryConnection() = runBlocking {
        val fixture = ServerFixture()
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val host = JavascriptDebugServerProcessHost(fixture, scope)
        try {
            val root = host.start(ARGV, emptyMap())
            val first = host.childSessionHost().start(ARGV, emptyMap())
            val second = host.childSessionHost().start(ARGV, emptyMap())
            assertEquals(1, fixture.starts.get())
            first.close()
            assertTrue(root.isAlive); assertTrue(second.isAlive)
            root.close()
            assertFalse(root.isAlive); assertFalse(second.isAlive)
            assertTrue(fixture.closed.get()); assertTrue(scope.isActive)
        } finally { host.close(); fixture.close(); scope.cancel() }
    }

    @Test fun targetLimitRejectsExtraConnectionsWithoutClosingExistingTargets() = runBlocking {
        val fixture = ServerFixture()
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val host = JavascriptDebugServerProcessHost(fixture, scope)
        try {
            val root = host.start(ARGV, emptyMap())
            val children = (1..8).map { host.childSessionHost().start(ARGV, emptyMap()) }
            try { host.childSessionHost().start(ARGV, emptyMap()); fail("Ninth target was admitted") }
            catch (_: IllegalArgumentException) { }
            assertTrue(root.isAlive); assertTrue(children.all { it.isAlive }); assertEquals(1, fixture.starts.get())
            children.first().close()
            val replacement = host.childSessionHost().start(ARGV, emptyMap())
            assertTrue(replacement.isAlive)
        } finally { host.close(); fixture.close(); scope.cancel() }
    }

    @Test fun publicBindAndChildContextMutationAreRejectedBeforeSpawnOrAttach() = runBlocking {
        val fixture = ServerFixture()
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val host = JavascriptDebugServerProcessHost(fixture, scope)
        try {
            try { host.start(listOf("js-debug-adapter", "0", "0.0.0.0"), emptyMap()); fail() }
            catch (_: IllegalArgumentException) { }
            assertEquals(0, fixture.starts.get())
            val root = host.start(ARGV, emptyMap())
            try { host.childSessionHost().start(ARGV, mapOf("PATH" to "/elsewhere")); fail() }
            catch (_: IllegalArgumentException) { }
            assertTrue(root.isAlive); assertEquals(1, fixture.starts.get())
        } finally { host.close(); fixture.close(); scope.cancel() }
    }

    @Test fun malformedEndpointFailsOwnedServerWithoutCancellingIdeScope() = runBlocking {
        for (announcement in listOf("Debug server listening at 0.0.0.0:1234\n", "Debug server listening at 127.0.0.1:0\n", "x".repeat(4097) + "\n")) {
            val fixture = ServerFixture(announcement)
            val scope = CoroutineScope(Job() + Dispatchers.Default)
            val host = JavascriptDebugServerProcessHost(fixture, scope)
            try {
                try { withTimeout(3000) { host.start(ARGV, emptyMap()) }; fail("Unsafe endpoint was admitted") }
                catch (failure: Exception) { assertFalse(failure is TimeoutCancellationException) }
                assertTrue(fixture.closed.get()); assertTrue(scope.isActive)
            } finally { host.close(); fixture.close(); scope.cancel() }
        }
    }

    @Test fun parentCancellationUnblocksEndpointReaderAndClosesSpawnedServer() = runBlocking {
        val input = ClosingInput()
        val fixture = ServerFixture(input = input)
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val host = JavascriptDebugServerProcessHost(fixture, scope)
        try {
            val startup = async { runCatching { host.start(ARGV, emptyMap()) } }
            withTimeout(3000) { while (input.reads.get() == 0) delay(10) }
            scope.cancel()
            assertTrue(withTimeout(3000) { startup.await() }.isFailure)
            assertTrue(fixture.closed.get()); assertTrue(input.closed.get())
        } finally { host.close(); fixture.close(); scope.cancel() }
    }

    @Test(timeout = 10_000) fun awaitedCleanupRetainsServerAfterRootWasAlreadyClosed() = runBlocking {
        val cleanup = CompletableDeferred<Int>()
        val fixture = ServerFixture(cleanup = cleanup)
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val host = JavascriptDebugServerProcessHost(fixture, scope)
        try {
            val root = host.start(ARGV, emptyMap())
            root.close()
            val stopped = async(start = CoroutineStart.UNDISPATCHED) { host.closeAndAwaitExit() }
            withTimeout(3000) { fixture.exitWaitEntered.await() }
            assertFalse(stopped.isCompleted)
            assertTrue(fixture.closed.get())
            cleanup.complete(143)
            stopped.await()
            assertTrue(scope.isActive)
        } finally { cleanup.complete(143); host.close(); fixture.close(); scope.cancel() }
    }

    @Test(timeout = 10_000) fun awaitedCleanupRetainsServerThatNeverPublishedAnEndpoint() = runBlocking {
        val cleanup = CompletableDeferred<Int>()
        val input = ClosingInput()
        val fixture = ServerFixture(input = input, cleanup = cleanup)
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val host = JavascriptDebugServerProcessHost(fixture, scope)
        try {
            val startup = async { runCatching { host.start(ARGV, emptyMap()) } }
            withTimeout(3000) { while (input.reads.get() == 0) delay(10) }
            host.close()
            assertTrue(withTimeout(3000) { startup.await() }.isFailure)
            val stopped = async(start = CoroutineStart.UNDISPATCHED) { host.closeAndAwaitExit() }
            withTimeout(3000) { fixture.exitWaitEntered.await() }
            assertFalse(stopped.isCompleted)
            cleanup.complete(143)
            stopped.await()
            assertTrue(input.closed.get())
            assertTrue(scope.isActive)
        } finally { cleanup.complete(143); host.close(); fixture.close(); scope.cancel() }
    }

    @Test(timeout = 10_000) fun awaitedCleanupWaitsForLateDelegateSpawnBeforeAcceptingItsExit() = runBlocking {
        val spawn = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Int>()
        val fixture = ServerFixture(cleanup = cleanup, spawnGate = spawn)
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val host = JavascriptDebugServerProcessHost(fixture, scope)
        try {
            val startup = async { runCatching { host.start(ARGV, emptyMap()) } }
            withTimeout(3000) { fixture.spawnEntered.await() }
            val stopped = async(start = CoroutineStart.UNDISPATCHED) { host.closeAndAwaitExit() }
            assertFalse(stopped.isCompleted)
            assertFalse(fixture.closed.get())
            spawn.complete(Unit)
            assertTrue(withTimeout(3000) { startup.await() }.isFailure)
            withTimeout(3000) { fixture.exitWaitEntered.await() }
            assertFalse(stopped.isCompleted)
            assertTrue(fixture.closed.get())
            cleanup.complete(143)
            stopped.await()
            assertEquals(1, fixture.starts.get())
        } finally { spawn.complete(Unit); cleanup.complete(143); host.close(); fixture.close(); scope.cancel() }
    }

    @Test(timeout = 10_000) fun stalledCleanupReportsBudgetExhaustionAndCanBeAwaitedAgain() = runBlocking {
        val cleanup = CompletableDeferred<Int>()
        val fixture = ServerFixture(cleanup = cleanup)
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val host = JavascriptDebugServerProcessHost(fixture, scope)
        try {
            host.start(ARGV, emptyMap())
            val failure = runCatching { host.closeAndAwaitExit(timeoutMs = 50) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure?.message.orEmpty().contains("50ms"))
            assertTrue(fixture.closed.get())
            assertTrue(scope.isActive)
            cleanup.complete(143)
            host.closeAndAwaitExit()
        } finally { cleanup.complete(143); host.close(); fixture.close(); scope.cancel() }
    }

    @Test(timeout = 10_000) fun ownedCleanupFailureIsVisibleWithoutCancellingIdeScope() = runBlocking {
        val cleanup = CompletableDeferred<Int>()
        val fixture = ServerFixture(cleanup = cleanup)
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val host = JavascriptDebugServerProcessHost(fixture, scope)
        try {
            host.start(ARGV, emptyMap())
            val ownedFailure = IllegalStateException("Owned lease cleanup failed")
            cleanup.completeExceptionally(ownedFailure)
            val failure = runCatching { host.closeAndAwaitExit() }.exceptionOrNull()
            // Debug-enabled coroutine recovery can copy the exception; its
            // failure type/cause must survive rather than its object identity.
            assertTrue(failure is IllegalStateException)
            assertTrue(generateSequence(failure) { it.cause }.any { it.message == ownedFailure.message })
            assertTrue(fixture.closed.get())
            assertTrue(scope.isActive)
        } finally { cleanup.complete(143); host.close(); fixture.close(); scope.cancel() }
    }

    private class ClosingInput : InputStream() {
        val reads = AtomicInteger()
        val closed = AtomicBoolean(false)
        private val release = CountDownLatch(1)
        override fun read(): Int { reads.incrementAndGet(); release.await(); return -1 }
        override fun close() { closed.set(true); release.countDown() }
    }

    private class ServerFixture(
        announcement: String? = null,
        input: InputStream? = null,
        private val cleanup: Deferred<Int>? = null,
        private val spawnGate: Deferred<Unit>? = null,
    ) : StdioProcessHost, AutoCloseable {
        override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
        val starts = AtomicInteger()
        val closed = AtomicBoolean(false)
        val spawnEntered = CompletableDeferred<Unit>()
        val exitWaitEntered = CompletableDeferred<Unit>()
        private val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        private val accepted = mutableListOf<Socket>()
        private val accepting = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val stdout = input ?: ByteArrayInputStream((announcement ?: "Debug server listening at 127.0.0.1:${server.localPort}\n").toByteArray())
        init {
            accepting.launch {
                try { while (isActive) { val socket = server.accept(); synchronized(accepted) { accepted += socket } } }
                catch (_: IOException) { }
            }
        }
        override suspend fun resolveExecutable(command: String) = command
        override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
            starts.incrementAndGet()
            spawnEntered.complete(Unit)
            spawnGate?.await()
            return object : HostedStdioProcess {
                override val stdin: OutputStream = ByteArrayOutputStream()
                override val stdout: InputStream get() = this@ServerFixture.stdout
                override val stderr: InputStream = ByteArrayInputStream(byteArrayOf())
                override val isAlive get() = !closed.get()
                override val exitCode: Int? get() = if (closed.get()) 0 else null
                override suspend fun awaitExit(): Int {
                    exitWaitEntered.complete(Unit)
                    cleanup?.let { return it.await() }
                    while (!closed.get()) delay(10)
                    return 0
                }
                override fun close() = this@ServerFixture.close()
            }
        }
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            stdout.close(); server.close()
            synchronized(accepted) { accepted.forEach { it.close() }; accepted.clear() }
            accepting.cancel()
        }
    }
    private companion object { val ARGV = listOf("js-debug-adapter", "0", "127.0.0.1") }
}
