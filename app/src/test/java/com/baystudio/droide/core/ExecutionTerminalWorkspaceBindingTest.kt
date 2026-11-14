package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/** Real host shell commands through a selected-tab fixture; PTY dispatch is a capability fake. */
class ExecutionTerminalWorkspaceBindingTest {
    private open class Session(val humanCwd: File, val marker: String) :
        ITerminalSession, InteractivePtyCommandSession {
        override val output = MutableStateFlow("")
        override val supportsCommandInput = true
        override val supportsInteractivePty = true
        val calls = mutableListOf<String>()
        var starts = 0
        var destroys = 0
        var interrupts = 0
        var lastInput: ProcessInputBuffer? = null
        override fun start() { starts++ }
        override fun send(cmd: String) = error("Agent must never type cd into the human shell")
        override fun clear() = Unit
        override fun destroy() { destroys++ }
        override fun interrupt() { interrupts++ }
        private suspend fun shell(command: String, timeout: Long, input: ProcessInputBuffer? = null,
                                  output: (String) -> Unit = {}): ExecResult {
            calls += command
            lastInput = input
            return LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", command), humanCwd,
                environment = mapOf("DROIDE_TEST_BACKEND" to marker), timeoutMs = timeout,
                streamInput = input, onOutput = output)
        }
        override suspend fun execOnce(cmd: String, timeoutMs: Long) = shell(cmd, timeoutMs)
        override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit) =
            shell(cmd, timeoutMs, output = onOutput)
        override suspend fun execStreamingWithInput(cmd: String, timeoutMs: Long, input: ProcessInputBuffer,
                                                  onOutput: (String) -> Unit) =
            shell(cmd, timeoutMs, input, onOutput)
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult {
            calls += "argv"
            return LocalProcessSupervisor.capture(argv, humanCwd,
                environment = mapOf("DROIDE_TEST_BACKEND" to marker), timeoutMs = timeoutMs)
        }
        override suspend fun execInteractivePty(command: String, timeoutMs: Long, input: ProcessInputBuffer?,
                                                onOutput: (String) -> Unit) =
            shell(command, timeoutMs, input, onOutput)
    }

    private fun fixture(block: suspend (File, CoroutineScope) -> Unit) = runBlocking {
        // A real app launch opens this epoch. Plain host tests supply and restore it.
        val admissionField = ApplicationWorkAdmission::class.java.getDeclaredField("state").apply { isAccessible = true }
        val oldAdmission = admissionField.get(null)
        val admission = ApplicationWorkAdmissionState(
            read = { ApplicationWorkAdmissionState.Snapshot("a".repeat(32), true) },
            write = {},
        ).apply { initialize() }
        val root = Files.createTempDirectory("agent-workspace-router-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        admissionField.set(null, admission)
        try { block(root, scope) }
        finally {
            scope.cancel()
            admissionField.set(null, oldAdmission)
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    @Test fun nativeAgentRunCommandWritesCanonicalRootAfterUserCdWithoutChangingHumanCwd() = fixture { root, scope ->
        val human = File(root, "module").apply { mkdirs() }
        val session = Session(human, "ubuntu-existing")
        val tabs = TerminalManager(scope, root) { _, _, _ -> session }
        try {
            tabs.createLocal()
            val router = ExecutionTerminalRouter(tabs, workspaceRoot = root)
            val result = AgentTools.execute("run_command", buildJsonObject {
                put("command", "printf '%s' \"\$DROIDE_TEST_BACKEND\" > artifact.bin")
            }, agentWorkflowContext(root, terminal = router))
            assertTrue(result, result.contains("exit=0"))
            assertEquals("ubuntu-existing", File(root, "artifact.bin").readText())
            assertFalse(File(human, "artifact.bin").exists())
            assertEquals(human, session.humanCwd)
            assertEquals(1, session.starts)
            assertEquals(1, tabs.tabs.value.size)
            assertEquals(0, session.destroys)
        } finally { tabs.dispose(false) }
    }

    @Test fun boundStreamInputAndPtyKeepProjectRootAndOriginalTabAfterSwitch() = fixture { root, scope ->
        val human = File(root, "nested").apply { mkdirs() }
        val one = Session(human, "original")
        val two = Session(human, "other")
        var creations = 0
        val tabs = TerminalManager(scope, root) { _, _, _ -> if (creations++ == 0) one else two }
        try {
            val first = tabs.createLocal(); val second = tabs.createLocal()
            tabs.switch(first.id)
            val bound = ExecutionTerminalRouter(tabs, workspaceRoot = root).bindCommandSession()
            tabs.switch(second.id)
            val streamed = StringBuilder()
            assertEquals(0, bound.execStreaming("pwd; printf '%s' \"\$DROIDE_TEST_BACKEND\" > stream.bin", 5_000) {
                synchronized(streamed) { streamed.append(it) }
            }.exitCode)
            assertEquals(root.canonicalPath, streamed.toString().trim())
            val input = ProcessInputBuffer().apply { offer("input bytes\n"); close() }
            assertEquals(0, bound.execStreamingWithInput("cat > stdin.bin", 5_000, input) {}.exitCode)
            assertSame(input, one.lastInput)
            val pty = bound as InteractivePtyCommandSession
            assertEquals(0, pty.execInteractivePty("printf '%s' \"\$DROIDE_TEST_BACKEND\" > pty.bin", 5_000, null) {}.exitCode)
            assertEquals("original", File(root, "stream.bin").readText())
            assertEquals("input bytes\n", File(root, "stdin.bin").readText())
            assertEquals("original", File(root, "pty.bin").readText())
            assertTrue(two.calls.isEmpty())
            assertEquals(human, one.humanCwd)
            assertEquals(second.id, tabs.snapshot.value.activeId)
            assertEquals(2, tabs.tabs.value.size)
        } finally { tabs.dispose(false) }
    }

    @Test fun argvKeepsLiteralArgumentsAndExplicitIdeCwdStillWorks() = fixture { root, scope ->
        val human = File(root, "human").apply { mkdirs() }
        val taskDir = File(root, "task").apply { mkdirs() }
        val one = Session(human, "selected")
        val tabs = TerminalManager(scope, root) { _, _, _ -> one }
        try {
            tabs.createLocal()
            val router = ExecutionTerminalRouter(tabs, workspaceRoot = root)
            val literal = "spaces ' ; \$(touch injected) \$HOME"
            val command = listOf("/bin/sh", "-c", "printf '%s' \"\$1\" > argv.txt; pwd", "argument-check", literal)
            assertEquals(root.canonicalPath, router.execArgv(command, 5_000).output.trim())
            assertEquals(literal, File(root, "argv.txt").readText())
            assertEquals(root.canonicalPath, router.bindCommandSession().execArgv(command, 5_000).output.trim())
            assertEquals(taskDir.canonicalPath, router.execWorkspaceArgv(root, taskDir, listOf("pwd"), 5_000).output.trim())
            assertFalse(File(root, "injected").exists())
            assertFalse(File(human, "injected").exists())
            assertFalse(File(human, "argv.txt").exists())
        } finally { tabs.dispose(false) }
    }

    @Test fun failedRootAdmissionAndClosedTabNeverLaunchElsewhere() = fixture { root, scope ->
        val project = File(root, "project").apply { mkdirs() }
        val human = File(root, "human").apply { mkdirs() }
        val one = Session(human, "selected")
        val tabs = TerminalManager(scope, project) { _, _, _ -> one }
        try {
            val tab = tabs.createLocal()
            val router = ExecutionTerminalRouter(tabs, workspaceRoot = project)
            val pinned = router.bindCommandSession()
            assertTrue(project.delete())
            try { pinned.execOnce("touch misplaced", 5_000); fail("Absent project accepted") }
            catch (_: IllegalArgumentException) { }
            assertTrue(one.calls.isEmpty())
            tabs.close(tab.id)
            assertEquals(126, pinned.execOnce("touch misplaced", 5_000).exitCode)
            assertFalse(File(human, "misplaced").exists())
            assertTrue(tabs.tabs.value.isEmpty())
        } finally { tabs.dispose(false) }
    }

    @Test fun cancellingPinnedPtyPreservesHumanSessionAndDoesNotRetarget() = fixture { root, scope ->
        val human = File(root, "human").apply { mkdirs() }
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val one = object : Session(human, "original") {
            override suspend fun execInteractivePty(command: String, timeoutMs: Long, input: ProcessInputBuffer?,
                                                    onOutput: (String) -> Unit): ExecResult {
                calls += command; entered.complete(Unit)
                try { awaitCancellation() }
                finally { stopped.complete(Unit) }
            }
        }
        val tabs = TerminalManager(scope, root) { _, _, _ -> one }
        try {
            tabs.createLocal()
            val bound = ExecutionTerminalRouter(tabs, workspaceRoot = root).bindCommandSession()
            val call = scope.launch { (bound as InteractivePtyCommandSession).execInteractivePty("running", 0, null) {} }
            withTimeout(5_000) { entered.await() }
            call.cancelAndJoin()
            assertTrue(stopped.isCompleted)
            assertTrue(one.calls.single().startsWith("cd -- "))
            bound.interrupt()
            assertEquals(0, one.interrupts)
            assertEquals(0, one.destroys)
            assertEquals(human, one.humanCwd)
            assertEquals(1, tabs.tabs.value.size)
        } finally { tabs.dispose(false) }
    }
}
