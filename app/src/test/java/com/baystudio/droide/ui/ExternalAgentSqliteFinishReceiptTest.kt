package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Real SQLite store, fresh controller. Only return-delivery is fault injected, never SQL. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ExternalAgentSqliteFinishReceiptTest {
    private data class Finish(val id: String, val reply: String?, val notices: List<String>, val status: PluginHistoryStatus, val media: List<ChatMediaRef>)
    private class LostReturn(val durable: SqlitePluginConversationHistory): PluginConversationHistory by durable, PluginHistoryCheckpointStore by durable {
        val attempts = mutableListOf<Finish>()
        val returnedWindows = mutableListOf<PluginHistoryWindow>()
        var lose = true
        var failBefore = false
        var partialWrites = 0
        var activityWrites = 0
        override suspend fun appendCheckpointPartial(identity: PluginHistoryTurnIdentity, fragments: List<String>) { partialWrites++; durable.appendCheckpointPartial(identity, fragments) }
        override suspend fun appendCheckpointTranscript(identity: PluginHistoryTurnIdentity, updates: List<ExternalTranscriptUpdate>) { activityWrites++; durable.appendCheckpointTranscript(identity, updates) }
        override suspend fun finishCheckpoint(request: PluginHistoryFinishRequest): PluginHistoryWindow {
            val (identity, reply, notices, status, media) = request
            attempts += Finish(identity.conversationId, reply, notices.toList(), status, media.toList())
            if (failBefore) { failBefore = false; throw IOException("before commit") }
            val saved = durable.finishCheckpoint(request)
            returnedWindows += saved
            if (lose) { lose = false; throw IOException("return lost after real durable commit") }
            return saved
        }
    }
    private class Backend(val failedActivity: Boolean, val media: ChatMediaRef): WorkspaceAgentChatBackend {
        val prompts = mutableListOf<String>()
        var cancellations = 0
        override suspend fun runTurn(agentId: String, prompt: String, mode: WorkspaceAgentMode, resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit): WorkspaceAgentTurnResult {
            prompts += prompt
            onEvent(WorkspaceAgentEvent.SessionBound("native-session"))
            onEvent(WorkspaceAgentEvent.Media(media))
            if (failedActivity) {
                onEvent(WorkspaceAgentEvent.Tool("Inspect source", "source inspected"))
                onEvent(WorkspaceAgentEvent.Text("partial before backend failure"))
                throw IOException("backend stopped after activity")
            }
            return WorkspaceAgentTurnResult(agentId, "native-session", "exact final \uD83D\uDE80 response", "max_tokens", listOf("a.kt"), listOf("b.kt"))
        }
        override suspend fun prepareSession(agentId: String, resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit): WorkspaceAgentEvent.Configuration = error("No prepare allowed")
        override suspend fun newSession(agentId: String, onEvent: (WorkspaceAgentEvent) -> Unit): String = error("No new session allowed")
        override suspend fun selectModel(agentId: String, expectedSessionId: String, modelId: String): WorkspaceAgentEvent.Configuration = error("No model change")
        override suspend fun cancel(agentId: String, expectedSessionId: String?) { cancellations++ }
    }
    private fun store() = SqlitePluginConversationHistory(Files.createTempDirectory("native-finish-receipt-").toFile())
    private suspend fun idle(controller: ExternalAgentSessionController) = withTimeout(10_000) { while (controller.busy) delay(1) }
    private fun controllerCase(activity: Boolean, precommit: Boolean = false) = runBlocking {
        val durable = store(); val wrapped = LostReturn(durable).apply { if (precommit) { lose = false; failBefore = true } }
        val media = ChatMediaRef("result", "unused/result.png", "result.png", "image/png", 12, 1, 999)
        val draft = listOf(ChatMediaRef("draft", "unused/draft.png", "draft.png", "image/png", 13, 2, 999))
        val host = Backend(activity, media)
        val job = SupervisorJob(coroutineContext[Job]); val scope = CoroutineScope(coroutineContext + job)
        val controller = ExternalAgentSessionController(scope, history = wrapped, workAdmission = { true })
        val original = WorkspaceAgentPluginCatalog.entries.first(); val other = WorkspaceAgentPluginCatalog.entries[1]
        try {
            assertTrue(controller.submit(host, original, "first prompt", WorkspaceAgentMode.EDIT)); idle(controller)
            assertNotNull(controller[original.id].persistenceError)
            assertEquals(1, wrapped.attempts.size)
            val initial = wrapped.attempts.single()
            val writesBefore = wrapped.partialWrites + wrapped.activityWrites
            if (activity) { assertNull(initial.reply); assertEquals(PluginHistoryStatus.FAILED, initial.status); assertTrue(writesBefore > 0) }
            else { assertEquals("exact final \uD83D\uDE80 response", initial.reply); assertEquals(PluginHistoryStatus.STOPPED, initial.status); assertEquals(3, initial.notices.size) }
            assertEquals(listOf(media), initial.media)
            assertFalse("Retry must retain the new prompt/media with its caller", controller.submit(host, other, "kept next draft", WorkspaceAgentMode.EDIT, media = draft))
            idle(controller)
            assertEquals("Retry reached SQLite finish, not just flushCheckpoint", 2, wrapped.attempts.size)
            assertEquals(initial, wrapped.attempts.last())
            assertEquals("No checkpoint replay after finish commit", writesBefore, wrapped.partialWrites + wrapped.activityWrites)
            assertEquals(listOf("first prompt"), host.prompts)
            val saved = checkNotNull(durable.load(initial.id))
            assertEquals(1, saved.messages.count { it.role == "user" })
            assertEquals(1, saved.messages.count { it.media == listOf(media) })
            if (!activity) assertEquals(1, saved.messages.count { it.content == initial.reply })
            assertEquals(initial.status, saved.summary.status)
            println("NATIVE_FINISH_RECEIPT activity=$activity precommit=$precommit attempts=${wrapped.attempts.size} durableReturns=${wrapped.returnedWindows.size} messageCount=${saved.summary.messageCount} error=${controller[original.id].persistenceError}")
            assertNull("Exact committed PendingFinish must receive its receipt without replay", controller[original.id].persistenceError)
        } finally {
            controller.close(); job.cancelAndJoin(); durable.close()
        }
    }
    @Test fun ordinaryCrossPluginSendRecoversExactCommittedTextMediaAndNotices() = controllerCase(false)
    @Test fun failedActivityRetryReachesFinishWithoutReappendingCheckpoints() = controllerCase(true)
    @Test fun precommitFailureIsRecoverableControl() = controllerCase(false, true)
    @Test fun legacyDuplicateFinishStillCannotInventReceipt() = runBlocking {
        val durable = store()
        try {
            val id = durable.beginTurn(null, "opencode", "first", WorkspaceAgentMode.EDIT, null).summary.id
            val first = durable.finish(id, "saved response", listOf("notice"), PluginHistoryStatus.COMPLETED)
            val retry = runCatching { durable.finish(id, "saved response", listOf("notice"), PluginHistoryStatus.COMPLETED) }
            assertEquals(first, durable.load(id))
            println("NATIVE_DIRECT_RETRY failure=${retry.exceptionOrNull()}")
            assertTrue("Legacy API has no generation identity and remains strict", retry.isFailure)
        } finally { durable.close() }
    }
    @Test fun foreignOwnerCannotFinalizeRunningOrCompletedTurnControl() = runBlocking {
        val root = Files.createTempDirectory("native-finish-foreign-").toFile()
        val owner = SqlitePluginConversationHistory(root); val foreign = SqlitePluginConversationHistory(root)
        try {
            val id = owner.beginTurn(null, "opencode", "question", WorkspaceAgentMode.EDIT, null).summary.id
            assertTrue(runCatching { foreign.finish(id, "answer", emptyList(), PluginHistoryStatus.COMPLETED) }.isFailure)
            val saved = owner.finish(id, "answer", emptyList(), PluginHistoryStatus.COMPLETED)
            assertTrue(runCatching { foreign.finish(id, "answer", emptyList(), PluginHistoryStatus.COMPLETED) }.isFailure)
            assertEquals(saved, owner.load(id))
        } finally { foreign.close(); owner.close() }
    }
    private class HeldRetry(val durable: SqlitePluginConversationHistory) : PluginConversationHistory by durable, PluginHistoryCheckpointStore by durable {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var attempts = 0; var closes = 0
        override suspend fun finishCheckpoint(request: PluginHistoryFinishRequest): PluginHistoryWindow {
            attempts++
            val saved = durable.finishCheckpoint(request)
            if (attempts == 1) throw IOException("first real commit return lost")
            entered.complete(Unit); release.await(); return saved
        }
        override suspend fun close() { closes++; durable.close() }
    }
    private fun heldLifecycle(mode: String) = runBlocking {
        val durable = store(); val wrapped = HeldRetry(durable)
        val host = Backend(false, ChatMediaRef("result", "unused/result.png", "result.png", "image/png", 12, 1, 999))
        val job = SupervisorJob(coroutineContext[Job]); val scope = CoroutineScope(coroutineContext + job)
        val controller = ExternalAgentSessionController(scope, history = wrapped, workAdmission = { true })
        val spec = WorkspaceAgentPluginCatalog.entries.first()
        try {
            assertTrue(controller.submit(host, spec, "first prompt", WorkspaceAgentMode.EDIT)); idle(controller)
            assertNotNull(controller[spec.id].persistenceError)
            assertFalse(controller.submit(host, spec, "kept draft", WorkspaceAgentMode.EDIT))
            withTimeout(10_000) { wrapped.entered.await() }
            val exit = if (mode == "exit") scope.async { controller.stopAndCheckpointForExit(host) } else null
            when (mode) { "stop" -> controller.cancel(host); "close" -> controller.close() }
            repeat(20) { yield() }
            assertEquals("Store remains owned until in-flight receipt returns", 0, wrapped.closes)
            assertEquals("No remote cancel for local-only retry", 0, host.cancellations)
            assertEquals(2, wrapped.attempts); assertEquals(listOf("first prompt"), host.prompts)
            if (exit != null) assertFalse(exit.isCompleted)
            wrapped.release.complete(Unit)
            if (mode == "close") withTimeout(10_000) { while (wrapped.closes == 0) delay(1) }
            else {
                exit?.await(); idle(controller)
                assertNull(controller[spec.id].persistenceError)
                val saved = durable.load(controller[spec.id].historyId!!)!!
                assertEquals(5, saved.summary.messageCount)
                if (mode == "exit") controller.closeAfterExitCheckpoint()
            }
            assertEquals(2, wrapped.attempts); assertEquals(listOf("first prompt"), host.prompts)
        } finally { wrapped.release.complete(Unit); controller.close(); job.cancelAndJoin(); durable.close() }
    }
    @Test fun stopJoinsNativeReceiptRetryWithoutBackendCancellation() = heldLifecycle("stop")
    @Test fun exitJoinsNativeReceiptRetryBeforeClosing() = heldLifecycle("exit")
    @Test fun closeJoinsNativeReceiptRetryBeforeStoreClose() = heldLifecycle("close")

}
