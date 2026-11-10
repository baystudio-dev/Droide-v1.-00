package com.baystudio.droide.core

import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentActivityServiceWorkflowTest {
    @org.junit.Before fun openApplicationSession() {
        ApplicationWorkAdmission.initialize(org.robolectric.RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
    }

    @Test(timeout = 30_000) fun providerEventsReachServiceActivityWithoutChangingToolExecutionOrHistory() = runBlocking {
        val root = Files.createTempDirectory("droide-agent-activity-").toFile()
        try {
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { fixture ->
                val agent = AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root),
                    perms = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), maxSubagentDepth = 0)
                val observed = CopyOnWriteArrayList<AgentModelActivity>()
                agent.publicationBoundaryProbe = { boundary ->
                    if (boundary == AgentService.PublicationBoundary.PROVIDER_EVENT || boundary == AgentService.PublicationBoundary.PROVIDER_RESULT)
                        observed += agent.streamState.value.modelActivity
                }
                fixture.response = { call, _, _ ->
                    if (call == 1) {
                        val arguments = buildJsonObject { put("path", "created.txt"); put("content", "once") }.toString()
                        val tools = buildJsonObject {
                            put("tool_calls", buildJsonArray { add(buildJsonObject {
                                put("index", 0); put("id", "create-call"); put("type", "function")
                                put("function", buildJsonObject { put("name", "write_file"); put("arguments", arguments) })
                            }) })
                        }
                        fixture.event("""{"choices":[{"delta":{"reasoning_content":"private phase payload"}}]}""") +
                            fixture.event("""{"choices":[{"delta":{"content":"I will write the file."}}]}""") +
                            fixture.event("""{"choices":[{"delta":$tools,"finish_reason":"tool_calls"}]}""") + "data: [DONE]\n\n"
                    } else fixture.event("""{"choices":[{"delta":{"content":"Done"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n"
                }
                try {
                    runBlocking { withTimeout(15_000) { agent.chat("Create created.txt", fixture.config, maxTurns = 2) } }
                    assertTrue(observed.containsAll(listOf(AgentModelActivity.WORKING, AgentModelActivity.THINKING, AgentModelActivity.RESPONDING, AgentModelActivity.PREPARING_TOOL)))
                    assertEquals("once", root.resolve("created.txt").readText())
                    val session = runBlocking { requireNotNull(agent.sessions.get(requireNotNull(agent.currentSessionId))) }
                    assertEquals(1, session.apiHistory.count { it["tool_call_id"] == JsonPrimitive("create-call") })
                    assertTrue(session.messages.any { it.content == "Done" || it.streamPart?.text == "Done" })
                    assertFalse(session.messages.toString().contains("private phase payload"))
                    assertFalse(session.apiHistory.toString().contains("private phase payload"))
                    assertFalse(agent.busy.value)
                } finally { agent.shutdown() }
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
