package com.baystudio.droide.core

import java.io.*
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real NDJSON pipes exercise capability negotiation and two media prompts on one loaded connection. */
class AcpMediaSessionReuseTest {
    @Test(timeout = 10_000) fun initializedMediaCapabilityAndNativeSessionAreReusedWithoutAnotherBootstrap() = runBlocking {
        val root = Files.createTempDirectory("acp-media-session-").toFile()
        val mapper = WorkspacePathMapper(root, "/workspace")
        val clientIn = PipedOutputStream(); val serverIn = PipedInputStream(clientIn, 4_096)
        val serverOut = PipedOutputStream(); val clientOut = PipedInputStream(serverOut, 4_096)
        val alive = AtomicBoolean(true)
        val started = AtomicInteger()
        val requests = CopyOnWriteArrayList<JsonObject>()
        val workerFailure = AtomicReference<Throwable?>()
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> uncaught += error })
        val process = object : HostedStdioProcess {
            override val stdin = clientIn
            override val stdout = clientOut
            override val stderr = ByteArrayInputStream(byteArrayOf())
            override val isAlive get() = alive.get()
            override val exitCode get() = if (isAlive) null else 0
            override suspend fun awaitExit() = 0
            override fun close() {
                alive.set(false)
                listOf(clientIn, serverIn, serverOut, clientOut).forEach { runCatching { it.close() } }
            }
        }
        val host = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command: String) = command
            override suspend fun pathMapper() = mapper
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
                started.incrementAndGet(); return process
            }
        }
        val io = object : AgentWorkspaceIo {
            override val checksumCommand = "sha256sum"
            override fun requirePath(path: String) = Unit
            override suspend fun refresh() = Unit
            override suspend fun shellBounded(command: String, maxOutputBytes: Int): BridgeShellResult = error("No shell needed for ACP media fixture")
            override suspend fun pull(path: String, output: File) = error("No file transfer needed")
            override suspend fun push(input: File, path: String) = error("No file transfer needed")
        }
        val worker = Thread({
            try {
                serverIn.bufferedReader().use { reader ->
                    while (alive.get()) {
                        val line = reader.readLine() ?: break
                        val request = Json.parseToJsonElement(line).jsonObject
                        requests += request
                        val result = when (request["method"]!!.jsonPrimitive.content) {
                            "initialize" -> """{"protocolVersion":1,"agentCapabilities":{"promptCapabilities":{"image":true,"embeddedContext":true}}}"""
                            "session/new" -> """{"sessionId":"preserved-native-session"}"""
                            "session/prompt" -> """{"stopReason":"end_turn"}"""
                            else -> error("Unexpected bootstrap during media send")
                        }
                        serverOut.write("{\"jsonrpc\":\"2.0\",\"id\":${request["id"]},\"result\":$result}\n".toByteArray())
                        serverOut.flush()
                    }
                }
            } catch (failure: Exception) { if (alive.get()) workerFailure.set(failure) }
        }, "droide-acp-media-test").apply { isDaemon = true; start() }
        var rpc: AcpNdjsonConnection? = null
        try {
            withTimeout(5_000) {
                val boot = WorkspaceAgentSessionBootstrap.start(WorkspaceAgentPluginCatalog.entries.first(), root, FileRepository(root), host,
                    ApprovalManager(), PermissionEngine(), scope, null, {}, io)
                rpc = boot.rpc
                assertEquals(AcpPromptCapabilities(true, true), boot.promptCapabilities)
                val bytes = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aYd0AAAAASUVORK5CYII=")
                File(root, "image.png").writeBytes(bytes)
                repeat(2) { turn ->
                    val prompt = AcpChatMedia.prompt("Turn $turn", listOf("image.png"), emptyList(), root, boot.promptCapabilities)
                    boot.rpc.request("session/prompt", buildJsonObject { put("sessionId", boot.sessionId); put("prompt", prompt) }, 2_000)
                }
                assertEquals(1, started.get())
                assertEquals(listOf("initialize", "session/new", "session/prompt", "session/prompt"), requests.map { it["method"]!!.jsonPrimitive.content })
                requests.filter { it["method"]!!.jsonPrimitive.content == "session/prompt" }.forEach {
                    val params = it["params"]!!.jsonObject
                    assertEquals("preserved-native-session", params["sessionId"]!!.jsonPrimitive.content)
                    val image = params["prompt"]!!.jsonArray[1].jsonObject
                    assertEquals("image", image["type"]!!.jsonPrimitive.content)
                    assertArrayEquals(bytes, Base64.getDecoder().decode(image["data"]!!.jsonPrimitive.content))
                }
                assertNull(workerFailure.get()); assertTrue(uncaught.toString(), uncaught.isEmpty())
            }
        } finally {
            rpc?.close(); process.close(); worker.join(2_000)
            scope.coroutineContext.job.cancelAndJoin()
            assertTrue(PathSecurity.deleteTreeNoFollow(root))
            assertFalse("ACP fixture worker was left running", worker.isAlive)
        }
    }
}
