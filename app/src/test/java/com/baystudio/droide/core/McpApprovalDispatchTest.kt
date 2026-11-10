package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class McpApprovalDispatchTest {
    private class RecordingHost : StdioProcessHost {
        override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
        val starts = CopyOnWriteArrayList<List<String>>()
        override suspend fun resolveExecutable(command: String) = command
        override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
            starts += argv.toList()
            throw IOException("Fixture records an attempted launch; no process is created")
        }
    }
    private fun configure(root: File, command: String) {
        root.resolve(".droide").mkdirs()
        root.resolve(".droide/mcp.json").writeText("""{"servers":{"local":{"command":"$command","protocol":"legacy-2025"}}}""")
    }
    private fun context(root: File, source: AgentPluginSource, host: StdioProcessHost): AgentTools.Ctx {
        val c = agentWorkflowContext(root)
        return AgentTools.Ctx(c.files, c.terminal, c.git, c.approvals, c.questions, c.history, c.perms, c.mode,
            c.llm, c.llmConfig, root, c.todos, c.sessionId, c.lsp, pluginSource = source, mcpProcessHost = host)
    }
    private fun fixture(block: suspend (File, RecordingHost) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("mcp-approved-dispatch-").toFile(); val host = RecordingHost()
        try { withTimeout(10_000) { configure(root, "fixture-a"); block(root, host) } }
        finally { McpManager.shutdownSessions(root, host); McpToolRegistry.invalidate(root); PathSecurity.deleteTreeNoFollow(root) }
    }
    private fun operation(operation: String?) = buildJsonObject {
        put("server", "local"); operation?.let { put("operation", it) }
        if (operation == "call_tool" || operation == null) put("tool", "counter")
        if (operation == "read_resource") put("uri", "fixture://one")
    }
    private fun changedAfterApproval(operation: String?) = fixture { root, host ->
        val approved = AtomicBoolean(false); val changed = AtomicBoolean(false)
        val source = AgentPluginSource {
            if (approved.get() && changed.compareAndSet(false, true)) configure(root, "fixture-b")
            emptyList()
        }
        val c = context(root, source, host)
        val failure = runCatching {
            McpAgentOperations.execute(operation(operation), c,
                gate = { action, resource ->
                    assertEquals("mcp", action); assertTrue(resource.contains("@cfg:")); approved.set(true); null
                }, gatePreview = { _, _, _, _, _ -> error("Unexpected repair approval") })
        }.exceptionOrNull()
        assertTrue("Fixture must change configuration after approving A", changed.get())
        assertNotNull("A changed approval must not succeed", failure)
        assertTrue("A different configuration was dispatched under A's approval: ${host.starts}", host.starts.isEmpty())
    }

    @Test fun explicitDiscoveryCannotUseChangedApproval() = changedAfterApproval("list_tools")
    @Test fun explicitToolCannotUseChangedApproval() = changedAfterApproval("call_tool")
    @Test fun legacyToolCannotUseChangedApproval() = changedAfterApproval(null)
    @Test fun contextReadCannotUseChangedApproval() = changedAfterApproval("read_resource")
    @Test fun healthVerificationCannotUseChangedApproval() = changedAfterApproval("verify")
    @Test fun catalogRefreshCannotUseChangedApproval() = changedAfterApproval("refresh_catalog")

    @Test fun automaticCatalogCannotStartChangedConfigurationUnderOldPermission() = fixture { root, host ->
        val reads = AtomicInteger()
        val source = AgentPluginSource { if (reads.incrementAndGet() == 2) configure(root, "fixture-b"); emptyList() }
        val permissions = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW)))
        val tools = McpToolRegistry(root, source, host).discoverAgentTools(permissions, "fixture")
        assertTrue(tools.isEmpty())
        assertTrue("Catalog discovery started a different configuration: ${host.starts}", host.starts.isEmpty())
    }

    @Test fun deniedOperationDoesNotStartTheHost() = fixture { root, host ->
        val c = context(root, AgentPluginSource.EMPTY, host)
        val result = McpAgentOperations.execute(operation("list_tools"), c, gate = { _, _ -> "DENIED fixture" },
            gatePreview = { _, _, _, _, _ -> error("Unexpected repair") })
        assertEquals("DENIED fixture", result); assertTrue(host.starts.isEmpty())
    }

    @Test fun unchangedApprovedOperationReachesOnlyTheSelectedHost() = fixture { root, host ->
        val c = context(root, AgentPluginSource.EMPTY, host)
        val failure = runCatching { McpAgentOperations.execute(operation("list_tools"), c, gate = { _, _ -> null },
            gatePreview = { _, _, _, _, _ -> error("Unexpected repair") }) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(listOf(listOf("fixture-a")), host.starts.toList())
    }
}
