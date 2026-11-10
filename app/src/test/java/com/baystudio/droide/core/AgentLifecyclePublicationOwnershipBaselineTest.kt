package com.baystudio.droide.core

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
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

/** No-op boundary probes widen existing windows; they do not replace any owner checks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentLifecyclePublicationOwnershipBaselineTest {
    @get:Rule val applicationWork: TestRule = ApplicationWorkAdmissionFixture()

    @Test(timeout = 30_000)
    fun createdSessionCannotInstallAfterReplacementLoad() = scenario(
        AgentService.PublicationBoundary.CREATED_SESSION, expectedRequests = 0,
    ) { before, after ->
        assertEquals("An obsolete creation must not install its session", before.sessionId, after.sessionId)
        assertUnchanged(before, after)
    }

    @Test(timeout = 30_000)
    fun readyOldRunCannotInstallItsInitialStreamAfterReplacementLoad() = scenario(
        AgentService.PublicationBoundary.INITIAL_STREAM, expectedRequests = 0,
    ) { before, after ->
        assertEquals("An obsolete start must not replace the visible stream", before.stream, after.stream)
        assertUnchanged(before, after)
    }

    @Test(timeout = 30_000)
    fun admittedOldProviderEventCannotPublishAfterReplacementLoad() = scenario(
        AgentService.PublicationBoundary.PROVIDER_EVENT, expectedRequests = 1,
    ) { before, after ->
        assertEquals("An old provider callback must not publish into the replacement", before.stream, after.stream)
        assertUnchanged(before, after)
    }

    @Test(timeout = 30_000)
    fun returnedOldProviderResultCannotPublishAfterReplacementLoad() = scenario(
        AgentService.PublicationBoundary.PROVIDER_RESULT, expectedRequests = 1,
    ) { before, after ->
        assertEquals("An old returned result must not publish into the replacement", before.stream, after.stream)
        assertUnchanged(before, after)
    }

    @Test(timeout = 30_000)
    fun oldAssistantAppendCannotCaptureReplacementHistoryOwner() = scenario(
        AgentService.PublicationBoundary.ASSISTANT_HISTORY_APPEND, expectedRequests = 1,
    ) { before, after ->
        assertEquals("A delayed append must keep its original history owner", before.api, after.api)
        assertUnchanged(before, after)
    }

    private data class Visible(
        val sessionId: String?, val stream: AgentStreamState, val busy: Boolean,
        val messages: List<AgentMessage>, val api: List<JsonObject>,
    )

    private fun visible(agent: AgentService) = Visible(agent.currentSessionId, agent.streamState.value,
        agent.busy.value, agent.messages.value.toList(), agent.boundedApiHistory())

    private fun assertUnchanged(before: Visible, after: Visible) {
        assertEquals(before.sessionId, after.sessionId)
        assertEquals(before.stream.runId, after.stream.runId)
        assertEquals(before.stream.sessionId, after.stream.sessionId)
        assertEquals(before.stream, after.stream)
        assertEquals(before.busy, after.busy)
        assertEquals(before.messages, after.messages)
        assertEquals(before.api, after.api)
    }

    private fun scenario(boundary: AgentService.PublicationBoundary, expectedRequests: Int,
        assertions: (Visible, Visible) -> Unit) {
        val root = Files.createTempDirectory("droide-lifecycle-owner-").toFile()
        try {
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, authRequired = false) { provider ->
                val terminal = NoProcessTerminal()
                val agent = AgentService(FileRepository(root), terminal, GitManager(root), maxSubagentDepth = 0)
                val reached = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val once = AtomicBoolean(false)
                val hits = AtomicInteger()
                agent.publicationBoundaryProbe = { point ->
                    if (point == boundary && once.compareAndSet(false, true)) {
                        hits.incrementAndGet()
                        reached.complete(Unit)
                        release.await()
                    }
                }
                provider.response = { _, _, _ ->
                    provider.event("""{"choices":[{"index":0,"delta":{"content":"old provider answer"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n"
                }
                try {
                    runBlocking {
                        withTimeout(15_000) {
                            // Direct chat is intentionally owned by this test job. loadSession revokes its run/session
                            // authority without cancelling this waiter.
                            val oldRun = launch(Dispatchers.Default) {
                                agent.chat("Explain the old task", provider.config, maxTurns = 1)
                            }
                            try {
                                reached.await()
                                val sentinel = buildJsonObject { put("role", "user"); put("content", "replacement sentinel") }
                                val replacement = DroideSession("replacement-owner", "Replacement",
                                    messages = listOf(AgentMessage("user", "replacement sentinel")),
                                    apiHistory = listOf(sentinel), forkHistory = listOf(sentinel),
                                    forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION)
                                agent.sessions.save(replacement)
                                agent.loadSession(replacement)
                                val before = visible(agent)
                                assertEquals(replacement.id, before.sessionId)
                                assertEquals(listOf(sentinel), before.api)
                                assertFalse(before.busy)
                                assertTrue(before.stream.parts.isEmpty())
                                release.complete(Unit)
                                oldRun.join()
                                assertEquals("Expected boundary must be reached exactly once", 1, hits.get())
                                assertEquals(expectedRequests, provider.requests.size)
                                assertEquals("No terminal execution is permitted", 0, terminal.executionCalls.get())
                                assertions(before, visible(agent))
                            } finally {
                                release.complete(Unit)
                                withContext(NonCancellable) { withTimeout(5_000) { oldRun.cancelAndJoin() } }
                            }
                        }
                    }
                } finally {
                    release.complete(Unit)
                    agent.publicationBoundaryProbe = null
                    agent.shutdown()
                }
                assertEquals(0, terminal.executionCalls.get())
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private class NoProcessTerminal : ITerminalSession {
        override val output = MutableStateFlow("")
        val executionCalls = AtomicInteger()
        private fun forbidden(): Nothing {
            executionCalls.incrementAndGet()
            throw AssertionError("Process execution is forbidden in the ownership baseline")
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
