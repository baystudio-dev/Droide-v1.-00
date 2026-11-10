package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProjectPythonDispatchTest {
    private fun workspace(block: (File) -> Unit) {
        val root = Files.createTempDirectory("droide-python-dispatch").toFile()
        try { root.resolve("main.py").writeText("print('ok')"); block(root) }
        finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    private fun select(root: File, content: String = "{\"interpreter\":\".venv/bin/python\"}") {
        root.resolve(".droide").mkdirs(); root.resolve(".droide/python.json").writeText(content)
    }
    @Test fun pipAliasHasTheSameEnvironmentSpecificHintAsPip3() {
        for (profile in TerminalManager.Profile.values()) {
            assertEquals(TerminalEnvironmentReadiness.missing("pip3", profile), TerminalEnvironmentReadiness.missing("pip", profile))
        }
        assertEquals("package.pip", TerminalEnvironmentReadiness.missing("pip", TerminalManager.Profile.LOCAL_LINUX_ARM64)!!.managedFamily)
    }
    @Test fun guideExplainsExplicitSelectionAndSeparateDebugScope() {
        val section = TerminalCommandGuide.forProfile(TerminalManager.Profile.LOCAL_LINUX_ARM64).sections.single { it.title == "Python project packages" }
        assertTrue(section.description.contains(".droide/python.json"))
        assertTrue(section.description.contains("Debug/LSP"))
        assertTrue(section.examples.contains(".venv/bin/python -m pip install requests"))
    }
    @Test fun malformedSelectionCannotInvokeContributedFallback() = workspace { root ->
        select(root, "{bad}"); var calls = 0
        val result = Runner.resolveFilePlan("main.py", root) { calls++; RunPlan(listOf(listOf("wrong")), "wrong") }
        assertTrue(result is Runner.FilePlanResolution.Error); assertEquals(0, calls)
    }
    @Test fun compilerAliasLikePythonSelectionDoesNotProbeOrSubstitute() = workspace { root -> runBlocking {
        select(root, "{\"interpreter\":\"gcc\"}")
        val calls = mutableListOf<List<String>>()
        val terminal = object : ITerminalSession, WorkspaceCommandSession {
            override val output = kotlinx.coroutines.flow.MutableStateFlow("")
            override fun start() = error("No terminal startup")
            override fun send(cmd: String) = error("No shell input")
            override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = error("No shell command")
            override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult = error("No alias probe")
            override fun interrupt() = Unit
            override fun clear() = Unit
            override fun destroy() = Unit
            override suspend fun execWorkspaceArgv(workspaceRoot: File, directory: File, command: List<String>, timeoutMs: Long): ExecResult {
                assertEquals(root, directory); calls += command; return ExecResult(127, "selected interpreter missing", false)
            }
        }
        val plan = (Runner.resolveFilePlan("main.py", root) as Runner.FilePlanResolution.Ready).plan
        val result = Runner(terminal, root).runPlanResult(plan, timeoutMs = 0)
        assertEquals(listOf(listOf("gcc", "main.py")), calls)
        assertEquals(127, result.exitCode); assertFalse(result.success)
        assertTrue(result.output.contains("will not fall back"))
    } }
    @Test fun detectedPytestUsesSelectedInterpreterAndCustomTaskStillWins() = workspace { root -> runBlocking {
        root.resolve("pyproject.toml").writeText(""); select(root)
        val automatic = TaskManager(root).list().single { it.label == "Pytest" }
        assertEquals(listOf("./.venv/bin/python", "-m", "pytest"), automatic.argv)
        root.resolve(".droide/tasks.json").writeText("""{"tasks":[{"label":"Pytest","command":["custom-python","-m","pytest"],"options":{"env":{"CUSTOM":"yes"}}}]}""")
        val custom = TaskManager(root).list().single { it.label == "Pytest" }
        assertEquals(listOf("custom-python", "-m", "pytest"), custom.argv)
        assertEquals(mapOf("CUSTOM" to "yes"), custom.environment)
    } }
    @Test fun absentSelectionKeepsLegacyPytest() = workspace { root -> runBlocking {
        root.resolve("pytest.ini").writeText("")
        assertEquals(listOf("pytest"), TaskManager(root).list().single { it.label == "Pytest" }.argv)
    } }
    @Test fun invalidPythonConfigDoesNotBlockOtherTasksButCannotExecutePytest() = workspace { root -> runBlocking {
        root.resolve("pytest.ini").writeText(""); root.resolve("Makefile").writeText(""); select(root, "{bad}")
        val executed = mutableListOf<IdeTask>()
        val tasks = TaskManager(root, taskExecutor = { task, _ -> executed += task; TaskResult(task, 0, "ok", false, 0) })
        assertEquals(setOf("Pytest", "Make"), tasks.list().map { it.label }.toSet())
        try { tasks.runNamed("Pytest"); fail("Invalid selected config must fail before dispatch") }
        catch (failure: IllegalArgumentException) { assertTrue(failure.message!!.contains(".droide/python.json")) }
        assertTrue(executed.isEmpty())
        assertEquals(0, tasks.runNamed("Make").exitCode); assertEquals(listOf("Make"), executed.map { it.label })
    } }
    @Test fun selectedPytestDispatchUsesExactArgvWithoutCreatingVenv() = workspace { root -> runBlocking {
        root.resolve("pytest.ini").writeText(""); select(root)
        var command: List<String>? = null
        val tasks = TaskManager(root, taskExecutor = { task, _ -> command = task.argv; TaskResult(task, 127, "missing", false, 0) })
        assertEquals(127, tasks.runNamed("Pytest").exitCode)
        assertEquals(listOf("./.venv/bin/python", "-m", "pytest"), command)
        assertFalse(root.resolve(".venv").exists())
    } }
}
