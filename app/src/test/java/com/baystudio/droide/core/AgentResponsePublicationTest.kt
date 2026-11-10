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
@Config(sdk = [34])
class AgentResponsePublicationTest {
    @get:org.junit.Rule
    val applicationAdmission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    @Test(timeout = 30_000)
    fun emptyStopShowsAnErrorWithoutInventingAnApiAssistant() = scenario { env ->
        env.provider.response = { _, _, _ -> textResponse(env.provider, "", "stop") }

        env.agent.chat("Explain the requested task", env.provider.config, maxTurns = 1)

        val saved = requireNotNull(env.agent.sessions.get(requireNotNull(env.agent.currentSessionId)))
        assertFalse(env.agent.busy.value)
        assertNull(env.agent.persistenceError.value)
        // The final lifecycle resets the live stream after persisting the transcript.
        assertEquals(AgentStreamState.Status.IDLE, env.agent.streamState.value.status)
        assertTrue(saved.messages.any { it.content.startsWith("Error:") && it.content.contains("without public answer text") })
        assertTrue(env.agent.messages.value.any { it.content.startsWith("Error:") })
        assertFalse(saved.messages.any { it.content == "(tidak ada respons)" })
        assertFalse(saved.apiHistory.any { it["role"] == JsonPrimitive("assistant") })
        assertFalse(env.agent.boundedApiHistory().any { it["role"] == JsonPrimitive("assistant") })
        assertUsage(env, requests = 1)
    }

    @Test(timeout = 30_000)
    fun outputLimitPreservesPublicPartialExactlyOnceAndRecordsUsageOnce() = scenario { env ->
        val partial = "First finding.\nSecond finding remains incomplete."
        env.provider.response = { _, _, _ -> textResponse(env.provider, partial, "length") }

        env.agent.chat("Explain the requested findings", env.provider.config, maxTurns = 1)

        val saved = requireNotNull(env.agent.sessions.get(requireNotNull(env.agent.currentSessionId)))
        val partialMessages = saved.messages.filter { it.streamPart?.kind == AgentStreamPartKind.TEXT }
        assertEquals(1, partialMessages.size)
        assertEquals(partial, partialMessages.single().streamPart!!.text)
        assertEquals(AgentStreamPartState.ERROR, partialMessages.single().streamPart!!.state)
        assertEquals(1, env.agent.messages.value.count { it.streamPart?.text == partial })
        val apiAssistants = saved.apiHistory.filter { it["role"] == JsonPrimitive("assistant") }
        assertEquals("Only the marked public partial belongs in API history", 1, apiAssistants.size)
        val apiText = apiAssistants.single().getValue("content").jsonPrimitive.content
        assertTrue(apiText.startsWith("[Partial response"))
        assertEquals(partial, apiText.substringAfter('\n'))
        assertEquals(1, env.agent.boundedApiHistory().count { it == apiAssistants.single() })
        assertFalse(saved.apiHistory.any { (it["content"] as? JsonPrimitive)?.contentOrNull?.startsWith("Error:") == true })
        assertFalse(saved.apiHistory.any { it.containsKey("tool_calls") })
        assertTrue(saved.messages.any { it.content.startsWith("Error:") && it.content.contains("output limit") })
        assertFalse(env.agent.busy.value)
        assertNull(env.agent.persistenceError.value)
        assertUsage(env, requests = 1)
    }

    @Test(timeout = 30_000)
    fun validToolOnlyTurnStillRunsTheToolAndContinuesToAFinalAnswer() = scenario { env ->
        env.agent.workDir.resolve("evidence.txt").writeText("fixture evidence remains readable")
        env.provider.response = { request, _, _ ->
            if (request == 1) toolResponse(env.provider)
            else textResponse(env.provider, "The evidence was read successfully.", "stop")
        }

        env.agent.chat("Read evidence.txt and report the result", env.provider.config, maxTurns = 2)

        val saved = requireNotNull(env.agent.sessions.get(requireNotNull(env.agent.currentSessionId)))
        val toolTurns = saved.apiHistory.filter { it.containsKey("tool_calls") }
        assertEquals(1, toolTurns.size)
        assertEquals(JsonPrimitive(""), toolTurns.single()["content"])
        assertEquals("read-evidence", toolTurns.single().getValue("tool_calls").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        val results = saved.apiHistory.filter { it["tool_call_id"] == JsonPrimitive("read-evidence") }
        assertEquals(1, results.size)
        assertTrue(results.single().getValue("content").jsonPrimitive.content.contains("fixture evidence remains readable"))
        assertEquals(saved.apiHistory, AgentHistory.requireCompleteGroups(saved.apiHistory).flatten())
        val nextMessages = env.provider.requests[1].getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(1, nextMessages.count { it["tool_call_id"] == JsonPrimitive("read-evidence") })
        assertEquals(1, nextMessages.count { it.containsKey("tool_calls") })
        assertTrue(saved.messages.any { it.content == "The evidence was read successfully." })
        assertFalse(saved.messages.any { it.content.startsWith("Error:") || it.content == "(tidak ada respons)" })
        assertEquals("fixture evidence remains readable", env.agent.workDir.resolve("evidence.txt").readText())
        assertFalse(env.agent.busy.value)
        assertNull(env.agent.persistenceError.value)
        assertUsage(env, requests = 2)
    }

    @Test(timeout = 30_000)
    fun obsoleteLengthResultCannotPublishPartialOrErrorIntoReplacementSession() =
        staleResult(content = "obsolete partial answer", finish = "length")

    @Test(timeout = 30_000)
    fun obsoleteEmptyResultCannotPublishAnErrorIntoReplacementSession() =
        staleResult(content = "", finish = "stop")

    private fun staleResult(content: String, finish: String) = scenario { env ->
        env.provider.response = { _, _, _ -> textResponse(env.provider, content, finish) }
        val gate = env.gate(AgentService.PublicationBoundary.PROVIDER_RESULT)
        val old = env.scope.launch(Dispatchers.Default) {
            env.agent.chat("Explain the obsolete task", env.provider.config, maxTurns = 1)
        }
        gate.reached.await()
        val replacement = env.agent.sessions.create("Replacement session")
        env.agent.loadSession(replacement)
        val before = snapshot(env.agent)
        val savedBefore = requireNotNull(env.agent.sessions.get(replacement.id))
        gate.release.complete(Unit)
        old.join()

        assertEquals(replacement.id, env.agent.currentSessionId)
        assertEquals(before, snapshot(env.agent))
        val savedAfter = requireNotNull(env.agent.sessions.get(replacement.id))
        assertEquals(savedBefore.messages, savedAfter.messages)
        assertEquals(savedBefore.apiHistory, savedAfter.apiHistory)
        assertFalse(savedAfter.messages.any { it.streamPart?.kind == AgentStreamPartKind.TEXT || it.content.startsWith("Error:") })
        assertFalse(savedAfter.apiHistory.any { it["role"] == JsonPrimitive("assistant") })
        // The returned provider request incurred usage before ownership was revoked.
        assertUsage(env, requests = 1)
    }

    private fun assertUsage(env: Environment, requests: Int) {
        assertEquals(requests, env.provider.requests.size)
        assertEquals(requests, env.agent.tokens.history.value.size)
        assertEquals(requests.toLong(), env.agent.tokens.usage.value.requestCount)
        assertEquals(7 * requests, env.agent.tokens.usage.value.prompt)
        assertEquals(3 * requests, env.agent.tokens.usage.value.completion)
        assertEquals(10 * requests, env.agent.tokens.usage.value.total)
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
