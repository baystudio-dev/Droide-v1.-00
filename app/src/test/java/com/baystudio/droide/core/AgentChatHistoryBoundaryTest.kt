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
@Config(sdk = [35])
class AgentChatHistoryBoundaryTest {
    @get:org.junit.Rule
    val applicationAdmission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    @Test(timeout=30_000) fun retainedCountBoundaryDoesNotRenderSavedAndLiveToolTurnTwice() = scenario { env ->
        val session = env.agent.sessions.create("Long fixture")
        env.agent.loadSession(session.copy(messages = (1..500).map { AgentMessage("assistant", "Previous $it") }))
        env.provider.response = { call, _, _ -> if (call == 1) tool(env.provider) else text(env.provider, "Finished") }
        val gate = env.gate(AgentService.PublicationBoundary.ASSISTANT_HISTORY_APPEND)
        val running = env.scope.launch(Dispatchers.Default) { env.agent.chat("Read evidence", env.provider.config, maxTurns = 3) }
        try {
            gate.reached.await()
            val stream = env.agent.streamState.value
            val messages = env.agent.messages.value
            assertEquals(500, messages.size)
            val frozen = messages.take(stream.baseMessageCount)
            assertFalse("Saved update must not also appear above the live run", frozen.any { it.content == "Reading evidence" })
            assertFalse("Saved tool must not also appear above the live run", frozen.any { it.streamPart?.toolCallId == "read-evidence" })
            assertFalse(frozen.any { it.content == "Finished" })
            assertTrue(frozen.any { it.role == "user" && it.content == "Read evidence" })
            assertEquals(1, stream.parts.count { it.text == "Reading evidence" })
            assertEquals(1, stream.parts.count { it.toolCallId == "read-evidence" })
            assertEquals(1, stream.parts.count { it.text == "Finished" })
            gate.release.complete(Unit); running.join()
            assertFalse(env.agent.busy.value)
            assertTrue(env.agent.streamState.value.parts.isEmpty())
            assertEquals(1, env.agent.messages.value.count { it.content == "Reading evidence" })
            assertEquals(1, env.agent.messages.value.count { it.streamPart?.toolCallId == "read-evidence" })
            val saved = env.agent.sessions.get(requireNotNull(env.agent.currentSessionId))!!
            env.agent.loadSession(saved)
            assertEquals(1, env.agent.messages.value.count { it.content == "Reading evidence" })
            assertEquals(1, env.agent.messages.value.count { it.streamPart?.toolCallId == "read-evidence" })
        } finally { gate.release.complete(Unit) }
    }

    @Test(timeout=30_000) fun characterLimitRebasesThePrefixBeforeLiveAnswerIsPublished() = scenario { env ->
        val session = env.agent.sessions.create("Large fixture")
        env.agent.loadSession(session.copy(messages = listOf(
            AgentMessage("assistant", "a".repeat(490_000)), AgentMessage("assistant", "b".repeat(490_000)),
            AgentMessage("assistant", "c".repeat(19_000)))))
        val answer = "new".repeat(1_000)
        env.provider.response = { _, _, _ -> text(env.provider, answer) }
        val gate = env.gate(AgentService.PublicationBoundary.ASSISTANT_HISTORY_APPEND)
        val running = env.scope.launch(Dispatchers.Default) { env.agent.chat("Continue", env.provider.config, maxTurns = 1) }
        try {
            gate.reached.await()
            val messages = env.agent.messages.value
            val base = env.agent.streamState.value.baseMessageCount
            assertTrue(base in 0..messages.size)
            assertEquals(3, base)
            assertFalse(messages.take(base).any { it.content == answer })
            assertEquals(1, env.agent.streamState.value.parts.count { it.text == answer })
            gate.release.complete(Unit); running.join()
            assertEquals(1, env.agent.messages.value.count { it.content == answer })
        } finally { gate.release.complete(Unit) }
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
