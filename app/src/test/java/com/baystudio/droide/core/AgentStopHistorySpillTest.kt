package com.baystudio.droide.core

import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises real AgentService cancellation and disk history; HTTP replies are fixture data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentStopHistorySpillTest {
    @get:org.junit.Rule
    val applicationAdmission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    @Test(timeout = 40_000)
    fun stopClosesPendingToolWhenItsResultMustSpillExactHistoryToDisk() {
        val root = Files.createTempDirectory("droide-stop-history-spill-").toFile()
        try {
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { provider ->
                runBlocking {
                    withTimeout(30_000) {
                        val escaped = CopyOnWriteArrayList<Throwable>()
                        val owner = SupervisorJob(coroutineContext[Job])
                        val scope = CoroutineScope(coroutineContext + owner + CoroutineExceptionHandler { _, error -> escaped += error })
                        val agent = AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root), maxSubagentDepth = 0)
                        try {
                            val original = root.resolve("keep.txt").apply { writeText("user source") }
                            val prompt = "Edit keep.txt"
                            val callId = "spill-write"
                            val tool = buildJsonObject {
                                put("index", 0)
                                put("id", callId)
                                put("type", "function")
                                put("function", buildJsonObject {
                                    put("name", "write_file")
                                    put("arguments", buildJsonObject {
                                        put("path", "keep.txt")
                                        put("content", "unapproved overwrite")
                                    }.toString())
                                })
                            }
                            val pendingTool = buildJsonObject {
                                put("role", "assistant")
                                put("content", "")
                                put("tool_calls", JsonArray(listOf(tool)))
                            }
                            val promptEntry = VisionSupport.userMessage(prompt, emptyList(), AgentService.MAX_API_MESSAGE_CHARS)
                            fun previousEntry(text: String): JsonObject = buildJsonObject {
                                put("role", "user")
                                put("content", text)
                            }
                            // Use about half a MiB of ordinary history, not a huge response or a mocked counter.
                            // The next interrupted-tool result is the first append that needs disk spill.
                            val previousBytes = SessionHistoryLedger.HOT_HISTORY_BYTES -
                                SessionJsonSize.estimatedBytes(promptEntry) - SessionJsonSize.estimatedBytes(pendingTool) - 1
                            val previous = previousEntry("x".repeat((previousBytes - SessionJsonSize.estimatedBytes(previousEntry(""))).toInt()))
                            assertEquals(previousBytes, SessionJsonSize.estimatedBytes(previous))
                            val session = agent.sessions.create("Stop at history spill")
                            val summary = buildJsonObject {
                                put("role", "system")
                                put("content", "Previous context: user source must be preserved until write approval.")
                            }
                            agent.loadSession(session.copy(
                                apiHistory = listOf(summary),
                                forkHistory = listOf(previous),
                                forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION,
                            ))
                            provider.response = { call, _, _ ->
                                if (call == 1) provider.event("""{"choices":[{"index":0,"delta":{"tool_calls":[$tool]},"finish_reason":"tool_calls"}]}""") + "data: [DONE]\n\n"
                                else provider.event("""{"choices":[{"index":0,"delta":{"content":"Continued safely"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n"
                            }
                            assertNotNull(agent.submitAsync(scope, prompt, provider.config))
                            awaitCondition { agent.approvals.pending.value != null }
                            // Read-only fixture observation confirms the real append path, not a synthetic history state.
                            val history = AgentService::class.java.getDeclaredField("conversationHistory")
                                .apply { isAccessible = true }.get(agent) as AgentConversationHistory
                            assertEquals(SessionHistoryLedger.HOT_HISTORY_BYTES - 1, history.hotHistoryBytes)
                            assertTrue(agent.busy.value)
                            assertEquals(1, provider.requests.size)

                            agent.cancel()
                            awaitCondition { !agent.busy.value && agent.approvals.pending.value == null }

                            assertEquals("user source", original.readText())
                            assertNull("Stopped history must remain saveable", agent.persistenceError.value)
                            val saved = requireNotNull(SessionManager(root).get(session.id))
                            assertTrue("The stopped session must retain complete tool-call/result groups", SessionForkHistory.isExact(saved.forkHistory))
                            assertTrue("The spill must persist cold history pages", saved.forkHistoryPages.isNotEmpty())
                            assertEquals(previous, saved.forkHistory.first())
                            val closure = saved.forkHistory.single { it["tool_call_id"] == JsonPrimitive(callId) }
                            assertEquals(JsonPrimitive("Tool execution was interrupted by the user"), closure["content"])
                            assertEquals(1, saved.apiHistory.count { it["tool_call_id"] == JsonPrimitive(callId) })
                            assertTrue(saved.messages.any { it.content == "Stopped by user." })

                            agent.loadSession(saved, requireIdle = true)
                            assertNotNull(agent.submitAsync(scope, "Continue without editing", provider.config))
                            awaitCondition {
                                !agent.busy.value && agent.messages.value.any {
                                    it.content.contains("Continued safely") || it.streamPart?.text?.contains("Continued safely") == true
                                }
                            }
                            assertEquals(session.id, agent.currentSessionId)
                            assertEquals("user source", original.readText())
                            assertEquals(2, provider.requests.size)
                            assertTrue("No exception may escape the Agent run: $escaped", escaped.isEmpty())
                        } finally {
                            agent.shutdown()
                            scope.coroutineContext.job.cancelAndJoin()
                        }
                    }
                }
            }
        } finally {
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    private suspend fun awaitCondition(condition: () -> Boolean) = withTimeout(10_000) {
        while (!condition()) delay(5)
    }
}
