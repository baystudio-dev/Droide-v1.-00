package com.baystudio.droide.core

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Existing AgentService API; local synthetic HTTP responses and a fake terminal only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentToolPublicationOwnershipBaselineTest {
    @get:org.junit.Rule
    val applicationAdmission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    @Test(timeout = 30_000)
    fun oldToolCancellationCannotOverwriteReplacementRunWithTheSamePartId() {
        val root = Files.createTempDirectory("droide-tool-publication-owner-").toFile()
        try {
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, authRequired = false) { fixture ->
                val oldEntered = CompletableDeferred<Unit>()
                val releaseOld = CompletableDeferred<Unit>()
                val files = FileRepository(root, onMutation = { mutation ->
                    if (mutation is FileRepository.Mutation.Write) {
                        when (mutation.path) {
                            "old.txt" -> withContext(NonCancellable) { oldEntered.complete(Unit); releaseOld.await() }
                        }
                    }
                })
                val terminal = NoProcessTerminal()
                val agent = AgentService(files, terminal, GitManager(root),
                    perms = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), maxSubagentDepth = 0)
                fixture.response = { call, _, _ -> when (call) {
                    1 -> writeResponse(fixture, "old.txt", "old write")
                    2 -> questionResponse(fixture)
                    else -> fixture.event("""{"choices":[{"index":0,"delta":{"content":"Done"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n"
                } }
                try {
                    runBlocking {
                        withTimeout(15_000) {
                            val oldRun = launch(Dispatchers.Default) { agent.chat("Create old.txt", fixture.config, maxTurns = 2) }
                            var newRun: kotlinx.coroutines.Job? = null
                            try {
                                oldEntered.await()
                                val oldSession = agent.currentSessionId
                                val replacement = agent.sessions.create(title = "Replacement")
                                agent.loadSession(replacement)
                                assertNotEquals(oldSession, agent.currentSessionId)
                                newRun = launch(Dispatchers.Default) { agent.chat("Ask me which option to choose", fixture.config, maxTurns = 2) }
                                // A question suspends without acquiring the root mutation mutex still held by the old write. Its
                                // part reuses tool:1:0.
                                val pendingQuestion = agent.questions.pending.first {
                                    it?.provenance?.sessionId == replacement.id
                                }
                                assertNotNull(pendingQuestion)
                                val before = agent.streamState.value
                                val replacementPart = before.parts.single { it.kind == AgentStreamPartKind.TOOL }
                                assertEquals(AgentStreamPartState.RUNNING, replacementPart.state)
                                assertEquals("question", replacementPart.toolName)
                                assertEquals("tool:1:0", replacementPart.id)
                                assertEquals(replacement.id, before.sessionId)
                                releaseOld.complete(Unit)
                                oldRun.join()
                                val after = agent.streamState.value
                                assertEquals(before.runId, after.runId)
                                assertEquals(before.sessionId, after.sessionId)
                                assertEquals("A late old tool result must not change the replacement run's reused part ID",
                                    replacementPart, after.parts.single { it.id == replacementPart.id })
                                assertEquals("old write", root.resolve("old.txt").readText())
                                assertEquals("This regression must not execute any terminal process", 0, terminal.executionCalls.get())
                            } finally {
                                releaseOld.complete(Unit)
                                // Cancellation clears the question continuation. Bound cleanup even if the enclosing assertion
                                // or timeout already failed.
                                withContext(NonCancellable) {
                                    withTimeout(5_000) {
                                        oldRun.cancelAndJoin()
                                        newRun?.cancelAndJoin()
                                    }
                                }
                            }
                        }
                    }
                } finally { releaseOld.complete(Unit); agent.shutdown() }
                assertEquals(0, terminal.executionCalls.get())
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private class NoProcessTerminal : ITerminalSession {
        override val output = MutableStateFlow("")
        val executionCalls = AtomicInteger()
        private fun forbidden(): Nothing {
            executionCalls.incrementAndGet()
            throw AssertionError("Process execution is forbidden in this synthetic ownership regression")
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

    private fun questionResponse(fixture: ProviderStreamFixture): String {
        val calls = buildJsonArray { add(buildJsonObject {
            put("index", 0); put("id", "replacement-question"); put("type", "function")
            put("function", buildJsonObject {
                put("name", "question")
                put("arguments", buildJsonObject {
                    put("header", "Ownership test")
                    put("question", "Which option should the replacement run use?")
                    put("options", buildJsonArray { add(JsonPrimitive("First")); add(JsonPrimitive("Second")) })
                }.toString())
            })
        }) }
        return fixture.event("""{"choices":[{"index":0,"delta":{"tool_calls":$calls},"finish_reason":"tool_calls"}]}""") + "data: [DONE]\n\n"
    }

    private fun writeResponse(fixture: ProviderStreamFixture, path: String, content: String): String {
        val calls = buildJsonArray { add(buildJsonObject {
            put("index", 0); put("id", "write-$path"); put("type", "function")
            put("function", buildJsonObject {
                put("name", "write_file")
                put("arguments", buildJsonObject { put("path", path); put("content", content) }.toString())
            })
        }) }
        return fixture.event("""{"choices":[{"index":0,"delta":{"tool_calls":$calls},"finish_reason":"tool_calls"}]}""") + "data: [DONE]\n\n"
    }
}
