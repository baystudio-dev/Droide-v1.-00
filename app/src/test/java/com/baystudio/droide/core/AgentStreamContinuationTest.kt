package com.baystudio.droide.core

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
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
class AgentStreamContinuationTest {
    @org.junit.Before fun openApplicationSession() {
        ApplicationWorkAdmission.initialize(org.robolectric.RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
    }

    @Test(timeout = 30_000) fun completedEditAndInterruptedTextSurviveTheSameSessionWithoutToolReplay() = runBlocking {
        val root = Files.createTempDirectory("droide-agent-stream-continuation-").toFile()
        try {
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { fixture ->
                val edits = AtomicInteger()
                val files = FileRepository(root, beforeMutation = { mutation -> if (mutation is FileRepository.Mutation.Write) edits.incrementAndGet() })
                val agent = AgentService(files, AgentWorkflowTerminal(), GitManager(root),
                    perms = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), maxSubagentDepth = 0)
                fixture.response = { call, _, _ -> when (call) {
                    1 -> {
                        val arguments = buildJsonObject { put("path", "committed.txt"); put("content", "written once") }.toString()
                        val delta = buildJsonObject {
                            put("tool_calls", buildJsonArray { add(buildJsonObject {
                                put("index", 0); put("id", "committed-call"); put("type", "function")
                                put("function", buildJsonObject { put("name", "write_file"); put("arguments", arguments) })
                            }) })
                        }
                        fixture.event("""{"choices":[{"index":0,"delta":$delta,"finish_reason":"tool_calls"}]}""") + "data: [DONE]\n\n"
                    }
                    2 -> fixture.prefix() + fixture.errorEvent()
                    else -> fixture.event("""{"choices":[{"index":0,"delta":{"content":"continued safely"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n"
                } }
                try {
                    runBlocking { withTimeout(15_000) { agent.chat("Write a file and explain", fixture.config, maxTurns = 3) } }
                    val id = agent.currentSessionId!!
                    val first = runBlocking { agent.sessions.get(id)!! }
                    // The existing lifecycle clears completed live task UI after saving
                    // durable messages. Assert the persisted error, not stale live state.
                    assertEquals(AgentStreamState.Status.IDLE, agent.streamState.value.status)
                    assertFalse(agent.busy.value)
                    assertTrue(first.messages.any { it.content.contains("Provider response error inside HTTP 200") })
                    assertEquals("written once", root.resolve("committed.txt").readText())
                    assertEquals(1, edits.get())
                    assertFalse(root.resolve("pending.txt").exists())
                    assertEquals(1, first.apiHistory.count { it["tool_call_id"] == JsonPrimitive("committed-call") })
                    assertFalse(first.apiHistory.toString().contains("pending-call"))
                    assertTrue(first.apiHistory.any { it["content"]?.toString()?.contains("public partial") == true })
                    assertTrue(first.messages.any { it.streamPart?.text == "public partial" && it.streamPart.state == AgentStreamPartState.ERROR })
                    assertFalse(first.messages.toString().contains(PROVIDER_STREAM_SECRET))
                    runBlocking { agent.loadSession(first) }
                    runBlocking { withTimeout(10_000) { agent.chat("Continue", fixture.config, maxTurns = 1) } }
                    assertEquals(id, agent.currentSessionId)
                    assertEquals(1, edits.get())
                    assertEquals(3, fixture.requests.size)
                    assertTrue(fixture.requests.last().toString().contains("public partial"))
                    assertFalse(fixture.requests.last().toString().contains("pending-call"))
                    assertEquals(1, runBlocking { agent.sessions.count() })
                } finally { agent.shutdown() }
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
