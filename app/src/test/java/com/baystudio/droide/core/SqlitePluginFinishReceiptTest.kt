package com.baystudio.droide.core

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SqlitePluginFinishReceiptTest {
    private suspend fun admitted(store: SqlitePluginConversationHistory): PluginHistoryFinishRequest {
        val turn = store.beginCheckpointTurn(null, "opencode", "question", WorkspaceAgentMode.EDIT, null)
        return PluginHistoryFinishRequest(turn.identity, "answer 🚀\uD83D", listOf("first", "second"), PluginHistoryStatus.COMPLETED)
    }
    private fun store() = SqlitePluginConversationHistory(Files.createTempDirectory("finish-receipt-store-").toFile())
    @Test fun beforeCommitRollsBackTranscriptAndReceiptThenRetriesOnce() = runBlocking {
        val store = store()
        try {
            val request = admitted(store)
            store.lifecycleCheckpoint = { if (it == "before-finish-commit") error("precommit failure") }
            assertTrue(runCatching { store.finishCheckpoint(request) }.isFailure)
            assertEquals(PluginHistoryStatus.RUNNING, store.listPage().entries.single().status)
            store.lifecycleCheckpoint = {}
            val saved = store.finishCheckpoint(request)
            assertEquals(4, saved.summary.messageCount)
            assertEquals(saved, store.finishCheckpoint(request))
            assertEquals(saved, store.load(request.identity.conversationId))
        } finally { store.lifecycleCheckpoint = {}; store.close() }
    }
    @Test fun nativePostcommitReturnExceptionLeavesExactRecoverableReceipt() = runBlocking {
        val store = store()
        try {
            val request = admitted(store)
            store.lifecycleCheckpoint = { if (it == "after-finish-commit") error("postcommit return failure") }
            assertTrue(runCatching { store.finishCheckpoint(request) }.isFailure)
            assertEquals(PluginHistoryStatus.COMPLETED, store.listPage().entries.single().status)
            store.lifecycleCheckpoint = {}
            val recovered = store.finishCheckpoint(request)
            assertEquals(listOf("question", request.reply, "first", "second"), recovered.messages.map { it.content })
            assertEquals(recovered, store.finishCheckpoint(request))
        } finally { store.lifecycleCheckpoint = {}; store.close() }
    }
    @Test fun directCancellableCallerCanLoseIoReturnAfterCommitAndRecover() = runBlocking {
        val store = store(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            val request = admitted(store)
            store.lifecycleCheckpoint = { if (it == "after-finish-commit") { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) } }
            val caller = async(Dispatchers.Default) { store.finishCheckpoint(request) }
            assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
            caller.cancel(); release.countDown()
            assertTrue(runCatching { caller.await() }.exceptionOrNull() is CancellationException)
            store.lifecycleCheckpoint = {}
            val recovered = store.finishCheckpoint(request)
            assertEquals(4, recovered.summary.messageCount)
            assertEquals(request.reply, recovered.messages[1].content)
        } finally { release.countDown(); store.lifecycleCheckpoint = {}; store.close() }
    }
    @Test fun concurrentIdenticalFinishesReturnOneTranscript() = runBlocking {
        val store = store()
        try {
            val request = admitted(store)
            val results = List(12) { async(Dispatchers.Default) { store.finishCheckpoint(request) } }.awaitAll()
            assertTrue(results.all { it == results.first() })
            assertEquals(4, results.first().summary.messageCount)
        } finally { store.close() }
    }
    @Test fun newerSameOwnerTurnRejectsOldPrepBeforeMutationAndOldFinish() = runBlocking {
        val store = store()
        try {
            val old = admitted(store); store.finishCheckpoint(old)
            val next = store.beginCheckpointTurn(old.identity.conversationId, "opencode", "question", WorkspaceAgentMode.EDIT, "current-native")
            assertNotEquals(old.identity, next.identity)
            assertTrue(runCatching { store.bindCheckpoint(old.identity, "stale-native") }.isFailure)
            assertTrue(runCatching { store.appendCheckpointPartial(old.identity, listOf("stale partial")) }.isFailure)
            assertTrue(runCatching { store.appendCheckpointTranscript(old.identity, listOf(ExternalTranscriptUpdate(
                AgentStreamPart("stale", 0, AgentStreamPartKind.TEXT, AgentStreamPartState.RUNNING), "stale transcript"))) }.isFailure)
            assertTrue(runCatching { store.finishCheckpoint(old) }.isFailure)
            val current = store.finishCheckpoint(old.copy(identity = next.identity))
            assertEquals(8, current.summary.messageCount)
            assertFalse(current.messages.any { it.content.startsWith("stale") })
            assertNotEquals("stale-native", current.summary.nativeSessionId)
        } finally { store.close() }
    }
}
