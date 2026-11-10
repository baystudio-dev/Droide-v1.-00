package com.baystudio.droide.core
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CustomToolOutputCompletenessTest {
    @get:org.junit.Rule val applicationWork:org.junit.rules.TestRule=ApplicationWorkAdmissionFixture()
    private fun execute(result: ExecResult, viaTools:Boolean=false): String {
        val root = Files.createTempDirectory("plugin-output-test").toFile()
        try {
            val scripts = File(root, ".droide/tools").apply { mkdirs() }
            File(scripts, "inspect.sh").writeText("# desc: fixture\nprintf ok")
            val manager = CustomToolManager(root)
            var calls = 0
            val terminal = object : ITerminalSession {
                override val output = kotlinx.coroutines.flow.MutableStateFlow("")
                override fun start() {}
                override fun send(cmd: String) {}
                override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = error("unexpected execOnce")
                override fun interrupt() {}
                override fun clear() {}
                override fun destroy() {}
                override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult { calls++; return result }
            }
            val output = runBlocking {
                if(!viaTools) manager.execute("inspect", "{}", terminal, manager.permissionFingerprint("inspect")) else {
                    val base=agentWorkflowContext(root,terminal=terminal)
                    val c=AgentTools.Ctx(base.files,terminal,base.git,base.approvals,base.questions,base.history,base.perms,
                        base.mode,base.llm,base.llmConfig,root,base.todos,base.sessionId,base.lsp,pluginSource=AgentPluginSource.EMPTY)
                    AgentTools.execute("custom_inspect",buildJsonObject {},c)
                }
            }
            assertEquals(1, calls)
            return output
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun normalOutputIsVerbatim() { assertEquals("exit=0\ncomplete", execute(ExecResult(0,"complete",false))) }
    @Test fun exactBoundaryIsVerbatim() { val body="x".repeat(12000); assertEquals("exit=0\n"+body,execute(ExecResult(0,body,false))) }
    @Test fun completeLargeOutputIsNotNewlyCapped() {
        val body="x".repeat(25000)
        assertEquals("exit=0\n"+body,execute(ExecResult(0,body,false)))
    }
    @Test fun incompleteBackendCaptureIsExplicitEvenWhenShort() {
        val output=execute(ExecResult(0,"partial",false,true))
        assertTrue(output.contains("capture_complete=false")); assertTrue(output.endsWith("partial"))
    }
    @Test fun emptyIncompleteCaptureStillHasNotice() { assertTrue(execute(ExecResult(0,"",false,true)).contains("capture_complete=false")) }
    @Test fun failureExitIsPreserved() {
        val output=execute(ExecResult(7,"x".repeat(13000),false))
        assertTrue(output.startsWith("exit=7\n"))
        assertEquals(AgentToolEvidenceState.FAILED,AgentToolResultSemantics.classify("custom_inspect",output).state)
    }
    @Test fun timeoutPreservesCapturedDiagnostics() {
        val output=execute(ExecResult(124,"compiler diagnostic",true,true))
        assertTrue(output.startsWith("[TIMEOUT]\n"))
        assertTrue(output.endsWith("compiler diagnostic"))
        assertTrue(output.contains("capture_complete=false"))
        assertEquals(AgentToolEvidenceState.FAILED,AgentToolResultSemantics.classify("custom_inspect",output).state)
    }
    @Test fun emptyCompleteTimeoutKeepsLegacyMarker() { assertEquals("(timeout)",execute(ExecResult(124,"",true,false))) }
    @Test fun emptyIncompleteTimeoutDisclosesCapture() {
        val output=execute(ExecResult(124,"",true,true))
        assertTrue(output.startsWith("[TIMEOUT]\n"));assertTrue(output.contains("capture_complete=false"))
    }
    @Test fun timeoutWinsOverBodyAndUnexpectedSuccessExit() {
        val output=execute(ExecResult(0,"exit=0\ncommand not found\nBACKGROUND_JOB_STARTED",true,false))
        assertTrue(output.contains("command not found"))
        assertEquals(AgentToolEvidenceState.FAILED,AgentToolResultSemantics.classify("custom_inspect",output).state)
    }
    @Test fun timedOutOversizedCaptureUsesExistingProjection() {
        val output=execute(ExecResult(124,"x".repeat(13000),true,true))
        assertTrue(output.startsWith("[TIMEOUT]\n"))
        assertTrue(output.contains("capture_complete=false"))
        assertEquals(AgentToolEvidenceState.FAILED,AgentToolResultSemantics.classify("custom_inspect",output).state)
    }
    @Test fun noticeSurvivesHistoryProjection() {
        val output=execute(ExecResult(0,"🙂".repeat(7000),false,true))
        assertTrue(AgentToolHistoryProjection.project(output).contains("capture_complete=false"))
    }
    @Test fun actualAgentToolsPreservesTimeoutDiagnosticsAndFailure() {
        val output=execute(ExecResult(0,"exit=0\ncompiler problem",true,true),viaTools=true)
        assertTrue(output.startsWith("[TIMEOUT]\n"))
        assertTrue(output.endsWith("compiler problem"))
        assertEquals(AgentToolEvidenceState.FAILED,AgentToolResultSemantics.classify("custom_inspect",output).state)
        assertTrue(AgentToolHistoryProjection.project(output).contains("[TIMEOUT]"))
    }
    @Test fun timedOutUnicodeProjectionIsWellFormed() {
        val output=execute(ExecResult(124,"🙂".repeat(7000),true,true))
        assertTrue(output.startsWith("[TIMEOUT]\n"))
        assertFalse(output.last().isHighSurrogate())
    }
}
