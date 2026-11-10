package com.baystudio.droide.core

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
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
class MirrorMetadataAuthorityTest {
    @Test fun pendingExternalLinkBlocksFilesystemAdmissionUntilItsDurableCompletionIsRecorded() = fixture {
        val store = MirrorMetadataAuthority.createForNewWorkingCopy(authority, source, working, requireExternalLink = true)
        assertEquals(MirrorMetadataAuthority.BootstrapPhase.PREPARED, store.load().bootstrapPhase)
        File(working, "pending").writeText("unpublished")
        assertNotNull(runCatching { store.prepare("new", "create", MirrorMetadataAuthority.Node.absent,
            MirrorMetadataAuthority.Node.file(entry('a'))) }.exceptionOrNull())
        assertNotNull(runCatching { store.commit(0, emptyMap(), emptySet()) }.exceptionOrNull())
        assertTrue(store.operations().isEmpty())
        val completed = store.completeExternalLink(0)
        assertEquals(MirrorMetadataAuthority.BootstrapPhase.LINKED, completed.bootstrapPhase)
        assertEquals(1, completed.revision)
        assertEquals(completed, reopen().load())
        store.prepare("new", "create", MirrorMetadataAuthority.Node.absent, MirrorMetadataAuthority.Node.file(entry('a')))
        assertEquals("unpublished", File(working, "pending").readText())
    }

    @Test fun bootstrapPhaseTamperingCannotConvertAnIncompleteAuthorityIntoALinkedOne() = fixture {
        MirrorMetadataAuthority.createForNewWorkingCopy(authority, source, working, requireExternalLink = true)
        File(working, "pending").writeText("unpublished")
        db().use { it.execSQL("UPDATE meta SET bootstrap_phase='LINKED'") }
        val failure = runCatching { reopen().load() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("bootstrap checksum"))
        assertEquals("unpublished", File(working, "pending").readText())
    }

    @Test fun actualNativeSqlitePageQuotaRollsBackAFullAdmissionWithoutChangingBaselineOrWorkingFiles() = fixture {
        val store = create()
        val before = store.commit(0, mapOf("file" to entry('a')), setOf("old-conflict"))
        File(working, "pending").writeText("unpublished")
        val database = File(authority, "state.sqlite")
        val restricted = MirrorMetadataAuthority(authority, source, working, databaseSizeLimitBytes = database.length())
        val proposals = (0 until 1_000).map { MirrorMetadataAuthority.Proposal("new-$it", "create",
            MirrorMetadataAuthority.Node.absent, MirrorMetadataAuthority.Node.file(entry('b'))) }
        val failure = runCatching { restricted.prepareBatch(before.revision, proposals) }.exceptionOrNull()
        assertTrue("Expected an actual native SQLite FULL result, got $failure", failure is android.database.sqlite.SQLiteFullException)
        assertEquals(before, reopen().load())
        assertTrue(reopen().operations().isEmpty())
        assertEquals("unpublished", File(working, "pending").readText())
    }

    @Test fun completedReceiptsAndBaselineRollbackTogetherWhenSqlRejectsCompletion() = fixture {
        val store = create()
        val old = store.commit(0, mapOf("file" to entry('a')), emptySet())
        val intent = store.prepare("file", "replace", MirrorMetadataAuthority.Node.file(entry('a')), MirrorMetadataAuthority.Node.file(entry('b')))
        db().use { it.execSQL("CREATE TRIGGER reject_completion BEFORE UPDATE ON operations WHEN NEW.phase='APPLIED' BEGIN SELECT RAISE(ABORT,'injected receipt failure'); END") }
        val failure = runCatching { store.finishBatch(old.revision, listOf(
            MirrorMetadataAuthority.Transition(intent.id, MirrorMetadataAuthority.Phase.PREPARED, MirrorMetadataAuthority.Phase.RETAINED),
            MirrorMetadataAuthority.Transition(intent.id, MirrorMetadataAuthority.Phase.RETAINED, MirrorMetadataAuthority.Phase.APPLIED)),
            mapOf("file" to entry('b')), emptySet()) }.exceptionOrNull()
        assertTrue(failure is SQLiteException)
        assertEquals(old, reopen().load())
        assertEquals(MirrorMetadataAuthority.Phase.PREPARED, reopen().operations().single().phase)
    }

    @Test fun completionWithoutItsPublishedBaselineIsRejectedWithoutChangingAnyMetadata() = fixture {
        val store = create()
        val old = store.commit(0, mapOf("file" to entry('a')), emptySet())
        val intent = store.prepare("file", "replace", MirrorMetadataAuthority.Node.file(entry('a')), MirrorMetadataAuthority.Node.file(entry('b')))
        val failure = runCatching { store.finishBatch(old.revision, listOf(
            MirrorMetadataAuthority.Transition(intent.id, MirrorMetadataAuthority.Phase.PREPARED, MirrorMetadataAuthority.Phase.RETAINED),
            MirrorMetadataAuthority.Transition(intent.id, MirrorMetadataAuthority.Phase.RETAINED, MirrorMetadataAuthority.Phase.APPLIED)),
            old.baseline, emptySet()) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertNotNull(runCatching { store.transition(intent.id, MirrorMetadataAuthority.Phase.RETAINED, MirrorMetadataAuthority.Phase.APPLIED) }.exceptionOrNull())
        assertEquals(old, reopen().load())
        assertEquals(MirrorMetadataAuthority.Phase.PREPARED, reopen().operations().single().phase)
    }

    @Test fun invalidReceiptBatchRollsBackEarlierTransitionsWithoutInventingCompletion() = fixture {
        val store = create()
        val first = MirrorMetadataAuthority.Proposal("first", "replace", MirrorMetadataAuthority.Node.file(entry('a')), MirrorMetadataAuthority.Node.file(entry('b')))
        val second = MirrorMetadataAuthority.Proposal("second", "replace", MirrorMetadataAuthority.Node.file(entry('a')), MirrorMetadataAuthority.Node.file(entry('b')))
        val intents = store.prepareBatch(0, listOf(first, second))
        val failure = runCatching { store.transitionBatch(listOf(
            MirrorMetadataAuthority.Transition(intents[0].id, MirrorMetadataAuthority.Phase.PREPARED, MirrorMetadataAuthority.Phase.RETAINED),
            MirrorMetadataAuthority.Transition(intents[1].id, MirrorMetadataAuthority.Phase.RETAINED, MirrorMetadataAuthority.Phase.CONFLICT))) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(reopen().operations().all { it.phase == MirrorMetadataAuthority.Phase.PREPARED })
    }

    @Test fun replacedRetentionDirectoryFailsClosedAndPreservesTheOriginalDirectory() = fixture {
        create()
        val retained = File(authority, "retained")
        File(retained, "pending").writeText("unpublished")
        val saved = File(root, "original-retained")
        Files.move(retained.toPath(), saved.toPath())
        retained.mkdir()
        assertNotNull(runCatching { reopen().load() }.exceptionOrNull())
        assertEquals("unpublished", File(saved, "pending").readText())
        assertTrue(retained.listFiles().orEmpty().isEmpty())
    }

    private data class Fixture(val root: File, val source: File, val working: File, val authority: File) {
        fun create() = MirrorMetadataAuthority.createForNewWorkingCopy(authority, source, working)
        fun reopen() = MirrorMetadataAuthority(authority, source, working)
        fun db() = SQLiteDatabase.openDatabase(File(authority, "state.sqlite").path, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
    }
    private fun fixture(block: Fixture.() -> Unit) {
        val root = Files.createTempDirectory("droide-metadata-authority-").toFile()
        try { Fixture(root, File(root, "source").apply { mkdir() }, File(root, "working"), File(root, "authority")).block() }
        finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    private fun entry(letter: Char) = WorkspaceSyncManifest.Entry(letter.toString().repeat(64), 420)

    @Test fun nativeDirectoryPublicationAndFrameworkSqliteSurviveOwnerRecreation() = fixture {
        File(source, "file").writeText("canonical")
        val store = create()
        File(working, "file").writeText("CLI pending")
        val committed = store.commit(0, mapOf("file" to entry('a')), setOf("pending.bin"))
        assertEquals(1, committed.revision)
        assertEquals(committed, reopen().load())
        assertEquals("canonical", File(source, "file").readText())
        assertEquals("CLI pending", File(working, "file").readText())
    }

    @Test fun baselineAndConflictUpdatesRollbackTogetherOnSqlFailure() = fixture {
        val store = create()
        val before = store.commit(0, mapOf("a" to entry('a')), setOf("old"))
        db().use { it.execSQL("CREATE TRIGGER reject_b BEFORE INSERT ON baseline WHEN NEW.path='b' BEGIN SELECT RAISE(ABORT,'injected write failure'); END") }
        val failure = runCatching { store.commit(before.revision, mapOf("a" to entry('b'), "b" to entry('c')), setOf("new")) }.exceptionOrNull()
        assertTrue(failure is SQLiteException)
        assertEquals(before, reopen().load())
    }

    @Test fun unchangedMetadataCommitDoesNotRewriteTheDatabase() = fixture {
        val store = create()
        val before = store.commit(0, mapOf("file" to entry('a')), setOf("pending"))
        val file = File(authority, "state.sqlite")
        val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        assertEquals(before, store.commit(before.revision, before.baseline, before.conflicts))
        val after = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        assertEquals(attrs.fileKey(), after.fileKey())
        assertEquals(attrs.lastModifiedTime(), after.lastModifiedTime())
    }

    @Test fun missingEstablishedDatabaseIsNeverRecreated() = fixture {
        create()
        File(working, "pending").writeText("unpublished")
        assertTrue(File(authority, "state.sqlite").delete())
        val failure = runCatching { reopen().load() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertFalse(File(authority, "state.sqlite").exists())
        assertEquals("unpublished", File(working, "pending").readText())
    }

    @Test fun corruptDatabaseIsPreservedInsteadOfDeletedOrReinitialized() = fixture {
        create()
        val file = File(authority, "state.sqlite")
        val corrupt = ByteArray(4_096) { 0x35 }
        file.writeBytes(corrupt)
        val failure = runCatching { reopen().load() }.exceptionOrNull()
        assertNotNull(failure)
        assertArrayEquals(corrupt, file.readBytes())
    }

    @Test fun validSqlWithMissingBaselineRowsFailsChecksumValidation() = fixture {
        create().commit(0, mapOf("a" to entry('a'), "b" to entry('b')), setOf("pending"))
        db().use { it.execSQL("DELETE FROM baseline WHERE path='b'") }
        val failure = runCatching { reopen().load() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("checksum"))
    }

    @Test fun symlinkDatabaseCannotReadOrModifyAnExternalFile() = fixture {
        create()
        val file = File(authority, "state.sqlite")
        Files.move(file.toPath(), File(authority, "retained.sqlite").toPath())
        val outside = File(root, "outside").apply { writeText("external") }
        Files.createSymbolicLink(file.toPath(), outside.toPath())
        val failure = runCatching { reopen().load() }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals("external", outside.readText())
        assertTrue(Files.isSymbolicLink(file.toPath()))
    }

    @Test fun operationIntentAndConditionalTransitionsSurviveRestart() = fixture {
        val store = create()
        val intent = store.prepare("file", "replace", MirrorMetadataAuthority.Node.file(entry('a')), MirrorMetadataAuthority.Node.file(entry('b')))
        assertEquals(listOf(intent), reopen().operations())
        val wrong = runCatching { store.transition(intent.id, MirrorMetadataAuthority.Phase.RETAINED, MirrorMetadataAuthority.Phase.APPLIED) }.exceptionOrNull()
        assertNotNull(wrong)
        assertEquals(listOf(intent), reopen().operations())
        store.transition(intent.id, MirrorMetadataAuthority.Phase.PREPARED, MirrorMetadataAuthority.Phase.RETAINED)
        assertEquals(MirrorMetadataAuthority.Phase.RETAINED, reopen().operations().single().phase)
    }

    @Test fun removedRecoveryIntentIsDetectedWithoutRecreatingIt() = fixture {
        val store = create()
        store.prepare("file", "delete", MirrorMetadataAuthority.Node.file(entry('a')), MirrorMetadataAuthority.Node.absent)
        db().use { it.execSQL("DELETE FROM operations") }
        val failure = runCatching { reopen().operations() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("intents"))
    }

    @Test fun validLookingRecoveryPhaseTamperingFailsItsChecksum() = fixture {
        val store = create()
        store.prepare("file", "replace", MirrorMetadataAuthority.Node.file(entry('a')), MirrorMetadataAuthority.Node.file(entry('b')))
        db().use { it.execSQL("UPDATE operations SET phase='APPLIED'") }
        val failure = runCatching { reopen().operations() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("checksum"))
    }

    @Test fun largeIntentBatchUsesBoundedPagesAndSurvivesRestart() = fixture {
        val store = create()
        val proposals = (0 until 1_000).map { MirrorMetadataAuthority.Proposal("file-$it", "create",
            MirrorMetadataAuthority.Node.absent, MirrorMetadataAuthority.Node.file(entry('a'))) }
        val inserted = store.prepareBatch(0, proposals).associateBy { it.id }
        val recovered = linkedMapOf<String, MirrorMetadataAuthority.Intent>()
        var after: String? = null
        while (true) {
            val page = reopen().operations(after, 128)
            assertTrue(page.size <= 128)
            if (page.isEmpty()) break
            for (record in page) assertNull(recovered.put(record.id, record))
            after = page.last().id
        }
        assertEquals(inserted, recovered)
        assertEquals(0, reopen().load().revision)
    }

    @Test fun pendingFilesystemIntentBlocksAnotherPlanWithoutDiscardingIt() = fixture {
        val store = create()
        val first = store.prepare("file", "replace", MirrorMetadataAuthority.Node.file(entry('a')), MirrorMetadataAuthority.Node.file(entry('b')))
        val failure = runCatching { store.prepare("other", "create", MirrorMetadataAuthority.Node.absent, MirrorMetadataAuthority.Node.file(entry('c'))) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(listOf(first), reopen().operations())
    }

    @Test(timeout = 30_000) fun twoOwnersCannotCommitDifferentStatesFromTheSameRevision() = fixture {
        val first = create(); val second = reopen()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val ready = CountDownLatch(2); val start = CountDownLatch(1)
            val contenders = listOf(first, second).mapIndexed { index, store -> executor.submit<Boolean> {
                val revision = store.load().revision
                ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS))
                try { store.commit(revision, mapOf("file" to entry(if (index == 0) 'a' else 'b')), setOf("conflict-$index")); true }
                catch (_: IllegalStateException) { false }
            } }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown()
            val accepted = contenders.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, accepted.count { it })
            val winner = if (accepted[0]) 0 else 1
            val state = reopen().load()
            assertEquals(1, state.revision)
            assertEquals(entry(if (winner == 0) 'a' else 'b'), state.baseline["file"])
            assertEquals(setOf("conflict-$winner"), state.conflicts)
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test fun unsupportedSchemaIsNotSilentlyUpgradedOrReset() = fixture {
        create()
        db().use { it.version = 2 }
        val failure = runCatching { reopen().load() }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        db().use { assertEquals(2, it.version) }
    }

    @Test fun interruptedInitializationDoesNotPublishPartialAuthorityOrReinitializeAnExistingCopy() = fixture {
        val durability = object : ProjectCopyJournal.Durability {
            override fun forceFile(channel: FileChannel) = channel.force(true)
            override fun forceDirectory(directory: File) { throw java.io.IOException("injected initialization interruption") }
        }
        val failure = runCatching { MirrorMetadataAuthority.createForNewWorkingCopy(authority, source, working, durability) }.exceptionOrNull()
        assertTrue(failure is java.io.IOException)
        assertTrue(working.isDirectory)
        assertFalse(authority.exists())
        File(working, "pending").writeText("retained after interruption")
        assertNotNull(runCatching { reopen().load() }.exceptionOrNull())
        assertNotNull(runCatching { create() }.exceptionOrNull())
        assertEquals("retained after interruption", File(working, "pending").readText())
        assertTrue(root.listFiles().orEmpty().any { it.name.startsWith(".mirror-authority-") })
    }
}
