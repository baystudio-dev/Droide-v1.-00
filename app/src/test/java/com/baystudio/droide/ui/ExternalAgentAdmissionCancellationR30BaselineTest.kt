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
class ExternalAgentAdmissionCancellationR30BaselineTest {
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
    fun stopAfterAdmissionCommitBeforeReturnFinalizesTheSameRowWithoutInvokingBackend() = runBlocking {
        val root = Files.createTempDirectory("r30-admission-cancel-").toFile()
        val real = SqlitePluginConversationHistory(root)
        val committed = CompletableDeferred<String>()
        val allowReturn = CompletableDeferred<Unit>()
        val owner = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + owner)
        val store = object : PluginConversationHistory by real {
            override suspend fun beginMediaTurn(id: String?, agentId: String, prompt: String,
                                               mode: WorkspaceAgentMode, nativeSessionId: String?,
                                               media: List<ChatMediaRef>): PluginHistoryWindow {
                val admitted = real.beginMediaTurn(id, agentId, prompt, mode, nativeSessionId, media)
                committed.complete(admitted.summary.id)
                // A real IO-dispatcher return can be cancelled after commit. Hold that exact ownership gap.
                withTimeout(3_000) { allowReturn.await() }
                return admitted
            }
        }
        val controller = ExternalAgentSessionController(scope, history = store)
        val backend = NoExternalCalls()
        val spec = WorkspaceAgentPluginCatalog.entries.first()
        try {
            withTimeout(10_000) {
                assertTrue(controller.submit(backend, spec, "Keep this admitted prompt", WorkspaceAgentMode.EDIT))
                val id = committed.await()
                assertEquals(PluginHistoryStatus.RUNNING, real.listPage().entries.single().status)
                controller.cancel(backend)
                allowReturn.complete(Unit)
                while (controller.busy) delay(1)

                assertEquals("Stopping before admission returns must not launch work", 0, backend.calls)
                val summary = real.listPage().entries.single()
                assertEquals("The exact committed conversation must remain selected", id, summary.id)
                assertEquals("A committed admission must not remain owned and RUNNING after Stop",
                    PluginHistoryStatus.STOPPED, summary.status)
                val saved = checkNotNull(real.load(id))
                assertEquals("Keep this admitted prompt", saved.messages.first().content)
                assertTrue(saved.messages.any { it.content == "Stopped." })
            }
        } finally {
            withContext(NonCancellable) {
                allowReturn.complete(Unit)
                controller.close()
                val joined = withTimeoutOrNull(3_000) { owner.cancelAndJoin(); true } == true
                check(joined) { "Owner cleanup did not finish; preserve temporary database at ${root.path}" }
                real.close()
                assertTrue(PathSecurity.deleteTreeNoFollow(root))
            }
        }
    }
}
