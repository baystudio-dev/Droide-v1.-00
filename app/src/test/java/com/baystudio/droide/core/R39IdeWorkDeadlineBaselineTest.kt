package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Inert boundary tests. No production terminal, coordinator, or process is constructed. */
class R39IdeWorkDeadlineBaselineTest {
    @get:Rule val temp = TemporaryFolder()

    private data class Call(val argv: List<String>, val timeoutMs: Long)
    private class Terminal(
        val workspace: suspend (List<String>, Long) -> ExecResult,
        val direct: suspend (List<String>, Long) -> ExecResult = { _, _ -> error("Unexpected direct argv") },
    ) : ITerminalSession, WorkspaceCommandSession {
        override val output = MutableStateFlow("")
        val calls = mutableListOf<Call>()
        val probes = mutableListOf<Call>()
        override suspend fun execWorkspaceArgv(workspaceRoot: File, directory: File, command: List<String>, timeoutMs: Long): ExecResult {
            assertEquals(workspaceRoot.canonicalFile, directory.canonicalFile)
            calls += Call(command.toList(), timeoutMs)
            return workspace(command, timeoutMs)
        }
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult {
            probes += Call(argv.toList(), timeoutMs)
            return direct(argv, timeoutMs)
        }
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = error("No command execution")
        override fun start(): Unit = error("No terminal startup")
        override fun send(cmd: String): Unit = error("No terminal input")
        override fun interrupt(): Unit = error("No terminal interruption")
        override fun clear() = Unit
        override fun destroy() = Unit
    }

    @Test fun contributedRunHasNoImplicitWorkDeadline() {
        assertEquals(0L, IdeRunTimeoutPolicy.forContributed(DroideToolKind.RUN))
    }

    @Test fun contributedCliHasNoImplicitWorkDeadline() {
        assertEquals(0L, IdeRunTimeoutPolicy.forContributed(DroideToolKind.CLI))
    }

    @Test fun contributedLinterHasNoImplicitWorkDeadline() {
        assertEquals(0L, IdeRunTimeoutPolicy.forContributed(DroideToolKind.LINTER))
    }

    @Test fun formatterBuildTestAndSharedRunnerDefaultsRemainUnchanged() {
        assertEquals(600_000L, IdeRunTimeoutPolicy.FORMATTER_DEADLINE_MS)
        assertEquals(600_000L, IdeRunTimeoutPolicy.forContributed(DroideToolKind.FORMATTER))
        assertEquals(0L, IdeRunTimeoutPolicy.forContributed(DroideToolKind.BUILD))
        assertEquals(0L, IdeRunTimeoutPolicy.forContributed(DroideToolKind.TEST))
        assertEquals(1_800_000L, Runner.DEFAULT_RUN_TIMEOUT_MS)
        assertEquals(86_400_000L, Runner.MAX_RUN_TIMEOUT_MS)
    }

    @Test(timeout = 10_000) fun runnerExplicitZeroReachesEveryStepWithoutChangingArgv() = runBlocking {
        val terminal = Terminal(workspace = { _, _ -> ExecResult(0, "synthetic result", false) })
        val steps = listOf(listOf("r39-fake-compile", "a ' file"), listOf("r39-fake-program", "literal ; value"))
        val result = Runner(terminal, temp.root).runPlanResult(RunPlan(steps, "Inert two-step plan"), timeoutMs = 0L)
        assertTrue(result.success)
        assertEquals(steps.map { Call(it, 0L) }, terminal.calls)
        assertTrue(terminal.probes.isEmpty())
    }

    @Test(timeout = 10_000) fun runnerExplicitPositiveAndDefaultBudgetsPreserveTimeoutFailFast() = runBlocking {
        val terminal = Terminal(workspace = { argv, _ -> ExecResult(0, "synthetic", argv.first() == "r39-timeout") })
        val runner = Runner(terminal, temp.root)
        val steps = listOf(listOf("r39-first"), listOf("r39-timeout"), listOf("r39-never"))
        val result = runner.runPlanResult(RunPlan(steps, "Inert deadline result"), timeoutMs = 1_500L)
        assertTrue(result.timedOut)
        assertFalse(result.success)
        assertEquals(steps.take(2).map { Call(it, 1_500L) }, terminal.calls)
        val one = RunPlan(listOf(listOf("r39-default")), "Inert default control")
        assertTrue(runner.runPlanResult(one).success)
        assertEquals(Call(one.steps.single(), 1_800_000L), terminal.calls.last())
        for (invalid in listOf(-1L, 999L, Runner.MAX_RUN_TIMEOUT_MS + 1)) {
            assertTrue(runCatching { runner.runPlanResult(one, invalid) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals(3, terminal.calls.size)
    }

    @Test(timeout = 10_000) fun runnerZeroCancellationWaitsForFakeCleanupAndSkipsNextStep() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var cleaned = false
        val terminal = Terminal(workspace = { _, timeout ->
            assertEquals(0L, timeout)
            entered.complete(Unit)
            try { awaitCancellation() } finally {
                withContext(NonCancellable) {
                    cleaning.complete(Unit)
                    withTimeout(2_000) { release.await() }
                    cleaned = true
                }
            }
        })
        val job = async {
            Runner(terminal, temp.root).runPlanResult(
                RunPlan(listOf(listOf("r39-wait"), listOf("r39-never")), "Inert cancellation"), 0L)
        }
        try {
            withTimeout(2_000) { entered.await() }
            job.cancel(CancellationException("Explicit test Stop"))
            withTimeout(2_000) { cleaning.await() }
            assertFalse(job.isCompleted)
            release.complete(Unit)
            withTimeout(2_000) { job.join() }
            assertTrue(job.isCancelled)
            assertTrue(cleaned)
            assertEquals(listOf(Call(listOf("r39-wait"), 0L)), terminal.calls)
        } finally {
            release.complete(Unit)
            withContext(NonCancellable) { withTimeout(2_000) { job.cancelAndJoin() } }
        }
    }

    @Test(timeout = 10_000) fun compilerDiscoveryProbesStayBoundedWhenWorkUsesZero() = runBlocking {
        val terminal = Terminal(
            workspace = { _, _ -> ExecResult(0, "synthetic compile", false) },
            direct = { argv, _ -> ExecResult(if (argv.last() == "gcc") 1 else 0, "synthetic probe", false) },
        )
        assertTrue(Runner(terminal, temp.root).runPlanResult(
            RunPlan(listOf(listOf("gcc", "source.c")), "Inert compiler fallback"), 0L).success)
        assertEquals(listOf(5_000L, 5_000L), terminal.probes.map { it.timeoutMs })
        assertEquals(listOf("gcc", "clang"), terminal.probes.map { it.argv.last() })
        assertEquals(listOf(Call(listOf("clang", "source.c"), 0L)), terminal.calls)
    }
}
