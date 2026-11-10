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
class AgentLspResultRunWorkflowTest {
    @get:Rule val applicationWork: TestRule = ApplicationWorkAdmissionFixture()

    @Test(timeout = 30_000) fun actualAgentRunSharesPagesAndNextRunCannotReplayOldResult() = runBlocking {
        LspDeliveryFixture().use { f ->
            f.text = "x".repeat(6_001) + "FINAL_TAIL"
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { provider ->
                val agent = AgentService(FileRepository(f.root), AgentWorkflowTerminal(), GitManager(f.root),
                    lsp = f.manager, perms = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), maxSubagentDepth = 0)
                var oldPage: JsonObject? = null
                fun tool(arguments: JsonObject, id: String): String {
                    val calls = buildJsonArray { add(buildJsonObject {
                        put("index", 0); put("id", id); put("type", "function")
                        put("function", buildJsonObject { put("name", "lsp"); put("arguments", arguments.toString()) })
                    }) }
                    return provider.event("""{"choices":[{"index":0,"delta":{"tool_calls":$calls},"finish_reason":"tool_calls"}]}""") + "data: [DONE]\n\n"
                }
                provider.response = { call, _, _ -> when (call) {
                    1 -> tool(lspDeliveryArgs(), "lsp-first")
                    2 -> {
                        val output = agent.boundedApiHistory().last { it["role"] == JsonPrimitive("tool") }
                            .getValue("content").jsonPrimitive.content
                        oldPage = lspDeliveryMetadata(output)
                        tool(lspDeliveryArgs(oldPage), "lsp-next")
                    }
                    4 -> tool(lspDeliveryArgs(requireNotNull(oldPage)), "lsp-old-run")
                    else -> provider.event("""{"choices":[{"index":0,"delta":{"content":"Done"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n"
                } }
                try {
                    runBlocking {
                        agent.chat("Read the complete LSP response for main.kt", provider.config, maxTurns = 3)
                        val results = agent.boundedApiHistory().filter { it["role"] == JsonPrimitive("tool") }
                        assertEquals(2, results.size)
                        assertTrue(results.last().getValue("content").jsonPrimitive.content.contains("FINAL_TAIL"))
                        assertEquals(1, f.calls)
                        agent.chat("Try the previous response ID in this new run", provider.config, maxTurns = 2)
                        val last = agent.boundedApiHistory().last { it["role"] == JsonPrimitive("tool") }
                            .getValue("content").jsonPrimitive.content
                        assertTrue(last.contains("expired or belongs to another run"))
                        assertFalse(last.contains("FINAL_TAIL")); assertEquals(1, f.calls)
                        assertFalse(agent.busy.value)
                    }
                } finally { agent.shutdown() }
            }
        }
    }
}
