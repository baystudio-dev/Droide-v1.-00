package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class ExternalAgentSessionControllerTest {
    @Before fun admitExplicitUserLaunch() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
    }
    private val spec = WorkspaceAgentPluginCatalog.entries.first()
    private val models = WorkspaceAgentSessionConfiguration(
        models = listOf(WorkspaceAgentModelOption("provider/first", "First"), WorkspaceAgentModelOption("provider/second", "Second")),
        currentModelId = "provider/first", modelConfigId = "model",
    )

    private class Backend(var configuration: WorkspaceAgentSessionConfiguration) : WorkspaceAgentChatBackend {
        var prepareCalls = 0
        var newCalls = 0
        var modelCalls = 0
        var reviewCalls = 0
        var reviewResult = WorkspaceAgentSyncResult(listOf("accepted.html"), emptyList())
        override val workspaceSyncState = kotlinx.coroutines.flow.MutableStateFlow(WorkspaceAgentSyncState(sourceRoot = "/project"))
        var holdReview: CompletableDeferred<Unit>? = null
        val reviewStarted = CompletableDeferred<Unit>()
        var failure: String? = null
        var holdPrepare: CompletableDeferred<Unit>? = null
        var holdTurn: CompletableDeferred<Unit>? = null
        val prepareStarted = CompletableDeferred<Unit>()
        val turnStarted = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val callbacks = mutableListOf<(WorkspaceAgentEvent) -> Unit>()
        val prompts = mutableListOf<Pair<String, String?>>()
        val selectedSessions = mutableListOf<String>()

        override suspend fun prepareSession(agentId: String, resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit): WorkspaceAgentEvent.Configuration {
            prepareCalls++; callbacks += onEvent
            prepareStarted.complete(Unit)
            try {
                holdPrepare?.await()
                failure?.let { error(it) }
                onEvent(WorkspaceAgentEvent.SessionBound("same_session"))
                yield()
                return WorkspaceAgentEvent.Configuration("same_session", configuration)
            } finally { if (!currentCoroutineContext().isActive) cancelled.complete(Unit) }
        }

        override suspend fun runTurn(agentId: String, prompt: String, mode: WorkspaceAgentMode, resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit): WorkspaceAgentTurnResult {
            prompts += prompt to resumeSessionId
            turnStarted.complete(Unit)
            try {
                holdTurn?.await()
                onEvent(WorkspaceAgentEvent.SessionBound("same_session"))
                onEvent(WorkspaceAgentEvent.Text("Live reply"))
                yield()
                return WorkspaceAgentTurnResult(agentId, "same_session", "Reply to $prompt", "end_turn", emptyList(), emptyList())
            } finally { if (!currentCoroutineContext().isActive) cancelled.complete(Unit) }
        }

        override suspend fun selectModel(agentId: String, expectedSessionId: String, modelId: String): WorkspaceAgentEvent.Configuration {
            modelCalls++; selectedSessions += expectedSessionId
            configuration = configuration.copy(currentModelId = modelId)
            return WorkspaceAgentEvent.Configuration(expectedSessionId, configuration)
        }
        override suspend fun newSession(agentId: String, onEvent: (WorkspaceAgentEvent) -> Unit): String { newCalls++; return "same_session" }
        override suspend fun reviewWorkspaceChanges(agentId: String): WorkspaceAgentSyncResult {
            reviewCalls++
            reviewStarted.complete(Unit)
            holdReview?.await()
            return reviewResult
        }
        override suspend fun cancel(agentId: String, expectedSessionId: String?) = Unit
    }

    private fun fixture(block: suspend (CoroutineScope, ExternalAgentSessionController, Backend) -> Unit) = runBlocking {
        withTimeout(6_000) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
            val controller = ExternalAgentSessionController(scope)
            try { block(scope, controller, Backend(models)) }
            finally { scope.coroutineContext.job.cancelAndJoin() }
        }
    }
    private suspend fun ExternalAgentSessionController.idle() { withTimeout(2_000) { while (busy) delay(1) } }

    @Test fun workspaceReviewUsesRetainedOwnerWithoutStartingOrPromptingCli() = fixture { parent, controller, host ->
        val panel = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
        host.holdReview = CompletableDeferred()
        controller.reviewWorkspaceChanges(host, spec)
        host.reviewStarted.await()
        assertTrue(controller.busy)
        controller.reviewWorkspaceChanges(host, spec)
        panel.cancel()
        assertTrue(controller.busy)
        host.holdReview!!.complete(Unit)
        controller.idle()
        assertEquals(1, host.reviewCalls)
        assertEquals(0, host.prepareCalls)
        assertEquals(0, host.newCalls)
        assertTrue(host.prompts.isEmpty())
        assertEquals(listOf("Workspace changes applied: accepted.html", "Workspace review complete. All reviewed changes were applied."), controller[spec.id].messages.map { it.content })
    }

    @Test fun manualPendingReviewSurfacesReasonAndSuccessfulRetryWithoutPrompt() = fixture { _, controller, host ->
        host.reviewResult = WorkspaceAgentSyncResult(emptyList(), listOf("pending.html"))
        host.workspaceSyncState.value = WorkspaceAgentSyncState(WorkspaceAgentSyncPhase.PENDING, "/project", pendingPaths = listOf("pending.html"), pendingCount = 1, notice = "Local file changed during approval.")
        controller.reviewWorkspaceChanges(host, spec)
        controller.idle()
        assertTrue(controller[spec.id].messages.last().content.contains("Local file changed during approval."))
        host.reviewResult = WorkspaceAgentSyncResult(listOf("pending.html"), emptyList())
        host.workspaceSyncState.value = WorkspaceAgentSyncState(WorkspaceAgentSyncPhase.SYNCED, "/project")
        controller.reviewWorkspaceChanges(host, spec)
        controller.idle()
        assertTrue(controller[spec.id].messages.last().content.contains("All reviewed changes were applied"))
        assertEquals(2, host.reviewCalls)
        assertTrue(host.prompts.isEmpty())
    }

    @Test fun explicitStopCancelsWorkspaceReviewAndRetiredOwnerCannotRestartIt() = fixture { _, controller, host ->
        host.holdReview = CompletableDeferred()
        controller.reviewWorkspaceChanges(host, spec)
        host.reviewStarted.await()
        controller.cancel(host)
        controller.idle()
        controller.close()
        controller.reviewWorkspaceChanges(host, spec)
        assertEquals(1, host.reviewCalls)
        assertFalse(controller.busy)
    }

    @Test fun durableApplicationExitFenceRejectsReviewAndNewPrompts() = fixture { _, controller, host ->
        ApplicationWorkAdmission.closeForExit()
        controller.reviewWorkspaceChanges(host, spec)
        assertFalse(controller.submit(host, spec, "keep this draft", WorkspaceAgentMode.EDIT))
        assertEquals(0, host.reviewCalls)
        assertTrue(host.prompts.isEmpty())
        assertFalse(controller.busy)
    }

    @Test fun canceledOwnerRejectsARequestWithoutConsumingThePromptOrLeavingBusyState() = fixture { scope, controller, host ->
        scope.cancel()
        assertFalse(controller.submit(host, spec, "keep this draft", WorkspaceAgentMode.EDIT))
        assertFalse(controller.busy)
        assertTrue(controller[spec.id].messages.isEmpty())
        assertTrue(host.prompts.isEmpty())
    }

    @Test fun workspaceReplacementCancelsItsOldRunAndRejectsLateSubmissions() = fixture { _, controller, host ->
        host.holdTurn = CompletableDeferred()
        assertTrue(controller.submit(host, spec, "old workspace", WorkspaceAgentMode.EDIT))
        host.turnStarted.await()
        controller.close()
        host.cancelled.await()
        assertFalse(controller.busy)
        assertFalse(controller.submit(host, spec, "late old panel", WorkspaceAgentMode.EDIT))
        assertEquals(listOf("old workspace"), host.prompts.map { it.first })
    }

    @Test fun retainedWorkspaceStateKeepsExternalWorkAndDraftWhenAPanelScopeIsCancelled() = fixture { parent, _, host ->
        val retained = AgentWorkspaceUiState(parent)
        val panel = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
        try {
            retained.input.value = "next question draft"
            retained.backendId.value = spec.id
            host.holdTurn = CompletableDeferred()
            assertTrue(retained.externalController.submit(host, spec, "long operation", WorkspaceAgentMode.EDIT))
            host.turnStarted.await()
            panel.cancel("Panel removed")
            assertTrue(retained.externalController.busy)
            assertFalse(host.cancelled.isCompleted)
            assertEquals("next question draft", retained.input.value)
            host.holdTurn!!.complete(Unit)
            retained.externalController.idle()
            assertEquals("Reply to long operation", retained.externalController[spec.id].messages.last().content)
            assertEquals(spec.id, retained.backendId.value)
        } finally { retained.close(); panel.cancel() }
    }

    @Test fun changingModelsKeepsSessionHistoryAndSendsOnlyTheNextUserPrompt() = fixture { _, controller, host ->
        controller.prepare(host, spec); controller.idle()
        controller.submit(host, spec, "First question", WorkspaceAgentMode.EDIT); controller.idle()
        val history = controller[spec.id].messages
        controller.selectModel(host, spec, "provider/second"); controller.idle()
        assertEquals("same_session", controller[spec.id].sessionId)
        assertEquals(history, controller[spec.id].messages)
        assertEquals("provider/second", controller[spec.id].configuration!!.currentModelId)
        controller.submit(host, spec, "Follow up", WorkspaceAgentMode.EDIT); controller.idle()
        assertEquals(listOf("First question" to "same_session", "Follow up" to "same_session"), host.prompts)
        assertEquals(listOf("same_session"), host.selectedSessions)
        assertEquals(0, host.newCalls)
        assertEquals(1, host.prepareCalls)
        assertEquals(4, controller[spec.id].messages.size)
    }

    @Test fun stopCancelsBootstrapBeforeASessionIdExistsAndAllowsRetry() = fixture { _, controller, host ->
        host.holdPrepare = CompletableDeferred()
        controller.prepare(host, spec)
        host.prepareStarted.await()
        assertNull(controller[spec.id].sessionId)
        controller.cancel(host)
        controller.idle()
        assertTrue(host.cancelled.isCompleted)
        assertEquals(listOf("Stopped."), controller[spec.id].activity)
        assertNull(controller[spec.id].connectionError)
        host.holdPrepare = null
        controller.prepare(host, spec); controller.idle()
        assertEquals("same_session", controller[spec.id].sessionId)
        assertEquals(0, host.newCalls)
    }

    @Test fun stopBeforeDispatchDoesNotLeaveTheControlsPermanentlyBusy() = fixture { _, controller, host ->
        controller.prepare(host, spec)
        controller.cancel(host)
        controller.idle()
        assertNull(controller.lifecycleBusyAgentId)
        assertNull(controller.activeRun)
        controller.submit(host, spec, "hello", WorkspaceAgentMode.EDIT)
        controller.cancel(host)
        controller.idle()
        assertNull(controller.activeRun)
    }

    @Test fun stopAlsoReachesATurnStillStartingWithoutPublishingASession() = fixture { _, controller, host ->
        host.holdTurn = CompletableDeferred()
        controller.submit(host, spec, "hello", WorkspaceAgentMode.EDIT)
        host.turnStarted.await()
        assertNull(controller.activeRun!!.sessionId)
        controller.cancel(host); controller.idle()
        assertTrue(host.cancelled.isCompleted)
        assertEquals(listOf("Stopped."), controller[spec.id].activity)
        assertEquals(listOf("hello"), controller[spec.id].messages.map { it.content })
    }

    @Test fun authenticationFailureIsVisibleAndRefreshDoesNotEraseTheConversation() = fixture { _, controller, host ->
        controller.prepare(host, spec); controller.idle()
        controller.submit(host, spec, "hello", WorkspaceAgentMode.EDIT); controller.idle()
        val history = controller[spec.id].messages
        host.failure = "Sign in is required"
        controller.prepare(host, spec, force = true); controller.idle()
        assertEquals("Sign in is required", controller[spec.id].connectionError)
        assertEquals(history, controller[spec.id].messages)
        host.failure = null
        controller.prepare(host, spec, force = true); controller.idle()
        assertNull(controller[spec.id].connectionError)
        assertEquals(history, controller[spec.id].messages)
        assertEquals(0, host.newCalls)
    }

    @Test fun staleStartupCallbacksCannotBindToALaterRetryForTheSameAgent() = fixture { _, controller, host ->
        host.failure = "Failed first attempt"
        controller.prepare(host, spec); controller.idle()
        val oldCallback = host.callbacks.single()
        host.failure = null; host.holdPrepare = CompletableDeferred()
        controller.prepare(host, spec)
        while (host.callbacks.size < 2) yield()
        oldCallback(WorkspaceAgentEvent.Configuration("stale_session", models.copy(currentModelId = "provider/second")))
        yield()
        assertNull(controller[spec.id].sessionId)
        host.holdPrepare!!.complete(Unit); controller.idle()
        assertEquals("same_session", controller[spec.id].sessionId)
    }

    @Test fun unknownModelsCannotReachTheBackendAndPrepareIsCached() = fixture { _, controller, host ->
        controller.prepare(host, spec); controller.idle()
        controller.prepare(host, spec); controller.selectModel(host, spec, "invented/model")
        controller.idle()
        assertEquals(1, host.prepareCalls)
        assertEquals(0, host.modelCalls)
    }
}
