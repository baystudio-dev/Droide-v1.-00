package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TestRule

class AgentEnvironmentProbeTest {
    @get:Rule val applicationWork: TestRule = ApplicationWorkAdmissionFixture()

    private open class Session(
        private val root: File,
        private val path: String = root.path,
        override val environmentProfile: TerminalManager.Profile? = TerminalManager.Profile.LOCAL_LINUX_ARM64,
    ) : ITerminalSession, TerminalEnvironmentIdentitySession {
        override val output = MutableStateFlow("")
        var executions = 0
        var beforeExecute: (() -> Unit)? = null
        override fun start() = Unit
        override fun send(cmd: String) = Unit
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun destroy() = Unit
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult {
            executions++
            beforeExecute?.invoke()
            return LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", cmd), root,
                environment = mapOf("PATH" to path), timeoutMs = timeoutMs)
        }
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult = error("Probe must use one shell batch")
    }
    private suspend fun probe(root: File, terminal: ITerminalSession, commands: List<String>, includePath: Boolean = true,
        permissions: PermissionEngine = PermissionEngine(listOf(PermRule("read", "*", PermEffect.ALLOW)))): String =
        AgentEnvironmentProbe.run(buildJsonObject {
            put("commands", buildJsonArray { commands.forEach { add(it) } })
            put("include_path", includePath)
        }, agentWorkflowContext(root, terminal = terminal, permissions = permissions))

    @Test fun oneShellBatchFindsBuiltinsAndExecutablesWithoutExecutingThem() = runBlocking {
        val root = Files.createTempDirectory("droide-env-batch-").toFile()
        try {
            val executable = root.resolve("git").apply {
                writeText("#!/bin/sh\nprintf executed > '${root.resolve("unexpected").path}'\n")
                check(setExecutable(true))
            }
            val session = Session(root)
            val result = probe(root, session, listOf("git", "curl", "node", "npm", "python3", "printf"))
            assertEquals(1, session.executions)
            assertTrue(result.contains("command=git available=true probe_exit=0 path=${executable.path}"))
            assertTrue(result.contains("command=printf available=true"))
            assertTrue(result.contains("command=node available=false"))
            assertTrue(result.contains("family=runtime.node commands=droide pkg install runtime.node"))
            assertTrue(result.contains("PATH=${root.path}"))
            assertFalse(root.resolve("unexpected").exists())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun permissionDeniedDoesNotStartAnyProbeOrInstall() = runBlocking {
        val root = Files.createTempDirectory("droide-env-denied-").toFile()
        try {
            val session = Session(root)
            val result = probe(root, session, listOf("npm"), permissions = PermissionEngine(listOf(PermRule("read", "*", PermEffect.DENY))))
            assertTrue(result.startsWith("DENIED"))
            assertEquals(0, session.executions)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun malformedAndOptionNamesAreRejectedBeforeShellExecution() = runBlocking {
        val root = Files.createTempDirectory("droide-env-invalid-").toFile()
        try {
            val session = Session(root)
            val result = probe(root, session, listOf("--help", "npm; touch unwanted", "git\ncommand=spoof"), includePath = false)
            assertEquals(0, session.executions)
            assertEquals(3, result.split("reason=invalid_probe_name").size - 1)
            assertTrue(result.contains("git\\ncommand=spoof available=false"))
            assertFalse(root.resolve("unwanted").exists())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun timeoutIsUnknownRatherThanAnInstructionToReinstallEverything() = runBlocking {
        val root = Files.createTempDirectory("droide-env-timeout-").toFile()
        try {
            val session = object : Session(root) {
                override suspend fun execOnce(cmd: String, timeoutMs: Long) = ExecResult(124, "partial", true)
            }
            val result = probe(root, session, listOf("npm", "python3"))
            assertTrue(result.contains("command=npm available=unknown probe_exit=124 reason=probe_timeout"))
            assertTrue(result.contains("PATH_available=unknown"))
            assertFalse(result.contains("install_hint="))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun missingCompletionFrameIsNotReportedAsMissingRuntime() = runBlocking {
        val root = Files.createTempDirectory("droide-env-frame-").toFile()
        try {
            val session = object : Session(root) {
                override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult {
                    val result = super.execOnce(cmd, timeoutMs)
                    return result.copy(output = result.output.substringBeforeLast("E\u0000"))
                }
            }
            val result = probe(root, session, listOf("npm"))
            assertTrue(result.contains("available=unknown probe_exit=0 reason=probe_incomplete"))
            assertFalse(result.contains("install_hint="))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun selectedSessionAndProfileStayPinnedWhenUserSwitchesMidProbe() = runBlocking {
        val root = Files.createTempDirectory("droide-env-pin-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessions = mutableListOf<Session>()
        val manager = TerminalManager(scope, root) { _, _, _ -> Session(root).also { sessions += it } }
        try {
            val alpine = manager.createLocal(title = "Alpine", profile = TerminalManager.Profile.LOCAL_QEMU)
            val android = manager.createLocal()
            manager.switch(alpine.id)
            val router = ExecutionTerminalRouter(manager, Session(root))
            sessions.first().beforeExecute = { manager.switch(android.id) }
            val result = probe(root, router, listOf("node", "npm"))
            assertEquals(1, sessions.first().executions)
            assertEquals(0, sessions.last().executions)
            assertEquals(android.id, manager.activeTab()?.id)
            assertTrue(result.contains("environment=alpine pinned=true"))
            assertTrue(result.contains("commands=apk add nodejs npm"))
            assertFalse(result.contains("droide pkg install"))
            assertEquals(2, manager.tabs.value.size)
        } finally { manager.dispose(false); scope.cancel(); PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun unusualPathCharactersRemainOneRecordAndDoNotSpoofAvailability() = runBlocking {
        val root = Files.createTempDirectory("droide-env-path-").toFile()
        try {
            val bin = root.resolve("tools with spaces\nand newline").apply { check(mkdir()) }
            val executable = bin.resolve("git").apply { writeText("#!/bin/sh\nexit 0\n"); check(setExecutable(true)) }
            val result = probe(root, Session(root, bin.path), listOf("git", "npm"))
            assertTrue(result.contains("path=" + TerminalCapabilityProbe.display(executable.path)))
            assertTrue(result.contains("command=npm available=false"))
            assertFalse(result.contains("spaces\nand newline"))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun cancellationPropagatesWithoutConvertingToAbsentPackages() = runBlocking {
        val root = Files.createTempDirectory("droide-env-cancel-").toFile()
        try {
            val session = object : Session(root) {
                override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = throw CancellationException("closed")
            }
            try { probe(root, session, listOf("npm")); fail("Cancellation must propagate") }
            catch (expected: CancellationException) { assertEquals("closed", expected.message) }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun maximumBatchAndLongPathsFitTheProductionTwentyThousandCharacterTail() = runBlocking {
        val root = Files.createTempDirectory("droide-env-bounds-").toFile()
        try {
            val bin = root.resolve("a".repeat(180)).resolve("b".repeat(180)).resolve("c".repeat(180)).apply { check(mkdirs()) }
            val names = (0 until 32).map { "fixture$it" }
            names.forEach { name -> bin.resolve(name).apply { writeText("#!/bin/sh\nexit 71\n"); check(setExecutable(true)) } }
            val path = bin.path + ":" + (0 until 120).joinToString(":") { "/missing/" + "x".repeat(35) + it }
            assertTrue(path.length > 4_000)
            var rawOutputChars = 0
            val session = object : Session(root, path) {
                override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult {
                    val result = super.execOnce(cmd, timeoutMs)
                    rawOutputChars = result.output.length
                    return result.copy(output = result.output.takeLast(20_000))
                }
            }
            val result = TerminalCapabilityProbe.inspect(session, names, includePath = true)
            assertEquals(1, session.executions)
            assertTrue("All records must survive the backend output bound", result.complete)
            assertEquals(32, result.commands.size)
            assertTrue(result.commands.values.all { it != null })
            assertEquals(4_000, result.path?.length)
            assertTrue(rawOutputChars < 20_000)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
