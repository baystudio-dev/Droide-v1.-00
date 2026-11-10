package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tests request routing, permissions and history against fixture HTTP, not live model obedience. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentIntentWorkflowTest {
    @org.junit.Before fun openAuthoredUserLaunch() {
        ApplicationWorkAdmission.initialize(org.robolectric.RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
    }

    @Test(timeout = 30_000) fun aTextOnlyModelAnswersAQuestionWithoutAnExhaustedTaskHandoff() = fixture { agent, http, root ->
        textOnly(http)
        root.resolve("keep.txt").writeText("user source")
        http.response = { _, _, _ -> reply(http, "A compiler translates a program before execution.") }
        chat(agent, http, "Apa perbedaan compiler dan interpreter?")
        assertEquals(1, http.requests.size)
        assertFalse(http.requests.single().containsKey("tools"))
        val system = messages(http.requests.single()).first().jsonObject["content"]!!.jsonPrimitive.content
        assertFalse(system.contains("FINAL AGENT STEP:"))
        assertEquals("user source", root.resolve("keep.txt").readText())
    }

    @Test(timeout = 30_000) fun anUnsolicitedTextOnlyModelToolCannotWriteOrInventAStepLimitFailure() = fixture { agent, http, root ->
        textOnly(http)
        root.resolve("keep.txt").writeText("user source")
        http.response = { _, _, _ -> write(http, "keep.txt", "unadvertised write") }
        chat(agent, http, "Create keep.txt")
        assertEquals("user source", root.resolve("keep.txt").readText())
        assertEquals(1, http.requests.size)
        assertFalse(http.requests.single().containsKey("tools"))
        val text = agent.messages.value.joinToString("\n") { it.content }
        assertTrue(text.contains("does not support tools"))
        assertFalse(text.contains("step limit"))
    }

    @Test(timeout = 30_000) fun explanationThenExplicitImplementationUsesOneSessionWithoutAClassifierRoundTrip() = fixture { agent, http, root ->
        val question = "Jelaskan cara membuat parser. Jangan edit file dan jangan build dulu."
        http.response = { number, _, _ -> when (number) {
            2 -> write(http, "parser.txt", "authorized implementation")
            else -> reply(http, "Explanation only; no build was run.")
        } }
        chat(agent, http, question)
        val session = agent.currentSessionId
        assertEquals(1, http.requests.size)
        assertFalse(root.resolve("parser.txt").exists())
        chat(agent, http, "Sekarang buat parser.txt sesuai penjelasan tadi, tetap jangan build.")
        assertEquals(3, http.requests.size)
        assertEquals(session, agent.currentSessionId)
        assertEquals(1, runBlocking { agent.sessions.count() })
        assertEquals("authorized implementation", root.resolve("parser.txt").readText())
        assertTrue(messages(http.requests[1]).any { it.jsonObject["role"] == JsonPrimitive("user") &&
            it.jsonObject["content"]!!.jsonPrimitive.content.contains(question) })
    }

    @Test(timeout = 30_000) fun readOnlyReviewRejectsAModelWriteEvenWithBroadStoredPermissions() = fixture { agent, http, root ->
        root.resolve("keep.txt").writeText("original source")
        agent.setMode(AgentMode.REVIEW)
        http.response = { number, _, _ -> if (number == 1) write(http, "keep.txt", "unrequested edit") else reply(http, "Review only") }
        chat(agent, http, "Review keep.txt dan laporkan masalahnya; jangan ubah file.")
        val tools = http.requests.first()["tools"]!!.jsonArray.map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }
        assertFalse("write_file" in tools)
        assertEquals("original source", root.resolve("keep.txt").readText())
        assertEquals(AgentMode.REVIEW, agent.mode.value)
    }

    @Test(timeout = 30_000) fun steeringDuringModelGenerationPreventsItsStaleWriteBeforeDispatch() = fixture { agent, http, root ->
        root.resolve("keep.txt").writeText("user source")
        val correction = "Jangan edit lagi. Jelaskan rencananya saja."
        http.response = { number, _, _ -> if (number == 1) {
            assertTrue(runBlocking { agent.steerActive(correction, http.config, agent.currentSessionId!!) })
            write(http, "keep.txt", "stale model write")
        } else reply(http, "I will explain only.") }
        chat(agent, http, "Update keep.txt")
        assertEquals("user source", root.resolve("keep.txt").readText())
        assertEquals(2, http.requests.size)
        val second = messages(http.requests[1])
        assertTrue(second.any { it.jsonObject["role"] == JsonPrimitive("tool") &&
            it.jsonObject["content"]!!.jsonPrimitive.content.contains("USER_STEER_PENDING") })
        val skipped = second.first { it.jsonObject["role"] == JsonPrimitive("tool") }.jsonObject["content"]!!.jsonPrimitive.content
        assertEquals(false, AgentToolResultSemantics.parseAnnotation(skipped)!!.operationStarted)
        assertEquals(false, AgentToolResultSemantics.parseAnnotation(skipped)!!.success)
        assertTrue(second.any { it.jsonObject["role"] == JsonPrimitive("user") &&
            it.jsonObject["content"]!!.jsonPrimitive.content.contains(correction) })
        assertTrue(agent.pendingInputs.value.isEmpty())
        assertFalse(agent.busy.value)
    }

    @Test(timeout = 30_000) fun queuedFollowUpDoesNotPreemptWorkTheUserAlreadyAuthorized() = fixture { agent, http, root ->
        http.response = { number, _, _ -> if (number == 1) {
            runBlocking { agent.submitAsync(this, "Setelah selesai, jelaskan hasilnya.", http.config,
                delivery = AgentPromptDelivery.QUEUE, maxTurns = 3)!!.join() }
            write(http, "requested.txt", "authorized source")
        } else reply(http, "Complete") }
        chat(agent, http, "Buat requested.txt")
        assertEquals("authorized source", root.resolve("requested.txt").readText())
        assertEquals(3, http.requests.size)
        assertFalse(http.requests.any { it.toString().contains("USER_STEER_PENDING") })
        assertTrue(agent.pendingInputs.value.isEmpty())
        assertEquals(1, runBlocking { agent.sessions.count() })
    }

    @Test(timeout = 30_000) fun compactedProposalsAndRestrictionsRemainHistoricalAndResumeInTheSameSession() = fixture { agent, http, root ->
        val manager = agent.sessions
        val history = listOf(
            entry("user", "Audit only; do not edit or build until I ask.\n" + "context ".repeat(900)),
            entry("assistant", "Proposed migration only, not approved.\n" + "evidence ".repeat(700)),
            entry("user", "What are the tradeoffs?"),
        )
        val session = runBlocking { manager.create("Read-only discussion").copy(apiHistory = history,
            forkHistory = history, forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION).also { manager.save(it) } }
        val summary = "Active: read-only audit. Do not edit or build until the user asks. Migration was proposed, not authorized."
        http.response = { _, _, streaming -> if (streaming) reply(http, "Continuing the review") else buildJsonObject {
            put("choices", buildJsonArray { add(buildJsonObject { put("message", entry("assistant", summary)); put("finish_reason", "stop") }) })
        }.toString() }
        val compact = runBlocking { manager.compact(session, http.config, keepRecentTokens = 1000) }
        assertTrue(compact.changed)
        val checkpoint = compact.session.apiHistory.first()
        assertEquals(JsonPrimitive("assistant"), checkpoint["role"])
        val text = checkpoint["content"]!!.jsonPrimitive.content
        assertTrue(text.contains(summary))
        assertFalse(text.contains("older completed context"))
        assertEquals(history.last(), compact.session.apiHistory.last())
        val restored = runBlocking { manager.getForUpdate(session.id)!! }
        assertEquals(history, restored.forkHistory)
        runBlocking { agent.loadSession(restored) }
        root.resolve("keep.txt").writeText("user source")
        chat(agent, http, "Lanjutkan review saja.")
        assertEquals(2, http.requests.size)
        assertEquals(session.id, agent.currentSessionId)
        assertEquals("user source", root.resolve("keep.txt").readText())
        assertTrue(messages(http.requests.last()).any { it == checkpoint })
    }

    private fun fixture(block: (AgentService, ProviderStreamFixture, File) -> Unit) {
        val root = Files.createTempDirectory("droide-intent-workflow-").toFile()
        try {
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { http ->
                val agent = AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root),
                    perms = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), maxSubagentDepth = 0)
                try { block(agent, http, root) } finally { agent.shutdown() }
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private fun textOnly(http: ProviderStreamFixture) {
        ModelCapabilityRegistry.installRuntime(ModelCapabilities(http.provider.id, http.model, http.model,
            attachment = null, reasoning = null, toolCall = false, structuredOutput = null, temperature = null,
            inputModalities = setOf("text"), outputModalities = setOf("text"), reasoningEfforts = emptySet(),
            contextTokens = null, outputTokens = null, source = ModelCapabilitySource.RUNTIME_PROVIDER))
    }

    private fun chat(agent: AgentService, http: ProviderStreamFixture, text: String) = runBlocking {
        withTimeout(15_000) { agent.chat(text, http.config, maxTurns = 4) }
    }
    private fun messages(request: JsonObject) = request["messages"]!!.jsonArray
    private fun entry(role: String, text: String) = buildJsonObject { put("role", role); put("content", text) }
    private fun reply(http: ProviderStreamFixture, text: String) = http.event(buildJsonObject {
        put("choices", buildJsonArray { add(buildJsonObject {
            put("index", 0); put("delta", buildJsonObject { put("content", text) }); put("finish_reason", "stop")
        }) })
    }.toString()) + "data: [DONE]\n\n"
    private fun write(http: ProviderStreamFixture, path: String, content: String): String {
        val calls = buildJsonArray { add(buildJsonObject {
            put("index", 0); put("id", "intent-write"); put("type", "function")
            put("function", buildJsonObject {
                put("name", "write_file"); put("arguments", buildJsonObject { put("path", path); put("content", content) }.toString())
            })
        }) }
        return http.event("""{"choices":[{"index":0,"delta":{"tool_calls":$calls},"finish_reason":"tool_calls"}]}""") + "data: [DONE]\n\n"
    }
}
