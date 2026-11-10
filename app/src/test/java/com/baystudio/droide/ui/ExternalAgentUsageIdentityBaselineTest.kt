package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Existing controller/backend API only: no Android application, store, process, network, or provider. */
class ExternalAgentUsageIdentityBaselineTest {
    @get:org.junit.Rule
    val applicationAdmission: org.junit.rules.TestRule = com.baystudio.droide.core.ApplicationWorkAdmissionFixture()

    private val spec = WorkspaceAgentPluginCatalog.entries.first()
    private val reported = WorkspaceAgentUsageSnapshot(96_000, 128_000, 1.25, "EUR")

    private class Backend : WorkspaceAgentChatBackend {
        var nativeId = "native-a"
        var turnUsage: WorkspaceAgentUsageSnapshot? = null
        var prepareUsage: WorkspaceAgentUsageSnapshot? = null
        var emitPrepareEvents = true
        var holdPrepare: CompletableDeferred<Unit>? = null
        var prepareStarted = CompletableDeferred<Unit>()
        val lifecycleCallbacks = mutableListOf<(WorkspaceAgentEvent) -> Unit>()
        val config = WorkspaceAgentSessionConfiguration()

        override suspend fun runTurn(agentId: String, prompt: String, mode: WorkspaceAgentMode,
                                     resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit): WorkspaceAgentTurnResult {
            onEvent(WorkspaceAgentEvent.SessionBound(nativeId))
            turnUsage?.let { onEvent(WorkspaceAgentEvent.Usage(it)) }
            return WorkspaceAgentTurnResult(agentId, nativeId, "Synthetic answer", "end_turn", emptyList(), emptyList())
        }

        override suspend fun prepareSession(agentId: String, resumeSessionId: String?,
                                            onEvent: (WorkspaceAgentEvent) -> Unit): WorkspaceAgentEvent.Configuration {
            lifecycleCallbacks += onEvent
            val result = WorkspaceAgentEvent.Configuration(nativeId, config)
            if (emitPrepareEvents) {
                onEvent(WorkspaceAgentEvent.SessionBound(nativeId))
                prepareUsage?.let { onEvent(WorkspaceAgentEvent.Usage(it)) }
                onEvent(result)
            }
            prepareStarted.complete(Unit)
            holdPrepare?.let { withTimeout(3_000) { it.await() } }
            // Intentionally no yield: admitted callbacks must drain before this operation closes.
            return result
        }

        override suspend fun selectModel(agentId: String, expectedSessionId: String, modelId: String): WorkspaceAgentEvent.Configuration =
            error("No model operation in this fixture")
        override suspend fun newSession(agentId: String, onEvent: (WorkspaceAgentEvent) -> Unit): String =
            error("No new-session operation in this fixture")
        override suspend fun cancel(agentId: String, expectedSessionId: String?) = Unit
    }

    private fun fixture(block: suspend (ExternalAgentSessionController, Backend) -> Unit) = runBlocking {
        val owner = SupervisorJob(coroutineContext[Job])
        val controller = ExternalAgentSessionController(CoroutineScope(coroutineContext + owner))
        val backend = Backend()
        try { withTimeout(8_000) { block(controller, backend) } }
        finally {
            withContext(NonCancellable) {
                backend.holdPrepare?.complete(Unit)
                controller.close()
                check(withTimeoutOrNull(3_000) { owner.cancelAndJoin(); true } == true) { "Fixture owner did not finish" }
            }
        }
    }

    private suspend fun ExternalAgentSessionController.idle() {
        withTimeout(3_000) { while (busy) delay(1) }
    }

    private suspend fun seed(controller: ExternalAgentSessionController, backend: Backend) {
        backend.turnUsage = reported
        assertTrue(controller.submit(backend, spec, "Seed same-session telemetry", WorkspaceAgentMode.EDIT))
        controller.idle()
        assertEquals(reported, controller[spec.id].usage)
        backend.turnUsage = null
    }

    @Test(timeout = 12_000)
    fun immediatePrepareDrainsSessionUsageBeforeFinalConfiguration() = fixture { controller, backend ->
        backend.prepareUsage = reported
        controller.prepare(backend, spec, force = true)
        controller.idle()
        assertEquals("native-a", controller[spec.id].sessionId)
        assertEquals("A lifecycle Usage event must survive an immediate Configuration return", reported, controller[spec.id].usage)
    }

    @Test(timeout = 12_000)
    fun changedRunNativeIdentityInvalidatesOldUsage() = fixture { controller, backend ->
        seed(controller, backend)
        backend.nativeId = "native-b"
        assertTrue(controller.submit(backend, spec, "A different native session", WorkspaceAgentMode.EDIT))
        controller.idle()
        assertEquals("native-b", controller[spec.id].sessionId)
        assertNull("Telemetry from native-a must not be attributed to native-b", controller[spec.id].usage)
    }

    @Test(timeout = 12_000)
    fun sameRunNativeIdentityRetainsLastReportedUsage() = fixture { controller, backend ->
        seed(controller, backend)
        assertTrue(controller.submit(backend, spec, "Same native session", WorkspaceAgentMode.EDIT))
        controller.idle()
        assertEquals(reported, controller[spec.id].usage)
    }

    @Test(timeout = 12_000)
    fun changedLifecycleBindingInvalidatesOldUsage() = fixture { controller, backend ->
        seed(controller, backend)
        backend.nativeId = "native-b"
        controller.prepare(backend, spec, force = true)
        controller.idle()
        assertEquals("native-b", controller[spec.id].sessionId)
        assertNull(controller[spec.id].usage)
    }

    @Test(timeout = 12_000)
    fun changedConfigurationResultWithoutBindingEventInvalidatesOldUsage() = fixture { controller, backend ->
        seed(controller, backend)
        backend.nativeId = "native-b"
        backend.emitPrepareEvents = false
        controller.prepare(backend, spec, force = true)
        controller.idle()
        assertEquals("native-b", controller[spec.id].sessionId)
        assertNull(controller[spec.id].usage)
    }

    @Test(timeout = 12_000)
    fun sameLifecycleNativeIdentityRetainsLastReportedUsage() = fixture { controller, backend ->
        seed(controller, backend)
        controller.prepare(backend, spec, force = true)
        controller.idle()
        assertEquals(reported, controller[spec.id].usage)
    }

    @Test(timeout = 12_000)
    fun staleLifecycleCallbacksCannotOverwriteTheCurrentOperation() = fixture { controller, backend ->
        seed(controller, backend)
        controller.prepare(backend, spec, force = true)
        controller.idle()
        val stale = backend.lifecycleCallbacks.single()
        backend.holdPrepare = CompletableDeferred()
        backend.prepareStarted = CompletableDeferred()
        controller.prepare(backend, spec, force = true)
        backend.prepareStarted.await()
        stale(WorkspaceAgentEvent.SessionBound("stale-native"))
        stale(WorkspaceAgentEvent.Usage(WorkspaceAgentUsageSnapshot(1, 2)))
        stale(WorkspaceAgentEvent.Configuration("stale-native", backend.config))
        repeat(3) { yield() }
        assertEquals("native-a", controller[spec.id].sessionId)
        assertEquals(reported, controller[spec.id].usage)
        backend.holdPrepare!!.complete(Unit)
        controller.idle()
        assertEquals(reported, controller[spec.id].usage)
    }
}
