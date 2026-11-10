package com.baystudio.droide.core

import java.io.File
import java.net.URI
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

/** Existing APIs only. One synthetic loopback reply, a one-byte edit, and no process backend. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentPartialOutcomeBaselineTest {
    @get:Rule val applicationWork: TestRule = ApplicationWorkAdmissionFixture()

    @Test(timeout = 30_000)
    fun cancelledLocalPublicationKeepsItsObservationAfterFreshOriginalSessionLoad() = scenario { env ->
        val original = env.agent.sessions.create("Original")
        env.agent.loadSession(original)

        val failure = runCatching {
            env.agent.chat("Replace note.txt with b", env.config, maxTurns = 2)
        }.exceptionOrNull()

        assertOriginatingFailure(env.stopped, failure)
        assertPublishedOnce(env)
        val stored = requireNotNull(SessionManager(env.root).get(original.id))
        val cancelled = stored.messages.mapNotNull { it.streamPart }.single { it.toolCallId == CALL_ID }
        assertEquals(AgentStreamPartState.CANCELLED, cancelled.state)
        val restored = newAgent(env.root, env.terminal)
        try {
            restored.loadSession(stored)
            val part = restored.messages.value.mapNotNull { it.streamPart }.single { it.toolCallId == CALL_ID }
            assertEquals(AgentStreamPartState.CANCELLED, part.state)
            assertEquals(stored.apiHistory, restored.boundedApiHistory())
            assertEquals(1, env.provider.requests.size)
            assertEquals(0, env.terminal.executionCalls.get())
            // Central RED: current persists only generic cancellation, losing the invocation-owned
            // local-publication observation already earned by this edit.
            assertTrue("Fresh original-session load must retain the cancelled invocation's local write observation: ${part.detail}",
                part.detail.contains("local write observed", ignoreCase = true))
            assertFalse("Cancellation evidence must not claim rollback", part.detail.contains("rolled back", ignoreCase = true))
        } finally { restored.shutdown() }
    }

    @Test(timeout = 30_000)
    fun replacementStateStaysExactWhileOriginalReloadRecoversTheLateLocalObservation() = scenario(hold = true) { env ->
        val original = env.agent.sessions.create("Original")
        env.agent.loadSession(original)
        val old = async(Dispatchers.Default) {
            runCatching { env.agent.chat("Replace note.txt with b", env.config, maxTurns = 2) }
        }
        try {
            env.published.await()
            assertEquals("b", env.root.resolve("note.txt").readText())
            val marker = buildJsonObject { put("role", "user"); put("content", "replacement sentinel") }
            val replacement = env.agent.sessions.create("Replacement").copy(
                messages = listOf(AgentMessage("user", "replacement sentinel")),
                apiHistory = listOf(marker), forkHistory = listOf(marker),
                forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION,
            )
            env.agent.sessions.save(replacement)
            env.agent.loadSession(replacement)
            val beforeMessages = env.agent.messages.value
            val beforeApi = env.agent.boundedApiHistory()
            val beforeStream = env.agent.streamState.value
            val replacementFile = env.root.resolve(".droide/sessions/${replacement.id}.json")
            val beforeBytes = replacementFile.readBytes()

            env.release.complete(Unit)
            assertOriginatingFailure(env.stopped, old.await().exceptionOrNull())

            assertPublishedOnce(env)
            assertEquals(replacement.id, env.agent.currentSessionId)
            assertEquals(beforeMessages, env.agent.messages.value)
            assertEquals(beforeApi, env.agent.boundedApiHistory())
            assertEquals(beforeStream, env.agent.streamState.value)
            assertArrayEquals(beforeBytes, replacementFile.readBytes())
            val stored = requireNotNull(SessionManager(env.root).get(original.id))
            val restored = newAgent(env.root, env.terminal)
            try {
                restored.loadSession(stored)
                assertEquals(original.id, restored.currentSessionId)
                assertEquals(stored.apiHistory, restored.boundedApiHistory())
                assertEquals(1, env.provider.requests.size)
                assertEquals(0, env.terminal.executionCalls.get())
                val observations = restored.messages.value.mapNotNull { it.streamPart }
                    .filter { it.detail.contains("local write observed", ignoreCase = true) }
                // Central RED variant: stale UI publication is correctly rejected today, but there is no durable
                // old-operation evidence for a deliberate reload.
                assertEquals("Original-session reload must project exactly one historical local write observation", 1, observations.size)
                assertEquals(AgentStreamPartState.CANCELLED, observations.single().state)
                assertTrue(observations.single().title.contains("note.txt"))
                assertEquals("Reload must not repeat the write", 1, env.beforeWrites.get())
            } finally { restored.shutdown() }
        } finally {
            env.release.complete(Unit)
            withContext(NonCancellable) { withTimeout(5_000) { old.cancelAndJoin() } }
        }
    }

    private data class Fixture(
        val root: File,
        val agent: AgentService,
        val terminal: NoProcessTerminal,
        val provider: ProviderStreamFixture,
        val config: AgentConfig,
        val stopped: CancellationException,
        val published: CompletableDeferred<Unit>,
        val release: CompletableDeferred<Unit>,
        val beforeWrites: AtomicInteger,
        val publishedWrites: AtomicInteger,
    )

    private fun scenario(hold: Boolean = false, block: suspend CoroutineScope.(Fixture) -> Unit) {
        val root = Files.createTempDirectory("droide-partial-outcome-baseline-").toFile()
        try {
            root.resolve("note.txt").writeText("a")
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, authRequired = false) { provider ->
                provider.secret = ""
                val config = provider.config.copy(apiKey = "")
                assertEquals("127.0.0.1", URI(config.baseUrl).host)
                assertEquals(ProviderNetworkScope.LOOPBACK_ONLY, provider.provider.networkScope)
                val stopped = CancellationException("fixture stopped after local publication")
                val published = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val beforeWrites = AtomicInteger()
                val publishedWrites = AtomicInteger()
                val files = FileRepository(root,
                    beforeMutation = { mutation ->
                        if (mutation is FileRepository.Mutation.Write) {
                            check(mutation.path == "note.txt")
                            beforeWrites.incrementAndGet()
                        }
                    },
                    onMutation = { mutation ->
                        if (mutation is FileRepository.Mutation.Write) {
                            check(mutation.path == "note.txt")
                            check(publishedWrites.incrementAndGet() == 1) { "Unexpected second publication" }
                            assertEquals("b", root.resolve("note.txt").readText())
                            if (hold) withContext(NonCancellable) { published.complete(Unit); release.await() }
                            throw stopped
                        }
                    },
                )
                val terminal = NoProcessTerminal()
                val agent = newAgent(root, terminal, files)
                provider.response = { call, _, streaming ->
                    check(call == 1 && streaming) { "Only one synthetic streaming provider request is permitted" }
                    val arguments = buildJsonObject { put("path", "note.txt"); put("content", "b") }
                    val tool = buildJsonObject {
                        put("index", 0); put("id", CALL_ID); put("type", "function")
                        put("function", buildJsonObject { put("name", "write_file"); put("arguments", arguments.toString()) })
                    }
                    provider.event("""{"choices":[{"index":0,"delta":{"tool_calls":[$tool]},"finish_reason":"tool_calls"}]}""") + "data: [DONE]\n\n"
                }
                try {
                    runBlocking { withTimeout(15_000) {
                        block(Fixture(root, agent, terminal, provider, config, stopped, published, release, beforeWrites, publishedWrites))
                    } }
                } finally {
                    release.complete(Unit)
                    agent.shutdown()
                    assertEquals("The fixture must never execute a process", 0, terminal.executionCalls.get())
                }
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private fun assertPublishedOnce(env: Fixture) {
        assertEquals("b", env.root.resolve("note.txt").readText())
        assertEquals(1, env.beforeWrites.get())
        assertEquals(1, env.publishedWrites.get())
        assertEquals("a", env.root.resolve(".droide/backups").listFiles()!!.single().readText())
        assertEquals(1, env.provider.requests.size)
        assertEquals(0, env.terminal.executionCalls.get())
    }

    private fun newAgent(root: File, terminal: NoProcessTerminal, files: FileRepository = FileRepository(root)) =
        AgentService(files, terminal, GitManager(root),
            perms = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), maxSubagentDepth = 0)

    private class NoProcessTerminal : ITerminalSession {
        override val output = MutableStateFlow("")
        val executionCalls = AtomicInteger()
        private fun forbidden(): Nothing {
            executionCalls.incrementAndGet()
            throw AssertionError("Process execution is forbidden in the partial-outcome baseline")
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

    companion object { private const val CALL_ID = "partial-outcome-write" }
}
