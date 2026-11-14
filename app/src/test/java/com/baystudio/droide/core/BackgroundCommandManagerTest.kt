package com.baystudio.droide.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

/** Controller integration with real JVM processes; Android shell/PRoot is not exercised here. */
class BackgroundCommandManagerTest {
    @get:org.junit.Rule val applicationAdmission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    @Test fun defaultBackgroundCommandUsesUnlimitedDeadlineAndCapturesStreamingExit() = runBlocking {
        val terminal = HostTerminal()
        val manager = BackgroundCommandManager(terminal)
        try {
            val initial = manager.start("sleep 0.2; printf 'background 日本語 😀'; exit 7")
            val final = withTimeout(3_000) {
                while (manager.snapshot(initial.id)?.state == BackgroundCommandManager.State.RUNNING) delay(10)
                requireNotNull(manager.snapshot(initial.id))
            }
            assertEquals(0L, terminal.deadlines[initial.command])
            assertEquals(BackgroundCommandManager.State.FAILED, final.state)
            assertEquals(7, final.exitCode)
            assertEquals("background 日本語 😀", final.output)
        } finally { manager.close() }
    }

    @Test fun killCancelsUnlimitedProcessAndPreservesKilledStatus() = runBlocking {
        val terminal = HostTerminal()
        val manager = BackgroundCommandManager(terminal)
        val pid = File.createTempFile("droide-background-", ".pid").apply { delete() }
        try {
            val command = "echo \$\$ > " + quote(pid.absolutePath) + "; exec sleep 30"
            val initial = manager.start(command)
            withTimeout(3_000) { while (!pid.isFile || pid.readText().trim().isEmpty()) delay(10) }
            val child = pid.readText().trim()
            val killed = withTimeout(2_500) { manager.kill(initial.id) }
            assertEquals(BackgroundCommandManager.State.KILLED, killed?.state)
            assertNotNull(killed?.finishedAtMs)
            withTimeout(2_500) { while (isRunning(child)) delay(10) }
            assertEquals(BackgroundCommandManager.State.KILLED, manager.snapshot(initial.id)?.state)
        } finally { manager.close(); pid.delete() }
    }

    @Test fun closeIsIdempotentAndRejectsNewEntries() {
        val manager = BackgroundCommandManager(HostTerminal())
        manager.close(); manager.close()
        assertTrue(runCatching { manager.start("printf late") }.exceptionOrNull() is IllegalStateException)
        assertTrue(manager.list().isEmpty())
    }

    @Test fun lateIoFailureCannotOverwriteExplicitKill() = lateFailureAfterStop(close = false)

    @Test fun lateIoFailureCannotOverwriteWorkspaceClose() = lateFailureAfterStop(close = true)

    private fun lateFailureAfterStop(close: Boolean) = runBlocking {
        val started = CompletableDeferred<Job>()
        val release = CompletableDeferred<Unit>()
        val terminal = object : ITerminalSession by HostTerminal() {
            override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult {
                started.complete(currentCoroutineContext().job)
                withContext(NonCancellable) { release.await() }
                throw java.io.IOException("late pipe close failure")
            }
        }
        val manager = BackgroundCommandManager(terminal)
        try {
            val initial = manager.start("blocked fixture")
            val execution = withTimeout(3_000) { started.await() }
            val stopping = if (close) {
                manager.close()
                null
            } else async { manager.kill(initial.id) }
            withTimeout(3_000) {
                while (manager.snapshot(initial.id)?.state != BackgroundCommandManager.State.KILLED) yield()
            }
            release.complete(Unit)
            withTimeout(3_000) { stopping?.await(); execution.join() }
            val final = manager.snapshot(initial.id)
            assertEquals(BackgroundCommandManager.State.KILLED, final?.state)
            assertNotNull(final?.finishedAtMs)
        } finally { release.complete(Unit); manager.close() }
    }

    @Test fun concurrentListKillCannotLaunchAfterItsKilledJobIsAttached() = runBlocking {
        val terminal = HostTerminal()
        val manager = BackgroundCommandManager(terminal)
        val dir = java.nio.file.Files.createTempDirectory("droide-background-race-").toFile()
        try {
            // A real process writes its pid before sleeping; every observed RUNNING entry is killed.
            repeat(100) { iteration ->
                val pid = File(dir, "$iteration.pid")
                val command = "echo \$\$ > " + quote(pid.absolutePath) + "; exec sleep 30"
                val launch = async(Dispatchers.Default) { manager.start(command) }
                val killed = withTimeout(3_000) {
                    var observed: BackgroundCommandManager.Snapshot? = null
                    while (observed == null) {
                        observed = manager.list().firstOrNull { it.command == command }
                        if (observed == null) yield()
                    }
                    manager.kill(observed.id)
                }
                launch.await()
                assertEquals(BackgroundCommandManager.State.KILLED, killed?.state)
                // Job attachment may race kill's null-job snapshot; wait for launch to settle.
                withTimeout(2_500) {
                    while (terminal.active.containsKey(command)) delay(10)
                }
                if (pid.isFile) {
                    val child = pid.readText().trim()
                    withTimeout(2_500) { while (isRunning(child)) delay(10) }
                }
            }
        } finally { manager.close(); PathSecurity.deleteTreeNoFollow(dir) }
    }

    @Test fun delayedOutputCallbackCannotChangeKilledSnapshot() = runBlocking {
        val callback = CompletableDeferred<(String) -> Unit>()
        val terminal = object : ITerminalSession by HostTerminal() {
            override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult {
                onOutput("before cancellation")
                callback.complete(onOutput)
                awaitCancellation()
            }
        }
        val manager = BackgroundCommandManager(terminal)
        try {
            val started = manager.start("simulated delayed backend")
            val delayed = withTimeout(2_500) { callback.await() }
            val stopped = withTimeout(2_500) { manager.kill(started.id) }
            assertEquals(BackgroundCommandManager.State.KILLED, stopped?.state)
            val prior = requireNotNull(manager.snapshot(started.id))
            delayed("unexpected after cancellation")
            val later = requireNotNull(manager.snapshot(started.id))
            assertEquals(prior.output, later.output)
            assertEquals(prior.outputEndCursor, later.outputEndCursor)
            assertEquals(BackgroundCommandManager.State.KILLED, later.state)
        } finally { manager.close() }
    }

    private fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"

    private fun isRunning(pid: String): Boolean {
        // A process killed between redirection and echo leaves an empty PID file. Never
        // interpret that as /proc/stat (which exists and describes the whole kernel).
        if (!pid.matches(Regex("[1-9][0-9]*"))) return false
        // Some container init processes leave killed orphans as zombies. A
        // zombie cannot execute; live children still fail this bounded check.
        val stat = File("/proc/$pid/stat")
        return runCatching { stat.readText().substringAfterLast(") ").first() != 'Z' }.getOrDefault(false)
    }

    private class HostTerminal : ITerminalSession {
        override val output = MutableStateFlow("")
        val deadlines = ConcurrentHashMap<String, Long>()
        val active = ConcurrentHashMap<String, Boolean>()
        override fun start() = Unit
        override fun send(cmd: String) = Unit
        override suspend fun execOnce(cmd: String, timeoutMs: Long) = execStreaming(cmd, timeoutMs) {}
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long) = error("Not used")
        override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult {
            deadlines[cmd] = timeoutMs
            active[cmd] = true
            try {
                return LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", cmd), File(System.getProperty("java.io.tmpdir")),
                    timeoutMs = timeoutMs, onOutput = onOutput, keepTail = true)
            } finally { active.remove(cmd) }
        }
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun destroy() = Unit
    }
}
