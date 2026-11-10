package com.baystudio.droide.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class InterruptedAgentStreamTest {
    @Test fun onlyPublicTextCanBeCapturedAndEveryRunHasDistinctIdentity() {
        val text = AgentStreamPart("text:1", 1, AgentStreamPartKind.TEXT, AgentStreamPartState.RUNNING,
            text = "Partial answer", input = "not an executable argument", finishReason = "stop")
        val first = InterruptedAgentStream.snapshot(text, false, 1)!!
        val second = InterruptedAgentStream.snapshot(text, true, 2)!!
        assertNotEquals(first.message.streamPart!!.id, second.message.streamPart!!.id)
        assertEquals(AgentStreamPartState.ERROR, first.message.streamPart!!.state)
        assertEquals(AgentStreamPartState.CANCELLED, second.message.streamPart!!.state)
        assertEquals("", first.message.streamPart!!.finishReason)
        assertEquals(setOf("role", "content"), first.apiEntry.keys)
        assertTrue(first.apiEntry.getValue("content").jsonPrimitive.content.contains("incomplete"))
        assertFalse(first.apiEntry.toString().contains("executable argument"))
        assertNull(InterruptedAgentStream.snapshot(text.copy(kind = AgentStreamPartKind.TOOL), false, 1))
        assertNull(InterruptedAgentStream.snapshot(text.copy(text = ""), false, 1))
    }

    @Test fun persistenceKeepsCommittedToolGroupsAndIncompleteTextInTheSameSession() = runBlocking {
        val root = Files.createTempDirectory("droide-stream-continuation-").toFile()
        try {
            val manager = SessionManager(root)
            val session = manager.create("Existing conversation")
            val history = AgentConversationHistory().apply { resetNew() }
            val committed = listOf(
                Json.parseToJsonElement("""{"role":"user","content":"Build it"}""").jsonObject,
                Json.parseToJsonElement("""{"role":"assistant","content":"","tool_calls":[{"id":"done-call","type":"function","function":{"name":"write_file","arguments":"{}"}}]}""").jsonObject,
                Json.parseToJsonElement("""{"role":"tool","tool_call_id":"done-call","content":"Already applied successfully"}""").jsonObject,
            )
            history.append(committed)
            val text = AgentStreamPart("text:2", 2, AgentStreamPartKind.TEXT, AgentStreamPartState.RUNNING, text = "Here is the result so far")
            val partial = InterruptedAgentStream.snapshot(text, false, 7)!!
            history.append(partial.apiEntry)
            manager.save(session.copy(messages = listOf(partial.message), apiHistory = history.completeActive(),
                forkHistory = history.completeExactOrNull()!!, forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION))
            val restored = manager.get(session.id)!!
            val next = AgentConversationHistory()
            next.load(restored)
            next.append(buildJsonObject { put("role", "user"); put("content", "Continue") })
            assertEquals(session.id, restored.id)
            assertEquals(committed, next.completeActive().take(committed.size))
            assertEquals(1, next.completeActive().count { it["tool_call_id"] == JsonPrimitive("done-call") })
            assertTrue(next.completeActive()[3].getValue("content").jsonPrimitive.content.contains(text.text))
            assertEquals("Continue", next.completeActive().last().getValue("content").jsonPrimitive.content)
            assertEquals(AgentStreamPartState.ERROR, restored.messages.single().streamPart!!.state)
            assertEquals(1, manager.count())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
