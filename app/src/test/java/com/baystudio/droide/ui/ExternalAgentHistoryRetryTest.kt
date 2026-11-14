package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import java.io.IOException
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Actual controller on one dispatcher; receipt-bearing fake storage, no Android/SQLite or CLI. */
class ExternalAgentHistoryRetryTest {
    private val spec = WorkspaceAgentPluginSpec("retry-a", "plugin.retry.a", "Retry A", "never-run", emptyList())
    private val other = WorkspaceAgentPluginSpec("retry-b", "plugin.retry.b", "Retry B", "never-run", emptyList())
    private val outputMedia = ChatMediaRef("result-media", "unused/result.png", "result.png", "image/png", 12, 1, 999)
    private val draftMedia = ChatMediaRef("draft-media", "unused/draft.png", "draft.png", "image/png", 34, 2, 999)

    private data class Finish(val id: String, val reply: String?, val notices: List<String>, val status: PluginHistoryStatus, val media: List<ChatMediaRef>)

    private class Store : PluginConversationHistory {
        val windows = linkedMapOf<String, PluginHistoryWindow>()
        val attempts = mutableListOf<Finish>()
        val committed = mutableListOf<Finish>()
        val fragments = mutableListOf<String>()
        val updates = mutableListOf<ExternalTranscriptUpdate>()
        var failures = 0
        var cancelNext = false
        var failAfterCommit = false
        var gate: CompletableDeferred<Unit>? = null
        var entered: CompletableDeferred<Unit>? = null
        var writing = false
        var begins = 0
        var closes = 0
        override suspend fun listPage(agentId: String?, query: String, before: PluginHistoryCursor?, limit: Int) =
            PluginHistoryPage(windows.values.map { it.summary }.filter { agentId == null || it.agentId == agentId }, null)
        override suspend fun latest(agentId: String) = windows.values.lastOrNull { it.summary.agentId == agentId }
        override suspend fun load(id: String, beforePart: Long?) = windows[id]
        override suspend fun beginTurn(id: String?, agentId: String, prompt: String, mode: WorkspaceAgentMode, nativeSessionId: String?): PluginHistoryWindow {
            check(!writing) { "New turn raced final receipt" }
            val old = id?.let(windows::get)
            check(old?.summary?.status != PluginHistoryStatus.RUNNING) { "Prior final receipt is missing" }
            begins++
            val messages = old?.messages.orEmpty() + AgentMessage("user", prompt)
            return PluginHistoryWindow(PluginHistorySummary(id ?: "history-$begins", agentId, prompt, 1, 1, nativeSessionId,
                mode, null, messages.size, messages.size.toLong(), PluginHistoryStatus.RUNNING), messages,
                messages.indices.map { it.toLong() }, 0, messages.size.toLong()).also { windows[it.summary.id] = it }
        }
        override suspend fun bind(id: String, nativeSessionId: String, modelId: String?) {
            check(!writing) { "Binding raced final receipt" }
            windows[id] = windows.getValue(id).let { it.copy(summary = it.summary.copy(nativeSessionId = nativeSessionId, modelId = modelId)) }
        }
        override suspend fun appendPartial(id: String, fragments: List<String>) {
            check(!writing) { "Append raced final receipt" }
            this.fragments += fragments
        }
        override suspend fun appendTranscript(id: String, updates: List<ExternalTranscriptUpdate>) {
            check(!writing) { "Transcript append raced final receipt" }
            this.updates += updates
        }
        override suspend fun finish(id: String, reply: String?, notices: List<String>, status: PluginHistoryStatus) =
            finishMediaTurn(id, reply, notices, status, emptyList())
        override suspend fun finishMediaTurn(id: String, reply: String?, notices: List<String>, status: PluginHistoryStatus, media: List<ChatMediaRef>): PluginHistoryWindow {
            check(!writing) { "Concurrent final writes" }
            writing = true
            val payload = Finish(id, reply, notices.toList(), status, media.toList())
            attempts += payload
            try {
                entered?.complete(Unit)
                gate?.await()
                if (cancelNext) { cancelNext = false; throw CancellationException("Store cancelled before receipt") }
                if (failures > 0) { failures--; throw IOException("Temporary final write failure") }
                val old = windows.getValue(id)
                check(old.summary.status == PluginHistoryStatus.RUNNING) { "Turn is already finalized" }
                committed += payload
                val messages = old.messages + listOfNotNull(reply?.let { AgentMessage("assistant", it, media = media) }) + notices.map { AgentMessage("assistant", it) }
                return old.copy(summary = old.summary.copy(status = status, messageCount = messages.size, partCount = messages.size.toLong()),
                    messages = messages, messageKeys = messages.indices.map { it.toLong() }, endPart = messages.size.toLong())
                    .also {
                        windows[id] = it
                        if (failAfterCommit) throw IOException("Ambiguous post-commit failure")
                    }
            } finally { writing = false }
        }
        override suspend fun delete(id: String) = error("No deletion expected")
        override suspend fun close() { check(!writing) { "Close raced final receipt" }; closes++ }
    }

    private class Backend : WorkspaceAgentChatBackend {
        val prompts = mutableListOf<Pair<String, String>>()
        val started = CompletableDeferred<Unit>()
        var gate: CompletableDeferred<Unit>? = null
        var events = emptyList<WorkspaceAgentEvent>()
        var stopReason = "end_turn"
        var changedFiles = emptyList<String>()
        var conflicts = emptyList<String>()
        var mediaAdmissions = 0
        var prepares = 0
        var newSessions = 0
        var cancels = 0
        override fun mediaAdmissionError(agentId: String, media: List<ChatMediaRef>): String? { mediaAdmissions++; return null }
        override suspend fun runTurn(agentId: String, prompt: String, mode: WorkspaceAgentMode, resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit): WorkspaceAgentTurnResult {
            prompts += agentId to prompt
            onEvent(WorkspaceAgentEvent.SessionBound("native-$agentId"))
            events.forEach(onEvent)
            started.complete(Unit)
            gate?.await()
            return WorkspaceAgentTurnResult(agentId, "native-$agentId", "Authoritative reply to $prompt", stopReason, changedFiles, conflicts)
        }
        override suspend fun prepareSession(agentId: String, resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit): WorkspaceAgentEvent.Configuration {
            prepares++
            return WorkspaceAgentEvent.Configuration(resumeSessionId ?: "native-$agentId", WorkspaceAgentSessionConfiguration())
        }
        override suspend fun newSession(agentId: String, onEvent: (WorkspaceAgentEvent) -> Unit): String { newSessions++; return "new-$agentId" }
        override suspend fun selectModel(agentId: String, expectedSessionId: String, modelId: String) = error("No model change expected")
        override suspend fun cancel(agentId: String, expectedSessionId: String?) { cancels++ }
    }

    private class Fixture(val scope: CoroutineScope) {
        val store = Store()
        val backend = Backend()
        var admission = true
        val controller = ExternalAgentSessionController(scope, history = store, workAdmission = { admission })
        suspend fun idle() { withTimeout(3_000) { while (controller.busy) yield() } }
    }
    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        withTimeout(8_000) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
            val f = Fixture(scope)
            try { f.block() } finally {
                f.store.gate?.complete(Unit); f.backend.gate?.complete(Unit)
                f.controller.close(); scope.coroutineContext.job.cancelAndJoin()
            }
        }
    }
    private suspend fun Fixture.failedTurn() {
        store.failures = 1
        assertTrue(controller.submit(backend, spec, "first", WorkspaceAgentMode.EDIT))
        idle()
        assertEquals(1, store.attempts.size)
        assertNotNull(controller[spec.id].persistenceError)
    }

    @Test fun successfulTurnsStillAdmitNormally() = fixture {
        assertTrue(controller.submit(backend, spec, "first", WorkspaceAgentMode.EDIT)); idle()
        assertTrue(controller.submit(backend, spec, "second", WorkspaceAgentMode.EDIT)); idle()
        assertEquals(2, backend.prompts.size)
        assertEquals(2, store.committed.size)
        assertNull(controller[spec.id].persistenceError)
    }

    @Test fun explicitSendRetriesOnlyLocalFinishAndKeepsNewDraftAndMedia() = fixture {
        failedTurn()
        val draft = "second unsent draft"
        val media = listOf(draftMedia)
        val admissionCalls = backend.mediaAdmissions
        assertFalse(controller.submit(backend, spec, draft, WorkspaceAgentMode.EDIT, media = media, mediaOwnerId = "caller-draft-owner"))
        assertTrue(controller.busy)
        idle()
        assertEquals(listOf(spec.id to "first"), backend.prompts)
        assertEquals(admissionCalls, backend.mediaAdmissions)
        assertEquals(listOf(draftMedia), media)
        assertTrue(controller.snapshotPendingPromptsForExit().isEmpty())
        assertEquals(store.attempts.first(), store.committed.single())
        assertNull(controller[spec.id].persistenceError)
        assertTrue(controller.submit(backend, spec, draft, WorkspaceAgentMode.EDIT)); idle()
        assertEquals(listOf(spec.id to "first", spec.id to draft), backend.prompts)
    }

    @Test fun repeatedFailuresRetainPayloadAndAutomaticPreparationNeverRetries() = fixture {
        failedTurn()
        val original = store.attempts.single()
        store.failures = 2
        repeat(2) {
            assertFalse(controller.submit(backend, spec, "kept draft", WorkspaceAgentMode.EDIT)); idle()
            assertNotNull(controller[spec.id].persistenceError)
            controller.prepare(backend, spec)
            controller.prepare(backend, spec, force = true)
            assertFalse(controller.restoreLatest(other))
            yield()
            assertEquals(it + 2, store.attempts.size)
        }
        assertFalse(controller.submit(backend, spec, "kept draft", WorkspaceAgentMode.EDIT)); idle()
        assertEquals(List(4) { original }, store.attempts)
        assertEquals(listOf(original), store.committed)
        assertEquals(0, backend.prepares)
        assertEquals(1, backend.prompts.size)
    }

    @Test fun completedStoppedAndFailedRepliesKeepExactNoticesAndMedia() = fixture {
        backend.events = listOf(WorkspaceAgentEvent.Text("non-authoritative preview"), WorkspaceAgentEvent.Media(outputMedia))
        backend.changedFiles = listOf("one.kt", "two.kt")
        backend.conflicts = listOf("review.kt")
        for ((reason, status) in listOf("end_turn" to PluginHistoryStatus.COMPLETED, "max_tokens" to PluginHistoryStatus.STOPPED, "unknown" to PluginHistoryStatus.FAILED)) {
            backend.stopReason = reason
            store.failures = 1
            assertTrue(controller.submit(backend, spec, reason, WorkspaceAgentMode.EDIT)); idle()
            val original = store.attempts.last()
            assertEquals("Authoritative reply to $reason", original.reply)
            assertEquals(status, original.status)
            assertEquals(listOf(outputMedia), original.media)
            assertTrue(original.notices.any { it.contains("one.kt, two.kt") })
            assertTrue(original.notices.any { it.contains("review.kt") })
            assertFalse(controller.submit(backend, spec, "never sent", WorkspaceAgentMode.EDIT)); idle()
            assertEquals(original, store.committed.last())
            assertEquals(listOf(outputMedia), controller[spec.id].messages.last { it.media.isNotEmpty() }.media)
        }
        assertEquals(listOf("end_turn", "max_tokens", "unknown"), backend.prompts.map { it.second })
    }

    @Test fun repeatedCrossPluginActionsAreSingleFlightAndNeverSwitchSessions() = fixture {
        failedTurn()
        store.entered = CompletableDeferred(); store.gate = CompletableDeferred()
        assertFalse(controller.submit(backend, other, "other draft", WorkspaceAgentMode.EDIT))
        assertEquals(spec.id, controller.lifecycleBusyAgentId)
        store.entered!!.await()
        repeat(8) {
            assertFalse(controller.submit(backend, spec, "same draft", WorkspaceAgentMode.EDIT))
            assertFalse(controller.submit(backend, other, "other draft", WorkspaceAgentMode.EDIT))
            controller.newSession(backend, other)
        }
        assertEquals(2, store.attempts.size)
        assertEquals(0, backend.newSessions)
        assertNull(controller[other.id].historyId)
        assertNull(controller[other.id].sessionId)
        store.gate!!.complete(Unit); idle()
        assertEquals(1, store.committed.size)
        assertEquals(1, backend.prompts.size)
        controller.newSession(backend, other); idle()
        assertEquals(1, backend.newSessions)
        assertEquals("new-${other.id}", controller[other.id].sessionId)
        assertEquals("Authoritative reply to first", controller[spec.id].messages.last().content)
    }

    @Test fun newSessionAloneCanRecoverWithoutSendingOrReplacingTheOldSession() = fixture {
        failedTurn()
        val old = controller[spec.id].historyId
        controller.newSession(backend, spec); idle()
        assertEquals(2, store.attempts.size)
        assertEquals(old, controller[spec.id].historyId)
        assertEquals(0, backend.newSessions)
        assertEquals(1, backend.prompts.size)
        controller.newSession(backend, spec); idle()
        assertNull(controller[spec.id].historyId)
        assertEquals(1, backend.newSessions)
    }

    @Test fun stoppedActivityRetryDoesNotAppendAgainOrPromoteQueuedInput() = fixture {
        store.failures = 1
        backend.gate = CompletableDeferred()
        backend.events = listOf(WorkspaceAgentEvent.Text("partial"), WorkspaceAgentEvent.Plan(listOf(ExternalAgentPlanEntry("Keep activity", "in_progress"))), WorkspaceAgentEvent.Media(outputMedia))
        assertTrue(controller.submit(backend, spec, "first", WorkspaceAgentMode.EDIT))
        backend.started.await(); yield()
        assertTrue(controller.submit(backend, spec, "queued", WorkspaceAgentMode.EDIT, AgentPromptDelivery.QUEUE))
        controller.cancel(backend); idle()
        val original = store.attempts.single()
        assertNull(original.reply)
        assertEquals(PluginHistoryStatus.STOPPED, original.status)
        assertEquals(listOf("Stopped."), original.notices)
        assertEquals(listOf(outputMedia), original.media)
        val savedUpdates = store.updates.toList()
        assertTrue(savedUpdates.any { it.plan })
        val pending = controller.snapshotPendingPromptsForExit()
        assertFalse(controller.submit(backend, spec, "kept draft", WorkspaceAgentMode.EDIT)); idle()
        assertEquals(original, store.committed.single())
        assertEquals(savedUpdates, store.updates)
        assertEquals(pending, controller.snapshotPendingPromptsForExit())
        assertEquals(listOf("queued"), controller.pendingInputsFor(spec.id).map { it.text })
        assertEquals(1, backend.prompts.size)
    }

    @Test fun stopJoinsInFlightReceiptAndFailureRemainsRetryable() = fixture {
        failedTurn()
        store.failures = 1; store.entered = CompletableDeferred(); store.gate = CompletableDeferred()
        assertFalse(controller.submit(backend, spec, "draft", WorkspaceAgentMode.EDIT))
        store.entered!!.await()
        controller.cancel(backend)
        yield()
        assertTrue(controller.busy)
        assertFalse(controller.submit(backend, other, "late", WorkspaceAgentMode.EDIT))
        assertEquals(0, backend.cancels)
        store.gate!!.complete(Unit); idle()
        assertNotNull(controller[spec.id].persistenceError)
        assertFalse(controller.submit(backend, spec, "draft", WorkspaceAgentMode.EDIT)); idle()
        assertEquals(store.attempts.first(), store.committed.single())
        assertEquals(1, backend.prompts.size)
    }

    @Test fun exitJoinsRetryThenClosesOnlyAfterTheReceipt() = fixture {
        failedTurn()
        store.entered = CompletableDeferred(); store.gate = CompletableDeferred()
        assertFalse(controller.submit(backend, spec, "draft", WorkspaceAgentMode.EDIT))
        store.entered!!.await()
        admission = false
        val exit = scope.async { controller.stopAndCheckpointForExit(backend) }
        yield()
        assertFalse(exit.isCompleted)
        assertEquals(2, store.attempts.size)
        assertEquals(0, store.closes)
        assertFalse(controller.submit(backend, spec, "late", WorkspaceAgentMode.EDIT))
        store.gate!!.complete(Unit)
        exit.await()
        assertEquals(2, store.attempts.size)
        assertEquals(1, store.committed.size)
        controller.closeAfterExitCheckpoint()
        assertEquals(1, store.closes)
        assertFalse(controller.busy)
    }

    @Test fun closeWaitsForRetryAndNeverPublishesItsLateWindow() = fixture {
        failedTurn()
        store.entered = CompletableDeferred(); store.gate = CompletableDeferred()
        assertFalse(controller.submit(backend, spec, "draft", WorkspaceAgentMode.EDIT))
        store.entered!!.await()
        val before = controller[spec.id]
        controller.close()
        yield()
        assertEquals(0, store.closes)
        store.gate!!.complete(Unit)
        withTimeout(3_000) { while (store.closes == 0) yield() }
        assertEquals(before, controller[spec.id])
        assertEquals(1, store.committed.size)
        assertEquals(1, backend.prompts.size)
    }

    @Test fun cancelledStoreResponseAndClosedAdmissionKeepOriginalCheckpoint() = fixture {
        failedTurn()
        store.cancelNext = true
        assertFalse(controller.submit(backend, spec, "draft", WorkspaceAgentMode.EDIT)); idle()
        assertNotNull(controller[spec.id].persistenceError)
        admission = false
        assertFalse(controller.submit(backend, spec, "draft", WorkspaceAgentMode.EDIT))
        controller.newSession(backend, other)
        yield()
        assertEquals(2, store.attempts.size)
        admission = true
        assertFalse(controller.submit(backend, spec, "draft", WorkspaceAgentMode.EDIT)); idle()
        assertEquals(List(3) { store.attempts.first() }, store.attempts)
        assertEquals(1, store.committed.size)
        assertEquals(1, backend.prompts.size)
    }

    @Test fun readOnlyArchiveIsNotMutationAuthorityAndRemainsUntouched() = fixture {
        val readOnly = PluginHistoryWindow(PluginHistorySummary("foreign", other.id, "Foreign", 1, 2, "unverified-native",
            WorkspaceAgentMode.EDIT, null, 1, 1, PluginHistoryStatus.RUNNING), listOf(AgentMessage("user", "foreign evidence")), listOf(0), 0, 1,
            "Read-only foreign owner")
        assertTrue(controller.openSaved(readOnly))
        failedTurn()
        assertFalse(controller.submit(backend, other, "must not send", WorkspaceAgentMode.EDIT))
        yield()
        assertEquals(1, store.attempts.size)
        assertEquals(readOnly, controller[other.id].historyWindow)
        assertNull(controller[other.id].sessionId)
        controller.newSession(backend, other); idle()
        assertEquals(2, store.attempts.size)
        assertEquals(readOnly, controller[other.id].historyWindow)
        assertEquals(0, backend.newSessions)
        assertEquals(1, backend.prompts.size)
    }

    @Test fun ambiguousPostCommitFailureNeverPretendsToHaveAReceiptOrReplaysBackend() = fixture {
        store.failAfterCommit = true
        assertTrue(controller.submit(backend, spec, "first", WorkspaceAgentMode.EDIT)); idle()
        val committed = store.committed.single()
        assertNotNull(controller[spec.id].persistenceError)
        store.failAfterCommit = false
        assertFalse(controller.submit(backend, spec, "draft", WorkspaceAgentMode.EDIT)); idle()
        assertEquals(listOf(committed), store.committed)
        assertEquals(listOf(committed, committed), store.attempts)
        assertNotNull(controller[spec.id].persistenceError)
        assertTrue(runCatching { controller.stopAndCheckpointForExit(backend) }.isFailure)
        assertTrue(runCatching { controller.closeAfterExitCheckpoint() }.isFailure)
        assertEquals(1, backend.prompts.size)
    }

    @Test fun stopBeforeRetryDispatchKeepsPayloadForAnotherExplicitAction() = runBlocking {
        val queued = ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
        }
        val owner = SupervisorJob()
        val f = Fixture(CoroutineScope(owner + dispatcher))
        fun drain() { while (queued.isNotEmpty()) queued.removeFirst().run() }
        suspend fun drainUntilIdle() = withTimeout(3_000) {
            while (f.controller.busy) { drain(); delay(1) }
            drain()
        }
        try {
            f.store.failures = 1
            assertTrue(f.controller.submit(f.backend, spec, "first", WorkspaceAgentMode.EDIT))
            drainUntilIdle()
            assertFalse(f.controller.busy)
            assertEquals(1, f.store.attempts.size)
            assertFalse(f.controller.submit(f.backend, spec, "draft", WorkspaceAgentMode.EDIT))
            f.controller.cancel(f.backend)
            drainUntilIdle()
            assertFalse(f.controller.busy)
            assertEquals(1, f.store.attempts.size)
            assertFalse(f.controller.submit(f.backend, spec, "draft", WorkspaceAgentMode.EDIT))
            drainUntilIdle()
            assertEquals(2, f.store.attempts.size)
            assertEquals(f.store.attempts.first(), f.store.committed.single())
            assertEquals(1, f.backend.prompts.size)
        } finally {
            f.controller.close(); owner.cancel()
            withTimeout(3_000) { while (!owner.isCompleted) { drain(); delay(1) } }
        }
    }
}
