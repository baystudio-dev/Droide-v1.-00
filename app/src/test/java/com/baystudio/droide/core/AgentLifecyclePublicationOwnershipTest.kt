package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Current ownership, Stop and teardown controls; synthetic loopback and no process backend. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentLifecyclePublicationOwnershipTest {
    @get:Rule val applicationWork: TestRule = ApplicationWorkAdmissionFixture()

    @Test(timeout = 30_000)
    fun currentRunPublishesAndNextRunRetainsTheSameConversation() = scenario { env ->
        val observed = CopyOnWriteArrayList<AgentService.PublicationBoundary>()
        env.agent.publicationBoundaryProbe = { observed += it }
        env.agent.chat("Explain the first task", env.provider.config, maxTurns = 1)
        val session = requireNotNull(env.agent.currentSessionId)
        assertFalse(env.agent.busy.value)
        AgentService.PublicationBoundary.entries.forEach { point -> assertEquals(point.name, 1, observed.count { it == point }) }
        assertEquals(1, env.agent.boundedApiHistory().count { it["content"] == JsonPrimitive(ANSWER) })
        assertTrue(env.agent.messages.value.any { it.content == ANSWER })
        val firstGeneration = env.agent.streamState.value.runId

        env.agent.chat("Explain the second task", env.provider.config, maxTurns = 1)

        assertEquals(session, env.agent.currentSessionId)
        assertTrue(env.agent.streamState.value.runId > firstGeneration)
        assertFalse(env.agent.busy.value)
        assertEquals(2, env.agent.boundedApiHistory().count { it["content"] == JsonPrimitive(ANSWER) })
        val saved = requireNotNull(env.agent.sessions.get(session))
        assertEquals(2, saved.apiHistory.count { it["content"] == JsonPrimitive(ANSWER) })
        assertEquals(2, env.agent.tokens.history.value.size)
        assertEquals(2, env.provider.requests.size)
    }

    @Test(timeout = 30_000)
    fun stopRetainsCurrentOwnerAndPersistsItsCancellation() = scenario { env ->
        val gate = env.gate(AgentService.PublicationBoundary.ASSISTANT_HISTORY_APPEND)
        assertNotNull(env.agent.submitAsync(env.scope, "Explain before Stop", env.provider.config, maxTurns = 1))
        gate.reached.await()
        val session = requireNotNull(env.agent.currentSessionId)
        val generation = env.agent.streamState.value.runId
        env.agent.cancel()
        env.agent.busy.first { !it }

        assertEquals(session, env.agent.currentSessionId)
        assertEquals(generation, env.agent.streamState.value.runId)
        assertEquals(AgentStreamState.Status.IDLE, env.agent.streamState.value.status)
        assertNull(env.agent.persistenceError.value)
        val saved = requireNotNull(env.agent.sessions.get(session))
        assertTrue(saved.messages.any { it.content == "Stopped by user." })
        assertEquals(1, saved.apiHistory.count { it["content"] == JsonPrimitive("Stopped by user.") })
        assertEquals(1, env.provider.requests.size)
    }

    @Test(timeout = 30_000)
    fun shutdownRevokesAnUninstalledCreatedSession() = scenario { env ->
        val gate = env.gate(AgentService.PublicationBoundary.CREATED_SESSION)
        val old = env.scope.launch(Dispatchers.Default) { env.agent.chat("Explain before close", env.provider.config, maxTurns = 1) }
        gate.reached.await()
        env.agent.shutdown()
        val closed = snapshot(env.agent)
        gate.release.complete(Unit)
        old.join()

        assertNull(env.agent.currentSessionId)
        assertEquals(closed, snapshot(env.agent))
        assertEquals(0, env.provider.requests.size)
    }

    @Test(timeout = 30_000)
    fun shutdownRevokesAHistoryAppendWithoutClearingExistingState() = scenario { env ->
        val gate = env.gate(AgentService.PublicationBoundary.ASSISTANT_HISTORY_APPEND)
        val old = env.scope.launch(Dispatchers.Default) { env.agent.chat("Explain before close", env.provider.config, maxTurns = 1) }
        gate.reached.await()
        assertFalse(env.agent.boundedApiHistory().any { it["content"] == JsonPrimitive(ANSWER) })
        env.agent.shutdown()
        val closed = snapshot(env.agent)
        gate.release.complete(Unit)
        old.join()

        assertEquals(closed, snapshot(env.agent))
        assertEquals(1, env.provider.requests.size)
    }

    @Test(timeout = 30_000)
    fun staleReturnedResultStillRecordsIncurredProviderUsageExactlyOnce() = scenario { env ->
        val gate = env.gate(AgentService.PublicationBoundary.PROVIDER_RESULT)
        val old = env.scope.launch(Dispatchers.Default) { env.agent.chat("Explain the obsolete task", env.provider.config, maxTurns = 1) }
        gate.reached.await()
        val replacement = env.agent.sessions.create("Replacement")
        env.agent.loadSession(replacement)
        val before = snapshot(env.agent)
        gate.release.complete(Unit)
        old.join()

        assertEquals(before, snapshot(env.agent))
        assertEquals(1, env.agent.tokens.history.value.size)
        assertEquals(7, env.agent.tokens.usage.value.prompt)
        assertEquals(3, env.agent.tokens.usage.value.completion)
        assertEquals(10, env.agent.tokens.usage.value.total)
        assertEquals(1, env.provider.requests.size)
    }

    private data class Visible(val session: String?, val stream: AgentStreamState, val busy: Boolean,
        val messages: List<AgentMessage>, val api: List<JsonObject>)
    private fun snapshot(agent: AgentService) = Visible(agent.currentSessionId, agent.streamState.value,
        agent.busy.value, agent.messages.value.toList(), agent.boundedApiHistory())

    private class Gate {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    private class Environment(val agent: AgentService, val provider: ProviderStreamFixture, val scope: CoroutineScope) {
        val gates = mutableListOf<Gate>()
        fun gate(point: AgentService.PublicationBoundary): Gate = Gate().also { gate ->
            gates += gate
            agent.publicationBoundaryProbe = { observed -> if (observed == point && gate.reached.complete(Unit)) gate.release.await() }
        }
    }

    private fun scenario(body: suspend (Environment) -> Unit) {
        val root = Files.createTempDirectory("droide-lifecycle-controls-").toFile()
        try {
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, authRequired = false) { provider ->
                provider.secret = ""
                val terminal = NoProcessTerminal()
                val agent = AgentService(FileRepository(root), terminal, GitManager(root), maxSubagentDepth = 0)
                provider.response = { _, _, _ ->
                    provider.event("""{"choices":[{"index":0,"delta":{"content":"$ANSWER"},"finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}""") + "data: [DONE]\n\n"
                }
                try {
                    runBlocking {
                        withTimeout(15_000) {
                            val child = Job(coroutineContext[Job])
                            val scope = CoroutineScope(coroutineContext + child)
                            val env = Environment(agent, provider, scope)
                            try { body(env); assertEquals(0, terminal.executionCalls.get()) }
                            finally {
                                env.gates.forEach { it.release.complete(Unit) }
                                withContext(NonCancellable) { withTimeout(5_000) { child.cancelAndJoin() } }
                            }
                        }
                    }
                } finally { agent.publicationBoundaryProbe = null; agent.shutdown() }
                assertEquals(0, terminal.executionCalls.get())
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private class NoProcessTerminal : ITerminalSession {
        override val output = MutableStateFlow("")
        val executionCalls = AtomicInteger()
        private fun forbidden(): Nothing {
            executionCalls.incrementAndGet()
            throw AssertionError("Process execution is forbidden in the ownership controls")
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

    companion object { private const val ANSWER = "current provider answer" }
}
