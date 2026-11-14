package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** A blocked pipe deliberately ignores interruption; only its process owner can release it. */
class McpStdioDeadlineR43Test {
    @Test(timeout = 10_000)
    fun cancellationDuringBlockedInitializeWriteClosesTheOwnedProcess() = runBlocking {
        checkCancellation("initialize", blockFlush = false)
    }

    @Test(timeout = 10_000)
    fun cancellationDuringBlockedInitializedNotificationFlushClosesTheOwnedProcess() = runBlocking {
        checkCancellation("notifications/initialized", blockFlush = true)
    }

    @Test(timeout = 10_000)
    fun cancellationDuringBlockedServerPingReplyClosesTheOwnedProcess() = runBlocking {
        checkCancellation("peer-ping", blockFlush = false, serverPing = true)
    }

    private suspend fun checkCancellation(method: String, blockFlush: Boolean, serverPing: Boolean = false) {
        withFixture(BlockedProcess(method, blockFlush, serverPing)) { manager, process, scope ->
            val request = scope.async { manager.listTools("local") }
            assertTrue("Fixture must reach the blocked pipe", process.blocked.await(2, TimeUnit.SECONDS))
            request.cancel()
            withTimeout(1_500) { request.join() }
            assertTrue(request.isCancelled)
            assertTrue("Cancellation must close its owned process", process.closeEntered.await(1, TimeUnit.SECONDS))
            assertEquals(1, process.closeCalls.get())
        }
    }

    @Test(timeout = 10_000)
    fun discoveryDeadlineIncludesAnUninterruptibleWrite() = runBlocking {
        withFixture(BlockedProcess("server/discover"), modern = true) { manager, process, scope ->
            val started = System.nanoTime()
            val request = scope.async { runCatching { manager.listTools("local") } }
            assertTrue(process.blocked.await(2, TimeUnit.SECONDS))
            val failure = withTimeout(6_500) { request.await().exceptionOrNull() }
            assertNotNull("A server that never reads must time out", failure)
            assertTrue(failure!!.message.orEmpty(), failure.message.orEmpty().contains("timed out"))
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("The existing five-second discovery budget must be retained: $elapsedMs", elapsedMs in 4_500..6_500)
            assertTrue(process.closeEntered.await(1, TimeUnit.SECONDS))
        }
    }

    @Test(timeout = 10_000)
    fun discoveryResponseOnlyGetsTheBudgetLeftAfterWriting() = runBlocking {
        withFixture(BlockedProcess("server/discover", ignoreDiscover = true), modern = true) { manager, process, scope ->
            val started = System.nanoTime()
            val request = scope.async { runCatching { manager.listTools("local") } }
            assertTrue(process.blocked.await(2, TimeUnit.SECONDS))
            scope.launch { delay(4_000); process.release.countDown() }
            val failure = withTimeout(6_500) { request.await().exceptionOrNull() }
            assertTrue(failure?.message.orEmpty(), failure?.message.orEmpty().contains("timed out"))
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("Writing must not restart the response budget: $elapsedMs", elapsedMs in 4_500..6_500)
            assertTrue(process.closeEntered.await(1, TimeUnit.SECONDS))
        }
    }

    @Test(timeout = 10_000)
    fun shutdownDoesNotFlushAnIdleWriterOrWaitForBlockingProcessClose() = runBlocking {
        val process = BlockedProcess(null, holdProcessClose = true)
        withFixture(process) { manager, _, scope ->
            assertTrue(manager.listTools("local").contains("counter"))
            process.blockAllFlushes.set(true)
            val closing = scope.async { McpManager.shutdownSessions(process.root, process.host) }
            withTimeout(1_500) { closing.await() }
            assertTrue("Owned process close must be dispatched before any stream flush", process.closeEntered.await(1, TimeUnit.SECONDS))
            assertEquals("Shutdown must never flush pending protocol output", 1L, process.blocked.count)
            assertEquals(1, process.closeCalls.get())
        }
    }

    private suspend fun withFixture(
        process: BlockedProcess,
        modern: Boolean = false,
        block: suspend (McpManager, BlockedProcess, CoroutineScope) -> Unit,
    ) {
        val root = Files.createTempDirectory("droide-mcp-r43-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        process.root = root
        val host = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command: String) = command
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?) = process
        }
        process.host = host
        root.resolve(".droide").mkdirs()
        root.resolve(".droide/mcp.json").writeText(
            """{"servers":{"local":{"command":"fake-mcp","protocol":"${if (modern) "modern-2026" else "legacy-2025"}"}}}"""
        )
        try { block(McpManager(root, processHost = host), process, scope) }
        finally {
            process.release.countDown()
            process.releaseClose.countDown()
            scope.cancel()
            McpManager.shutdownSessions(root, host)
            withTimeout(2_000) { scope.coroutineContext[Job]!!.join() }
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    private class BlockedProcess(
        private val blockedMethod: String?,
        private val blockFlush: Boolean = false,
        private val serverPing: Boolean = false,
        private val holdProcessClose: Boolean = false,
        private val ignoreDiscover: Boolean = false,
    ) : HostedStdioProcess {
        lateinit var root: java.io.File
        lateinit var host: StdioProcessHost
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(if (holdProcessClose) 1 else 0)
        val closeCalls = AtomicInteger()
        val blockAllFlushes = AtomicBoolean(false)
        private val alive = AtomicBoolean(true)
        private val incoming = ArrayDeque<Int>()
        private val buffer = ByteArrayOutputStream()
        private var pendingMethod = ""
        override val stdin = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(bytes: ByteArray, off: Int, len: Int) {
                val text = bytes.copyOfRange(off, off + len).toString(Charsets.UTF_8)
                if (!blockFlush && blockedMethod != null && text.contains(blockedMethod)) holdPipe()
                if (!alive.get()) throw IOException("Owned process is closed")
                buffer.write(bytes, off, len)
                pendingMethod = text
            }
            override fun flush() {
                if (blockAllFlushes.get() || (blockFlush && blockedMethod != null && pendingMethod.contains(blockedMethod))) holdPipe()
                val text = buffer.toString("UTF-8")
                buffer.reset()
                text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                    val request = Json.parseToJsonElement(line).jsonObject
                    val method = request["method"]?.jsonPrimitive?.content ?: return@forEach
                    val id = request["id"] ?: return@forEach
                    if (method == "server/discover" && ignoreDiscover) return@forEach
                    val response = when (method) {
                        "initialize" -> """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-11-25"}}"""
                        "tools/list" -> if (serverPing) """{"jsonrpc":"2.0","id":"peer-ping","method":"ping"}"""
                            else """{"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"counter","inputSchema":{"type":"object"}}]}}"""
                        else -> error("Unexpected fixture request: $method")
                    }
                    synchronized(incoming) { (response + "\n").toByteArray().forEach { incoming.addLast(it.toInt() and 255) } }
                }
            }
        }
        override val stdout = object : InputStream() {
            override fun available(): Int = synchronized(incoming) { incoming.size }
            override fun read(): Int = synchronized(incoming) { if (incoming.isEmpty()) -1 else incoming.removeFirst() }
        }
        override val stderr = ByteArrayInputStream(byteArrayOf())
        override val isAlive: Boolean get() = alive.get()
        override val exitCode: Int? get() = if (alive.get()) null else 143
        override suspend fun awaitExit() = 143
        override fun close() {
            closeCalls.incrementAndGet()
            closeEntered.countDown()
            awaitUninterruptibly(releaseClose)
            alive.set(false)
            release.countDown()
        }
        private fun holdPipe() {
            blocked.countDown()
            awaitUninterruptibly(release)
        }
        private fun awaitUninterruptibly(latch: CountDownLatch) {
            while (true) try { latch.await(); return } catch (_: InterruptedException) { /* models an OS pipe */ }
        }
    }
}
