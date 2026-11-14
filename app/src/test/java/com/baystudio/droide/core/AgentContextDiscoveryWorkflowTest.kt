package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual AgentService -> provider wire -> tool admission, with a loopback model fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentContextDiscoveryWorkflowTest {
    @get:Rule val admission: TestRule = ApplicationWorkAdmissionFixture()

    @Test fun discoveredToolIsAdvertisedOnNextRequestAndRunsOnce() = scenario(discover = true, revoke = false)
    @Test fun providerCannotCallAnUnadvertisedDeferredTool() = scenario(discover = false, revoke = false)
    @Test fun modeChangeAfterDiscoveryStillRejectsExecution() = scenario(discover = true, revoke = true)

    private fun scenario(discover: Boolean, revoke: Boolean) = runBlocking {
        val root = Files.createTempDirectory("context-discovery-").toFile()
        var calls = 0
        try {
            root.resolve("app.kt").writeText("fun main() = Unit")
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, authRequired = false) { fixture ->
                val lsp = object : LspManager {
                    override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
                    override suspend fun diagnose(relPath: String): String { calls++; return "No diagnostics returned for $relPath" }
                    override suspend fun lsp(op: String, path: String, line: Int, col: Int) = ""
                }
                val agent = AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root), lsp = lsp,
                    perms = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), maxSubagentDepth = 0)
                fixture.response = { step, _, _ ->
                    val callStep = if (discover) 2 else 1
                    when {
                        discover && step == 1 -> response(fixture, "tool_search", buildJsonObject { put("query", "diagnose"); put("limit", 1) }, "search")
                        step == callStep -> {
                            if (revoke) agent.setMode(AgentMode.EXPLORE)
                            response(fixture, "diagnose", buildJsonObject { put("path", "app.kt") }, "diagnose")
                        }
                        else -> fixture.event("""{"choices":[{"delta":{"content":"Finished"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n"
                    }
                }
                try {
                    runBlocking { agent.chat("Inspect diagnostics for app.kt", fixture.config, maxTurns = 5) }
                    val first = AgentToolRequestPolicy.snapshot(fixture.requests.first())
                    assertTrue("tool_search" in first); assertFalse("diagnose" in first)
                    if (discover) assertTrue("diagnose" in AgentToolRequestPolicy.snapshot(fixture.requests[1]))
                    assertEquals(if (discover && !revoke) 1 else 0, calls)
                    assertFalse(agent.busy.value)
                    val saved = agent.boundedApiHistory()
                    assertEquals(saved, AgentHistory.requireCompleteGroups(saved).flatten())
                } finally { agent.shutdown() }
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private fun response(fixture: ProviderStreamFixture, name: String, args: JsonObject, id: String): String {
        val calls = buildJsonArray { add(buildJsonObject {
            put("index", 0); put("id", id); put("type", "function")
            put("function", buildJsonObject { put("name", name); put("arguments", args.toString()) })
        }) }
        return fixture.event("""{"choices":[{"delta":{"tool_calls":$calls},"finish_reason":"tool_calls"}]}""") + "data: [DONE]\n\n"
    }
}
