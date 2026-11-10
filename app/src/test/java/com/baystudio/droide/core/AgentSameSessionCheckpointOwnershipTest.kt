package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentSameSessionCheckpointOwnershipTest {
    @get:org.junit.Rule
    val applicationAdmission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    @Test(timeout = 30_000)
    fun nextSameSessionRunRevokesTheOldFinalCheckpointAfterEarlyIdle() {
        val root = Files.createTempDirectory("droide-same-session-checkpoint-").toFile()
        try {
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, authRequired = false) { provider ->
                provider.secret = ""
                val terminal = NoProcessTerminal()
                val agent = AgentService(FileRepository(root), terminal, GitManager(root), maxSubagentDepth = 0)
                val history = AgentService::class.java.getDeclaredField("conversationHistory")
                    .apply { isAccessible = true }.get(agent) as AgentConversationHistory
                val coordinator = AgentService::class.java.getDeclaredField("promptCoordinator")
                    .apply { isAccessible = true }.get(agent) as AgentPromptCoordinator
                val checkpointReached = CompletableDeferred<Long>()
                val releaseCheckpoint = CompletableDeferred<Unit>()
                val nextRunReached = CompletableDeferred<Unit>()
                val releaseNextRun = CompletableDeferred<Unit>()
                val checkpointHits = AtomicInteger()
                val streamHits = AtomicInteger()
                history.checkpointBoundaryProbe = { version ->
                    if (checkpointHits.incrementAndGet() == 1) {
                        checkpointReached.complete(version)
                        releaseCheckpoint.await()
                    }
                }
                agent.publicationBoundaryProbe = { boundary ->
                    if (boundary == AgentService.PublicationBoundary.INITIAL_STREAM && streamHits.incrementAndGet() == 2) {
                        nextRunReached.complete(Unit)
                        releaseNextRun.await()
                    }
                }
                provider.response = { index, _, streaming ->
                    assertTrue(streaming)
                    provider.event("""{"choices":[{"index":0,"delta":{"content":"answer $index"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n"
                }
                try { runBlocking { withTimeout(15_000) {
                    val child = Job(coroutineContext[Job])
                    val scope = CoroutineScope(coroutineContext + child)
                    val first = scope.async { runCatching { agent.chat("Explain the first task", provider.config, maxTurns = 1) } }
                    try {
                        val oldVersion = checkpointReached.await()
                        assertFalse("Empty allowQueue has already released the run lease", coordinator.hasReservedRun)
                        assertFalse(first.isCompleted)
                        val session = requireNotNull(agent.currentSessionId)
                        val retained = history.active.toList()
                        val hotBytes = history.hotHistoryBytes
                        assertTrue(hotBytes > 0)
                        assertTrue(retained.any { it["content"] == JsonPrimitive("answer 1") })
                        val beforeBytes = sessionBytes(root)
                        val second = scope.async { runCatching { agent.chat("Explain the second task", provider.config, maxTurns = 1) } }
                        nextRunReached.await()
                        assertEquals(session, agent.currentSessionId)
                        assertEquals(2, streamHits.get())
                        assertEquals(1, checkpointHits.get())
                        assertEquals(1, provider.requests.size)
                        assertEquals(0, terminal.executionCalls.get())

                        releaseCheckpoint.complete(Unit)
                        val oldOutcome = first.await()

                        assertEquals("An obsolete final checkpoint must not drain the retained current tail",
                            hotBytes, history.hotHistoryBytes)
                        assertEquals(beforeBytes, sessionBytes(root))
                        assertEquals(retained, history.active)
                        assertTrue(history.ownershipVersion > oldVersion)
                        assertTrue(oldOutcome.exceptionOrNull() is CancellationException)
                        releaseNextRun.complete(Unit)
                        second.await().getOrThrow()
                        assertEquals(session, agent.currentSessionId)
                        assertEquals(2, provider.requests.size)
                        assertTrue(agent.boundedApiHistory().any { it["content"] == JsonPrimitive("answer 1") })
                        assertTrue(agent.boundedApiHistory().any { it["content"] == JsonPrimitive("answer 2") })
                        assertEquals(0, terminal.executionCalls.get())
                    } finally {
                        releaseCheckpoint.complete(Unit)
                        releaseNextRun.complete(Unit)
                        withContext(NonCancellable) { withTimeout(5_000) { child.cancelAndJoin() } }
                    }
                } } } finally {
                    history.checkpointBoundaryProbe = null
                    agent.publicationBoundaryProbe = null
                    agent.shutdown()
                }
                assertEquals(0, terminal.executionCalls.get())
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private fun sessionBytes(root: File) = File(root, ".droide/sessions").walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }

    private class NoProcessTerminal : ITerminalSession {
        override val output = MutableStateFlow("")
        val executionCalls = AtomicInteger()
        private fun forbidden(): Nothing {
            executionCalls.incrementAndGet()
            throw AssertionError("Process execution is forbidden in the checkpoint fixture")
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
