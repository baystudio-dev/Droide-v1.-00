package com.baystudio.droide.core

import android.app.Application
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AgentHeartbeatWorkflowTest {
    @Before fun openWork() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
    }

    @Test(timeout = 30000) fun realAgentWritesOnceAndAnswersAcrossResponsesHeartbeats() = workflow(interrupted = false)

    @Test(timeout = 30000) fun interruptedTurnKeepsCompletedWorkAndNeverExecutesPendingWrite() = workflow(interrupted = true)

    private fun workflow(interrupted: Boolean) = runBlocking {
        val root = Files.createTempDirectory("droide-heartbeat-workflow-").toFile()
        try {
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_RESPONSES) { fixture ->
                val writes = AtomicInteger()
                val files = FileRepository(root, beforeMutation = { if (it is FileRepository.Mutation.Write) writes.incrementAndGet() })
                val agent = AgentService(files, AgentWorkflowTerminal(), GitManager(root),
                    perms = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), maxSubagentDepth = 0)
                val heartbeat = fixture.event("""{"type":"keepalive"}""")
                val arguments = buildJsonObject { put("path", "committed.txt"); put("content", "written once") }.toString()
                val completeCall = buildJsonObject {
                    put("type", "function_call"); put("id", "item-committed"); put("call_id", "committed-call")
                    put("name", "write_file"); put("arguments", arguments); put("status", "completed")
                }
                fixture.response = { number, _, _ ->
                    if (number == 1) heartbeat + fixture.event("""{"type":"response.completed","response":{"status":"completed","output":[$completeCall]}}""")
                    else if (interrupted) fixture.prefix() + heartbeat
                    else heartbeat + fixture.event("""{"type":"response.completed","response":{"status":"completed","output":[{"type":"message","id":"answer-1","role":"assistant","content":[{"type":"output_text","text":"Finished safely."}]}]}}""")
                }
                try {
                    runBlocking { withTimeout(15000) { agent.chat("Write committed.txt, then answer", fixture.config, maxTurns = 3) } }
                    assertEquals("written once", root.resolve("committed.txt").readText())
                    assertEquals(1, writes.get())
                    assertEquals(2, fixture.requests.size)
                    assertFalse(root.resolve("pending.txt").exists())
                    assertFalse(agent.busy.value)
                    val saved = runBlocking { agent.sessions.get(agent.currentSessionId!!)!! }
                    assertEquals(1, saved.apiHistory.count { it["tool_call_id"] == JsonPrimitive("committed-call") })
                    assertFalse(saved.apiHistory.toString().contains("pending-call"))
                    if (interrupted) {
                        assertTrue(saved.messages.any { it.content.contains("incomplete", ignoreCase = true) })
                        assertTrue(saved.apiHistory.any { it["content"]?.toString()?.contains("public partial") == true })
                    } else assertTrue(saved.messages.any { it.content.contains("Finished safely.") })
                } finally { agent.shutdown() }
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
