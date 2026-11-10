package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentToolArgumentContractTest {
    @Test fun malformedAndNonObjectArgumentsRemainFailedCallsWithoutReorderingSiblings() {
        val invalid = listOf<JsonElement?>(JsonPrimitive("{broken"), JsonPrimitive("[]"), JsonPrimitive("null"),
            JsonPrimitive("17"), JsonPrimitive("\"text\""), JsonPrimitive(""), JsonPrimitive(17), JsonNull, null)
        for (value in invalid) {
            val calls = buildJsonArray {
                add(call("bad", "undo_agent_edit", value))
                add(call("good", "read_file", JsonPrimitive("""{"path":"Main.kt"}""")))
            }
            val prepared = AgentToolTurnPlanner.prepare(calls, Json, 3, 128_000, "", 0)
            assertEquals(listOf("bad", "good"), prepared.map { it.id })
            assertTrue(prepared.first().argumentError.orEmpty().contains("INVALID_TOOL_ARGUMENTS"))
            assertNull(prepared.last().argumentError)
            assertEquals("Main.kt", prepared.last().args["path"]?.jsonPrimitive?.content)
            val facts = AgentToolTurnPlanner.schedulingFacts(prepared, { true }, { false })
            assertEquals(AgentToolScheduler.Lane.SERIAL, facts.first().lane)
            assertEquals(AgentToolScheduler.Lane.PARALLEL_READ, facts.last().lane)
        }
    }

    @Test fun explicitEmptyObjectRemainsValidForArgumentFreeTools() {
        val prepared = AgentToolTurnPlanner.prepare(buildJsonArray {
            add(call("status", "git_status", JsonPrimitive("{}")))
        }, Json, 1, 128_000, "", 0).single()
        assertNull(prepared.argumentError)
        assertEquals(buildJsonObject {}, prepared.args)
    }

    private fun call(id: String, name: String, arguments: JsonElement?) = buildJsonObject {
        put("id", id)
        put("function", buildJsonObject {
            put("name", name)
            arguments?.let { put("arguments", it) }
        })
    }
}
