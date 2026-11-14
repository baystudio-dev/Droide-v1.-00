package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ExternalAgentPromptStreamingTest {
    private val spec = WorkspaceAgentPluginCatalog.entries.first()
    private class Backend : WorkspaceAgentChatBackend {
        var config = WorkspaceAgentSessionConfiguration(
            models = listOf(WorkspaceAgentModelOption("model", "Model")), currentModelId = "model", modelConfigId = "model",
            reasoningOptions = listOf(WorkspaceAgentModelOption("low", "Low"), WorkspaceAgentModelOption("high", "High")),
            currentReasoningId = "low", reasoningConfigId = "effort")
        val started = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val prompts = mutableListOf<Pair<String, String?>>()
        var newCalls = 0
        var steerCalls = 0
        var clearCalls = 0
        var failFirst = false
        var configurationChange = false
        var stopReason = "end_turn"
        var acceptsSteer = false
        override suspend fun prepareSession(agentId: String, resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit) =
            WorkspaceAgentEvent.Configuration("native-kept", config)
        override suspend fun selectModel(agentId: String, expectedSessionId: String, modelId: String) =
            WorkspaceAgentEvent.Configuration(expectedSessionId, config)
        override suspend fun selectReasoning(agentId: String, expectedSessionId: String, value: String): WorkspaceAgentEvent.Configuration {
            config = config.copy(currentReasoningId = value)
            return WorkspaceAgentEvent.Configuration(expectedSessionId, config)
        }
        override suspend fun newSession(agentId: String, onEvent: (WorkspaceAgentEvent) -> Unit): String { newCalls++; return "new" }
        override suspend fun cancel(agentId: String, expectedSessionId: String?) = Unit
        override suspend fun requestSteer(agentId: String, expectedSessionId: String): Boolean {
            steerCalls++
            if (acceptsSteer) { stopReason = "cancelled"; releaseFirst.complete(Unit) }
            return acceptsSteer
        }
        override suspend fun clearSteerRequest(agentId: String, expectedSessionId: String) { clearCalls++ }
        override suspend fun runTurn(agentId: String, prompt: String, mode: WorkspaceAgentMode,
                                     resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit): WorkspaceAgentTurnResult {
            prompts += prompt to resumeSessionId
            onEvent(WorkspaceAgentEvent.SessionBound("native-kept"))
            onEvent(WorkspaceAgentEvent.Configuration("native-kept", config))
            onEvent(WorkspaceAgentEvent.Thought("Checking the workspace…"))
            onEvent(WorkspaceAgentEvent.Text("First "))
            if (prompts.size == 1) {
                started.complete(Unit); releaseFirst.await()
                if (failFirst) error("fixture provider failed")
                if (configurationChange) { config = config.copy(currentReasoningId = "high"); onEvent(WorkspaceAgentEvent.Configuration("native-kept", config)) }
            }
            onEvent(WorkspaceAgentEvent.Text("reply 🚀"))
            return WorkspaceAgentTurnResult(agentId, "native-kept", "First reply 🚀", if (prompts.size == 1) stopReason else "end_turn", emptyList(), emptyList())
        }
    }

    private fun fixture(block: suspend (ExternalAgentSessionController, Backend) -> Unit) = runBlocking {
        withTimeout(8_000) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
            // This pure controller fixture has no Application startup. Model an explicitly
            // opened app session through the existing seam; retain the production Exit fence.
            val controller = ExternalAgentSessionController(scope, workAdmission = { true })
            val host = Backend()
            try { block(controller, host) }
            finally { controller.close(); scope.coroutineContext.job.cancelAndJoin() }
        }
    }
    private suspend fun ExternalAgentSessionController.idle() { withTimeout(4_000) { while (busy) delay(1) } }
    private suspend fun begin(controller: ExternalAgentSessionController, host: Backend) {
        controller.prepare(host, spec); controller.idle()
        assertTrue(controller.submit(host, spec, "first", WorkspaceAgentMode.EDIT)); host.started.await()
        withTimeout(2_000) { while (controller[spec.id].liveText != "First ") delay(1) }
    }

    @Test fun streamedThoughtAndAnswerAreVisibleBeforeTheTurnCompletesAndFinalAnswerIsNotDuplicated() = fixture { controller, host ->
        begin(controller, host)
        assertTrue(controller.busy)
        assertEquals("Checking the workspace…", controller[spec.id].liveThought)
        assertEquals("First ", controller[spec.id].liveText)
        assertEquals(listOf("user"), controller[spec.id].messages.map { it.role })
        host.releaseFirst.complete(Unit); controller.idle()
        assertEquals(listOf("first", "First reply 🚀"), controller[spec.id].messages.map { it.content })
        assertEquals("", controller[spec.id].liveText); assertEquals("", controller[spec.id].liveThought)
    }

    @Test fun queuedPromptsWaitAndThenRunInFifoOnTheSameNativeSession() = fixture { controller, host ->
        begin(controller, host)
        assertTrue(controller.submit(host, spec, "second", WorkspaceAgentMode.EDIT, AgentPromptDelivery.QUEUE))
        assertTrue(controller.submit(host, spec, "third", WorkspaceAgentMode.PLAN, AgentPromptDelivery.QUEUE))
        assertEquals(1, host.prompts.size); assertEquals(2, controller.pendingPrompts.size)
        host.releaseFirst.complete(Unit); controller.idle()
        assertEquals(listOf("first", "second", "third"), host.prompts.map { it.first })
        assertTrue(host.prompts.all { it.second == "native-kept" }); assertEquals(0, host.newCalls)
        assertTrue(controller.pendingPrompts.isEmpty()); assertEquals(0, host.steerCalls)
    }

    @Test fun steerHasPriorityOverQueuedFollowUpsAndCannotChangeCliEngine() = fixture { controller, host ->
        begin(controller, host)
        controller.submit(host, spec, "queued", WorkspaceAgentMode.EDIT, AgentPromptDelivery.QUEUE)
        controller.submit(host, spec, "redirect", WorkspaceAgentMode.EDIT, AgentPromptDelivery.STEER)
        assertFalse(controller.submit(host, WorkspaceAgentPluginCatalog.entries[1], "wrong engine", WorkspaceAgentMode.EDIT))
        yield(); assertEquals(1, host.steerCalls); assertEquals(1, host.prompts.size)
        host.releaseFirst.complete(Unit); controller.idle()
        assertEquals(listOf("first", "redirect", "queued"), host.prompts.map { it.first })
        assertEquals(0, host.newCalls)
    }

    @Test fun stopPreservesPartialAnswerAndDoesNotAutomaticallySendPendingInputs() = fixture { controller, host ->
        begin(controller, host)
        controller.submit(host, spec, "pending", WorkspaceAgentMode.EDIT, AgentPromptDelivery.QUEUE)
        controller.cancel(host); controller.idle()
        assertEquals(1, host.prompts.size)
        assertEquals("pending", controller.pendingPrompts.single().input.text)
        assertTrue(controller[spec.id].messages.any { it.role == "assistant" && it.content == "First " })
        controller.newSession(host, spec); yield(); assertEquals(0, host.newCalls)
    }

    @Test fun failedTurnKeepsItsPartialAnswerAndPausesTheQueueWithoutReplay() = fixture { controller, host ->
        begin(controller, host); host.failFirst = true
        controller.submit(host, spec, "pending", WorkspaceAgentMode.EDIT, AgentPromptDelivery.QUEUE)
        host.releaseFirst.complete(Unit); controller.idle()
        assertEquals(1, host.prompts.size); assertEquals(1, controller.pendingPrompts.size)
        assertTrue(controller[spec.id].messages.any { it.content == "First " })
        assertTrue(controller[spec.id].messages.last().content.contains("fixture provider failed"))
    }

    @Test fun changedReasoningConfigurationPausesPinnedPendingInputs() = fixture { controller, host ->
        begin(controller, host)
        controller.submit(host, spec, "old reasoning", WorkspaceAgentMode.EDIT, AgentPromptDelivery.QUEUE)
        host.configurationChange = true; host.releaseFirst.complete(Unit); controller.idle()
        assertEquals(1, host.prompts.size); assertEquals(1, controller.pendingPrompts.size)
        assertTrue(controller[spec.id].connectionError!!.contains("paused"))
    }

    @Test fun removingAnUnsentSteerClearsItsBoundaryRequest() = fixture { controller, host ->
        begin(controller, host)
        controller.submit(host, spec, "remove me", WorkspaceAgentMode.EDIT, AgentPromptDelivery.STEER)
        yield(); controller.removePendingInput(controller.pendingPrompts.single().input.id); yield()
        assertEquals(1, host.clearCalls); assertTrue(controller.pendingPrompts.isEmpty())
        host.releaseFirst.complete(Unit); controller.idle(); assertEquals(1, host.prompts.size)
    }

    @Test fun queueCapacityRejectsNewDraftsWithoutReplacingAcceptedInputs() = fixture { controller, host ->
        begin(controller, host)
        repeat(16) { assertTrue(controller.submit(host, spec, "queued-$it", WorkspaceAgentMode.EDIT, AgentPromptDelivery.QUEUE)) }
        assertFalse(controller.submit(host, spec, "keep this draft", WorkspaceAgentMode.EDIT, AgentPromptDelivery.QUEUE))
        assertEquals(16, controller.pendingPrompts.size); assertEquals(1, host.prompts.size)
        controller.cancel(host); controller.idle()
    }

    @Test fun selectingReasoningKeepsMessagesAndSessionAndIsAppliedOnlyToFutureTurns() = fixture { controller, host ->
        begin(controller, host); host.releaseFirst.complete(Unit); controller.idle()
        val messages = controller[spec.id].messages
        controller.selectReasoning(host, spec, "high"); controller.idle()
        assertEquals("high", controller[spec.id].configuration!!.currentReasoningId)
        assertEquals(messages, controller[spec.id].messages); assertEquals("native-kept", controller[spec.id].sessionId)
        controller.submit(host, spec, "follow up", WorkspaceAgentMode.EDIT); controller.idle()
        assertEquals("native-kept", host.prompts.last().second); assertEquals(0, host.newCalls)
    }

    @Test fun aCancelledCliResponseCannotAutomaticallyPromoteOrdinaryQueuedInputs() = fixture { controller, host ->
        begin(controller, host)
        controller.submit(host, spec, "pending", WorkspaceAgentMode.EDIT, AgentPromptDelivery.QUEUE)
        host.stopReason = "cancelled"; host.releaseFirst.complete(Unit); controller.idle()
        assertEquals(1, host.prompts.size); assertEquals(1, controller.pendingPrompts.size)
        controller.selectReasoning(host, spec, "high"); yield()
        assertEquals("low", controller[spec.id].configuration!!.currentReasoningId)
        assertTrue(controller[spec.id].connectionError!!.contains("Remove pending inputs"))
    }

    @Test fun anAcceptedSteerContinuesAfterCancelledResponseWithoutStartingANewNativeSession() = fixture { controller, host ->
        begin(controller, host); host.acceptsSteer = true
        controller.submit(host, spec, "queued", WorkspaceAgentMode.EDIT, AgentPromptDelivery.QUEUE)
        controller.submit(host, spec, "redirect", WorkspaceAgentMode.EDIT, AgentPromptDelivery.STEER)
        controller.idle()
        assertEquals(listOf("first", "redirect", "queued"), host.prompts.map { it.first })
        assertTrue(host.prompts.all { it.second == "native-kept" })
        assertEquals(1, host.steerCalls); assertEquals(0, host.newCalls)
        assertTrue(controller.pendingPrompts.isEmpty())
    }
}
