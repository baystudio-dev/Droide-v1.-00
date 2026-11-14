package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Candidate sources only. NOT compiled or executed. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class ExternalAgentModeConfirmationTest {
    @Before fun explicitLaunch() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
    }
    private val spec = WorkspaceAgentPluginCatalog.entries.single { it.id == "claude" }
    private class Backend : WorkspaceAgentChatBackend {
        var configuration = WorkspaceAgentSessionConfiguration(
            models = listOf(WorkspaceAgentModelOption("one", "One"), WorkspaceAgentModelOption("two", "Two")),
            currentModelId = "one", modes = listOf("default", "plan"), currentModeId = "plan")
        var failTurn = false
        var prepareSessionId = "confirmed_session"
        var turnSessionId = "confirmed_session"
        var holdPrepare: CompletableDeferred<Unit>? = null
        var holdTurn: CompletableDeferred<Unit>? = null
        var holdTurnCleanup: CompletableDeferred<Unit>? = null
        val turnStarted = CompletableDeferred<Unit>()
        val turnCleanupStarted = CompletableDeferred<Unit>()
        var holdModel: CompletableDeferred<Unit>? = null
        val modelStarted = CompletableDeferred<Unit>()
        val receivedModes = mutableListOf<WorkspaceAgentMode>()
        override suspend fun prepareSession(agentId: String, resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit): WorkspaceAgentEvent.Configuration {
            onEvent(WorkspaceAgentEvent.SessionBound(prepareSessionId))
            holdPrepare?.await()
            return WorkspaceAgentEvent.Configuration(prepareSessionId, configuration)
        }
        override suspend fun runTurn(agentId: String, prompt: String, mode: WorkspaceAgentMode, resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit): WorkspaceAgentTurnResult {
            receivedModes += mode
            if (failTurn) error("CLI mode was not confirmed. No prompt was sent.")
            try {
                onEvent(WorkspaceAgentEvent.SessionBound(turnSessionId))
                turnStarted.complete(Unit)
                holdTurn?.await()
                configuration = configuration.copy(currentModeId = "default")
                onEvent(WorkspaceAgentEvent.Configuration(turnSessionId, configuration))
                return WorkspaceAgentTurnResult(agentId, turnSessionId, "Done", "end_turn", emptyList(), emptyList())
            } finally {
                if (!currentCoroutineContext().isActive) {
                    turnCleanupStarted.complete(Unit)
                    holdTurnCleanup?.let { withContext(NonCancellable) { it.await() } }
                }
            }
        }
        override suspend fun selectModel(agentId: String, expectedSessionId: String, modelId: String): WorkspaceAgentEvent.Configuration {
            modelStarted.complete(Unit)
            holdModel?.await()
            return WorkspaceAgentEvent.Configuration(expectedSessionId, configuration.copy(currentModelId = modelId))
        }
        override suspend fun newSession(agentId: String, onEvent: (WorkspaceAgentEvent) -> Unit) = "confirmed_session"
        override suspend fun cancel(agentId: String, expectedSessionId: String?) = Unit
    }
    private suspend fun ExternalAgentSessionController.idle() = withTimeout(2_000) { while (busy) delay(1) }
    private fun fixture(block: suspend (ExternalAgentSessionController, Backend) -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val controller = ExternalAgentSessionController(scope)
        val backend = Backend()
        try {
            withTimeout(6_000) {
                controller.prepare(backend, spec)
                controller.idle()
                assertEquals("plan", controller[spec.id].configuration?.currentModeId)
                block(controller, backend)
            }
        } finally { controller.close(); scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun failedNativeTransitionIsUnconfirmedWithoutLosingConversationIdentity() = fixture { controller, backend ->
        backend.failTurn = true
        assertTrue(controller.submit(backend, spec, "Build the change", WorkspaceAgentMode.EDIT))
        controller.idle()
        assertNull(controller[spec.id].configuration)
        assertEquals("confirmed_session", controller[spec.id].sessionId)
        assertTrue(controller[spec.id].messages.any { it.content.contains("CLI mode was not confirmed") })
    }

    @Test fun stopDuringConfigurationInvalidatesConfirmationImmediatelyAndAfterCleanup() = fixture { controller, backend ->
        backend.holdModel = CompletableDeferred()
        controller.selectModel(backend, spec, "two")
        backend.modelStarted.await()
        controller.cancel(backend)
        assertNull(controller[spec.id].configuration)
        controller.idle()
        assertNull(controller[spec.id].configuration)
        assertEquals("confirmed_session", controller[spec.id].sessionId)
    }

    @Test fun stoppingLazyConfigurationBeforeDispatchDoesNotLeaveAConfirmedMode() = fixture { controller, backend ->
        backend.holdModel = CompletableDeferred()
        controller.selectModel(backend, spec, "two")
        controller.cancel(backend)
        assertNull(controller[spec.id].configuration)
        controller.idle()
        assertNull(controller[spec.id].configuration)
    }

    @Test fun auditWordsDoNotChangeTheExplicitBuildMode() = fixture { controller, backend ->
        assertTrue(controller.submit(backend, spec, "Audit and review this project", WorkspaceAgentMode.EDIT))
        controller.idle()
        assertEquals(listOf(WorkspaceAgentMode.EDIT), backend.receivedModes)
        assertEquals("default", controller[spec.id].configuration?.currentModeId)
    }

    @Test fun replacementSessionDoesNotInheritThePreviousSessionConfirmation() = fixture { controller, backend ->
        val gate = CompletableDeferred<Unit>()
        backend.turnSessionId = "replacement_session"
        backend.holdTurn = gate
        try {
            assertTrue(controller.submit(backend, spec, "Next turn", WorkspaceAgentMode.EDIT))
            backend.turnStarted.await()
            withTimeout(2_000) { while (controller[spec.id].sessionId != "replacement_session") delay(1) }
            assertNull(controller[spec.id].configuration)
        } finally { gate.complete(Unit) }
        controller.idle()
        assertEquals("default", controller[spec.id].configuration?.currentModeId)
    }

    @Test fun lifecycleSessionBindingClearsOldConfirmationBeforeItsSnapshotArrives() = fixture { controller, backend ->
        val gate = CompletableDeferred<Unit>()
        backend.prepareSessionId = "replacement_session"
        backend.holdPrepare = gate
        try {
            controller.prepare(backend, spec, force = true)
            withTimeout(2_000) { while (controller[spec.id].sessionId != "replacement_session") delay(1) }
            assertNull(controller[spec.id].configuration)
        } finally { gate.complete(Unit) }
        controller.idle()
        assertEquals("plan", controller[spec.id].configuration?.currentModeId)
    }

    @Test fun drainingAnAcceptedConfigurationDuringStopCannotRestoreConfirmation() = fixture { controller, backend ->
        backend.holdTurn = CompletableDeferred()
        val cleanup = CompletableDeferred<Unit>()
        backend.holdTurnCleanup = cleanup
        try {
            assertTrue(controller.submit(backend, spec, "Stop this turn", WorkspaceAgentMode.EDIT))
            backend.turnStarted.await()
            controller.cancel(backend)
            backend.turnCleanupStarted.await()
            val runId = checkNotNull(controller.activeRun).runId
            // Model an already-accepted relay event while owned cleanup is held open.
            // This exercises the real handler without adding a production test seam.
            val apply = controller.javaClass.getDeclaredMethod("applyEvent", java.lang.Long.TYPE,
                String::class.java, WorkspaceAgentEvent::class.java).apply { isAccessible = true }
            apply.invoke(controller, runId, spec.id,
                WorkspaceAgentEvent.Configuration("confirmed_session", backend.configuration))
            assertNull(controller[spec.id].configuration)
        } finally { cleanup.complete(Unit) }
        controller.idle()
        assertNull(controller[spec.id].configuration)
    }
}
