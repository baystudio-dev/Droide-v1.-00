package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DebugVsCodeConfigurationTest {
    private suspend fun fixture(block: suspend (File, DebugManager) -> Unit) {
        val root = Files.createTempDirectory("debug-vscode-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        root.resolve("src").mkdir()
        root.resolve("src/main.py").writeText("print('hello')")
        val host = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command: String): String? = when (command) {
                "python3", "lldb-dap" -> "/usr/bin/$command"
                else -> null
            }
            override suspend fun pathMapper() = WorkspacePathMapper(root, "/workspace")
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess = error("Discovery must not spawn or install a process")
        }
        val manager = DebugManager(scope, FileRepository(root), TerminalManager(scope, root), remoteProcessHost = host)
        try { block(root, manager) }
        finally { manager.close(); scope.cancel(); PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun vscodeJsoncTypeFlatFieldsAndPreLaunchTaskArePreserved() = runBlocking {
        fixture { root, manager ->
            root.resolve(".vscode").mkdir()
            root.resolve(".vscode/launch.json").writeText("""
                { // VS Code configuration is JSONC.
                  "version": "0.2.0",
                  "configurations": [{
                    "name": "Python project", "type": "debugpy", "request": "launch",
                    "program": "${'$'}{file}", "cwd": "${'$'}{workspaceFolder}",
                    "args": ["https://example.com/a//b", "${'$'}{fileBasenameNoExtension}"],
                    "preLaunchTask": "build project",
                    "linux": {"justMyCode": false},
                  }],
                }
            """.trimIndent())
            val config = manager.configurations("src/main.py").first { it.source == ".vscode/launch.json" }
            assertEquals("build project", config.preLaunchTask)
            assertEquals(listOf("/usr/bin/python3", "-m", "debugpy.adapter"), config.adapter)
            assertEquals("/workspace/src/main.py", config.arguments["program"]!!.jsonPrimitive.content)
            assertEquals("/workspace", config.arguments["cwd"]!!.jsonPrimitive.content)
            assertFalse(config.arguments["justMyCode"]!!.jsonPrimitive.boolean)
            assertEquals("main", config.arguments["args"]!!.jsonArray[1].jsonPrimitive.content)
            assertFalse(config.arguments.containsKey("preLaunchTask"))
            assertFalse(config.arguments.containsKey("type"))
            assertTrue(manager.configurationDiagnostics.value.isEmpty())
        }
    }

    @Test fun legacyDroideNestedArgumentsRemainSupported() = runBlocking {
        fixture { root, manager ->
            root.resolve(".droide").mkdir()
            root.resolve(".droide/launch.json").writeText("""
                {"configurations":[{"name":"legacy", "request":"attach", "adapterId":"python-debugpy",
                  "arguments":{"connect":{"host":"127.0.0.1", "port":5678}}}]}
            """.trimIndent())
            val config = manager.configurations("src/main.py").first { it.name == "legacy" }
            assertEquals("attach", config.request)
            assertEquals(5678, config.arguments["connect"]!!.jsonObject["port"]!!.jsonPrimitive.int)
            assertNull(config.preLaunchTask)
        }
    }

    @Test fun unavailableAdaptersUnsafeBackendAndUnresolvedVariablesHaveDiagnostics() = runBlocking {
        fixture { root, manager ->
            root.resolve(".vscode").mkdir()
            root.resolve(".vscode/launch.json").writeText("""
                {"configurations":[
                  {"name":"missing", "type":"cppdbg", "request":"launch", "program":"main"},
                  {"name":"redirect", "type":"debugpy", "request":"launch", "executionScope":"device"},
                  {"name":"variable", "type":"debugpy", "request":"launch", "program":"${'$'}{env:HOME}/main.py"},
                  {"name":"task", "type":"debugpy", "request":"launch", "preLaunchTask":27}
                ]}
            """.trimIndent())
            assertTrue(manager.configurations("src/main.py").none { it.source == ".vscode/launch.json" })
            assertEquals(4, manager.configurationDiagnostics.value.size)
            assertTrue(manager.configurationDiagnostics.value.any { "type=cppdbg" in it })
            assertTrue(manager.configurationDiagnostics.value.any { "cannot redirect" in it })
        }
    }

    @Test fun nativeAdapterRequiresExplicitConfigurationAndDoesNotClaimPtraceCertification() = runBlocking {
        fixture { root, manager ->
            root.resolve("main.cpp").writeText("int main() {}")
            assertTrue(manager.configurations("main.cpp").isEmpty())
            root.resolve(".vscode").mkdir()
            root.resolve(".vscode/launch.json").writeText("""
                {"configurations":[{"name":"native remote", "type":"lldb-dap", "request":"attach", "program":"${'$'}{workspaceFolder}/build/main"}]}
            """.trimIndent())
            val config = manager.configurations("main.cpp").single()
            assertTrue(config.runtimeWarning!!.contains("PRoot already uses ptrace"))
            assertEquals(ProcessExecutionScope.LOCAL_LINUX_ARM64, config.executionScope)
            assertEquals("/workspace/build/main", config.arguments["program"]!!.jsonPrimitive.content)
        }
    }

    @Test fun earlyStartupFailureIsErrorAndDoesNotLeaveStaleActiveSession() = runBlocking {
        val root = Files.createTempDirectory("debug-start-error-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = DebugManager(scope, FileRepository(root), TerminalManager(scope, root))
        try {
            val failure = runCatching { manager.start(DebugConfiguration("missing", "launch", listOf("adapter"), buildJsonObject {}, "test")) }
            assertTrue(failure.isFailure)
            assertEquals(DebugState.ERROR, manager.state.value)
            assertTrue(manager.status.value.contains("unavailable"))
            manager.stop()
            assertEquals(DebugState.IDLE, manager.state.value)
        } finally { manager.close(); scope.cancel(); PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun standardFileVariablesResolveAtRootAndNestedPaths() = runBlocking {
        fixture { root, manager ->
            root.resolve("main.py").writeText("")
            root.resolve("README").writeText("")
            root.resolve(".vscode/launch.json").apply {
                parentFile.mkdirs()
                writeText("""{"configurations":[{"name":"variables","type":"debugpy","request":"launch","cwd":"${'$'}{relativeFileDirname}","args":["${'$'}{fileExtname}"]}]}""")
            }
            suspend fun check(path: String, expectedDirectory: String, expectedExtension: String) {
                val config = manager.configurations(path).single { it.source == ".vscode/launch.json" }
                assertEquals(expectedDirectory, config.arguments["cwd"]!!.jsonPrimitive.content)
                assertEquals(expectedExtension, config.arguments["args"]!!.jsonArray.single().jsonPrimitive.content)
                assertTrue(manager.configurationDiagnostics.value.isEmpty())
            }
            check("main.py", ".", ".py")
            check("src/main.py", "src", ".py")
            check("README", ".", "")
            for ((name, extension) in listOf(".gitignore" to "", "archive." to ".", ".env.local" to ".local")) {
                root.resolve(name).writeText("")
                check(name, ".", extension)
            }
        }
    }

    @Test fun rootRelativeDirectoryDoesNotBecomeEmptyDebugCwd() = runBlocking {
        fixture { root, manager ->
            root.resolve("main.py").writeText("")
            root.resolve(".vscode/launch.json").apply {
                parentFile.mkdirs()
                writeText("""{"configurations":[{"name":"root-directory","type":"debugpy","request":"launch","cwd":"${'$'}{relativeFileDirname}"}]}""")
            }
            val config = manager.configurations("main.py").single { it.source == ".vscode/launch.json" }
            assertEquals(".", config.arguments["cwd"]!!.jsonPrimitive.content)
        }
    }

    @Test fun basenameWithoutExtensionPreservesDotfiles() = runBlocking {
        fixture { root, manager ->
            root.resolve(".vscode/launch.json").apply {
                parentFile.mkdirs()
                writeText("""{"configurations":[{"name":"basename","type":"debugpy","request":"launch","args":["${'$'}{fileBasenameNoExtension}"]}]}""")
            }
            for ((name, expected) in listOf(".env" to ".env", ".gitignore" to ".gitignore", ".env.local" to ".env", "archive." to "archive", "main.test.py" to "main.test", "README" to "README")) {
                root.resolve(name).writeText("")
                val config = manager.configurations(name).single { it.source == ".vscode/launch.json" }
                assertEquals(name, expected, config.arguments["args"]!!.jsonArray.single().jsonPrimitive.content)
                assertTrue(manager.configurationDiagnostics.value.isEmpty())
            }
        }
    }

    @Test fun preLaunchTaskKeepsFullTaskIdentity() = runBlocking {
        fixture { root, manager ->
            val label = " " + "x".repeat(240) + "-build "
            root.resolve(".vscode/launch.json").apply {
                parentFile.mkdirs()
                writeText("""{"configurations":[{"name":"long-task","type":"debugpy","request":"launch","preLaunchTask":"$label"}]}""")
            }
            val config = manager.configurations("src/main.py").single { it.source == ".vscode/launch.json" }
            assertEquals(label, config.preLaunchTask)
            assertTrue(manager.configurationDiagnostics.value.isEmpty())
        }
    }

    @Test fun longConfigurationNamesRemainDistinctAndKeepTheirArguments() = runBlocking {
        fixture { root, manager ->
            val first = "x".repeat(240) + "-first"
            val second = "x".repeat(240) + "-second"
            root.resolve(".vscode/launch.json").apply {
                parentFile.mkdirs()
                writeText("""{"configurations":[
                    {"name":"$first","type":"debugpy","request":"launch","args":["first"]},
                    {"name":"$second","type":"debugpy","request":"launch","args":["second"]}
                ]}""")
            }
            val configs = manager.configurations("src/main.py").filter { it.source == ".vscode/launch.json" }
            assertEquals(listOf(first, second), configs.map { it.name })
            assertEquals(listOf("first", "second"), configs.map { it.arguments["args"]!!.jsonArray.single().jsonPrimitive.content })
            assertTrue(manager.configurationDiagnostics.value.isEmpty())
        }
    }

    @Test fun vscodePathSeparatorAliasesResolveInLaunchArguments() = runBlocking {
        fixture { root, manager ->
            root.resolve(".vscode/launch.json").apply {
                parentFile.mkdirs()
                writeText("""{"configurations":[{"name":"separator","type":"debugpy","request":"launch",
                  "program":"${'$'}{file}","args":["a${'$'}{pathSeparator}b","a${'$'}{/}b"]}]}""")
            }
            val config = manager.configurations("src/main.py").first { it.name == "separator" }
            assertEquals(listOf("a/b", "a/b"), config.arguments["args"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertTrue(manager.configurationDiagnostics.value.isEmpty())
        }
    }
}
