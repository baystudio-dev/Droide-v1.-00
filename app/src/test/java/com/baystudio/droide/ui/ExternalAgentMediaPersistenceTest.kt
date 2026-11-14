package com.baystudio.droide.ui

import android.database.sqlite.SQLiteDatabase
import com.baystudio.droide.core.*
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
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
class ExternalAgentMediaPersistenceTest {
    @get:org.junit.Rule
    val applicationAdmission: org.junit.rules.TestRule = com.baystudio.droide.core.ApplicationWorkAdmissionFixture()

    private val root = Files.createTempDirectory("external-media-history-").toFile()
    private val stores = mutableListOf<PluginConversationHistory>()
    private val media = ChatMediaStore(root)
    private val spec = WorkspaceAgentPluginCatalog.entries.first()
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aYd0AAAAASUVORK5CYII=")
    private fun history() = SqlitePluginConversationHistory(root).also { stores += it }
    private suspend fun attachment(owner: String) = ByteArrayInputStream(png).use { media.import(owner, "image.png", "image/png", it) }

    @After fun cleanup() = runBlocking {
        stores.forEach { it.close() }
        assertTrue(PathSecurity.deleteTreeNoFollow(ChatMediaStoragePaths.directory(root)))
        assertTrue(PathSecurity.deleteTreeNoFollow(root))
    }
    private fun fixture(block: suspend (CoroutineScope) -> Unit) = runBlocking {
        withTimeout(12_000) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
            try { block(scope) } finally { scope.coroutineContext.job.cancelAndJoin() }
        }
    }
    private suspend fun ExternalAgentSessionController.idle() { withTimeout(5_000) { while (busy) delay(1) } }

    private inner class Backend : WorkspaceAgentChatBackend {
        var allowsMedia = true
        var calls = 0
        var outgoing = false
        var gate: CompletableDeferred<Unit>? = null
        val started = CompletableDeferred<Unit>()
        val received = mutableListOf<List<ChatMediaRef>>()
        val sessions = mutableListOf<String?>()
        val owners = mutableListOf<String?>()
        val discarded = mutableListOf<String>()
        var nativeId = "same-native-session"
        override fun mediaAdmissionError(agentId: String, media: List<ChatMediaRef>): String? =
            if (media.isNotEmpty() && !allowsMedia) "This CLI cannot see images; draft kept" else null
        override suspend fun runMediaTurn(agentId: String, prompt: String, media: List<ChatMediaRef>, mediaOwnerId: String?, mode: WorkspaceAgentMode,
                                          resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit): WorkspaceAgentTurnResult {
            calls++; received += media; sessions += resumeSessionId; owners += mediaOwnerId
            if (media.isNotEmpty()) assertEquals(media.map { it.id }, this@ExternalAgentMediaPersistenceTest.media.ownerRefs(checkNotNull(mediaOwnerId)).map { it.id })
            onEvent(WorkspaceAgentEvent.SessionBound(nativeId))
            onEvent(WorkspaceAgentEvent.Configuration(nativeId, WorkspaceAgentSessionConfiguration()))
            if (outgoing) onEvent(WorkspaceAgentEvent.Media(attachment(checkNotNull(mediaOwnerId))))
            onEvent(WorkspaceAgentEvent.Text("Answer")); started.complete(Unit)
            gate?.await()
            return WorkspaceAgentTurnResult(agentId, nativeId, "Answer", "end_turn", emptyList(), emptyList())
        }
        override suspend fun runTurn(agentId: String, prompt: String, mode: WorkspaceAgentMode, resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit) =
            error("Media controller must use the additive media turn contract")
        override suspend fun prepareSession(agentId: String, resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit) =
            WorkspaceAgentEvent.Configuration(resumeSessionId ?: nativeId, WorkspaceAgentSessionConfiguration())
        override suspend fun newSession(agentId: String, onEvent: (WorkspaceAgentEvent) -> Unit) = error("Media must not create another native session")
        override suspend fun selectModel(agentId: String, expectedSessionId: String, modelId: String) = error("Media must not change the model")
        override suspend fun cancel(agentId: String, expectedSessionId: String?) = Unit
        override suspend fun discardSession(agentId: String, expectedSessionId: String) {
            if (nativeId == expectedSessionId) { discarded += expectedSessionId; nativeId = "replacement-native-session" }
        }
    }

    @Test fun unsupportedConnectionKeepsDraftOwnerAndDoesNotCommitOrStartATurn() = fixture { scope ->
        val store = history()
        val ref = attachment("draft-one")
        val host = Backend().apply { allowsMedia = false }
        val controller = ExternalAgentSessionController(scope, history = store, workDir = root)
        assertFalse(controller.submit(host, spec, "Describe", WorkspaceAgentMode.EDIT, media = listOf(ref), mediaOwnerId = "draft-one"))
        assertEquals(0, host.calls); assertTrue(store.listPage().entries.isEmpty())
        assertEquals(listOf(ref), media.ownerRefs("draft-one")); assertNotNull(media.file(ref))
        assertTrue(controller[spec.id].connectionError!!.contains("draft kept"))
        controller.close()
    }

    @Test fun incomingAndOutgoingRefsSurviveRestartAndDeleteReleasesOnlyCommittedConversationOwnership() = fixture { scope ->
        val source = File(root, "original.png").apply { writeBytes(png) }
        val ref = attachment("draft-one")
        val store = history(); val host = Backend().apply { outgoing = true }
        val controller = ExternalAgentSessionController(scope, history = store, workDir = root)
        assertTrue(controller.submit(host, spec, "Describe", WorkspaceAgentMode.EDIT, media = listOf(ref), mediaOwnerId = "draft-one"))
        controller.idle()
        val id = controller[spec.id].historyId!!
        val saved = store.load(id)!!
        assertEquals(listOf(ref), saved.messages.first().media)
        assertEquals(1, saved.messages.last().media.size)
        assertTrue(media.ownerRefs("draft-one").isEmpty())
        assertEquals(id, host.owners.single()); assertEquals(2, media.ownerRefs(id).size)
        controller.close()
        val restored = history().load(id)!!
        assertEquals(saved.messages.map { it.media }, restored.messages.map { it.media })
        stores.last().delete(id)
        assertTrue(media.ownerRefs(id).isEmpty())
        assertNull(media.file(ref)); assertArrayEquals(png, source.readBytes())
    }

    @Test fun queuedMediaIsSentIntoTheSameConversationAndNativeSessionAfterCurrentTurn() = fixture { scope ->
        val store = history(); val gate = CompletableDeferred<Unit>()
        val host = Backend().apply { this.gate = gate }
        val controller = ExternalAgentSessionController(scope, history = store, workDir = root)
        controller.submit(host, spec, "First task", WorkspaceAgentMode.EDIT)
        host.started.await()
        withTimeout(5_000) { while (controller[spec.id].sessionId == null || controller[spec.id].configuration == null) delay(1) }
        val id = controller[spec.id].historyId!!
        val ref = attachment("draft-queued")
        assertTrue(controller.submit(host, spec, "Then inspect this", WorkspaceAgentMode.EDIT, AgentPromptDelivery.QUEUE, listOf(ref), "draft-queued"))
        assertEquals(listOf(ref), controller.pendingPrompts.single().media)
        assertEquals(1, host.calls); assertNotNull(media.file(ref))
        gate.complete(Unit); controller.idle()
        assertEquals(listOf(null, "same-native-session"), host.sessions)
        assertEquals(listOf(emptyList(), listOf(ref)), host.received)
        assertEquals(listOf(id, id), host.owners)
        assertEquals(listOf(ref), store.load(id)!!.messages[2].media)
        assertTrue(media.ownerRefs("draft-queued").isEmpty()); assertEquals(1, store.listPage().entries.size)
        controller.close()
    }

    @Test fun deletingActiveHistoryCannotDeleteItsMediaAndStopPersistsOutgoingMedia() = fixture { scope ->
        val store = history(); val host = Backend().apply { outgoing = true; gate = CompletableDeferred() }
        val controller = ExternalAgentSessionController(scope, history = store, workDir = root)
        controller.submit(host, spec, "Work", WorkspaceAgentMode.EDIT)
        host.started.await()
        withTimeout(5_000) { while (controller[spec.id].liveMedia.isEmpty()) delay(1) }
        val id = controller[spec.id].historyId!!; val ref = controller[spec.id].liveMedia.single()
        assertTrue(runCatching { store.delete(id) }.isFailure)
        assertNotNull(media.file(ref)); assertEquals(listOf(ref), media.ownerRefs(id))
        controller.cancel(host); controller.idle()
        val saved = store.load(id)!!
        assertEquals(PluginHistoryStatus.STOPPED, saved.summary.status)
        assertEquals(listOf(ref), saved.messages.first { it.role == "assistant" }.media)
        assertEquals("same-native-session", saved.summary.nativeSessionId)
        controller.close()
    }

    @Test fun removingQueuedMediaReleasesOnlyTheUnsentDraft() = fixture { scope ->
        val store = history(); val host = Backend().apply { gate = CompletableDeferred() }
        val controller = ExternalAgentSessionController(scope, history = store, workDir = root)
        controller.submit(host, spec, "Work", WorkspaceAgentMode.EDIT); host.started.await()
        val ref = attachment("draft-remove")
        assertTrue(controller.submit(host, spec, "Unsent", WorkspaceAgentMode.EDIT, AgentPromptDelivery.QUEUE, listOf(ref), "draft-remove"))
        controller.removePendingInput(controller.pendingPrompts.single().input.id)
        withTimeout(5_000) { while (media.file(ref) != null) delay(1) }
        assertTrue(controller.pendingPrompts.isEmpty()); assertEquals(1, host.calls)
        assertNotNull(store.listPage().entries.single())
        controller.cancel(host); controller.idle(); controller.close()
    }

    @Test fun schemaOneUpgradesWithoutErasingExistingTranscript() = fixture { _ ->
        val id = PluginConversationHistory.newId()
        val file = File(root, ".droide/sessions/plugins.sqlite").apply { parentFile.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            // Build the historical v1 schema directly. Relabeling a current database
            // would leave newer columns behind and would not test an actual upgrade.
            db.execSQL("""CREATE TABLE conversations (
                id TEXT PRIMARY KEY, agent_id TEXT NOT NULL, title TEXT NOT NULL,
                created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, native_id TEXT,
                mode TEXT NOT NULL, model_id TEXT, message_count INTEGER NOT NULL DEFAULT 0,
                part_count INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL, owner_id TEXT NOT NULL)""")
            db.execSQL("CREATE INDEX conversation_time ON conversations(updated_at DESC,id DESC)")
            db.execSQL("CREATE INDEX conversation_agent_time ON conversations(agent_id,updated_at DESC,id DESC)")
            db.execSQL("""CREATE TABLE message_parts (
                conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                ordinal INTEGER NOT NULL, logical_ordinal INTEGER NOT NULL,
                role TEXT NOT NULL, content TEXT NOT NULL,
                PRIMARY KEY(conversation_id,ordinal))""")
            db.execSQL("""CREATE TABLE live_parts (
                conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                ordinal INTEGER NOT NULL, content TEXT NOT NULL,
                PRIMARY KEY(conversation_id,ordinal))""")
            db.execSQL("""CREATE TABLE active_conversations (
                agent_id TEXT PRIMARY KEY,
                conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE)""")
            db.execSQL(
                """INSERT INTO conversations VALUES (
                    ?,?,'Earlier',1,1,'same-native-session','EDIT',NULL,2,2,'COMPLETED','legacy-owner')""",
                arrayOf(id, spec.id),
            )
            db.execSQL("INSERT INTO message_parts VALUES(?,0,0,'user','Earlier')", arrayOf(id))
            db.execSQL("INSERT INTO message_parts VALUES(?,1,1,'assistant','Earlier answer')", arrayOf(id))
            db.execSQL("INSERT INTO active_conversations VALUES(?,?)", arrayOf(spec.id, id))
            db.version = 1
        }
        val reopened = history()
        val saved = reopened.load(id)!!
        assertEquals(listOf("Earlier", "Earlier answer"), saved.messages.map { it.content })
        assertTrue(saved.messages.all { it.media.isEmpty() })
        val ref = attachment("draft-new")
        val next = reopened.beginMediaTurn(saved.summary.id, spec.id, "New image", WorkspaceAgentMode.EDIT, "same-native-session", listOf(ref))
        assertEquals(listOf(ref), next.messages.last().media)
        reopened.finish(next.summary.id, "New answer", emptyList(), PluginHistoryStatus.COMPLETED)
        assertEquals(4, reopened.load(next.summary.id)!!.messages.size)
    }

    @Test fun deletingSelectedHistoryClearsRevokedOwnerAndDetachesOldNativeSessionBeforeAnotherSend() = fixture { scope ->
        val store = history(); val host = Backend(); val controller = ExternalAgentSessionController(scope, history = store, workDir = root)
        val ref = attachment("draft-first")
        controller.submit(host, spec, "First image", WorkspaceAgentMode.EDIT, media = listOf(ref), mediaOwnerId = "draft-first")
        controller.idle(); val oldId = controller[spec.id].historyId!!
        store.delete(oldId)
        assertTrue(controller.resetDeletedConversation(oldId, spec.id))
        assertNull(controller[spec.id].historyId); assertNull(controller[spec.id].sessionId)
        assertTrue(controller[spec.id].messages.isEmpty()); assertNull(media.file(ref))
        val next = attachment("draft-after-delete")
        assertTrue(controller.submit(host, spec, "Another image", WorkspaceAgentMode.EDIT, media = listOf(next), mediaOwnerId = "draft-after-delete"))
        controller.idle()
        assertEquals(listOf("same-native-session"), host.discarded)
        assertEquals(listOf(null, null), host.sessions)
        assertEquals("replacement-native-session", controller[spec.id].sessionId)
        assertNotEquals(oldId, controller[spec.id].historyId)
        assertEquals(listOf(next), store.load(controller[spec.id].historyId!!)!!.messages.first().media)
        controller.close()
    }

    @Test fun committedDeletionResetsSelectedConversationEvenIfMediaCleanupIsDeferred() = fixture { scope ->
        val store = history(); val host = Backend(); val controller = ExternalAgentSessionController(scope, history = store, workDir = root)
        val ref = attachment("draft-delete-corrupt")
        controller.submit(host, spec, "Image", WorkspaceAgentMode.EDIT, media = listOf(ref), mediaOwnerId = "draft-delete-corrupt")
        controller.idle()
        val id = controller[spec.id].historyId!!
        val index = File(media.storageDirectory, "index.json")
        val original = index.readBytes()
        index.writeText("broken media index")
        store.delete(id)
        assertNull(store.load(id))
        assertTrue(controller.resetDeletedConversation(id, spec.id))
        assertNull(controller[spec.id].sessionId)
        index.writeBytes(original)
        media.cleanupExpired()
        assertNull(media.file(ref))
        assertTrue(controller.submit(host, spec, "Continue", WorkspaceAgentMode.EDIT))
        controller.idle()
        assertEquals(listOf("same-native-session"), host.discarded)
        assertEquals("replacement-native-session", controller[spec.id].sessionId)
        controller.close()
    }

    @Test fun deletingUnselectedHistoryNeverClearsOrDetachesTheSelectedConversation() = fixture { scope ->
        val store = history(); val host = Backend(); val controller = ExternalAgentSessionController(scope, history = store, workDir = root)
        controller.submit(host, spec, "Current conversation", WorkspaceAgentMode.EDIT); controller.idle()
        val currentId = controller[spec.id].historyId!!
        val other = store.beginTurn(null, spec.id, "Another saved conversation", WorkspaceAgentMode.EDIT, "other-native-session")
        store.finish(other.summary.id, "Other answer", emptyList(), PluginHistoryStatus.COMPLETED)
        store.delete(other.summary.id)
        assertFalse(controller.resetDeletedConversation(other.summary.id, spec.id, host))
        assertEquals(currentId, controller[spec.id].historyId)
        assertEquals("same-native-session", controller[spec.id].sessionId)
        assertTrue(host.discarded.isEmpty()); assertEquals(1, host.calls)
        controller.close()
    }
}
