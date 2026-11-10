package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DebugProbeRecoveryTest {
    @Test fun jdwpMapperFailureIsVisibleAndImmediatelyRetryable() = runBlocking {
        val root = Files.createTempDirectory("debug-probe-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var fail = true
        val host = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command: String): String? = "/usr/bin/$command"
            override suspend fun pathMapper(): WorkspacePathMapper {
                if (fail) error("workspace admission blocked")
                return WorkspacePathMapper(root, "/workspace")
            }
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess = error("Discovery must not spawn")
        }
        val manager = DebugManager(scope, FileRepository(root), TerminalManager(scope, root), remoteProcessHost = host)
        try {
            val failure = runCatching { manager.androidAttachConfigurations("main.kt", 123) }.exceptionOrNull()
            assertTrue(failure is ToolchainProbeFailed)
            assertTrue(failure!!.message.orEmpty().contains("workspace admission blocked"))
            fail = false
            assertTrue(manager.androidAttachConfigurations("main.kt", 123).any { it.androidJdwp })
        } finally { manager.close(); scope.cancel(); PathSecurity.deleteTreeNoFollow(root) }
    }
}
