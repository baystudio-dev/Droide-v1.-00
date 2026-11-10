package com.baystudio.droide.core

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceCommandInvocationTest {
    @get:org.junit.Rule val applicationAdmission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    @get:Rule val temp = TemporaryFolder()

    private class InheritedDirectoryTerminal(val humanDirectory: File) : ITerminalSession {
        override val output = MutableStateFlow("")
        val calls = mutableListOf<List<String>>()
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult = withContext(Dispatchers.IO) {
            calls += argv
            if (argv.lastOrNull()?.contains("command -v ruff") == true) {
                return@withContext ExecResult(0, "python3", false)
            }
            val process = ProcessBuilder(argv).directory(humanDirectory).redirectErrorStream(true).start()
            check(process.waitFor(5, TimeUnit.SECONDS)) { "Host command did not finish" }
            ExecResult(process.exitValue(), process.inputStream.bufferedReader().readText(), false)
        }
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = error("Literal argv expected")
        override fun start() = Unit
        override fun send(cmd: String) = Unit
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun destroy() = Unit
    }

    private class MappedTerminal(private val remoteRoot: String, private val shell: String,
                                 private val beforeCommand: suspend () -> Unit = {}) : ITerminalSession, WorkspaceCommandSession {
        override val output = MutableStateFlow("")
        val calls = mutableListOf<List<String>>()
        override suspend fun execWorkspaceArgv(workspaceRoot: File, directory: File, command: List<String>, timeoutMs: Long): ExecResult {
            beforeCommand()
            return execArgv(WorkspaceCommandInvocation.remoteArgv(workspaceRoot, directory, remoteRoot, command, shell), timeoutMs)
        }
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult {
            calls += argv
            return ExecResult(0, "backend command completed", false)
        }
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = error("Literal argv expected")
        override fun start() = Unit
        override fun send(cmd: String) = Unit
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun destroy() = Unit
    }

    @Test fun runFileReadsWorkspaceSourceAfterHumanChangedDirectory() = runBlocking {
        val human = temp.newFolder("human-subdirectory")
        File(temp.root, "main.sh").writeText("printf 'workspace source'")
        File(human, "main.sh").writeText("printf 'wrong source'")
        val terminal = InheritedDirectoryTerminal(human)
        val result = Runner(terminal, temp.root).runPlanResult(RunPlan(listOf(listOf("/bin/sh", "main.sh")), "Run source"))
        assertTrue(result.success)
        assertTrue(result.output.contains("workspace source"))
        assertFalse(result.output.contains("wrong source"))
        assertEquals(human, terminal.humanDirectory)
    }

    @Test fun taskGraphUsesEachDeclaredDirectoryWithoutChangingHumanShell() = runBlocking {
        val human = temp.newFolder("human-subdirectory")
        val source = temp.newFolder("src ' & directory")
        File(temp.root, "marker.txt").writeText("project root")
        File(source, "marker.txt").writeText("declared source directory")
        File(human, "marker.txt").writeText("human directory must stay unchanged")
        File(temp.root, ".droide").mkdirs()
        File(temp.root, ".droide/tasks.json").writeText("""{"tasks":[
          {"label":"root","command":["cat","marker.txt"]},
          {"label":"source","command":["cat","marker.txt"],"cwd":"src ' & directory","dependsOn":"root"}
        ]}""")
        val terminal = InheritedDirectoryTerminal(human)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val terminals = TerminalManager(scope, temp.root, ubuntuOnly = true,
            linuxLaunchSpecFactory = { _, _ -> TerminalLaunchSpec(prefix = listOf("/usr/bin/env"), shell = "/bin/sh") },
            localSessionFactory = { _, _, _ -> terminal })
        try {
            terminals.openLinuxTerminal()
            val result = TaskManager(temp.root, terminals).runNamed("source")
            assertEquals(0, result.exitCode)
            assertTrue(result.output.contains("project root"))
            assertTrue(result.output.contains("declared source directory"))
            assertFalse(result.output.contains("human directory must stay unchanged"))
            assertEquals(human, terminal.humanDirectory)
            assertSame(terminal, terminals.activeTab()!!.session)
        } finally {
            terminals.dispose(false)
            scope.cancel()
        }
    }

    @Test fun fallbackDiagnosticsChecksProjectFileRatherThanSameNameUnderHumanCwd() = runBlocking {
        val human = temp.newFolder("human-subdirectory")
        File(temp.root, "main.py").writeText("value = 1\n")
        File(human, "main.py").writeText("this is intentionally invalid Python !\n")
        val terminal = InheritedDirectoryTerminal(human)
        val result = CliLspManager(terminal, FileRepository(temp.root)).diagnose("main.py")
        assertTrue(result, result.startsWith("OK: main.py"))
        assertEquals(human, terminal.humanDirectory)
        assertFalse(File(human, "__pycache__").exists())
    }

    @Test fun directoryAndCommandArgumentsStayLiteral() = runBlocking {
        val work = temp.newFolder("workspace ' ; & \$() 😀")
        val human = temp.newFolder("human")
        val terminal = InheritedDirectoryTerminal(human)
        val literal = "\$(touch should-not-exist); ' & *"
        val result = terminal.execArgv(WorkspaceCommandInvocation.argv(work,
            listOf("/bin/sh", "-c", "printf '%s\\n%s' \"${'$'}PWD\" \"${'$'}1\"", "test", literal)), 5_000)
        assertEquals(0, result.exitCode)
        assertEquals(work.canonicalPath + "\n" + literal, result.output)
        assertFalse(File(work, "should-not-exist").exists())
        assertFalse(File(human, "should-not-exist").exists())
    }

    @Test fun missingDeclaredDirectoryNeverRunsCommandInHumanDirectory() = runBlocking {
        val human = temp.newFolder("human")
        val terminal = InheritedDirectoryTerminal(human)
        val result = terminal.execArgv(WorkspaceCommandInvocation.argv(File(temp.root, "missing"),
            listOf("/bin/sh", "-c", "touch must-not-run")), 5_000)
        assertNotEquals(0, result.exitCode)
        assertFalse(File(human, "must-not-run").exists())
    }

    @Test fun deviceBackendUsesItsRemoteWorkspaceAndAndroidShell() = runBlocking {
        val source = temp.newFolder("source ' & directory")
        val remote = "/data/local/tmp/droide/workspaces/project-123"
        val terminal = MappedTerminal(remote, "/system/bin/sh")
        val argv = listOf("kotlinc", "main ' file.kt", "-d", File(temp.root, ".droide/tmp/check.jar").absolutePath)
        assertEquals(0, WorkspaceCommandInvocation.execute(terminal, temp.root, source, argv, 5_000).exitCode)
        val invocation = terminal.calls.single()
        assertEquals("/system/bin/sh", invocation[0])
        assertEquals("$remote/${source.name}", invocation[4])
        assertEquals(listOf("kotlinc", "main ' file.kt", "-d", "$remote/.droide/tmp/check.jar"), invocation.drop(5))
        assertFalse(invocation[4].startsWith(temp.root.canonicalPath))
    }

    @Test fun runnerKeepsExplicitAndroidShellBackendInsteadOfAssumingUbuntu() = runBlocking {
        val terminal = MappedTerminal(temp.root.canonicalPath, "/system/bin/sh")
        val result = Runner(terminal, temp.root).runPlanResult(RunPlan(listOf(listOf("echo", "hello")), "Run"))
        assertTrue(result.success)
        assertEquals("/system/bin/sh", terminal.calls.single().first())
        assertEquals(temp.root.canonicalPath, terminal.calls.single()[4])
    }

    @Test fun routerPinsBackendBeforeSuspendingAndNeverFollowsTabSwitch() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = MappedTerminal("/remote/first", "/system/bin/sh") { entered.complete(Unit); release.await() }
        val second = MappedTerminal("/remote/second", "/bin/sh")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var created = 0
        val terminals = TerminalManager(scope, temp.root) { _, _, _ -> if (created++ == 0) first else second }
        try {
            val one = terminals.createLocal()
            val two = terminals.createLocal()
            terminals.switch(one.id)
            val router = ExecutionTerminalRouter(terminals)
            val invocation = async(start = CoroutineStart.UNDISPATCHED) {
                router.execWorkspaceArgv(temp.root, temp.root, listOf("echo", "first"), 5_000)
            }
            entered.await()
            terminals.switch(two.id)
            release.complete(Unit)
            assertEquals(0, withTimeout(5_000) { invocation.await() }.exitCode)
            assertEquals("/remote/first", first.calls.single()[4])
            assertTrue(second.calls.isEmpty())
        } finally {
            release.complete(Unit)
            terminals.dispose(false)
            scope.cancel()
        }
    }
}
