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

class McpManagementTest {
    @get:Rule val admission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    private class Host : StdioProcessHost {
        override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
        val processes = CopyOnWriteArrayList<HostedStdioProcess>()
        var resolving: suspend () -> Unit = {}
        override suspend fun resolveExecutable(command: String): String { resolving(); return command }
        override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
            val client = PipedInputStream(16_384)
            val output = PipedOutputStream(client)
            val alive = AtomicBoolean(true)
            val input = object : OutputStream() {
                private val frame = ByteArrayOutputStream()
                override fun write(value: Int) {
                    if (value != 10) { frame.write(value); return }
                    val message = Json.parseToJsonElement(frame.toString("UTF-8")).jsonObject
                    frame.reset()
                    val id = message["id"] ?: return
                    val result = when (message["method"]?.jsonPrimitive?.content) {
                        "initialize" -> """{"protocolVersion":"2025-11-25"}"""
                        "tools/list" -> """{"tools":[{"name":"counter","inputSchema":{"type":"object"}}]}"""
                        else -> "{}"
                    }
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
            }.also { processes += it }
        }
    }

    private class Fixture(val root: File) {
        val host = Host()
        var effect = PermEffect.ASK
        val manager = McpManager(root, processHost = host)
        val management = McpManagement(FileRepository(root), manager) { effect }
        suspend fun configure(): McpConfigurationSnapshot = management.save(management.load(),
            McpServerDraft("local", "fixture", protocol = McpProtocolPreference.LEGACY_2025), null)
    }
    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val root = Files.createTempDirectory("mcp-management-").toFile()
        val fixture = Fixture(root)
        try { withTimeout(10_000) { fixture.block() } }
        finally {
            McpManager.shutdownSessions(root, fixture.host)
            withTimeout(3_000) { McpManager.awaitIoIdleForExit() }
            McpToolRegistry.invalidate(root)
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    @Test fun explicitConnectionWorksWithAskWithoutGrantingPersistentToolPermission() = fixture {
        configure()
        val review = management.reviewConnection("local")
        assertTrue(host.processes.isEmpty())
        val connected = management.connect(review)
        assertEquals(McpHealthState.CONNECTED, connected.state)
        assertEquals(1, connected.toolsCount)
        assertEquals(1, host.processes.size)
        assertEquals(PermEffect.ASK, effect)
        val askPermissions = PermissionEngine(listOf(PermRule("mcp", "*", PermEffect.ASK)))
        assertTrue(McpToolRegistry(root, processHost = host).discoverAgentTools(askPermissions, "test").isEmpty())
        assertEquals(McpHealthState.CONNECTED, McpHealthRegistry.observe(root).value.single().state)
        assertTrue(runCatching { management.connect(review) }.isFailure)
        assertEquals(1, host.processes.size)
    }

    @Test fun denyBeforeReviewOrAfterReviewPreventsExecution() = fixture {
        configure()
        effect = PermEffect.DENY
        assertTrue(runCatching { management.reviewConnection("local") }.isFailure)
        effect = PermEffect.ASK
        val review = management.reviewConnection("local")
        effect = PermEffect.DENY
        assertTrue(runCatching { management.connect(review) }.isFailure)
        assertTrue(host.processes.isEmpty())
    }

    @Test fun editingConfigurationInvalidatesPendingConnectionApproval() = fixture {
        val original = configure()
        val review = management.reviewConnection("local")
        management.save(original, McpServerDraft("local", "different", protocol = McpProtocolPreference.LEGACY_2025), "local")
        assertTrue(runCatching { management.connect(review) }.isFailure)
        assertTrue(host.processes.isEmpty())
    }

    @Test fun disablingRetiresSessionHidesToolsAndRejectsOldSelections() = fixture {
        val original = configure()
        val selection = manager.select("local")
        management.connect(management.reviewConnection("local"))
        val disabled = management.setEnabled(original, "local", false)
        withTimeout(3_000) { while (host.processes.any { it.isAlive }) delay(10) }
        assertTrue(runCatching { manager.discoverTools(selection) }.isFailure)
        assertTrue(runCatching { manager.select("local") }.isFailure)
        val permissions = PermissionEngine(listOf(PermRule("mcp", "*", PermEffect.ALLOW)))
        assertTrue(McpToolRegistry(root, processHost = host).discoverAgentTools(permissions, "test").isEmpty())
        assertEquals(McpHealthState.DISABLED, McpHealthRegistry.observe(root).value.single().state)
        management.setEnabled(disabled, "local", true)
        assertEquals(McpHealthState.CONFIGURED, McpHealthRegistry.observe(root).value.single().state)
        assertEquals("Enabling must not auto-start", 1, host.processes.size)
    }

    @Test fun removalClosesSessionAndLateHealthCannotResurrectIt() = fixture {
        val original = configure()
        val server = manager.list().single()
        management.connect(management.reviewConnection("local"))
        management.remove(original, "local")
        withTimeout(3_000) { while (host.processes.any { it.isAlive }) delay(10) }
        McpHealthRegistry.connected(root, server, 99, "late")
        McpHealthRegistry.failure(root, server, McpHealthPhase.DISCOVERY, IOException("late"))
        assertTrue(McpHealthRegistry.observe(root).value.isEmpty())
    }

    @Test fun disabledStateCannotBeOverwrittenByOldConnectionResult() = fixture {
        val original = configure()
        val server = manager.list().single()
        management.setEnabled(original, "local", false)
        McpHealthRegistry.connected(root, server, 99, "late")
        assertEquals(McpHealthState.DISABLED, McpHealthRegistry.observe(root).value.single().state)
        assertNull(McpHealthRegistry.observe(root).value.single().toolsCount)
    }

    @Test fun cancellationWhileResolvingIsPropagatedWithoutStarting() = fixture {
        configure()
        host.resolving = { throw CancellationException("cancel fixture") }
        val failure = runCatching { management.connect(management.reviewConnection("local")) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(host.processes.isEmpty())
    }

    @Test fun externalSessionProjectionExcludesDisabledServersWithoutStartingThem() = fixture {
        val configured = configure()
        val mapper = WorkspacePathMapper(root, "/workspace/project")
        assertEquals(1, WorkspaceMcpServers.project(root, mapper, host).size)
        management.setEnabled(configured, "local", false)
        assertTrue(WorkspaceMcpServers.project(root, mapper, host).isEmpty())
        assertTrue(host.processes.isEmpty())
    }
}
