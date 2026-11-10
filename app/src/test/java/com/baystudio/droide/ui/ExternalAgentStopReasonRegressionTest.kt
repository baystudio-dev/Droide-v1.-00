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

/** Synthetic ACP turn results only. No process, credentials, HTTP, or model request. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ExternalAgentStopReasonRegressionTest {
    @get:org.junit.Rule
    val applicationAdmission: org.junit.rules.TestRule = com.baystudio.droide.core.ApplicationWorkAdmissionFixture()

    private class SyntheticBackend(private val firstStopReason: String) : WorkspaceAgentChatBackend {
        val prompts = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        override suspend fun runTurn(agentId: String, prompt: String, mode: WorkspaceAgentMode,
                                     resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit): WorkspaceAgentTurnResult {
            prompts += prompt
            val first = prompts.size == 1
            onEvent(WorkspaceAgentEvent.SessionBound("synthetic-native-session"))
            onEvent(WorkspaceAgentEvent.Text("Synthetic partial answer"))
            if (first) withTimeout(3_000) { release.await() }
            return WorkspaceAgentTurnResult(agentId, "synthetic-native-session", "Synthetic answer to $prompt",
                if (first) firstStopReason else "end_turn", emptyList(), emptyList())
        }
        override suspend fun prepareSession(agentId: String, resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit): WorkspaceAgentEvent.Configuration =
            error("Unexpected prepare in a synthetic stop-reason fixture")
        override suspend fun selectModel(agentId: String, expectedSessionId: String, modelId: String): WorkspaceAgentEvent.Configuration =
            error("Unexpected model operation in a synthetic stop-reason fixture")
        override suspend fun newSession(agentId: String, onEvent: (WorkspaceAgentEvent) -> Unit): String =
            error("Unexpected new session in a synthetic stop-reason fixture")
        override suspend fun cancel(agentId: String, expectedSessionId: String?) = Unit
    }

    private fun scenario(reason: String, verify: (ExternalAgentSessionController, SyntheticBackend, PluginHistoryWindow) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("r30-stop-reason-").toFile()
        val store = SqlitePluginConversationHistory(root)
        val owner = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + owner)
        val controller = ExternalAgentSessionController(scope, history = store)
        val backend = SyntheticBackend(reason)
        val spec = WorkspaceAgentPluginCatalog.entries.first()
        try {
            withTimeout(10_000) {
                assertTrue(controller.submit(backend, spec, "first", WorkspaceAgentMode.EDIT))
                while (controller[spec.id].sessionId == null) delay(1)
                val id = checkNotNull(controller[spec.id].historyId)
                assertTrue(controller.submit(backend, spec, "queued", WorkspaceAgentMode.EDIT, AgentPromptDelivery.QUEUE))
                backend.release.complete(Unit)
                while (controller.busy) delay(1)
                verify(controller, backend, checkNotNull(store.load(id)))
            }
        } finally {
            withContext(NonCancellable) {
                backend.release.complete(Unit)
                controller.close()
                val joined = withTimeoutOrNull(3_000) { owner.cancelAndJoin(); true } == true
                check(joined) { "Owner cleanup did not finish; preserve temporary database at ${root.path}" }
                store.close()
                assertTrue(PathSecurity.deleteTreeNoFollow(root))
            }
        }
    }

    @Test(timeout = 15_000)
    fun refusalPreservesPartialAnswerAndReasonWithoutPromotingQueue() = scenario("refusal") { controller, backend, saved ->
        assertEquals(listOf("first"), backend.prompts)
        assertEquals(listOf("queued"), controller.pendingPrompts.map { it.input.text })
        assertEquals(PluginHistoryStatus.STOPPED, saved.summary.status)
        assertTrue(saved.messages.any { it.content == "Synthetic answer to first" })
        assertTrue(saved.messages.any { it.content.contains("refusal") && it.content.contains("paused") })
    }

    @Test(timeout = 15_000)
    fun unknownReasonFailsClosedWithBoundedDurableNoticeAndKeepsQueue() {
        val reason = "future_stop_" + "x".repeat(1_000)
        scenario(reason) { controller, backend, saved ->
            assertEquals(listOf("first"), backend.prompts)
            assertEquals(listOf("queued"), controller.pendingPrompts.map { it.input.text })
            assertEquals(PluginHistoryStatus.FAILED, saved.summary.status)
            assertTrue(saved.messages.any { it.content == "Synthetic answer to first" })
            val notice = saved.messages.single { it.content.contains("unrecognized stop reason") }.content
            assertTrue(notice.contains("future_stop_"))
            assertTrue(notice.contains("paused"))
            assertTrue("Unknown protocol strings must not create unbounded notices", notice.length < 300)
        }
    }
}
