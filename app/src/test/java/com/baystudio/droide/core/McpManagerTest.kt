package com.baystudio.droide.core

import java.nio.file.Files
import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class McpManagerTest {
    private lateinit var root: java.io.File

    @Before fun setUp() {
        root = Files.createTempDirectory("droide-mcp-").toFile()
        root.resolve(".droide").mkdirs()
    }

    @After fun tearDown() {
        PathSecurity.deleteTreeNoFollow(root)
    }

    @Test fun approvalFingerprintChangesWhenReferencedServerScriptChanges() {
        root.resolve("server.sh").writeText("#!/bin/sh\necho one\n")
        root.resolve(".droide/mcp.json").writeText(
            """{"servers":{"local":{"command":"mcp-test-command","args":["server.sh"],"protocol":"auto"}}}"""
        )
        val manager = McpManager(root)
        val before = manager.permissionResource("local", "tool")

        root.resolve("server.sh").writeText("#!/bin/sh\necho two\n")
        val after = manager.permissionResource("local", "tool")

        assertNotEquals(before, after)
    }

    @Test fun reusesStdioSessionAcrossManagersAndRestartsOnConfigChange() = runBlocking {
        val host = FakeMcpHost()
        try {
            root.resolve(".droide/mcp.json").writeText(
                """{"servers":{"local":{"command":"fake-mcp","protocol":"legacy-2025"}}}"""
            )
            McpManager(root, processHost = host).listTools("local")
            McpManager(root, processHost = host).callTool("local", "counter")
            assertEquals(1, host.starts.size)
            assertEquals(McpHealthState.CONNECTED, McpHealthRegistry.observe(root).value.first { it.serverName == "local" }.state)
            assertTrue(host.starts[0].isAlive)

            root.resolve(".droide/mcp.json").writeText(
                """{"servers":{"local":{"command":"fake-mcp","args":["revised"],"protocol":"legacy-2025"}}}"""
            )
            McpManager(root, processHost = host).listTools("local")
            assertEquals(2, host.starts.size)
            withTimeout(2_000) { while (host.starts[0].isAlive) delay(10) }
            assertFalse(host.starts[0].isAlive)
            McpManager.shutdownSessions(root, host)
            withTimeout(2_000) { while (host.starts.any { it.isAlive }) delay(10) }
            assertTrue(host.starts.all { !it.isAlive })
            assertEquals(McpHealthState.CONFIGURED, McpHealthRegistry.observe(root).value.first { it.serverName == "local" }.state)
        } finally {
            McpManager.shutdownSessions(root, host)
        }
    }

    @Test fun remoteAndEnvConfigurationShowUnsupportedReason() {
        val config = root.resolve(".droide/mcp.json")
        config.writeText("""{"servers":{"remote":{"url":"https://example.test/mcp"}}}""")
        assertTrue(McpManager(root).list().isEmpty())
        assertTrue(McpHealthRegistry.observe(root).value.any {
            it.workspaceIssue && it.message == "Unsupported MCP configuration" && it.detail.orEmpty().contains("remote HTTP")
        })

        config.writeText("""{"servers":{"local":{"command":"fake-mcp","env":{"TOKEN":"secret"}}}}""")
        assertTrue(McpManager(root).list().isEmpty())
        assertTrue(McpHealthRegistry.observe(root).value.any {
            it.workspaceIssue && it.detail.orEmpty().contains("env") && !it.detail.orEmpty().contains("secret")
        })
    }

    @Test fun listsResourcesAndTemplatesWithExplicitServerPagination() = runBlocking {
        val host = FakeMcpHost()
        root.resolve(".droide/mcp.json").writeText("""{"servers":{"local":{"command":"fake-mcp","protocol":"legacy-2025"}}}""")
        try {
            val manager = McpManager(root, processHost = host)
            val page = Json.parseToJsonElement(manager.contextOperation("local", "list_resources", buildJsonObject {})).jsonObject
            assertEquals(JsonPrimitive("second-page"), page.getValue("result").jsonObject["nextCursor"])
            val next = Json.parseToJsonElement(manager.contextOperation("local", "list_resources", buildJsonObject { put("cursor", "second-page") })).jsonObject
            assertEquals(JsonPrimitive("fixture://two"), next.getValue("result").jsonObject.getValue("resources").jsonArray[0].jsonObject["uri"])
            assertTrue(manager.contextOperation("local", "list_resource_templates", buildJsonObject {}).contains("uriTemplate"))
            assertEquals(McpHealthState.CONNECTED, McpHealthRegistry.observe(root).value.first { it.serverName == "local" }.state)
            assertEquals(1, host.starts.size)
        } finally { McpManager.shutdownSessions(root, host) }
    }

    @Test fun readsResourceAndGetsPromptThroughTheNegotiatedStdioSession() = runBlocking {
        val host = FakeMcpHost()
        root.resolve(".droide/mcp.json").writeText("""{"servers":{"local":{"command":"fake-mcp","protocol":"legacy-2025"}}}""")
        try {
            val manager = McpManager(root, processHost = host)
            assertTrue(manager.contextOperation("local", "read_resource", buildJsonObject { put("uri", "fixture://one") }).contains("resource body"))
            assertTrue(manager.contextOperation("local", "list_prompts", buildJsonObject {}).contains("review"))
            assertTrue(manager.contextOperation("local", "get_prompt", buildJsonObject { put("name", "review"); put("arguments", buildJsonObject { put("path", "Main.kt") }) }).contains("Review this code"))
            assertTrue(runCatching { manager.contextOperation("local", "get_prompt", buildJsonObject { put("name", "review"); put("arguments", buildJsonObject { put("path", 42) }) }) }.isFailure)
        } finally { McpManager.shutdownSessions(root, host) }
    }

    @Test fun repeatedContextCursorIsRejectedAndOversizedOutputStaysValidJson() = runBlocking {
        val host = FakeMcpHost()
        root.resolve(".droide/mcp.json").writeText("""{"servers":{"local":{"command":"fake-mcp","protocol":"legacy-2025"}}}""")
        try {
            assertTrue(runCatching { McpManager(root, processHost = host).contextOperation("local", "list_resources", buildJsonObject { put("cursor", "repeat") }) }.isFailure)
            val bounded = McpAgentResult.render("local", "2025-11-25", "read_resource", buildJsonObject {
                put("text", "\"\\\n".repeat(20_000))
            })
            val parsed = Json.parseToJsonElement(bounded).jsonObject
            assertEquals(JsonPrimitive(true), parsed["truncated"]); assertTrue(bounded.length <= 10_000)
        } finally { McpManager.shutdownSessions(root, host) }
    }

    @Test fun mcpIsErrorResultIsFailureEvidenceEvenWhenTransportSucceeds() {
        val output = McpAgentResult.render("local", "2025-11-25", "call_tool:test", buildJsonObject {
            put("isError", true); put("content", "server failed")
        })
        assertEquals(AgentToolEvidenceState.FAILED, AgentToolResultSemantics.classify("mcp", output).state)
        assertEquals(AgentToolEvidenceState.FAILED, AgentToolResultSemantics.classify("mcp__fixture__test__0123456789ab", output).state)
    }

    @Test(timeout = 80_000) fun toolCallCanContinuePastTheFormerOneMinuteLimit() = runBlocking {
        val host = FakeMcpHost(toolDelayMs = 61_000)
        root.resolve(".droide/mcp.json").writeText("""{"servers":{"local":{"command":"fake-mcp","protocol":"legacy-2025"}}}""")
        try {
            val started = System.nanoTime()
            assertTrue(McpManager(root, processHost = host).callTool("local", "counter").contains("ok"))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 61_000)
            assertTrue(host.starts.single().isAlive)
        } finally { McpManager.shutdownSessions(root, host) }
    }

    @Test(timeout = 10_000) fun cancellationDuringALongToolCallClosesItsOwnedSessionPromptly() = runBlocking {
        val host = FakeMcpHost(toolDelayMs = 60_000)
        root.resolve(".droide/mcp.json").writeText("""{"servers":{"local":{"command":"fake-mcp","protocol":"legacy-2025"}}}""")
        try {
            val job = async(Dispatchers.IO) { McpManager(root, processHost = host).callTool("local", "counter") }
            assertTrue(host.toolEntered.await(3, TimeUnit.SECONDS))
            withTimeout(2_000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            withTimeout(2_000) { while (host.starts.any { it.isAlive }) delay(10) }
            assertTrue(host.starts.all { !it.isAlive })
        } finally { McpManager.shutdownSessions(root, host) }
    }

    private class FakeMcpHost(private val toolDelayMs: Long = 0) : StdioProcessHost {
        override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
        val starts = mutableListOf<HostedStdioProcess>()
        val toolEntered = CountDownLatch(1)
        override suspend fun resolveExecutable(command: String): String = command
        override suspend fun start(
            argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?,
        ): HostedStdioProcess {
            val toServer = PipedOutputStream()
            val serverInput = PipedInputStream(toServer, 64 * 1024)
            val toClient = PipedOutputStream()
            val clientInput = PipedInputStream(toClient, 64 * 1024)
            val alive = AtomicBoolean(true)
            val worker = AtomicReference<Thread?>()
            val process = object : HostedStdioProcess {
                override val stdin = toServer
                override val stdout = clientInput
                override val stderr = ByteArrayInputStream(byteArrayOf())
                override val isAlive: Boolean get() = alive.get()
                override val exitCode: Int? get() = if (alive.get()) null else 0
                override suspend fun awaitExit(): Int = 0
                override fun close() {
                    if (alive.compareAndSet(true, false)) {
                        worker.get()?.interrupt()
                        runCatching { toServer.close() }
                        runCatching { serverInput.close() }
                        runCatching { toClient.close() }
                        runCatching { clientInput.close() }
                    }
                }
            }
            starts += process
            Thread({
                try {
                    serverInput.bufferedReader().use { input ->
                        toClient.bufferedWriter().use { output ->
                            while (true) {
                                val line = input.readLine() ?: break
                                val request = Json.parseToJsonElement(line).jsonObject
                                val method = request["method"]?.jsonPrimitive?.content.orEmpty()
                                val id = request["id"] ?: continue
                                if (method == "tools/call") {
                                    toolEntered.countDown()
                                    if (toolDelayMs > 0) Thread.sleep(toolDelayMs)
                                }
                                val result = when (method) {
                                    "initialize" -> """{"protocolVersion":"2025-11-25","capabilities":{"tools":{}},"serverInfo":{"name":"fake","version":"1"}}"""
                                    "tools/list" -> """{"tools":[{"name":"counter","inputSchema":{"type":"object"}}]}"""
                                    "tools/call" -> """{"content":[{"type":"text","text":"ok"}]}"""
                                    "resources/list" -> when (request["params"]?.jsonObject?.get("cursor")?.jsonPrimitive?.content) {
                                        "second-page" -> """{"resources":[{"uri":"fixture://two","name":"two"}]}"""
                                        "repeat" -> """{"resources":[],"nextCursor":"repeat"}"""
                                        else -> """{"resources":[{"uri":"fixture://one","name":"one"}],"nextCursor":"second-page"}"""
                                    }
                                    "resources/templates/list" -> """{"resourceTemplates":[{"uriTemplate":"fixture://{name}","name":"fixture"}]}"""
                                    "resources/read" -> """{"contents":[{"uri":"fixture://one","mimeType":"text/plain","text":"resource body"}]}"""
                                    "prompts/list" -> """{"prompts":[{"name":"review"}]}"""
                                    "prompts/get" -> """{"messages":[{"role":"user","content":{"type":"text","text":"Review this code"}}]}"""
                                    else -> "{}"
                                }
                                output.write("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
                                output.newLine()
                                output.flush()
                            }
                        }
                    }
                } catch (_: Exception) {   }
            }, "fake-mcp-test").apply { isDaemon = true; worker.set(this); start() }
            return process
        }
    }
}
