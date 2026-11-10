package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentToolTimeoutTest {
    @get:org.junit.Rule val applicationWork: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    @Test fun resumableDefaultHasNoDeadlineAndExplicitDeadlinesReachTheApprovedBackend() = withRoot { root -> runBlocking {
        val terminal = RecordingTerminal()
        val manager = BackgroundCommandManager(terminal)
        try {
            val ctx = agentWorkflowContext(root, manager = manager, terminal = terminal)
            assertTrue(AgentTools.execute("run_command", command("printf ready"), ctx).contains("exit=0"))
            assertTrue(AgentTools.execute("bash", command("printf ready", 1_250), ctx).contains("exit=0"))
            assertEquals(listOf(0L, 1_250L), terminal.deadlines)
            assertTrue(AgentTools.execute("run_command", command("printf ready", 999), ctx).startsWith("ERROR:"))
            assertEquals(2, terminal.deadlines.size)
        } finally { manager.close() }
    } }

    @Test(timeout = 50_000) fun actualLongCommandYieldsAutomaticallyAndFinishesAfterTheFormerThirtySecondLimit() = withRoot { root -> runBlocking {
        val terminal = AgentWorkflowTerminal()
        val manager = BackgroundCommandManager(terminal)
        try {
            val ctx = agentWorkflowContext(root, manager = manager, terminal = terminal)
            val started = System.nanoTime()
            val result = AgentTools.execute("run_command", command("printf started; sleep 31; printf after-old-limit"), ctx)
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("Automatic yield took $elapsed ms", elapsed in 4_500..8_000)
            assertTrue(result.contains("started")); assertFalse(result.contains("exit=0"))
            assertEquals(AgentToolEvidenceState.STARTED, AgentToolResultSemantics.classify("run_command", result).state)
            val metadata = Json.parseToJsonElement(result.lineSequence().first { it.startsWith('{') }).jsonObject
            val id = metadata.getValue("session_id").jsonPrimitive.content
            val cursor = metadata.getValue("next_output_cursor").jsonPrimitive.long
            assertNull(manager.snapshot(id)?.timeoutMs)
            val resumed = AgentTools.execute("write_stdin", buildJsonObject {
                put("session_id", id); put("output_cursor", cursor); put("wait_for_completion", true)
            }, ctx)
            assertEquals(BackgroundCommandManager.State.EXITED, manager.snapshot(id)?.state)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 31_000)
            assertTrue(resumed.contains("after-old-limit")); assertFalse(resumed.contains("started"))
            assertEquals(AgentToolEvidenceState.SUCCEEDED, AgentToolResultSemantics.classify("write_stdin", resumed).state)
        } finally { manager.close() }
    } }

    @Test(timeout = 10_000) fun explicitDeadlineSurvivesYieldAndPollingWithoutBeingReset() = withRoot { root -> runBlocking {
        val terminal = AgentWorkflowTerminal()
        val manager = BackgroundCommandManager(terminal)
        try {
            val ctx = agentWorkflowContext(root, manager = manager, terminal = terminal)
            val result = AgentTools.execute("run_command", command("exec sleep 10", 1_000, 0), ctx)
            assertEquals(AgentToolEvidenceState.STARTED, AgentToolResultSemantics.classify("run_command", result).state)
            val id = manager.list().single().id
            manager.awaitOutput(id, 0, 100)
            val finalResult = withTimeout(3_000) { AgentTools.execute("write_stdin", buildJsonObject {
                put("session_id", id); put("wait_for_completion", true)
            }, ctx) }
            val final = requireNotNull(manager.snapshot(id))
            assertEquals(BackgroundCommandManager.State.TIMED_OUT, final.state)
            assertEquals(1_000L, final.timeoutMs)
            assertEquals(AgentToolEvidenceState.FAILED, AgentToolResultSemantics.classify("write_stdin", finalResult).state)
        } finally { manager.close() }
    } }

    @Test(timeout = 10_000) fun stopCancelsForegroundWithoutWaitingForTheLongDefault() = withRoot { root -> runBlocking {
        val started = CompletableDeferred<Unit>()
        val backend = AgentWorkflowTerminal()
        val terminal = object : ITerminalSession by backend {
            override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult {
                started.complete(Unit)
                return backend.execStreaming(cmd, timeoutMs, onOutput)
            }
        }
        val manager = BackgroundCommandManager(terminal)
        try {
            val job = async(Dispatchers.IO) { AgentTools.execute("run_command", command("exec sleep 60"), agentWorkflowContext(root, manager = manager, terminal = terminal)) }
            withTimeout(3_000) { started.await() }
            withTimeout(2_000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertEquals(BackgroundCommandManager.State.KILLED, manager.list().single().state)
        } finally { manager.close() }
    } }

    @Test(timeout = 10_000) fun stopAfterAutomaticYieldPreservesExplicitBackgroundWorkAndUserProcesses() = withRoot { root -> runBlocking {
        val terminal = AgentWorkflowTerminal()
        val manager = BackgroundCommandManager(terminal)
        val unrelated = ProcessBuilder("/bin/sh", "-c", "exec sleep 60").start()
        try {
            val ctx = agentWorkflowContext(root, manager = manager, terminal = terminal)
            AgentTools.execute("run_command", command("exec sleep 60", yield = 0), ctx)
            val foreground = manager.list().single().id
            val detached = AgentTools.execute("run_command", buildJsonObject { put("command", "exec sleep 60"); put("background", true) }, ctx)
            assertTrue(detached.contains("BACKGROUND_JOB_STARTED"))
            manager.cancelForegroundCommands()
            withTimeout(2_000) { manager.kill(foreground) }
            assertEquals(BackgroundCommandManager.State.KILLED, manager.snapshot(foreground)?.state)
            assertEquals(BackgroundCommandManager.State.RUNNING, manager.list().single { it.id != foreground }.state)
            assertTrue(unrelated.isAlive)
        } finally {
            manager.list().forEach { withTimeout(2_000) { manager.kill(it.id) } }
            manager.close(); unrelated.destroyForcibly(); unrelated.waitFor(2, TimeUnit.SECONDS)
        }
    } }

    @Test fun embeddedCommandCallerWithoutSessionManagerHasNoImplicitBuildDeadline() = withRoot { root -> runBlocking {
        val terminal = RecordingTerminal()
        assertTrue(AgentTools.execute("run_command", command("printf ready"), agentWorkflowContext(root, terminal = terminal)).contains("exit=0"))
        assertEquals(listOf(0L), terminal.deadlines)
        assertEquals(0L, AgentTimeoutPolicy.BUILD_DEADLINE_MS)
    } }

    @Test fun pluginDefaultsAndExplicitLongOrShortDeadlinesSurviveDiscoveryAndExecution() = withRoot { root -> runBlocking {
        val manager = pluginManager(root, listOf(null, 3_600_000L, 1_250L))
        val tools = manager.discover()
        assertEquals(listOf(0L, 3_600_000L, 1_250L), tools.map { it.timeoutMs })
        val terminal = RecordingTerminal()
        tools.forEach { assertTrue(manager.execute(it, buildJsonObject {}, terminal).contains("exit=0")) }
        assertEquals(tools.map { it.timeoutMs }, terminal.deadlines)
    } }

    @Test fun pluginDeadlineBoundsAndApprovalFingerprintAreStillEnforced() = withRoot { root -> runBlocking {
        val manager = pluginManager(root, listOf(1L, 100_000_000L))
        val tools = manager.discover()
        assertEquals(listOf(500L, 86_400_000L), tools.map { it.timeoutMs })
        val file = File(root, "plugin/tools.json")
        val previous = Json.parseToJsonElement(file.readText()).jsonObject.getValue("tools").jsonArray
        file.writeText(buildJsonObject { put("tools", JsonArray(previous.mapIndexed { index, tool ->
            if (index == 0) JsonObject(tool.jsonObject + ("timeoutMs" to JsonPrimitive(1_800_000))) else tool
        })) }.toString())
        // Changed metadata cannot reuse an approval fingerprint from the old deadline.
        val terminal = RecordingTerminal()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { manager.execute(tools.first(), buildJsonObject {}, terminal) } }
        assertTrue(terminal.deadlines.isEmpty())
    } }

    @Test fun customScriptsHaveNoImplicitDeadlineAndPreserveFingerprintChecks() = withRoot { root -> runBlocking {
        val script = File(root, ".droide/tools/slow.sh").apply { parentFile.mkdirs(); writeText("# desc: slow\ntrue\n") }
        val manager = CustomToolManager(root)
        val approved = requireNotNull(manager.permissionFingerprint("slow"))
        val terminal = RecordingTerminal()
        assertTrue(manager.execute("slow", "{}", terminal, approved).contains("exit=0"))
        assertEquals(listOf(0L), terminal.deadlines)
        script.appendText("echo changed\n")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { manager.execute("slow", "{}", terminal, approved) } }
        assertEquals(1, terminal.deadlines.size)
    } }

    @Test(timeout = 10_000) fun pluginToolCancellationPropagatesWithoutAnImplicitDeadline() = withRoot { root -> runBlocking {
        val manager = pluginManager(root, listOf(null))
        val entered = CompletableDeferred<Unit>()
        val terminal = object : ITerminalSession by AgentWorkflowTerminal() {
            override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult {
                assertEquals(0L, timeoutMs)
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val job = async { manager.execute(manager.discover().single(), buildJsonObject {}, terminal) }
        withTimeout(3_000) { entered.await() }
        withTimeout(1_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    } }

    private fun command(text: String, timeout: Long? = null, yield: Long? = null) = buildJsonObject {
        put("command", text); timeout?.let { put("timeout_ms", it) }
        yield?.let { put("yield_time_ms", it) }
    }
    private fun pluginManager(root: File, deadlines: List<Long?>): AgentPluginToolManager {
        val plugin = File(root, "plugin").apply { mkdirs() }
        File(plugin, "tools.json").writeText(buildJsonObject { put("tools", buildJsonArray {
            deadlines.forEachIndexed { index, timeout -> add(buildJsonObject {
                put("name", "tool$index")
                put("command", buildJsonArray { add("sh"); add("-c"); add("printf ok") })
                timeout?.let { put("timeoutMs", it) }
            }) }
        }) }.toString())
        val contribution = AgentPluginContributionRoot("fixture", "Fixture", "1", "", AgentPluginFormat.DROIDE, plugin, "fixture-sha")
        return AgentPluginToolManager(root, AgentPluginSource { listOf(contribution) })
    }
    private class RecordingTerminal : ITerminalSession by AgentWorkflowTerminal() {
        val deadlines = mutableListOf<Long>()
        override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult {
            deadlines += timeoutMs
            return ExecResult(0, "recorded", false)
        }
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult {
            deadlines += timeoutMs
            return ExecResult(0, "recorded", false)
        }
    }
    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("droide-agent-timeout-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
