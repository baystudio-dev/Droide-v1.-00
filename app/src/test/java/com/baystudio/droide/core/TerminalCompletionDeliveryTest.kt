package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TerminalCompletionDeliveryTest {
    private suspend fun withAcpFixture(
        input: InputStream = ByteArrayInputStream(byteArrayOf()),
        code: Int = 0,
        exit: CompletableDeferred<Int> = CompletableDeferred(code),
        close: () -> Unit = {},
        block: suspend (AcpTerminalController, JsonObject) -> Unit,
    ) {
        val root = Files.createTempDirectory("droide-acp-completion-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val process = object : HostedStdioProcess {
            override val stdin = ByteArrayOutputStream()
            override val stdout = input
            override val stderr = ByteArrayInputStream(byteArrayOf())
            override val isAlive get() = !exit.isCompleted
            override val exitCode get() = if (exit.isCompleted) code else null
            override suspend fun awaitExit() = exit.await()
            override fun close() { input.close(); exit.complete(code); close.invoke() }
        }
        val host = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.DEVICE_ADB
            override suspend fun resolveExecutable(command: String) = "/fixture/$command"
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?) = process
            override suspend fun pathMapper() = WorkspacePathMapper(root, root.canonicalPath)
        }
        val access = WorkspaceAgentAccessPolicy().apply { setMode(WorkspaceAgentMode.EDIT) }
        val controller = AcpTerminalController(host, WorkspacePathMapper(root, root.canonicalPath),
            ApprovalManager(), PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))),
            AcpPermissionLedger(), access, scope).apply { bindApprovalSession("fixture-approval") }
        try {
            val created = controller.create(buildJsonObject { put("command", "fixture"); put("sessionId", "fixture-session") })
            block(controller, buildJsonObject { put("terminalId", created.getValue("terminalId")) })
        } finally {
            runCatching { controller.close() }
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test(timeout = 5_000) fun acpUnknownNegativeExitCannotBeReportedAsSuccess() = runBlocking {
        withAcpFixture(code = -1) { controller, params ->
            assertEquals(1, withTimeout(2_000) { controller.waitForExit(params) }.getValue("exitCode").jsonPrimitive.int)
            assertEquals(1, controller.output(params).getValue("exitStatus").jsonObject.getValue("exitCode").jsonPrimitive.int)
        }
    }

    @Test(timeout = 5_000) fun acpReaderFailureRemainsVisibleInsteadOfCrashingTheAgentScope() = runBlocking {
        val failing = object : InputStream() { override fun read(): Int = throw IOException("broken output pipe") }
        withAcpFixture(input = failing, code = 7) { controller, params ->
            assertEquals(7, withTimeout(2_000) { controller.waitForExit(params) }.getValue("exitCode").jsonPrimitive.int)
            val output = controller.output(params)
            assertTrue(output.getValue("truncated").jsonPrimitive.boolean)
            assertTrue(output.getValue("output").jsonPrimitive.content.contains("broken output pipe"))
        }
    }

    @Test(timeout = 7_000) fun acpInheritedPipeIsBoundedAfterExitAndCleanupFailureCannotLoseCompletion() = runBlocking {
        val gate = CountDownLatch(1)
        val reading = CountDownLatch(1)
        val blocked = object : InputStream() {
            override fun read(): Int { reading.countDown(); check(gate.await(5, TimeUnit.SECONDS)); return -1 }
            override fun close() { gate.countDown() }
        }
        withAcpFixture(input = blocked, code = 7, close = { throw IOException("fixture close failed") }) { controller, params ->
            assertTrue(reading.await(1, TimeUnit.SECONDS))
            assertEquals(7, withTimeout(3_500) { controller.waitForExit(params) }.getValue("exitCode").jsonPrimitive.int)
            val output = controller.output(params)
            assertTrue(output.getValue("truncated").jsonPrimitive.boolean)
            assertTrue(output.getValue("output").jsonPrimitive.content.contains("pipe remained open"))
            assertTrue(output.getValue("output").jsonPrimitive.content.contains("fixture close failed"))
        }
    }

    @Test(timeout = 5_000) fun releasingAcpTerminalCancelsCompletionWaitWithoutInventingAnExitStatus() = runBlocking {
        withAcpFixture(exit = CompletableDeferred()) { controller, params ->
            val completion = async { controller.waitForExit(params) }
            yield()
            assertFalse(controller.output(params).containsKey("exitStatus"))
            controller.release(params)
            try { withTimeout(1_000) { completion.await() }; fail("Released terminal must cancel its wait") }
            catch (_: CancellationException) { assertTrue(completion.isCancelled) }
        }
    }

    @Test(timeout = 8_000) fun exitedCommandReleasesItsLeaseBeforeWaitingForInheritedOutputPipes() = runBlocking {
        val root = Files.createTempDirectory("droide-output-lease-").toFile()
        val pid = root.resolve("child.pid")
        var cleanups = 0
        try {
            val result = withTimeout(2_500) {
                LocalProcessSupervisor.capture(
                    listOf("/bin/sh", "-c", "sleep 20 & echo \$! > '${pid.absolutePath}'; printf 'FINAL: failed'; sleep 0.2; exit 7"),
                    root, timeoutMs = 0, cleanup = {
                        cleanups++
                        if (pid.isFile()) ProcessBuilder("/bin/kill", "-KILL", pid.readText().trim()).start().waitFor()
                    },
                )
            }
            assertEquals(7, result.exitCode)
            assertEquals("FINAL: failed", result.output)
            assertFalse(result.timedOut)
            assertEquals(1, cleanups)
        } finally {
            if (pid.isFile()) ProcessBuilder("/bin/kill", "-KILL", pid.readText().trim()).start().waitFor()
            root.deleteRecursively()
        }
    }

    @Test(timeout = 8_000) fun acpCompletionWaitRetainsFinalOutputThatHasNotReachedItsReaderYet() = runBlocking {
        val root = Files.createTempDirectory("droide-acp-output-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val gate = CountDownLatch(1)
        val reading = CountDownLatch(1)
        val input = object : InputStream() {
            private val content = ByteArrayInputStream("FINAL: 日本語 😀 error\n".toByteArray())
            override fun read(): Int = content.read()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reading.countDown()
                check(gate.await(5, TimeUnit.SECONDS)) { "Fixture output was not released" }
                return content.read(buffer, offset, length)
            }
            override fun close() { gate.countDown(); content.close() }
        }
        val process = object : HostedStdioProcess {
            override val stdin = ByteArrayOutputStream()
            override val stdout = input
            override val stderr = ByteArrayInputStream(byteArrayOf())
            override val isAlive = false
            override val exitCode = 7
            override suspend fun awaitExit() = 7
            override fun close() { input.close() }
        }
        val host = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command: String) = "/fixture/$command"
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?) = process
            override suspend fun pathMapper() = WorkspacePathMapper(root, root.canonicalPath)
        }
        val access = WorkspaceAgentAccessPolicy().apply { setMode(WorkspaceAgentMode.EDIT) }
        val controller = AcpTerminalController(host, WorkspacePathMapper(root, root.canonicalPath),
            ApprovalManager(), PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))),
            AcpPermissionLedger(), access, scope).apply { bindApprovalSession("fixture-approval") }
        try {
            val created = controller.create(buildJsonObject { put("command", "fixture"); put("sessionId", "fixture-session") })
            val params = buildJsonObject { put("terminalId", created.getValue("terminalId")) }
            assertTrue(reading.await(2, TimeUnit.SECONDS))
            val completion = async { controller.waitForExit(params) }
            delay(100)
            assertFalse("ACP must drain output before announcing completion", completion.isCompleted)
            assertFalse("Output with an exitStatus must contain final bytes", controller.output(params).containsKey("exitStatus"))
            gate.countDown()
            assertEquals(7, withTimeout(2_000) { completion.await() }.getValue("exitCode").jsonPrimitive.int)
            val output = controller.output(params)
            assertEquals("FINAL: 日本語 😀 error\n", output.getValue("output").jsonPrimitive.content)
            assertEquals(7, output.getValue("exitStatus").jsonObject.getValue("exitCode").jsonPrimitive.int)
        } finally {
            gate.countDown()
            controller.close()
            scope.cancel()
            root.deleteRecursively()
        }
    }
}
