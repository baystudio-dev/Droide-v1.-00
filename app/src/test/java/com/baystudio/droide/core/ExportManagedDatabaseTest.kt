package com.baystudio.droide.core

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ExportManagedDatabaseTest {
    private val home = Files.createTempDirectory("export-managed-db-").toFile()
    private val root = File(home, "workspace").apply { mkdir() }
    private val histories = mutableListOf<SqlitePluginConversationHistory>()
    private fun history(directory: File = root) = SqlitePluginConversationHistory(directory).also { histories += it }
    private val documents = object : WorkspaceDocumentAuthority {
        override suspend fun snapshot(path: String): WorkspaceDocumentSnapshot? = null
        override suspend fun snapshots(): List<WorkspaceDocumentSnapshot> = emptyList()
    }
    private fun owner() = ExportSourceAdmission("project", root, documents, {},
        requireEpoch = { "epoch" }, epochOpen = { it == "epoch" })
    private suspend fun complete(history: PluginConversationHistory, prompt: String): String {
        val turn = history.beginTurn(null, "codex", prompt, WorkspaceAgentMode.EDIT, null)
        history.finish(turn.summary.id, "reply: $prompt", emptyList(), PluginHistoryStatus.COMPLETED)
        return turn.summary.id
    }
    @After fun cleanup() = runBlocking { histories.forEach { it.close() }; PathSecurity.deleteTreeNoFollow(home); Unit }

    private class Destination(private val root: File, private val rawPath: String? = null) : ExportDestination {
        override fun createDirectory(relativePath: String) { File(root, relativePath).mkdirs() }
        override fun createFile(relativePath: String): ExportOutput {
            val file = File(root, rawPath ?: relativePath)
            file.parentFile!!.mkdirs()
            check(!file.exists())
            return object : ExportOutput {
                override fun openWrite() = file.outputStream()
                override fun openRead() = file.inputStream()
            }
        }
    }

    @Test fun allExportContainsReadableCommittedHistoryAndOwnerRemainsUsable() = runBlocking {
        val history = history()
        val first = complete(history, "before export")
        File(root, "app.apk").writeBytes(byteArrayOf(0, -1, 13, 10))
        val owner = owner(); val ticket = owner.ticket(ExportScope(""))
        val captured = owner.withCapture(ticket, true) { ExportCaptureStore(File(home, "capture")).capture(it) }
        captured.use {
            // Later activity must not alter the sealed export.
            complete(history, "after export")
            val restored = File(home, "restored").apply { mkdir() }
            ExportEngine().transfer(it, ExportFormat.TREE, Destination(restored))
            assertArrayEquals(File(root, "app.apk").readBytes(), File(restored, "app.apk").readBytes())
            val imported = history(restored)
            assertEquals(listOf("before export", "reply: before export"), imported.load(first)!!.messages.map { it.content })
            assertEquals(1, imported.listPage().entries.size)
            assertEquals(2, history.listPage().entries.size)
            SQLiteDatabase.openDatabase(File(restored, ".droide/sessions/plugins.sqlite").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("PRAGMA integrity_check", null).use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals("ok", cursor.getString(0)) }
            }
        }
    }

    @Test fun singleDatabaseExportDoesNotRequireWalSidecarsToRestoreLastCommittedTurn() = runBlocking {
        val history = history(); val id = complete(history, "committed WAL contents")
        val owner = owner(); val ticket = owner.ticket(ExportScope(".droide/sessions/plugins.sqlite"))
        val captured = owner.withCapture(ticket, true) { ExportCaptureStore(File(home, "capture")).capture(it) }
        captured.use {
            val restored = File(home, "restored").apply { mkdir() }
            ExportEngine().transfer(it, ExportFormat.RAW_FILE, Destination(restored, ".droide/sessions/plugins.sqlite"))
            assertEquals(listOf("plugins.sqlite"), File(restored, ".droide/sessions").list()!!.toList())
            assertEquals("committed WAL contents", history(restored).load(id)!!.messages.first().content)
            assertEquals(1, history.listPage().entries.size)
        }
    }

    @Test fun activeTurnRefusesCaptureThenSameOwnerCanRetryAfterCompletion() = runBlocking {
        val history = history()
        val turn = history.beginTurn(null, "codex", "still working", WorkspaceAgentMode.EDIT, null)
        val owner = owner(); val ticket = owner.ticket(ExportScope(""))
        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { owner.withCapture(ticket, true) { fail("Active turn cannot be captured") } }
        }
        assertTrue(failure.message.orEmpty().contains("turn is active"))
        history.finish(turn.summary.id, "done", emptyList(), PluginHistoryStatus.COMPLETED)
        owner.withCapture(ticket, true) { it.validate() }
        assertEquals(PluginHistoryStatus.COMPLETED, history.load(turn.summary.id)!!.summary.status)
    }

    @Test fun foreignTransactionRefusesCheckpointWithoutPublishingOrReplacingDatabase() = runBlocking {
        val history = history(); val id = complete(history, "preserved")
        val path = File(root, ".droide/sessions/plugins.sqlite")
        val db = SqlitePluginConversationHistory::class.java.getDeclaredField("database").apply { isAccessible = true }.get(history) as SQLiteDatabase
        db.rawQuery("PRAGMA busy_timeout=1", null).use { assertTrue(it.moveToFirst()) }
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING).use { foreign ->
            foreign.beginTransactionNonExclusive()
            try {
                val owner = owner(); val ticket = owner.ticket(ExportScope(""))
                val failure = assertThrows(IllegalStateException::class.java) {
                    runBlocking { owner.withCapture(ticket, true) { fail("Busy WAL was treated as stable") } }
                }
                assertTrue(failure.message.orEmpty().contains("busy"))
            } finally { foreign.endTransaction() }
        }
        assertEquals("preserved", history.load(id)!!.messages.first().content)
        val owner = owner(); owner.withCapture(owner.ticket(ExportScope("")), true) { it.validate() }
    }

    @Test fun captureReservationRejectsLateWritersAndCancellationReleasesEveryOwner() = runBlocking {
        val entered = CompletableDeferred<Unit>(); var released = false
        ExportWriterAdmission.beginManagedWriter(root, "Managed fixture", object : ExportCaptureParticipant {
            override suspend fun <T> withQuiescence(action: suspend () -> T): T {
                entered.complete(Unit)
                try { awaitCancellation() } finally { released = true }
            }
        }).use {
            val capture = launch { ExportWriterAdmission.withQuiescentCapture(root) { fail("Cancelled preparation reached capture") } }
            entered.await()
            assertThrows(ExportCaptureBusyException::class.java) { ExportWriterAdmission.beginWriter(root, "Late writer") }
            assertThrows(IllegalStateException::class.java) { ExportWriterAdmission.beginCapture(root) }
            capture.cancelAndJoin()
            assertTrue(released)
            assertEquals(listOf("Managed fixture"), ExportWriterAdmission.snapshotBlockers(root))
        }
        ExportWriterAdmission.beginWriter(root, "After cancellation").close()
        ExportWriterAdmission.beginCapture(root).close()
    }

    @Test fun ownerPreparationFailureUnwindsEarlierOwnersWithoutClosingTheirRegistrations() = runBlocking {
        var held = false
        ExportWriterAdmission.beginManagedWriter(root, "First", object : ExportCaptureParticipant {
            override suspend fun <T> withQuiescence(action: suspend () -> T): T {
                held = true
                return try { action() } finally { held = false }
            }
        }).use {
            ExportWriterAdmission.beginManagedWriter(root, "Second", object : ExportCaptureParticipant {
                override suspend fun <T> withQuiescence(action: suspend () -> T): T = error("owner unavailable")
            }).use {
                assertThrows(IllegalStateException::class.java) {
                    runBlocking { ExportWriterAdmission.withQuiescentCapture(root) { fail("Failed preparation reached capture") } }
                }
                assertFalse(held)
                assertEquals(listOf("First", "Second"), ExportWriterAdmission.snapshotBlockers(root))
                ExportWriterAdmission.beginWriter(root, "Normal operation").close()
            }
        }
    }

    @Test fun closeDuringCaptureWaitsOutsideOwnerMutexThenReleasesConnection() = runBlocking {
        val history = history(); complete(history, "keep this turn")
        val owner = owner(); val ticket = owner.ticket(ExportScope(""))
        val captured = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val operation = async(Dispatchers.IO) {
            owner.withCapture(ticket, true) { captured.complete(Unit); finish.await() }
        }
        captured.await()
        val close = async(start = CoroutineStart.UNDISPATCHED) { history.close() }
        try {
            assertFalse(close.isCompleted)
            assertTrue(ExportWriterAdmission.snapshotBlockers(root).contains("Another export is capturing this workspace"))
        } finally { finish.complete(Unit) }
        withTimeout(5_000) { operation.await(); close.await() }
        ExportWriterAdmission.beginCapture(root).close()
        assertThrows(IllegalStateException::class.java) { runBlocking { history.listPage() } }
        Unit
    }
}
