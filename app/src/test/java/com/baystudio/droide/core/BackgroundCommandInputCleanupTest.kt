package com.baystudio.droide.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class BackgroundCommandInputCleanupTest {
    @get:org.junit.Rule val applicationAdmission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    @Test fun exitedInteractiveCommandDiscardsInputWithoutSendingEofToClosedPty() = cleanup(stop = false)
    @Test fun stoppedInteractiveCommandDiscardsInputWithoutSendingEofToClosedPty() = cleanup(stop = true)

    private fun cleanup(stop: Boolean) = runBlocking {
        val started = CompletableDeferred<Pair<Job, ProcessInputBuffer>>()
        val finish = CompletableDeferred<Unit>()
        val eofCalls = AtomicInteger()
        val terminal = object : ITerminalSession by AgentWorkflowTerminal(), InteractivePtyCommandSession {
            override val supportsInteractivePty = true
            override suspend fun execInteractivePty(command: String, timeoutMs: Long, input: ProcessInputBuffer?, onOutput: (String) -> Unit): ExecResult {
                val stdin = requireNotNull(input)
                stdin.bindPty({ true }) { eofCalls.incrementAndGet(); false }
                started.complete(currentCoroutineContext().job to stdin)
                finish.await()
                return ExecResult(0, "complete", false)
            }
        }
        val manager = BackgroundCommandManager(terminal)
        try {
            val snapshot = manager.start("interactive fixture", keepStdinOpen = true, stopWithAgent = true, requiresTty = true)
            val (execution, stdin) = withTimeout(5_000) { started.await() }
            if (stop) manager.cancelForegroundCommands() else finish.complete(Unit)
            withTimeout(5_000) { execution.join() }
            assertEquals(0, eofCalls.get())
            assertTrue(stdin.isAborted)
            assertEquals(if (stop) BackgroundCommandManager.State.KILLED else BackgroundCommandManager.State.EXITED, manager.snapshot(snapshot.id)?.state)
            assertTrue(runCatching { stdin.offer("never replay") }.isFailure)
        } finally { finish.complete(Unit); manager.close() }
    }
}
