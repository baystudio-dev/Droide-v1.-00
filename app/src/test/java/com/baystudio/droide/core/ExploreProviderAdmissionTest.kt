package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ExploreProviderAdmissionTest {
    private fun calls(count: Int) = buildJsonArray { repeat(count) { index -> add(buildJsonObject {
        put("id", "call_$index"); put("type", "function")
        put("function", buildJsonObject { put("name", "read_file"); put("arguments", "{\"path\":\"Main.kt\"}") })
    }) } }

    @Test fun excessiveCallBatchIsRejectedBeforeAnExecutableIterableCanBeObtained() {
        var retained = false
        var executed = 0
        val source = calls(AgentService.MAX_TOOL_CALLS_PER_TURN + 1)
        val error = assertThrows(IllegalArgumentException::class.java) {
            val admitted = ExploreAgent.admittedToolCalls(source)
            retained = true
            admitted.forEach { executed++ }
        }
        assertFalse(retained)
        assertEquals(0, executed)
        assertTrue(error.message.orEmpty().contains("maximum is 16"))
        val allowed = calls(AgentService.MAX_TOOL_CALLS_PER_TURN)
        assertSame(allowed, ExploreAgent.admittedToolCalls(allowed))
    }

    @Test fun completeQueryRemainsUserDataWithoutDuplicatingItIntoMandatoryInstructions() {
        val query = "q".repeat(4000) + " DO_NOT_EDIT_OR_BUILD"
        val messages = ExploreAgent.initialMessages(query)
        assertEquals(JsonPrimitive("user"), messages.last()["role"])
        assertEquals(JsonPrimitive(query), messages.last()["content"])
        val system = messages.first()["content"]!!.jsonPrimitive.content
        assertTrue(system.contains("read-only explore subagent"))
        assertTrue(system.contains("Do not guess file contents"))
        assertFalse(system.contains(query))
        assertFalse(system.contains("DO_NOT_EDIT_OR_BUILD"))
    }
}
