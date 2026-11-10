package com.baystudio.droide.core

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], manifest = Config.NONE)
class ArchivedAgentResponsePublicationTest {
    @get:org.junit.Rule
    val applicationAdmission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    @Test(timeout = 30_000)
    fun largeCompletedAnswerPublishesExactArchiveAndTypedContinuation() = scenario { env ->
        val original = " ".repeat(20_000) + "Verified response 🌏\n".repeat(10_000)
        env.provider.response = { _, _, _ -> textResponse(env.provider, original, "stop") }
        env.agent.chat("Explain this task", env.provider.config, maxTurns = 1)
        val sessionId = requireNotNull(env.agent.currentSessionId)
        val saved = requireNotNull(env.agent.sessions.get(sessionId))
        assertNull(env.agent.persistenceError.value)
        assertTrue(saved.messages.toString(), saved.messages.any { it.publicContent != null })
        val message = saved.messages.single { it.publicContent != null }
        val output = java.io.ByteArrayOutputStream()
        env.agent.sessions.publicContentArchive(sessionId).export(requireNotNull(message.publicContent), output)
        assertArrayEquals(original.toByteArray(Charsets.UTF_8), output.toByteArray())
        assertTrue(message.content.length < 100_000)
        assertTrue("Legacy projection must not be authoritative after typed activation", saved.apiHistory.isEmpty())
        val checkpoint = checkNotNull(saved.typedChat)
        val resources = env.agent.sessions.typedChatResources()
        val items = resources.history.validateCompleteRequest(checkpoint, sessionId, checkpoint.target.runtime())
        val replay = java.io.ByteArrayOutputStream()
        SourceAwareChatHistoryWriter.writeMessages(items, checkpoint.target.runtime(), replay)
        val wire = replay.toString("UTF-8")
        val api = Json.parseToJsonElement(wire).jsonArray.last().jsonObject
        assertEquals(original, api.getValue("content").jsonPrimitive.content)
        assertFalse(wire.contains(requireNotNull(message.publicContent).opaqueHandle))
    }

    private fun textResponse(provider: ProviderStreamFixture, text: String, finish: String): String =
        response(provider, buildJsonObject { put("content", text) }, finish)

    private fun toolResponse(provider: ProviderStreamFixture): String = response(provider, buildJsonObject {
        put("tool_calls", buildJsonArray { add(buildJsonObject {
            put("index", 0); put("id", "read-evidence"); put("type", "function")
            put("function", buildJsonObject {
                put("name", "read_file")
                put("arguments", buildJsonObject { put("path", "evidence.txt") }.toString())
            })
        }) })
    }, "tool_calls")

    private fun response(provider: ProviderStreamFixture, delta: JsonObject, finish: String): String {
        val payload = buildJsonObject {
            put("choices", buildJsonArray { add(buildJsonObject {
                put("index", 0); put("delta", delta); put("finish_reason", finish)
            }) })
            put("usage", buildJsonObject { put("prompt_tokens", 7); put("completion_tokens", 3); put("total_tokens", 10) })
        }
        return provider.event(payload.toString()) + "data: [DONE]\n\n"
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
            agent.publicationBoundaryProbe = { observed ->
                if (observed == point && gate.reached.complete(Unit)) gate.release.await()
            }
        }
    }

    private fun scenario(body: suspend (Environment) -> Unit) {
        val root = Files.createTempDirectory("droide-r54-response-publication-").toFile()
        try {
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, authRequired = false) { provider ->
                provider.secret = ""
                val terminal = NoProcessTerminal()
                val agent = AgentService(FileRepository(root), terminal, GitManager(root),
                    perms = PermissionEngine(listOf(PermRule("read", "*", PermEffect.ALLOW))), maxSubagentDepth = 0)
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
            throw AssertionError("Process execution is forbidden in the response publication tests")
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
