package com.baystudio.droide.core

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TypedChatForkBoundaryTest {
    private fun legacy(json: String) = TypedChatEntry.Legacy(Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject)
    private val user = legacy("""{"role":"user","content":"same"}""")
    private val answer = legacy("""{"role":"assistant","content":"same"}""")
    private val calls = legacy("""{"role":"assistant","tool_calls":[{"id":"a"},{"id":"b"}]}""")
    private fun result(id: String) = legacy("""{"role":"tool","tool_call_id":"$id","content":"ok"}""")
    private val owned = TypedChatEntry.OwnedTurn("00000000-0000-0000-0000-000000000001", "run", "response", 1)

    @Test fun mixedOwnedAndLegacyOnlyExposeCompleteBoundaries() {
        val entries = listOf(user, owned, calls, result("b"), result("a"), answer)
        assertEquals(0, TypedChatForkBoundary.requireComplete(emptyList()))
        assertEquals(emptyList<TypedChatEntry>(), TypedChatHistory.takeBoundary(entries, 0))
        assertEquals(1, TypedChatForkBoundary.completedEnd(entries.take(1)))
        assertEquals(3, TypedChatForkBoundary.completedEnd(entries.take(2)))
        assertNull(TypedChatForkBoundary.completedEnd(entries.take(3)))
        assertNull(TypedChatForkBoundary.completedEnd(entries.take(4)))
        assertEquals(6, TypedChatForkBoundary.completedEnd(entries.take(5)))
        assertEquals(7, TypedChatForkBoundary.requireComplete(entries))
        for (split in listOf(2,4,5)) assertThrows(IllegalArgumentException::class.java) { TypedChatHistory.takeBoundary(entries, split) }
        assertEquals(entries.take(5), TypedChatHistory.takeBoundary(entries, 6))
        assertEquals(entries, TypedChatHistory.takeBoundary(entries, 7))
        assertThrows(IllegalArgumentException::class.java) { TypedChatHistory.takeBoundary(entries, 8) }
    }
    @Test fun duplicateTextDoesNotInferOrMoveAnAnchor() {
        val message = AgentMessage("assistant", "same", forkHistoryEnd = 99)
        assertEquals(2, TypedChatForkBoundary.stamp(message, listOf(user, answer)).forkHistoryEnd)
        assertEquals(5, TypedChatForkBoundary.stamp(message, listOf(user, answer, owned, answer)).forkHistoryEnd)
        assertNull(TypedChatForkBoundary.stamp(message, listOf(user, calls)).forkHistoryEnd)
    }
    @Test fun explicitNullCallsRemainBaselineCompatible() {
        val nullCalls = legacy("""{"role":"assistant","content":"done","tool_calls":null}""")
        val emptyCalls = legacy("""{"role":"assistant","content":"done","tool_calls":[]}""")
        val entries = listOf(user, owned, nullCalls, emptyCalls)
        assertEquals(5, TypedChatForkBoundary.requireComplete(entries))
        assertEquals(entries, TypedChatHistory.takeBoundary(entries, 5))
        assertEquals(4, TypedChatForkBoundary.stamp(AgentMessage("assistant", "done"), entries.take(3)).forkHistoryEnd)
    }
    @Test fun malformedOrCrossedGroupsFailClosed() {
        val bad = listOf(
            listOf(result("a")), listOf(calls, result("a"), result("a")),
            listOf(calls, result("other")), listOf(calls, answer), listOf(calls, owned),
            listOf(legacy("""{"role":"assistant","tool_calls":[{"id":"a"},{"id":"a"}]}""")),
            listOf(legacy("""{"role":"assistant","tool_calls":[{}]}""")),
            listOf(legacy("""{"role":"assistant","tool_calls":{}}"""))
        )
        bad.forEach { entries -> assertThrows(IllegalArgumentException::class.java) { TypedChatForkBoundary.completedEnd(entries) } }
        assertThrows(IllegalArgumentException::class.java) { TypedChatHistory.takeBoundary(listOf(calls), 1) }
    }
}
