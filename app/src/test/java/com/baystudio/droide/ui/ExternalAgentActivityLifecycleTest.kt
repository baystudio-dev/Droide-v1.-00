package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class ExternalAgentActivityLifecycleTest {
    private val spec = WorkspaceAgentPluginCatalog.entries.first()
    private class Backend : WorkspaceAgentChatBackend {
        val started = Channel<Unit>(Channel.UNLIMITED)
        val finish = Channel<String>(Channel.UNLIMITED)
        val callbacks = mutableListOf<(WorkspaceAgentEvent) -> Unit>()
        val config = WorkspaceAgentSessionConfiguration()
        override suspend fun prepareSession(agentId: String, resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit) = WorkspaceAgentEvent.Configuration("session", config)
        override suspend fun selectModel(agentId: String, expectedSessionId: String, modelId: String) = WorkspaceAgentEvent.Configuration("session", config)
        override suspend fun newSession(agentId: String, onEvent: (WorkspaceAgentEvent) -> Unit) = "session"
        override suspend fun cancel(agentId: String, expectedSessionId: String?) = Unit
        override suspend fun runTurn(agentId: String, prompt: String, mode: WorkspaceAgentMode, resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit): WorkspaceAgentTurnResult {
            callbacks += onEvent
            onEvent(WorkspaceAgentEvent.SessionBound("session"))
            started.send(Unit)
            val end = finish.receive()
            if (end == "exception") error("fixture failure")
            return WorkspaceAgentTurnResult(agentId, "session", "Final reply", end, emptyList(), emptyList())
        }
    }
    private fun fixture(body: suspend (ExternalAgentSessionController, Backend) -> Unit) = runBlocking {
        withTimeout(8_000) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
            val controller = ExternalAgentSessionController(scope, workAdmission = { true })
            val host = Backend()
            try { body(controller, host) }
            finally { controller.close(); scope.coroutineContext.job.cancelAndJoin() }
        }
    }
    private suspend fun until(predicate: () -> Boolean) { withTimeout(2_000) { while (!predicate()) delay(1) } }
    private suspend fun start(c: ExternalAgentSessionController, h: Backend) {
        assertTrue(c.submit(h, spec, "Work", WorkspaceAgentMode.EDIT))
        h.started.receive()
    }

    @Test fun actualControllerUsesLatestEventAndPreviewFlushCannotRestoreOldPhase() = fixture { c, h ->
        start(c, h)
        h.callbacks.last()(WorkspaceAgentEvent.Text("Commentary"))
        until { c[spec.id].liveText == "Commentary" }
        h.callbacks.last()(WorkspaceAgentEvent.Thought("Public summary"))
        until { c[spec.id].progress.phase == AgentActivityKind.THINKING }
        h.callbacks.last()(WorkspaceAgentEvent.Tool("Compile", """{"toolCallId":"a","status":"in_progress","kind":"execute"}"""))
        until { c[spec.id].progress.presentation().kind == AgentActivityKind.COMMAND }
        delay(45)
        assertEquals(AgentActivityKind.COMMAND, c[spec.id].progress.presentation().kind)
        assertEquals("Commentary", c[spec.id].liveText)
        assertEquals("Public summary", c[spec.id].liveThought)
        h.finish.send("end_turn"); until { !c.busy }
        assertEquals(AgentActivityKind.COMPLETED, c[spec.id].progress.phase)
        assertEquals("Final reply", c[spec.id].messages.last().content)
    }

    @Test fun stopAndNewRunRejectOldActivityWithoutClearingTheNewReply() = fixture { c, h ->
        start(c, h)
        val old = h.callbacks.last()
        old(WorkspaceAgentEvent.Text("Saved partial")); until { c[spec.id].liveText == "Saved partial" }
        c.cancel(h); until { !c.busy }
        assertEquals(AgentActivityKind.STOPPED, c[spec.id].progress.phase)
        assertTrue(c[spec.id].messages.any { it.content == "Saved partial" })
        start(c, h)
        assertEquals(AgentActivityKind.WORKING, c[spec.id].progress.phase)
        h.callbacks.last()(WorkspaceAgentEvent.Text("New reply")); until { c[spec.id].liveText == "New reply" }
        runCatching { old(WorkspaceAgentEvent.Thought("Late old thought")) }
        delay(45)
        assertEquals(AgentActivityKind.RESPONDING, c[spec.id].progress.phase)
        assertEquals("New reply", c[spec.id].liveText)
        h.finish.send("end_turn"); until { !c.busy }
    }

    @Test fun failureSetsFinalStatusAndKeepsPartialOutput() = fixture { c, h ->
        start(c, h)
        h.callbacks.last()(WorkspaceAgentEvent.Text("Partial")); until { c[spec.id].liveText == "Partial" }
        h.finish.send("exception"); until { !c.busy }
        assertEquals(AgentActivityKind.FAILED, c[spec.id].progress.phase)
        assertTrue(c[spec.id].messages.any { it.content == "Partial" })
        assertTrue(c[spec.id].messages.last().content.contains("fixture failure"))
    }
}
