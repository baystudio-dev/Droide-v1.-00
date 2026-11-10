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
class CustomToolMetadataRunTest {
    @get:Rule val applicationWork: TestRule = ApplicationWorkAdmissionFixture()

    @Test(timeout = 30_000) fun oversizedMetadataIsDisclosedAndApprovedToolResultReachesNextRequest() = runBlocking {
        val root = Files.createTempDirectory("droide-custom-run-").toFile()
        root.resolve(".droide/tools/demo.sh").apply {
            parentFile.mkdirs(); writeText("# desc:" + "x".repeat(20_000) + "\nPRIVATE_SCRIPT_BODY")
        }
        val calls = mutableListOf<List<String>>()
        val terminal = object : ITerminalSession by AgentWorkflowTerminal() {
            override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult {
                calls += argv; assertEquals(0L, timeoutMs)
                return ExecResult(0, "CUSTOM_TOOL_RESULT", false)
            }
        }
        try {
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { provider ->
                val agent = AgentService(FileRepository(root), terminal, GitManager(root),
                    perms = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), maxSubagentDepth = 0)
                provider.response = { call, _, _ ->
                    if (call == 1) {
                        val args = buildJsonObject { put("input", "literal ; input") }.toString()
                        val tools = buildJsonArray { add(buildJsonObject {
                            put("index", 0); put("id", "custom-one"); put("type", "function")
                            put("function", buildJsonObject { put("name", "custom_demo"); put("arguments", args) })
                        }) }
                        provider.event("""{"choices":[{"index":0,"delta":{"tool_calls":$tools},"finish_reason":"tool_calls"}]}""") + "data: [DONE]\n\n"
                    } else provider.event("""{"choices":[{"index":0,"delta":{"content":"Done"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n"
                }
                try {
                    runBlocking { agent.chat("Run the demo tool", provider.config, maxTurns = 3) }
                    assertEquals(2, provider.requests.size)
                    val custom = provider.requests[0].getValue("tools").jsonArray.map { it.jsonObject.getValue("function").jsonObject }
                        .single { it["name"] == JsonPrimitive("custom_demo") }
                    assertTrue(custom.getValue("description").jsonPrimitive.content.contains("[Description omitted:"))
                    assertFalse(provider.requests[0].toString().contains("PRIVATE_SCRIPT_BODY"))
                    val history = agent.boundedApiHistory().last { it["role"] == JsonPrimitive("tool") }.getValue("content").jsonPrimitive.content
                    val delivered = provider.requests[1].getValue("messages").jsonArray.map { it.jsonObject }
                        .last { it["role"] == JsonPrimitive("tool") }.getValue("content").jsonPrimitive.content
                    assertEquals(history, delivered); assertTrue(delivered.contains("CUSTOM_TOOL_RESULT"))
                    assertEquals(listOf(listOf("sh", ".droide/tools/demo.sh", "literal ; input")), calls)
                    assertFalse(agent.busy.value)
                } finally { agent.shutdown() }
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
