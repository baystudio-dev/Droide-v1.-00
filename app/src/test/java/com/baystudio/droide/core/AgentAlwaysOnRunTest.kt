package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentAlwaysOnRunTest {
    @get:Rule val applicationWork: TestRule = ApplicationWorkAdmissionFixture()

    @Test(timeout = 30_000) fun omittedAlwaysOnGuidanceCanBeLoadedWithDurableEvidence() = runBlocking {
        val root = Files.createTempDirectory("always-on-run-").toFile()
        try {
            val dir = root.resolve("plugin").apply { mkdir(); resolve("rules").mkdir() }
            repeat(25) { dir.resolve("rules/r%02d.md".format(it)).writeText("---\ntrigger: always\n---\nGUIDANCE_%02d".format(it)) }
            val source = AgentPluginSource { listOf(AgentPluginContributionRoot("p", "p", "1", "", AgentPluginFormat.DROIDE, dir, "a".repeat(64))) }
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { provider ->
                val agent = AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root),
                    perms = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), maxSubagentDepth = 0, agentPluginSource = source)
                provider.response = { call, _, _ ->
                    if (call == 1) {
                        val args = buildJsonObject { put("name", "p:r24") }.toString()
                        val calls = buildJsonArray { add(buildJsonObject {
                            put("index", 0); put("id", "load-omitted"); put("type", "function")
                            put("function", buildJsonObject { put("name", "rule"); put("arguments", args) })
                        }) }
                        provider.event("""{"choices":[{"index":0,"delta":{"tool_calls":$calls},"finish_reason":"tool_calls"}]}""") + "data: [DONE]\n\n"
                    } else provider.event("""{"choices":[{"index":0,"delta":{"content":"Loaded"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n"
                }
                try {
                    runBlocking { agent.chat("Inspect omitted plugin guidance", provider.config, maxTurns = 3) }
                    assertEquals(2, provider.requests.size)
                    val first = provider.requests.first().getValue("messages").toString()
                    assertTrue(first.contains("Always-on rule selection limit")); assertTrue(first.contains("GUIDANCE_23")); assertFalse(first.contains("GUIDANCE_24"))
                    val history = agent.boundedApiHistory().filter { it["role"] == JsonPrimitive("tool") }.map { it.getValue("content").jsonPrimitive.content }
                    assertEquals(1, history.size); assertTrue(history.single().endsWith("GUIDANCE_24"))
                    assertEquals(AgentToolEvidenceState.SUCCEEDED, AgentToolResultSemantics.parseAnnotation(history.single())!!.state)
                    val delivered = provider.requests.last().getValue("messages").jsonArray.map { it.jsonObject }
                        .filter { it["role"] == JsonPrimitive("tool") }.map { it.getValue("content").jsonPrimitive.content }
                    assertEquals(history, delivered); assertFalse(agent.busy.value)
                } finally { agent.shutdown() }
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
