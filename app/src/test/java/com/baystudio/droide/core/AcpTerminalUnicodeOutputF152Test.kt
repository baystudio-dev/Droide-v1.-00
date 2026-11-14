package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Actual ACP controller coverage: byte cap must never corrupt valid supplementary output. */
class AcpTerminalUnicodeOutputF152Test {
    private suspend fun capture(payload: String, limit: Int, realProcess: Boolean = false): String {
        val root = Files.createTempDirectory("f152-acp-unicode-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val file = root.resolve("output.txt").apply { writeBytes(bytes) }
        var child: Process? = null
        val host = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command: String) = "/bin/$command"
            override suspend fun pathMapper() = WorkspacePathMapper(root, root.canonicalPath)
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
                if (realProcess) {
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
                return object : HostedStdioProcess {
                    override val stdin = ByteArrayOutputStream()
                    override val stdout = ByteArrayInputStream(bytes)
                    override val stderr = ByteArrayInputStream(byteArrayOf())
                    override val isAlive = false
                    override val exitCode = 0
                    override suspend fun awaitExit() = 0
                    override fun close() {}
                }
            }
        }
        val controller = AcpTerminalController(host, WorkspacePathMapper(root, root.canonicalPath), ApprovalManager(),
            PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), AcpPermissionLedger(),
            WorkspaceAgentAccessPolicy().apply { setMode(WorkspaceAgentMode.EDIT) }, scope).apply { bindApprovalSession("fixture") }
        try {
            val created = controller.create(buildJsonObject {
                put("command", "cat"); put("sessionId", "fixture"); if (limit > 0) put("outputByteLimit", limit)
                put("args", buildJsonArray { add(file.canonicalPath) })
            })
            assertEquals(0, withTimeout(3_000) { controller.waitForExit(created) }.getValue("exitCode").jsonPrimitive.int)
            val result = controller.output(created)
            assertTrue(result.getValue("truncated").jsonPrimitive.boolean)
            return result.getValue("output").jsonPrimitive.content
        } finally {
            controller.close()
            child?.let { if (it.isAlive) it.destroyForcibly(); it.waitFor(1, TimeUnit.SECONDS) }
            scope.cancel(); withTimeout(2_000) { scope.coroutineContext.job.join() }
        }
    }
    @Test(timeout = 20_000) fun preservesSupplementaryUnicodeAcrossDrainChunks() = runBlocking {
        println("origin=" + AcpTerminalController::class.java.protectionDomain.codeSource.location)
        val failures = mutableListOf<String>()
        for (prefix in listOf(4_095, 8_191, 12_287, 16_383, 20_479, 24_575, 28_671)) {
            val payload = "a".repeat(prefix) + "😀"
            val actual = capture(payload, 4096)
            val expected = "a".repeat(4092) + "😀"
            val line = "prefix=$prefix sourceBytes=${payload.toByteArray().size} actualBytes=${actual.toByteArray().size} tailCodeUnits=${actual.takeLast(8).map { it.code.toString(16) }} correct=${expected == actual}"
            println(line)
            if (expected != actual) failures += line
        }
        assertTrue("Actual controller irreversibly corrupts a valid UTF-8 tail: ${failures.joinToString("; ")}", failures.isEmpty())
    }
    @Test(timeout = 20_000) fun defaultCapRealCatOutputPreservesSupplementaryUnicode() = runBlocking {
        val payload = "a".repeat(266_239) + "😀"
        val actual = capture(payload, 0, true)
        println("defaultCapRealCat sourceBytes=${payload.toByteArray().size} actualBytes=${actual.toByteArray().size} tailCodeUnits=${actual.takeLast(8).map { it.code.toString(16) }}")
        assertTrue("Default cap must retain final complete emoji, not corruption", actual.endsWith("😀"))
        assertEquals("a".repeat(262_140) + "😀", actual)
    }
    @Test(timeout = 20_000) fun realCatOutputPreservesSupplementaryUnicode() = runBlocking {
        val payload = "a".repeat(12_287) + "😀"
        val actual = capture(payload, 4096, true)
        println("realCat sourceBytes=${payload.toByteArray().size} actualBytes=${actual.toByteArray().size} tailCodeUnits=${actual.takeLast(8).map { it.code.toString(16) }}")
        assertEquals("a".repeat(4092) + "😀", actual)
    }
}
