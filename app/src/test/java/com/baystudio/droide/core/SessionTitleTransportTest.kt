package com.baystudio.droide.core

import android.app.Application
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SessionTitleTransportTest {
    @get:Rule val applicationWork: TestRule = ApplicationWorkAdmissionFixture()
    private fun reply(protocol: ProviderWireProtocol): String = when (protocol) {
        ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> """{"choices":[{"message":{"role":"assistant","content":"Skor pertandingan voli"},"finish_reason":"stop"}]}"""
        ProviderWireProtocol.OPENAI_RESPONSES -> """{"id":"response_title","status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Skor pertandingan voli"}]}]}"""
        ProviderWireProtocol.ANTHROPIC_MESSAGES -> """{"content":[{"type":"text","text":"Skor pertandingan voli"}],"stop_reason":"end_turn"}"""
        ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> """{"candidates":[{"content":{"role":"model","parts":[{"text":"Skor pertandingan voli"}]},"finishReason":"STOP"}]}"""
    }
    @Test(timeout = 30_000) fun isolatedTitleUsesAllFourProviderAdapters() {
        for (protocol in ProviderWireProtocol.entries) withProviderStreamFixture(protocol) { http -> runBlocking {
            http.forceBufferedResponse = true; http.response = { _, _, _ -> reply(protocol) }
            val body = SessionTitlePolicy.request(http.config, SessionTitleSeed("hi", "Buat aplikasi skor voli"))
            val result = LlmClient().sessionTitleCompletion(http.config, body.toString())
            assertEquals(protocol.name, "Skor pertandingan voli", SessionTitlePolicy.parse(result))
            val wire = http.requests.single()
            assertFalse(wire.containsKey("tools"))
            assertTrue(wire.toString().toByteArray().size < 2400)
        } }
    }
    @Test(timeout = 30_000) fun realAgentTitlesAfterTopicWithoutChangingItsRequestOrHistory() = withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { http -> runBlocking {
        val root = Files.createTempDirectory("agent-title").toFile()
        val terminal = AgentWorkflowTerminal()
        val agent = AgentService(FileRepository(root), terminal, GitManager(root))
        try {
            http.forceBufferedResponse = true; http.response = { _, _, _ -> reply(http.protocol) }
            agent.chat("pagi bang", http.config, maxTurns = 1)
            assertEquals(1, http.requests.size)
            agent.chat("Jelaskan aturan skor pertandingan voli", http.config, maxTurns = 1)
            withTimeout(5000) { agent.sessions.titleRevision.first { it > 0 } }
            val saved = agent.sessions.get(agent.currentSessionId!!)!!
            assertEquals("Skor pertandingan voli", saved.title)
            assertEquals(3, http.requests.size)
            val utility = http.requests.last()
            assertEquals(2, utility["messages"]!!.jsonArray.size)
            assertFalse(utility.containsKey("tools"))
            assertFalse(saved.apiHistory.toString().contains("Write only a short"))
            assertFalse(agent.busy.value)
            val sameId = agent.currentSessionId
            agent.chat("Terima kasih", http.config, maxTurns = 1)
            assertEquals(4, http.requests.size)
            assertEquals(sameId, agent.currentSessionId)
            assertEquals("Skor pertandingan voli", agent.sessions.get(sameId!!)!!.title)
        } finally { agent.shutdown(); terminal.destroy(); root.deleteRecursively() }
    } }
}
