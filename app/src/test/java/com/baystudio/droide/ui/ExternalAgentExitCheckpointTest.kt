package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ExternalAgentExitCheckpointTest {
    private val spec = WorkspaceAgentPluginSpec("exit-test", "plugin.exit", "Exit test", "never-run", emptyList())
    private class History : PluginConversationHistory {
        var window: PluginHistoryWindow? = null
        var partial = ""
        var failFinish = false
        var failClose = false
        var finishes = 0
        var closeCalls = 0
        override suspend fun listPage(agentId: String?, query: String, before: PluginHistoryCursor?, limit: Int) = PluginHistoryPage(listOfNotNull(window?.summary), null)
        override suspend fun latest(agentId: String) = window
        override suspend fun load(id: String, beforePart: Long?) = window
        override suspend fun beginTurn(id: String?, agentId: String, prompt: String, mode: WorkspaceAgentMode, nativeSessionId: String?): PluginHistoryWindow =
            PluginHistoryWindow(PluginHistorySummary("exit-history", agentId, prompt, 1, 1, nativeSessionId, mode, null, 1, 1, PluginHistoryStatus.RUNNING),
                listOf(AgentMessage("user", prompt)), listOf(0L), 0, 1).also { window = it }
        override suspend fun bind(id: String, nativeSessionId: String, modelId: String?) {
            window = window?.let { it.copy(summary = it.summary.copy(nativeSessionId = nativeSessionId, modelId = modelId)) }
        }
        override suspend fun appendPartial(id: String, fragments: List<String>) { partial += fragments.joinToString("") }
        override suspend fun finish(id: String, reply: String?, notices: List<String>, status: PluginHistoryStatus): PluginHistoryWindow {
            if (failFinish) error("fixture final storage unavailable")
            finishes++
            val current = checkNotNull(window)
            val messages = current.messages.take(1) + AgentMessage("assistant", reply ?: partial) + notices.map { AgentMessage("assistant", it) }
            return current.copy(summary = current.summary.copy(status = status), messages = messages).also { window = it }
        }
        override suspend fun delete(id: String) = Unit
        override suspend fun close() { closeCalls++; if (failClose) error("fixture close unavailable") }
    }
    private class Backend : WorkspaceAgentChatBackend {
        val started = CompletableDeferred<Unit>()
        var calls = 0
        var finishNormally = false
        var cancels = 0
        override suspend fun runTurn(agentId: String, prompt: String, mode: WorkspaceAgentMode, resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit): WorkspaceAgentTurnResult {
            calls++
            onEvent(WorkspaceAgentEvent.SessionBound("native-exit"))
            onEvent(WorkspaceAgentEvent.Text("before"))
            onEvent(WorkspaceAgentEvent.Thought("private-thought-must-not-be-saved"))
            started.complete(Unit)
            if (finishNormally) return WorkspaceAgentTurnResult(agentId, "native-exit", "authoritative-final", "end_turn", emptyList(), emptyList())
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { onEvent(WorkspaceAgentEvent.Text("-final")) } }
        }
        override suspend fun prepareSession(agentId: String, resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit) =
            WorkspaceAgentEvent.Configuration("native-exit", WorkspaceAgentSessionConfiguration())
        override suspend fun selectModel(agentId: String, expectedSessionId: String, modelId: String) = error("Unexpected model change")
        override suspend fun newSession(agentId: String, onEvent: (WorkspaceAgentEvent) -> Unit): String = error("Unexpected new session")
        override suspend fun cancel(agentId: String, expectedSessionId: String?) { cancels++ }
    }
    private suspend fun CoroutineScope.fixture(block: suspend (ExternalAgentSessionController, Backend, History, () -> Unit) -> Unit) {
        val owner = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + owner)
        var admission = true
        val history = History()
        val controller = ExternalAgentSessionController(scope, history = history, workAdmission = { admission })
        try { block(controller, Backend(), history, { admission = false }) }
        finally { owner.cancelAndJoin() }
    }

    @Test fun exitDrainsLastAcceptedOutputAndKeepsQueuedDraftWithoutPromotion() = runBlocking {
        withTimeout(5_000) { fixture { controller, backend, history, closeAdmission ->
            assertTrue(controller.submit(backend, spec, "first", WorkspaceAgentMode.EDIT))
            backend.started.await(); yield()
            assertTrue(controller.submit(backend, spec, "next draft", WorkspaceAgentMode.EDIT, AgentPromptDelivery.QUEUE))
            closeAdmission()
            controller.stopAndCheckpointForExit(backend)
            assertEquals("before-final", history.partial)
            assertEquals(PluginHistoryStatus.STOPPED, history.window?.summary?.status)
            assertEquals(listOf("next draft"), controller.snapshotPendingPromptsForExit().map { it.input.text })
            assertFalse(controller.busy)
            assertFalse(controller.submit(backend, spec, "late", WorkspaceAgentMode.EDIT))
            assertEquals(1, backend.calls)
            assertFalse(history.window!!.messages.any { "private-thought" in it.content })
            controller.closeAfterExitCheckpoint()
            assertEquals(1, history.closeCalls)
        } }
    }

    @Test fun failedFinalWriteBlocksExitAndRetryOnlySavesWithoutRerunningPrompt() = runBlocking {
        withTimeout(5_000) { fixture { controller, backend, history, closeAdmission ->
            history.failFinish = true
            controller.submit(backend, spec, "first", WorkspaceAgentMode.EDIT)
            backend.started.await(); yield(); closeAdmission()
            assertTrue(runCatching { controller.stopAndCheckpointForExit(backend) }.isFailure)
            assertNotNull(controller[spec.id].persistenceError)
            assertTrue(runCatching { controller.closeAfterExitCheckpoint() }.isFailure)
            assertEquals("before-final", history.partial)
            history.failFinish = false
            controller.stopAndCheckpointForExit(backend)
            assertEquals(1, history.finishes)
            assertEquals(1, backend.calls)
            assertEquals("before-final", history.window!!.messages[1].content)
            assertNull(controller[spec.id].persistenceError)
            controller.closeAfterExitCheckpoint()
        } }
    }

    @Test fun failedCompletedReplyRetainsAuthoritativeAnswerForExitRetry() = runBlocking {
        withTimeout(5_000) { fixture { controller, backend, history, closeAdmission ->
            history.failFinish = true; backend.finishNormally = true
            controller.submit(backend, spec, "first", WorkspaceAgentMode.EDIT)
            backend.started.await()
            while (controller.busy) yield()
            assertNotNull(controller[spec.id].persistenceError)
            closeAdmission()
            assertTrue(runCatching { controller.stopAndCheckpointForExit(backend) }.isFailure)
            history.failFinish = false
            controller.stopAndCheckpointForExit(backend)
            assertEquals("authoritative-final", history.window!!.messages[1].content)
            assertEquals(1, history.finishes)
            assertEquals(1, backend.calls)
            controller.closeAfterExitCheckpoint()
        } }
    }

    @Test fun cancelBeforeDispatchKeepsTheAcceptedPromptAsADraftAndMakesNoCall() = runBlocking {
        val queued = ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) } }
        val owner = SupervisorJob()
        val history = History(); val backend = Backend()
        val controller = ExternalAgentSessionController(CoroutineScope(owner + dispatcher), history = history, workAdmission = { true })
        try {
            assertTrue(controller.submit(backend, spec, "undispatched", WorkspaceAgentMode.EDIT))
            val stopping = async(start = CoroutineStart.UNDISPATCHED) { controller.stopAndCheckpointForExit(backend) }
            while (queued.isNotEmpty()) queued.removeFirst().run()
            stopping.await()
            assertEquals(0, backend.calls)
            assertEquals(listOf("undispatched"), controller.snapshotPendingPromptsForExit().map { it.input.text })
            assertFalse(controller.busy)
            controller.closeAfterExitCheckpoint()
        } finally { owner.cancel(); while (queued.isNotEmpty()) queued.removeFirst().run(); owner.join() }
    }

    @Test fun ordinaryStopStillAllowsAnotherExplicitPromptAfterItsDurableFinish() = runBlocking {
        withTimeout(5_000) { fixture { controller, backend, history, _ ->
            controller.submit(backend, spec, "first", WorkspaceAgentMode.EDIT)
            backend.started.await(); yield()
            controller.cancel(backend)
            while (controller.busy) yield()
            backend.finishNormally = true
            assertTrue(controller.submit(backend, spec, "second", WorkspaceAgentMode.EDIT))
            while (controller.busy) yield()
            assertEquals(2, backend.calls)
            assertEquals(2, history.finishes)
            controller.stopAndCheckpointForExit(backend)
            controller.closeAfterExitCheckpoint()
        } }
    }

    @Test fun closeFailureIsAwaitedAndRetriedWithoutDiscardingPendingDrafts() = runBlocking {
        withTimeout(5_000) { fixture { controller, backend, history, closeAdmission ->
            controller.submit(backend, spec, "first", WorkspaceAgentMode.EDIT)
            backend.started.await(); yield()
            controller.submit(backend, spec, "draft", WorkspaceAgentMode.EDIT, AgentPromptDelivery.QUEUE)
            closeAdmission(); controller.stopAndCheckpointForExit(backend)
            history.failClose = true
            assertTrue(runCatching { controller.closeAfterExitCheckpoint() }.isFailure)
            assertEquals(listOf("draft"), controller.snapshotPendingPromptsForExit().map { it.input.text })
            history.failClose = false
            controller.closeAfterExitCheckpoint()
            assertEquals(2, history.closeCalls)
            assertEquals(1, backend.calls)
        } }
    }
}
