package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the real consumers so a downstream check cannot hide an upstream lossy projection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderAdmissionIntegrationTest {
    @org.junit.Before
    fun openApplicationWork() {
        ApplicationWorkAdmission.initialize(org.robolectric.RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
    }

    private val user = buildJsonObject { put("role", "user"); put("content", "Read the evidence") }
    private val toolCall = buildJsonObject {
        put("id", "read-evidence"); put("type", "function")
        put("function", buildJsonObject { put("name", "read_file"); put("arguments", "{\"path\":\"evidence.txt\"}") })
    }
    private val assistant = buildJsonObject {
        put("role", "assistant"); put("content", "public text"); put("tool_calls", JsonArray(listOf(toolCall)))
    }
    private fun result(id: String = "read-evidence") = buildJsonObject {
        put("role", "tool"); put("tool_call_id", id); put("content", "saved evidence")
    }
    private fun response(finish: String?, message: JsonObject = assistant) = buildJsonObject {
        put("choices", buildJsonArray { add(buildJsonObject {
            put("message", message)
            finish?.let { put("finish_reason", it) }
        }) })
    }.toString()

    @Test(timeout = 30_000) fun bufferedChatCompletionGatePreventsExploreRetentionAndExecutionOfUnfinishedCalls() {
        for (finish in listOf("length", "content_filter", "stop", null, "tool_calls")) {
            val root = Files.createTempDirectory("droide-explore-completion-").toFile()
            try {
                root.resolve("evidence.txt").writeText("READ_EXECUTED_SENTINEL")
                withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { http -> runBlocking {
                    http.forceBufferedResponse = true
                    http.response = { number, _, _ -> if (number == 1) response(finish) else
                        response("stop", buildJsonObject { put("role", "assistant"); put("content", "done") }) }
                    val explore = ExploreAgent(FileRepository(root), GitManager(root), LlmClient(),
                        PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))))
                    val outcome = runCatching { withTimeout(10_000) { explore.run("Read evidence.txt", http.config, maxTurns = 2) } }
                    if (finish == null) {
                        assertTrue(outcome.exceptionOrNull()?.message.orEmpty().contains("finish reason"))
                    } else assertTrue("$finish: ${outcome.exceptionOrNull()}", outcome.isSuccess)
                    if (finish == "tool_calls") {
                        assertEquals(2, http.requests.size)
                        val messages = http.requests.last()["messages"]!!.jsonArray.map { it.jsonObject }
                        assertEquals(1, messages.count { it["role"] == JsonPrimitive("tool") })
                        assertTrue(messages.single { it["role"] == JsonPrimitive("tool") }
                            .getValue("content").jsonPrimitive.content.contains("READ_EXECUTED_SENTINEL"))
                    } else {
                        assertEquals("$finish must stop before a tool result can be replayed", 1, http.requests.size)
                        assertFalse(http.requests.any { request -> request["messages"]!!.jsonArray.any {
                            it.jsonObject["role"] == JsonPrimitive("tool")
                        } })
                    }
                } }
            } finally { PathSecurity.deleteTreeNoFollow(root) }
        }
    }

    @Test(timeout = 30_000) fun restoredMalformedHistoryFailsAtTheActualMainAgentBoundaryBeforeHttp() {
        val histories = listOf(
            listOf(user, assistant, result(), result()),
            listOf(user, result("orphan")),
            listOf(user, assistant),
        )
        for (source in histories) {
            val root = Files.createTempDirectory("droide-agent-history-admission-").toFile()
            try {
                withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { http -> runBlocking {
                    val agent = AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root), maxSubagentDepth = 0)
                    try {
                        val restored = agent.sessions.create("Restored").copy(apiHistory = source,
                            forkHistory = listOf(user), forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION)
                        agent.sessions.save(restored)
                        agent.loadSession(agent.sessions.get(restored.id)!!)
                        withTimeout(10_000) { agent.chat("Continue with all saved evidence", http.config, maxTurns = 1) }
                        assertTrue("Malformed evidence must be rejected before any provider request", http.requests.isEmpty())
                        assertTrue(agent.messages.value.any { it.content.contains("incomplete or mismatched tool calls/results") })
                        assertFalse(agent.busy.value)
                    } finally { agent.shutdown() }
                } }
            } finally { PathSecurity.deleteTreeNoFollow(root) }
        }
    }

    @Test(timeout = 30_000) fun completeRestoredGroupReachesTheActualMainAgentRequestWithoutFiltering() {
        val root = Files.createTempDirectory("droide-agent-complete-admission-").toFile()
        try {
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { http -> runBlocking {
                val agent = AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root), maxSubagentDepth = 0)
                http.forceBufferedResponse = true
                http.response = { _, _, _ -> response("stop", buildJsonObject { put("role", "assistant"); put("content", "done") }) }
                try {
                    val complete = listOf(user, assistant, result())
                    val restored = agent.sessions.create("Complete").copy(apiHistory = complete,
                        forkHistory = complete, forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION)
                    agent.sessions.save(restored)
                    agent.loadSession(agent.sessions.get(restored.id)!!)
                    withTimeout(10_000) { agent.chat("Continue", http.config, maxTurns = 1) }
                    assertEquals(1, http.requests.size)
                    val wireMessages = http.requests.single()["messages"]!!.jsonArray.map { it.jsonObject }
                    assertTrue(wireMessages.containsAll(complete))
                    assertEquals(1, wireMessages.count { it["tool_call_id"] == JsonPrimitive("read-evidence") })
                } finally { agent.shutdown() }
            } }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
