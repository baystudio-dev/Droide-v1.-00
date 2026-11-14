package com.baystudio.droide.core

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.model.MultipleFailureException
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Exercises the production store through framework/native SQLite, including recreation and faults. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SqlitePluginHistoryUtf16Test {
    private val roots = mutableListOf<File>()
    private val stores = mutableListOf<SqlitePluginConversationHistory>()

    private fun temporaryRoot(label: String): File =
        Files.createTempDirectory("plugin-utf16-$label-").toFile().also { roots += it }

    private fun history(root: File): SqlitePluginConversationHistory =
        SqlitePluginConversationHistory(root).also { stores += it }

    private fun databaseFile(root: File): File = File(root, ".droide/sessions/plugins.sqlite")

    private fun database(root: File): SQLiteDatabase =
        SQLiteDatabase.openDatabase(databaseFile(root).path, null, SQLiteDatabase.OPEN_READWRITE)

    private fun part(id: String, kind: AgentStreamPartKind): AgentStreamPart =
        AgentStreamPart(id, 0, kind, AgentStreamPartState.RUNNING)

    private suspend fun begin(store: PluginConversationHistory): String =
        store.beginTurn(null, "opencode", "Question", WorkspaceAgentMode.EDIT, null).summary.id

    private suspend fun expectInvalidPayload(action: suspend () -> Unit) {
        try {
            action()
            fail("Malformed persisted payload must be rejected")
        } catch (_: IllegalArgumentException) {
            // The production decoder rejects the payload before any transaction can commit.
        }
    }

    @After
    fun cleanup() = runBlocking {
        val failures = mutableListOf<Throwable>()
        // Attempt every close and removal even if a preceding cleanup operation fails.
        stores.asReversed().forEach { store ->
            runCatching { store.close() }.exceptionOrNull()?.let(failures::add)
        }
        roots.asReversed().forEach { root ->
            runCatching { assertTrue("Cannot remove test fixture $root", PathSecurity.deleteTreeNoFollow(root)) }
                .exceptionOrNull()?.let(failures::add)
        }
        MultipleFailureException.assertEmpty(failures)
    }

    @Test
    fun splitCheckpointRecovery() = runBlocking {
        val root = temporaryRoot("checkpoint")
        val store = history(root)
        val id = begin(store)
        store.appendPartial(id, listOf("start \uD83D"))
        store.appendPartial(id, listOf("\uDE80 end"))
        store.close()

        val reopened = history(root)
        val messages = reopened.load(id)!!.messages
        assertEquals("start 🚀 end", messages.last().content)
    }

    @Test
    fun splitTranscriptBatchFinish() = runBlocking {
        val store = history(temporaryRoot("batch"))
        val id = begin(store)
        val text = part("text", AgentStreamPartKind.TEXT)
        store.appendTranscript(id, listOf(
            ExternalTranscriptUpdate(text, "start \uD83D"),
            ExternalTranscriptUpdate(text, "\uDE80 end"),
        ))

        val done = store.finish(id, null, emptyList(), PluginHistoryStatus.STOPPED)
        assertEquals("start 🚀 end", done.messages.last().content)
    }

    @Test
    fun activityBoundaryAndFinalSuffixStayExactWithoutDuplicate() = runBlocking {
        val root = temporaryRoot("activity")
        val store = history(root)
        val id = begin(store)
        val text = part("text", AgentStreamPartKind.TEXT)
        store.appendTranscript(id, listOf(ExternalTranscriptUpdate(text, "start \uD83D")))
        store.appendTranscript(id, listOf(
            ExternalTranscriptUpdate(part("tool", AgentStreamPartKind.TOOL)),
            ExternalTranscriptUpdate(text, "\uDE80 end"),
        ))

        val done = store.finish(id, "start 🚀 end", emptyList(), PluginHistoryStatus.COMPLETED)
        assertEquals(4, done.summary.messageCount)
        assertEquals("start 🚀 end", done.messages.filter { it.role == "assistant" }.joinToString("") { it.content })
        assertEquals(1, done.messages.count { it.streamPart != null })
        store.close()
        assertEquals(done.messages, history(root).load(id)!!.messages)
    }

    @Test
    fun legacySchemaThreeRowsMigrateWithoutRewritingText() = runBlocking {
        val root = temporaryRoot("legacy")
        val file = databaseFile(root).apply { parentFile.mkdirs() }
        val id = PluginConversationHistory.newId()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            // Actual historical schema, not a current database relabeled with an older version.
            db.execSQL("""CREATE TABLE conversations (
                id TEXT PRIMARY KEY, agent_id TEXT NOT NULL, title TEXT NOT NULL,
                created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, native_id TEXT,
                mode TEXT NOT NULL, model_id TEXT, message_count INTEGER NOT NULL,
                part_count INTEGER NOT NULL, status TEXT NOT NULL, owner_id TEXT NOT NULL)""")
            db.execSQL("""CREATE TABLE message_parts (
                conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                ordinal INTEGER NOT NULL, logical_ordinal INTEGER NOT NULL, role TEXT NOT NULL,
                content TEXT NOT NULL, activity TEXT, PRIMARY KEY(conversation_id,ordinal))""")
            db.execSQL("""CREATE TABLE live_parts (
                conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                ordinal INTEGER NOT NULL, content TEXT NOT NULL, activity_id TEXT, activity TEXT,
                PRIMARY KEY(conversation_id,ordinal))""")
            db.execSQL("""CREATE TABLE active_conversations (
                agent_id TEXT PRIMARY KEY,
                conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE)""")
            db.execSQL("""CREATE TABLE message_media (
                conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                logical_ordinal INTEGER NOT NULL, refs TEXT NOT NULL,
                PRIMARY KEY(conversation_id,logical_ordinal))""")
            db.execSQL(
                "INSERT INTO conversations VALUES(?, 'opencode','Legacy',1,1,NULL,'EDIT',NULL,1,1,'COMPLETED','legacy')",
                arrayOf(id),
            )
            db.execSQL("INSERT INTO message_parts VALUES(?,0,0,'assistant',?,NULL)", arrayOf(id, "legacy 🚀 text"))
            db.version = 3
        }

        val store = history(root)
        assertEquals("legacy 🚀 text", store.load(id)!!.messages.single().content)
        store.beginTurn(id, "opencode", "new", WorkspaceAgentMode.EDIT, null)
        store.finish(id, "answer 🚀", emptyList(), PluginHistoryStatus.COMPLETED)
        store.close()
        database(root).use { db ->
            assertEquals(6, db.version)
            db.rawQuery("SELECT content,content_utf16 FROM message_parts ORDER BY ordinal", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("legacy 🚀 text", cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertTrue(cursor.moveToNext())
                assertEquals("", cursor.getString(0))
                assertNotNull(cursor.getBlob(1))
            }
        }
    }

    @Test
    fun pagingReconstructsExactCodeUnitsWithBoundedBlobs() = runBlocking {
        val root = temporaryRoot("paging")
        val store = history(root)
        val id = begin(store)
        val reply = "x".repeat(16_383) + "🚀" + "中".repeat(1_100_000) + "\uD83D"
        var page = store.finish(id, reply, emptyList(), PluginHistoryStatus.COMPLETED)
        val pages = mutableListOf(page)
        while (page.hasEarlier) {
            page = store.load(id, page.firstPart)!!
            pages += page
        }
        val reconstructed = pages.asReversed().flatMap { it.messages }
            .filter { it.role == "assistant" }.joinToString("") { it.content }
        assertEquals(reply, reconstructed)
        store.close()
        database(root).use { db ->
            db.rawQuery("SELECT MAX(length(content_utf16)),MAX(length(content)) FROM message_parts", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.getInt(0) <= 32_768)
                assertEquals(0, cursor.getInt(1))
            }
        }
    }

    @Test
    fun malformedPayloadRollsBackPromotionAndRetryKeepsOriginalParts() = runBlocking {
        for (bad in listOf(byteArrayOf(1), ByteArray(2_100_000))) {
            val root = temporaryRoot("malformed")
            val store = history(root)
            val id = begin(store)
            store.appendPartial(id, listOf("first"))
            store.appendPartial(id, listOf("second"))
            database(root).use { db ->
                db.update("live_parts", ContentValues().apply { put("content_utf16", bad) }, "ordinal=1", null)
            }

            expectInvalidPayload { store.finish(id, null, emptyList(), PluginHistoryStatus.STOPPED) }
            database(root).use { db ->
                db.rawQuery("SELECT status,message_count,part_count FROM conversations", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("RUNNING", cursor.getString(0))
                    assertEquals(1, cursor.getInt(1))
                    assertEquals(1, cursor.getInt(2))
                }
                // Compare inside SQLite so the oversized evidence is never fetched into CursorWindow.
                db.compileStatement("SELECT content_utf16=? FROM live_parts WHERE ordinal=1").use { statement ->
                    statement.bindBlob(1, bad)
                    assertEquals(1L, statement.simpleQueryForLong())
                }
                db.rawQuery("SELECT COUNT(*) FROM message_parts", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                }
                db.execSQL("UPDATE live_parts SET content='second',content_utf16=NULL WHERE ordinal=1")
            }
            val retried = store.finish(id, null, emptyList(), PluginHistoryStatus.STOPPED)
            assertEquals("firstsecond", retried.messages.last().content)
        }
    }

    @Test
    fun nonBlobTextIsRejectedBeforeFinalReplyCanDiscardEvidence() = runBlocking {
        val root = temporaryRoot("nonblob")
        val store = history(root)
        val id = begin(store)
        store.appendPartial(id, listOf("partial"))
        database(root).use { it.execSQL("UPDATE live_parts SET content_utf16='not a blob'") }

        expectInvalidPayload { store.finish(id, "final", emptyList(), PluginHistoryStatus.COMPLETED) }
        database(root).use { db ->
            db.rawQuery("SELECT content_utf16 FROM live_parts", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("not a blob", cursor.getString(0))
            }
            db.rawQuery("SELECT status,message_count FROM conversations", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("RUNNING", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
            }
        }
    }

    @Test
    fun failedSecondMigrationAlterRollsBackFirstAndKeepsLegacyBytes() = runBlocking {
        val root = temporaryRoot("migration-fault")
        val file = databaseFile(root).apply { parentFile.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE live_parts(content TEXT)")
            db.execSQL("INSERT INTO live_parts VALUES('keep live')")
            db.execSQL("CREATE TABLE message_parts(content TEXT,content_utf16 BLOB)")
            db.execSQL("INSERT INTO message_parts VALUES('keep saved',NULL)")
            db.version = 3
        }
        val store = history(root)
        var failed = false
        try {
            store.listPage()
        } catch (_: Exception) {
            failed = true
        }
        assertTrue("Conflicting schema must fail migration", failed)
        store.close()

        database(root).use { db ->
            assertEquals(3, db.version)
            db.rawQuery("PRAGMA table_info(live_parts)", null).use { cursor ->
                val names = mutableListOf<String>()
                while (cursor.moveToNext()) names += cursor.getString(1)
                assertEquals(listOf("content"), names)
            }
            db.rawQuery("SELECT content FROM live_parts", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("keep live", cursor.getString(0))
            }
            db.rawQuery("SELECT content FROM message_parts", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("keep saved", cursor.getString(0))
            }
        }
    }

    @Test
    fun conflictingLegacyAndBlobPayloadsPreserveBothIncludingNulPrefix() = runBlocking {
        for (legacy in listOf("conflicting text", "\u0000hidden")) {
            val root = temporaryRoot("conflicting")
            val store = history(root)
            val id = begin(store)
            store.appendPartial(id, listOf("partial"))
            database(root).use { db ->
                db.update("live_parts", ContentValues().apply { put("content", legacy) }, null, null)
            }

            expectInvalidPayload { store.finish(id, "final", emptyList(), PluginHistoryStatus.COMPLETED) }
            database(root).use { db ->
                db.rawQuery("SELECT content,content_utf16 FROM live_parts", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(legacy, cursor.getString(0))
                    assertEquals(14, cursor.getBlob(1).size)
                }
            }
        }
    }

    @Test
    fun everyUtf16CodeUnitAndEmptyPayloadRoundTripExactly() = runBlocking {
        for (reply in listOf("", "\u0000中🚀\u0000", "\uDE80x\uD83D")) {
            val root = temporaryRoot("code-units")
            val store = history(root)
            val id = begin(store)
            val done = store.finish(id, reply, emptyList(), PluginHistoryStatus.COMPLETED)
            assertEquals(reply, done.messages.last().content)
            store.close()
            assertEquals(reply, history(root).load(id)!!.messages.last().content)
        }
    }

    @Test
    fun titleBoundaryDoesNotSplitValidSupplementaryCharacter() = runBlocking {
        val root = temporaryRoot("title")
        val store = history(root)
        val prompt = "a".repeat(119) + "🚀suffix"
        val started = store.beginTurn(null, "opencode", prompt, WorkspaceAgentMode.EDIT, null)
        assertEquals("a".repeat(119), started.summary.title)
        assertEquals(prompt, started.messages.single().content)
        store.finish(started.summary.id, "done", emptyList(), PluginHistoryStatus.COMPLETED)
        store.close()

        val reopened = history(root)
        assertEquals("a".repeat(119), reopened.listPage().entries.single().title)
        assertEquals(prompt, reopened.load(started.summary.id)!!.messages.first().content)
    }
}
