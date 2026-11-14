package com.baystudio.droide.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentHistoryProviderAdmissionTest {
    private val user = buildJsonObject { put("role", "user"); put("content", "Keep my request") }
    private fun call(vararg ids: String) = buildJsonObject {
        put("role", "assistant"); put("content", "")
        put("tool_calls", buildJsonArray { ids.forEach { id -> add(buildJsonObject {
            put("id", id); put("type", "function")
            put("function", buildJsonObject { put("name", "read_file"); put("arguments", "{}") })
        }) } })
    }
    private fun result(id: String) = buildJsonObject {
        put("role", "tool"); put("tool_call_id", id); put("content", "Read result $id")
    }

    @Test fun restoredMalformedEvidenceCannotDisappearBeforeProviderAdmission() = runBlocking {
        val malformed = listOf(
            listOf(user, call("a"), result("a"), result("a")),
            listOf(user, result("orphan")),
            listOf(user, call("a", "b"), result("a")),
        )
        for (source in malformed) {
            val history = AgentConversationHistory()
            history.load(DroideSession("restored", "Restored", apiHistory = source,
                forkHistory = listOf(user), forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION))
            assertNotEquals(source, history.completeActive()) // Old projection hides the evidence.
            val failure = assertThrows(IllegalArgumentException::class.java) { history.strictActiveForProvider() }
            assertTrue(failure.message.orEmpty().contains("incomplete or mismatched"))
            assertEquals(source, history.active)
        }
    }

    @Test fun normalIncrementalToolBatchCanStillBeSnapshottedAndAdmittedOnceComplete() = runBlocking {
        val history = AgentConversationHistory()
        val assistant = call("a", "b")
        history.append(listOf(user, assistant))
        assertEquals(listOf(user), history.completeActive())
        history.append(result("a"))
        assertEquals(listOf(user), history.completeActive())
        assertThrows(IllegalArgumentException::class.java) { history.strictActiveForProvider() }
        history.append(result("b"))
        val complete = listOf(user, assistant, result("a"), result("b"))
        assertEquals(complete, history.strictActiveForProvider())
        assertEquals(complete, history.completeActive())
        assertEquals(complete, history.completeExactOrNull())
    }

    @Test fun existingInterruptedToolClosureMakesTheNextProviderBoundaryComplete() = runBlocking {
        val history = AgentConversationHistory()
        history.append(listOf(user, call("a", "b"), result("a")))
        val closure = AgentHistory.closeInterruptedTools(mapOf("b" to "read_file"),
            "Tool execution was interrupted by the user", AgentStreamPartState.CANCELLED, emptyList(), 1)
        history.append(closure.apiEntries)
        val admitted = history.strictActiveForProvider()
        assertEquals(4, admitted.size)
        assertEquals(JsonPrimitive("b"), admitted.last()["tool_call_id"])
        assertEquals(1, admitted.count { it["tool_call_id"] == JsonPrimitive("a") })
        assertEquals(1, admitted.count { it["tool_call_id"] == JsonPrimitive("b") })
    }
}
