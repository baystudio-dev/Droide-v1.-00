package com.baystudio.droide.core

import java.io.*
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class McpSelectionSafetyTest {
    @get:Rule val admission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    private class Host : StdioProcessHost {
        override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
        val starts = CopyOnWriteArrayList<List<String>>()
        val methods = CopyOnWriteArrayList<String>()
        var resolve: suspend () -> Unit = {}
        var reply: ((String) -> Unit)? = null
        var respond = false
        override suspend fun resolveExecutable(command: String): String { resolve(); return command }
        override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
            starts += argv.toList()
            if (!respond) throw IOException("No process created by recording fixture")
            val client = PipedInputStream(16_384)
            val output = PipedOutputStream(client)
            val alive = AtomicBoolean(true)
            val input = object : OutputStream() {
                private val frame = ByteArrayOutputStream()
                override fun write(value: Int) {
                    if (value != 10) { frame.write(value); return }
                    val message = Json.parseToJsonElement(frame.toString("UTF-8")).jsonObject
                    frame.reset()
                    val method = message.getValue("method").jsonPrimitive.content
                    methods += method
                    val id = message["id"] ?: return
                    val result = when (method) {
                        "initialize" -> """{"protocolVersion":"2025-11-25"}"""
                        "tools/list" -> """{"tools":[{"name":"counter","inputSchema":{"type":"object"}}]}"""
                        "tools/call" -> """{"content":[{"type":"text","text":"ok"}]}"""
                        else -> "{}"
                    }
                    reply?.invoke(method)
                    output.write("""{"jsonrpc":"2.0","id":$id,"result":$result}""".toByteArray() + byteArrayOf(10))
                    output.flush()
                }
            }
            return object : HostedStdioProcess {
                override val stdin = input
                override val stdout = client
                override val stderr = ByteArrayInputStream(byteArrayOf())
                override val isAlive get() = alive.get()
                override val exitCode: Int? get() = if (alive.get()) null else 0
                override suspend fun awaitExit() = 0
                override fun close() { if (alive.compareAndSet(true, false)) { output.close(); client.close() } }
            }
        }
    }

    private fun configure(root: File, command: String = "fixture-a", protocol: String = "legacy-2025", args: String = "") {
        root.resolve(".droide").mkdirs()
        root.resolve(".droide/mcp.json").writeText("""{"servers":{"local":{"command":"$command","protocol":"$protocol"$args}}}""")
    }

    private fun fixture(block: suspend (File, Host, McpManager) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("mcp-selection-").toFile()
        val host = Host()
        try { withTimeout(10_000) { configure(root); block(root, host, McpManager(root, processHost = host)) } }
        finally {
            McpManager.shutdownSessions(root, host)
            withTimeout(3_000) { McpManager.awaitIoIdleForExit() }
            McpToolRegistry.invalidate(root)
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    @Test fun mutationWhileResolvingExecutableIsRejectedBeforeStart() = fixture { root, host, manager ->
        val selected = manager.select("local")
        host.resolve = { configure(root, "fixture-b") }
        val failure = runCatching { manager.listTools(selected) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(host.starts.isEmpty())
    }

    @Test fun autoProbeCannotUseConfigurationChangedDuringResolution() = fixture { root, host, manager ->
        configure(root, protocol = "auto")
        val selected = manager.select("local")
        host.resolve = { configure(root, "fixture-b", "auto") }
        assertTrue(runCatching { manager.discoverTools(selected) }.isFailure)
        assertTrue(host.starts.isEmpty())
    }

    @Test fun changedScriptArgumentInvalidatesApproval() = fixture { root, host, manager ->
        root.resolve("server.js").writeText("one")
        configure(root, args = """, "args":["server.js"]""")
        val selected = manager.select("local")
        root.resolve("server.js").writeText("two")
        assertTrue(runCatching { manager.callTool(selected, "counter") }.isFailure)
        assertTrue(host.starts.isEmpty())
    }

    @Test fun selectionCannotBeDispatchedByAnotherHost() = fixture { root, host, manager ->
        val other = Host()
        assertTrue(runCatching { McpManager(root, processHost = other).listTools(manager.select("local")) }.isFailure)
        assertTrue(host.starts.isEmpty()); assertTrue(other.starts.isEmpty())
    }

    @Test fun selectionCannotBeDispatchedInAnotherWorkspace() = fixture { root, host, manager ->
        val other = Files.createTempDirectory("mcp-selection-other-").toFile()
        try {
            configure(other)
            assertTrue(runCatching { McpManager(other, processHost = host).listTools(manager.select("local")) }.isFailure)
            assertTrue(host.starts.isEmpty())
        } finally { McpManager.shutdownSessions(other, host); PathSecurity.deleteTreeNoFollow(other) }
    }

    @Test fun selectionOwnsAnUnmodifiableArgumentSnapshot() = fixture { _, _, manager ->
        val server = manager.list().single()
        val args = server.argv.toMutableList()
        val selected = manager.select(server.copy(argv = args))
        args[0] = "changed"
        assertEquals("fixture-a", selected.server.argv.single())
        assertTrue(runCatching { (selected.server.argv as MutableList<String>)[0] = "changed" }.exceptionOrNull() is UnsupportedOperationException)
    }

    @Test fun unchangedSelectionKeepsExistingPermissionScope() = fixture { _, _, manager ->
        val selected = manager.select("local")
        assertEquals(manager.permissionResource("local"), selected.runtimeIdentity)
        assertEquals(manager.permissionResource("local", "counter"), selected.permissionResource("counter"))
        assertEquals(manager.permissionResource("local", "read_resource:digest"), selected.permissionResource("read_resource:digest"))
    }

    @Test fun removedServerCannotUseAnEarlierSelection() = fixture { root, host, manager ->
        val selected = manager.select("local")
        root.resolve(".droide/mcp.json").writeText("""{"servers":{}}""")
        assertTrue(runCatching { manager.discoverTools(selected) }.isFailure)
        assertTrue(host.starts.isEmpty())
    }

    @Test fun currentSelectionCanBeUsedAcrossManagersOfTheSameContext() = fixture { root, host, manager ->
        host.respond = true
        val selected = manager.select("local")
        assertTrue(McpManager(root, processHost = host).callTool(selected, "counter").contains("ok"))
        assertEquals(listOf(listOf("fixture-a")), host.starts.toList())
    }

    @Test fun cancellationDuringResolutionDoesNotStartAServer() = fixture { _, host, manager ->
        val entered = CompletableDeferred<Unit>()
        host.resolve = { entered.complete(Unit); awaitCancellation() }
        val selected = manager.select("local")
        coroutineScope {
            val work = launch { manager.listTools(selected) }
            entered.await(); work.cancelAndJoin()
            assertTrue(work.isCancelled); assertTrue(host.starts.isEmpty())
        }
    }

    @Test fun mutationDuringNegotiationCannotReachTheTool() = fixture { root, host, manager ->
        host.respond = true
        host.reply = { method -> if (method == "initialize") configure(root, "fixture-b") }
        assertTrue(runCatching { manager.callTool(manager.select("local"), "counter") }.isFailure)
        assertEquals(listOf(listOf("fixture-a")), host.starts.toList())
        assertFalse(host.methods.contains("tools/call"))
    }

    @Test fun configurationChangedByProtocolProbeCannotStartTheFollowupSession() = fixture { root, host, manager ->
        configure(root, protocol = "auto")
        host.respond = true
        host.reply = { method -> if (method == "server/discover") configure(root, "fixture-b") }
        val failure = runCatching { manager.listTools(manager.select("local")) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(listOf(listOf("fixture-a")), host.starts.toList())
        assertEquals(listOf("server/discover"), host.methods.toList())
        withTimeout(3_000) { McpManager.awaitIoIdleForExit() }
        host.reply = null
        assertTrue(manager.listTools(manager.select("local")).contains("counter"))
        assertEquals(listOf(listOf("fixture-a"), listOf("fixture-b")), host.starts.toList())
    }

    @Test fun rejectionAfterPoolReservationReleasesCapacityForFutureApprovals() = fixture { root, host, _ ->
        val armed = AtomicBoolean(false)
        val changed = AtomicBoolean(false)
        val source = AgentPluginSource {
            if (armed.get() && changed.compareAndSet(false, true)) configure(root, "fixture-b")
            emptyList()
        }
        val manager = McpManager(root, source, host)
        host.resolve = { armed.set(true) }
        repeat(65) {
            armed.set(false); changed.set(false); configure(root)
            val selected = manager.select("local")
            val failure = runCatching { manager.listTools(selected) }.exceptionOrNull()
            assertTrue(changed.get())
            assertTrue("Unexpected rejection: $failure", failure?.message.orEmpty().contains("configuration changed"))
            assertTrue(host.starts.isEmpty())
        }
        withTimeout(3_000) { McpManager.awaitIoIdleForExit() }
    }

    private fun context(root: File, host: Host): AgentTools.Ctx {
        val c = agentWorkflowContext(root)
        return AgentTools.Ctx(c.files, c.terminal, c.git, c.approvals, c.questions, c.history, c.perms, c.mode,
            c.llm, c.llmConfig, root, c.todos, c.sessionId, c.lsp, mcpProcessHost = host)
    }

    @Test fun firstClassToolCannotSwitchConfigAfterItsDescriptorWasApproved() = fixture { root, host, _ ->
        host.respond = true
        val c = context(root, host)
        val descriptor = McpToolRegistry(root, processHost = host).discoverAgentTools(c.perms, c.permissionScope).single()
        host.resolve = { configure(root, "fixture-b") }
        val result = AgentTools.execute(descriptor.functionName, buildJsonObject {}, c)
        assertTrue(result.contains("changed", ignoreCase = true))
        assertEquals(listOf(listOf("fixture-a")), host.starts.toList())
        assertFalse(host.methods.contains("tools/call"))
    }

    @Test fun unchangedFirstClassToolReusesTheApprovedSession() = fixture { root, host, _ ->
        host.respond = true
        val c = context(root, host)
        val descriptor = McpToolRegistry(root, processHost = host).discoverAgentTools(c.perms, c.permissionScope).single()
        assertTrue(AgentTools.execute(descriptor.functionName, buildJsonObject {}, c).contains("ok"))
        assertEquals(1, host.starts.size)
        assertTrue(host.methods.contains("tools/call"))
    }

    @Test fun healthRefreshCannotStartAChangedConfiguration() = fixture { root, host, _ ->
        val c = context(root, host)
        val agent = AgentService(c.files, c.terminal, c.git, perms = c.perms, mcpProcessHost = host, maxSubagentDepth = 0)
        val resolved = AtomicBoolean(false)
        host.resolve = { resolved.set(true); configure(root, "fixture-b") }
        try { agent.refreshMcpHealth("local"); assertTrue(resolved.get()); assertTrue(host.starts.isEmpty()) }
        finally { agent.shutdown() }
    }
}
