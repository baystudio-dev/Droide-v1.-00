package com.baystudio.droide.ui

import android.database.sqlite.SQLiteDatabase
import com.baystudio.droide.core.*
import java.nio.file.Files
import java.io.IOException
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
class ExternalAgentHistoryReadFailureTest {
    @get:org.junit.Rule val applicationAdmission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()
    private val root = Files.createTempDirectory("failed-history-restore-probe-").toFile()
    private val stores = mutableListOf<SqlitePluginConversationHistory>()
    private val spec = WorkspaceAgentPluginCatalog.entries.first()
    private fun store() = SqlitePluginConversationHistory(root).also { stores += it }
    @After fun cleanup() = runBlocking { stores.forEach { it.close() }; assertTrue(PathSecurity.deleteTreeNoFollow(root)) }
    private fun fixture(block: suspend (CoroutineScope) -> Unit) = runBlocking {
        withTimeout(20_000) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
            try { block(scope) } finally { scope.coroutineContext.job.cancelAndJoin() }
        }
    }
    private suspend fun ExternalAgentSessionController.idle() = withTimeout(5_000) { while (busy) delay(1) }
    private suspend fun malformed(owner: SqlitePluginConversationHistory): String {
        val id = owner.beginTurn(null, spec.id, "Original unfinished question", WorkspaceAgentMode.EDIT, "old-live-native").summary.id
        owner.appendPartial(id, listOf("original live checkpoint"))
        SQLiteDatabase.openDatabase(root.resolve(".droide/sessions/plugins.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE live_parts SET content='',content_utf16=? WHERE conversation_id=?", arrayOf(byteArrayOf(0), id))
        }
        return id
    }
    private class Backend : WorkspaceAgentChatBackend {
        var currentNative = "old-live-native"
        var newCalls = 0
        var prepareCalls = 0
        var mediaChecks = 0
        val resumeIds = mutableListOf<String?>()
        override fun mediaAdmissionError(agentId: String, media: List<ChatMediaRef>): String? { mediaChecks++; return null }
        override suspend fun runTurn(agentId: String, prompt: String, mode: WorkspaceAgentMode, resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit): WorkspaceAgentTurnResult {
            resumeIds += resumeSessionId
            currentNative = resumeSessionId ?: currentNative
            onEvent(WorkspaceAgentEvent.SessionBound(currentNative))
            return WorkspaceAgentTurnResult(agentId, currentNative, "Backend reused $currentNative", "end_turn", emptyList(), emptyList())
        }
        override suspend fun prepareSession(agentId: String, resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit): WorkspaceAgentEvent.Configuration {
            prepareCalls++; currentNative = resumeSessionId ?: currentNative
            return WorkspaceAgentEvent.Configuration(currentNative, WorkspaceAgentSessionConfiguration())
        }
        override suspend fun newSession(agentId: String, onEvent: (WorkspaceAgentEvent) -> Unit): String { newCalls++; currentNative = "new-native-$newCalls"; return currentNative }
        override suspend fun selectModel(agentId: String, expectedSessionId: String, modelId: String) = WorkspaceAgentEvent.Configuration(expectedSessionId, WorkspaceAgentSessionConfiguration())
        override suspend fun cancel(agentId: String, expectedSessionId: String?) = Unit
    }

    @Test fun failedInitialRestoreMustNotSendNullResumeToAnExistingNativeContext() = fixture { scope ->
        val original = store(); val originalId = malformed(original)
        val replacementStore = store()
        val replacement = ExternalAgentSessionController(scope, history = replacementStore)
        val backend = Backend()
        replacement.restoreLatest(spec); replacement.idle()
        assertNotNull(replacement[spec.id].persistenceError)
        assertNull(replacement[spec.id].sessionId)
        val admitted = replacement.submit(backend, spec, "After failed restore", WorkspaceAgentMode.EDIT)
        replacement.idle()
        assertFalse(admitted)
        assertEquals(0, backend.mediaChecks)
        assertEquals(1, replacementStore.listPage(spec.id).entries.size)
        assertTrue(replacement[spec.id].messages.isEmpty())
        assertEquals(PluginHistoryStatus.RUNNING, original.listPage(spec.id).entries.single { it.id == originalId }.status)
        assertEquals("Initial restore failure must block backend admission, not silently reuse native context", 0, backend.resumeIds.size)
        replacement.close()
    }

    @Test fun successfulInitialRestoreRetryClearsOnlyItsAdmissionFailure() = fixture { scope ->
        val original = store(); val originalId = malformed(original)
        val replacement = ExternalAgentSessionController(scope, history = store())
        val backend = Backend()
        replacement.restoreLatest(spec); replacement.idle()
        assertFalse(replacement.submit(backend, spec, "Keep this draft", WorkspaceAgentMode.EDIT))
        assertEquals(0, backend.mediaChecks)
        val fixed = "Repaired fixture checkpoint"
        val bytes = ByteArray(fixed.length * 2)
        fixed.forEachIndexed { index, unit -> bytes[index * 2] = unit.code.toByte(); bytes[index * 2 + 1] = (unit.code ushr 8).toByte() }
        SQLiteDatabase.openDatabase(root.resolve(".droide/sessions/plugins.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE live_parts SET content='',content_utf16=? WHERE conversation_id=?", arrayOf(bytes, originalId))
        }
        original.close()
        replacement.prepare(backend, spec, force = true); replacement.idle()
        assertEquals(originalId, replacement[spec.id].historyId)
        assertEquals("old-live-native", replacement[spec.id].sessionId)
        assertNull(replacement[spec.id].connectionError); assertNull(replacement[spec.id].persistenceError)
        assertEquals(0, backend.prepareCalls)
        assertTrue(replacement.submit(backend, spec, "Continue verified saved context", WorkspaceAgentMode.EDIT)); replacement.idle()
        assertEquals(listOf("old-live-native"), backend.resumeIds)
        assertEquals(0, backend.newCalls)
        replacement.close()
    }

    @Test fun successfulExplicitOpenClearsFailedInitialRestoreWithoutStartingFreshNative() = fixture { scope ->
        val original = store()
        val validId = original.beginTurn(null, spec.id, "Verified earlier conversation", WorkspaceAgentMode.EDIT, "verified-native").summary.id
        original.finish(validId, "Verified answer", emptyList(), PluginHistoryStatus.COMPLETED)
        malformed(original)
        val replacement = ExternalAgentSessionController(scope, history = store())
        val backend = Backend()
        replacement.restoreLatest(spec); replacement.idle()
        assertFalse(replacement.submit(backend, spec, "Blocked until selection", WorkspaceAgentMode.EDIT))
        replacement.openSaved(validId, spec.id)
        assertNull(replacement[spec.id].connectionError); assertNull(replacement[spec.id].persistenceError)
        assertTrue(replacement.submit(backend, spec, "Continue selected history", WorkspaceAgentMode.EDIT)); replacement.idle()
        assertEquals(listOf("verified-native"), backend.resumeIds)
        assertEquals(validId, replacement[spec.id].historyId)
        assertEquals(0, backend.newCalls)
        replacement.close()
    }

    @Test fun failedFirstExplicitOpenAlsoCannotFallThroughToNullNativeReuse() = fixture { scope ->
        val originalId = malformed(store())
        val replacement = ExternalAgentSessionController(scope, history = store())
        val backend = Backend()
        var failure: Exception? = null
        try { replacement.openSaved(originalId, spec.id) } catch (error: Exception) { failure = error }
        assertNotNull(failure)
        assertFalse(replacement.submit(backend, spec, "Keep unverified draft", WorkspaceAgentMode.EDIT))
        assertEquals(0, backend.mediaChecks); assertTrue(backend.resumeIds.isEmpty())
        replacement.close()
    }

    @Test fun emptyRetryAfterInitialReadFailureStillRequiresExplicitFreshNativeSession() = fixture { scope ->
        var failRead = true
        val history = object : PluginConversationHistory by store() {
            override suspend fun latest(agentId: String): PluginHistoryWindow? {
                if (failRead) throw IOException("Transient initial read failure")
                return null
            }
        }
        val replacement = ExternalAgentSessionController(scope, history = history)
        val backend = Backend()
        replacement.prepare(backend, spec); replacement.idle()
        assertNotNull(replacement[spec.id].persistenceError)
        failRead = false
        replacement.prepare(backend, spec, force = true); replacement.idle()
        assertNull(replacement[spec.id].persistenceError)
        assertNotNull(replacement[spec.id].connectionError)
        assertEquals(0, backend.prepareCalls)
        assertEquals("old-live-native", backend.currentNative)
        assertFalse(replacement.submit(backend, spec, "Do not silently reuse context", WorkspaceAgentMode.EDIT))
        assertEquals(0, backend.mediaChecks); assertTrue(backend.resumeIds.isEmpty())
        replacement.newSession(backend, spec); replacement.idle()
        assertEquals(1, backend.newCalls)
        assertEquals("new-native-1", replacement[spec.id].sessionId)
        assertTrue(replacement.submit(backend, spec, "Explicit independent context", WorkspaceAgentMode.EDIT)); replacement.idle()
        assertEquals(listOf("new-native-1"), backend.resumeIds)
        replacement.close()
    }

    @Test fun cancellingInitialRestoreOrExplicitOpenKeepsUnknownNativeContextBlocked() = fixture { scope ->
        for (openExplicitly in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val history = object : PluginConversationHistory by store() {
                override suspend fun latest(agentId: String): PluginHistoryWindow? { entered.complete(Unit); awaitCancellation() }
                override suspend fun load(id: String, beforePart: Long?): PluginHistoryWindow? { entered.complete(Unit); awaitCancellation() }
            }
            val replacement = ExternalAgentSessionController(scope, history = history)
            val backend = Backend()
            if (openExplicitly) {
                val open = scope.async { replacement.openSaved(PluginConversationHistory.newId(), spec.id) }
                entered.await(); open.cancelAndJoin()
                assertTrue(open.isCancelled)
            } else {
                replacement.restoreLatest(spec); entered.await()
                replacement.cancel(null); replacement.idle()
            }
            assertNull(replacement[spec.id].connectionError)
            assertNull(replacement[spec.id].persistenceError)
            assertFalse(replacement.submit(backend, spec, "Unverified after cancelled read", WorkspaceAgentMode.EDIT))
            assertEquals(0, backend.mediaChecks); assertTrue(backend.resumeIds.isEmpty())
            replacement.newSession(backend, spec); replacement.idle()
            assertTrue(replacement.submit(backend, spec, "Explicit fresh session after cancel", WorkspaceAgentMode.EDIT)); replacement.idle()
            assertEquals(listOf("new-native-1"), backend.resumeIds)
            replacement.close()
        }
    }

    @Test fun stopBeforeInitialRestoreDispatchCannotLeaveNullNativeAdmissionOpen() = fixture { scope ->
        var reads = 0
        val history = object : PluginConversationHistory by store() {
            override suspend fun latest(agentId: String): PluginHistoryWindow? { reads++; awaitCancellation() }
        }
        val replacement = ExternalAgentSessionController(scope, history = history)
        val backend = Backend()
        assertTrue(replacement.restoreLatest(spec))
        replacement.cancel(null); replacement.idle()
        assertEquals(0, reads)
        assertNull(replacement[spec.id].connectionError)
        assertFalse(replacement.submit(backend, spec, "Keep unsent draft", WorkspaceAgentMode.EDIT))
        assertEquals(0, backend.mediaChecks); assertTrue(backend.resumeIds.isEmpty())
        replacement.close()
    }

    @Test fun failedExplicitOpenKeepsPreviouslyVerifiedConversationUsable() = fixture { scope ->
        val original = store(); val originalId = malformed(original)
        val validId = original.beginTurn(null, spec.id, "Verified conversation", WorkspaceAgentMode.EDIT, "verified-native").summary.id
        original.finish(validId, "Verified answer", emptyList(), PluginHistoryStatus.COMPLETED)
        val replacement = ExternalAgentSessionController(scope, history = store())
        val backend = Backend()
        replacement.restoreLatest(spec); replacement.idle()
        assertEquals(validId, replacement[spec.id].historyId)
        var failure: Exception? = null
        try { replacement.openSaved(originalId, spec.id) } catch (error: Exception) { failure = error }
        assertNotNull(failure)
        assertEquals(validId, replacement[spec.id].historyId)
        assertEquals("verified-native", replacement[spec.id].sessionId)
        assertTrue(replacement.submit(backend, spec, "Continue verified context", WorkspaceAgentMode.EDIT)); replacement.idle()
        assertEquals(listOf("verified-native"), backend.resumeIds)
        assertEquals(validId, replacement[spec.id].historyId)
        replacement.close()
    }

    @Test fun explicitNewSessionAfterFailedInitialRestoreCreatesIndependentNativeContext() = fixture { scope ->
        malformed(store())
        val replacement = ExternalAgentSessionController(scope, history = store())
        val backend = Backend()
        replacement.prepare(backend, spec); replacement.idle()
        assertNotNull(replacement[spec.id].persistenceError)
        replacement.prepare(backend, spec, force = true); replacement.idle()
        assertEquals(0, backend.prepareCalls)
        replacement.newSession(backend, spec); replacement.idle()
        assertEquals("new-native-1", replacement[spec.id].sessionId)
        assertTrue(replacement.submit(backend, spec, "Explicit fresh context", WorkspaceAgentMode.EDIT)); replacement.idle()
        assertEquals(listOf("new-native-1"), backend.resumeIds)
        replacement.close()
    }
}
