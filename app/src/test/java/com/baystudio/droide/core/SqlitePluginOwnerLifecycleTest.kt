package com.baystudio.droide.core

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SqlitePluginOwnerLifecycleTest {
    private val roots = mutableListOf<File>()
    private val stores = mutableListOf<SqlitePluginConversationHistory>()
    private fun root() = Files.createTempDirectory("plugin-owner-lifecycle-").toFile().also { roots += it }
    private fun store(root: File) = SqlitePluginConversationHistory(root).also { stores += it }
    private fun database(root: File) = SQLiteDatabase.openDatabase(File(root, ".droide/sessions/plugins.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE)
    private suspend fun begin(store: PluginConversationHistory, id: String? = null) = store.beginTurn(id, "opencode", "Question", WorkspaceAgentMode.EDIT, "native-original").summary.id
    private fun count(root: File, table: String) = database(root).use { db -> db.rawQuery("SELECT count(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) } }
    private fun status(root: File, id: String) = database(root).use { db -> db.rawQuery("SELECT status FROM conversations WHERE id=?", arrayOf(id)).use { it.moveToFirst(); it.getString(0) } }
    @After fun cleanup() = runBlocking {
        stores.asReversed().forEach { it.lifecycleCheckpoint = {}; it.close() }
        roots.asReversed().forEach { assertTrue(PathSecurity.deleteTreeNoFollow(it)) }
    }

    @Test fun gracefulCloseRetiresOnlyOwnRowsAndReopenPromotesExactlyOnce() = runBlocking {
        val root = root(); val owner = store(root); val reader = store(root)
        reader.listPage(); owner.listPage()
        val id = begin(owner); owner.appendPartial(id, listOf("first ", "\uD83D")); owner.appendPartial(id, listOf("\uDE80 second"))
        val foreignId = begin(reader); reader.appendPartial(foreignId, listOf("other owner"))
        val live = reader.load(id)!!
        assertNotNull(live.readOnlyReason); assertEquals("first 🚀 second", live.messages.last().content)
        assertEquals(PluginHistoryStatus.RUNNING, live.summary.status)
        owner.close()
        assertEquals("INTERRUPTED", status(root, id)); assertEquals("RUNNING", status(root, foreignId))
        assertEquals(3, count(root, "live_parts")) // Close never reads or removes checkpoint evidence.
        val recovered = reader.load(id)!!
        assertNull(recovered.readOnlyReason); assertEquals(PluginHistoryStatus.INTERRUPTED, recovered.summary.status)
        assertEquals("first 🚀 second", recovered.messages.last().content)
        assertEquals(1, count(root, "live_parts")); assertEquals(recovered.messages, reader.load(id)!!.messages)
        begin(reader, id); reader.finish(id, "continued", emptyList(), PluginHistoryStatus.COMPLETED)
        database(root).use { assertEquals(6, it.version) }
        assertTrue(File(root, ".droide/sessions").list()!!.none { it.startsWith(".plugin-owner-") })
    }

    @Test fun directBeginAfterClosePromotesRetainedPrefixBeforeNewPrompt() = runBlocking {
        val root = root(); val first = store(root); val id = begin(first)
        first.appendPartial(id, listOf("old checkpoint")); first.close()
        val second = store(root); begin(second, id)
        val final = second.finish(id, "new reply", emptyList(), PluginHistoryStatus.COMPLETED)
        assertEquals(listOf("Question", "old checkpoint", "Question", "new reply"), final.messages.map { it.content })
        assertEquals(0, count(root, "live_parts"))
    }

    @Test fun completedAndStoppedForeignConfigurationBindingsStillWork() = runBlocking {
        val root = root(); val first = store(root); val second = store(root)
        for (status in listOf(PluginHistoryStatus.COMPLETED, PluginHistoryStatus.STOPPED)) {
            val id = begin(first); first.finish(id, "done", emptyList(), status)
            second.bind(id, "new-native", "new-model")
            val loaded = second.load(id)!!
            assertEquals("new-native", loaded.summary.nativeSessionId); assertEquals("new-model", loaded.summary.modelId)
            begin(first, id)
            assertTrue(runCatching { second.bind(id, "wrong", "wrong") }.isFailure)
            assertEquals("new-native", first.listPage().entries.first { it.id == id }.nativeSessionId)
            first.finish(id, "done again", emptyList(), status)
        }
    }

    @Test fun failedRetirementRollsBackAllOwnedRowsAndClosesResourcesWithoutClaimingRecovery() = runBlocking {
        val root = root(); val first = store(root); val ids = listOf(begin(first), begin(first))
        ids.forEach { first.appendPartial(it, listOf("checkpoint $it")) }
        first.lifecycleCheckpoint = { if (it == "before-retirement-commit") error("injected retirement failure") }
        assertTrue(runCatching { first.close() }.isFailure)
        assertTrue(runCatching { first.appendPartial(ids.first(), listOf("late")) }.isFailure)
        ids.forEach { assertEquals("RUNNING", status(root, it)) }
        assertEquals(2, count(root, "live_parts")); assertEquals(2, count(root, "message_parts"))
        ExportWriterAdmission.beginCapture(root).close()
        val second = store(root); ids.forEach { assertNotNull(second.load(it)!!.readOnlyReason) }
        first.lifecycleCheckpoint = {}; first.close()
        ids.forEach { assertNotNull(second.load(it)!!.readOnlyReason) }
        val independent = begin(second); second.finish(independent, "new work", emptyList(), PluginHistoryStatus.COMPLETED)
        assertEquals(2, count(root, "live_parts"))
    }

    @Test fun malformedCheckpointCanRetireWithoutLossAndPromotionRollsBackUntilRepair() = runBlocking {
        val root = root(); val first = store(root); val id = begin(first)
        first.appendPartial(id, listOf("good")); first.appendPartial(id, listOf("bad"))
        database(root).use { it.update("live_parts", ContentValues().apply { put("content_utf16", byteArrayOf(1)) }, "ordinal=1", null) }
        first.close()
        assertEquals("INTERRUPTED", status(root, id)); assertEquals(2, count(root, "live_parts"))
        val second = store(root)
        assertTrue(runCatching { second.load(id) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { begin(second, id) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals("INTERRUPTED", status(root, id)); assertEquals(2, count(root, "live_parts")); assertEquals(1, count(root, "message_parts"))
        database(root).use { db ->
            db.rawQuery("SELECT content_utf16 FROM live_parts WHERE ordinal=1", null).use { it.moveToFirst(); assertArrayEquals(byteArrayOf(1), it.getBlob(0)) }
            db.execSQL("UPDATE live_parts SET content='repaired fixture',content_utf16=NULL WHERE ordinal=1")
        }
        assertEquals("goodrepaired fixture", second.load(id)!!.messages.last().content)
    }

    @Test fun cleanupFailuresRetainRegistrationButDurableRetirementPermitsReopen() = runBlocking {
        for (point in listOf("before-database-close", "after-database-close", "before-registration-close")) {
            val root = root(); val first = store(root); val id = begin(first); first.appendPartial(id, listOf("retained close"))
            first.lifecycleCheckpoint = { if (it == point) error("injected close failure") }
            assertTrue(runCatching { first.close() }.isFailure)
            assertEquals("INTERRUPTED", status(root, id))
            if (point == "after-database-close") ExportWriterAdmission.beginCapture(root).close()
            else assertThrows(IllegalStateException::class.java) { ExportWriterAdmission.beginCapture(root) }
            val second = store(root); assertNull(second.load(id)!!.readOnlyReason)
            begin(second, id); second.appendPartial(id, listOf("new owner"))
            first.lifecycleCheckpoint = {}; first.close() // Retry cannot retire this new owner's turn.
            assertEquals("RUNNING", status(root, id))
            assertEquals("new owner", second.finish(id, null, emptyList(), PluginHistoryStatus.COMPLETED).messages.last().content)
            second.close(); ExportWriterAdmission.beginCapture(root).close()
        }
    }

    @Test fun cancellationAtTurnCommitDoesNotAuthorizeForeignTakeover() = runBlocking {
        val root = root(); val first = store(root)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        first.lifecycleCheckpoint = { if (it == "before-turn-commit") { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) } }
        val pending = async(Dispatchers.Default) { begin(first) }
        assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
        pending.cancel(); release.countDown(); runCatching { pending.await() }
        first.lifecycleCheckpoint = {}
        val id = first.listPage().entries.single().id
        val second = store(root); assertNotNull(second.load(id)!!.readOnlyReason)
        first.appendPartial(id, listOf("still owns")); first.close()
        assertEquals("still owns", second.load(id)!!.messages.last().content)
    }

    @Test fun cancelledCallerStillCompletesPhysicalCloseAndAllowsRecovery() = runBlocking {
        val root = root(); val first = store(root); val id = begin(first); first.appendPartial(id, listOf("close on cancellation"))
        val caller = launch(start = CoroutineStart.UNDISPATCHED) { currentCoroutineContext().cancel(); first.close() }
        caller.join()
        assertNull(store(root).load(id)!!.readOnlyReason)
    }

    @Test fun unknownOwnerPreviewDoesNotChangeActiveSelectionOrNativeIdentity() = runBlocking {
        val root = root(); val first = store(root); val unknown = begin(first); first.appendPartial(unknown, listOf("legacy checkpoint"))
        val selected = begin(first); first.finish(selected, "selected", emptyList(), PluginHistoryStatus.COMPLETED)
        database(root).use { it.execSQL("UPDATE conversations SET owner_id=? WHERE id=?", arrayOf(UUID.randomUUID().toString(), unknown)) }
        first.close()
        val reader = store(root)
        assertNotNull(reader.load(unknown)!!.readOnlyReason)
        assertEquals(selected, reader.latest("opencode")!!.summary.id)
        assertEquals("native-original", reader.listPage().entries.first { it.id == unknown }.nativeSessionId)
        assertTrue(runCatching { reader.bind(unknown, "wrong") }.isFailure)
        assertTrue(runCatching { reader.delete(unknown) }.isFailure)
        assertTrue(runCatching { begin(reader, unknown) }.isFailure)
        assertEquals(1, count(root, "live_parts")); assertEquals("RUNNING", status(root, unknown))
    }

    @Test fun legacyNonRunningCheckpointPromotionKeepsItsFinalStatus() = runBlocking {
        for (status in listOf(PluginHistoryStatus.STOPPED, PluginHistoryStatus.COMPLETED, PluginHistoryStatus.FAILED)) {
            val root = root(); val first = store(root); val id = begin(first); first.appendPartial(id, listOf("retained legacy"))
            database(root).use { it.execSQL("UPDATE conversations SET owner_id='legacy',status=? WHERE id=?", arrayOf(status.name, id)) }
            first.close(); val second = store(root)
            val restored = second.load(id)!!
            assertEquals(status, restored.summary.status); assertEquals("retained legacy", restored.messages.last().content)
            assertEquals(0, count(root, "live_parts"))
        }
    }
    @Test fun repeatedSameCleanupFailureDoesNotReplaceTheOriginalOrPreventRetry() = runBlocking {
        val root = root(); val first = store(root); val id = begin(first)
        val failure = IllegalStateException("same injected failure")
        first.lifecycleCheckpoint = { if (it == "before-retirement" || it == "before-database-close") throw failure }
        assertSame(failure, runCatching { first.close() }.exceptionOrNull())
        assertEquals("RUNNING", status(root, id))
        assertThrows(IllegalStateException::class.java) { ExportWriterAdmission.beginCapture(root) }
        first.lifecycleCheckpoint = {}; first.close()
        assertNull(store(root).load(id)!!.readOnlyReason)
    }

    @Test fun retirementVersionMarkerPublishesBeforeOperationsAndMigrationFailureRollsBack() = runBlocking {
        val root = root(); val id = PluginConversationHistory.newId()
        val file = File(root, ".droide/sessions/plugins.sqlite").apply { parentFile.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            // Genuine version 4 schema, not a current version 6 database relabeled with an older number
            // while retaining new receipt columns.
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
                role TEXT NOT NULL, content TEXT NOT NULL, activity TEXT, content_utf16 BLOB,
                PRIMARY KEY(conversation_id,ordinal))""")
            db.execSQL("""CREATE TABLE live_parts (
                conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                ordinal INTEGER NOT NULL, content TEXT NOT NULL, activity_id TEXT, activity TEXT, content_utf16 BLOB,
                PRIMARY KEY(conversation_id,ordinal))""")
            db.execSQL("CREATE INDEX live_activity_id ON live_parts(conversation_id,activity_id)")
            db.execSQL("""CREATE TABLE active_conversations (
                agent_id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE)""")
            db.execSQL("""CREATE TABLE message_media (
                conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                logical_ordinal INTEGER NOT NULL, refs TEXT NOT NULL, PRIMARY KEY(conversation_id,logical_ordinal))""")
            db.execSQL("INSERT INTO conversations VALUES(?, 'opencode','Question',1,1,NULL,'EDIT',NULL,2,2,'COMPLETED','legacy')", arrayOf(id))
            db.execSQL("INSERT INTO message_parts VALUES(?,0,0,'user','Question',NULL,NULL)", arrayOf(id))
            db.execSQL("INSERT INTO message_parts VALUES(?,1,1,'assistant','legacy-compatible completed reply',NULL,NULL)", arrayOf(id))
            db.version = 4
        }
        val reopened = store(root)
        reopened.lifecycleCheckpoint = { if (it == "before-retirement-version-commit") error("injected semantic marker commit failure") }
        assertTrue(runCatching { reopened.load(id) }.isFailure)
        database(root).use { assertEquals(4, it.version) }
        assertEquals("COMPLETED", status(root, id)); assertEquals(2, count(root, "message_parts")); assertEquals(0, count(root, "live_parts"))
        reopened.lifecycleCheckpoint = {}
        assertEquals("legacy-compatible completed reply", reopened.load(id)!!.messages.last().content)
        database(root).use { assertEquals(6, it.version) }
    }

}
