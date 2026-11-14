package com.baystudio.droide.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Fake session results exercise real manager completion; no process backend. */
class BackgroundCommandOutputCompletenessR39Test {
    @get:org.junit.Rule val applicationAdmission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    @Test(timeout = 10_000)
    fun streamedPrefixRetainsIncompleteFlagWithoutCursorReplay() = runBlocking {
        val terminal = FakeTerminal(ExecResult(0, "prefix\n[output incomplete]", false, outputIncomplete = true), "prefix")
        val manager = BackgroundCommandManager(terminal)
        try {
            val initial = manager.start("fake-command")
            val result = withTimeout(3_000) { manager.awaitExit(initial.id, 0) { } }
            assertEquals(BackgroundCommandManager.State.EXITED, result.state)
            assertEquals(0, result.exitCode)
            assertTrue(result.outputIncomplete)
            assertEquals("prefix", result.output)
            assertEquals(6L, result.outputEndCursor)
            val unchanged = manager.awaitOutput(initial.id, result.outputEndCursor, 0)
            assertTrue(unchanged.outputIncomplete)
            assertEquals(result.outputEndCursor, unchanged.outputEndCursor)
            assertEquals(result.output, unchanged.output)
            assertTrue(manager.list().single().outputIncomplete)
        } finally { manager.close() }
    }

    @Test(timeout = 10_000)
    fun incompleteNaturalFailureRemainsFailureRatherThanTimeout() = runBlocking {
        val terminal = FakeTerminal(ExecResult(7, "error", false, outputIncomplete = true), "error")
        val manager = BackgroundCommandManager(terminal)
        try {
            val initial = manager.start("fake-command")
            val result = withTimeout(3_000) { manager.awaitExit(initial.id, 0) { } }
            assertEquals(BackgroundCommandManager.State.FAILED, result.state)
            assertEquals(7, result.exitCode)
            assertTrue(result.outputIncomplete)
        } finally { manager.close() }
    }

    @Test(timeout = 10_000)
    fun resultWithoutStreamingStillCarriesIncompleteFlagAndFinalText() = runBlocking {
        val terminal = FakeTerminal(ExecResult(0, "[output incomplete]", false, outputIncomplete = true))
        val manager = BackgroundCommandManager(terminal)
        try {
            val initial = manager.start("fake-command")
            val result = withTimeout(3_000) { manager.awaitExit(initial.id, 0) { } }
            assertEquals(BackgroundCommandManager.State.EXITED, result.state)
            assertTrue(result.outputIncomplete)
            assertEquals("[output incomplete]", result.output)
            assertEquals(result.output.length.toLong(), result.outputEndCursor)
        } finally { manager.close() }
    }

    @Test(timeout = 10_000)
    fun completeOutputKeepsDefaultFalseAndLateCallbackCannotMutateCompletion() = runBlocking {
        val terminal = FakeTerminal(ExecResult(0, "done", false), "done")
        val manager = BackgroundCommandManager(terminal)
        try {
            val initial = manager.start("fake-command")
            val result = withTimeout(3_000) { manager.awaitExit(initial.id, 0) { } }
            assertFalse(result.outputIncomplete)
            assertEquals(BackgroundCommandManager.State.EXITED, result.state)
            val callback = withTimeout(3_000) { terminal.callback.await() }
            callback("LATE")
            val after = requireNotNull(manager.snapshot(initial.id))
            assertEquals(result, after)
            assertFalse(after.outputIncomplete)
        } finally { manager.close() }
    }

    private class FakeTerminal(private val result: ExecResult, private val streamed: String = "") : ITerminalSession {
        override val output = MutableStateFlow("")
        val callback = CompletableDeferred<(String) -> Unit>()
        override fun start() = Unit
        override fun send(cmd: String) = Unit
        override suspend fun execOnce(cmd: String, timeoutMs: Long) = result
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long) = result
        override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult {
            callback.complete(onOutput)
            if (streamed.isNotEmpty()) onOutput(streamed)
            return result
        }
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun destroy() = Unit
    }
}
