package com.baystudio.droide.ui

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.baystudio.droide.core.*
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Real controller/store integration; every mutation is confined to newly allocated test fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ExternalAgentReadOnlyHistoryTest {
    @get:org.junit.Rule
    val applicationAdmission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    private val fixtureRoot = Files.createTempDirectory("external-read-only-history-").toFile()
    private val root = fixtureRoot.resolve("files/projects/example").apply { mkdirs() }
    private val repositories = mutableListOf<PluginConversationHistory>()
    private val spec = WorkspaceAgentPluginCatalog.entries.first()
    private val databaseFile get() = root.resolve(".droide/sessions/plugins.sqlite")
    private fun repository() = SqlitePluginConversationHistory(root).also { repositories += it }

    @After fun cleanup() = runBlocking {
        repositories.forEach { it.close() }
        assertTrue(PathSecurity.deleteTreeNoFollow(fixtureRoot))
    }

    /** Null resume deliberately reuses the prior live native session, like the production host. */
    private class Backend : WorkspaceAgentChatBackend {
        var nativeId = "legacy-native"
        var admissionChecks = 0
        var newSessions = 0
        var prepares = 0
        var modelChanges = 0
        var reasoningChanges = 0
        val resumeIds = mutableListOf<String?>()
        val started = CompletableDeferred<Unit>()
        var hold: CompletableDeferred<Unit>? = null
        var stream: List<String> = emptyList()
        val config = WorkspaceAgentSessionConfiguration(
            models = listOf(WorkspaceAgentModelOption("model-a", "Model A"), WorkspaceAgentModelOption("model-b", "Model B")),
            currentModelId = "model-a",
            reasoningOptions = listOf(WorkspaceAgentModelOption("low", "Low"), WorkspaceAgentModelOption("high", "High")),
            currentReasoningId = "low",
        )
        override fun mediaAdmissionError(agentId: String, media: List<ChatMediaRef>): String? { admissionChecks++; return null }
        override suspend fun runTurn(agentId: String, prompt: String, mode: WorkspaceAgentMode, resumeSessionId: String?,
                                     onEvent: (WorkspaceAgentEvent) -> Unit): WorkspaceAgentTurnResult {
            resumeIds += resumeSessionId
            nativeId = resumeSessionId ?: nativeId
            onEvent(WorkspaceAgentEvent.SessionBound(nativeId))
            onEvent(WorkspaceAgentEvent.Configuration(nativeId, config))
            stream.forEach { onEvent(WorkspaceAgentEvent.Text(it)) }
            started.complete(Unit)
            hold?.await()
            return WorkspaceAgentTurnResult(agentId, nativeId, "Reply to $prompt", "end_turn", emptyList(), emptyList())
        }
        override suspend fun prepareSession(agentId: String, resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit): WorkspaceAgentEvent.Configuration {
            prepares++
            nativeId = resumeSessionId ?: nativeId
            return WorkspaceAgentEvent.Configuration(nativeId, config)
        }
        override suspend fun newSession(agentId: String, onEvent: (WorkspaceAgentEvent) -> Unit): String {
            newSessions++
            nativeId = "independent-native-$newSessions"
            return nativeId
        }
        override suspend fun selectModel(agentId: String, expectedSessionId: String, modelId: String): WorkspaceAgentEvent.Configuration {
            modelChanges++
            return WorkspaceAgentEvent.Configuration(expectedSessionId, config.copy(currentModelId = modelId))
        }
        override suspend fun selectReasoning(agentId: String, expectedSessionId: String, value: String): WorkspaceAgentEvent.Configuration {
            reasoningChanges++
            return WorkspaceAgentEvent.Configuration(expectedSessionId, config.copy(currentReasoningId = value))
        }
        override suspend fun cancel(agentId: String, expectedSessionId: String?) = Unit
    }

    private fun fixture(block: suspend (CoroutineScope) -> Unit) = runBlocking {
        withTimeout(30_000) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
            try { block(scope) } finally { scope.coroutineContext.job.cancelAndJoin() }
        }
    }
    private suspend fun ExternalAgentSessionController.idle() {
        withTimeout(10_000) { while (busy) delay(1) }
    }

    private fun makeLegacyUnknown(id: String) {
        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE conversations SET owner_id=? WHERE id=?", arrayOf(UUID.randomUUID().toString(), id))
        }
    }

    /** Compare every stored field, including exact BLOB bytes, rather than only rendered text. */
    private fun evidence(id: String): Map<String, List<List<String>>> =
        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            mapOf("conversations" to "id", "message_parts" to "conversation_id", "live_parts" to "conversation_id", "message_media" to "conversation_id")
                .mapValues { (table, key) ->
                    db.rawQuery("SELECT * FROM $table WHERE $key=? ORDER BY rowid", arrayOf(id)).use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) add((0 until cursor.columnCount).map { column ->
                                when (cursor.getType(column)) {
                                    Cursor.FIELD_TYPE_NULL -> "null"
                                    Cursor.FIELD_TYPE_BLOB -> "blob:" + Base64.getEncoder().encodeToString(cursor.getBlob(column))
                                    else -> "value:" + cursor.getString(column)
                                }
                            })
                        }
                    }
                }
        }

    @Test fun readOnlyWindowAdmissionNeverTreatsRetainedNativeEvidenceAsResumeAuthority() = fixture { scope ->
        val controller = ExternalAgentSessionController(scope)
        val backend = Backend()
        val summary = PluginHistorySummary(PluginConversationHistory.newId(), spec.id, "Saved legacy title", 1L, 2L,
            "legacy-native", WorkspaceAgentMode.EDIT, "model-a", 1, 1L, PluginHistoryStatus.RUNNING)
        val preview = PluginHistoryWindow(summary, listOf(AgentMessage("user", "Saved prompt")), listOf(0L), 0L, 1L,
            "This owner is unverified. The original was preserved. Use New Session for independent work.")
        assertTrue(controller.openSaved(preview))
        assertEquals(summary, controller[spec.id].historyWindow!!.summary)
        assertNull(controller[spec.id].sessionId)
        controller.prepare(backend, spec)
        controller.prepare(backend, spec, force = true)
        controller.selectModel(backend, spec, "model-b")
        controller.selectReasoning(backend, spec, "high")
        assertFalse(controller.submit(backend, spec, "Keep my draft", WorkspaceAgentMode.EDIT))
        assertEquals(0, backend.admissionChecks); assertEquals(0, backend.prepares)
        assertEquals(0, backend.modelChanges); assertEquals(0, backend.reasoningChanges)
        assertEquals(0, backend.newSessions); assertTrue(backend.resumeIds.isEmpty())
        assertEquals(listOf("Saved prompt"), controller[spec.id].messages.map { it.content })
        assertEquals(preview.readOnlyReason, controller[spec.id].connectionError)
        controller.newSession(backend, spec); controller.idle()
        assertEquals("independent-native-1", controller[spec.id].sessionId)
        assertNull(controller[spec.id].historyWindow)
        assertTrue(controller.submit(backend, spec, "Independent work", WorkspaceAgentMode.EDIT))
        controller.idle()
        assertEquals(listOf("independent-native-1"), backend.resumeIds)
        controller.close()
    }

    @Test fun unknownLegacyPreviewBlocksBackendUntilExplicitNewSessionAndPreservesOriginalEvidence() = fixture { scope ->
        val mediaStore = ChatMediaStore(root)
        val bytes = "Retain this original attachment".toByteArray()
        val ref = mediaStore.import("draft", "evidence.txt", "text/plain", ByteArrayInputStream(bytes))
        val seed = repository()
        val id = seed.beginMediaTurn(null, spec.id, "Legacy question", WorkspaceAgentMode.EDIT, "legacy-native", listOf(ref)).summary.id
        mediaStore.retain(id, listOf(ref)); mediaStore.releaseOwner("draft")
        seed.appendPartial(id, listOf("First \uD83D"))
        seed.appendPartial(id, listOf("\uDE80 checkpoint"))
        val activity = AgentStreamPart("tool-live", 1, AgentStreamPartKind.TOOL, AgentStreamPartState.RUNNING,
            title = "Unfinished tool", output = "Durable output", startedAtMs = 1234)
        seed.appendTranscript(id, listOf(ExternalTranscriptUpdate(activity)))
        makeLegacyUnknown(id)
        val original = evidence(id)
        seed.close()
        assertEquals(original, evidence(id))
        val mediaIndex = mediaStore.storageDirectory.resolve("index.json").readBytes()
        val store = repository()
        val controller = ExternalAgentSessionController(scope, history = store, workDir = root)
        val backend = Backend()

        controller.prepare(backend, spec); controller.idle()
        val state = controller[spec.id]
        assertEquals(id, state.historyId)
        assertEquals("legacy-native", state.historyWindow!!.summary.nativeSessionId)
        assertEquals(PluginHistoryStatus.RUNNING, state.historyWindow.summary.status)
        assertNotNull(state.historyWindow.readOnlyReason)
        assertNull(state.sessionId); assertNull(state.configuration); assertNull(state.usage)
        assertEquals(listOf(ref), state.messages.first().media)
        assertTrue(state.messages.any { it.content == "First 🚀 checkpoint" })
        assertEquals(activity, state.messages.single { it.streamPart != null }.streamPart)
        controller.prepare(backend, spec, force = true)
        controller.selectModel(backend, spec, "model-b")
        controller.selectReasoning(backend, spec, "high")
        assertFalse(controller.submit(backend, spec, "Do not resume", WorkspaceAgentMode.EDIT))
        controller.idle()
        assertEquals(0, backend.admissionChecks); assertEquals(0, backend.prepares)
        assertEquals(0, backend.modelChanges); assertEquals(0, backend.reasoningChanges)
        assertEquals(0, backend.newSessions); assertTrue(backend.resumeIds.isEmpty())
        assertEquals(original, evidence(id))

        controller.newSession(backend, spec); controller.idle()
        assertEquals(1, backend.newSessions); assertEquals(1, backend.prepares)
        assertEquals("independent-native-1", controller[spec.id].sessionId)
        assertNull(controller[spec.id].historyId); assertNull(controller[spec.id].historyWindow)
        assertNull(controller[spec.id].connectionError)
        assertTrue(controller.submit(backend, spec, "Independent question", WorkspaceAgentMode.EDIT))
        controller.idle()
        val newId = controller[spec.id].historyId!!
        assertNotEquals(id, newId)
        assertEquals(listOf("independent-native-1"), backend.resumeIds)
        assertEquals("independent-native-1", store.load(newId)!!.summary.nativeSessionId)
        assertEquals(2, store.listPage(spec.id).entries.size)
        assertEquals(original, evidence(id))
        assertEquals(listOf(ref), mediaStore.ownerRefs(id))
        assertArrayEquals(mediaIndex, mediaStore.storageDirectory.resolve("index.json").readBytes())
        assertArrayEquals(bytes, mediaStore.file(ref)!!.readBytes())
        controller.close()
    }

    @Test fun foreignLivePreviewBecomesNormalAfterOwnerClosesAndExplicitReopenResumesNative() = fixture { scope ->
        val owner = repository()
        val id = owner.beginTurn(null, spec.id, "Original question", WorkspaceAgentMode.EDIT, "legacy-native").summary.id
        owner.appendPartial(id, listOf("Still working"))
        val store = repository()
        val controller = ExternalAgentSessionController(scope, history = store)
        val backend = Backend()
        controller.restoreLatest(spec); controller.idle()
        assertNotNull(controller[spec.id].historyWindow!!.readOnlyReason)
        assertNull(controller[spec.id].sessionId)
        assertFalse(controller.submit(backend, spec, "Too soon", WorkspaceAgentMode.EDIT))
        owner.appendPartial(id, listOf(" and still owned"))
        owner.close()

        controller.openSaved(id, spec.id)
        assertEquals(PluginHistoryStatus.INTERRUPTED, controller[spec.id].historyWindow!!.summary.status)
        assertTrue(controller[spec.id].messages.any { it.content == "Still working and still owned" })
        assertNull(controller[spec.id].historyWindow!!.readOnlyReason)
        assertNull(controller[spec.id].connectionError)
        assertEquals("legacy-native", controller[spec.id].sessionId)
        assertTrue(controller.submit(backend, spec, "Now continue", WorkspaceAgentMode.EDIT))
        controller.idle()
        assertEquals(id, controller[spec.id].historyId)
        assertEquals(listOf("legacy-native"), backend.resumeIds)
        assertEquals(0, backend.newSessions)
        assertEquals(1, store.listPage(spec.id).entries.size)
        assertEquals(PluginHistoryStatus.COMPLETED, store.load(id)!!.summary.status)
        controller.close()
    }

    @Test fun replacementControllerPreviewsWhilePriorControllersCancelledFinishIsStillUncommitted() = fixture { scope ->
        val owner = repository()
        val finishing = CompletableDeferred<Unit>()
        val allowFinish = CompletableDeferred<Unit>()
        val retired = CompletableDeferred<Unit>()
        val heldHistory = object : PluginConversationHistory by owner {
            override suspend fun finishMediaTurn(id: String, reply: String?, notices: List<String>, status: PluginHistoryStatus,
                                                 media: List<ChatMediaRef>): PluginHistoryWindow {
                finishing.complete(Unit)
                allowFinish.await()
                return owner.finishMediaTurn(id, reply, notices, status, media)
            }
            override suspend fun close() { owner.close(); retired.complete(Unit) }
        }
        val firstBackend = Backend().apply {
            hold = CompletableDeferred()
            stream = listOf("Original durable output")
        }
        val first = ExternalAgentSessionController(scope, history = heldHistory)
        val replacement = ExternalAgentSessionController(scope, history = repository())
        try {
            assertTrue(first.submit(firstBackend, spec, "Original controller prompt", WorkspaceAgentMode.EDIT))
            firstBackend.started.await()
            withTimeout(10_000) {
                while (first[spec.id].sessionId == null || first[spec.id].liveText != "Original durable output") delay(1)
            }
            val id = first[spec.id].historyId!!
            first.close()
            finishing.await()
            assertFalse(retired.isCompleted)
            val beforePreview = evidence(id)
            assertTrue(replacement.restoreLatest(spec)); replacement.idle()
            assertEquals(id, replacement[spec.id].historyId)
            assertNotNull(replacement[spec.id].historyWindow!!.readOnlyReason)
            assertEquals("legacy-native", replacement[spec.id].historyWindow!!.summary.nativeSessionId)
            assertNull(replacement[spec.id].sessionId)
            assertTrue(replacement[spec.id].messages.any { it.content == "Original durable output" })
            assertEquals(beforePreview, evidence(id))
            allowFinish.complete(Unit)
            retired.await()
            replacement.openSaved(id, spec.id)
            assertNull(replacement[spec.id].historyWindow!!.readOnlyReason)
            assertEquals(PluginHistoryStatus.STOPPED, replacement[spec.id].historyWindow!!.summary.status)
            assertEquals("legacy-native", replacement[spec.id].sessionId)
            val nextBackend = Backend()
            assertTrue(replacement.submit(nextBackend, spec, "After clean retirement", WorkspaceAgentMode.EDIT))
            replacement.idle()
            assertEquals(listOf("legacy-native"), nextBackend.resumeIds)
            assertEquals(id, replacement[spec.id].historyId)
            assertEquals(0, nextBackend.newSessions)
        } finally {
            allowFinish.complete(Unit)
            firstBackend.hold?.complete(Unit)
            first.close(); replacement.close()
        }
    }

    @Test fun failedRetirementDuringControllerClosePreservesUnknownHistoryWithoutLeakingExportParticipant() = fixture { scope ->
        val owner = repository()
        val failRetirement = AtomicBoolean(true)
        owner.lifecycleCheckpoint = { point ->
            if (point == "before-retirement-commit" && failRetirement.compareAndSet(true, false)) {
                throw IOException("Injected owner retirement commit failure")
            }
        }
        val beforeRetirement = CompletableDeferred<Map<String, List<List<String>>>>()
        val closeOutcome = CompletableDeferred<Throwable?>()
        val heldHistory = object : PluginConversationHistory by owner {
            override suspend fun finishMediaTurn(id: String, reply: String?, notices: List<String>, status: PluginHistoryStatus,
                                                 media: List<ChatMediaRef>): PluginHistoryWindow {
                beforeRetirement.complete(evidence(id))
                throw IOException("Injected failed finish leaves durable running checkpoints")
            }
            override suspend fun close() {
                try { owner.close(); closeOutcome.complete(null) }
                catch (failure: Throwable) { closeOutcome.complete(failure); throw failure }
            }
        }
        val firstBackend = Backend().apply {
            hold = CompletableDeferred()
            stream = listOf("Preserved checkpoint after failed retirement")
        }
        val first = ExternalAgentSessionController(scope, history = heldHistory)
        val replacementStore = repository()
        val replacementClosed = CompletableDeferred<Unit>()
        val replacementHistory = object : PluginConversationHistory by replacementStore {
            override suspend fun close() { replacementStore.close(); replacementClosed.complete(Unit) }
        }
        val replacement = ExternalAgentSessionController(scope, history = replacementHistory)
        try {
            assertTrue(first.submit(firstBackend, spec, "Original failed-retirement prompt", WorkspaceAgentMode.EDIT))
            firstBackend.started.await()
            withTimeout(10_000) {
                while (first[spec.id].sessionId == null || first[spec.id].liveText != "Preserved checkpoint after failed retirement") delay(1)
            }
            val originalId = first[spec.id].historyId!!
            first.close()
            val original = beforeRetirement.await()
            assertTrue(closeOutcome.await() is IOException)
            assertFalse(failRetirement.get())
            assertEquals(original, evidence(originalId))

            assertTrue(replacement.restoreLatest(spec)); replacement.idle()
            assertNotNull(replacement[spec.id].historyWindow!!.readOnlyReason)
            assertEquals(PluginHistoryStatus.RUNNING, replacement[spec.id].historyWindow!!.summary.status)
            assertNull(replacement[spec.id].sessionId)
            assertTrue(replacement[spec.id].messages.any { it.content == "Preserved checkpoint after failed retirement" })
            val nextBackend = Backend()
            assertFalse(replacement.submit(nextBackend, spec, "Do not reuse unknown context", WorkspaceAgentMode.EDIT))
            assertTrue(nextBackend.resumeIds.isEmpty())
            replacement.newSession(nextBackend, spec); replacement.idle()
            assertTrue(replacement.submit(nextBackend, spec, "Independent work after failure", WorkspaceAgentMode.EDIT))
            replacement.idle()
            val newId = replacement[spec.id].historyId!!
            assertNotEquals(originalId, newId)
            assertEquals(listOf("independent-native-1"), nextBackend.resumeIds)
            assertEquals(original, evidence(originalId))

            // Use the ordinary coordinated export route while the replacement store is open.
            // A stranded prior participant would reject its quiescence callback as closed.
            val documents = object : WorkspaceDocumentAuthority {
                override suspend fun snapshot(path: String): WorkspaceDocumentSnapshot? = null
                override suspend fun snapshots(): List<WorkspaceDocumentSnapshot> = emptyList()
            }
            val exports = ExportSourceAdmission("project", root, documents, {},
                requireEpoch = { "test-epoch" }, epochOpen = { it == "test-epoch" })
            val ticket = exports.ticket(ExportScope(".droide/sessions/plugins.sqlite"))
            val captured = exports.withCapture(ticket, true) { ExportCaptureStore(fixtureRoot.resolve("capture")).capture(it) }
            captured.use { capture ->
                var files = 0
                ExportManifest.each(capture.manifest, capture.manifestDigest) { entry ->
                    if (entry.type == ExportEntryType.FILE) {
                        files++
                        SQLiteDatabase.openDatabase(capture.data(entry.dataId).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                            db.rawQuery("SELECT status FROM conversations WHERE id=?", arrayOf(originalId)).use { cursor ->
                                assertTrue(cursor.moveToFirst()); assertEquals("RUNNING", cursor.getString(0))
                            }
                            db.rawQuery("SELECT COUNT(*) FROM live_parts WHERE conversation_id=?", arrayOf(originalId)).use { cursor ->
                                assertTrue(cursor.moveToFirst()); assertTrue(cursor.getInt(0) > 0)
                            }
                            db.rawQuery("SELECT native_id FROM conversations WHERE id=?", arrayOf(newId)).use { cursor ->
                                assertTrue(cursor.moveToFirst()); assertEquals("independent-native-1", cursor.getString(0))
                            }
                        }
                    }
                }
                assertEquals(1, files)
            }
            assertEquals(original, evidence(originalId))
            replacement.close(); replacementClosed.await()
            ExportWriterAdmission.beginCapture(root).close()
        } finally {
            firstBackend.hold?.complete(Unit)
            first.close(); replacement.close()
        }
    }

    @Test fun unknownPreviewPagingNeverActivatesCopiesOrChangesOriginalAndRemainsReadOnly() = fixture { scope ->
        val seed = repository()
        var id: String? = null
        repeat(45) { index ->
            id = seed.beginTurn(id, spec.id, "Question $index", WorkspaceAgentMode.EDIT, "legacy-native").summary.id
            seed.finish(id, "Answer $index", emptyList(), PluginHistoryStatus.COMPLETED)
        }
        val originalId = seed.beginTurn(id, spec.id, "Unfinished final question", WorkspaceAgentMode.EDIT, "legacy-native").summary.id
        seed.appendPartial(originalId, listOf("Durable final checkpoint"))
        makeLegacyUnknown(originalId)
        val original = evidence(originalId)
        seed.close()
        assertEquals(original, evidence(originalId))
        val store = repository()
        val controller = ExternalAgentSessionController(scope, history = store)
        val window = controller.openSaved(originalId, spec.id)
        assertNotNull(window.readOnlyReason); assertTrue(window.hasEarlier)
        assertTrue(window.messages.any { it.content == "Durable final checkpoint" })
        val firstPart = window.firstPart
        controller.pageHistory(spec.id, earlier = true); controller.idle()
        val earlier = controller[spec.id].historyWindow!!
        assertNotNull(earlier.readOnlyReason); assertEquals(originalId, earlier.summary.id)
        assertEquals(firstPart, earlier.endPart)
        assertTrue(earlier.messages.any { it.content == "Question 0" })
        assertNull(controller[spec.id].sessionId)
        controller.pageHistory(spec.id, earlier = false); controller.idle()
        assertTrue(controller[spec.id].messages.any { it.content == "Durable final checkpoint" })
        assertEquals(1, store.listPage(spec.id).entries.size)
        assertEquals(original, evidence(originalId))
        controller.close()
    }
}
