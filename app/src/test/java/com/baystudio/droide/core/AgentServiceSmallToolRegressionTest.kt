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
@Config(sdk = [35], manifest = Config.NONE)
class AgentServiceSmallToolRegressionTest {
    @org.junit.Before fun openApplicationSession() {
        ApplicationWorkAdmission.initialize(org.robolectric.RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
    }

    @Test(timeout = 30_000) fun smallToolsRetainActivityAndExactPrivateReplayWithoutSessionLeak() =
        scenario("private phase payload")

    @Test(timeout = 60_000) fun longPrivateReasoningDoesNotBlockSmallToolExecutionOrExactReplay() =
        scenario("private phase payload-".repeat(110_000))

    private fun scenario(privateText: String) = runBlocking {
        val root = Files.createTempDirectory("droide-agent-activity-").toFile()
        try {
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { fixture ->
                ModelContextRegistry.remember(fixture.provider.id, fixture.model, 1_000_000)
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
                        privateText.chunked(32_768).joinToString("") { chunk ->
                            fixture.event(buildJsonObject {
                                put("choices", buildJsonArray { add(buildJsonObject {
                                    put("delta", buildJsonObject { put("reasoning_content", chunk) })
                                }) })
                            }.toString())
                        } +
                            fixture.event("""{"choices":[{"delta":{"content":"I will write the file."}}]}""") +
                            fixture.event("""{"choices":[{"delta":$tools,"finish_reason":"tool_calls"}]}""") + "data: [DONE]\n\n"
                    } else fixture.event("""{"choices":[{"delta":{"content":"Done"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n"
                }
                try {
                    runBlocking { withTimeout(30_000) { agent.chat("Create created.txt", fixture.config, maxTurns = 2) } }
                    assertTrue(observed.containsAll(listOf(AgentModelActivity.WORKING, AgentModelActivity.THINKING, AgentModelActivity.RESPONDING, AgentModelActivity.PREPARING_TOOL)))
                    assertTrue(agent.messages.value.toString(), root.resolve("created.txt").exists())
                    assertEquals("once", root.resolve("created.txt").readText())
                    val session = runBlocking { requireNotNull(agent.sessions.get(requireNotNull(agent.currentSessionId))) }
                    assertTrue(session.apiHistory.isEmpty())
                    val checkpoint = checkNotNull(session.typedChat)
                    assertEquals(1, checkpoint.exact.filterIsInstance<TypedChatEntry.Legacy>().count {
                        it.message["tool_call_id"] == JsonPrimitive("create-call") })
                    assertTrue(checkpoint.exact.any { it is TypedChatEntry.OwnedAssistant })
                    assertFalse(checkpoint.toString().contains("private phase payload"))
                    val replay = fixture.requests.last().getValue("messages").jsonArray
                    val native = replay.map { it.jsonObject }.first { it["tool_calls"] != null }
                    assertEquals(privateText, native.getValue("reasoning_content").jsonPrimitive.content)
                    assertFalse(replay.toString().contains("_droide_chat_reasoning"))
                    assertTrue(session.messages.any { it.content == "Done" || it.streamPart?.text == "Done" })
                    assertFalse(session.messages.toString().contains("private phase payload"))
                    assertFalse(session.apiHistory.toString().contains("private phase payload"))
                    assertFalse(agent.busy.value)
                } finally { agent.shutdown() }
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
