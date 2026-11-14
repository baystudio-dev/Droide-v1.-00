package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Proposed R29 baseline only. Existing loopback HTTP fixture and fail-closed terminal. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class R29MalformedToolHistoryBaselineTest {
    @get:org.junit.Rule
    val applicationAdmission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    @Test(timeout = 15_000)
    fun duplicateBufferedToolIdsCannotPoisonConversationPersistence() = checkMalformed(buffered = true, missingId = false)

    @Test(timeout = 15_000)
    fun missingBufferedToolIdCannotPoisonConversationPersistence() = checkMalformed(buffered = true, missingId = true)

    @Test(timeout = 15_000)
    fun duplicateStreamedToolIdsCannotPoisonConversationPersistence() = checkMalformed(buffered = false, missingId = false)

    private fun checkMalformed(buffered: Boolean, missingId: Boolean) {
        val root = Files.createTempDirectory("droide-r29-malformed-tool-").toFile()
        try {
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, authRequired = false) { fixture ->
                fixture.secret = ""
                fixture.forceBufferedResponse = buffered
                val terminal = NoProcessTerminal()
                val agent = AgentService(FileRepository(root), terminal, GitManager(root), maxSubagentDepth = 0)
                val calls = buildJsonArray {
                    add(tool(0, if (missingId) null else "duplicate"))
                    if (!missingId) add(tool(1, "duplicate"))
                }
                fixture.response = { number, _, _ ->
                    val message = buildJsonObject {
                        put("role", "assistant")
                        put("content", if (number == 1) "" else "Continued safely")
                        if (number == 1) put("tool_calls", calls)
                    }
                    val finish = if (number == 1) "tool_calls" else "stop"
                    val body = buildJsonObject {
                        put("choices", buildJsonArray { add(buildJsonObject {
                            put("index", 0); put(if (buffered) "message" else "delta", message); put("finish_reason", finish)
                        }) })
                    }.toString()
                    if (buffered) body else fixture.event(body) + "data: [DONE]\n\n"
                }
                try {
                    runBlocking {
                        withTimeout(8_000) {
                            agent.chat("Inspect this project without changes", fixture.config, maxTurns = 2)
                            val id = requireNotNull(agent.currentSessionId)
                            assertEquals(1, fixture.requests.size)
                            assertEquals("Malformed tool identities must never start execution", 0, terminal.executionCalls)
                            assertFalse(agent.busy.value)
                            assertNull("A rejected provider batch must leave the conversation saveable", agent.persistenceError.value)
                            val saved = requireNotNull(agent.sessions.get(id))
                            assertTrue(saved.messages.any { it.role == "user" && it.content == "Inspect this project without changes" })
                            assertTrue(SessionForkHistory.isExact(saved.forkHistory))
                            AgentHistory.requireCompleteGroups(saved.apiHistory)

                            agent.chat("Continue with a plain response", fixture.config, maxTurns = 2)
                            assertEquals("The same live conversation must be able to call the provider again", 2, fixture.requests.size)
                            assertTrue(agent.messages.value.any { it.content == "Continued safely" })
                            assertNull(agent.persistenceError.value)
                            assertEquals(0, terminal.executionCalls)
                        }
                    }
                } finally {
                    try { agent.shutdown() }
                    finally { assertEquals("The baseline must never start any terminal process", 0, terminal.executionCalls) }
                }
            }
        } finally {
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    private fun tool(index: Int, id: String?) = buildJsonObject {
        put("index", index)
        if (id != null) put("id", id)
        put("type", "function")
        put("function", buildJsonObject {
            put("name", "read_file")
            put("arguments", buildJsonObject { put("path", "README.md") }.toString())
        })
    }

    private class NoProcessTerminal : ITerminalSession {
        override val output = MutableStateFlow("")
        var executionCalls = 0
        private fun forbidden(): Nothing { executionCalls++; error("No process is allowed in this baseline") }
        override fun start() = forbidden()
        override fun send(cmd: String) = forbidden()
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = forbidden()
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult = forbidden()
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun destroy() = Unit
    }
}
