package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ToolchainEnvironmentRefreshTest {
    @get:org.junit.Rule val admission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()
    private class Session(val spec: TerminalLaunchSpec) : ITerminalSession {
        override val output = MutableStateFlow("")
        var destroyed = false
        val commands = mutableListOf<String>()
        override fun start() = Unit
        override fun send(cmd: String) = Unit
        override fun clear() = Unit
        override fun interrupt() = Unit
        override fun destroy() { destroyed = true }
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult {
            commands += cmd
            return ExecResult(0, spec.environment["TEST_SDK"].orEmpty(), false)
        }
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long) = execOnce(argv.joinToString(" "), timeoutMs)
    }
    private class Fixture(val root: File) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var sdk = "old"
        var beforePrepared: suspend () -> Unit = {}
        val sessions = mutableListOf<Session>()
        val terminals = TerminalManager(scope, root, ubuntuOnly = true,
            linuxLaunchSpecFactory = { _, _ -> beforePrepared(); TerminalLaunchSpec(prefix = listOf("proot"), shell = "/bin/sh",
                environment = mapOf("TEST_SDK" to sdk)) },
            localSessionFactory = { _, _, spec -> Session(spec).also { sessions += it } })
        val refresh = ToolchainEnvironmentRefresh(terminals)
    }
    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val f = Fixture(Files.createTempDirectory("toolchain-refresh-").toFile())
        try { withTimeout(10_000) { f.block() } }
        finally { f.terminals.dispose(false); f.scope.coroutineContext.job.cancelAndJoin(); PathSecurity.deleteTreeNoFollow(f.root) }
    }

    @Test fun nextCommandUsesFreshEnvironmentWhilePinnedWorkStaysOnItsOriginalTerminal() = fixture {
        val old = terminals.createLinuxTerminal()
        val router = ExecutionTerminalRouter(terminals)
        val pinned = router.bindCommandSession()
        sdk = "new"
        val result = refresh.open {}
        assertTrue(result.contains("success=true")); assertTrue(result.contains("terminal_id=${terminals.active.value}"))
        assertEquals("new", router.execOnce("build", 1000).output)
        assertEquals("old", pinned.execOnce("old work", 1000).output)
        assertEquals(2, terminals.tabs.value.size)
        assertFalse((old.session as Session).destroyed)
    }

    @Test fun withdrawnOwnershipDuringPreparationDoesNotPublishANewTab() = fixture {
        val old = terminals.createLinuxTerminal()
        var current = true
        beforePrepared = { current = false }
        assertNotNull(runCatching { refresh.open { check(current) } }.exceptionOrNull())
        assertEquals(listOf(old), terminals.tabs.value); assertFalse((old.session as Session).destroyed)
    }

    @Test fun failedPreparationKeepsOldSelectionAndDoesNotClaimRefresh() = fixture {
        val old = terminals.createLinuxTerminal()
        beforePrepared = { error("SDK receipt unavailable") }
        assertNotNull(runCatching { refresh.open {} }.exceptionOrNull())
        assertEquals(old.id, terminals.active.value); assertEquals(listOf(old), terminals.tabs.value)
    }

    @Test fun cancellationReleasesPreparationWithoutRetiringOldTerminal() = fixture {
        val old = terminals.createLinuxTerminal(); val started = CompletableDeferred<Unit>()
        beforePrepared = { started.complete(Unit); awaitCancellation() }
        val task = scope.async { refresh.open {} }
        started.await(); task.cancelAndJoin()
        assertEquals(0, terminals.ubuntuPreparation.value.pending)
        assertEquals(listOf(old), terminals.tabs.value); assertFalse((old.session as Session).destroyed)
    }

    @Test fun closedWorkspaceCannotPrepareANewEnvironment() = fixture {
        terminals.dispose(false)
        assertNotNull(runCatching { refresh.open {} }.exceptionOrNull())
        assertTrue(sessions.isEmpty())
    }

    @Test fun externalDiscoveryAndDispatchCannotPretendToRefreshTheCallingCli() = runBlocking {
        ExternalIdeFixture().use { base ->
            var calls = 0
            val provider = ExternalIdeTools(FileRepository(base.root), base.documents, base.lsp, base.permissions, base.approvals,
                requireOwner = {}, toolchain = { _, _, _ -> calls++; "unused" })
            val schema = provider.definitions.map { it.jsonObject }.single { it["name"] == JsonPrimitive("droide_toolchain") }
            val operations = schema["inputSchema"]!!.jsonObject["properties"]!!.jsonObject["operation"]!!.jsonObject["enum"]!!.jsonArray
            assertFalse(operations.contains(JsonPrimitive("refresh_environment")))
            assertNotNull(runCatching { provider.call("droide_toolchain", buildJsonObject { put("operation", "refresh_environment") }) }.exceptionOrNull())
            assertEquals(0, calls)
            provider.close()
        }
    }
}
