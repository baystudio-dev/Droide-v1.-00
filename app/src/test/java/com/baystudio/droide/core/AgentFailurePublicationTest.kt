package com.baystudio.droide.core

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentFailurePublicationTest {
    @Before fun openApplicationSession() {
        ApplicationWorkAdmission.initialize(org.robolectric.RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
    }

    @Test(timeout = 30_000)
    fun failedStreamingTurnPreservesPublicTextAndUsageExactlyOnce() = scenario { env ->
        env.provider.response = { _, _, _ ->
            env.provider.event("""{"choices":[{"index":0,"delta":{"content":"Public partial"}}],"usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}""") + env.provider.errorEvent()
        }
        env.agent.chat("Inspect the fixture",env.provider.config,maxTurns=1)
        assertPartial(env,"Public partial")
        assertUsage(env,1)
    }
    @Test(timeout = 30_000)
    fun failedBufferedTurnPreservesRedactedPublicTextWithoutPrivateFields() = scenario { env ->
        env.provider.forceBufferedResponse=true
        env.provider.response={_,_,_->"""{"error":{"code":503,"message":"fixture error"},"usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10},"choices":[{"message":{"role":"assistant","content":"Buffered partial","reasoning_content":"PRIVATE REASONING","tool_calls":[{"id":"never-execute"}]}}]}"""}
        env.agent.chat("Inspect buffered fixture",env.provider.config,maxTurns=1)
        assertPartial(env,"Buffered partial")
        val saved=requireNotNull(env.agent.sessions.get(requireNotNull(env.agent.currentSessionId)))
        assertFalse(saved.apiHistory.toString().contains("PRIVATE REASONING"))
        assertFalse(saved.apiHistory.any{it.containsKey("tool_calls")})
        assertUsage(env,1)
    }
    @Test(timeout = 30_000)
    fun failedStreamContinuesSameSessionWithPartialTextButWithoutErrorAsModelAnswer() = scenario { env ->
        env.provider.response = { call, _, _ ->
            if (call == 1) env.provider.event("""{"choices":[{"index":0,"delta":{"content":"Public partial"}}],"usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}""") + env.provider.errorEvent()
            else textResponse(env.provider, "Recovered answer", "stop")
        }
        env.agent.chat("Explain the fixture", env.provider.config, maxTurns = 1)
        assertPartial(env, "Public partial")
        val session = requireNotNull(env.agent.currentSessionId)
        assertFalse(env.agent.busy.value)
        assertEquals(1, env.provider.requests.size) // No replay on an incomplete generation.

        env.agent.chat("Continue the explanation", env.provider.config, maxTurns = 1)
        assertEquals(session, env.agent.currentSessionId)
        assertFalse(env.agent.busy.value)
        assertUsage(env, 2)
        val sent = env.provider.requests.last().getValue("messages").jsonArray.map { it.jsonObject }
        val assistants = sent.filter { it["role"] == JsonPrimitive("assistant") }
        assertEquals(1, assistants.size)
        assertTrue(assistants.single().getValue("content").jsonPrimitive.content.contains("Public partial"))
        assertFalse(assistants.any { it.getValue("content").jsonPrimitive.content.startsWith("Error:") })
        assertFalse(sent.any { it.containsKey("tool_calls") })
        val saved = requireNotNull(env.agent.sessions.get(session))
        assertTrue(saved.apiHistory.any { it["content"] == JsonPrimitive("Recovered answer") })
        assertTrue(saved.messages.any { it.content.startsWith("Error:") })
    }
    @Test(timeout = 30_000)
    fun blankSafeSnapshotClearsPreviouslyEmittedUnsafePreview() = scenario { env ->
        env.provider.response={_,_,_->env.provider.event("""{"choices":[{"delta":{"content":"unsafe-prefix"}}]}""")+env.provider.event("""{"choices":[{"delta":{"content":"second"}}]}""")}
        var events=0
        env.agent.publicationBoundaryProbe={point->
            if(point==AgentService.PublicationBoundary.PROVIDER_EVENT && ++events==2) throw clearedFailure()
        }
        env.agent.chat("Inspect capacity fixture",env.provider.config,maxTurns=1)
        val saved=requireNotNull(env.agent.sessions.get(requireNotNull(env.agent.currentSessionId)))
        assertFalse(saved.messages.any{it.streamPart?.kind==AgentStreamPartKind.TEXT})
        assertFalse(saved.apiHistory.any{it["role"]==JsonPrimitive("assistant")})
        assertFalse(saved.messages.toString().contains("unsafe-prefix"))
        assertEquals(1L,env.agent.tokens.usage.value.requestCount)
        assertEquals(UsageAuthority.UNKNOWN,env.agent.tokens.usage.value.tokenAuthority)
    }
    @Test(timeout = 30_000)
    fun revokedOwnerCannotPublishFailureSnapshotButReportedWorkIsAccounted() = scenario { env ->
        env.provider.response={_,_,_->env.provider.event("""{"choices":[{"delta":{"content":"obsolete"}}]}""")}
        val reached=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        env.agent.publicationBoundaryProbe={point->if(point==AgentService.PublicationBoundary.PROVIDER_EVENT){reached.complete(Unit);withContext(NonCancellable){release.await()};throw clearedFailure()}}
        val old=env.scope.launch(Dispatchers.Default){env.agent.chat("Old task",env.provider.config,maxTurns=1)}
        try {
            reached.await();val replacement=env.agent.sessions.create("Replacement");env.agent.loadSession(replacement)
            val before=snapshot(env.agent);release.complete(Unit);old.join()
            assertEquals(before,snapshot(env.agent));assertEquals(1L,env.agent.tokens.usage.value.requestCount)
        } finally {release.complete(Unit)}
    }
    private fun clearedFailure()=ProviderStreamFailure.fromUnsafeMessage("capacity fixture",emptyList(),200,
        ProviderFailureSnapshot("",TokenTracker.parseProviderUsage("fixture","{}")))
    private suspend fun assertPartial(env:Environment,text:String) {
        val saved=requireNotNull(env.agent.sessions.get(requireNotNull(env.agent.currentSessionId)))
        assertEquals(listOf(text),saved.messages.mapNotNull{it.streamPart?.takeIf{part->part.kind==AgentStreamPartKind.TEXT}?.text})
        val api=saved.apiHistory.filter{it["role"]==JsonPrimitive("assistant")}
        assertEquals(1,api.size);assertEquals(text,api.single().getValue("content").jsonPrimitive.content.substringAfter('\n'))
        assertFalse(saved.apiHistory.any{it.containsKey("tool_calls")})
        assertTrue(saved.messages.any{it.content.startsWith("Error:")})
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
        val root = Files.createTempDirectory("droide-r56-response-publication-").toFile()
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
