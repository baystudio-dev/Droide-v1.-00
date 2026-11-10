package com.baystudio.droide.core

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CanonicalJournalBootstrapTest {
    @get:Rule val temp = TemporaryFolder()
    private val context = EmptyCoroutineContext
    private val id = "catalog-project"
    private val limit = 1_000_000L

    private inner class Fixture {
        val base = temp.newFolder()
        val root = File(base, "workspace").apply { check(mkdir()) }
        val host = File(base, "host").apply { check(mkdir()) }
        val slot = File(host, "registration")
        val source = File(root, "file.txt").apply { writeText("unpublished user bytes") }
        val authority get() = File(slot, "authority")
        val database get() = File(authority, "state.sqlite")
        fun bootstrap(checkpoint: (String) -> Unit = {}) = CanonicalJournalBootstrap(slot, limit, checkpoint)
        fun create() = bootstrap().createNew(root, id, context)
        fun read() = bootstrap().readExisting(root, id, context)
        fun recover() = bootstrap().recoverCommittedEnrollment(root, id, context)
        fun interruptAt(boundary: String) {
            var reached = false
            refused { bootstrap { if (it == boundary) { reached = true; throw IOException("injected interruption") } }
                .createNew(root, id, context) }
            assertTrue("Boundary was not reached: $boundary", reached)
        }
        fun journal(enrollment: CanonicalFileOperationJournal.Enrollment) =
            CanonicalFileOperationJournal(root, authority, enrollment, limit)
        fun before() = AnchoredWorkspaceFiles(root).use {
            CanonicalFileSnapshot(limit).inspect(it, source.name, context)
        }
    }

    @Test fun explicitEnrollmentSurvivesReopeningWithoutReinitializingSqlite() {
        val f = Fixture()
        val enrolled = f.create()
        val before = tree(f.base)
        repeat(3) {
            assertEquals(enrolled, f.read())
            assertNull(f.journal(f.read()).read())
        }
        assertEquals(before, tree(f.base))
        assertEquals("unpublished user bytes", f.source.readText())
        CanonicalJournalFixture.openSql(f.database) {
            it.rawQuery("PRAGMA journal_mode", null).use { row -> assertTrue(row.moveToFirst()); assertEquals("delete", row.getString(0)) }
            it.rawQuery("PRAGMA synchronous", null).use { row -> assertTrue(row.moveToFirst()); assertEquals(3, row.getInt(0)) }
        }
    }

    @Test fun pendingIntentAndIndependentCopyRemainAvailableAfterReopeningEnrollment() {
        val f = Fixture()
        val journal = f.journal(f.create())
        val proposal = CanonicalFileOperationJournal.Content(3L, CanonicalJournalFixture.sha256("new".toByteArray()), 384)
        val intent = journal.admit(f.source.name, f.before(), proposal, context)
        val copied = journal.capture(intent.id, context)
        val expected = journal.read()
        val before = tree(f.base)
        val resumed = f.journal(f.read())
        assertEquals(expected, resumed.read())
        assertEquals(copied, resumed.verifySnapshot(intent.id, context))
        assertEquals(before, tree(f.base))
    }

    @Test fun missingEnrollmentReadNeverCreatesARegistration() {
        val f = Fixture()
        val before = tree(f.base)
        refused { f.read() }
        assertEquals(before, tree(f.base))
        assertFalse(f.slot.exists())
    }

    @Test fun enrollmentCannotOverwriteAnExistingSlot() {
        val f = Fixture()
        f.create()
        val before = tree(f.base)
        refused { f.create() }
        assertEquals(before, tree(f.base))
    }

    @Test fun missingMetadataRemainsMissingWithoutReenrollment() {
        for (path in listOf("intent.json", "enrollment.json", "authority", "authority/state.sqlite", "authority/custody")) {
            val f = Fixture()
            f.create()
            val entry = File(f.slot, path)
            Files.move(entry.toPath(), File(f.base, "retained").toPath())
            val before = tree(f.base)
            refused { f.read() }
            refused { f.create() }
            assertEquals(before, tree(f.base))
            assertFalse(entry.exists())
        }
    }

    @Test fun missingWholeSlotIsNotRecreatedByRestartRead() {
        val f = Fixture()
        f.create()
        Files.move(f.slot.toPath(), File(f.base, "retained-slot").toPath())
        val before = tree(f.base)
        refused { f.read() }
        assertEquals(before, tree(f.base))
        assertFalse(f.slot.exists())
    }

    @Test fun corruptOrUnsupportedReceiptIsPreserved() {
        for (path in listOf("intent.json", "enrollment.json")) {
            for (change in listOf("broken", "version", "unknown", "oversized")) {
                val f = Fixture()
                f.create()
                val entry = File(f.slot, path)
                entry.writeText(when (change) {
                    "broken" -> "{broken"
                    "version" -> entry.readText().replace("\"version\":1", "\"version\":7")
                    "unknown" -> entry.readText().dropLast(1) + ",\"future\":true}"
                    else -> "x".repeat(20_000)
                })
                val before = tree(f.base)
                refused { f.read() }
                assertEquals(before, tree(f.base))
            }
        }
    }

    @Test fun wrongWorkspaceOrProjectCannotUseAnEnrollment() {
        val f = Fixture()
        f.create()
        val other = File(f.base, "other-project").apply { check(mkdir()) }
        val before = tree(f.base)
        refused { f.bootstrap().readExisting(other, id, context) }
        refused { f.bootstrap().readExisting(f.root, "other-id", context) }
        assertEquals(before, tree(f.base))
    }

    @Test fun sameBytesReplacementDatabaseIsNotAdopted() {
        val f = Fixture()
        f.create()
        val retained = File(f.base, "retained.sqlite")
        Files.move(f.database.toPath(), retained.toPath())
        Files.copy(retained.toPath(), f.database.toPath())
        val before = tree(f.base)
        refused { f.read() }
        assertEquals(before, tree(f.base))
    }

    @Test fun databaseCorruptionDoesNotTriggerDeletionOrRecreation() {
        val f = Fixture()
        f.create()
        f.database.writeBytes(ByteArray(8_192) { 71 })
        val before = tree(f.base)
        refused { f.read() }
        assertEquals(before, tree(f.base))
    }

    @Test fun symlinkedMetadataIsNeverFollowed() {
        for (path in listOf("intent.json", "enrollment.json", "authority", "authority/state.sqlite", "authority/custody")) {
            val f = Fixture()
            f.create()
            val entry = File(f.slot, path)
            val retained = File(f.base, "retained")
            Files.move(entry.toPath(), retained.toPath())
            Files.createSymbolicLink(entry.toPath(), retained.toPath())
            val before = tree(f.base)
            refused { f.read() }
            assertEquals(before, tree(f.base))
        }
    }

    @Test fun workspaceLossStillAllowsHistoricalMetadataReadButNotMutation() {
        val f = Fixture()
        val enrollment = f.create()
        val beforeVersion = f.before()
        Files.move(f.root.toPath(), File(f.base, "retained-project").toPath())
        val before = tree(f.base)
        assertEquals(enrollment, f.read())
        val journal = f.journal(enrollment)
        assertNull(journal.readPendingMetadataForRecovery())
        refused { journal.admit("file.txt", beforeVersion,
            CanonicalFileOperationJournal.Content(0, CanonicalJournalFixture.sha256(byteArrayOf()), 384), context) }
        assertEquals(before, tree(f.base))
    }

    @Test fun rootReplacementDoesNotChangeThePersistedRootIdentity() {
        val f = Fixture()
        val enrollment = f.create()
        Files.move(f.root.toPath(), File(f.base, "retained-project").toPath())
        check(f.root.mkdir())
        assertEquals(enrollment, f.read())
        val before = tree(f.base)
        refused { f.journal(f.read()).read() }
        assertEquals(before, tree(f.base))
    }

    @Test fun authorityInsideWorkspaceIsRejectedWithoutCreatingFiles() {
        val f = Fixture()
        val before = tree(f.base)
        refused { CanonicalJournalBootstrap(File(f.root, "authority"), limit).createNew(f.root, id, context) }
        assertEquals(before, tree(f.base))
    }

    @Test fun interruptionAtEveryEnrollmentBoundaryPreservesWorkspaceAndRefusesRecreate() {
        val boundaries = listOf("slot-created", "intent.json-before-force", "intent.json-after-force", "authority-created",
            "database-reserved", "schema-before-commit", "database-committed", "enrollment.json-before-force",
            "enrollment.json-after-force", "enrollment-complete")
        for (boundary in boundaries) {
            val f = Fixture()
            var reached = false
            refused { f.bootstrap { if (it == boundary) { reached = true; throw IOException("injected $boundary") } }
                .createNew(f.root, id, context) }
            assertTrue(boundary, reached)
            assertEquals("unpublished user bytes", f.source.readText())
            val before = tree(f.base)
            val completeReceipt = boundary.startsWith("enrollment.json-") || boundary == "enrollment-complete"
            if (completeReceipt) assertNotNull(f.read()) else refused { f.read() }
            refused { f.create() }
            assertEquals(before, tree(f.base))
        }
    }

    @Test fun cancellationBeforeAdmissionDoesNotCreateASlot() {
        val f = Fixture()
        val cancelled = Job().apply { cancel() }
        val before = tree(f.base)
        refused { f.bootstrap().createNew(f.root, id, cancelled) }
        assertEquals(before, tree(f.base))
    }

    @Test fun concurrentEnrollmentHasExactlyOneWinnerAndDoesNotResetTheWinner() {
        val f = Fixture()
        val pool = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        try {
            val runs = (0..1).map {
                pool.submit<Boolean> {
                    ready.countDown(); check(start.await(10, TimeUnit.SECONDS))
                    runCatching { f.create() }.also {
                        it.exceptionOrNull()?.let(::requireExpectedFailure)
                    }.isSuccess
                }
            }
            try { assertTrue(ready.await(10, TimeUnit.SECONDS)) } finally { start.countDown() }
            assertEquals(1, runs.count { it.get(15, TimeUnit.SECONDS) })
            assertNotNull(f.read())
            assertEquals("unpublished user bytes", f.source.readText())
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    @Test fun replacementSlotDuringCreationCannotReceiveEnrollmentFiles() {
        val f = Fixture()
        var reached = false
        refused { f.bootstrap { if (it == "slot-created") {
            reached = true
            Files.move(f.slot.toPath(), File(f.base, "retained-slot").toPath())
            check(f.slot.mkdir())
            File(f.slot, "foreign.txt").writeText("external user bytes")
        } }.createNew(f.root, id, context) }
        assertTrue(reached)
        assertEquals(listOf("foreign.txt"), f.slot.list()!!.toList())
        assertEquals("external user bytes", File(f.slot, "foreign.txt").readText())
    }

    @Test fun unexpectedDatabaseContentsBeforeSchemaCreationAreNotModified() {
        val f = Fixture()
        var captured: Map<String, String>? = null
        refused { f.bootstrap { if (it == "database-reserved") {
            SQLiteDatabase.openDatabase(f.database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("CREATE TABLE user_data(value TEXT)")
                db.execSQL("INSERT INTO user_data VALUES('external user bytes')")
            }
            captured = tree(f.base)
        } }.createNew(f.root, id, context) }
        assertNotNull(captured)
        assertEquals(captured, tree(f.base))
    }

    @Test fun recordReplacementWhileWritingCannotOverwriteTheReplacement() {
        for (name in listOf("intent.json", "enrollment.json")) {
            val f = Fixture()
            var reached = false
            refused { f.bootstrap { if (it == "$name-before-force") {
                reached = true
                val file = File(f.slot, name)
                Files.move(file.toPath(), File(f.base, "retained-record").toPath())
                file.writeText("external record")
            } }.createNew(f.root, id, context) }
            assertTrue(reached)
            assertEquals("external record", File(f.slot, name).readText())
            assertTrue(File(f.base, "retained-record").isFile)
        }
    }

    @Test fun foreignReceiptCreatedBeforePublicationIsNeverOverwritten() {
        val f = Fixture()
        var reached = false
        refused { f.bootstrap { if (it == "database-committed") {
            reached = true
            File(f.slot, "enrollment.json").writeText("external user record")
        } }.createNew(f.root, id, context) }
        assertTrue(reached)
        assertEquals("external user record", File(f.slot, "enrollment.json").readText())
        val before = tree(f.base)
        refused { f.read() }
        assertEquals(before, tree(f.base))
    }

    @Test fun replacementEmptyDatabaseIsRejectedBeforeSqliteCanWriteToIt() {
        val f = Fixture()
        var captured: Map<String, String>? = null
        refused { f.bootstrap { if (it == "database-reserved") {
            Files.move(f.database.toPath(), File(f.base, "retained.sqlite").toPath())
            f.database.writeBytes(byteArrayOf())
            captured = tree(f.base)
        } }.createNew(f.root, id, context) }
        assertNotNull(captured)
        assertEquals(captured, tree(f.base))
        assertEquals(0L, f.database.length())
    }

    @Test fun cancellationDuringSchemaTransactionKeepsEvidenceAndDoesNotPublishReceipt() {
        val f = Fixture()
        val job = Job()
        val failure = refused { f.bootstrap { if (it == "schema-before-commit") job.cancel() }
            .createNew(f.root, id, job) }
        assertTrue(failure is CancellationException)
        assertFalse(File(f.slot, "enrollment.json").exists())
        assertEquals("unpublished user bytes", f.source.readText())
        val before = tree(f.base)
        refused { f.read() }
        assertEquals(before, tree(f.base))
    }

    @Test fun explicitRecoveryOfCommittedSchemaOnlyAddsItsMissingReceipt() {
        val f = Fixture()
        f.interruptAt("database-committed")
        refused { f.read() }
        val before = tree(f.base)
        val enrollment = f.recover()
        val after = tree(f.base)
        assertEquals(before, after - "host/registration/enrollment.json")
        assertEquals(before.size + 1, after.size)
        assertEquals(enrollment, f.read())
        assertEquals(enrollment, f.recover())
        assertNull(f.journal(enrollment).read())
        assertEquals(after, tree(f.base))
    }

    @Test fun explicitRecoveryCannotInitializeAnIncompleteAuthority() {
        for (boundary in listOf("slot-created", "intent.json-before-force", "intent.json-after-force",
            "authority-created", "database-reserved", "schema-before-commit")) {
            val f = Fixture()
            f.interruptAt(boundary)
            val before = tree(f.base)
            refused { f.recover() }
            assertEquals(boundary, before, tree(f.base))
            assertFalse(File(f.slot, "enrollment.json").exists())
        }
    }

    @Test fun readOnlyInspectionPreservesEmptyAndPendingAuthorityBytes() {
        val f = Fixture()
        val journal = f.journal(f.create())
        val empty = tree(f.base)
        assertNull(journal.inspectPendingMetadataForRecovery())
        assertEquals(empty, tree(f.base))
        val intent = journal.admit(f.source.name, f.before(),
            CanonicalFileOperationJournal.Content(0, CanonicalJournalFixture.sha256(byteArrayOf()), 384), context)
        journal.capture(intent.id, context)
        val expected = journal.read()
        val pending = tree(f.base)
        repeat(3) { assertEquals(expected, journal.inspectPendingMetadataForRecovery()) }
        assertEquals(pending, tree(f.base))
    }

    @Test fun lostReceiptWithPendingWorkCannotBeReconstructedAsNewEnrollment() {
        val f = Fixture()
        val journal = f.journal(f.create())
        val intent = journal.admit(f.source.name, f.before(),
            CanonicalFileOperationJournal.Content(0, CanonicalJournalFixture.sha256(byteArrayOf()), 384), context)
        val captured = journal.capture(intent.id, context)
        Files.move(File(f.slot, "enrollment.json").toPath(), File(f.base, "retained-receipt").toPath())
        val before = tree(f.base)
        refused { f.recover() }
        assertEquals(captured, journal.verifySnapshot(intent.id, context))
        assertEquals(before, tree(f.base))
    }

    @Test fun explicitRecoveryPreservesForeignAndCorruptDatabaseEvidence() {
        for (change in listOf("missing", "replacement", "corrupt", "symlink", "foreign-schema")) {
            val f = Fixture()
            f.interruptAt("database-committed")
            val retained = File(f.base, "retained.sqlite")
            Files.move(f.database.toPath(), retained.toPath())
            when (change) {
                "replacement" -> Files.copy(retained.toPath(), f.database.toPath())
                "corrupt" -> f.database.writeBytes(ByteArray(8_192) { 71 })
                "symlink" -> Files.createSymbolicLink(f.database.toPath(), retained.toPath())
                "foreign-schema" -> SQLiteDatabase.openOrCreateDatabase(f.database, null).use {
                    it.execSQL("CREATE TABLE user_data(value TEXT)")
                    it.execSQL("INSERT INTO user_data VALUES('external user bytes')")
                }
            }
            val before = tree(f.base)
            refused { f.recover() }
            assertEquals(change, before, tree(f.base))
        }
    }

    @Test fun receiptCreatedDuringExplicitRecoveryIsPreserved() {
        val f = Fixture()
        f.interruptAt("database-committed")
        var captured: Map<String, String>? = null
        refused { f.bootstrap { if (it == "enrollment-recovery-inspected") {
            File(f.slot, "enrollment.json").writeText("external record")
            captured = tree(f.base)
        } }.recoverCommittedEnrollment(f.root, id, context) }
        assertNotNull(captured)
        assertEquals(captured, tree(f.base))
        refused { f.recover() }
        assertEquals(captured, tree(f.base))
    }

    @Test fun databaseReplacedAfterReadOnlyInspectionCannotReceiveRecoveryWrites() {
        val f = Fixture()
        f.interruptAt("database-committed")
        var captured: Map<String, String>? = null
        refused { f.bootstrap { if (it == "enrollment-recovery-inspected") {
            Files.move(f.database.toPath(), File(f.base, "retained.sqlite").toPath())
            f.database.writeText("external database")
            captured = tree(f.base)
        } }.recoverCommittedEnrollment(f.root, id, context) }
        assertNotNull(captured)
        assertEquals(captured, tree(f.base))
    }

    @Test fun explicitRecoveryRefusesReplacedAuthorityAndMissingReservation() {
        for (path in listOf("intent.json", "authority", "authority/custody", "")) {
            val f = Fixture()
            f.interruptAt("database-committed")
            val entry = if (path.isEmpty()) f.slot else File(f.slot, path)
            Files.move(entry.toPath(), File(f.base, "retained").toPath())
            if (path.endsWith("authority") || path.endsWith("custody")) check(entry.mkdir())
            val before = tree(f.base)
            refused { f.recover() }
            assertEquals(path, before, tree(f.base))
        }
    }

    @Test fun concurrentExplicitRecoveryNeverReplacesAnEnrollmentReceipt() {
        val f = Fixture()
        f.interruptAt("database-committed")
        val before = tree(f.base)
        val pool = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        try {
            val runs = (0..1).map {
                pool.submit<CanonicalFileOperationJournal.Enrollment?> {
                    ready.countDown(); check(start.await(10, TimeUnit.SECONDS))
                    runCatching { f.recover() }.also {
                        it.exceptionOrNull()?.let(::requireExpectedFailure)
                    }.getOrNull()
                }
            }
            try { assertTrue(ready.await(10, TimeUnit.SECONDS)) } finally { start.countDown() }
            val results = runs.mapNotNull { it.get(15, TimeUnit.SECONDS) }
            assertTrue(results.isNotEmpty())
            val enrollment = f.read()
            assertTrue(results.all { it == enrollment })
            assertEquals(before, tree(f.base) - "host/registration/enrollment.json")
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    @Test fun cancellationDuringExplicitRecoveryDoesNotResetEvidence() {
        for (boundary in listOf("enrollment-recovery-inspected", "enrollment-recovery-forced", "enrollment.json-before-force")) {
            val f = Fixture()
            f.interruptAt("database-committed")
            val before = tree(f.base)
            val job = Job()
            val failure = refused { f.bootstrap { if (it == boundary) job.cancel() }
                .recoverCommittedEnrollment(f.root, id, job) }
            assertTrue(failure is CancellationException)
            if (boundary == "enrollment.json-before-force") {
                assertNotNull(f.read())
                assertEquals(before, tree(f.base) - "host/registration/enrollment.json")
            } else {
                refused { f.read() }
                assertEquals(before, tree(f.base))
            }
        }
    }

    @Test fun explicitRecoveryPreservesHistoricalRootBinding() {
        val f = Fixture()
        f.interruptAt("database-committed")
        Files.move(f.root.toPath(), File(f.base, "retained-project").toPath())
        val before = tree(f.base)
        val enrollment = f.recover()
        assertNull(f.journal(enrollment).inspectPendingMetadataForRecovery())
        refused { f.journal(enrollment).read() }
        assertEquals(before, tree(f.base) - "host/registration/enrollment.json")
        check(f.root.mkdir())
        refused { f.journal(f.recover()).read() }
    }

    @Test fun explicitRecoveryRejectsForeignProjectIdentityWithoutChangingEvidence() {
        val f = Fixture()
        f.interruptAt("database-committed")
        val other = File(f.base, "other-root").apply { check(mkdir()) }
        val before = tree(f.base)
        refused { f.bootstrap().recoverCommittedEnrollment(f.root, "other-id", context) }
        refused { f.bootstrap().recoverCommittedEnrollment(other, id, context) }
        assertEquals(before, tree(f.base))
    }

    @Test fun unsupportedWalRecoveryDoesNotCreateSidecars() = verifyUnexpectedWal(readOnly = true)

    @Test fun ordinaryJournalReadDoesNotConvertAnUnexpectedWalDatabase() = verifyUnexpectedWal(readOnly = false)

    private fun verifyUnexpectedWal(readOnly: Boolean) {
        val f = Fixture()
        val enrollment = f.create()
        val parameters = SQLiteDatabase.OpenParams.Builder()
            .setOpenFlags(SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
            .setJournalMode("WAL").setSynchronousMode("EXTRA").build()
        SQLiteDatabase.openDatabase(f.database, parameters).use {
            it.rawQuery("PRAGMA journal_mode", null).use { row -> assertTrue(row.moveToFirst()); assertEquals("wal", row.getString(0)) }
        }
        assertEquals(2.toByte(), f.database.readBytes()[18])
        if (readOnly) Files.move(File(f.slot, "enrollment.json").toPath(), File(f.base, "retained-receipt").toPath())
        val before = tree(f.base)
        if (readOnly) refused { f.recover() } else refused { f.journal(enrollment).read() }
        assertEquals(before, tree(f.base))
    }

    @Test fun unexpectedWalSidecarsArePreservedBeforeEitherKindOfDatabaseOpen() {
        for (suffix in listOf("-wal", "-shm")) {
            val f = Fixture()
            val enrollment = f.create()
            File(f.authority, "state.sqlite$suffix").writeText("external evidence")
            val before = tree(f.base)
            refused { f.journal(enrollment).inspectPendingMetadataForRecovery() }
            refused { f.journal(enrollment).read() }
            assertEquals(before, tree(f.base))
        }
    }

    @Test fun explicitRecoveryCannotConsumeAnUnresolvedRollbackJournal() {
        val f = Fixture()
        f.interruptAt("database-committed")
        File(f.authority, "state.sqlite-journal").writeText("unresolved transaction evidence")
        val before = tree(f.base)
        refused { f.recover() }
        assertEquals(before, tree(f.base))
    }

    private fun refused(action: () -> Any?): Throwable = runCatching(action).exceptionOrNull()
        ?.also(::requireExpectedFailure)
        ?: throw AssertionError("Expected fail-closed enrollment")

    private fun requireExpectedFailure(failure: Throwable) {
        assertTrue("Unexpected enrollment failure: $failure", failure is IOException ||
            failure is IllegalStateException || failure is IllegalArgumentException || failure is SQLiteException)
    }

    private fun tree(root: File): Map<String, String> {
        val result = sortedMapOf<String, String>()
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                result[root.toPath().relativize(dir).toString()] = "directory:${attrs.fileKey()}"
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                result[root.toPath().relativize(file).toString()] = if (attrs.isSymbolicLink)
                    "link:${Files.readSymbolicLink(file)}" else "${attrs.fileKey()}:${CanonicalJournalFixture.sha256(Files.readAllBytes(file))}"
                return FileVisitResult.CONTINUE
            }
        })
        return result
    }
}
