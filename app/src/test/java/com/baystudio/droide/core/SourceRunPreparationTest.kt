package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceRunPreparationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun typescriptUsesInstalledCompilerAndNodeWithExactSourcePath() {
        val source = "src/a ' & file.ts"
        val plan = LanguageRegistry.runPlanFor(source)!!
        val prep = plan.preparation!!
        assertEquals("tsc", plan.steps[0][0])
        assertEquals("node", plan.steps[1][0])
        assertEquals(listOf("tsc", "--project", "${prep.directory}/tsconfig.json"), plan.steps[0])
        assertEquals("${prep.directory}/src/a ' & file.js", plan.steps[1][1])
        val config = Json.parseToJsonElement(prep.files.getValue("tsconfig.json")).jsonObject
        assertEquals("../../../../$source", config.getValue("files").jsonArray[0].jsonPrimitive.content)
        assertEquals("NodeNext", config.getValue("compilerOptions").jsonObject.getValue("module").jsonPrimitive.content)
    }

    @Test fun typescriptModuleExtensionsMatchNodeOutput() {
        for ((extension, emitted) in mapOf("ts" to "js", "mts" to "mjs", "cts" to "cjs")) {
            val plan = LanguageRegistry.runPlanFor("main.$extension")!!
            assertTrue(plan.steps[1][1].endsWith("/main.$emitted"))
        }
    }

    @Test fun csharpUsesSdkProjectWithoutTheUnavailableDotnetScriptTool() {
        val plan = LanguageRegistry.runPlanFor("src/a&b;\$(touch sentinel)'%.cs")!!
        val project = plan.preparation!!.files.getValue("DroideRun.csproj")
        assertEquals("dotnet", plan.steps[0][0])
        assertEquals("build", plan.steps[0][1])
        assertEquals(listOf("dotnet", "${plan.preparation!!.directory}/bin/Debug/net8.0/DroideRun.dll"), plan.steps[1])
        assertTrue(plan.steps[0].contains("--property:ImportDirectoryBuildProps=false"))
        assertTrue(plan.steps[0].contains("--property:UseSharedCompilation=false"))
        assertTrue(plan.steps[0].contains("--disable-build-servers"))
        assertTrue(project.contains("<TargetFramework>net8.0</TargetFramework>"))
        assertTrue(project.contains("<EnableDefaultCompileItems>false</EnableDefaultCompileItems>"))
        assertTrue(project.contains("a&amp;b%3B%24%28touch sentinel%29%27%25.cs"))
    }

    @Test fun kotlinScriptsUseTheCompilerScriptEntrypoint() {
        assertEquals(listOf(listOf("kotlinc", "-script", "demo.kts")), LanguageRegistry.runPlanFor("demo.kts")!!.steps)
        assertEquals("kotlinc", LanguageRegistry.runPlanFor("demo.kt")!!.steps[0][0])
    }

    @Test fun preparedProjectsPreserveOriginalUserFiles() {
        File(temp.root, "Main.cs").writeText("Console.WriteLine(\"user\");")
        File(temp.root, "user.csproj").writeText("user project")
        File(temp.root, "tsconfig.json").writeText("user TypeScript config")
        val prep = SourceRunPreparation.cSharpPlan("Main.cs").preparation!!
        val files = SourceRunPreparation.prepare(temp.root, prep)
        assertEquals(setOf("DroideRun.csproj"), files.keys)
        assertTrue(File(temp.root, "${prep.directory}/DroideRun.csproj").isFile)
        assertEquals("user project", File(temp.root, "user.csproj").readText())
        assertEquals("user TypeScript config", File(temp.root, "tsconfig.json").readText())
        assertEquals("Console.WriteLine(\"user\");", File(temp.root, "Main.cs").readText())
    }

    @Test fun nodePackageScopesAreMirroredWithoutScriptsAndRefreshedAfterRemoval() {
        File(temp.root, "package.json").writeText("{\"type\":\"module\",\"scripts\":{\"install\":\"bad\"}}")
        File(temp.root, "nested").mkdir()
        File(temp.root, "nested/package.json").writeText("{\"type\":\"commonjs\"}")
        File(temp.root, "node_modules/dependency").mkdirs()
        File(temp.root, "node_modules/dependency/package.json").writeText("not JSON")
        val prep = SourceRunPreparation.typeScriptPlan("main.ts").preparation!!
        val files = SourceRunPreparation.prepare(temp.root, prep)
        assertEquals(setOf("type"), Json.parseToJsonElement(files.getValue("package.json")).jsonObject.keys)
        assertEquals("module", Json.parseToJsonElement(files.getValue("package.json")).jsonObject.getValue("type").jsonPrimitive.content)
        assertTrue(files.containsKey("nested/package.json"))
        File(temp.root, "nested/package.json").delete()
        SourceRunPreparation.prepare(temp.root, prep)
        assertFalse(File(temp.root, "${prep.directory}/nested/package.json").exists())
    }

    @Test fun unownedGeneratedDirectoriesArePreservedAndRejected() {
        val prep = SourceRunPreparation.cSharpPlan("main.cs").preparation!!
        val directory = File(temp.root, prep.directory).apply { mkdirs() }
        val user = File(directory, "keep.txt").apply { writeText("keep") }
        reject { SourceRunPreparation.prepare(temp.root, prep) }
        assertEquals("keep", user.readText())
    }

    @Test fun generatedDirectoryAndFilesCannotFollowSymlinks() {
        val outside = temp.newFolder("outside")
        val workspace = temp.newFolder("workspace")
        Files.createSymbolicLink(File(workspace, ".droide").toPath(), outside.toPath())
        reject { SourceRunPreparation.prepare(workspace, SourceRunPreparation.cSharpPlan("main.cs").preparation!!) }
        assertTrue(outside.listFiles()!!.isEmpty())
        Files.delete(File(workspace, ".droide").toPath())
        val prep = SourceRunPreparation.cSharpPlan("main.cs").preparation!!
        SourceRunPreparation.prepare(workspace, prep)
        val output = File(workspace, "${prep.directory}/DroideRun.csproj")
        output.delete()
        val sentinel = File(outside, "sentinel").apply { writeText("keep") }
        Files.createSymbolicLink(output.toPath(), sentinel.toPath())
        reject { SourceRunPreparation.prepare(workspace, prep) }
        assertEquals("keep", sentinel.readText())
    }

    @Test fun pathEscapesAndReservedPreparationFilesAreRejected() {
        for (source in listOf("../outside.ts", "/absolute.cs", "main\n.cs", "folder\\main.ts")) {
            reject { SourceRunPreparation.typeScriptPlan(source) }
        }
        val base = SourceRunPreparation.cSharpPlan("main.cs").preparation!!
        reject { SourceRunPreparation.prepare(temp.root, base.copy(files = mapOf("../outside" to "bad"))) }
        reject { SourceRunPreparation.prepare(temp.root, base.copy(files = mapOf(".droide-owned-run" to "bad"))) }
    }

    @Test fun runnerPreparesProjectsBeforeInvokingInstalledCommands() = runBlocking {
        val plan = SourceRunPreparation.cSharpPlan("Main.cs")
        val calls = mutableListOf<List<String>>()
        val terminal = object : ITerminalSession {
            override val output = MutableStateFlow("")
            override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult {
                assertTrue(File(temp.root, "${plan.preparation!!.directory}/DroideRun.csproj").isFile)
                calls += argv
                return ExecResult(0, "ran", false)
            }
            override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = error("expected argv")
            override fun start() = Unit
            override fun send(cmd: String) = Unit
            override fun interrupt() = Unit
            override fun clear() = Unit
            override fun destroy() = Unit
        }
        val result = Runner(terminal, temp.root).runPlanResult(plan)
        assertTrue(result.success)
        assertEquals(plan.steps.map { WorkspaceCommandInvocation.argv(temp.root, it) }, calls)
    }

    @Test fun preparationFailureNeverStartsTheCompiler() = runBlocking {
        val prep = SourceRunPreparation.cSharpPlan("main.cs").preparation!!
        File(temp.root, prep.directory).mkdirs()
        var called = false
        val terminal = object : ITerminalSession {
            override val output = MutableStateFlow("")
            override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult { called = true; error("must not run") }
            override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = error("expected argv")
            override fun start() = Unit
            override fun send(cmd: String) = Unit
            override fun interrupt() = Unit
            override fun clear() = Unit
            override fun destroy() = Unit
        }
        val result = Runner(terminal, temp.root).runPlanResult(SourceRunPreparation.cSharpPlan("main.cs"))
        assertFalse(result.success)
        assertFalse(result.processStarted)
        assertFalse(called)
    }

    @Test fun remoteMaterializationPreservesArgvContentsAndRejectsUnownedOutput() {
        val sourceRoot = temp.newFolder("source")
        File(sourceRoot, "package.json").writeText("{\"type\":\"module\"}")
        val prep = SourceRunPreparation.typeScriptPlan("a ' & \$(touch sentinel).ts").preparation!!
        val files = SourceRunPreparation.prepare(sourceRoot, prep)
        val remote = temp.newFolder("remote")
        for (argv in SourceRunPreparation.remotePreparationCommands(prep, files)) {
            val process = ProcessBuilder(argv).directory(remote).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.waitFor())
        }
        files.forEach { (relative, content) -> assertEquals(content, File(remote, "${prep.directory}/$relative").readText()) }
        assertFalse(File(remote, "sentinel").exists())
        File(remote, "${prep.directory}/.droide-owned-run").delete()
        val sentinel = File(remote, "${prep.directory}/keep.txt").apply { writeText("keep") }
        val process = ProcessBuilder(SourceRunPreparation.remotePreparationCommands(prep, files)[0]).directory(remote).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().readText()
        assertNotEquals(0, process.waitFor())
        assertEquals("keep", sentinel.readText())
    }

    @Test fun concurrentRunsOfOneGeneratedProjectAreSerializedAndCancelledWaitersReleaseTheirGate() = runBlocking {
        val directory = SourceRunPreparation.cSharpPlan("Main.cs").preparation!!.directory
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val first = async {
            SourceRunPreparation.withRunDirectory(temp.root, directory) {
                events += "first"
                entered.complete(Unit)
                finish.await()
            }
        }
        entered.await()
        val cancelled = async {
            SourceRunPreparation.withRunDirectory(temp.root, directory) { events += "cancelled" }
        }
        val second = async {
            SourceRunPreparation.withRunDirectory(temp.root, directory) { events += "second" }
        }
        yield()
        assertEquals(listOf("first"), events)
        cancelled.cancelAndJoin()
        finish.complete(Unit)
        first.await()
        second.await()
        SourceRunPreparation.withRunDirectory(temp.root, directory) { events += "third" }
        assertEquals(listOf("first", "second", "third"), events)
    }

    private fun reject(block: () -> Unit) {
        try { block(); fail("Expected source preparation rejection") } catch (_: IllegalArgumentException) { }
    }
}
