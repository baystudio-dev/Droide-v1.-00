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
class AgentRuleDiagnosticsRunTest {
    @get:Rule val applicationWork: TestRule = ApplicationWorkAdmissionFixture()

    @Test(timeout = 30_000) fun failedRuleIsDisclosedToTheProviderAndToolHistoryWithoutLeakingItsBody() = runBlocking {
        val root = Files.createTempDirectory("rule-diagnostics-run-").toFile()
        try {
            val dir = root.resolve("plugin").apply { mkdir(); resolve("rules").mkdir() }
            dir.resolve("rules/bad.md").writeText("---\nname: !!!\ntrigger: always\n---\nPRIVATE_RULE_BODY")
            dir.resolve("rules/good.md").writeText("---\ntrigger: always\n---\nVALID_ALWAYS_GUIDANCE")
            val source = AgentPluginSource { listOf(AgentPluginContributionRoot("fixture", "Fixture", "1", "", AgentPluginFormat.DROIDE, dir, "a".repeat(64))) }
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { provider ->
                val agent = AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root),
                    perms = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), maxSubagentDepth = 0, agentPluginSource = source)
                provider.response = { call, _, _ ->
                    if (call == 1) {
                        val args = buildJsonObject { put("name", "fixture:bad") }.toString()
                        val calls = buildJsonArray { add(buildJsonObject {
                            put("index", 0); put("id", "broken-rule"); put("type", "function")
                            put("function", buildJsonObject { put("name", "rule"); put("arguments", args) })
                        }) }
                        provider.event("""{"choices":[{"index":0,"delta":{"tool_calls":$calls},"finish_reason":"tool_calls"}]}""") + "data: [DONE]\n\n"
                    } else provider.event("""{"choices":[{"index":0,"delta":{"content":"Rule unavailable"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n"
                }
                try {
                    runBlocking { agent.chat("Inspect the plugin rule", provider.config, maxTurns = 3) }
                    assertEquals(2, provider.requests.size)
                    val first = provider.requests.first().getValue("messages").toString()
                    assertTrue(first.contains("DROIDE_RULE_DIAGNOSTICS")); assertTrue(first.contains("invalid_rule"))
                    assertTrue(first.contains("VALID_ALWAYS_GUIDANCE")); assertFalse(first.contains("PRIVATE_RULE_BODY"))
                    val schema = provider.requests.first().getValue("tools").jsonArray.map { it.jsonObject.getValue("function").jsonObject }
                        .single { it["name"] == JsonPrimitive("rule") }
                    assertTrue(schema.getValue("description").jsonPrimitive.content.contains("incomplete"))
                    val history = agent.boundedApiHistory().last { it["role"] == JsonPrimitive("tool") }.getValue("content").jsonPrimitive.content
                    val delivered = provider.requests[1].getValue("messages").jsonArray.map { it.jsonObject }
                        .last { it["role"] == JsonPrimitive("tool") }.getValue("content").jsonPrimitive.content
                    assertEquals(history, delivered)
                    assertEquals(AgentToolEvidenceState.FAILED, AgentToolResultSemantics.parseAnnotation(delivered)!!.state)
                    assertTrue(AgentToolResultSemantics.stripAnnotation(delivered).startsWith("ERROR: Rule unavailable:"))
                    assertTrue(delivered.contains("absence is not confirmed")); assertFalse(delivered.contains("PRIVATE_RULE_BODY"))
                    assertFalse(agent.busy.value)
                } finally { agent.shutdown() }
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
