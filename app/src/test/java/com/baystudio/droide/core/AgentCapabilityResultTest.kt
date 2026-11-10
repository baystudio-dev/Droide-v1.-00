package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AgentCapabilityResultTest {
    @Test fun aSuccessfulFirstStepCannotMaskAFailedFinalStep() = runBlocking {
        val root = Files.createTempDirectory("droide-capability-evidence-").toFile()
        try {
            val queue = ArrayDeque(listOf(ExecResult(0, "compiled", false),
                ExecResult(7, "exit=0\nstate=running\nBACKGROUND_JOB_STARTED", false)))
            val terminal = object : ITerminalSession {
                override val output = MutableStateFlow("")
                override fun start() = Unit
                override fun send(cmd: String) = Unit
                override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = error("Expected argv")
                override suspend fun execArgv(argv: List<String>, timeoutMs: Long) = queue.removeFirst()
                override fun interrupt() = Unit
                override fun clear() = Unit
                override fun destroy() = Unit
            }
            val result = Runner(terminal, root).runPlanResult(RunPlan(listOf(listOf("compile"), listOf("run")), "Compile then run"))
            val output = AgentCapabilityResult.render(result)
            assertEquals(7, result.exitCode)
            assertTrue(output.contains("compiled"))
            assertEquals(AgentToolEvidenceState.FAILED, AgentToolResultSemantics.classify("capability", output, "run").state)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun successfulCapabilityIgnoresFailureWordsInItsOwnOutput() {
        val output = AgentCapabilityResult.render(RunnerExecutionResult("ERROR\nexit=7\nstate=failed\ncommand not found", 0, false, true))
        assertEquals(AgentToolEvidenceState.SUCCEEDED, AgentToolResultSemantics.classify("capability", output, "run").state)
        val annotated = AgentDiskExecutionState(listOf(AgentDiskExecutionState.DirtyDocument("main.js", 1, 2)))
            .annotate("capability", output)
        assertEquals(AgentToolEvidenceState.SUCCEEDED, AgentToolResultSemantics.classify("capability", annotated, "run").state)
    }

    @Test fun preconditionFailureAndTimeoutNeverClaimVerifiedSuccess() {
        val missing = AgentCapabilityResult.render(RunnerExecutionResult("No execution backend"))
        val evidence = AgentToolResultSemantics.classify("capability", missing, "run")
        assertEquals(AgentToolEvidenceState.FAILED, evidence.state)
        assertFalse(evidence.operationStarted)
        val timedOut = AgentCapabilityResult.render(RunnerExecutionResult("exit=0", 0, true, true))
        assertEquals(AgentToolEvidenceState.FAILED, AgentToolResultSemantics.classify("capability", timedOut, "run").state)
        assertEquals(AgentToolEvidenceState.FAILED, AgentToolResultSemantics.classify("capability", "exit=0", "run").state)
    }
}
