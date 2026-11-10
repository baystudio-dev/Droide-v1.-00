package com.baystudio.droide.core

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MirrorTypedAuthorityTest {
    @Test fun legacyReadsAndOrdinaryCommitsDoNotImplicitlyUpgradeSchema() = fixture {
        val state = store.commit(0, mapOf("file" to entry('a')), setOf("pending"))
        assertTrue(reopen().load().directoryHistory.isEmpty())
        assertEquals(state, reopen().load())
        database().use { assertEquals(1, it.version) }
    }

    @Test fun explicitUpgradeKeepsFileHistoryAndConflictsAcrossRestart() = fixture {
        val before = store.commit(0, mapOf("file" to entry('a')), setOf("pending"))
        val after = store.commitWithHistory(before.revision, before.baseline, before.conflicts, setOf("folder", "empty/child"))
        assertEquals(before.revision + 1, after.revision)
        assertEquals(before.baseline, after.baseline)
        assertEquals(before.conflicts, after.conflicts)
        assertEquals(setOf("folder", "empty/child"), after.directoryHistory)
        assertEquals(after, reopen().load())
        database().use { assertEquals(2, it.version) }
    }

    @Test fun failedUpgradeRollsBackSchemaRowsAndRevisionTogether() = fixture {
        val before = store.commit(0, mapOf("file" to entry('a')), emptySet())
        database().use { it.execSQL("CREATE TRIGGER reject_upgrade BEFORE UPDATE OF directory_digest ON meta BEGIN SELECT RAISE(ABORT,'injected directory failure'); END") }
        assertTrue(runCatching {
            store.commitWithHistory(before.revision, before.baseline, setOf("new-conflict"), setOf("folder"))
        }.exceptionOrNull() is SQLiteException)
        assertEquals(before, reopen().load())
        database().use { db ->
            assertEquals(1, db.version)
            db.rawQuery("SELECT name FROM sqlite_master WHERE name='baseline_directories'", null).use { assertFalse(it.moveToFirst()) }
        }
    }

    @Test fun nativeSqliteCapacityFailurePreservesDirectoryHistoryAndPendingFiles() = fixture {
        val before = store.commitWithHistory(0, emptyMap(), emptySet(), setOf("folder"))
        File(working, "pending").writeText("CLI bytes")
        val limited = MirrorMetadataAuthority(authority, source, working, databaseSizeLimitBytes = File(authority, "state.sqlite").length())
        val directories = (0 until 2_000).mapTo(linkedSetOf()) { "directory-$it" }
        val failure = runCatching { limited.commitWithHistory(before.revision, emptyMap(), emptySet(), directories) }.exceptionOrNull()
        assertTrue(failure.toString(), failure is android.database.sqlite.SQLiteFullException)
        assertEquals(before, reopen().load())
        assertEquals("CLI bytes", File(working, "pending").readText())
    }

    @Test fun unchangedTypedCommitPreservesDatabaseBytesAndTimestamp() = fixture {
        val state = store.commitWithHistory(0, emptyMap(), emptySet(), setOf("folder"))
        val file = File(authority, "state.sqlite")
        val bytes = file.readBytes()
        val modified = Files.getLastModifiedTime(file.toPath())
        repeat(3) { assertEquals(state, store.commitWithHistory(state.revision, state.baseline, state.conflicts, state.directoryHistory)) }
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(modified, Files.getLastModifiedTime(file.toPath()))
    }

    @Test fun clearingHistoryExplicitlyNeverDowngradesSchema() = fixture {
        val before = store.commitWithHistory(0, emptyMap(), emptySet(), setOf("folder"))
        val after = store.commitWithHistory(before.revision, emptyMap(), emptySet(), emptySet())
        assertTrue(after.directoryHistory.isEmpty())
        assertEquals(before.revision + 1, after.revision)
        database().use { assertEquals(2, it.version) }
        assertEquals(after, reopen().load())
    }

    @Test fun ordinaryReceiptCompletionCannotClearDirectoryHistory() = fixture {
        val before = store.commitWithHistory(0, emptyMap(), emptySet(), setOf("folder"))
        val intent = store.prepare("file", "create", MirrorMetadataAuthority.Node.absent, MirrorMetadataAuthority.Node.file(entry('b')))
        val after = store.finishBatch(before.revision, complete(intent), mapOf("file" to entry('b')), emptySet())
        assertEquals(before.directoryHistory, after.directoryHistory)
        assertEquals(after, reopen().load())
    }

    @Test fun directoryReceiptRequiresMatchingHistoryInTheSameTransaction() = fixture {
        val before = store.commitWithHistory(0, emptyMap(), emptySet(), emptySet())
        val intent = store.prepare("folder", "create", MirrorMetadataAuthority.Node.absent, MirrorMetadataAuthority.Node.directory("a".repeat(64)))
        assertNotNull(runCatching { store.finishBatch(before.revision, complete(intent), emptyMap(), emptySet()) }.exceptionOrNull())
        assertEquals(MirrorMetadataAuthority.Phase.PREPARED, reopen().operations().single().phase)
        assertEquals(before, reopen().load())
        val after = store.finishBatchWithHistory(before.revision, complete(intent), emptyMap(), emptySet(), setOf("folder"))
        assertEquals(setOf("folder"), after.directoryHistory)
        assertEquals(MirrorMetadataAuthority.Phase.APPLIED, reopen().operations().single().phase)
    }

    @Test fun directoryReceiptAndMetadataRollbackTogetherOnSqlFailure() = fixture {
        val before = store.commitWithHistory(0, emptyMap(), emptySet(), emptySet())
        val intent = store.prepare("folder", "create", MirrorMetadataAuthority.Node.absent, MirrorMetadataAuthority.Node.directory("a".repeat(64)))
        database().use { it.execSQL("CREATE TRIGGER reject_directory BEFORE INSERT ON baseline_directories BEGIN SELECT RAISE(ABORT,'injected directory failure'); END") }
        assertTrue(runCatching {
            store.finishBatchWithHistory(before.revision, complete(intent), emptyMap(), emptySet(), setOf("folder"))
        }.exceptionOrNull() is SQLiteException)
        assertEquals(before, reopen().load())
        assertEquals(MirrorMetadataAuthority.Phase.PREPARED, reopen().operations().single().phase)
    }

    @Test fun pendingIntentPreventsDirectoryHistoryFromBeingRewritten() = fixture {
        val before = store.commitWithHistory(0, emptyMap(), emptySet(), setOf("folder"))
        store.prepare("file", "create", MirrorMetadataAuthority.Node.absent, MirrorMetadataAuthority.Node.file(entry('a')))
        assertNotNull(runCatching { store.commitWithHistory(before.revision, emptyMap(), emptySet(), emptySet()) }.exceptionOrNull())
        assertEquals(before, reopen().load())
    }

    @Test fun invalidDirectoryPathsAndFileAncestryFailBeforeMigration() = fixture {
        val before = store.commit(0, mapOf("file" to entry('a')), emptySet())
        for (directories in listOf(setOf("../outside"), setOf("file"), setOf("file/child"), setOf(".git"), setOf("folder\tchild"))) {
            assertNotNull(runCatching { store.commitWithHistory(before.revision, before.baseline, emptySet(), directories) }.exceptionOrNull())
            assertEquals(before, reopen().load())
            database().use { assertEquals(1, it.version) }
        }
    }

    @Test fun directoryCountLimitIsCheckedBeforeDatabaseMutation() = fixture {
        val before = store.load()
        val directories = (0..40_000).mapTo(linkedSetOf()) { "d$it" }
        assertNotNull(runCatching { store.commitWithHistory(before.revision, emptyMap(), emptySet(), directories) }.exceptionOrNull())
        assertEquals(before, reopen().load())
        database().use { assertEquals(1, it.version) }
    }

    @Test fun corruptOrMissingDirectorySchemaNeverFallsBackToFilesOnly() {
        for (statement in listOf("DROP TABLE baseline_directories", "UPDATE meta SET directory_digest='broken'", "PRAGMA user_version=99")) {
            fixture {
                store.commitWithHistory(0, emptyMap(), emptySet(), setOf("folder"))
                File(working, "pending").writeText("CLI bytes")
                database().use { it.execSQL(statement) }
                assertNotNull(runCatching { reopen().load() }.exceptionOrNull())
                assertEquals("CLI bytes", File(working, "pending").readText())
            }
        }
    }

    @Test(timeout = 30_000) fun twoOwnersCannotCommitDifferentDirectoryHistoriesFromOneRevision() = fixture {
        val before = store.commitWithHistory(0, emptyMap(), emptySet(), setOf("original"))
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (0..1).map { index -> executor.submit<Boolean> {
                val owner = reopen()
                ready.countDown()
                check(start.await(5, TimeUnit.SECONDS))
                try {
                    owner.commitWithHistory(before.revision, emptyMap(), setOf("conflict-$index"), setOf("directory-$index"))
                    true
                } catch (_: IllegalStateException) { false }
            } }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it })
            val winner = results.indexOf(true)
            assertEquals(setOf("directory-$winner"), reopen().load().directoryHistory)
            assertEquals(setOf("conflict-$winner"), reopen().load().conflicts)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private data class Fixture(val source: File, val working: File, val authority: File, val store: MirrorMetadataAuthority) {
        fun reopen() = MirrorMetadataAuthority(authority, source, working)
        fun database(): SQLiteDatabase = SQLiteDatabase.openDatabase(File(authority, "state.sqlite").path, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
    }

    private fun fixture(block: Fixture.() -> Unit) {
        val root = Files.createTempDirectory("droide-typed-authority-").toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            val working = File(root, "working")
            val authority = File(root, "authority")
            Fixture(source, working, authority, MirrorMetadataAuthority.createForNewWorkingCopy(authority, source, working)).block()
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private fun entry(letter: Char) = WorkspaceSyncManifest.Entry(letter.toString().repeat(64), 420)
    private fun complete(intent: MirrorMetadataAuthority.Intent) = listOf(
        MirrorMetadataAuthority.Transition(intent.id, MirrorMetadataAuthority.Phase.PREPARED, MirrorMetadataAuthority.Phase.RETAINED),
        MirrorMetadataAuthority.Transition(intent.id, MirrorMetadataAuthority.Phase.RETAINED, MirrorMetadataAuthority.Phase.APPLIED),
    )
}
