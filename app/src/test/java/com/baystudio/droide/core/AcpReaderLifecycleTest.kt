package com.baystudio.droide.core

import java.io.*
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class AcpReaderLifecycleTest {
    private suspend fun exercise(failRead: Boolean) {
        val root = Files.createTempDirectory("droide-acp-reader-").toFile()
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> uncaught += error })
        val releaseRead = CountDownLatch(1)
        val writeSeen = CompletableDeferred<Unit>()
        val closed = AtomicBoolean(false)
        val input = object : InputStream() {
            override fun read(): Int {
                check(releaseRead.await(3, TimeUnit.SECONDS))
                if (failRead) throw IOException("agent output pipe disconnected")
                return -1
            }
            override fun close() { releaseRead.countDown() }
        }
        val process = object : HostedStdioProcess {
            override val stdin = object : OutputStream() { override fun write(value: Int) { writeSeen.complete(Unit) } }
            override val stdout = input
            override val stderr = ByteArrayInputStream(byteArrayOf())
            override val isAlive get() = !closed.get()
            override val exitCode: Int? = null
            override suspend fun awaitExit() = 1
            override fun close() { closed.set(true); input.close() }
        }
        val mapper = WorkspacePathMapper(root, root.canonicalPath)
        val host = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command: String) = command
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?) = process
            override suspend fun pathMapper() = mapper
        }
        val io = object : AgentWorkspaceIo {
            override val checksumCommand = "sha256sum"
            override fun requirePath(path: String) = Unit
            override suspend fun refresh() = Unit
            override suspend fun shellBounded(command: String, maxOutputBytes: Int): BridgeShellResult = error("No shell in reader fixture")
            override suspend fun pull(path: String, output: File) = error("No transfer in reader fixture")
            override suspend fun push(input: File, path: String) = error("No transfer in reader fixture")
        }
        val rpc = AcpNdjsonConnection(process, FileRepository(root), mapper, host, ApprovalManager(), PermissionEngine(), scope, io)
        try {
            coroutineScope {
                val request = async { runCatching { rpc.request("session/prompt", JsonObject(emptyMap()), 5_000) } }
                withTimeout(2_000) { writeSeen.await() }
                releaseRead.countDown()
                val result = withTimeout(2_000) { request.await() }
                assertTrue(result.isFailure)
                withTimeout(2_000) { while (!closed.get()) delay(10) }
                assertTrue("ACP reader failed outside the protocol boundary: $uncaught", uncaught.isEmpty())
                val late = runCatching { withTimeout(500) { rpc.request("after_disconnect", JsonObject(emptyMap()), 0) } }
                assertTrue(late.isFailure)
                assertFalse("Disconnected requests must fail promptly", late.exceptionOrNull() is TimeoutCancellationException)
            }
        } finally {
            releaseRead.countDown(); runCatching { rpc.close() }; process.close()
            scope.coroutineContext.job.cancelAndJoin(); root.deleteRecursively()
        }
    }

    @Test(timeout = 7_000) fun disconnectedAgentPipeFailsPendingRequestWithoutCrashingTheScope() = runBlocking { exercise(true) }
    @Test(timeout = 7_000) fun agentEofClosesOwnedProcessAndRejectsLaterRequests() = runBlocking { exercise(false) }
}
