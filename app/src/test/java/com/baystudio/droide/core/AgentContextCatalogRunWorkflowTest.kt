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
class AgentContextCatalogRunWorkflowTest {
    @get:Rule val applicationWork: TestRule = ApplicationWorkAdmissionFixture()

    @Test(timeout = 30_000) fun actualProviderRequestsStayBoundedAndNormalSkillStillRuns() = runBlocking {
        val root = Files.createTempDirectory("droide-skill-run-").toFile()
        val file = root.resolve(".droide/skills/alpha/SKILL.md").apply {
            parentFile.mkdirs(); writeText("---\nname: alpha\ndescription: First description\n---\nORIGINAL_PRIVATE_SKILL_BODY")
        }
        root.resolve(".droide/skills/large/SKILL.md").apply {
            parentFile.mkdirs(); writeText("---\nname: " + "z".repeat(180_000) + "\ndescription: Large\n---\nPRIVATE_LARGE_BODY")
        }
        try {
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { provider ->
                val agent = AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root),
                    perms = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), maxSubagentDepth = 0)
                provider.response = { call, _, _ ->
                    if (call == 1) {
                        file.writeText(file.readText().replace("First", "Fresh").replace("ORIGINAL_PRIVATE_SKILL_BODY", "CURRENT_SKILL_BODY"))
                        val calls = buildJsonArray { add(buildJsonObject {
                            put("index", 0); put("id", "load-skill"); put("type", "function")
                            put("function", buildJsonObject { put("name", "skill"); put("arguments", "{\"name\":\"alpha\"}") })
                        }) }
                        provider.event("""{"choices":[{"index":0,"delta":{"tool_calls":$calls},"finish_reason":"tool_calls"}]}""") + "data: [DONE]\n\n"
                    } else provider.event("""{"choices":[{"index":0,"delta":{"content":"Done"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n"
                }
                try {
                    runBlocking { agent.chat("Load and follow the alpha skill for this task", provider.config, maxTurns = 3) }
                    assertEquals(2, provider.requests.size)
                    provider.requests.forEach { request ->
                        assertTrue("Provider payload must stay bounded", request.toString().toByteArray(Charsets.UTF_8).size < 150_000)
                        assertFalse(request.toString().contains("PRIVATE_LARGE_BODY"))
                        val tool = request.getValue("tools").jsonArray.map { it.jsonObject.getValue("function").jsonObject }
                            .first { it["name"] == JsonPrimitive("skill") }
                        assertFalse(tool.toString().contains("a".repeat(257)))
                        assertNull(tool.getValue("parameters").jsonObject.getValue("properties").jsonObject.getValue("name").jsonObject["enum"])
                    }
                    fun description(index: Int) = provider.requests[index].getValue("tools").jsonArray
                        .map { it.jsonObject.getValue("function").jsonObject }.first { it["name"] == JsonPrimitive("skill") }
                        .getValue("description").jsonPrimitive.content
                    assertTrue(description(0).contains("First description"))
                    assertFalse(provider.requests[0].toString().contains("ORIGINAL_PRIVATE_SKILL_BODY"))
                    assertTrue(description(1).contains("Fresh description"))
                    val result = agent.boundedApiHistory().last { it["role"] == JsonPrimitive("tool") }.getValue("content").jsonPrimitive.content
                    assertTrue(result.contains("CURRENT_SKILL_BODY")); assertFalse(result.contains("ORIGINAL_PRIVATE_SKILL_BODY"))
                    assertFalse(agent.busy.value)
                } finally { agent.shutdown() }
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
