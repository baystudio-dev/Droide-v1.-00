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
class ExternalAgentStopReasonR30BaselineTest {
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

    private fun assertLimitStops(reason: String) = scenario(reason) { controller, backend, saved ->
        assertEquals("A provider limit must not silently submit the next queued prompt", listOf("first"), backend.prompts)
        assertEquals("The queued draft must remain reviewable", listOf("queued"), controller.pendingPrompts.map { it.input.text })
        assertNotEquals("A limit stop must not be saved as ordinary completion", PluginHistoryStatus.COMPLETED, saved.summary.status)
        assertNotEquals(PluginHistoryStatus.RUNNING, saved.summary.status)
        assertTrue("Keep the provider's partial answer", saved.messages.any { it.content == "Synthetic answer to first" })
        val notices = saved.messages.filter { it.role == "assistant" && it.content != "Synthetic answer to first" }
            .joinToString(" ") { it.content }.lowercase()
        assertTrue("The reason for stopping must be visible after reopening", notices.contains(reason) ||
            notices.contains("limit") && notices.contains(if (reason == "max_tokens") "token" else "request"))
    }

    @Test(timeout = 15_000)
    fun maxTokensRemainsVisibleAndPausesTheQueue() = assertLimitStops("max_tokens")

    @Test(timeout = 15_000)
    fun maxTurnRequestsRemainsVisibleAndPausesTheQueue() = assertLimitStops("max_turn_requests")

    @Test(timeout = 15_000)
    fun normalEndTurnStillRunsQueuedInputOnTheSameConversation() = scenario("end_turn") { controller, backend, saved ->
        assertEquals(listOf("first", "queued"), backend.prompts)
        assertTrue(controller.pendingPrompts.isEmpty())
        assertEquals(PluginHistoryStatus.COMPLETED, saved.summary.status)
        assertEquals(listOf("first", "Synthetic answer to first", "queued", "Synthetic answer to queued"), saved.messages.map { it.content })
    }

    @Test(timeout = 15_000)
    fun cancellationStillPreservesTheAnswerWithoutPromotingOrdinaryQueuedInput() = scenario("cancelled") { controller, backend, saved ->
        assertEquals(listOf("first"), backend.prompts)
        assertEquals(listOf("queued"), controller.pendingPrompts.map { it.input.text })
        assertEquals(PluginHistoryStatus.STOPPED, saved.summary.status)
        assertEquals(listOf("first", "Synthetic answer to first"), saved.messages.map { it.content })
    }
}
