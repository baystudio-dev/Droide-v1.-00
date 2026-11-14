package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Independent review. Runs actual controller and real domain objects; no SQLite/native/Android runtime. */
class IndependentHistoryRetryReviewTest {
    private data class Finish(val id: String, val reply: String?, val notices: List<String>, val status: PluginHistoryStatus, val media: List<ChatMediaRef>)
    private class Store : PluginConversationHistory {
        val windows = linkedMapOf<String, PluginHistoryWindow>()
        val attempts = mutableListOf<Finish>(); val committed = mutableListOf<Finish>()
        val appended = mutableListOf<String>(); val calls = mutableListOf<String>()
        var failCount = 1; var cancelCount = 0; var loseReceipt = false; var gate: CompletableDeferred<Unit>? = null
        var mutators = 0; var maxMutators = 0; var closed = false
        private suspend fun <T> mutation(label: String, block: suspend () -> T): T {
            check(!closed) { "Store used after close" }; calls += label; mutators++; maxMutators = maxOf(maxMutators, mutators)
            try { return block() } finally { mutators-- }
        }
        override suspend fun listPage(agentId: String?, query: String, before: PluginHistoryCursor?, limit: Int) = PluginHistoryPage(windows.values.map { it.summary }, null)
        override suspend fun latest(agentId: String) = windows.values.lastOrNull { it.summary.agentId == agentId }
        override suspend fun load(id: String, beforePart: Long?) = windows[id]
        override suspend fun beginTurn(id: String?, agentId: String, prompt: String, mode: WorkspaceAgentMode, nativeSessionId: String?): PluginHistoryWindow = mutation("begin:$agentId") {
            val old = id?.let { windows[it] }; check(old?.summary?.status != PluginHistoryStatus.RUNNING)
            val messages = old?.messages.orEmpty() + AgentMessage("user", prompt)
            PluginHistoryWindow(PluginHistorySummary(id ?: "id-${windows.size}", agentId, prompt, 1, 1, nativeSessionId, mode, null, messages.size, messages.size.toLong(), PluginHistoryStatus.RUNNING), messages, messages.indices.map { it.toLong() }, 0, messages.size.toLong()).also { windows[it.summary.id] = it }
        }
        override suspend fun bind(id: String, nativeSessionId: String, modelId: String?) = mutation("bind:$id") {
            windows[id] = windows.getValue(id).let { it.copy(summary = it.summary.copy(nativeSessionId = nativeSessionId, modelId = modelId)) }
        }
        override suspend fun appendPartial(id: String, fragments: List<String>) = mutation("append:$id") { appended.addAll(fragments); Unit }
        override suspend fun appendTranscript(id: String, updates: List<ExternalTranscriptUpdate>) = mutation("appendTranscript:$id") { updates.mapNotNullTo(appended) { it.textDelta }; Unit }
        override suspend fun finish(id: String, reply: String?, notices: List<String>, status: PluginHistoryStatus) = finishMediaTurn(id, reply, notices, status, emptyList())
        override suspend fun finishMediaTurn(id: String, reply: String?, notices: List<String>, status: PluginHistoryStatus, media: List<ChatMediaRef>): PluginHistoryWindow = mutation("finish:$id") {
            val payload = Finish(id, reply, notices.toList(), status, media.toList()); attempts += payload
            check(windows.getValue(id).summary.status == PluginHistoryStatus.RUNNING) { "Plugin turn is already finalized" }
            if (failCount > 0) { failCount--; throw IOException("transient write") }
            if (cancelCount > 0) { cancelCount--; throw CancellationException("cancelled store operation") }
            gate?.await()
            committed += payload
            val old = windows.getValue(id)
            val messages = old.messages + listOfNotNull(reply?.let { AgentMessage("assistant", it, media = media) }) + notices.map { AgentMessage("assistant", it) }
            old.copy(summary = old.summary.copy(status = status, messageCount = messages.size, partCount = messages.size.toLong()), messages = messages, messageKeys = messages.indices.map { it.toLong() }, endPart = messages.size.toLong()).also { windows[id] = it; if (loseReceipt) throw IOException("ambiguous acknowledgement after commit") }
        }
        override suspend fun delete(id: String) { error("No deletion authorized or needed") }
        override suspend fun close() { assertEquals("Close waited for receipt", 0, mutators); closed = true; calls += "close" }
    }
    private class Backend : WorkspaceAgentChatBackend {
        val prompts = mutableListOf<Pair<String, String>>()
        var preparations = 0; var newSessions = 0; var cancellations = 0; var selections = 0
        var hold: CompletableDeferred<Unit>? = null; var reply = "exact final response"; var reason = "end_turn"
        var changed = emptyList<String>(); var conflicts = emptyList<String>(); var media = emptyList<ChatMediaRef>()
        var partial = ""; var failTurn = false
        private val configuration = WorkspaceAgentSessionConfiguration(models = listOf(WorkspaceAgentModelOption("m", "model")), currentModelId = "m", reasoningOptions = listOf(WorkspaceAgentModelOption("r", "reason")), currentReasoningId = "r")
        override suspend fun runTurn(agentId: String, prompt: String, mode: WorkspaceAgentMode, resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit): WorkspaceAgentTurnResult {
            prompts += agentId to prompt; onEvent(WorkspaceAgentEvent.SessionBound("session-$agentId")); onEvent(WorkspaceAgentEvent.Configuration("session-$agentId", configuration))
            if (partial.isNotEmpty()) onEvent(WorkspaceAgentEvent.Text(partial)); media.forEach { onEvent(WorkspaceAgentEvent.Media(it)) }
            hold?.await(); if (failTurn) throw IOException("backend failure")
            return WorkspaceAgentTurnResult(agentId, "session-$agentId", reply, reason, changed, conflicts)
        }
        override suspend fun prepareSession(agentId: String, resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit): WorkspaceAgentEvent.Configuration { preparations++; return WorkspaceAgentEvent.Configuration(resumeSessionId ?: "session-$agentId", configuration) }
        override suspend fun newSession(agentId: String, onEvent: (WorkspaceAgentEvent) -> Unit): String { newSessions++; return "new-$agentId" }
        override suspend fun selectModel(agentId: String, expectedSessionId: String, modelId: String): WorkspaceAgentEvent.Configuration { selections++; return WorkspaceAgentEvent.Configuration(expectedSessionId, configuration) }
        override suspend fun selectReasoning(agentId: String, expectedSessionId: String, value: String): WorkspaceAgentEvent.Configuration { selections++; return WorkspaceAgentEvent.Configuration(expectedSessionId, configuration) }
        override suspend fun cancel(agentId: String, expectedSessionId: String?) { cancellations++ }
    }
    private class Fixture(val scope: CoroutineScope) {
        val store = Store(); val host = Backend(); var admission = true
        val c = ExternalAgentSessionController(scope, history = store, workAdmission = { admission })
        val a = WorkspaceAgentPluginCatalog.entries[0]; val b = WorkspaceAgentPluginCatalog.entries[1]
        suspend fun failedTurn() { assertTrue(c.submit(host, a, "first", WorkspaceAgentMode.EDIT)); idle(); assertEquals(1, store.attempts.size); assertNotNull(c[a.id].persistenceError) }
        suspend fun idle() = withTimeout(2000) { while (c.busy) yield() }
        suspend fun attempts(n: Int) = withTimeout(2000) { while (store.attempts.size < n) yield() }
        fun retry(spec: WorkspaceAgentPluginSpec = a) = c.submit(host, spec, "kept draft", WorkspaceAgentMode.EDIT)
    }
    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        withTimeout(6000) {
            val child = SupervisorJob(coroutineContext[Job]); val f = Fixture(CoroutineScope(coroutineContext + child))
            try { f.block() } finally { f.store.gate?.complete(Unit); f.host.hold?.complete(Unit); if (!f.store.closed) f.c.close(); child.cancelAndJoin() }
        }
    }
    @Test fun explicitSubmitRepairsOnlyHistoryThenSecondExplicitSubmitSends() = fixture {
        failedTurn(); assertFalse(retry()); idle()
        assertEquals(2, store.attempts.size); assertEquals(store.attempts[0], store.attempts[1]); assertEquals(1, host.prompts.size); assertNull(c[a.id].persistenceError)
        assertTrue(retry()); idle(); assertEquals(listOf("first", "kept draft"), host.prompts.map { it.second }); assertEquals(2, store.committed.size)
    }
    @Test fun exactMediaNoticesAndStoppedStatusSurviveRetry() = fixture {
        host.reason = "max_tokens"; host.changed = listOf("a.kt", "b.kt"); host.conflicts = listOf("conflict.kt")
        host.media = listOf(ChatMediaRef("output", "out.png", "output.png", "image/png", 32, 1, 999))
        failedTurn(); val retained = store.attempts.single()
        assertEquals(PluginHistoryStatus.STOPPED, retained.status); assertEquals(host.reply, retained.reply); assertEquals(host.media, retained.media)
        assertEquals(3, retained.notices.size); assertTrue(retained.notices[0].contains("max_tokens")); assertTrue(retained.notices[1].contains("a.kt")); assertTrue(retained.notices[2].contains("conflict.kt"))
        assertFalse(retry()); idle(); assertEquals(listOf(retained, retained), store.attempts); assertEquals(listOf(retained), store.committed); assertEquals(1, store.maxMutators)
    }
    @Test fun repeatedFailureAndStoreCancellationRetainExactPayload() = fixture {
        failedTurn(); store.failCount = 1; assertFalse(retry()); idle(); assertEquals(2, store.attempts.size); assertNotNull(c[a.id].persistenceError)
        store.cancelCount = 1; assertFalse(retry()); idle(); assertEquals(3, store.attempts.size); assertNotNull(c[a.id].persistenceError)
        assertFalse(retry()); idle(); assertEquals(4, store.attempts.size); assertTrue(store.attempts.all { it == store.attempts[0] }); assertNull(c[a.id].persistenceError); assertEquals(1, host.prompts.size)
    }
    @Test fun crossPluginRepeatedSubmitsAndNewSessionShareOneOwnedRetry() = fixture {
        failedTurn(); store.gate = CompletableDeferred(); assertFalse(retry(b)); attempts(2)
        assertTrue(c.busy); assertEquals(a.id, c.lifecycleBusyAgentId)
        repeat(12) { assertFalse(retry(if (it % 2 == 0) a else b)); c.newSession(host, b); c.prepare(host, b, force = true); c.selectModel(host, a, "m"); c.selectReasoning(host, a, "r") }
        assertEquals(2, store.attempts.size); assertEquals(1, host.prompts.size); assertEquals(0, host.newSessions); assertEquals(0, host.preparations); assertEquals(0, host.selections)
        store.gate!!.complete(Unit); idle(); assertEquals(1, store.maxMutators); assertTrue(retry(b)); idle(); assertEquals(b.id, host.prompts.last().first)
    }
    @Test fun newSessionFirstRetriesOnlyThenNextExplicitActionCreatesSession() = fixture {
        failedTurn(); c.newSession(host, b); idle(); assertEquals(2, store.attempts.size); assertEquals(0, host.newSessions); assertEquals(0, host.preparations)
        c.newSession(host, b); idle(); assertEquals(1, host.newSessions); assertEquals(1, host.preparations); assertEquals(1, host.prompts.size)
    }
    @Test fun stopJoinsNonCancellableRetryReceiptWithoutBackendCancel() = fixture {
        failedTurn(); store.gate = CompletableDeferred(); assertFalse(retry()); attempts(2); c.cancel(host); repeat(20) { yield() }
        assertTrue(c.busy); assertEquals(0, host.cancellations); assertEquals(0, store.committed.size); assertFalse(retry(b))
        store.gate!!.complete(Unit); idle(); assertEquals(1, store.committed.size); assertNull(c[a.id].persistenceError); assertEquals(1, host.prompts.size)
    }
    @Test fun exitWaitsForInFlightRetryWithoutDuplicateFinish() = fixture {
        failedTurn(); store.gate = CompletableDeferred(); assertFalse(retry()); attempts(2)
        val exit = scope.async { c.stopAndCheckpointForExit(host) }; repeat(20) { yield() }; assertFalse(exit.isCompleted); assertFalse(retry(b)); assertEquals(2, store.attempts.size)
        store.gate!!.complete(Unit); exit.await(); assertEquals(2, store.attempts.size); assertEquals(1, store.committed.size); assertEquals(1, store.maxMutators)
        c.closeAfterExitCheckpoint(); assertTrue(store.closed); assertFalse(retry()); assertEquals(1, host.prompts.size)
    }
    @Test fun closeWaitsForRetryBeforeClosingStore() = fixture {
        failedTurn(); store.gate = CompletableDeferred(); assertFalse(retry()); attempts(2); c.close(); repeat(20) { yield() }; assertFalse(store.closed); assertFalse(retry())
        store.gate!!.complete(Unit); withTimeout(2000) { while (!store.closed) yield() }; assertEquals(1, store.committed.size); assertEquals("close", store.calls.last())
    }
    @Test fun queuedPromptIsNotAutomaticallyPromotedAfterRepair() = fixture {
        host.hold = CompletableDeferred(); assertTrue(c.submit(host, a, "first", WorkspaceAgentMode.EDIT)); withTimeout(2000) { while (host.prompts.isEmpty()) yield() }
        assertTrue(c.submit(host, a, "already queued", WorkspaceAgentMode.EDIT, AgentPromptDelivery.QUEUE)); host.hold!!.complete(Unit); idle(); assertEquals(1, c.pendingInputsFor(a.id).size)
        assertFalse(retry()); idle(); assertEquals(2, store.attempts.size); assertEquals(1, host.prompts.size); assertEquals("already queued", c.pendingInputsFor(a.id).single().text)
    }
    @Test fun closedAdmissionDoesNotTriggerLocalWritesOrBackendWork() = fixture {
        failedTurn(); admission = false; assertFalse(retry()); c.newSession(host, b); c.prepare(host, a, true); repeat(20) { yield() }
        assertEquals(1, store.attempts.size); assertEquals(1, host.prompts.size); assertEquals(0, host.newSessions)
        admission = true; assertFalse(retry()); idle(); assertEquals(2, store.attempts.size)
    }
    @Test fun ordinaryHealthyTurnDoesNotRequireExtraSubmitAndReadonlyNeverSends() = fixture {
        store.failCount = 0; assertTrue(c.submit(host, a, "normal", WorkspaceAgentMode.EDIT)); idle(); assertEquals(1, store.committed.size)
        val own = c[a.id].historyWindow!!; val foreign = own.copy(summary = own.summary.copy(id = "foreign", agentId = b.id), readOnlyReason = "Owned elsewhere")
        assertTrue(c.openSaved(foreign)); assertFalse(c.submit(host, b, "must not send", WorkspaceAgentMode.EDIT)); assertEquals(1, host.prompts.size); assertEquals("Owned elsewhere", c[b.id].connectionError)
    }
    @Test fun failedBackendNullReplyAndFailedStatusRetryWithoutDuplicatingPartial() = fixture {
        host.partial = "partial answer"; host.failTurn = true; failedTurn(); val retained = store.attempts[0]
        assertNull(retained.reply); assertEquals(PluginHistoryStatus.FAILED, retained.status); assertTrue(retained.notices.single().contains("backend failure")); assertEquals(listOf("partial answer"), store.appended)
        assertFalse(retry()); idle(); assertEquals(listOf(retained, retained), store.attempts); assertEquals(listOf("partial answer"), store.appended); assertEquals(1, store.maxMutators)
    }
    @Test fun exitControlRepairsFailedFinalizationWithoutBackendReplay() = fixture {
        failedTurn(); c.stopAndCheckpointForExit(host); assertEquals(2, store.attempts.size); assertEquals(store.attempts[0], store.attempts[1]); assertEquals(1, host.prompts.size); c.closeAfterExitCheckpoint(); assertTrue(store.closed)
    }
    @Test fun stopBeforeRetryDispatchRetainsPayloadForNextExplicitRetry() = fixture {
        failedTurn(); assertFalse(retry()); assertTrue(c.busy); c.cancel(host); idle()
        assertEquals(1, store.attempts.size); assertNotNull(c[a.id].persistenceError); assertEquals(0, host.cancellations)
        assertFalse(retry()); idle(); assertEquals(2, store.attempts.size); assertEquals(1, store.committed.size)
    }
    @Test fun closeBeforeRetryDispatchOwnsAndCancelsLazyJobBeforeStoreClose() = fixture {
        failedTurn(); assertFalse(retry()); assertEquals(a.id, c.lifecycleBusyAgentId); c.close()
        withTimeout(2000) { while (!store.closed) yield() }; assertEquals(1, store.attempts.size); assertFalse(retry()); assertEquals("close", store.calls.last())
    }
    @Test fun exitBeforeRetryDispatchCancelsOwnedJobThenCommitsExactlyOnce() = fixture {
        failedTurn(); assertFalse(retry()); c.stopAndCheckpointForExit(host)
        assertEquals(2, store.attempts.size); assertEquals(1, store.committed.size); assertEquals(1, host.prompts.size); c.closeAfterExitCheckpoint()
    }
    @Test fun readonlyOtherPluginSubmitCannotEvenTriggerRetainedWrite() = fixture {
        val summary = PluginHistorySummary("foreign", b.id, "foreign title", 1, 1, "foreign-native", WorkspaceAgentMode.EDIT, null, 0, 0, PluginHistoryStatus.RUNNING)
        val foreign = PluginHistoryWindow(summary, emptyList(), emptyList(), 0, 0, "Owned elsewhere")
        assertTrue(c.openSaved(foreign)); failedTurn(); assertFalse(retry(b)); repeat(20) { yield() }
        assertEquals(1, store.attempts.size); assertEquals(foreign, c[b.id].historyWindow); assertEquals(1, host.prompts.size)
        c.newSession(host, b); idle(); assertEquals(2, store.attempts.size); assertEquals(0, host.newSessions); assertEquals(foreign, c[b.id].historyWindow)
    }
    @Test fun canceledScopeCannotLaunchRetryOrNewSession() = fixture {
        failedTurn(); scope.cancel(); assertFalse(retry()); c.newSession(host, b)
        assertEquals(1, store.attempts.size); assertEquals(0, host.newSessions); assertEquals(1, host.prompts.size)
    }
    @Test fun ambiguousCommittedFailureNeverInventsReceiptOrAdmitsNewBackendWork() = fixture {
        store.failCount = 0; store.loseReceipt = true; failedTurn(); val retained = store.attempts.single()
        assertEquals(1, store.committed.size); assertEquals(PluginHistoryStatus.COMPLETED, store.windows.getValue(retained.id).summary.status)
        repeat(2) { assertFalse(retry()); idle(); assertNotNull(c[a.id].persistenceError) }
        c.newSession(host, b); idle(); assertEquals(4, store.attempts.size); assertTrue(store.attempts.all { it == retained }); assertEquals(1, store.committed.size)
        assertEquals(1, host.prompts.size); assertEquals(0, host.newSessions)
        try { c.stopAndCheckpointForExit(host); fail("Exit must report uncertain finalization") } catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("already finalized")) }
        assertFalse(store.closed); assertNotNull(c[a.id].persistenceError)
    }

}
