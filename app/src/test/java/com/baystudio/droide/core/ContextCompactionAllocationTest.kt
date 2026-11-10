package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContextCompactionAllocationTest {
    @get:Rule val tmp = TemporaryFolder()
    private val provider = "compaction.allocation.fixture"
    private val model = "model"
    @After fun clear() { ModelCapabilityRegistry.revokeRuntime(provider) }
    private fun message(role: String, content: String) = buildJsonObject { put("role", role); put("content", content) }
    private fun call(id: String) = buildJsonObject {
        put("role", "assistant")
        put("tool_calls", buildJsonArray { add(buildJsonObject {
            put("id", id); put("type", "function")
            put("function", buildJsonObject { put("name", "read_file"); put("arguments", "{}") })
        }) })
    }
    private fun result(id: String, text: String = "tool result") = buildJsonObject {
        put("role", "tool"); put("tool_call_id", id); put("content", text)
    }

    @Test fun compactionKeepsLatestAdmittedUserAndWholeRecentToolExchange() {
        val user = message("user", "KEEP_MY_REQUEST_AND_RESTRICTIONS")
        val history = listOf(message("user", "older"), message("assistant", "older answer"), user,
            call("old"), result("old", "x".repeat(12000)), call("recent"), result("recent"))
        val selected = AgentHistory.selectForCompaction(history, 1000)
        assertTrue(selected.recent.flatten().contains(user))
        assertFalse(selected.historical.flatten().contains(user))
        assertEquals(listOf(call("recent"), result("recent")), selected.recent.last())
        assertEquals(listOf(call("old"), result("old", "x".repeat(12000))), selected.historical.last())
        assertEquals(selected.recent.flatten(), AgentHistory.requireCompleteGroups(selected.recent.flatten()).flatten())
    }

    @Test fun malformedOrIncompleteToolGroupsBlockCompactionWithoutDroppingEvidence() {
        for (history in listOf(
            listOf(call("one")), listOf(result("orphan")),
            listOf(call("one"), result("other")),
            listOf(call("one"), result("one"), result("one")),
            listOf(call(""), result("")),
        )) {
            val before = history.toString()
            assertThrows(IllegalArgumentException::class.java) { AgentHistory.selectForCompaction(history, 1000) }
            assertEquals(before, history.toString())
        }
    }

    @Test fun compactionBodyAndChunkBudgetUseInputOnlyMetadataAndOutputReservation() {
        ModelCapabilityRegistry.installRuntime(ModelCapabilities(provider, model, model, null, null, true, null, null,
            emptySet(), emptySet(), emptySet(), null, 8192, ModelCapabilitySource.RUNTIME_PROVIDER, inputTokens = 4096))
        val manager = SessionManager(tmp.root)
        val config = AgentConfig(providerId = provider, model = model)
        val body = Json.parseToJsonElement(manager.buildCompactionBody(config, "preserve this prompt")).jsonObject
        assertEquals(8192, ProviderRequestBudget.explicitOutputTokens(body))
        assertEquals(11264, manager.compactionChunkChars(config))
        assertEquals(JsonPrimitive("preserve this prompt"), body["messages"]!!.jsonArray.single().jsonObject["content"])
    }

    @Test fun tinySharedContextFailsRatherThanInventingCompactionCapacity() {
        ModelContextRegistry.remember(provider, model, 2048)
        val manager = SessionManager(tmp.root)
        val config = AgentConfig(providerId = provider, model = model)
        assertThrows(IllegalArgumentException::class.java) { manager.compactionChunkChars(config) }
        assertEquals(2048, ModelContextRegistry.resolve(provider, model))
    }

    @Test fun truncatedCheckpointCannotBecomeDurableContinuation() {
        val manager = SessionManager(tmp.root)
        for (finish in listOf("length", "content_filter", "tool_calls", null)) {
            val response = buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject {
                if (finish != null) put("finish_reason", finish)
                put("message", message("assistant", "truncated checkpoint"))
            }) }) }
            assertThrows(IllegalArgumentException::class.java) { manager.extractAssistantText(response.toString()) }
        }
    }

    @Test fun ideProjectionNeverTruncatesAnAdmittedPromptEvenWhenItExceedsOptionalBudget() {
        val prompt = "u".repeat(2000) + " KEEP_USER_TAIL"
        val context = AgentIdeContextSnapshot(openFiles = listOf("Main.kt"))
        assertEquals(prompt, AgentIdeContextProjection.forModel(prompt, context, 1000))
        assertEquals(prompt, AgentIdeContextProjection.forModel(prompt, null, 1000))
    }

    @Test fun currentFourKLocalAgentProfileRejectsBeforeLaunchAndExplainsAvailableAlternatives() {
        assertEquals(4096, LocalLlamaServerPolicy.CONTEXT_SIZE)
        var launched = false
        val error = assertThrows(IllegalStateException::class.java) {
            LocalAgentCompatibility.requireCompatible()
            launched = true
        }
        assertFalse(launched)
        assertEquals(LocalAgentCompatibility.FOUR_K_REASON, error.message)
        assertTrue(error.message.orEmpty().contains("required instructions and tools"))
        assertTrue(error.message.orEmpty().contains("Import and certification remain available"))
    }
}
