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
class AgentRuleQuotaRunTest {
    @get:Rule val applicationWork: TestRule = ApplicationWorkAdmissionFixture()

    @Test(timeout = 30_000) fun quotaFailureIsDisclosedAndExplicitLoadRecoversAfterRetainingExcessFile() = runBlocking {
        val root = Files.createTempDirectory("rule-quota-run-").toFile()
        try {
            val dir = root.resolve("plugin").apply { mkdir(); resolve("rules").mkdir() }
            repeat(32) { dir.resolve("rules/a%02d.md".format(it)).writeText("Existing $it") }
            val bad = dir.resolve("rules/z-tail.md").apply { writeText("RECOVERED_RULE") }
            val source = AgentPluginSource { listOf(AgentPluginContributionRoot("fixture", "Fixture", "1", "", AgentPluginFormat.DROIDE, dir, "a".repeat(64))) }
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { provider ->
                val agent = AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root),
                    perms = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), maxSubagentDepth = 0, agentPluginSource = source)
                provider.response = { call, _, _ ->
                    if (call <= 2) {
                        if (call == 2) Files.move(dir.resolve("rules/a00.md").toPath(), dir.resolve("retained-a00.md").toPath())
                        val args = buildJsonObject { put("name", "fixture:z-tail") }.toString()
                        val calls = buildJsonArray { add(buildJsonObject {
                            put("index", 0); put("id", "rule-$call"); put("type", "function")
                            put("function", buildJsonObject { put("name", "rule"); put("arguments", args) })
                        }) }
                        provider.event("""{"choices":[{"index":0,"delta":{"tool_calls":$calls},"finish_reason":"tool_calls"}]}""") + "data: [DONE]\n\n"
                    } else provider.event("""{"choices":[{"index":0,"delta":{"content":"Recovered"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n"
                }
                try {
                    runBlocking { agent.chat("Inspect the plugin rule", provider.config, maxTurns = 4) }
                    assertEquals(3, provider.requests.size)
                    val first = provider.requests.first().getValue("messages").toString()
                    assertTrue(first.contains("DROIDE_RULE_DIAGNOSTICS")); assertTrue(first.contains("Per-plugin rule admission limit"))
                    val history = agent.boundedApiHistory().filter { it["role"] == JsonPrimitive("tool") }.map { it.getValue("content").jsonPrimitive.content }
                    assertEquals(2, history.size)
                    assertEquals(AgentToolEvidenceState.FAILED, AgentToolResultSemantics.parseAnnotation(history[0])!!.state)
                    assertTrue(AgentToolResultSemantics.stripAnnotation(history[0]).startsWith("ERROR: Rule unavailable:")); assertTrue(history[0].contains("Per-plugin rule admission limit"))
                    assertEquals(AgentToolEvidenceState.SUCCEEDED, AgentToolResultSemantics.parseAnnotation(history[1])!!.state)
                    assertTrue(history[1].endsWith("RECOVERED_RULE")); assertEquals("RECOVERED_RULE", bad.readText())
                    val delivered = provider.requests.last().getValue("messages").jsonArray.map { it.jsonObject }
                        .filter { it["role"] == JsonPrimitive("tool") }.map { it.getValue("content").jsonPrimitive.content }
                    assertEquals(history, delivered)
                    val schema = provider.requests.last().getValue("tools").jsonArray.map { it.jsonObject.getValue("function").jsonObject }
                        .single { it["name"] == JsonPrimitive("rule") }
                    assertFalse(schema.getValue("description").jsonPrimitive.content.contains("incomplete"))
                    val names = schema.getValue("parameters").jsonObject.getValue("properties").jsonObject.getValue("name").jsonObject.getValue("enum").jsonArray
                    assertEquals(32, names.size); assertTrue(names.contains(JsonPrimitive("fixture:z-tail")))
                    assertEquals("Existing 0", dir.resolve("retained-a00.md").readText())
                    assertFalse(agent.busy.value)
                } finally { agent.shutdown() }
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
