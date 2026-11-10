package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Proposed controller integration test. Requires one trailing defaulted constructor seam:
 * maxEventRetainedChars: Long = 8_000_000L. No database, process, provider or large strings. */
class ExternalAgentEventRetentionRegressionTest {
    private val spec = WorkspaceAgentPluginCatalog.entries.first()

    private class MemoryHistory : PluginConversationHistory {
        var window: PluginHistoryWindow? = null
        var partial = ""
        var finalReply: String? = null
        var finalStatus: PluginHistoryStatus? = null
        var finalNotices: List<String> = emptyList()
        override suspend fun listPage(agentId: String?, query: String, before: PluginHistoryCursor?, limit: Int) =
            PluginHistoryPage(listOfNotNull(window?.summary), null)
        override suspend fun latest(agentId: String) = window
        override suspend fun load(id: String, beforePart: Long?) = window
        override suspend fun beginTurn(id: String?, agentId: String, prompt: String, mode: WorkspaceAgentMode, nativeSessionId: String?): PluginHistoryWindow {
            return PluginHistoryWindow(
                PluginHistorySummary("tiny-history", agentId, prompt, 1, 1, nativeSessionId, mode, null, 1, 1, PluginHistoryStatus.RUNNING),
                listOf(AgentMessage("user", prompt)), listOf(0L), 0, 1,
            ).also { window = it }
        }
        override suspend fun bind(id: String, nativeSessionId: String, modelId: String?) {
            window = window?.let { it.copy(summary = it.summary.copy(nativeSessionId = nativeSessionId, modelId = modelId)) }
        }
        override suspend fun appendPartial(id: String, fragments: List<String>) { partial += fragments.joinToString("") }
        override suspend fun finish(id: String, reply: String?, notices: List<String>, status: PluginHistoryStatus): PluginHistoryWindow {
            finalReply = reply; finalStatus = status; finalNotices = notices
            val current = requireNotNull(window)
            val text = reply ?: partial
            val messages = current.messages.take(1) + listOf(AgentMessage("assistant", text)) + notices.map { AgentMessage("assistant", it) }
            return current.copy(summary = current.summary.copy(status = status, messageCount = messages.size, partCount = messages.size.toLong()),
                messages = messages, messageKeys = messages.indices.map { it.toLong() }, endPart = messages.size.toLong()).also { window = it }
        }
        override suspend fun delete(id: String) { window = null }
        override suspend fun close() = Unit
    }

    private class BurstBackend(private val overflow: Boolean, private val returnDespiteCancellation: Boolean = false) : WorkspaceAgentChatBackend {
        val started = CompletableDeferred<Unit>()
        val emit = CompletableDeferred<Unit>()
        var calls = 0
        override suspend fun runTurn(agentId: String, prompt: String, mode: WorkspaceAgentMode, resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit): WorkspaceAgentTurnResult {
            calls++
            started.complete(Unit)
            emit.await()
            // No suspension: same-dispatcher relay cannot consume until the tiny burst finishes.
            onEvent(WorkspaceAgentEvent.Text("one"))
            onEvent(WorkspaceAgentEvent.Text("two"))
            if (overflow) {
                onEvent(WorkspaceAgentEvent.Text("gap"))
                onEvent(WorkspaceAgentEvent.Text("late"))
                if (!returnDespiteCancellation) awaitCancellation()
            }
            // Empty result deliberately requires the fully drained live stream fallback.
            return WorkspaceAgentTurnResult(agentId, "native", if (returnDespiteCancellation) "must-not-finalize" else "", "end_turn", emptyList(), emptyList())
        }
        override suspend fun prepareSession(agentId: String, resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit) =
            WorkspaceAgentEvent.Configuration("native", WorkspaceAgentSessionConfiguration())
        override suspend fun selectModel(agentId: String, expectedSessionId: String, modelId: String): WorkspaceAgentEvent.Configuration = error("Unexpected model change")
        override suspend fun newSession(agentId: String, onEvent: (WorkspaceAgentEvent) -> Unit): String = error("Unexpected new session")
        override suspend fun cancel(agentId: String, expectedSessionId: String?) = Unit
    }

    private fun exercise(overflow: Boolean, returnDespiteCancellation: Boolean = false) = runBlocking {
        withTimeout(5_000) {
            val errors = mutableListOf<Throwable>()
            val owner = SupervisorJob(coroutineContext[Job])
            val scope = CoroutineScope(coroutineContext + owner + CoroutineExceptionHandler { _, failure -> errors += failure })
            val store = MemoryHistory()
            val backend = BurstBackend(overflow, returnDespiteCancellation)
            val controller = ExternalAgentSessionController(scope, history = store, maxEventRetainedChars = 134L, workAdmission = { true })
            suspend fun idle() { while (controller.busy) delay(1) }
            try {
                controller.prepare(backend, spec)
                idle()
                assertTrue(controller.submit(backend, spec, "first", WorkspaceAgentMode.EDIT))
                backend.started.await()
                if (overflow) assertTrue(controller.submit(backend, spec, "queued", WorkspaceAgentMode.EDIT, AgentPromptDelivery.QUEUE))
                backend.emit.complete(Unit)
                idle()
                assertEquals(1, backend.calls)
                assertTrue(errors.isEmpty())
                if (overflow) {
                    assertEquals("onetwo", store.partial)
                    assertNull(store.finalReply)
                    assertEquals(PluginHistoryStatus.FAILED, store.finalStatus)
                    assertTrue(store.finalNotices.any { it.contains("stopped", ignoreCase = true) && it.contains("memory", ignoreCase = true) })
                    assertEquals(1, controller.pendingPrompts.size)
                    assertFalse(requireNotNull(store.window).messages.any { it.content.contains("gap") || it.content.contains("late") })
                    if (returnDespiteCancellation) assertFalse(requireNotNull(store.window).messages.any { it.content == "must-not-finalize" })
                } else {
                    assertEquals("onetwo", store.finalReply)
                    assertEquals(PluginHistoryStatus.COMPLETED, store.finalStatus)
                }
            } finally {
                backend.emit.complete(Unit)
                controller.close()
                withContext(NonCancellable) { withTimeout(1_000) { owner.cancelAndJoin() } }
            }
        }
    }

    @Test(timeout = 10_000) fun overflowDrainsAcceptedPartialAndDoesNotPromoteQueuedPrompt() = exercise(true)
    @Test(timeout = 10_000) fun normalCompletionDrainsAcceptedStreamBeforeFinishingHistory() = exercise(false)
    @Test(timeout = 10_000) fun synchronousOverflowReturningNormallyStillFailsWithoutPromotingQueue() =
        exercise(overflow = true, returnDespiteCancellation = true)

}
