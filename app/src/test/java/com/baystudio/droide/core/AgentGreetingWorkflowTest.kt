package com.baystudio.droide.core

import android.app.Application
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs the real AgentService and HTTP stream transport; these are not live-provider tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AgentGreetingWorkflowTest {
    @get:Rule val applicationWork: TestRule = ApplicationWorkAdmissionFixture()

    @Test(timeout = 30_000) fun initialGreetingRejectsEvenAnUnsolicitedModelWriteWithBroadPermissions() = withAgent { agent, fixture, root ->
        root.resolve("keep.txt").writeText("user source")
        fixture.response = { _, _, _ -> writeResponse(fixture, "keep.txt", "unsolicited overwrite") }
        runBlocking { withTimeout(15_000) {
            agent.chat("hai", fixture.config, maxTurns = 3,
                ideContext = AgentIdeContextSnapshot(openFiles = listOf("keep.txt"),
                    problems = listOf(AgentIdeProblemContext("keep.txt", 1, 1, 1, "Example diagnostic"))))
        } }
        assertEquals("user source", root.resolve("keep.txt").readText())
        assertEquals(1, fixture.requests.size)
        assertFalse(fixture.requests.single().containsKey("tools"))
        assertTrue(system(fixture.requests.single()).startsWith(AgentSystemPrompt.GREETING_TEXT))
        assertTrue(fixture.requests.single().toString().contains("initial standalone greeting"))
        val saved = runBlocking { agent.sessions.get(agent.currentSessionId!!)!! }
        assertFalse(saved.apiHistory.any { it["role"] == JsonPrimitive("tool") })
        assertFalse(agent.busy.value)
    }

    @Test(timeout = 30_000) fun greetingWithExplicitTaskStillExecutesAuthorizedWork() = withAgent { agent, fixture, root ->
        fixture.response = { call, _, _ -> if (call == 1) writeResponse(fixture, "requested.txt", "requested source") else reply(fixture, "Done") }
        runBlocking { withTimeout(15_000) { agent.chat("Hi, create requested.txt", fixture.config, maxTurns = 3) } }
        assertTrue(fixture.requests.first().containsKey("tools"))
        assertTrue(system(fixture.requests.first()).startsWith(AgentSystemPrompt.forTools(AgentToolRequestPolicy.snapshot(fixture.requests.first()))))
        assertEquals("requested source", root.resolve("requested.txt").readText())
        assertEquals(2, fixture.requests.size)
    }

    @Test(timeout = 30_000) fun nextTaskAfterGreetingUsesTheSameSessionWithToolsRestored() = withAgent { agent, fixture, root ->
        fixture.response = { call, _, _ -> if (call == 2) writeResponse(fixture, "later.txt", "later task") else reply(fixture, "Hello") }
        runBlocking { withTimeout(15_000) { agent.chat("hello", fixture.config, maxTurns = 3) } }
        val session = agent.currentSessionId
        runBlocking { withTimeout(15_000) { agent.chat("Create later.txt", fixture.config, maxTurns = 3) } }
        assertFalse(fixture.requests[0].containsKey("tools"))
        assertTrue(fixture.requests[1].containsKey("tools"))
        assertTrue(system(fixture.requests[0]).startsWith(AgentSystemPrompt.GREETING_TEXT))
        assertTrue(system(fixture.requests[1]).startsWith(AgentSystemPrompt.forTools(AgentToolRequestPolicy.snapshot(fixture.requests[1]))))
        assertEquals(session, agent.currentSessionId)
        assertEquals(1, runBlocking { agent.sessions.count() })
        assertEquals("later task", root.resolve("later.txt").readText())
    }

    @Test(timeout = 30_000) fun steerPromotedDuringGreetingRestoresToolsBeforeTheNextModelRequest() = withAgent { agent, fixture, root ->
        fixture.response = { call, _, _ -> when (call) {
            1 -> {
                assertTrue(runBlocking { agent.steerActive("Create steered.txt", fixture.config, agent.currentSessionId!!) })
                reply(fixture, "Hello")
            }
            2 -> writeResponse(fixture, "steered.txt", "steered task")
            else -> reply(fixture, "Done")
        } }
        runBlocking { withTimeout(15_000) { agent.chat("hi", fixture.config, maxTurns = 4) } }
        assertFalse(fixture.requests[0].containsKey("tools"))
        assertTrue(fixture.requests[1].containsKey("tools"))
        assertTrue(system(fixture.requests[0]).startsWith(AgentSystemPrompt.GREETING_TEXT))
        assertTrue(system(fixture.requests[1]).startsWith(AgentSystemPrompt.forTools(AgentToolRequestPolicy.snapshot(fixture.requests[1]))))
        assertEquals("steered task", root.resolve("steered.txt").readText())
        assertEquals(3, fixture.requests.size)
        assertTrue(agent.pendingInputs.value.isEmpty())
    }

    @Test(timeout = 30_000) fun queuedTaskAfterGreetingRestoresFullPromptAndTools() = queuedGreeting(false)

    @Test(timeout = 30_000) fun queuedTaskAfterRejectedGreetingToolRestoresFullPromptAndTools() = queuedGreeting(true)

    private fun queuedGreeting(unsolicitedWrite: Boolean) = withAgent { agent, fixture, root ->
        root.resolve("keep.txt").writeText("user source")
        var admittedSession: String? = null
        fixture.response = { step, _, _ -> when (step) {
            1 -> {
                admittedSession = agent.currentSessionId!!
                runBlocking { withTimeout(5_000) {
                    val admission = agent.submitAsync(this, "Create queued.txt", fixture.config,
                        delivery = AgentPromptDelivery.QUEUE, maxTurns = 6, requiredSessionId = admittedSession)
                    assertNotNull(admission)
                    admission!!.join()
                } }
                if (unsolicitedWrite) writeResponse(fixture, "keep.txt", "unrequested overwrite") else reply(fixture, "Hello")
            }
            2 -> writeResponse(fixture, "queued.txt", "queued task")
            3 -> reply(fixture, "Done")
            else -> error("Unexpected model step $step")
        } }
        runBlocking { withTimeout(15_000) { agent.chat("hi", fixture.config, maxTurns = 6) } }
        assertEquals(3, fixture.requests.size)
        assertEquals("user source", root.resolve("keep.txt").readText())
        assertEquals("queued task", root.resolve("queued.txt").readText())
        assertEquals(admittedSession, agent.currentSessionId)
        assertEquals(1, runBlocking { agent.sessions.count() })
        assertFalse(fixture.requests[0].containsKey("tools"))
        assertTrue(system(fixture.requests[0]).startsWith(AgentSystemPrompt.GREETING_TEXT))
        assertTrue(fixture.requests[1].containsKey("tools"))
        assertTrue(system(fixture.requests[1]).startsWith(AgentSystemPrompt.forTools(AgentToolRequestPolicy.snapshot(fixture.requests[1]))))
        val nextHistory = fixture.requests[1].getValue("messages").jsonArray.drop(1).map { it.jsonObject }
        assertTrue(nextHistory.any { it["role"] == JsonPrimitive("user") &&
            it["content"]?.jsonPrimitive?.content?.contains("Create queued.txt") == true })
        assertFalse(nextHistory.any { it["role"] == JsonPrimitive("tool") || it.containsKey("tool_calls") })
        assertTrue(agent.pendingInputs.value.isEmpty())
        assertFalse(agent.busy.value)
    }

    private fun system(request: JsonObject) = request.getValue("messages").jsonArray.first().jsonObject
        .getValue("content").jsonPrimitive.content

    private fun withAgent(block: (AgentService, ProviderStreamFixture, java.io.File) -> Unit) {
        val root = Files.createTempDirectory("droide-agent-greeting-").toFile()
        try {
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, authRequired = false) { fixture ->
                fixture.secret = ""
                val terminal = NoProcessTerminal()
                val agent = AgentService(FileRepository(root), terminal, GitManager(root),
                    perms = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), maxSubagentDepth = 0)
                try { block(agent, fixture, root) } finally {
                    try { agent.shutdown() } finally { assertEquals("No process backend is permitted", 0, terminal.executionCalls.get()) }
                }
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    /** This greeting/write fixture must never execute a process, even after a regression. */
    private class NoProcessTerminal : ITerminalSession {
        override val output = MutableStateFlow("")
        override val supportsCommandInput = true
        val executionCalls = AtomicInteger()
        private fun forbidden(): Nothing {
            executionCalls.incrementAndGet()
            throw AssertionError("Process execution is forbidden in greeting fixtures")
        }
        override fun start() = forbidden()
        override fun send(cmd: String) = forbidden()
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = forbidden()
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult = forbidden()
        override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult = forbidden()
        override suspend fun execStreamingWithInput(cmd: String, timeoutMs: Long, input: ProcessInputBuffer,
            onOutput: (String) -> Unit): ExecResult = forbidden()
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun destroy() = Unit
    }

    private fun reply(fixture: ProviderStreamFixture, text: String): String = fixture.event(buildJsonObject {
        put("choices", buildJsonArray { add(buildJsonObject {
            put("index", 0); put("delta", buildJsonObject { put("content", text) }); put("finish_reason", "stop")
        }) })
    }.toString()) + "data: [DONE]\n\n"

    private fun writeResponse(fixture: ProviderStreamFixture, path: String, content: String): String {
        val calls = buildJsonArray { add(buildJsonObject {
            put("index", 0); put("id", "requested-write"); put("type", "function")
            put("function", buildJsonObject {
                put("name", "write_file")
                put("arguments", buildJsonObject { put("path", path); put("content", content) }.toString())
            })
        }) }
        return fixture.event("""{"choices":[{"index":0,"delta":{"tool_calls":$calls},"finish_reason":"tool_calls"}]}""") + "data: [DONE]\n\n"
    }
}
