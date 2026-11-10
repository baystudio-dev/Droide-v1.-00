package com.baystudio.droide.core

import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentManualCompactionErrorControlTest {
    @get:Rule val applicationWork: TestRule = ApplicationWorkAdmissionFixture()

    @Test(timeout = 15_000)
    fun currentOrdinaryFailureStillPublishesItsDiagnosticWithoutChangingHistory() {
        val root = Files.createTempDirectory("droide-current-manual-error-").toFile()
        val terminal = NoProcessTerminal()
        val agent = AgentService(FileRepository(root), terminal, GitManager(root), maxSubagentDepth = 0)
        val history = AgentService::class.java.getDeclaredField("conversationHistory")
            .apply { isAccessible = true }.get(agent) as AgentConversationHistory
        try { runBlocking { withTimeout(8_000) {
            val api = listOf(buildJsonObject { put("role", "user"); put("content", "sentinel") },
                buildJsonObject { put("role", "assistant"); put("content", "answer") })
            val session = agent.sessions.create("sentinel").copy(apiHistory = api, forkHistory = api,
                messages = listOf(AgentMessage("user", "sentinel"), AgentMessage("assistant", "answer")))
            agent.sessions.save(session); agent.loadSession(session)
            val messages = agent.messages.value.toList()
            val stream = agent.streamState.value
            val bytes = root.resolve(".droide/sessions").walkTopDown().filter { it.isFile }
                .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
            val hits = AtomicInteger()
            history.checkpointBoundaryProbe = { hits.incrementAndGet(); throw IOException("current checkpoint failure") }
            assertEquals("Compaction failed: current checkpoint failure",
                agent.compactCurrent(AgentConfig(baseUrl = "", model = "unreachable")))
            assertEquals(1, hits.get())
            assertEquals(0, terminal.executionCalls.get())
            assertEquals(session.id, agent.currentSessionId)
            assertEquals(messages, agent.messages.value)
            assertEquals(api, agent.boundedApiHistory())
            assertEquals(stream, agent.streamState.value)
            assertFalse(agent.busy.value)
            assertEquals(bytes, root.resolve(".droide/sessions").walkTopDown().filter { it.isFile }
                .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() })
            assertEquals("Compaction failed: current checkpoint failure", agent.persistenceError.value)
        } } } finally { history.checkpointBoundaryProbe = null; agent.shutdown(); PathSecurity.deleteTreeNoFollow(root) }
    }
    private class NoProcessTerminal : ITerminalSession {
        override val output = MutableStateFlow("")
        val executionCalls = AtomicInteger()
        private fun forbidden(): Nothing { executionCalls.incrementAndGet(); throw AssertionError("No execution backend is permitted") }
        override fun start() = forbidden()
        override fun send(cmd: String) = forbidden()
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = forbidden()
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult = forbidden()
        override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult = forbidden()
        override suspend fun execStreamingWithInput(cmd: String, timeoutMs: Long, input: ProcessInputBuffer,
            onOutput: (String) -> Unit): ExecResult = forbidden()
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun destroy() = Unit
    }
}
