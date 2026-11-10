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
class AgentContextResultRunTest {
    @get:Rule val applicationWork: TestRule = ApplicationWorkAdmissionFixture()

    @Test(timeout = 30_000) fun shortenedMetadataReachesHistoryAndNextProviderRequestWithBodyIntact() = runBlocking {
        val root = Files.createTempDirectory("droide-context-result-run-").toFile()
        val name = "a".repeat(256)
        val body = "CURRENT_PRIVATE_INSTRUCTIONS"
        root.resolve(".droide/skills/large/SKILL.md").apply {
            parentFile.mkdirs(); writeText("---\nname: $name\ndescription: ${"d".repeat(3000)}\n---\n$body")
        }
        try {
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { provider ->
                val agent = AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root),
                    perms = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), maxSubagentDepth = 0)
                provider.response = { call, _, _ ->
                    if (call == 1) {
                        val args = buildJsonObject { put("name", name) }.toString()
                        val calls = buildJsonArray { add(buildJsonObject {
                            put("index", 0); put("id", "load-large-skill"); put("type", "function")
                            put("function", buildJsonObject { put("name", "skill"); put("arguments", args) })
                        }) }
                        provider.event("""{"choices":[{"index":0,"delta":{"tool_calls":$calls},"finish_reason":"tool_calls"}]}""") + "data: [DONE]\n\n"
                    } else provider.event("""{"choices":[{"index":0,"delta":{"content":"Done"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n"
                }
                try {
                    runBlocking { agent.chat("Load the requested skill", provider.config, maxTurns = 3) }
                    assertEquals(2, provider.requests.size)
                    assertFalse(provider.requests[0].toString().contains(body))
                    val history = agent.boundedApiHistory().last { it["role"] == JsonPrimitive("tool") }.getValue("content").jsonPrimitive.content
                    val delivered = provider.requests[1].getValue("messages").jsonArray.map { it.jsonObject }
                        .last { it["role"] == JsonPrimitive("tool") }.getValue("content").jsonPrimitive.content
                    assertEquals(history, delivered)
                    assertTrue(delivered.length < 2_000)
                    assertFalse(delivered.contains("d".repeat(1025))); assertTrue(delivered.contains(body)); assertTrue(delivered.contains("[skill_sha256="))
                    assertFalse(agent.busy.value)
                } finally { agent.shutdown() }
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
