package com.baystudio.droide.core

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Actual controller, real local child, no Android/ADB or unknown process discovery. */
class AcpTerminalStdinEofTest {
    @Test(timeout = 8_000) fun noninteractiveAcpTerminalMustReachEofWithoutUnavailableInputOperation() = runBlocking {
        val root = Files.createTempDirectory("f148-acp-eof-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var child: Process? = null
        val host = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command: String) = "/bin/$command"
            override suspend fun pathMapper() = WorkspacePathMapper(root, root.canonicalPath)
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
                val p = ProcessBuilder(argv).directory(root).start().also { child = it }
                return object : HostedStdioProcess {
                    override val stdin get() = p.outputStream
                    override val stdout get() = p.inputStream
                    override val stderr get() = p.errorStream
                    override val isAlive get() = p.isAlive
                    override val exitCode get() = if (p.isAlive) null else p.exitValue()
                    override suspend fun awaitExit() = withContext(Dispatchers.IO) { runInterruptible { p.waitFor() } }
                    override fun close() { if (p.isAlive) p.destroyForcibly(); p.waitFor(1, TimeUnit.SECONDS) }
                }
            }
        }
        val controller = AcpTerminalController(host, WorkspacePathMapper(root, root.canonicalPath), ApprovalManager(),
            PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), AcpPermissionLedger(),
            WorkspaceAgentAccessPolicy().apply { setMode(WorkspaceAgentMode.EDIT) }, scope).apply { bindApprovalSession("fixture") }
        try {
            val created = controller.create(buildJsonObject {
                put("command", "wc"); put("sessionId", "fixture")
                put("args", buildJsonArray { add("-c") })
            })
            val result = withTimeoutOrNull(1_000) { controller.waitForExit(created) }
            val blockedAlive = child!!.isAlive
            if (result == null) {
                // Controlled diagnostic, after the failed observation: EOF alone releases the same child.
                child!!.outputStream.close()
                val afterEof = withTimeout(2_000) { controller.waitForExit(created) }
                assertEquals(0, afterEof.getValue("exitCode").jsonPrimitive.int)
                assertEquals("0", controller.output(created).getValue("output").jsonPrimitive.content.trim())
            }
            println("origin=" + AcpTerminalController::class.java.protectionDomain.codeSource.location)
            println("before_manual_eof_finished=${result != null}; child_alive=$blockedAlive; after_manual_eof_output=" + controller.output(created))
            assertNotNull("ACP terminal has no stdin method, but left stdin open indefinitely; same real child finishes when EOF is supplied", result)
            assertEquals(0, result!!.getValue("exitCode").jsonPrimitive.int)
        } finally {
            controller.close()
            child?.let { if (it.isAlive) it.destroyForcibly(); it.waitFor(1, TimeUnit.SECONDS) }
            scope.cancel(); scope.coroutineContext.job.join()
            // Retain this isolated fixture directory as evidence; no user files touched.
        }
    }

    @Test(timeout = 8_000) fun eofReadingCommandRetainsBothOutputStreamsAndItsActualNonzeroExit() = runBlocking {
        val root = Files.createTempDirectory("f148-acp-eof-output-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var child: Process? = null
        val host = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command: String) = "/bin/$command"
            override suspend fun pathMapper() = WorkspacePathMapper(root, root.canonicalPath)
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
                val p = ProcessBuilder(argv).directory(root).start().also { child = it }
                return object : HostedStdioProcess {
                    override val stdin get() = p.outputStream
                    override val stdout get() = p.inputStream
                    override val stderr get() = p.errorStream
                    override val isAlive get() = p.isAlive
                    override val exitCode get() = if (p.isAlive) null else p.exitValue()
                    override suspend fun awaitExit() = withContext(Dispatchers.IO) { runInterruptible { p.waitFor() } }
                    override fun close() { if (p.isAlive) p.destroyForcibly(); p.waitFor(1, TimeUnit.SECONDS) }
                }
            }
        }
        val controller = AcpTerminalController(host, WorkspacePathMapper(root, root.canonicalPath), ApprovalManager(),
            PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), AcpPermissionLedger(),
            WorkspaceAgentAccessPolicy().apply { setMode(WorkspaceAgentMode.EDIT) }, scope).apply { bindApprovalSession("fixture") }
        try {
            val created = controller.create(buildJsonObject {
                put("command", "sh"); put("sessionId", "fixture")
                // All commands are shell builtins, so the fixture owns one exact child.
                put("args", buildJsonArray { add("-c"); add("read unused; printf 'stdout-after-EOF\\n'; printf 'stderr-after-EOF\\n' >&2; exit 7") })
            })
            assertEquals(7, withTimeout(2_000) { controller.waitForExit(created) }.getValue("exitCode").jsonPrimitive.int)
            val snapshot = controller.output(created)
            val output = snapshot.getValue("output").jsonPrimitive.content
            assertTrue(output.contains("stdout-after-EOF\n")); assertTrue(output.contains("stderr-after-EOF\n"))
            assertFalse(snapshot.getValue("truncated").jsonPrimitive.boolean)
            assertEquals(7, snapshot.getValue("exitStatus").jsonObject.getValue("exitCode").jsonPrimitive.int)
            assertFalse(child!!.isAlive)
        } finally {
            controller.close()
            child?.let { if (it.isAlive) it.destroyForcibly(); it.waitFor(1, TimeUnit.SECONDS) }
            scope.cancel(); scope.coroutineContext.job.join()
        }
    }

}
