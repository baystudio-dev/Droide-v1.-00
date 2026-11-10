package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeBuildTaskRegressionTest {
    @get:Rule val temp = TemporaryFolder()

    private fun config(path: String, text: String) = File(temp.root, path).apply { parentFile.mkdirs(); writeText(text) }

    @Test fun nativeSourceOutputsAreOwnedIsolatedAndContainDebugSymbols() {
        val c = LanguageRegistry.runPlanFor("src/a file.c")!!
        val cpp = LanguageRegistry.runPlanFor("src/a file.cpp")!!
        assertNotEquals(c.preparation!!.directory, cpp.preparation!!.directory)
        assertEquals("gcc", c.steps[0][0]); assertEquals("g++", cpp.steps[0][0])
        assertTrue(c.steps[0].containsAll(listOf("-g", "-O0", "-std=c17", "./src/a file.c")))
        assertTrue(cpp.steps[0].contains("-std=c++17"))
        SourceRunPreparation.prepare(temp.root, c.preparation!!)
        assertEquals("src/a file.c\n", File(temp.root, "${c.preparation!!.directory}/source-path.txt").readText())
        assertTrue(c.steps[1][0].startsWith("./${c.preparation!!.directory}/"))
    }

    @Test fun headersAreLanguageDocumentsAndCannotBeAccidentallyExecuted() {
        for (path in listOf("include/a.h", "a.hpp", "a.hh", "a.hxx", "a.h++")) {
            assertNotNull(LanguageRegistry.forFile(path))
            try { LanguageRegistry.runPlanFor(path); fail("Header was compiled as a program") } catch (error: IllegalArgumentException) {
                assertTrue(error.message!!.contains("Headers cannot be run"))
            }
        }
        assertEquals("cmake", LanguageRegistry.forFile("CMakeLists.txt")!!.id)
        assertEquals("cpp", LanguageRegistry.forFile("main.C")!!.id)
        assertEquals("g++", LanguageRegistry.runPlanFor("main.C")!!.steps.first().first())
        assertEquals("bash", LanguageRegistry.runPlanFor("shell.bash")!!.steps.first().first())
        assertEquals("zsh", LanguageRegistry.runPlanFor("shell.zsh")!!.steps.first().first())
        assertTrue(LanguageRegistry.all.single { it.id == "assembly" }.template.contains("mov w0"))
    }

    private fun terminal(calls: MutableList<List<String>>, gccAvailable: Boolean, compileExit: Int = 0) = object : ITerminalSession {
        override val output = MutableStateFlow("")
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult {
            calls += argv
            val command = if (argv.getOrNull(3) == "droide-workspace-command") argv.drop(5) else argv
            return if (command.first() == "sh") ExecResult(if (command.last() == "gcc" && !gccAvailable) 1 else 0, "", false)
                else ExecResult(if (command.first() in setOf("gcc", "clang")) compileExit else 0, "compiler output", false)
        }
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = error("argv required")
        override fun start() = Unit
        override fun send(cmd: String) = Unit
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun destroy() = Unit
    }

    @Test fun installedGccIsNotSilentlyReplacedAndFailedCompilationNeverRunsBinary() = runBlocking {
        val calls = mutableListOf<List<String>>()
        val result = Runner(terminal(calls, true, 1), temp.root).runPlanResult(SourceRunPreparation.nativePlan("main.c"))
        assertEquals(1, result.exitCode)
        assertEquals("gcc", calls.last()[5])
        assertFalse(calls.any { it.getOrNull(5)?.startsWith("./") == true })
        assertFalse(calls.any { it.getOrNull(5) == "clang" })
    }

    @Test fun clangFallbackOnlyOccursWhenGccIsAbsent() = runBlocking {
        val calls = mutableListOf<List<String>>()
        assertTrue(Runner(terminal(calls, false), temp.root).runPlanResult(SourceRunPreparation.nativePlan("main.c")).success)
        assertEquals("clang", calls[2][5])
        assertTrue(calls.last()[5].endsWith("/program"))
    }

    @Test fun vscodeJsoncProcessTaskPreservesLiteralArgumentsAndScopedEnvironment() = runBlocking {
        config("src/a ' & file.cpp", "source")
        config(".vscode/tasks.json", """{
          // No host shell expansion
          "version":"2.0.0",
          "tasks":[{"label":"compile","type":"process","command":"g++",
            "args":["${'$'}{file}","-o","${'$'}{workspaceFolder}/app"],
            "options":{"cwd":"${'$'}{workspaceFolder}","env":{"CC":"gcc","MARKER":"${'$'}(touch sentinel)"}},
            "group":{"kind":"build","isDefault":true},
          }],
        }""")
        val task = TaskManager(temp.root, activeFile = { "src/a ' & file.cpp" }).list().single()
        assertEquals("build", task.group)
        assertEquals(File(temp.root, "src/a ' & file.cpp").canonicalPath, task.argv[1])
        assertEquals("\$(touch sentinel)", task.environment["MARKER"])
        assertEquals(temp.root.canonicalFile, task.cwd)
        assertEquals(".vscode/tasks.json", task.source)
    }

    @Test fun preprocessedAssemblyAndNativeRemotePreparationUseTheSameOwnedContract() {
        val plan = SourceRunPreparation.nativePlan("src/start.S")
        assertTrue(plan.steps.first().contains("assembler-with-cpp"))
        val files = SourceRunPreparation.prepare(temp.root, plan.preparation!!)
        val commands = SourceRunPreparation.remotePreparationCommands(plan.preparation!!, files)
        assertEquals(plan.preparation!!.directory, commands.first().last())
        assertTrue(commands.any { it.contains("source-path.txt") })
    }

    @Test fun jsoncCommentsInsideStringsAndMalformedInputsAreHandledWithoutGuessing() {
        val parsed = WorkspaceJsonConfig.parseObject("""{/* comment */"url":"https://example.org/a,]//x","text":"/*literal*/",}""")
        assertEquals("https://example.org/a,]//x", parsed["url"]!!.toString().trim('"'))
        for (text in listOf("{/* open", "{\"x\":\"unterminated", "[]", "{\"x\":1,,}")) {
            assertTrue(runCatching { WorkspaceJsonConfig.parseObject(text) }.isFailure)
        }
    }

    @Test fun cmakeBuildAndTestRequireTheirPrerequisitesAndExposeCompileDatabase() = runBlocking {
        config("CMakeLists.txt", "cmake_minimum_required(VERSION 3.22)")
        val tasks = TaskManager(temp.root).list().associateBy { it.label }
        assertEquals(listOf("CMake: configure", "CMake: build", "CMake: test"), TaskDependencyGraph.order("CMake: test", tasks).map { it.label })
        assertTrue(tasks.getValue("CMake: configure").argv.contains("-DCMAKE_EXPORT_COMPILE_COMMANDS=ON"))
        assertEquals(listOf("--parallel", "2"), tasks.getValue("CMake: build").argv.takeLast(2))
    }

    @Test fun failedPrerequisiteStopsGraphAndUnknownOrCyclicDependencyStartsNothing() = runBlocking {
        config(".droide/tasks.json", """{"tasks":[
          {"label":"build","command":["make"]},
          {"label":"launch","command":["./app"],"dependsOn":"build"},
          {"label":"cycle-a","command":["true"],"dependsOn":"cycle-b"},
          {"label":"cycle-b","command":["true"],"dependsOn":"cycle-a"},
          {"label":"missing","command":["true"],"dependsOn":"absent"}
        ]}""")
        val calls = mutableListOf<String>()
        val manager = TaskManager(temp.root, taskExecutor = { task, _ ->
            calls += task.label; TaskResult(task, if (task.label == "build") 2 else 0, "output", false, 1)
        })
        assertEquals(2, manager.runNamed("launch").exitCode)
        assertEquals(listOf("build"), calls)
        for (label in listOf("cycle-a", "missing")) assertTrue(runCatching { manager.runNamed(label) }.isFailure)
        assertEquals(listOf("build"), calls)
    }

    @Test fun graphDeduplicatesDiamondDependenciesAndCancellationStopsRemainingCommands() = runBlocking {
        val shared = IdeTask("shared", listOf("true"), temp.root)
        val a = IdeTask("a", listOf("true"), temp.root, dependsOn = listOf("shared"))
        val b = IdeTask("b", listOf("true"), temp.root, dependsOn = listOf("shared"))
        val all = IdeTask("all", emptyList(), temp.root, steps = emptyList(), dependsOn = listOf("a", "b"))
        assertEquals(listOf("shared", "a", "b", "all"), TaskDependencyGraph.order("all", listOf(shared,a,b,all).associateBy { it.label }).map { it.label })
        val manager = TaskManager(temp.root, taskExecutor = { _, _ -> throw CancellationException("stopped") })
        try { manager.run(shared); fail("Cancellation was swallowed") } catch (_: CancellationException) { }
    }

    @Test fun unsafeMetadataEnvironmentAndLegacyAndroidScopeAreRejectedBeforeExecution() = runBlocking {
        config(".vscode/tasks.json", """{"tasks":[
          {"label":"bad-cwd","type":"process","command":"true","options":{"cwd":"../outside"}},
          {"label":"bad-env","type":"process","command":"true","options":{"env":{"X=evil":"yes"}}},
          {"label":"bad-background","command":"true","isBackground":{}},
          {"label":"unknown-var","command":"true","args":["${'$'}{input:password}"]},
          {"label":"wrong-runtime","command":"true","executionScope":"device-workstation"}
        ]}""")
        assertTrue(TaskManager(temp.root).list().isEmpty())
        var invoked = false
        val manager = TaskManager(temp.root, taskExecutor = { t, _ -> invoked = true; TaskResult(t,0,"",false,0) })
        assertTrue(runCatching { manager.run(IdeTask("bad",listOf("true"),temp.root,executionScope=TaskExecutionScope.LOCAL)) }.isFailure)
        assertFalse(invoked)
    }

    @Test fun nativeSourceArgumentCannotBecomeCompilerOption() {
        for (source in listOf("-main.c", "-main.cpp", "nested/-main.c", "space file.c")) {
            val step = SourceRunPreparation.nativePlan(source).steps.first()
            assertEquals("./$source", step[step.indexOf("-o") - 1])
        }
    }

    private fun compileUnusualNativeName(name: String) {
        org.junit.Assume.assumeTrue(File("/usr/bin/gcc").canExecute() && File("/usr/bin/g++").canExecute())
        config(name, "int main(void) { return 0; }")
        val plan = SourceRunPreparation.nativePlan(name)
        SourceRunPreparation.prepare(temp.root, plan.preparation!!)
        val compile = ProcessBuilder(plan.steps.first()).directory(temp.root).redirectErrorStream(true).start()
        try {
            assertTrue(compile.waitFor(20, java.util.concurrent.TimeUnit.SECONDS))
            val log = compile.inputStream.bufferedReader().readText()
            assertEquals(name + ": " + log, 0, compile.exitValue())
        } finally { if (compile.isAlive) compile.destroyForcibly() }
        val run = ProcessBuilder(plan.steps[1]).directory(temp.root).redirectErrorStream(true).start()
        try {
            assertTrue(run.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(0, run.exitValue())
        } finally { if (run.isAlive) run.destroyForcibly() }
    }

    @Test fun leadingDashCFileCompiles() = compileUnusualNativeName("-main.c")
    @Test fun leadingDashCppFileCompiles() = compileUnusualNativeName("-main.cpp")
}
