package com.baystudio.droide.core

import java.util.concurrent.atomic.AtomicInteger
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Candidate baseline only. No shell, process, PTY, provider, device, or network.
 * The composition below uses the production planner and approval helper. Its
 * gate follows AgentService's repeatOrdinal >= 3 branch and calls the actual
 * command-observation predicate through AgentApprovalSupport. This is not
 * a claim that a full AgentService/provider workflow has been exercised.
 * R38 reproduced the healthy-observer failure; its product assertions remain unchanged.
 */
class R39AgentCommandPollingBaselineTest {
    @get:org.junit.Rule
    val applicationAdmission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    @Test(timeout = 10_000)
    fun healthyRunningObservationDoesNotAskForRepetitionApproval() = bounded {
        val terminal = LiteralWaitingTerminal()
        val commands = BackgroundCommandManager(terminal)
        val approvals = ApprovalManager()
        var gate: Deferred<Boolean>? = null
        try {
            val job = commands.start(LITERAL, stopWithAgent = true)
            withTimeout(2_000) { terminal.entered.await() }
            val args = observe(job.id, 1_000)
            val call = thirdCall("write_stdin", args)
            val facts = facts(call)
            assertEquals(BackgroundCommandManager.State.RUNNING, commands.snapshot(job.id)?.state)
            assertEquals(0L, commands.snapshot(job.id)?.outputEndCursor)
            assertEquals(1, terminal.executions.get())
            val work = async(start = CoroutineStart.UNDISPATCHED) {
                guarded(approvals, PermissionEngine(), call, facts, commands.snapshot(job.id))
            }
            gate = work
            // Product requirement, intentionally not inverted to match R38.
            assertNull("A valid positive-wait observation of the same healthy job must not ask for repetition approval", approvals.pending.value)
            assertTrue("An observation gate must finish without a user response", work.isCompleted)
            assertFalse(work.await())
            assertEquals(1, terminal.executions.get())
        } finally {
            approvals.resolve(ApprovalResolution.DENY)
            withContext(NonCancellable) {
                withTimeout(2_000) { gate?.cancelAndJoin(); commands.list().forEach { commands.kill(it.id) } }
            }
            commands.close()
            assertEquals(0, terminal.forbiddenCalls.get())
        }
    }

    @Test(timeout = 10_000)
    fun explicitDoomLoopDenyRemainsAuthoritative() = bounded {
        val approvals = ApprovalManager()
        val call = thirdCall("write_stdin", observe("bg-fixture-1", 1_000))
        val permissions = PermissionEngine(listOf(PermRule("doom_loop", "write_stdin", PermEffect.DENY)))
        assertTrue(guarded(approvals, permissions, call, facts(call), quietSnapshot()))
        assertNull(approvals.pending.value)
        assertFalse(approvals.hasSessionApproval(SESSION, "build", "doom_loop", "write_stdin"))
    }

    @Test(timeout = 10_000)
    fun executionInputEofInterruptAndZeroWaitRetainRepetitionReview() = bounded {
        val cases = listOf(
            "run_command" to buildJsonObject { put("command", LITERAL) },
            "write_stdin" to buildJsonObject { put("session_id", "bg-fixture-1"); put("chars", "literal input"); put("yield_time_ms", 1_000) },
            "write_stdin" to buildJsonObject { put("session_id", "bg-fixture-1"); put("close_stdin", true); put("yield_time_ms", 1_000) },
            "write_stdin" to buildJsonObject { put("session_id", "bg-fixture-1"); put("interrupt", true); put("yield_time_ms", 1_000) },
            "write_stdin" to observe("bg-fixture-1", 0),
        )
        for ((name, args) in cases) {
            val approvals = ApprovalManager()
            val call = thirdCall(name, args)
            val work = async(start = CoroutineStart.UNDISPATCHED) { guarded(approvals, PermissionEngine(), call, facts(call), quietSnapshot()) }
            try {
                val request = requireNotNull(approvals.pending.value)
                assertEquals("doom_loop", request.action)
                assertEquals(name, request.resource)
                assertTrue(approvals.resolve(request.id, ApprovalResolution.DENY))
                assertTrue(work.await())
            } finally {
                approvals.resolve(ApprovalResolution.DENY)
                withContext(NonCancellable) { withTimeout(1_000) { work.cancelAndJoin() } }
            }
        }
    }

    @Test(timeout = 10_000)
    fun foregroundStopWakesTheQuietObserverWithoutAnotherExecution() = bounded {
        val terminal = LiteralWaitingTerminal()
        val commands = BackgroundCommandManager(terminal)
        var observer: Deferred<BackgroundCommandManager.Snapshot>? = null
        try {
            val job = commands.start(LITERAL, stopWithAgent = true)
            withTimeout(2_000) { terminal.entered.await() }
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { commands.awaitOutput(job.id, 0, 30_000) }
            observer = waiting
            commands.cancelForegroundCommands()
            val result = withTimeout(2_000) { waiting.await() }
            assertEquals(BackgroundCommandManager.State.KILLED, result.state)
            assertEquals(0L, result.outputEndCursor)
            assertEquals(1, terminal.executions.get())
            withTimeout(2_000) { commands.kill(job.id) }
            assertTrue(terminal.finished.isCompleted)
        } finally {
            withContext(NonCancellable) {
                withTimeout(2_000) { observer?.cancelAndJoin(); commands.list().forEach { commands.kill(it.id) } }
            }
            commands.close()
            assertEquals(0, terminal.forbiddenCalls.get())
        }
    }

    @Test(timeout = 10_000)
    fun observationPredicateRejectsInvalidStaleReplayAndZeroCompletionRequests() = bounded {
        val job = quietSnapshot().copy(output = "abc", outputEndCursor = 3)
        val valid = buildJsonObject { put("session_id", job.id); put("output_cursor", 3); put("yield_time_ms", 1_000) }
        assertTrue(AgentCommandSessions.isQuietObservation("write_stdin", valid, job))
        fun changed(key: String, value: JsonElement) = JsonObject(valid + (key to value))
        val invalid = listOf(
            changed("session_id", JsonPrimitive("bg-other-1")), changed("session_id", buildJsonArray {}),
            changed("output_cursor", JsonPrimitive(0)), changed("output_cursor", JsonPrimitive(4)),
            changed("output_cursor", JsonPrimitive(-1)), changed("output_cursor", JsonPrimitive("3")),
            changed("yield_time_ms", JsonPrimitive(0)), changed("yield_time_ms", JsonPrimitive(30_001)),
            changed("yield_time_ms", JsonPrimitive("1000")), changed("chars", JsonPrimitive("x")),
            changed("chars", JsonNull), changed("close_stdin", JsonPrimitive(true)),
            changed("interrupt", JsonPrimitive(true)), changed("interrupt", JsonPrimitive("false")),
            changed("wait_for_completion", JsonPrimitive("true")), changed("unknown", JsonPrimitive(true)),
            JsonObject(valid + ("yield_time_ms" to JsonPrimitive(0)) + ("wait_for_completion" to JsonPrimitive(true))),
        )
        invalid.forEach { assertFalse("Must retain repetition guard for $it", AgentCommandSessions.isQuietObservation("write_stdin", it, job)) }
        assertFalse(AgentCommandSessions.isQuietObservation("write_stdin", valid, null))
        assertFalse(AgentCommandSessions.isQuietObservation("write_stdin", valid, job.copy(state = BackgroundCommandManager.State.EXITED, exitCode = 0)))
        assertFalse(AgentCommandSessions.isQuietObservation("background_job", valid, job))
        assertFalse(AgentCommandSessions.isQuietObservation("exec_command", valid, job))
        val completion = JsonObject(valid - "yield_time_ms" + ("wait_for_completion" to JsonPrimitive(true)))
        assertTrue(AgentCommandSessions.isQuietObservation("write_stdin", completion, job))
        assertTrue(facts(thirdCall("write_stdin", valid)).doomLoopApprovalPossible)
        assertEquals(AgentToolScheduler.Lane.SERIAL, facts(thirdCall("write_stdin", valid)).lane)
    }

    @Test(timeout = 10_000)
    fun pureObservationStillHonorsReadDenialAndReviewMode() = bounded {
        val root = Files.createTempDirectory("r39-observation-permissions-").toFile()
        val terminal = LiteralWaitingTerminal()
        val commands = BackgroundCommandManager(terminal)
        val web = AgentWebResearch(root)
        try {
            val job = commands.start(LITERAL, stopWithAgent = true)
            withTimeout(2_000) { terminal.entered.await() }
            val args = observe(job.id, 1_000)
            assertTrue(AgentCommandSessions.isQuietObservation("write_stdin", args, commands.snapshot(job.id)))
            val deny = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW), PermRule("read", "*", PermEffect.DENY)))
            val denied = AgentTools.execute("write_stdin", args,
                agentWorkflowContext(root, commands, permissions = deny, terminal = terminal, webResearch = web))
            assertTrue(denied.startsWith("DENIED"))
            val review = AgentTools.execute("write_stdin", args,
                agentWorkflowContext(root, commands, mode = AgentMode.REVIEW, terminal = terminal, webResearch = web))
            assertTrue(review.startsWith("DENIED"))
            assertEquals(1, terminal.executions.get())
            assertEquals(BackgroundCommandManager.State.RUNNING, commands.snapshot(job.id)?.state)
        } finally {
            withContext(NonCancellable) { withTimeout(2_000) { commands.list().forEach { commands.kill(it.id) } } }
            commands.close(); web.close(); PathSecurity.deleteTreeNoFollow(root)
            assertEquals(0, terminal.forbiddenCalls.get())
        }
    }

    @Test(timeout = 10_000)
    fun skippedRepetitionPromptDoesNotBypassTheHookOwnerBoundary() = bounded {
        val root = Files.createTempDirectory("r39-observation-owner-").toFile()
        val terminal = LiteralWaitingTerminal()
        val approvals = ApprovalManager()
        var current = true
        try {
            val call = thirdCall("write_stdin", observe("bg-fixture-1", 1_000))
            assertFalse(guarded(approvals, PermissionEngine(), call, facts(call), quietSnapshot()))
            assertNull(approvals.pending.value)
            val provenance = AgentRequestProvenance.primary(SESSION)
            val owner = HookInvocationAuthority(AgentMode.BUILD, provenance, "build", { current })
            val bridge = AgentHookLifecycleBridge(HookManager(root, terminal, PermissionEngine(), approvals), { SESSION }, { AgentMode.BUILD })
            var bodies = 0
            current = false
            val failure = runCatching { bridge.tool("write_stdin", call.rawArgs, owner = owner) { bodies++; "unexpected" } }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(0, bodies)
            assertEquals(0, terminal.executions.get())
        } finally {
            approvals.resolve(ApprovalResolution.DENY); PathSecurity.deleteTreeNoFollow(root)
            assertEquals(0, terminal.forbiddenCalls.get())
        }
    }

    private suspend fun guarded(approvals: ApprovalManager, permissions: PermissionEngine,
        call: PreparedToolCall, facts: AgentToolScheduler.Facts, snapshot: BackgroundCommandManager.Snapshot? = null): Boolean {
        // Match the existing repeat-ordinal guard; explicit denial remains authoritative.
        val deny = permissions.decide("doom_loop", call.name, "build") == PermEffect.DENY
        return (deny || facts.doomLoopApprovalPossible) && AgentApprovalSupport.doomLoopDenied(
            approvals, permissions, "build", call.name, call.rawArgs, AgentRequestProvenance.primary(SESSION),
            arguments = call.args, commandSnapshot = snapshot,
        )
    }

    private fun facts(call: PreparedToolCall) = AgentToolTurnPlanner.schedulingFacts(listOf(call), { true }, { false }).single()

    private fun quietSnapshot() = BackgroundCommandManager.Snapshot("bg-fixture-1", BackgroundCommandManager.State.RUNNING,
        LITERAL, "", null, null, 0L, null)

    private fun thirdCall(name: String, args: JsonObject): PreparedToolCall {
        var signature = ""
        var repeat = 0
        var last: PreparedToolCall? = null
        for (turn in 1..3) {
            last = AgentToolTurnPlanner.prepare(buildJsonArray {
                add(buildJsonObject {
                    put("id", "fixture-$turn")
                    put("function", buildJsonObject { put("name", name); put("arguments", args.toString()) })
                })
            }, Json, turn, 128_000, signature, repeat).single()
            signature = last.signature
            repeat = last.repeatOrdinal
        }
        return requireNotNull(last)
    }

    private fun observe(id: String, wait: Long) = buildJsonObject {
        put("session_id", id); put("output_cursor", 0); put("yield_time_ms", wait)
    }

    private fun bounded(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(8_000, block) }

    private class LiteralWaitingTerminal : ITerminalSession {
        override val output = MutableStateFlow("")
        val entered = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val executions = AtomicInteger()
        val forbiddenCalls = AtomicInteger()
        private fun forbidden(): Nothing { forbiddenCalls.incrementAndGet(); error("Forbidden fixture terminal route") }
        override fun start() = forbidden()
        override fun send(cmd: String) = forbidden()
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = forbidden()
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult = forbidden()
        override suspend fun execStreamingWithInput(cmd: String, timeoutMs: Long, input: ProcessInputBuffer, onOutput: (String) -> Unit): ExecResult = forbidden()
        override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult {
            if (cmd != LITERAL || timeoutMs != 0L || executions.incrementAndGet() != 1) forbidden()
            entered.complete(Unit)
            try { awaitCancellation() } finally { finished.complete(Unit) }
        }
        override fun interrupt() = forbidden()
        override fun clear() = forbidden()
        override fun destroy() = forbidden()
    }

    private companion object {
        const val LITERAL = "r39-literal-quiet-fixture"
        const val SESSION = "r39-fixture-session"
    }
}
