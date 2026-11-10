package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AgentActivityStateTest {
    private fun tool(id: String = "t", state: AgentStreamPartState = AgentStreamPartState.RUNNING, detail: String = "Running") =
        AgentStreamPart(id, 1, AgentStreamPartKind.TOOL, state, toolName = "run_command", title = "Terminal", detail = detail)
    private fun busy(vararg parts: AgentStreamPart) = AgentStreamState(status = AgentStreamState.Status.BUSY,
        activeStep = 1, parts = parts.toList(), modelActivity = AgentModelActivity.THINKING)
    private fun event(id: String, status: String, kind: String = "execute") = WorkspaceAgentEvent.Tool("Command $id",
        """{"toolCallId":"$id","status":"$status","kind":"$kind"}""")

    @Test fun nativePhaseFollowsNewEventsAndRetryResumesWithoutAnOrphanSpinner() {
        val retry = AgentStreamPart("retry", 1, AgentStreamPartKind.RETRY, AgentStreamPartState.RUNNING)
        val state = MutableStateFlow(busy(retry).copy(status = AgentStreamState.Status.RETRYING, retryAtMs = 123))
        val projector = AgentStreamProjector(state, 1024, 1024)
        assertEquals(AgentActivityKind.RETRYING, nativeAgentActivity(state.value).kind)
        projector.modelActivity(AgentModelActivity.THINKING)
        assertEquals(AgentActivityKind.THINKING, nativeAgentActivity(state.value).kind)
        assertNull(state.value.retryAtMs)
        assertEquals(AgentStreamPartState.COMPLETED, state.value.parts.single().state)
        projector.appendText("text", 1, "Public answer", false)
        assertEquals(AgentActivityKind.RESPONDING, nativeAgentActivity(state.value).kind)
        projector.modelActivity(AgentModelActivity.THINKING)
        assertEquals("Public answer", projector.part("text")!!.text)
        assertEquals(AgentActivityKind.THINKING, nativeAgentActivity(state.value).kind)
        projector.applyToolCallDelta(1, LlmStreamEvent.ToolCallDelta(0, "call", "run_command", "{}"))
        assertEquals(AgentActivityKind.PREPARING, nativeAgentActivity(state.value).kind)
    }

    @Test fun terminalNativeStatesRejectLatePhaseSignals() {
        for (status in listOf(AgentStreamState.Status.ERROR, AgentStreamState.Status.CANCELLED, AgentStreamState.Status.IDLE)) {
            val state = MutableStateFlow(busy().copy(status = status, endedAtMs = 42))
            val before = state.value
            AgentStreamProjector(state, 20, 20).modelActivity(AgentModelActivity.RESPONDING)
            assertEquals(before, state.value)
            assertFalse(nativeAgentActivity(state.value, approval = true).kind.spinning)
        }
    }

    @Test fun approvalInputAndCompactionOutrankStaleModelActivity() {
        assertEquals(AgentActivityKind.APPROVAL, nativeAgentActivity(busy(tool(detail = "Waiting for approval"))).kind)
        assertEquals(AgentActivityKind.INPUT, nativeAgentActivity(busy(tool(detail = "Waiting for user answer"))).kind)
        assertEquals(AgentActivityKind.COMMAND, nativeAgentActivity(busy(tool())).kind)
        assertEquals(AgentActivityKind.APPROVAL, nativeAgentActivity(busy(tool()), approval = true).kind)
        assertEquals(AgentActivityKind.INPUT, nativeAgentActivity(busy(), input = true).kind)
        val compact = AgentStreamPart("compact", 1, AgentStreamPartKind.COMPACTION, AgentStreamPartState.RUNNING)
        assertEquals(AgentActivityKind.COMPACTING, nativeAgentActivity(busy(compact)).kind)
    }

    @Test fun backgroundLaunchAndPriorToolFailureDoNotMasqueradeAsForegroundExecution() {
        val started = tool(state = AgentStreamPartState.COMPLETED, detail = "Started")
        val failed = tool("failed", AgentStreamPartState.ERROR, "Failed")
        assertEquals(AgentActivityKind.THINKING, nativeAgentActivity(busy(started, failed)).kind)
        assertEquals(AgentActivityKind.TOOL, nativeAgentActivity(busy(tool("a"), tool("b"))).kind)
        assertEquals("2 tools active", nativeAgentActivity(busy(tool("a"), tool("b"))).detail)
    }

    @Test fun detailsFilterKeepsFailureAndStopRecordsAndStableOriginalIds() {
        val step = AgentStreamPart("step", 1, AgentStreamPartKind.STEP, AgentStreamPartState.COMPLETED)
        val error = step.copy(id = "error", state = AgentStreamPartState.ERROR)
        val stopped = step.copy(id = "stopped", state = AgentStreamPartState.CANCELLED)
        val parts = listOf(step, tool(), error, stopped)
        assertEquals(listOf("t", "error", "stopped"), visibleAgentStreamParts(parts, false).map { it.id })
        assertSame(parts, visibleAgentStreamParts(parts, true))
        assertEquals(AgentStreamPartState.COMPLETED, step.state)
    }

    @Test fun legacyStreamSnapshotsDecodeWithANeutralPhase() {
        val state = Json.decodeFromString<AgentStreamState>("""{"runId":1,"status":"BUSY","activeStep":7} """)
        assertEquals(AgentModelActivity.WORKING, state.modelActivity)
        assertEquals(AgentActivityKind.WORKING, nativeAgentActivity(state).kind)
    }

    @Test fun cliPhaseFollowsEventsAfterEarlierCommentaryRatherThanRetainedText() {
        var state = ExternalAgentActivity().accept(WorkspaceAgentEvent.Text("I will inspect"))
        assertEquals(AgentActivityKind.RESPONDING, state.presentation().kind)
        state = state.accept(event("a", "in_progress", "read"))
        assertEquals(AgentActivityKind.READING, state.presentation().kind)
        state = state.accept(event("a", "completed", "read")).accept(WorkspaceAgentEvent.Thought("Public summary"))
        assertEquals(AgentActivityKind.THINKING, state.presentation().kind)
        state = state.accept(WorkspaceAgentEvent.Text("Result"))
        assertEquals(AgentActivityKind.RESPONDING, state.presentation().kind)
    }

    @Test fun cliToolsTrackIdentityAndDoNotReopenCompletedItems() {
        var state = ExternalAgentActivity().accept(event("a", "in_progress")).accept(event("b", "pending"))
        assertEquals("2 tools active", state.presentation().detail)
        state = state.accept(event("a", "completed"))
        assertEquals(AgentActivityKind.PREPARING, state.presentation().kind)
        val before = state
        state = state.accept(event("a", "in_progress"))
        assertEquals(before, state)
        state = state.accept(event("b", "failed"))
        assertEquals(AgentActivityKind.WORKING, state.presentation().kind)
        assertEquals("failed", state.tools["b"]!!.status)
        assertEquals(AgentActivityKind.APPROVAL, state.presentation(approval = true).kind)
    }

    @Test fun unknownCliMetadataDoesNotInventExecutionOrCrash() {
        for (raw in listOf("bad json", "[]", """{"toolCallId":"x","status":"unknown"}""", "x".repeat(65_537))) {
            val state = ExternalAgentActivity().accept(WorkspaceAgentEvent.Text("old reply"))
                .accept(WorkspaceAgentEvent.Tool("not proof of execution", raw))
            assertEquals(AgentActivityKind.WORKING, state.presentation().kind)
            assertTrue(state.tools.isEmpty())
        }
    }

    @Test fun cliRetentionIsBoundedAndDoesNotEvictCompletedIdentityProtection() {
        var state = ExternalAgentActivity()
        repeat(256) { state = state.accept(event("$it", "completed")) }
        assertEquals(128, state.tools.size)
        assertTrue(state.incomplete)
        assertEquals("completed", state.accept(event("0", "in_progress")).tools["0"]!!.status)
        assertEquals(AgentActivityKind.WORKING, state.presentation().kind)
    }

    @Test fun stoppedCliStateCannotBeRevivedByAnyLateEventOrApproval() {
        for (kind in listOf(AgentActivityKind.STOPPED, AgentActivityKind.FAILED, AgentActivityKind.COMPLETED)) {
            val state = ExternalAgentActivity().finish(kind)
            for (event in listOf(WorkspaceAgentEvent.Text("late"), WorkspaceAgentEvent.Thought("late"), event("a", "in_progress"), WorkspaceAgentEvent.Status("late")))
                assertEquals(state, state.accept(event))
            assertEquals(kind, state.presentation(approval = true).kind)
        }
    }
}
