package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentRuleReadRunTest {
    @get:Rule val applicationWork: TestRule = ApplicationWorkAdmissionFixture()

    @Test(timeout = 30_000) fun toolLoadsCurrentRuleWhileAlwaysOnGuidanceReachesTheFirstRequest() = runBlocking {
        val root = Files.createTempDirectory("droide-rule-run-").toFile()
        try {
            val dir = root.resolve("plugin").apply { mkdir(); resolve("rules").mkdir() }
            dir.resolve("rules/always.md").writeText("---\ntrigger: always\n---\nALWAYS_GUIDANCE")
            val file = dir.resolve("rules/manual.md").apply { writeText("---\ntrigger: manual\n---\nOLD_MANUAL_BODY") }
            val source = AgentPluginSource { listOf(AgentPluginContributionRoot("fixture", "Fixture", "1", "", AgentPluginFormat.DROIDE, dir, "a".repeat(64))) }
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { provider ->
                val agent = AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root),
                    perms = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), maxSubagentDepth = 0, agentPluginSource = source)
                provider.response = { call, _, _ ->
                    if (call == 1) {
                        file.writeText("---\ntrigger: manual\n---\nCURRENT_MANUAL_BODY")
                        val args = buildJsonObject { put("name", "fixture:manual") }.toString()
                        val calls = buildJsonArray { add(buildJsonObject {
                            put("index", 0); put("id", "rule-one"); put("type", "function")
                            put("function", buildJsonObject { put("name", "rule"); put("arguments", args) })
                        }) }
                        provider.event("""{"choices":[{"index":0,"delta":{"tool_calls":$calls},"finish_reason":"tool_calls"}]}""") + "data: [DONE]\n\n"
                    } else provider.event("""{"choices":[{"index":0,"delta":{"content":"Done"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n"
                }
                try {
                    runBlocking { agent.chat("Load the manual rule", provider.config, maxTurns = 3) }
                    assertEquals(2, provider.requests.size)
                    val first = provider.requests[0].getValue("messages").toString()
                    assertTrue(first.contains("ALWAYS_GUIDANCE")); assertFalse(first.contains("OLD_MANUAL_BODY"))
                    val history = agent.boundedApiHistory().last { it["role"] == JsonPrimitive("tool") }.getValue("content").jsonPrimitive.content
                    val delivered = provider.requests[1].getValue("messages").jsonArray.map { it.jsonObject }
                        .last { it["role"] == JsonPrimitive("tool") }.getValue("content").jsonPrimitive.content
                    assertEquals(history, delivered); assertTrue(delivered.endsWith("CURRENT_MANUAL_BODY"))
                    assertFalse(delivered.contains("OLD_MANUAL_BODY")); assertFalse(agent.busy.value)
                } finally { agent.shutdown() }
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
