package com.baystudio.droide.core

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class AgentLivePreviewRetentionTest {
    private fun tool(id: String, state: AgentStreamPartState = AgentStreamPartState.COMPLETED) = AgentStreamPart(
        id, 1, AgentStreamPartKind.TOOL, state, input = "x".repeat(128_000), output = "o".repeat(20_000),
        fileChanges = listOf(AgentFileChange("file.kt", "d".repeat(16_000))),
    )

    @Test fun nestedToolInputsOutputAndDiffsCountTowardDurableUiBudget() {
        val messages = (0 until 40).map { AgentMessage("assistant", "ok", streamPart = tool("tool:$it")) }
        val bounded = AgentHistory.boundedUi(messages, 500, 1_000_000)
        assertTrue(bounded.size < messages.size)
        assertEquals(messages.last(), bounded.last())
        assertTrue(bounded.sumOf { it.content.length + it.streamPart!!.retainedPreviewChars() } <= 1_000_000)
        assertEquals(messages.takeLast(bounded.size), bounded)
    }

    @Test fun longTaskStreamKeepsCurrentToolOwnersWhileBoundingCompletedPreviewMemory() {
        val state = MutableStateFlow(AgentStreamState())
        val projector = AgentStreamProjector(state, 100_000, 128_000)
        repeat(96) { step ->
            repeat(16) { call ->
                val part = tool("tool:$step:$call", AgentStreamPartState.PENDING)
                projector.upsert(part)
                assertNotNull(projector.part(part.id))
                projector.update(part.id) { it.copy(state = AgentStreamPartState.RUNNING) }
                assertEquals(AgentStreamPartState.RUNNING, projector.part(part.id)?.state)
                projector.update(part.id) { it.copy(state = AgentStreamPartState.COMPLETED) }
                assertTrue(state.value.parts.size <= AgentLivePreviewRetention.MAX_PARTS)
                assertTrue(state.value.parts.filter { it.state == AgentStreamPartState.COMPLETED }
                    .sumOf { it.retainedPreviewChars() } <= AgentLivePreviewRetention.MAX_COMPLETED_CHARS)
            }
        }
        val latest = "tool:95:15"
        assertNotNull(projector.part(latest))
        assertNull(projector.part("tool:0:0"))
    }

    @Test fun simultaneousPendingToolsRemainAddressableEvenOverCompletedBudget() {
        val state = MutableStateFlow(AgentStreamState())
        val projector = AgentStreamProjector(state, 100_000, 128_000)
        repeat(20) { projector.upsert(tool("old:$it")) }
        val active = (0 until 16).map { tool("current:$it", AgentStreamPartState.PENDING) }
        active.forEach(projector::upsert)
        active.forEach { assertEquals(it, projector.part(it.id)) }
        assertTrue(state.value.parts.filter { it.state == AgentStreamPartState.COMPLETED }
            .sumOf { it.retainedPreviewChars() } <= AgentLivePreviewRetention.MAX_COMPLETED_CHARS)
        projector.update(active.first().id) { it.copy(state = AgentStreamPartState.COMPLETED) }
        active.drop(1).forEach { assertEquals(it, projector.part(it.id)) }
    }

    @Test fun malformedStreamToolIndexesCannotAllocateUnboundedPendingOwners() {
        val state = MutableStateFlow(AgentStreamState())
        val projector = AgentStreamProjector(state, 100_000, 128_000)
        for (index in listOf(-1, 16, Int.MAX_VALUE)) {
            try {
                projector.applyToolCallDelta(1, LlmStreamEvent.ToolCallDelta(index, null, "write_file", "{}"))
                fail("Out-of-range tool preview must be rejected")
            } catch (_: IllegalArgumentException) { }
        }
        assertTrue(state.value.parts.isEmpty())
    }

    @Test fun streamedNamesAreBoundedWithoutTruncatingExecutableModelHistory() {
        val state = MutableStateFlow(AgentStreamState())
        val projector = AgentStreamProjector(state, 100_000, 128_000)
        repeat(20) { projector.applyToolCallDelta(1, LlmStreamEvent.ToolCallDelta(0, null, "x".repeat(256), null)) }
        assertEquals(256, state.value.parts.single().toolName.length)
        assertEquals(AgentStreamPartState.PENDING, state.value.parts.single().state)
    }
}
