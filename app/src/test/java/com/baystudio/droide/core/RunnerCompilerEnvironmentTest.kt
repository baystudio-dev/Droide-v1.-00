package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunnerCompilerEnvironmentTest {
    @get:Rule val temp = TemporaryFolder()
    private class Session(private val gcc: Boolean, private val probeTimeout: Boolean = false) : ITerminalSession, WorkspaceCommandSession {
        override val output = MutableStateFlow("")
        val ordinary = mutableListOf<List<String>>()
        val workspace = mutableListOf<List<String>>()
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult {
            ordinary += argv
            // The old launch environment can see clang but not the user's GCC.
            return ExecResult(if (argv.last() in setOf("gcc", "g++")) 1 else 0, "", false)
        }
        override suspend fun execWorkspaceArgv(workspaceRoot: File, directory: File, command: List<String>, timeoutMs: Long): ExecResult {
            workspace += command
            return if (command.first() == "sh")
                ExecResult(if (command.last() in setOf("gcc", "g++") && !gcc) 1 else 0, "", probeTimeout)
            else ExecResult(0, "ok", false)
        }
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = error("literal argv required")
        override fun start() = Unit
        override fun send(cmd: String) = Unit
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun destroy() = Unit
    }
    @Test fun compilerAvailableInWorkspaceEnvironmentIsNotReplaced() = runBlocking {
        val session = Session(true)
        assertTrue(Runner(session, temp.root).runPlanResult(SourceRunPreparation.nativePlan("main.c")).success)
        assertTrue("Probe must use the same workspace environment as compilation", session.ordinary.isEmpty())
        assertEquals(listOf("sh", "gcc"), session.workspace.take(2).map { it.first() })
        assertEquals("gcc", session.workspace.first().last())
        assertFalse(session.workspace.any { it.first() == "clang" })
    }
    @Test fun fallbackIsAlsoProbedInWorkspaceEnvironment() = runBlocking {
        val session = Session(false)
        assertTrue(Runner(session, temp.root).runPlanResult(SourceRunPreparation.nativePlan("main.c")).success)
        assertTrue(session.ordinary.isEmpty())
        assertEquals(listOf("gcc", "clang"), session.workspace.take(2).map { it.last() })
        assertEquals("clang", session.workspace[2].first())
    }
    @Test fun cppCompilerUsesTheSameWorkspaceProbeContract() = runBlocking {
        val session = Session(true)
        assertTrue(Runner(session, temp.root).runPlanResult(SourceRunPreparation.nativePlan("main.cpp")).success)
        assertTrue(session.ordinary.isEmpty())
        assertEquals("g++", session.workspace.first().last())
        assertEquals("g++", session.workspace[1].first())
    }
    @Test fun timedOutProbeDoesNotAuthorizeFallbackReplacement() = runBlocking {
        val session = Session(false, probeTimeout = true)
        assertTrue(Runner(session, temp.root).runPlanResult(SourceRunPreparation.nativePlan("main.c")).success)
        assertTrue(session.ordinary.isEmpty())
        assertEquals(1, session.workspace.count { it.first() == "sh" })
        assertEquals("gcc", session.workspace[1].first())
    }

}
