package com.baystudio.droide.core

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
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
class SqlitePluginConversationHistoryTest {
    private val root = Files.createTempDirectory("plugin-history-store-").toFile()
    private val stores = mutableListOf<SqlitePluginConversationHistory>()
    private fun store(directory: File = root, clock: () -> Long = { 10_000L }): SqlitePluginConversationHistory =
        SqlitePluginConversationHistory(directory, clock).also { stores += it }

    @After fun cleanup() = runBlocking {
        stores.forEach { it.close() }
        assertTrue(PathSecurity.deleteTreeNoFollow(root))
    }

    private suspend fun complete(history: PluginConversationHistory, agent: String, prompt: String, reply: String = "Answer to $prompt", native: String = "shared-native-id"): PluginHistoryWindow {
        val admitted = history.beginTurn(null, agent, prompt, WorkspaceAgentMode.EDIT, native)
        history.bind(admitted.summary.id, native, "provider/model")
        return history.finish(admitted.summary.id, reply, emptyList(), PluginHistoryStatus.COMPLETED)
    }

    private suspend fun fails(block: suspend () -> Unit) {
        var failure: Exception? = null
        try { block() } catch (error: Exception) { failure = error }
        assertNotNull("The operation must reject without destructive recovery", failure)
    }

    @Test fun liveConnectionBlocksCaptureUntilActualCloseButClosedFilesRemainExportable() = runBlocking {
        val history = store()
        ExportWriterAdmission.beginCapture(root).use {
            assertThrows(ExportCaptureBusyException::class.java) { runBlocking { history.listPage() } }
            assertFalse(File(root, ".droide/sessions/plugins.sqlite").exists())
        }
        history.listPage()
        val refused = assertThrows(IllegalStateException::class.java) { ExportWriterAdmission.beginCapture(root) }
        assertTrue(refused.message.orEmpty().contains("plugin conversation database"))
        history.close()
        assertTrue(File(root, ".droide/sessions/plugins.sqlite").isFile)
        ExportWriterAdmission.beginCapture(root).close()
    }

    @Test fun allCatalogAgentsSurviveRecreationWithTheirOwnTranscriptAndNativeIdentity() = runBlocking {
        val first = store()
        val ids = WorkspaceAgentPluginCatalog.entries.associate { spec ->
            val saved = complete(first, spec.id, "Question for ${spec.displayName}", "Reply from ${spec.id}")
            spec.id to saved.summary.id
        }
        assertEquals(5, ids.values.toSet().size)
        first.close()
        val reopened = store()
        for (spec in WorkspaceAgentPluginCatalog.entries) {
            val saved = checkNotNull(reopened.latest(spec.id))
            assertEquals(ids[spec.id], saved.summary.id)
            assertEquals(spec.id, saved.summary.agentId)
            assertEquals("shared-native-id", saved.summary.nativeSessionId)
            assertEquals("provider/model", saved.summary.modelId)
            assertEquals(listOf("Question for ${spec.displayName}", "Reply from ${spec.id}"), saved.messages.map { it.content })
        }
    }

    @Test fun nativeIdReuseDoesNotOverwriteOlderConversationsAndOpeningChangesOnlyActiveSelection() = runBlocking {
        val history = store()
        val first = complete(history, "codex", "First question")
        val second = complete(history, "codex", "Second question")
        assertNotEquals(first.summary.id, second.summary.id)
        assertEquals(2, history.listPage("codex").entries.size)
        assertEquals(second.summary.id, history.latest("codex")!!.summary.id)
        history.load(first.summary.id)
        history.close()
        val reopened = store()
        assertEquals(first.summary.id, reopened.latest("codex")!!.summary.id)
        assertEquals("Second question", reopened.load(second.summary.id)!!.messages.first().content)
    }

    @Test fun metadataPaginationReachesMoreThanOneHundredRecordsWithoutDuplicatesAtEqualTimestamps() = runBlocking {
        val history = store()
        repeat(123) { complete(history, if (it % 2 == 0) "opencode" else "gemini", "Saved task $it") }
        val ids = mutableListOf<String>()
        var cursor: PluginHistoryCursor? = null
        do {
            val page = history.listPage(before = cursor, limit = 17)
            assertTrue(page.entries.size <= 17)
            ids += page.entries.map { it.id }
            cursor = page.nextCursor
        } while (cursor != null)
        assertEquals(123, ids.size)
        assertEquals(123, ids.toSet().size)
        assertEquals(17, history.listPage("gemini", limit = 17).entries.size)
        assertTrue(history.listPage("gemini", limit = 80).entries.all { it.agentId == "gemini" })
    }

    @Test fun searchTreatsWildcardsAndSqlLikeUserTextLiterallyAndRespectsPluginFilter() = runBlocking {
        val history = store()
        complete(history, "opencode", "Fix 100%_done query")
        complete(history, "gemini", "Fix 100 percent done query")
        assertEquals(1, history.listPage(query = "%_").entries.size)
        assertTrue(history.listPage("gemini", "%_").entries.isEmpty())
        assertTrue(history.listPage(query = "' OR 1=1 --").entries.isEmpty())
    }

    @Test fun largeUnicodeRepliesArePreservedExactlyThroughBoundedEarlierAndLatestWindows() = runBlocking {
        val history = store()
        val reply = "a".repeat(16_383) + "🚀" + "b".repeat(1_100_000) + " — end"
        val latest = complete(history, "claude", "Large reply", reply)
        assertTrue(latest.hasEarlier)
        val pages = mutableListOf(latest)
        var page = latest
        while (page.hasEarlier) {
            page = history.load(latest.summary.id, page.firstPart)!!
            assertTrue(page.messages.sumOf { it.content.length } <= 512_000)
            pages += page
        }
        val reconstructed = pages.asReversed().flatMap { it.messages }.filter { it.role == "assistant" }.joinToString("") { it.content }
        assertEquals(reply, reconstructed)
        assertEquals(2, latest.summary.messageCount)
        assertEquals(latest.messages, history.load(latest.summary.id)!!.messages)
    }

    @Test fun interruptedPartialOutputIsRecoveredOnceWithoutReplayingTheUserPrompt() = runBlocking {
        val original = store()
        val saved = original.beginTurn(null, "opencode", "Build this", WorkspaceAgentMode.EDIT, null)
        original.bind(saved.summary.id, "native-durable")
        original.appendPartial(saved.summary.id, listOf("Progress ", "\uD83D", "\uDE80", " part two"))
        original.close()
        val reopened = store()
        assertEquals(PluginHistoryStatus.INTERRUPTED, reopened.listPage().entries.single().status)
        val loaded = reopened.latest("opencode")!!
        assertEquals(listOf("Build this", "Progress 🚀 part two"), loaded.messages.map { it.content })
        assertEquals(PluginHistoryStatus.INTERRUPTED, loaded.summary.status)
        assertEquals("native-durable", loaded.summary.nativeSessionId)
        assertEquals(loaded.messages, reopened.load(loaded.summary.id)!!.messages)
        assertEquals(2, reopened.load(loaded.summary.id)!!.summary.messageCount)
    }

    @Test fun ownedActiveTurnsCannotBeOpenedDeletedOrOverwrittenByLateWriters() = runBlocking {
        val old = store()
        val active = old.beginTurn(null, "codex", "First run", WorkspaceAgentMode.EDIT, "remote")
        old.appendPartial(active.summary.id, listOf("Old partial"))
        fails { old.load(active.summary.id) }
        fails { old.delete(active.summary.id) }
        val replacement = store()
        assertNotNull(replacement.load(active.summary.id)!!.readOnlyReason)
        fails { replacement.beginTurn(active.summary.id, "codex", "Premature", WorkspaceAgentMode.EDIT, "remote") }
        old.appendPartial(active.summary.id, listOf(" still owned"))
        old.close()
        assertNull(replacement.load(active.summary.id)!!.readOnlyReason)
        replacement.beginTurn(active.summary.id, "codex", "New run", WorkspaceAgentMode.EDIT, "remote")
        fails { old.appendPartial(active.summary.id, listOf("Late data")) }
        fails { old.finish(active.summary.id, "Late reply", emptyList(), PluginHistoryStatus.COMPLETED) }
        val final = replacement.finish(active.summary.id, "New reply", emptyList(), PluginHistoryStatus.COMPLETED)
        assertEquals(listOf("First run", "Old partial still owned", "New run", "New reply"), final.messages.map { it.content })
    }

    @Test fun workspaceIsolationAndDeletionPreserveBuiltinSessionsAndOtherPluginRecords() = runBlocking {
        val firstRoot = File(root, "first").apply { mkdirs() }
        val otherRoot = File(root, "second").apply { mkdirs() }
        val builtin = File(firstRoot, ".droide/sessions/s-unchanged.json").apply { parentFile.mkdirs(); writeText("builtin marker") }
        val first = store(firstRoot)
        val second = store(otherRoot)
        val erased = complete(first, "opencode", "Delete me")
        val kept = complete(first, "gemini", "Keep me")
        assertTrue(second.listPage().entries.isEmpty())
        first.delete(erased.summary.id)
        assertNull(first.load(erased.summary.id))
        assertEquals("Keep me", first.load(kept.summary.id)!!.messages.first().content)
        assertEquals("builtin marker", builtin.readText())
        assertTrue(second.listPage().entries.isEmpty())
    }

    @Test fun corruptionRecoveryNeverDeletesOrRecreatesExistingBytes() = runBlocking {
        val file = File(root, ".droide/sessions/plugins.sqlite").apply { parentFile.mkdirs() }
        val marker = "unreadable user history — preserve me".toByteArray()
        file.writeBytes(marker)
        fails { store().latest("gemini") }
        assertTrue(file.isFile)
        assertArrayEquals(marker, file.readBytes())
    }

    @Test fun symbolicLinksAndInvalidDatabaseTargetsAreRejectedWithoutTouchingTheirTargets() = runBlocking {
        val target = File(root, "unrelated-file").apply { writeText("keep me") }
        val link = File(root, ".droide/sessions/plugins.sqlite").apply { parentFile.mkdirs() }
        Files.createSymbolicLink(link.toPath(), target.toPath())
        fails { store().listPage() }
        assertEquals("keep me", target.readText())
        assertTrue(Files.isSymbolicLink(link.toPath()))
        Files.delete(link.toPath())
        link.mkdir()
        fails { store().beginTurn(null, "opencode", "Do not send", WorkspaceAgentMode.EDIT, null) }
        assertTrue(link.isDirectory)
    }

    @Test fun futureSchemaIsRejectedWithoutDeletingItsUserTable() = runBlocking {
        val file = File(root, ".droide/sessions/plugins.sqlite").apply { parentFile.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use {
            it.execSQL("CREATE TABLE preserved(value TEXT)")
            it.execSQL("INSERT INTO preserved VALUES('marker')")
            it.version = 2
        }
        fails { store().listPage() }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(2, it.version)
            it.rawQuery("SELECT value FROM preserved", null).use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals("marker", cursor.getString(0)) }
        }
    }

    @Test fun clockRollbackCannotMoveAnExistingConversationBeforeItsOwnCreation() = runBlocking {
        var now = 10_000L
        val history = store(clock = { now })
        val first = complete(history, "copilot", "Clock test")
        now = 1_000
        history.beginTurn(first.summary.id, "copilot", "Follow up", WorkspaceAgentMode.PLAN, "shared-native-id")
        val updated = history.finish(first.summary.id, "New answer", emptyList(), PluginHistoryStatus.COMPLETED)
        assertEquals(10_000L, updated.summary.updatedAt)
        assertEquals(WorkspaceAgentMode.PLAN, updated.summary.mode)
    }
}
