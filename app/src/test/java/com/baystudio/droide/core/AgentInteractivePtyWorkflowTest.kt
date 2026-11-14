package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TestRule

/** Exercises production job/router/permission workflows with a deterministic native capability fixture. */
class AgentInteractivePtyWorkflowTest {
    @get:Rule val applicationWork: TestRule = ApplicationWorkAdmissionFixture()

    private open class Session : ITerminalSession {
        override val output = MutableStateFlow("")
        val pipes = AtomicInteger()
        val humanInterrupts = AtomicInteger()
        val destroys = AtomicInteger()
        override fun start() = Unit
        override fun send(cmd: String) = error("Agent must not type into the human shell")
        override fun interrupt() { humanInterrupts.incrementAndGet() }
        override fun clear() = Unit
        override fun destroy() { destroys.incrementAndGet() }
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult {
            pipes.incrementAndGet(); return ExecResult(0, "pipe", false)
        }
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long) = ExecResult(0, "", false)
    }
    private class PtySession : Session(), InteractivePtyCommandSession {
        override val supportsInteractivePty = true
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Int>()
        val cancelled = CompletableDeferred<Unit>()
        val launched = mutableListOf<String>()
        val received = ByteArrayOutputStream()
        @Volatile var failInput = false
        private val ownership = InteractivePtyOwnership()
        @Volatile var humanPhase = TerminalCommandPhase.READY
        override suspend fun execInteractivePty(command: String, timeoutMs: Long, input: ProcessInputBuffer?, onOutput: (String) -> Unit): ExecResult = coroutineScope {
            val owner = ownership.acquire(humanPhase)
            try {
                launched += command
                started.complete(Unit)
                onOutput("[PTY screen snapshot]\nready\n")
                if (input != null) launch(Dispatchers.IO) {
                    InteractivePtyInputDelivery.drain(input, finish,
                        isAlive = { isActive && !finish.isCompleted },
                        writeBytes = { bytes, offset, length ->
                            if (failInput) {
                                received.write(bytes, offset, 1)
                                throw java.io.IOException("partial native write fixture")
                            }
                            received.write(bytes, offset, length)
                        }, onEof = {},
                    )
                }
                val exit = finish.await()
                ExecResult(exit, "", false)
            } finally {
                input?.abort()
                ownership.release(owner)
                if (!finish.isCompleted) cancelled.complete(Unit)
            }
        }
    }
    private suspend fun waitDone(manager: BackgroundCommandManager, id: String) = withTimeout(3_000) {
        while (manager.snapshot(id)?.state == BackgroundCommandManager.State.RUNNING) delay(10)
        requireNotNull(manager.snapshot(id))
    }

    @Test fun ttyKeepsInputByDefaultAcrossQuietPolls() = runBlocking<Unit> {
        val pty = PtySession(); val jobs = BackgroundCommandManager(pty)
        try {
            val job = jobs.start("interactive", requiresTty = true)
            withTimeout(3_000) { pty.started.await() }
            repeat(10) {
                val previous = requireNotNull(jobs.snapshot(job.id))
                val quiet = jobs.awaitOutput(job.id, previous.outputEndCursor, 5)
                assertEquals(BackgroundCommandManager.State.RUNNING, quiet.state)
                assertTrue("Interactive input must remain available", quiet.stdinAvailable)
                assertNull(quiet.timeoutMs)
            }
            jobs.sendInput(job.id, "after quiet polls\n")
            withTimeout(3_000) { while (!pty.received.toString("UTF-8").contains("after quiet polls")) delay(10) }
            pty.finish.complete(0)
            assertEquals(BackgroundCommandManager.State.EXITED, waitDone(jobs, job.id).state)
        } finally { jobs.closeAndJoin() }
    }

    @Test fun eofDoesNotCloseInputOfAStillRunningPty() = runBlocking<Unit> {
        val pty = PtySession(); val jobs = BackgroundCommandManager(pty)
        try {
            val job = jobs.start("interactive", keepStdinOpen = true, requiresTty = true)
            withTimeout(3_000) { pty.started.await() }
            jobs.sendInput(job.id, "first input\n", closeInput = true)
            assertTrue(requireNotNull(jobs.snapshot(job.id)).stdinAvailable)
            jobs.sendInput(job.id, "next command\n")
            withTimeout(3_000) { while (!pty.received.toString("UTF-8").contains("next command")) delay(10) }
            assertEquals("first input\n\u0004next command\n", pty.received.toString("UTF-8"))
            pty.finish.complete(0)
            assertEquals(BackgroundCommandManager.State.EXITED, waitDone(jobs, job.id).state)
            assertFalse(requireNotNull(jobs.snapshot(job.id)).stdinAvailable)
            assertThrows(IllegalStateException::class.java) { jobs.sendInput(job.id, "after exit") }
        } finally { jobs.closeAndJoin() }
    }

    @Test fun explicitInputOptOutIsPreservedForPty() = runBlocking<Unit> {
        val pty = PtySession(); val jobs = BackgroundCommandManager(pty)
        try {
            val job = jobs.start("interactive", keepStdinOpen = false, requiresTty = true)
            withTimeout(3_000) { pty.started.await() }
            assertFalse(requireNotNull(jobs.snapshot(job.id)).stdinAvailable)
            assertThrows(IllegalStateException::class.java) { jobs.sendInput(job.id, "not admitted") }
            pty.finish.complete(0)
            assertEquals(BackgroundCommandManager.State.EXITED, waitDone(jobs, job.id).state)
        } finally { jobs.closeAndJoin() }
    }

    @Test fun toolExecWithOnlyTtyFlagKeepsTheReturnedSessionWritable() = runBlocking<Unit> {
        val root = Files.createTempDirectory("droide-default-pty-input-").toFile()
        val pty = PtySession(); val jobs = BackgroundCommandManager(pty)
        try {
            val context = agentWorkflowContext(root, jobs)
            val result = AgentTools.execute("exec_command", buildJsonObject {
                put("cmd", "interactive"); put("requires_tty", true); put("yield_time_ms", 0)
            }, context)
            val job = jobs.list().single()
            assertTrue(result, result.contains("stdin_available=true"))
            assertTrue(result, result.contains("timeout_ms=none"))
            withTimeout(3_000) { pty.started.await() }
            jobs.sendInput(job.id, "later\n")
            withTimeout(3_000) { while (!pty.received.toString("UTF-8").contains("later")) delay(10) }
            pty.finish.complete(0)
            waitDone(jobs, job.id)
        } finally { jobs.closeAndJoin(); PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun ttyJobAndItsInputRemainOnOriginalTabAfterWorkstationSwitch() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val root = Files.createTempDirectory("droide-pty-pin-").toFile()
        val pty = PtySession(); val other = Session()
        var calls = 0
        val tabs = TerminalManager(scope, root) { _, _, _ -> if (calls++ == 0) pty else other }
        val jobs = BackgroundCommandManager(ExecutionTerminalRouter(tabs, Session()))
        try {
            val original = tabs.createLocal()
            val replacement = tabs.createLocal()
            tabs.switch(original.id)
            val job = jobs.start("interactive", keepStdinOpen = true, stopWithAgent = true, requiresTty = true)
            withTimeout(3_000) { pty.started.await() }
            tabs.switch(replacement.id)
            jobs.sendInput(job.id, "日本語\n", closeInput = true)
            withTimeout(3_000) { while (!pty.received.toString("UTF-8").contains("日本語")) delay(10) }
            pty.finish.complete(0)
            val done = waitDone(jobs, job.id)
            assertEquals(BackgroundCommandManager.State.EXITED, done.state)
            assertTrue(done.interactivePty)
            assertTrue(AgentCommandSessions.render(done, 0).contains("backend=pty"))
            assertEquals(listOf("interactive"), pty.launched)
            assertEquals(0, pty.pipes.get()); assertEquals(0, other.pipes.get())
            assertEquals(2, tabs.tabs.value.size)
            assertEquals(0, pty.humanInterrupts.get()); assertEquals(0, pty.destroys.get())
        } finally { jobs.close(); tabs.dispose(false); scope.cancel(); PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun unavailableBackendDoesNotCreateJobOrSilentlyLaunchAPipe() {
        val pipe = Session(); val jobs = BackgroundCommandManager(pipe)
        try {
            val error = assertThrows(IllegalArgumentException::class.java) { jobs.start("interactive", requiresTty = true) }
            assertTrue(error.message!!.contains("No command was launched"))
            assertTrue(jobs.list().isEmpty()); assertEquals(0, pipe.pipes.get())
        } finally { jobs.close() }
    }
    @Test fun stoppingAgentPtyNeverInterruptsOrDestroysHumanTerminal() = runBlocking<Unit> {
        val pty = PtySession(); val jobs = BackgroundCommandManager(pty)
        try {
            val job = jobs.start("interactive", keepStdinOpen = true, stopWithAgent = true, requiresTty = true)
            withTimeout(3_000) { pty.started.await() }
            jobs.sendInput(job.id, "pending")
            jobs.cancelForegroundCommands()
            withTimeout(3_000) { pty.cancelled.await() }
            assertEquals(BackgroundCommandManager.State.KILLED, waitDone(jobs, job.id).state)
            assertEquals(0, pty.humanInterrupts.get()); assertEquals(0, pty.destroys.get())
            assertEquals(0, pty.pipes.get())
            assertThrows(IllegalStateException::class.java) { jobs.sendInput(job.id, "late") }
        } finally { jobs.close() }
    }
    @Test fun busyHumanShellRefusesTakeoverWithoutPipeFallback() = runBlocking<Unit> {
        val pty = PtySession().apply { humanPhase = TerminalCommandPhase.RUNNING }
        val jobs = BackgroundCommandManager(pty)
        try {
            val job = jobs.start("interactive", requiresTty = true)
            val done = waitDone(jobs, job.id)
            assertEquals(BackgroundCommandManager.State.FAILED, done.state)
            assertTrue(done.output.contains("User shell is not ready"))
            assertTrue(pty.launched.isEmpty()); assertEquals(0, pty.pipes.get())
        } finally { jobs.close() }
    }
    @Test fun failedPtyInputReportsFailureDiscardsPendingTextAndDoesNotSignalHumanShell() = runBlocking<Unit> {
        val pty = PtySession().apply { failInput = true }
        val jobs = BackgroundCommandManager(pty)
        try {
            val job = jobs.start("interactive", keepStdinOpen = true, requiresTty = true)
            withTimeout(3_000) { pty.started.await() }
            jobs.sendInput(job.id, "abc")
            val done = waitDone(jobs, job.id)
            assertEquals(BackgroundCommandManager.State.FAILED, done.state)
            assertTrue(done.output.contains("input delivery could not be confirmed"))
            assertEquals("a", pty.received.toString("UTF-8"))
            assertEquals(0, pty.humanInterrupts.get()); assertEquals(0, pty.destroys.get())
            assertThrows(IllegalStateException::class.java) { jobs.sendInput(job.id, "no replay") }
        } finally { jobs.close() }
    }
    @Test fun shellApprovalIsRequiredBeforeNativeInteractiveLaunch() = runBlocking<Unit> {
        val root = Files.createTempDirectory("droide-pty-permission-").toFile()
        val pty = PtySession(); val jobs = BackgroundCommandManager(pty)
        try {
            val deny = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW), PermRule("shell", "*", PermEffect.DENY)))
            val ctx = agentWorkflowContext(root, jobs, permissions = deny)
            val result = AgentTools.execute("exec_command", buildJsonObject { put("cmd", "interactive"); put("requires_tty", true); put("yield_time_ms", 0) }, ctx)
            assertTrue(result.startsWith("DENIED")); assertTrue(jobs.list().isEmpty()); assertFalse(pty.started.isCompleted)
        } finally { jobs.close(); PathSecurity.deleteTreeNoFollow(root) }
    }
}
