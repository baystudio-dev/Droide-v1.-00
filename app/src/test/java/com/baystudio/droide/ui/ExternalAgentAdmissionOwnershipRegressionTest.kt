package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Existing production APIs only. A bounded gate isolates committed admission from its acknowledgement. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ExternalAgentAdmissionOwnershipRegressionTest {
    @get:org.junit.Rule
    val applicationAdmission: org.junit.rules.TestRule = com.baystudio.droide.core.ApplicationWorkAdmissionFixture()

    private class NoExternalCalls : WorkspaceAgentChatBackend {
        var calls = 0
        override suspend fun runTurn(agentId: String, prompt: String, mode: WorkspaceAgentMode,
                                     resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit): WorkspaceAgentTurnResult {
            calls++
            error("The cancelled, unacknowledged admission must never reach a backend")
        }
        override suspend fun prepareSession(agentId: String, resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit): WorkspaceAgentEvent.Configuration =
            error("No session preparation is allowed in this fixture")
        override suspend fun selectModel(agentId: String, expectedSessionId: String, modelId: String): WorkspaceAgentEvent.Configuration =
            error("No model operation is allowed in this fixture")
        override suspend fun newSession(agentId: String, onEvent: (WorkspaceAgentEvent) -> Unit): String =
            error("No session creation is allowed in this fixture")
        override suspend fun cancel(agentId: String, expectedSessionId: String?) = Unit
    }

    @Test(timeout = 15_000)
    fun stopBeforeRunDispatchDoesNotCommitOrInvokeBackend() = runBlocking {
        val root = Files.createTempDirectory("r30-before-dispatch-").toFile()
        val store = SqlitePluginConversationHistory(root)
        val owner = SupervisorJob(coroutineContext[Job])
        val controller = ExternalAgentSessionController(CoroutineScope(coroutineContext + owner), history = store)
        val backend = NoExternalCalls()
        val spec = WorkspaceAgentPluginCatalog.entries.first()
        try {
            withTimeout(10_000) {
                assertTrue(controller.submit(backend, spec, "Do not admit after Stop", WorkspaceAgentMode.EDIT))
                // No suspension between submit and Stop: the lazy run has not been dispatched.
                controller.cancel(backend)
                while (controller.busy) delay(1)
                assertEquals(0, backend.calls)
                assertTrue(store.listPage().entries.isEmpty())
                assertNull(controller[spec.id].historyId)
            }
        } finally {
            withContext(NonCancellable) {
                controller.close()
                val joined = withTimeoutOrNull(3_000) { owner.cancelAndJoin(); true } == true
                check(joined) { "Owner cleanup did not finish; preserve temporary database at ${root.path}" }
                store.close()
                assertTrue(PathSecurity.deleteTreeNoFollow(root))
            }
        }
    }

    @Test(timeout = 15_000)
    fun closeDuringCommittedAdmissionFinalizesExactRowWithoutPublishingIntoClosedController() = runBlocking {
        val root = Files.createTempDirectory("r30-close-admission-").toFile()
        val real = SqlitePluginConversationHistory(root)
        val committed = CompletableDeferred<String>()
        val allowReturn = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val owner = SupervisorJob(coroutineContext[Job])
        val store = object : PluginConversationHistory by real {
            override suspend fun beginMediaTurn(id: String?, agentId: String, prompt: String,
                                               mode: WorkspaceAgentMode, nativeSessionId: String?,
                                               media: List<ChatMediaRef>): PluginHistoryWindow {
                val admitted = real.beginMediaTurn(id, agentId, prompt, mode, nativeSessionId, media)
                committed.complete(admitted.summary.id)
                withTimeout(3_000) { allowReturn.await() }
                return admitted
            }
            override suspend fun close() {
                real.close()
                closed.complete(Unit)
            }
        }
        val controller = ExternalAgentSessionController(CoroutineScope(coroutineContext + owner), history = store)
        val backend = NoExternalCalls()
        val spec = WorkspaceAgentPluginCatalog.entries.first()
        var reopened: SqlitePluginConversationHistory? = null
        try {
            withTimeout(10_000) {
                assertTrue(controller.submit(backend, spec, "Close while receipt is pending", WorkspaceAgentMode.EDIT))
                val id = committed.await()
                controller.close()
                allowReturn.complete(Unit)
                closed.await()
                assertEquals(0, backend.calls)
                assertNull("Late admission must not publish into a closed controller", controller[spec.id].historyId)
                val inspection = SqlitePluginConversationHistory(root).also { reopened = it }
                val saved = checkNotNull(inspection.load(id))
                assertEquals(id, saved.summary.id)
                assertEquals(PluginHistoryStatus.STOPPED, saved.summary.status)
                assertEquals("Close while receipt is pending", saved.messages.first().content)
            }
        } finally {
            withContext(NonCancellable) {
                allowReturn.complete(Unit)
                val joined = withTimeoutOrNull(3_000) { owner.cancelAndJoin(); true } == true
                check(joined) { "Owner cleanup did not finish; preserve temporary database at ${root.path}" }
                reopened?.close()
                real.close()
                assertTrue(PathSecurity.deleteTreeNoFollow(root))
            }
        }
    }
}
