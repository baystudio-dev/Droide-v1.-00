package com.baystudio.droide.core

import java.io.File
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
class AgentCatalogCompletenessRunTest {
    @get:Rule val applicationWork: TestRule = ApplicationWorkAdmissionFixture()

    private suspend fun scenario(root: File, tool: String, name: String, remove: File, body: String,
                                 source: AgentPluginSource = AgentPluginSource.EMPTY) {
        withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { provider ->
            val agent = AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root),
                perms = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), maxSubagentDepth = 0,
                agentPluginSource = source)
            provider.response = { call, _, _ ->
                if (call == 1) {
                    check(remove.delete())
                    val args = buildJsonObject { put("name", name) }.toString()
                    val calls = buildJsonArray { add(buildJsonObject {
                        put("index", 0); put("id", "load-context"); put("type", "function")
                        put("function", buildJsonObject { put("name", tool); put("arguments", args) })
                    }) }
                    provider.event("""{"choices":[{"index":0,"delta":{"tool_calls":$calls},"finish_reason":"tool_calls"}]}""") + "data: [DONE]\n\n"
                } else provider.event("""{"choices":[{"index":0,"delta":{"content":"Done"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n"
            }
            try {
                runBlocking { agent.chat("Load the $tool named $name", provider.config, maxTurns = 3) }
                assertEquals(2, provider.requests.size)
                fun definition(index: Int) = provider.requests[index].getValue("tools").jsonArray
                    .map { it.jsonObject.getValue("function").jsonObject }.first { it["name"] == JsonPrimitive(tool) }
                fun nameSchema(index: Int) = definition(index).getValue("parameters").jsonObject.getValue("properties").jsonObject.getValue("name").jsonObject
                assertNull("First request must not forbid a still-loadable entry", nameSchema(0)["enum"])
                assertTrue(definition(0).getValue("description").jsonPrimitive.content.contains("Additional entries exist"))
                assertFalse(provider.requests[0].toString().contains(body))
                assertTrue(nameSchema(1).getValue("enum").jsonArray.contains(JsonPrimitive(name)))
                assertFalse(definition(1).getValue("description").jsonPrimitive.content.contains("Additional entries exist"))
                val result = agent.boundedApiHistory().last { it["role"] == JsonPrimitive("tool") }.getValue("content").jsonPrimitive.content
                assertTrue(result.contains(body)); assertFalse(agent.busy.value)
                assertTrue(provider.requests.all { it.toString().toByteArray(Charsets.UTF_8).size < 150_000 })
            } finally { agent.shutdown() }
        }
    }

    @Test(timeout = 30_000) fun agentCanLoadSkillOutsideDisplayWindowAndRefreshCompleteness() = runBlocking {
        val root = Files.createTempDirectory("droide-skill-window-").toFile()
        try {
            val files = (0..80).associate { n ->
                val name = "s$n"
                name to root.resolve(".droide/skills/$name/SKILL.md").apply {
                    parentFile.mkdirs(); writeText("---\nname: $name\ndescription: $name\n---\nPRIVATE_SKILL_BODY_$name")
                }
            }
            val target = SkillManager(root).catalog(81).last().name
            scenario(root, "skill", target, files.filterKeys { it != target }.values.first(), "PRIVATE_SKILL_BODY_$target")
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test(timeout = 30_000) fun agentCanLoadRuleOutsideDisplayWindowAndRefreshCompleteness() = runBlocking {
        val root = Files.createTempDirectory("droide-rule-window-").toFile()
        try {
            val plugins = (0..2).map { n ->
                val dir = root.resolve("plugin$n").apply { mkdir(); resolve("rules").mkdir() }
                AgentPluginContributionRoot("p$n", "Plugin", "1", "", AgentPluginFormat.DROIDE, dir, "a".repeat(64))
            }
            val files = (0..64).map { n -> plugins[n / 32].root.resolve("rules/r$n.md").apply {
                writeText("---\nname: r$n\ndescription: r$n\n---\nPRIVATE_RULE_BODY_$n")
            } }
            scenario(root, "rule", "p2:r64", files.first(), "PRIVATE_RULE_BODY_64", AgentPluginSource { plugins })
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
