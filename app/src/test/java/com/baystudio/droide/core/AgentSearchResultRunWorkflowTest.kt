package com.baystudio.droide.core

import kotlinx.coroutines.*
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
class AgentSearchResultRunWorkflowTest {
    @get:Rule val applicationWork: TestRule = ApplicationWorkAdmissionFixture()

    @Test(timeout = 30_000) fun actualAgentRunSharesPagesAndNextRunCannotReplayOldResult() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("droide-search-run-").toFile()
        try {
            root.resolve("a.kt").writeText(searchPageText(70))
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { provider ->
                val agent = AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root),
                    perms = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), maxSubagentDepth = 0)
                var oldPage: JsonObject? = null
                fun tool(arguments: JsonObject, id: String): String {
                    val calls = buildJsonArray { add(buildJsonObject {
                        put("index", 0); put("id", id); put("type", "function")
                        put("function", buildJsonObject { put("name", "search_files"); put("arguments", arguments.toString()) })
                    }) }
                    return provider.event("""{"choices":[{"index":0,"delta":{"tool_calls":$calls},"finish_reason":"tool_calls"}]}""") + "data: [DONE]\n\n"
                }
                provider.response = { call, _, _ -> when (call) {
                    1 -> tool(searchPageArgs(), "search-first")
                    2 -> {
                        val output = agent.boundedApiHistory().last { it["role"] == JsonPrimitive("tool") }
                            .getValue("content").jsonPrimitive.content
                        oldPage = searchPageMetadata(output)
                        tool(searchPageArgs(oldPage), "search-next")
                    }
                    4 -> tool(searchPageArgs(requireNotNull(oldPage)), "search-old-run")
                    else -> provider.event("""{"choices":[{"index":0,"delta":{"content":"Done"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n"
                } }
                try {
                    runBlocking {
                        agent.chat("Read all search results for needle in this workspace", provider.config, maxTurns = 3)
                        val results = agent.boundedApiHistory().filter { it["role"] == JsonPrimitive("tool") }
                        assertEquals(2, results.size)
                        assertTrue(results.last().getValue("content").jsonPrimitive.content.contains("item-70"))
                        agent.chat("Try the previous response ID in this new run", provider.config, maxTurns = 2)
                        val last = agent.boundedApiHistory().last { it["role"] == JsonPrimitive("tool") }
                            .getValue("content").jsonPrimitive.content
                        assertTrue(last.contains("expired or belongs to another run"))
                        assertFalse(last.contains("item-70"))
                        assertFalse(agent.busy.value)
                    }
                } finally { agent.shutdown() }
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
