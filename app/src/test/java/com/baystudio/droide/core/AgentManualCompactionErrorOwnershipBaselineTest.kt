package com.baystudio.droide.core

import java.io.File
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

/** Existing checkpoint probe only; no provider fixture, socket, or execution backend. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentManualCompactionErrorOwnershipBaselineTest {
    @get:Rule val applicationWork: TestRule = ApplicationWorkAdmissionFixture()

    @Test(timeout = 20_000)
    fun oldManualCompactionFailureCannotPublishIntoReplacementDiagnostics() = fixture { root, agent, terminal, history ->
        withTimeout(10_000) {
            val old = seed(agent, "old manual owner", AgentMode.BUILD)
            agent.loadSession(old)
            val reached = CompletableDeferred<Long>()
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val failure = FixtureIOException("deliberate old manual checkpoint failure")
            history.checkpointBoundaryProbe = { version ->
                calls.incrementAndGet(); reached.complete(version); release.await(); throw failure
            }
            val work = async { runCatching { agent.compactCurrent(AgentConfig(baseUrl = "", model = "unreachable")) } }
            try {
                val oldVersion = reached.await()
                val replacement = seed(agent, "replacement sentinel", AgentMode.PLAN)
                agent.loadSession(replacement)
                val expected = visible(agent)
                val expectedError = agent.persistenceError.value
                val expectedBytes = sessionBytes(root)
                assertNull(expectedError)
                assertEquals(replacement.id, expected.session)
                assertEquals(AgentMode.PLAN, expected.mode)
                assertFalse(expected.busy)
                assertTrue(history.ownershipVersion > oldVersion)
                release.complete(Unit)
                val outcome = work.await()

                assertEquals(1, calls.get())
                assertEquals(0, terminal.executionCalls.get())
                assertEquals(expected, visible(agent))
                assertEquals(expectedBytes, sessionBytes(root))
                assertEquals("Compaction failed: ${failure.message}", outcome.getOrThrow())
                assertEquals("An old manual failure must leave the replacement diagnostic untouched",
                    expectedError, agent.persistenceError.value)
            } finally {
                release.complete(Unit)
                withContext(NonCancellable) { withTimeout(3_000) { work.cancelAndJoin() } }
            }
        }
    }

    @Test(timeout = 20_000)
    fun fatalManualCheckpointFailureKeepsItsOriginalIdentity() = fixture { root, agent, terminal, history ->
        withTimeout(10_000) {
            agent.loadSession(seed(agent, "fatal sentinel", AgentMode.BUILD))
            val expected = visible(agent)
            val expectedBytes = sessionBytes(root)
            val calls = AtomicInteger()
            val failure = FixtureFatal("deliberate fatal checkpoint failure")
            history.checkpointBoundaryProbe = { calls.incrementAndGet(); throw failure }
            val outcome = runCatching { agent.compactCurrent(AgentConfig(baseUrl = "", model = "unreachable")) }
            assertEquals(1, calls.get())
            assertEquals(0, terminal.executionCalls.get())
            assertEquals(expected, visible(agent))
            assertEquals(expectedBytes, sessionBytes(root))
            assertSame("A fatal checkpoint failure must not become an ordinary compaction result", failure, outcome.exceptionOrNull())
            assertNull(agent.persistenceError.value)
        }
    }

    private data class Visible(val session: String?, val stream: AgentStreamState, val busy: Boolean,
        val mode: AgentMode, val messages: List<AgentMessage>, val api: List<JsonObject>,
        val pending: List<AgentPendingInput>, val todos: List<Todo>)
    private fun visible(agent: AgentService) = Visible(agent.currentSessionId, agent.streamState.value,
        agent.busy.value, agent.mode.value, agent.messages.value.toList(), agent.boundedApiHistory(),
        agent.pendingInputs.value.toList(), agent.todos.get(agent.currentSessionId.orEmpty()))
    private fun sessionBytes(root: File) = root.resolve(".droide/sessions").walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }

    private suspend fun seed(agent: AgentService, text: String, mode: AgentMode): DroideSession {
        val api = listOf(buildJsonObject { put("role", "user"); put("content", text) },
            buildJsonObject { put("role", "assistant"); put("content", "$text answer") })
        return agent.sessions.create(text).copy(mode = mode, apiHistory = api, forkHistory = api,
            messages = listOf(AgentMessage("user", text), AgentMessage("assistant", "$text answer")))
            .also { agent.sessions.save(it) }
    }

    private fun fixture(block: suspend CoroutineScope.(File, AgentService, NoProcessTerminal, AgentConversationHistory) -> Unit) {
        val root = Files.createTempDirectory("droide-manual-error-owner-").toFile()
        val terminal = NoProcessTerminal()
        val agent = AgentService(FileRepository(root), terminal, GitManager(root), maxSubagentDepth = 0)
        val history = AgentService::class.java.getDeclaredField("conversationHistory")
            .apply { isAccessible = true }.get(agent) as AgentConversationHistory
        try { runBlocking { block(root, agent, terminal, history) } }
        finally { history.checkpointBoundaryProbe = null; agent.shutdown(); PathSecurity.deleteTreeNoFollow(root) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private class FixtureIOException(message: String) : IOException(message), CopyableThrowable<FixtureIOException> {
        override fun createCopy(): FixtureIOException? = null
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    private class FixtureFatal(message: String) : AssertionError(message), CopyableThrowable<FixtureFatal> {
        override fun createCopy(): FixtureFatal? = null
    }
    private class NoProcessTerminal : ITerminalSession {
        override val output = MutableStateFlow("")
        val executionCalls = AtomicInteger()
        private fun forbidden(): Nothing {
            executionCalls.incrementAndGet(); throw AssertionError("No execution backend is permitted")
        }
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
