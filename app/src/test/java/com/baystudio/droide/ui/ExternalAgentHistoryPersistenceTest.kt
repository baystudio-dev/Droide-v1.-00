package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ExternalAgentHistoryPersistenceTest {
    @get:org.junit.Rule
    val applicationAdmission: org.junit.rules.TestRule = com.baystudio.droide.core.ApplicationWorkAdmissionFixture()

    private val root = Files.createTempDirectory("external-agent-history-").toFile()
    private val repositories = mutableListOf<PluginConversationHistory>()
    private val spec = WorkspaceAgentPluginCatalog.entries.first()
    private fun repository() = SqlitePluginConversationHistory(root).also { repositories += it }

    @After fun cleanup() = runBlocking {
        repositories.forEach { it.close() }
        assertTrue(PathSecurity.deleteTreeNoFollow(root))
    }

    private class Backend(private val repository: PluginConversationHistory? = null) : WorkspaceAgentChatBackend {
        var calls = 0
        var prepares = 0
        var newSessions = 0
        var failure: String? = null
        var hold: CompletableDeferred<Unit>? = null
        var nativeId = "shared-native-id"
        var stream = listOf("A partial ", "response 🚀")
        var durableAdmissionObserved = false
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val resumeIds = mutableListOf<String?>()
        val configuration = WorkspaceAgentSessionConfiguration(
            models = listOf(WorkspaceAgentModelOption("provider/model", "A model")), currentModelId = "provider/model",
        )
        override suspend fun runTurn(agentId: String, prompt: String, mode: WorkspaceAgentMode, resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit): WorkspaceAgentTurnResult {
            calls++; resumeIds += resumeSessionId
            repository?.listPage(agentId)?.entries?.firstOrNull()?.let { durableAdmissionObserved = it.messageCount >= 1 && it.status == PluginHistoryStatus.RUNNING }
            onEvent(WorkspaceAgentEvent.SessionBound(nativeId))
            onEvent(WorkspaceAgentEvent.Configuration(nativeId, configuration))
            stream.forEach { onEvent(WorkspaceAgentEvent.Text(it)) }
            started.complete(Unit)
            try {
                hold?.await()
                failure?.let { error(it) }
                return WorkspaceAgentTurnResult(agentId, nativeId, "Final reply to $prompt", "end_turn", emptyList(), emptyList())
            } finally { if (!currentCoroutineContext().isActive) cancelled.complete(Unit) }
        }
        override suspend fun prepareSession(agentId: String, resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit): WorkspaceAgentEvent.Configuration {
            prepares++
            return WorkspaceAgentEvent.Configuration(resumeSessionId ?: nativeId, configuration)
        }
        override suspend fun newSession(agentId: String, onEvent: (WorkspaceAgentEvent) -> Unit): String { newSessions++; return nativeId }
        override suspend fun selectModel(agentId: String, expectedSessionId: String, modelId: String): WorkspaceAgentEvent.Configuration =
            WorkspaceAgentEvent.Configuration(expectedSessionId, configuration.copy(currentModelId = modelId))
        override suspend fun cancel(agentId: String, expectedSessionId: String?) = Unit
    }

    private fun fixture(block: suspend (CoroutineScope) -> Unit) = runBlocking {
        withTimeout(12_000) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
            try { block(scope) } finally { scope.coroutineContext.job.cancelAndJoin() }
        }
    }
    private suspend fun ExternalAgentSessionController.idle() { withTimeout(5_000) { while (busy) delay(1) } }

    @Test fun admissionIsDurableBeforeBackendAndRecreatedControllerResumesOnlyAfterExplicitSend() = fixture { scope ->
        val firstStore = repository()
        val firstHost = Backend(firstStore)
        val first = ExternalAgentSessionController(scope, history = firstStore)
        assertTrue(first.submit(firstHost, spec, "First question", WorkspaceAgentMode.EDIT))
        first.idle()
        assertTrue(firstHost.durableAdmissionObserved)
        val id = first[spec.id].historyId
        first.close()
        val secondStore = repository()
        val secondHost = Backend(secondStore)
        val second = ExternalAgentSessionController(scope, history = secondStore)
        second.prepare(secondHost, spec)
        second.idle()
        assertTrue(second[spec.id].historyRestored)
        assertEquals(id, second[spec.id].historyId)
        assertEquals(listOf("First question", "Final reply to First question"), second[spec.id].messages.map { it.content })
        assertEquals(0, secondHost.prepares)
        assertEquals(0, secondHost.calls)
        assertTrue(second.submit(secondHost, spec, "Follow up", WorkspaceAgentMode.EDIT))
        second.idle()
        assertEquals(listOf("shared-native-id"), secondHost.resumeIds)
        assertEquals(1, secondHost.calls)
        assertEquals(4, secondStore.load(id!!)!!.summary.messageCount)
        second.close()
    }

    @Test fun everyCatalogPluginRestoresItsOwnMessagesEvenWhenNativeSessionIdsMatch() = fixture { scope ->
        val firstStore = repository()
        val first = ExternalAgentSessionController(scope, history = firstStore)
        for (plugin in WorkspaceAgentPluginCatalog.entries) {
            first.submit(Backend(firstStore), plugin, "Question for ${plugin.id}", WorkspaceAgentMode.PLAN)
            first.idle()
        }
        first.close()
        val second = ExternalAgentSessionController(scope, history = repository())
        val hosts = mutableListOf<Backend>()
        for (plugin in WorkspaceAgentPluginCatalog.entries) {
            val host = Backend().also { hosts += it }
            second.prepare(host, plugin); second.idle()
            assertEquals("Question for ${plugin.id}", second[plugin.id].messages.first().content)
        }
        assertTrue(hosts.all { it.calls == 0 && it.prepares == 0 })
        assertEquals(WorkspaceAgentPluginCatalog.entries.size, WorkspaceAgentPluginCatalog.entries.map { second[it.id].historyId }.toSet().size)
        second.close()
    }

    @Test fun newSessionKeepsThePreviousArchiveAndDoesNotGroupByReusedNativeId() = fixture { scope ->
        val store = repository()
        val host = Backend(store)
        val controller = ExternalAgentSessionController(scope, history = store)
        controller.submit(host, spec, "Original chat", WorkspaceAgentMode.EDIT); controller.idle()
        val originalId = controller[spec.id].historyId!!
        controller.newSession(host, spec); controller.idle()
        assertTrue(controller[spec.id].messages.isEmpty())
        controller.submit(host, spec, "Another chat", WorkspaceAgentMode.EDIT); controller.idle()
        assertNotEquals(originalId, controller[spec.id].historyId)
        assertEquals(2, store.listPage(spec.id).entries.size)
        assertEquals("Original chat", store.load(originalId)!!.messages.first().content)
        controller.close()
    }

    @Test fun queuedFollowUpCommitsIntoTheSameSqliteConversationBeforeItsCliTurn() = fixture { scope ->
        val store = repository()
        val gate = CompletableDeferred<Unit>()
        val host = Backend(store).apply { hold = gate }
        val controller = ExternalAgentSessionController(scope, history = store)
        controller.submit(host, spec, "First work", WorkspaceAgentMode.EDIT)
        host.started.await()
        withTimeout(5_000) { while (controller[spec.id].sessionId == null || controller[spec.id].configuration == null) delay(1) }
        val id = controller[spec.id].historyId!!
        assertTrue(controller.submit(host, spec, "Queued work", WorkspaceAgentMode.EDIT, AgentPromptDelivery.QUEUE))
        assertEquals(1, host.calls)
        gate.complete(Unit); controller.idle()
        val saved = store.load(id)!!
        assertEquals(id, controller[spec.id].historyId)
        assertEquals(listOf("First work", "Final reply to First work", "Queued work", "Final reply to Queued work"), saved.messages.map { it.content })
        assertEquals(listOf(null, "shared-native-id"), host.resumeIds)
        assertEquals(1, store.listPage(spec.id).entries.size)
        assertTrue(host.durableAdmissionObserved); assertEquals(0, host.newSessions)
        controller.close()
    }

    @Test fun stopSavesAllReceivedPartialFragmentsAndTheNativeBindingBeforeClearingBusy() = fixture { scope ->
        val store = repository()
        val host = Backend(store).apply { hold = CompletableDeferred() }
        val controller = ExternalAgentSessionController(scope, history = store)
        controller.submit(host, spec, "Long build", WorkspaceAgentMode.EDIT)
        host.started.await()
        controller.cancel(host); controller.idle()
        assertTrue(host.cancelled.isCompleted)
        val saved = store.load(controller[spec.id].historyId!!)!!
        assertEquals(PluginHistoryStatus.STOPPED, saved.summary.status)
        assertEquals("shared-native-id", saved.summary.nativeSessionId)
        assertEquals(listOf("Long build", "A partial response 🚀", "Stopped."), saved.messages.map { it.content })
        controller.close()
    }

    @Test fun backendFailurePersistsPartialOutputAndErrorWithoutErasingTheEarlierTurn() = fixture { scope ->
        val store = repository()
        val host = Backend(store)
        val controller = ExternalAgentSessionController(scope, history = store)
        controller.submit(host, spec, "Earlier work", WorkspaceAgentMode.EDIT); controller.idle()
        host.failure = "Native session failed"
        controller.submit(host, spec, "Next work", WorkspaceAgentMode.EDIT); controller.idle()
        val saved = store.load(controller[spec.id].historyId!!)!!
        assertEquals(PluginHistoryStatus.FAILED, saved.summary.status)
        assertEquals(listOf("Earlier work", "Final reply to Earlier work", "Next work", "A partial response 🚀", "OpenCode error: Native session failed"), saved.messages.map { it.content })
        controller.close()
    }

    @Test fun failedAdmissionDoesNotLaunchExternalWorkAndLeavesThePromptVisible() = fixture { scope ->
        val real = repository()
        var admissionAttempts = 0
        val blocked = object : PluginConversationHistory by real {
            override suspend fun beginTurn(id: String?, agentId: String, prompt: String, mode: WorkspaceAgentMode, nativeSessionId: String?): PluginHistoryWindow {
                admissionAttempts++
                throw IOException("No writable storage")
            }

            override suspend fun beginMediaTurn(id: String?, agentId: String, prompt: String, mode: WorkspaceAgentMode,
                                                nativeSessionId: String?, media: List<ChatMediaRef>): PluginHistoryWindow =
                beginTurn(id, agentId, prompt, mode, nativeSessionId)
        }
        val host = Backend(real)
        val controller = ExternalAgentSessionController(scope, history = blocked)
        controller.submit(host, spec, "Keep my input", WorkspaceAgentMode.EDIT); controller.idle()
        assertEquals(1, admissionAttempts)
        assertEquals(0, host.calls)
        assertEquals("Keep my input", controller[spec.id].messages.first().content)
        assertTrue(controller[spec.id].persistenceError!!.contains("not sent"))
        assertTrue(real.listPage().entries.isEmpty())
        controller.close()
    }

    @Test fun openingSavedHistoryDuringWorkIsRejectedWithoutStoppingOrSwitchingTheRun() = fixture { scope ->
        val store = repository()
        val old = store.beginTurn(null, spec.id, "Saved chat", WorkspaceAgentMode.EDIT, "old-native")
        val oldWindow = store.finish(old.summary.id, "Old reply", emptyList(), PluginHistoryStatus.COMPLETED)
        val host = Backend(store).apply { hold = CompletableDeferred() }
        val controller = ExternalAgentSessionController(scope, history = store)
        controller.submit(host, spec, "Active work", WorkspaceAgentMode.EDIT)
        host.started.await()
        val run = controller.activeRun!!.runId
        assertFalse(controller.openSaved(oldWindow))
        var rejected = false
        try { controller.openSaved(old.summary.id, spec.id) } catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)
        assertEquals(run, controller.activeRun!!.runId)
        assertFalse(host.cancelled.isCompleted)
        host.hold!!.complete(Unit); controller.idle()
        controller.close()
    }

    @Test fun missingNativeIdentityKeepsTheArchiveReadOnlyEvenWhenModelsAreExplicitlyRefreshed() = fixture { scope ->
        val store = repository()
        val unfinished = store.beginTurn(null, spec.id, "Unconnected prompt", WorkspaceAgentMode.EDIT, null)
        store.finish(unfinished.summary.id, null, listOf("CLI unavailable"), PluginHistoryStatus.FAILED)
        val host = Backend()
        val controller = ExternalAgentSessionController(scope, history = store)
        controller.prepare(host, spec); controller.idle()
        controller.prepare(host, spec, force = true); controller.idle()
        assertFalse(controller.submit(host, spec, "Continue", WorkspaceAgentMode.EDIT))
        assertEquals(0, host.prepares)
        assertEquals(0, host.calls)
        assertEquals("Unconnected prompt", controller[spec.id].messages.first().content)
        assertTrue(controller[spec.id].connectionError!!.contains("no resumable"))
        controller.close()
    }

    @Test fun failedStreamCheckpointDoesNotSpinWhileIdleAndFinalAuthoritativeReplyStillCommits() = fixture { scope ->
        val real = repository()
        var appendAttempts = 0
        val flaky = object : PluginConversationHistory by real {
            override suspend fun appendPartial(id: String, fragments: List<String>) { appendAttempts++; throw IOException("Checkpoint unavailable") }
        }
        val host = Backend(real).apply { hold = CompletableDeferred() }
        val controller = ExternalAgentSessionController(scope, history = flaky)
        controller.submit(host, spec, "Keep final reply", WorkspaceAgentMode.EDIT)
        host.started.await()
        withTimeout(4_000) { while (controller[spec.id].persistenceError == null) delay(10) }
        delay(1_200)
        assertEquals(1, appendAttempts)
        host.hold!!.complete(Unit); controller.idle()
        assertNull(controller[spec.id].persistenceError)
        val saved = real.load(controller[spec.id].historyId!!)!!
        assertEquals(PluginHistoryStatus.COMPLETED, saved.summary.status)
        assertEquals("Final reply to Keep final reply", saved.messages.last().content)
        controller.close()
    }
}
