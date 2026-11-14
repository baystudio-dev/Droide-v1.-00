package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Candidate only: never delegates to AgentWorkflowTerminal or any real terminal. */
class R39PluginDeadlineBaselineTest {
    @get:Rule val temp = TemporaryFolder()
    private class Terminal : ITerminalSession {
        override val output = MutableStateFlow("")
        val calls = mutableListOf<Pair<List<String>, Long>>()
        var execute: suspend () -> ExecResult = { ExecResult(0, "fake output", false) }
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult {
            calls += argv to timeoutMs
            return execute()
        }
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = error("Forbidden shell execution")
        override fun start(): Unit = error("Forbidden terminal startup")
        override fun send(cmd: String): Unit = error("Forbidden terminal input")
        override fun interrupt(): Unit = error("Forbidden terminal interrupt")
        override fun clear() = Unit
        override fun destroy() = Unit
    }
    private fun plugin(deadline: Long?): AgentPluginToolManager {
        val root = File(temp.root, "plugin").apply { mkdirs() }
        File(root, "tools.json").writeText(buildJsonObject {
            put("tools", buildJsonArray { add(buildJsonObject {
                put("name", "work")
                put("command", buildJsonArray { add("inert-fixture-command") })
                deadline?.let { put("timeoutMs", it) }
            }) })
        }.toString())
        val contribution = AgentPluginContributionRoot("fixture", "Fixture", "1", "", AgentPluginFormat.DROIDE, root, "fixture-sha")
        return AgentPluginToolManager(temp.root, AgentPluginSource { listOf(contribution) })
    }

    @Test fun omittedPluginDeadlineReachesApprovedBoundaryAsZero() = runBlocking {
        val manager = plugin(null)
        val terminal = Terminal()
        assertTrue(manager.execute(manager.discover().single(), buildJsonObject {}, terminal).startsWith("exit=0"))
        // Expected baseline red: current implicit default is 1800000.
        assertEquals(listOf(0L), terminal.calls.map { it.second })
    }

    @Test fun explicitPositiveDeadlineBoundsAndExistingExplicitZeroMeaningArePreserved() = runBlocking {
        for ((requested, expected) in listOf(1_250L to 1_250L, 3_600_000L to 3_600_000L,
            1L to 500L, 0L to 500L, 100_000_000L to 86_400_000L)) {
            val manager = plugin(requested)
            val terminal = Terminal()
            manager.execute(manager.discover().single(), buildJsonObject {}, terminal)
            assertEquals(listOf(expected), terminal.calls.map { it.second })
        }
    }

    @Test fun changedPluginDeadlineCannotReuseAnApprovalFingerprint() = runBlocking {
        val manager = plugin(1_250)
        val approved = manager.discover().single()
        plugin(3_600_000)
        val terminal = Terminal()
        try { manager.execute(approved, buildJsonObject {}, terminal); fail("Stale approval was accepted") }
        catch (_: IllegalArgumentException) { }
        assertTrue(terminal.calls.isEmpty())
    }

    @Test fun customScriptHasNoImplicitDeadlineAndStillRejectsChangedFingerprint() = runBlocking {
        val script = File(temp.root, ".droide/tools/work.sh").apply {
            parentFile.mkdirs(); writeText("# desc: inert fixture\nnot-executed\n")
        }
        val manager = CustomToolManager(temp.root)
        val approved = requireNotNull(manager.permissionFingerprint("work"))
        val terminal = Terminal()
        manager.execute("work", "{}", terminal, approved)
        script.appendText("changed-but-never-executed\n")
        try { manager.execute("work", "{}", terminal, approved); fail("Stale script approval was accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(1, terminal.calls.size)
        // Expected baseline red, after the fingerprint negative control was exercised.
        assertEquals(0L, terminal.calls.single().second)
    }

    @Test(timeout = 10_000) fun cancellationReachesFakeExecutionAndWaitsForItsCleanup() = runBlocking {
        val manager = plugin(null)
        val entered = CompletableDeferred<Unit>()
        var cleaned = false
        val terminal = Terminal().apply {
            execute = { entered.complete(Unit); try { awaitCancellation() } finally { cleaned = true } }
        }
        val job = launch { manager.execute(manager.discover().single(), buildJsonObject {}, terminal) }
        try { withTimeout(2_000) { entered.await(); job.cancelAndJoin() } }
        finally { withContext(NonCancellable) { withTimeout(2_000) { job.cancelAndJoin() } } }
        assertTrue(job.isCancelled); assertTrue(cleaned); assertEquals(1, terminal.calls.size)
    }
}
