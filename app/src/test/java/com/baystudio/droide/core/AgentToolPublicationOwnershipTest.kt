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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tiny owner/Stop scenarios: loopback fixture replies, local bytes, and a fail-closed terminal. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentToolPublicationOwnershipTest {
    @org.junit.Before fun openApplicationSession() {
        ApplicationWorkAdmission.initialize(org.robolectric.RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
    }

    @Test(timeout = 30_000)
    fun oldToolCancellationCannotChangeANewerRunInTheSameSession() = scenario { env ->
        val oldRun = env.scope.launch { env.agent.chat("Create old.txt", env.provider.config, maxTurns = 2) }
        env.oldEntered.await()
        val sessionId = requireNotNull(env.agent.currentSessionId)
        val oldGeneration = env.agent.streamState.value.runId
        val sameSession = requireNotNull(env.agent.sessions.get(sessionId))
        env.agent.loadSession(sameSession)
        assertEquals(sessionId, env.agent.currentSessionId)
        env.scope.launch { env.agent.chat("Ask me which option to choose", env.provider.config, maxTurns = 2) }
        env.agent.questions.pending.first { it?.provenance?.sessionId == sessionId }
        val before = env.agent.streamState.value
        val part = before.parts.single { it.kind == AgentStreamPartKind.TOOL }
        assertTrue(before.runId > oldGeneration)
        assertEquals(sessionId, before.sessionId)
        assertEquals("tool:1:0", part.id)
        assertEquals("question", part.toolName)
        assertEquals(AgentStreamPartState.RUNNING, part.state)

        env.releaseOld.complete(Unit)
        oldRun.join()

        val after = env.agent.streamState.value
        assertEquals(before.runId, after.runId)
        assertEquals(before.sessionId, after.sessionId)
        assertEquals(part, after.parts.single { it.id == part.id })
        assertEquals("old write", env.root.resolve("old.txt").readText())
        assertEquals(0, env.terminal.executionCalls.get())
    }

    @Test(timeout = 30_000)
    fun clearingAConversationRejectsLateToolCancellationWithoutRecreatingState() = scenario { env ->
        val oldRun = env.scope.launch { env.agent.chat("Create old.txt", env.provider.config, maxTurns = 2) }
        env.oldEntered.await()
        assertNotNull(env.agent.currentSessionId)
        env.agent.clear()
        val cleared = env.agent.streamState.value
        assertEquals(AgentStreamState.Status.IDLE, cleared.status)
        assertTrue(cleared.parts.isEmpty())

        env.releaseOld.complete(Unit)
        oldRun.join()

        assertNull(env.agent.currentSessionId)
        assertEquals(cleared, env.agent.streamState.value)
        assertTrue(env.agent.messages.value.isEmpty())
        assertFalse(env.agent.busy.value)
        assertEquals("old write", env.root.resolve("old.txt").readText())
        assertEquals(0, env.terminal.executionCalls.get())
    }

    @Test(timeout = 30_000)
    fun stopKeepsCurrentQuestionOwnerAndPersistsItsCancellation() = scenario { env ->
        env.provider.response = { _, _, _ -> questionResponse(env.provider) }
        assertNotNull(env.agent.submitAsync(env.scope, "Ask me which option to choose", env.provider.config, maxTurns = 2))
        val question = requireNotNull(env.agent.questions.pending.first { it != null })
        val owner = env.agent.streamState.value
        assertEquals(question.provenance.sessionId, owner.sessionId)
        assertEquals(AgentStreamPartState.RUNNING, owner.parts.single { it.toolName == "question" }.state)
        assertTrue(env.agent.busy.value)

        env.agent.cancel()
        env.agent.busy.first { !it }

        assertNull(env.agent.questions.pending.value)
        assertEquals(owner.sessionId, env.agent.currentSessionId)
        assertEquals(owner.runId, env.agent.streamState.value.runId)
        assertNull(env.agent.persistenceError.value)
        val saved = requireNotNull(env.agent.sessions.get(owner.sessionId))
        val cancelled = saved.messages.mapNotNull { it.streamPart }.single { it.toolCallId == "replacement-question" }
        assertEquals(AgentStreamPartState.CANCELLED, cancelled.state)
        assertTrue(saved.messages.any { it.content == "Stopped by user." })
        assertEquals(1, saved.apiHistory.count { it["tool_call_id"] == JsonPrimitive("replacement-question") })
        assertEquals(0, env.terminal.executionCalls.get())
    }

    @Test(timeout = 30_000)
    fun stopAfterCurrentWritePublicationKeepsItsBytesUndoAndCancellation() = scenario { env ->
        env.root.resolve("old.txt").writeText("reviewed original")
        assertNotNull(env.agent.submitAsync(env.scope, "Replace old.txt", env.provider.config, maxTurns = 2))
        env.oldEntered.await()
        val owner = env.agent.streamState.value
        assertEquals(AgentStreamPartState.RUNNING, owner.parts.single { it.toolName == "write_file" }.state)
        assertEquals("old write", env.root.resolve("old.txt").readText())

        env.agent.cancel()
        env.releaseOld.complete(Unit)
        env.agent.busy.first { !it }

        assertEquals(owner.sessionId, env.agent.currentSessionId)
        assertEquals(owner.runId, env.agent.streamState.value.runId)
        assertEquals("old write", env.root.resolve("old.txt").readText())
        assertNull(env.agent.persistenceError.value)
        val saved = requireNotNull(env.agent.sessions.get(owner.sessionId))
        val cancelled = saved.messages.mapNotNull { it.streamPart }.single { it.toolCallId == "write-old.txt" }
        assertEquals(AgentStreamPartState.CANCELLED, cancelled.state)
        assertTrue(saved.messages.any { it.content == "Stopped by user." })
        assertEquals(1, saved.apiHistory.count { it["tool_call_id"] == JsonPrimitive("write-old.txt") })
        assertTrue(env.agent.undoLast().startsWith("Undone:"))
        assertEquals("reviewed original", env.root.resolve("old.txt").readText())
        assertEquals(0, env.terminal.executionCalls.get())
    }

    private data class Scenario(
        val root: File,
        val agent: AgentService,
        val provider: ProviderStreamFixture,
        val terminal: NoProcessTerminal,
        val scope: CoroutineScope,
        val oldEntered: CompletableDeferred<Unit>,
        val releaseOld: CompletableDeferred<Unit>,
    )

    private fun scenario(block: suspend (Scenario) -> Unit) {
        val root = Files.createTempDirectory("droide-tool-owner-variant-").toFile()
        try {
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, authRequired = false) { provider ->
                provider.secret = ""
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val terminal = NoProcessTerminal()
                val escaped = CopyOnWriteArrayList<Throwable>()
                val owner = SupervisorJob()
                val scope = CoroutineScope(owner + Dispatchers.Default + CoroutineExceptionHandler { _, failure -> escaped += failure })
                val files = FileRepository(root, onMutation = { mutation ->
                    if (mutation is FileRepository.Mutation.Write && mutation.path == "old.txt") {
                        withContext(NonCancellable) { entered.complete(Unit); release.await() }
                    }
                })
                val agent = AgentService(files, terminal, GitManager(root),
                    perms = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), maxSubagentDepth = 0)
                provider.response = { call, _, _ -> when (call) {
                    1 -> toolResponse(provider, "write-old.txt", "write_file", buildJsonObject {
                        put("path", "old.txt"); put("content", "old write")
                    })
                    2 -> questionResponse(provider)
                    else -> provider.event("""{"choices":[{"index":0,"delta":{"content":"Done"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n"
                } }
                try {
                    runBlocking { withTimeout(15_000) {
                        block(Scenario(root, agent, provider, terminal, scope, entered, release))
                    } }
                    assertTrue("No exception may escape the fixture: $escaped", escaped.isEmpty())
                    assertEquals("No terminal process may execute", 0, terminal.executionCalls.get())
                } finally {
                    release.complete(Unit)
                    agent.shutdown()
                    runBlocking { withTimeout(5_000) { owner.cancelAndJoin() } }
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
            throw AssertionError("Process execution is forbidden in this ownership regression")
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

    private fun questionResponse(provider: ProviderStreamFixture) = toolResponse(
        provider, "replacement-question", "question", buildJsonObject {
            put("header", "Ownership test")
            put("question", "Which option should this run use?")
            put("options", buildJsonArray { add(JsonPrimitive("First")); add(JsonPrimitive("Second")) })
        },
    )

    private fun toolResponse(provider: ProviderStreamFixture, id: String, name: String, args: JsonObject): String {
        val calls = buildJsonArray { add(buildJsonObject {
            put("index", 0); put("id", id); put("type", "function")
            put("function", buildJsonObject { put("name", name); put("arguments", args.toString()) })
        }) }
        return provider.event("""{"choices":[{"index":0,"delta":{"tool_calls":$calls},"finish_reason":"tool_calls"}]}""") + "data: [DONE]\n\n"
    }
}
