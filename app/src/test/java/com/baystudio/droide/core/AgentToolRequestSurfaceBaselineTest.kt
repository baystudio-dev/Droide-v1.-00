package com.baystudio.droide.core

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Current API baseline. Exact literal markers are interpreted only; no process backend exists. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentToolRequestSurfaceBaselineTest {
    @get:org.junit.Rule val applicationAdmission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    private class RecordingTerminal : ITerminalSession {
        val calls = AtomicInteger()
        val forbiddenCalls = AtomicInteger()
        override val output = MutableStateFlow("")
        private fun forbidden(): Nothing {
            forbiddenCalls.incrementAndGet()
            throw AssertionError("No real process/backend is permitted")
        }
        override fun start() = forbidden()
        override fun send(cmd: String) = forbidden()
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult {
            if (cmd != "request-policy-fixture") forbidden()
            calls.incrementAndGet()
            return ExecResult(0, "{}", false)
        }
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult = forbidden()
        override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult = forbidden()
        override suspend fun execStreamingWithInput(cmd: String, timeoutMs: Long, input: ProcessInputBuffer,
            onOutput: (String) -> Unit): ExecResult = forbidden()
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun destroy() = Unit
    }

    // The absent donor production helper must not be introduced by this baseline.
    private fun advertised(request: JsonObject): Set<String> =
        (request["tools"] as? JsonArray)?.let(AgentRuntimePrompt::toolNames)?.toSet().orEmpty()

    private fun call(index: Int, name: String, args: JsonObject) = buildJsonObject {
        put("index", index); put("id", "call-$index"); put("type", "function")
        put("function", buildJsonObject { put("name", name); put("arguments", args.toString()) })
    }

    @Test(timeout = 30_000) fun omittedAndUnknownToolsCannotRunLifecycleHooksButAnAdvertisedAliasStillWorks() {
        val root = Files.createTempDirectory("droide-request-tool-surface-").toFile()
        val terminal = RecordingTerminal()
        try {
            root.resolve("keep.txt").writeText("existing user text")
            root.resolve(".droide").mkdirs()
            root.resolve(".droide/hooks.json").writeText("""{"enabled":true,"events":{"PreToolUse":[{"command":"request-policy-fixture"}],"PostToolUse":[{"command":"request-policy-fixture"}]}}""")
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, authRequired = false) { fixture ->
                val agent = AgentService(FileRepository(root), terminal, GitManager(root),
                    perms = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), maxSubagentDepth = 0)
                fixture.response = { turn, _, _ -> if (turn == 1) {
                    val calls = JsonArray(listOf(call(0, "not_a_tool", buildJsonObject {}),
                        call(1, "ide", buildJsonObject { put("operation", "status") }),
                        call(2, "read", buildJsonObject { put("path", "keep.txt") })))
                    fixture.event("""{"choices":[{"index":0,"delta":{"tool_calls":$calls},"finish_reason":"tool_calls"}]}""") + "data: [DONE]\n\n"
                } else fixture.event("""{"choices":[{"index":0,"delta":{"content":"Read complete"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n" }
                try {
                    runBlocking { withTimeout(15_000) { agent.chat("Read keep.txt", fixture.config, maxTurns = 2) } }
                    val names = advertised(fixture.requests.first())
                    assertFalse("ide" in names)
                    assertTrue("read" in names)
                    assertEquals(2, fixture.requests.size)
                    assertEquals(0, terminal.forbiddenCalls.get())
                    assertEquals("existing user text", root.resolve("keep.txt").readText())
                    assertEquals("Only the admitted read gets pre/post hooks", 2, terminal.calls.get())
                    val saved = runBlocking { agent.sessions.get(agent.currentSessionId!!)!! }
                    val results = saved.apiHistory.filter { it["role"] == JsonPrimitive("tool") }
                    assertEquals(3, results.size)
                    for (id in listOf("call-0", "call-1")) assertTrue(results.single { it["tool_call_id"] == JsonPrimitive(id) }
                        .getValue("content").jsonPrimitive.content.contains("DENIED (request tool surface)"))
                    assertTrue(results.single { it["tool_call_id"] == JsonPrimitive("call-2") }
                        .getValue("content").jsonPrimitive.content.contains("existing user text"))
                    assertEquals("existing user text", root.resolve("keep.txt").readText())
                } finally { agent.shutdown() }
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test(timeout = 30_000) fun laterCapabilityExpansionAppliesOnlyToTheNextActualRequest() {
        val root = Files.createTempDirectory("droide-request-tool-refresh-").toFile()
        try {
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, authRequired = false) { fixture ->
                val terminal = RecordingTerminal()
                val agent = AgentService(FileRepository(root), terminal, GitManager(root),
                    perms = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), maxSubagentDepth = 0)
                agent.setMode(AgentMode.PLAN)
                fixture.response = { turn, _, _ -> if (turn <= 2) {
                    if (turn == 1) agent.setMode(AgentMode.BUILD)
                    val calls = JsonArray(listOf(call(turn, "write_file", buildJsonObject {
                        put("path", if (turn == 1) "unadvertised.txt" else "requested.txt"); put("content", "requested text")
                    })))
                    fixture.event("""{"choices":[{"index":0,"delta":{"tool_calls":$calls},"finish_reason":"tool_calls"}]}""") + "data: [DONE]\n\n"
                } else fixture.event("""{"choices":[{"index":0,"delta":{"content":"Complete"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n" }
                try {
                    runBlocking { withTimeout(15_000) { agent.chat("Create requested.txt once BUILD is selected", fixture.config, maxTurns = 3) } }
                    assertFalse("write_file" in advertised(fixture.requests[0]))
                    assertTrue("write_file" in advertised(fixture.requests[1]))
                    assertEquals(3, fixture.requests.size)
                    assertEquals(0, terminal.forbiddenCalls.get())
                    assertEquals(0, terminal.calls.get())
                    assertFalse("An omitted tool cannot borrow a later BUILD selection", root.resolve("unadvertised.txt").exists())
                    assertEquals("requested text", root.resolve("requested.txt").readText())
                } finally { agent.shutdown() }
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
