package com.baystudio.droide.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class BackgroundCommandHeartbeatTest {
    @get:org.junit.Rule val applicationAdmission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    @Test fun quietCommandPublishesActivityWithoutChangingOutputOrExecutingTwice() = runBlocking {
        val finish = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var executions = 0
        val terminal = object : ITerminalSession {
            override val output = MutableStateFlow("")
            override fun start() = Unit
            override fun send(cmd: String) = Unit
            override suspend fun execOnce(cmd: String, timeoutMs: Long) = error("Not used")
            override suspend fun execArgv(argv: List<String>, timeoutMs: Long) = error("Not used")
            override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult {
                executions++
                onOutput("prefix")
                started.complete(Unit)
                finish.await()
                onOutput(" suffix")
                return ExecResult(exitCode = 0, output = "prefix suffix", timedOut = false)
            }
            override fun interrupt() = Unit
            override fun clear() = Unit
            override fun destroy() = Unit
        }
        val manager = BackgroundCommandManager(terminal, heartbeatIntervalMs = 25)
        try {
            val job = manager.start("quiet fixture")
            // A scheduler may delay the worker beyond the short heartbeat interval.
            // Exercise a quiet *running* command instead of assuming its launch took <25 ms.
            withTimeout(2_000) { started.await() }
            val activity = CompletableDeferred<String>()
            val chunks = StringBuilder()
            val waiting = async { manager.awaitExit(job.id, 0, onActivity = { activity.complete(it) }) { chunks.append(it) } }
            val feedback = withTimeout(2_000) { activity.await() }
            assertTrue(feedback.contains(job.id))
            assertTrue(feedback.contains("waiting for final exit status"))
            assertEquals("prefix", manager.snapshot(job.id)?.output)
            finish.complete(Unit)
            val result = withTimeout(2_000) { waiting.await() }
            assertEquals(BackgroundCommandManager.State.EXITED, result.state)
            assertEquals(1, executions)
            assertEquals("prefix suffix", chunks.toString())
            var lateActivity = false
            manager.awaitExit(job.id, result.outputEndCursor, onActivity = { lateActivity = true }) {}
            assertFalse(lateActivity)
        } finally { finish.complete(Unit); manager.close() }
    }
}
