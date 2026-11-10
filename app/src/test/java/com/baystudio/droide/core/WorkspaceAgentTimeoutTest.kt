package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceAgentTimeoutTest {
    @Test(timeout = 10_000) fun openCodeCanLoadModelsWithoutLoginEvenWhenOptionalAuthenticationIsAdvertised() = withFixture { root, scope -> runBlocking {
        val host = SlowHost(root, advertiseAuth = true)
        try {
            val boot = start(WorkspaceAgentPluginCatalog.entries.first { it.id == "opencode" }, root, scope, host)
            assertEquals("sess_fixture", boot.sessionId)
            assertEquals("opencode/public-fixture", boot.configuration.currentModelId)
            assertEquals(listOf("opencode/public-fixture"), boot.configuration.models.map { it.id })
            assertEquals(listOf("initialize", "session/new"), host.methods.toList())
            assertTrue(boot.process.isAlive)
            boot.rpc.close(); boot.process.close()
        } finally { host.close() }
    } }

    @Test(timeout = 10_000) fun selectedProviderCredentialRequirementIsPropagatedWithoutBypass() = withFixture { root, scope -> runBlocking {
        val host = SlowHost(root, advertiseAuth = true, requireProviderCredentials = true)
        try {
            try {
                start(WorkspaceAgentPluginCatalog.entries.first { it.id == "opencode" }, root, scope, host)
                fail("Provider credential requirement was bypassed")
            } catch (failure: IllegalStateException) {
                assertTrue(failure.message.orEmpty().contains("Selected provider requires credentials"))
            }
            assertEquals(listOf("initialize", "session/new"), host.methods.toList())
            assertFalse(host.process?.isAlive ?: true)
        } finally { host.close() }
    } }

    @Test(timeout = 55_000) fun allFiveBootstrapSessionsSurviveAResponseAfterTheFormerThirtySecondLimit() = withFixture { root, scope -> runBlocking {
        val hosts = WorkspaceAgentPluginCatalog.entries.map { SlowHost(root, initializeDelayMs = 31_000) }
        try {
            val started = System.nanoTime()
            val bootstraps = withTimeout(45_000) { WorkspaceAgentPluginCatalog.entries.zip(hosts).map { (spec, host) ->
                async(Dispatchers.IO) { start(spec, root, scope, host) }
            }.awaitAll() }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 31_000)
            assertEquals(5, bootstraps.size)
            bootstraps.forEach { assertEquals("sess_fixture", it.sessionId); assertTrue(it.process.isAlive) }
            bootstraps.forEach { it.rpc.close(); it.process.close() }
        } finally { hosts.forEach { it.close() } }
    } }

    @Test(timeout = 25_000) fun modeControlCanWaitPastTenSecondsAndPromptRemainsCancellable() = withFixture { root, scope -> runBlocking {
        val host = SlowHost(root, modeDelayMs = 11_100)
        try {
            val boot = start(WorkspaceAgentPluginCatalog.entries.first(), root, scope, host)
            val started = System.nanoTime()
            boot.rpc.request("session/set_mode", buildJsonObject { put("sessionId", boot.sessionId); put("modeId", "agent") }, AgentTimeoutPolicy.CLI_CONTROL_MS)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 11_100)
            val job = async { boot.rpc.request("session/prompt", buildJsonObject { put("sessionId", boot.sessionId) }, 0) }
            withTimeout(3_000) { host.promptSeen.await() }
            withTimeout(2_000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            boot.rpc.close(); boot.process.close()
        } finally { host.close() }
    } }

    @Test(timeout = 10_000) fun cancellingSlowBootstrapReapsTheExactOwnedTransport() = withFixture { root, scope -> runBlocking {
        val host = SlowHost(root, initializeDelayMs = 60_000)
        try {
            val job = async(Dispatchers.IO) { start(WorkspaceAgentPluginCatalog.entries.last(), root, scope, host) }
            withTimeout(3_000) { host.initializeSeen.await() }
            withTimeout(2_000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertFalse(host.process?.isAlive ?: true)
        } finally { host.close() }
    } }

    private suspend fun start(spec: WorkspaceAgentPluginSpec, root: File, scope: CoroutineScope, host: SlowHost) =
        WorkspaceAgentSessionBootstrap.start(spec, root, FileRepository(root), host, ApprovalManager(),
            PermissionEngine(), scope, null, {}, object : AgentWorkspaceIo {
                override val checksumCommand = "sha256sum"
                override fun requirePath(path: String) = Unit
                override suspend fun refresh() = Unit
                override suspend fun shellBounded(command: String, maxOutputBytes: Int): BridgeShellResult = error("No workspace execution in bootstrap fixture")
                override suspend fun pull(path: String, output: File) = error("No file transfer in bootstrap fixture")
                override suspend fun push(input: File, path: String) = error("No file transfer in bootstrap fixture")
            })

    private fun withFixture(block: (File, CoroutineScope) -> Unit) {
        val root = Files.createTempDirectory("droide-acp-timeout-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try { block(root, scope) } finally { scope.cancel(); PathSecurity.deleteTreeNoFollow(root) }
    }

    private class SlowHost(private val root: File, private val initializeDelayMs: Long = 0, private val modeDelayMs: Long = 0,
        private val advertiseAuth: Boolean = false, private val requireProviderCredentials: Boolean = false,
    ) : StdioProcessHost, AutoCloseable {
        override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
        var process: HostedStdioProcess? = null
        val initializeSeen = CompletableDeferred<Unit>()
        val promptSeen = CompletableDeferred<Unit>()
        val methods = CopyOnWriteArrayList<String>()
        private val worker = AtomicReference<Thread?>()
        override suspend fun resolveExecutable(command: String) = "/fixture/$command"
        override suspend fun pathMapper() = WorkspacePathMapper(root, root.canonicalPath)
        override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
            val clientWriter = PipedOutputStream()
            val serverReader = PipedInputStream(clientWriter, 64 * 1024)
            val serverWriter = PipedOutputStream()
            val clientReader = PipedInputStream(serverWriter, 64 * 1024)
            val alive = AtomicBoolean(true)
            val peer = object : HostedStdioProcess {
                override val stdin = clientWriter
                override val stdout = clientReader
                override val stderr = ByteArrayInputStream(byteArrayOf())
                override val isAlive get() = alive.get()
                override val exitCode get() = if (alive.get()) null else 0
                override suspend fun awaitExit(): Int = 0
                override fun close() {
                    if (alive.compareAndSet(true, false)) {
                        worker.get()?.interrupt()
                        listOf(clientWriter, serverReader, serverWriter, clientReader).forEach { runCatching { it.close() } }
                    }
                }
            }
            process = peer
            Thread({
                try {
                    serverReader.bufferedReader().use { input -> serverWriter.bufferedWriter().use { output ->
                        while (alive.get()) {
                            val request = input.readLine()?.let { Json.parseToJsonElement(it).jsonObject } ?: break
                            val id = request["id"] ?: continue
                            val method = request["method"]?.jsonPrimitive?.content
                            if (method != null) methods += method
                            if (requireProviderCredentials && method == "session/new") {
                                output.write("""{"jsonrpc":"2.0","id":$id,"error":{"code":-32000,"message":"Selected provider requires credentials"}}""")
                                output.newLine(); output.flush(); continue
                            }
                            val result = when (method) {
                                "initialize" -> {
                                    initializeSeen.complete(Unit)
                                    if (initializeDelayMs > 0) Thread.sleep(initializeDelayMs)
                                    if (advertiseAuth)
                                        """{"protocolVersion":1,"agentCapabilities":{"loadSession":true},"authMethods":[{"id":"connect-provider","name":"Connect provider"}]}"""
                                    else """{"protocolVersion":1,"agentCapabilities":{"loadSession":true}}"""
                                }
                                "session/new", "session/load" -> if (advertiseAuth)
                                    """{"sessionId":"sess_fixture","models":{"currentModelId":"opencode/public-fixture","availableModels":[{"modelId":"opencode/public-fixture","name":"Public fixture"}]}}"""
                                    else """{"sessionId":"sess_fixture"}"""
                                "session/set_mode" -> { if (modeDelayMs > 0) Thread.sleep(modeDelayMs); "{}" }
                                "session/prompt" -> { promptSeen.complete(Unit); continue }
                                else -> "{}"
                            }
                            output.write("""{"jsonrpc":"2.0","id":$id,"result":$result}"""); output.newLine(); output.flush()
                        }
                    } }
                } catch (_: Exception) { /* Closing/interrupting an owned fixture is expected on cancellation. */ }
            }, "droide-slow-acp-test").apply { isDaemon = true; worker.set(this); start() }
            return peer
        }
        override fun close() {
            process?.close()
            worker.get()?.join(2_000)
            check(worker.get()?.isAlive != true) { "Owned ACP fixture worker did not stop" }
        }
    }
}
