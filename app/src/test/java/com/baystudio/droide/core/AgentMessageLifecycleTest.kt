package com.baystudio.droide.core

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Authored actual Agent + loopback + durable-session tests. No real process execution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentMessageLifecycleTest {
    @get:org.junit.Rule
    val applicationAdmission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    @Test(timeout=30_000) fun toolUpdateAndFinalAnswerSurviveRestoreWithoutApiPresentationFields() = scenario { env ->
        env.provider.response={call,_,_->if(call==1)tool(env.provider) else text(env.provider,"Finished")}
        env.agent.chat("Read the evidence and explain",env.provider.config,maxTurns=3)
        val saved=env.agent.sessions.get(requireNotNull(env.agent.currentSessionId))!!
        val typed=saved.messages.filter{it.presentationId.isNotBlank()}
        assertEquals(listOf(AgentTextPresentation.UPDATE,AgentTextPresentation.ANSWER),typed.map{it.textPresentation})
        assertEquals(listOf("Reading evidence", "Finished"),typed.map{it.content})
        assertEquals(2,typed.map{it.presentationId}.distinct().size)
        assertFalse(saved.apiHistory.toString().contains("textPresentation"));assertFalse(saved.apiHistory.toString().contains("presentationId"))
        env.agent.loadSession(saved)
        assertEquals(typed,env.agent.messages.value.filter{it.presentationId.isNotBlank()})
    }
    @Test(timeout=30_000) fun stopHookContinuationCannotBecomePrematureAnswer() = scenario(stopOnce=true) { env ->
        env.provider.response={call,_,_->text(env.provider,if(call==1)"Intermediate" else "Finished")}
        env.agent.chat("Inspect the fixture",env.provider.config,maxTurns=3)
        assertEquals(listOf(AgentTextPresentation.UPDATE,AgentTextPresentation.ANSWER),env.agent.messages.value.filter{it.presentationId.isNotBlank()}.map{it.textPresentation})
        assertEquals(2,env.provider.requests.size)
    }
    @Test(timeout=30_000) fun queuedUserPreservesPreviousTaskAnswer() = promotion(AgentInputOrigin.USER,AgentTextPresentation.ANSWER)
    @Test(timeout=30_000) fun syntheticSubagentContinuesPriorTaskAsUpdate() = promotion(AgentInputOrigin.SUBAGENT,AgentTextPresentation.UPDATE)
    private fun promotion(origin:AgentInputOrigin, expected:AgentTextPresentation) = scenario { env ->
        env.provider.response={call,_,_->text(env.provider,if(call==1)"First result" else "Next result")}
        val gate=env.gate(AgentService.PublicationBoundary.ASSISTANT_HISTORY_APPEND)
        val running=env.scope.launch(Dispatchers.Default){env.agent.chat("Inspect fixture",env.provider.config,maxTurns=3)}
        try {
            gate.reached.await()
            assertEquals(AgentTextPresentation.RESPONSE,env.agent.messages.value.last{it.presentationId.isNotBlank()}.textPresentation)
            env.agent.submitAsync(env.scope,"Next instruction",env.provider.config,AgentPromptDelivery.QUEUE,maxTurns=3,
                inputOrigin=origin,sourceId=if(origin==AgentInputOrigin.SUBAGENT)"fixture-child" else null)?.join()
            env.agent.pendingInputs.first{it.any{input->input.text=="Next instruction"}}
            gate.release.complete(Unit);running.join()
            val typed=env.agent.messages.value.filter{it.presentationId.isNotBlank()}
            assertEquals(listOf(expected,AgentTextPresentation.ANSWER),typed.map{it.textPresentation})
        } finally {gate.release.complete(Unit)}
    }
    @Test(timeout=30_000) fun failedPublicTextRemainsVisiblePartialNotAnswer() = scenario { env ->
        env.provider.response={_,_,_->env.provider.event("""{"choices":[{"delta":{"content":"Partial"}}]}""")+env.provider.errorEvent()}
        env.agent.chat("Inspect fixture",env.provider.config,maxTurns=1)
        val part=env.agent.messages.value.mapNotNull{it.streamPart}.single{it.kind==AgentStreamPartKind.TEXT}
        assertEquals("Partial",part.text);assertEquals(AgentStreamPartState.ERROR,part.state)
        assertEquals(AgentTextPresentation.RESPONSE,part.textPresentation);assertTrue(part.publicTextSpans.isEmpty())
        assertTrue(env.agent.messages.value.any{it.content.startsWith("Error:")})
    }
    private fun text(p:ProviderStreamFixture,value:String)=p.event(buildJsonObject {
        put("choices",buildJsonArray{add(buildJsonObject{put("delta",buildJsonObject{put("content",value)});put("finish_reason","stop")})})
    }.toString())+"data: [DONE]\n\n"
    private fun tool(p:ProviderStreamFixture)=p.event(buildJsonObject {
        put("choices",buildJsonArray{add(buildJsonObject {
            put("delta",buildJsonObject {
                put("content","Reading evidence")
                put("tool_calls",buildJsonArray{add(buildJsonObject{put("index",0);put("id","read-evidence");put("type","function");put("function",buildJsonObject{put("name","read_file");put("arguments","""{"path":"evidence.txt"}""")})})})
            });put("finish_reason","tool_calls")
        })})
    }.toString())+"data: [DONE]\n\n"

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

    private fun scenario(stopOnce: Boolean = false, body: suspend (Environment) -> Unit) {
        val root = Files.createTempDirectory("droide-r58-public-message-").toFile()
        try {
            root.resolve("evidence.txt").writeText("fixture evidence")
            if (stopOnce) {
                root.resolve(".droide").mkdirs()
                root.resolve(".droide/hooks.json").writeText("""{"enabled":true,"events":{"Stop":[{"command":"owned-hook-fixture {event_file}"}]}}""")
            }
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, authRequired = false) { provider ->
                provider.secret = ""
                val terminal = NoProcessTerminal(root, stopOnce)
                val agent = AgentService(FileRepository(root), terminal, GitManager(root),
                    perms = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), maxSubagentDepth = 0)
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

    private class NoProcessTerminal(val root: java.io.File, val stopOnce: Boolean) : ITerminalSession {
        private val stopCalls = AtomicInteger()
        override val output = MutableStateFlow("")
        val executionCalls = AtomicInteger()
        private fun forbidden(): Nothing {
            executionCalls.incrementAndGet()
            throw AssertionError("Process execution is forbidden in the response publication tests")
        }
        override fun start() = forbidden()
        override fun send(cmd: String) = forbidden()
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult {
            if (!stopOnce) forbidden()
            val match = Regex("^owned-hook-fixture '([^']+)'$").matchEntire(cmd) ?: forbidden()
            val file = java.io.File(match.groupValues[1])
            if (file.parentFile != root.resolve(".droide/.runtime-hooks") || file.length() !in 1L..8_192L ||
                !Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) forbidden()
            val event = Json.parseToJsonElement(file.readText()).jsonObject
            if (event["event"] != JsonPrimitive("Stop")) forbidden()
            return ExecResult(0, if(stopCalls.incrementAndGet()==1) """{"decision":"block","reason":"fixture continuation"}""" else "{}", false)
        }
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult = forbidden()
        override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult = forbidden()
        override suspend fun execStreamingWithInput(cmd: String, timeoutMs: Long, input: ProcessInputBuffer,
            onOutput: (String) -> Unit): ExecResult = forbidden()
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun destroy() = Unit
    }
}
