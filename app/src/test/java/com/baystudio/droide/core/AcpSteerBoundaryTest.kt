package com.baystudio.droide.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class AcpSteerBoundaryTest {
    private fun event(kind: String, id: String? = null, status: String? = null) = Json.parseToJsonElement(
        """{"sessionUpdate":"$kind"${id?.let { ",\"toolCallId\":\"$it\"" }.orEmpty()}${status?.let { ",\"status\":\"$it\"" }.orEmpty()}}""").jsonObject

    @Test fun redirectWaitsUntilAllReportedConcurrentToolsFinish() {
        val boundary = AcpSteerBoundary(); val turn = boundary.begin()
        boundary.update(event("tool_call", "one", "in_progress"), false)
        boundary.update(event("tool_call", "two", "pending"), false)
        assertTrue(boundary.request())
        assertNull(boundary.update(event("agent_thought_chunk"), false))
        assertNull(boundary.update(event("tool_call_update", "one", "completed"), false))
        assertEquals(turn, boundary.update(event("tool_call_update", "two", "completed"), false))
        assertNull(boundary.update(event("agent_message_chunk"), false))
    }

    @Test fun pendingClientPermissionOrFilesystemCallPreventsRedirect() {
        val boundary = AcpSteerBoundary(); val turn = boundary.begin(); boundary.request()
        assertNull(boundary.update(event("agent_message_chunk"), true))
        assertEquals(turn, boundary.update(event("agent_message_chunk"), false))
    }

    @Test fun unknownToolIdentityRemainsConservativeUntilTurnEnds() {
        val boundary = AcpSteerBoundary(); boundary.begin()
        boundary.update(event("tool_call", status = "in_progress"), false); boundary.request()
        assertNull(boundary.update(event("agent_message_chunk"), false))
    }

    @Test fun removingPendingSteerClearsTheUnsentRedirectRequest() {
        val boundary = AcpSteerBoundary(); boundary.begin(); boundary.request(); boundary.clearRequest()
        assertNull(boundary.update(event("agent_message_chunk"), false))
    }

    @Test fun anOldTurnSignalCannotRedirectItsReplacement() {
        val boundary = AcpSteerBoundary(); val old = boundary.begin(); boundary.request()
        assertEquals(old, boundary.update(event("agent_message_chunk"), false))
        boundary.end(old); val next = boundary.begin()
        assertFalse(boundary.isOpen(old)); assertTrue(boundary.isOpen(next))
        boundary.end(old); assertTrue(boundary.isOpen(next))
        assertNull(boundary.update(event("agent_message_chunk"), false))
    }

    @Test fun noNewRequestIsAdmittedAfterAClosedTurn() {
        val boundary = AcpSteerBoundary(); assertFalse(boundary.request())
        val turn = boundary.begin(); boundary.end(turn); assertFalse(boundary.request())
    }
}
