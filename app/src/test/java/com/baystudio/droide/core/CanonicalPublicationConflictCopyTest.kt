package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CanonicalPublicationConflictCopyTest {
    private val context = EmptyCoroutineContext
    private val conflictBytes = ByteArray(90_000) { (it % 193).toByte() }

    @Test fun copiesEachRecordedRoleIntoIndependentCustodyWithoutChangingExistingData() {
        for (role in CanonicalFileOperationJournal.ConflictRole.entries) fixture { f ->
            val i = conflict(f, role); val prior = tree(f.base, true)
            val copy = f.journal().capturePublicationConflict(i.id, context)
            assertEquals(i.publicationConflict!!.observed, copy.copy(identity = i.publicationConflict.observed!!.identity))
            assertNotEquals(i.publicationConflict.observed.identity, copy.identity)
            assertArrayEquals(conflictBytes, copyFile(f, i).readBytes())
            assertEquals(prior, tree(f.base, true) - copyFile(f, i).path)
            assertEquals(copy, f.journal().read()!!.publicationConflictCopy)
            assertEquals(i.publicationConflict, f.journal().read()!!.publicationConflict)
            assertEquals(i.phase, f.journal().read()!!.phase)
            refused { f.journal().verifyPublication(i.id, context) }
            protectedCopies(f, i)
        }
    }

    @Test fun heldPreRetirementWriterCannotChangeTheIndependentConflictCopy() = fixture { f ->
        RandomAccessFile(f.source, "rw").use { writer ->
            val i = prepare(f)
            writer.seek(0); writer.write(conflictBytes); writer.setLength(conflictBytes.size.toLong()); writer.fd.sync()
            f.journal().recordPublicationConflict(i.id, context)
            val copy = f.journal().capturePublicationConflict(i.id, context)
            writer.seek(0); writer.write("later held writer".toByteArray()); writer.fd.sync()
            assertArrayEquals(conflictBytes, copyFile(f, i).readBytes())
            assertTrue(File(f.custody, i.retiredName).readText().startsWith("later held writer"))
            assertEquals(copy, f.journal().verifyPublicationConflictCopy(i.id, context))
            assertArrayEquals(f.proposedBytes, f.source.readBytes()); protectedCopies(f, i)
        }
    }

    @Test fun repeatsVerifyHistoricalCopyWithoutRewritingOrRecapturingNewerSource() = fixture { f ->
        val i = conflict(f); val copy = f.journal().capturePublicationConflict(i.id, context)
        f.source.writeText("newer unreviewed work"); val before = tree(f.base)
        repeat(3) { assertEquals(copy, f.journal(checkpoint = { error("Unexpected rewrite: $it") }).capturePublicationConflict(i.id, context)) }
        assertEquals(before, tree(f.base)); assertArrayEquals(conflictBytes, copyFile(f, i).readBytes())
        assertEquals("newer unreviewed work", f.source.readText())
        refused { f.journal().recordPublicationCompletion(i.id, context) }
    }

    @Test fun copyCanBeRecordedAfterHistoricalCompletionAndExplicitRecovery() {
        for (phase in listOf("completed", "recovered")) fixture { f ->
            val i = conflict(f, phase = phase); val copy = f.journal().capturePublicationConflict(i.id, context)
            val stored = f.journal().read()!!
            assertEquals(i.phase, stored.phase); assertEquals(i.publication, stored.publication)
            assertEquals(i.publicationRecovery, stored.publicationRecovery); assertEquals(copy, stored.publicationConflictCopy)
            assertArrayEquals(conflictBytes, copyFile(f, i).readBytes())
            refused { f.journal().recoverPublishedState(i.id, context) }
        }
    }

    @Test fun absentOrForeignConflictCannotCreateCustody() = fixture { f ->
        val i = prepare(f); val before = tree(f.base)
        refused { f.journal().capturePublicationConflict(i.id, context) }
        refused { f.journal().capturePublicationConflict(UUID.randomUUID().toString(), context) }
        refused { f.journal().verifyPublicationConflictCopy(i.id, context) }
        assertEquals(before, tree(f.base))
    }

    @Test fun nonRegularConflictKindsArePreservedAndRefused() {
        for (kind in listOf("absent", "directory", "symlink")) fixture { f ->
            val i = prepare(f); val saved = File(f.base, "published-preserved")
            Files.move(f.source.toPath(), saved.toPath())
            when (kind) {
                "directory" -> { check(f.source.mkdir()); File(f.source, "work").writeText("kept") }
                "symlink" -> Files.createSymbolicLink(f.source.toPath(), saved.toPath())
            }
            f.journal().recordPublicationConflict(i.id, context); val before = tree(f.base)
            refused { f.journal().capturePublicationConflict(i.id, context) }
            assertEquals(before, tree(f.base)); assertFalse(copyFile(f, i).exists())
        }
    }

    @Test fun unsupportedObservedPermissionsAreRefusedWithoutWideningOrCopying() = fixture { f ->
        val i = prepare(f); Files.setAttribute(f.source.toPath(), "unix:mode", 292)
        f.journal().recordPublicationConflict(i.id, context); val before = tree(f.base)
        refused { f.journal().capturePublicationConflict(i.id, context) }
        assertEquals(before, tree(f.base)); assertFalse(copyFile(f, i).exists())
    }

    @Test fun changedOrReplacedSourceCannotBeCapturedAsTheRecordedVersion() {
        for (replace in listOf(false, true)) fixture { f ->
            val i = conflict(f)
            if (replace) { Files.move(f.source.toPath(), File(f.base, "saved").toPath()); CanonicalJournalFixture.write(f.source, conflictBytes) }
            else f.source.writeText("later edit")
            val before = tree(f.base)
            refused { f.journal().capturePublicationConflict(i.id, context) }
            assertEquals(before, tree(f.base)); assertFalse(copyFile(f, i).exists())
        }
    }

    @Test fun preexistingDestinationKindsCannotBeOverwritten() {
        for (kind in listOf("file", "directory", "symlink")) fixture { f ->
            val i = conflict(f); val copy = copyFile(f, i)
            when (kind) {
                "file" -> copy.writeText("unrecorded work")
                "directory" -> { check(copy.mkdir()); File(copy, "work").writeText("unrecorded work") }
                "symlink" -> Files.createSymbolicLink(copy.toPath(), f.source.toPath())
            }
            val before = tree(f.base)
            refused { f.journal().capturePublicationConflict(i.id, context) }
            assertEquals(before, tree(f.base)); assertNull(f.journal().read()!!.publicationConflictCopy)
        }
    }

    @Test fun destinationCreationRaceUsesExclusiveCreationAndPreservesTheWinner() = fixture { f ->
        val i = conflict(f)
        refused { f.journal(checkpoint = { if (it == "conflict-copy-OPENED") copyFile(f, i).writeText("other writer") })
            .capturePublicationConflict(i.id, context) }
        assertEquals("other writer", copyFile(f, i).readText()); assertArrayEquals(conflictBytes, f.source.readBytes())
        assertNull(f.journal().read()!!.publicationConflictCopy); protectedCopies(f, i)
    }

    @Test fun sourceSymlinkReplacementDuringOpenDoesNotFollowTheNewTarget() = fixture { f ->
        val i = conflict(f); val outside = File(f.base, "outside").apply { writeText("outside work") }
        refused { f.journal(checkpoint = { if (it == "conflict-copy-OPENED") {
            Files.move(f.source.toPath(), File(f.base, "saved-conflict").toPath())
            Files.createSymbolicLink(f.source.toPath(), outside.toPath())
        } }).capturePublicationConflict(i.id, context) }
        assertEquals("outside work", outside.readText()); assertFalse(copyFile(f, i).exists())
        assertArrayEquals(conflictBytes, File(f.base, "saved-conflict").readBytes())
        assertNull(f.journal().read()!!.publicationConflictCopy)
    }

    @Test fun concurrentWriterDuringStreamingPreservesPartialCustodyAndRefusesReceipt() = fixture { f ->
        val i = conflict(f); val started = CountDownLatch(1); val finished = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val work = executor.submit {
                check(started.await(20, TimeUnit.SECONDS))
                RandomAccessFile(f.source, "rw").use { it.seek(80_000); it.write(255); it.fd.sync() }
                finished.countDown()
            }
            refused { f.journal(checkpoint = { if (it == "conflict-copy-COPIED_BLOCK") {
                started.countDown(); check(finished.await(20, TimeUnit.SECONDS))
            } }).capturePublicationConflict(i.id, context) }
            work.get(20, TimeUnit.SECONDS)
            assertNull(f.journal().read()!!.publicationConflictCopy); assertTrue(copyFile(f, i).length() > 0)
            assertEquals(255, f.source.readBytes()[80_000].toInt() and 255)
            val preserved = tree(f.base); refused { f.journal().capturePublicationConflict(i.id, context) }
            assertEquals(preserved, tree(f.base)); protectedCopies(f, i)
        } finally { started.countDown(); executor.shutdownNow(); check(executor.awaitTermination(20, TimeUnit.SECONDS)) }
    }

    @Test fun changedCopyBeforeMetadataCommitCannotObtainAValidReceipt() = fixture { f ->
        val i = conflict(f)
        refused { f.journal(checkpoint = { if (it == "conflict-copy-captured") copyFile(f, i).writeText("external copy edit") })
            .capturePublicationConflict(i.id, context) }
        assertNull(f.journal().read()!!.publicationConflictCopy); assertEquals("external copy edit", copyFile(f, i).readText())
        assertArrayEquals(conflictBytes, f.source.readBytes()); protectedCopies(f, i)
    }

    @Test fun lastCommitGapCopyChangeIsReportedByPostcommitVerification() = fixture { f ->
        val i = conflict(f)
        refused { f.journal(checkpoint = { if (it == "conflict-copy-before-commit") copyFile(f, i).writeText("last gap edit") })
            .capturePublicationConflict(i.id, context) }
        assertNotNull(f.journal().read()!!.publicationConflictCopy)
        assertEquals("last gap edit", copyFile(f, i).readText()); assertArrayEquals(conflictBytes, f.source.readBytes())
        refused { f.journal().verifyPublicationConflictCopy(i.id, context) }; protectedCopies(f, i)
    }

    @Test fun laterSourceEditDuringCommitDoesNotInvalidateHistoricalCustodyOrResolveConflict() = fixture { f ->
        val i = conflict(f)
        val copy = f.journal(checkpoint = { if (it == "conflict-copy-before-commit") f.source.writeText("newer CLI edit") })
            .capturePublicationConflict(i.id, context)
        assertArrayEquals(conflictBytes, copyFile(f, i).readBytes()); assertEquals("newer CLI edit", f.source.readText())
        assertEquals(copy, f.journal().verifyPublicationConflictCopy(i.id, context))
        assertEquals(i.publicationConflict, f.journal().read()!!.publicationConflict)
        refused { f.journal().recordPublicationCompletion(i.id, context) }
    }

    @Test fun cancellationPreservesCopyAndPreventsUncommittedReceipt() {
        for (point in listOf("start", "conflict-copy-CREATED", "conflict-copy-COPIED_BLOCK", "conflict-copy-FORCED", "conflict-copy-before-commit")) fixture { f ->
            val i = conflict(f); val job = Job(); if (point == "start") job.cancel()
            assertTrue(refused { f.journal(checkpoint = { if (it == point) job.cancel() }).capturePublicationConflict(i.id, job) } is CancellationException)
            assertNull(f.journal().read()!!.publicationConflictCopy); assertArrayEquals(conflictBytes, f.source.readBytes())
            if (point != "start") assertTrue(copyFile(f, i).exists())
            protectedCopies(f, i)
        }
    }

    @Test fun rollbackDoesNotDeleteCompletedButUnrecordedCopy() = fixture { f ->
        val i = conflict(f); val error = IOException("interrupted metadata commit")
        assertSame(error, refused { f.journal(checkpoint = { if (it == "conflict-copy-before-commit") throw error }).capturePublicationConflict(i.id, context) })
        assertNull(f.journal().read()!!.publicationConflictCopy); assertArrayEquals(conflictBytes, copyFile(f, i).readBytes())
        val preserved = tree(f.base); refused { f.journal().capturePublicationConflict(i.id, context) }
        assertEquals(preserved, tree(f.base))
    }

    @Test fun actualSqlUpdateRejectionLeavesCopyAvailableWithoutClaimingDiskFull() = fixture { f ->
        val i = conflict(f)
        f.sql { it.execSQL("CREATE TRIGGER reject_copy BEFORE UPDATE ON operation BEGIN SELECT RAISE(ABORT, 'test commit refusal'); END") }
        refused { f.journal().capturePublicationConflict(i.id, context) }
        assertNull(f.journal().read()!!.publicationConflictCopy); assertArrayEquals(conflictBytes, copyFile(f, i).readBytes())
        assertArrayEquals(conflictBytes, f.source.readBytes()); protectedCopies(f, i)
    }

    @Test fun lostReturnAfterCommitCanBeReadAndVerifiedWithoutWritingAgain() = fixture { f ->
        val i = conflict(f)
        refused { f.journal(checkpoint = { if (it == "conflict-copy-after-commit") throw IOException("lost return") }).capturePublicationConflict(i.id, context) }
        val stored = f.journal().read()!!.publicationConflictCopy!!; val before = tree(f.base)
        assertEquals(stored, f.journal(checkpoint = { error("Unexpected retry mutation") }).capturePublicationConflict(i.id, context))
        assertEquals(before, tree(f.base))
    }

    @Test fun damagedMissingAndReplacedRecordedCopiesFailClosedWithoutRepair() {
        for (kind in listOf("edit", "absent", "replacement", "symlink")) fixture { f ->
            val i = conflict(f); f.journal().capturePublicationConflict(i.id, context)
            val copy = copyFile(f, i)
            if (kind == "edit") copy.writeText("changed copy") else {
                Files.move(copy.toPath(), File(f.base, "saved-copy").toPath())
                if (kind == "replacement") CanonicalJournalFixture.write(copy, conflictBytes)
                if (kind == "symlink") Files.createSymbolicLink(copy.toPath(), f.source.toPath())
            }
            val before = tree(f.base)
            refused { f.journal().verifyPublicationConflictCopy(i.id, context) }
            refused { f.journal().capturePublicationConflict(i.id, context) }
            assertEquals(before, tree(f.base))
        }
    }

    @Test fun metadataReceiptSurvivesMissingSourceRootWithoutRecreatingIt() = fixture { f ->
        val i = conflict(f); val copy = f.journal().capturePublicationConflict(i.id, context)
        Files.move(f.root.toPath(), File(f.base, "moved-project").toPath())
        val before = tree(f.base)
        assertEquals(copy, f.journal().readPendingMetadataForRecovery()!!.publicationConflictCopy)
        refused { f.journal().verifyPublicationConflictCopy(i.id, context) }
        assertEquals(before, tree(f.base)); assertFalse(f.root.exists())
    }

    @Test fun parentReplacementBeforeCommitPreservesBothTreesAndRollsBackReceipt() = fixture { f ->
        val i = conflict(f); val parent = f.source.parentFile; val saved = File(f.base, "saved-parent")
        refused { f.journal(checkpoint = { if (it == "conflict-copy-before-commit") {
            Files.move(parent.toPath(), saved.toPath()); check(parent.mkdir()); f.source.writeText("new directory work")
        } }).capturePublicationConflict(i.id, context) }
        assertNull(f.journal().readPendingMetadataForRecovery()!!.publicationConflictCopy)
        assertArrayEquals(conflictBytes, File(saved, f.source.name).readBytes())
        assertEquals("new directory work", f.source.readText()); assertArrayEquals(conflictBytes, copyFile(f, i).readBytes())
    }

    @Test fun concurrentCaptureCannotReplaceAnotherCallersNewCopy() = fixture { f ->
        val i = conflict(f); val created = CountDownLatch(1); val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<CanonicalFileSnapshot.Version> {
                f.journal(checkpoint = { if (it == "conflict-copy-CREATED") {
                    created.countDown(); check(release.await(20, TimeUnit.SECONDS))
                } }).capturePublicationConflict(i.id, context)
            }
            assertTrue(created.await(20, TimeUnit.SECONDS)); val identity = CanonicalJournalFixture.key(copyFile(f, i))
            refused { f.journal().capturePublicationConflict(i.id, context) }
            assertEquals(identity, CanonicalJournalFixture.key(copyFile(f, i)))
            release.countDown(); assertEquals(first.get(20, TimeUnit.SECONDS), f.journal().read()!!.publicationConflictCopy)
            assertArrayEquals(conflictBytes, copyFile(f, i).readBytes())
        } finally { release.countDown(); executor.shutdownNow(); check(executor.awaitTermination(20, TimeUnit.SECONDS)) }
    }

    @Test fun invalidConflictCopyReceiptsAreRejectedWithoutNormalizingMetadata() {
        for (bad in listOf("null", "alias-observed", "alias-original", "wrong-content", "missing-conflict", "bad-placeholder", "extra-field")) fixture { f ->
            val i = conflict(f); f.journal().capturePublicationConflict(i.id, context)
            rewrite(f) { fields ->
                when (bad) {
                    "null" -> fields[14] = JsonNull
                    "alias-observed" -> fields[14] = fields[12].jsonArray[2]
                    "alias-original" -> fields[14] = fields[6]
                    "wrong-content" -> { val copy = fields[14].jsonArray.toMutableList(); copy[2] = JsonPrimitive("0".repeat(64)); fields[14] = JsonArray(copy) }
                    "missing-conflict" -> fields[12] = JsonNull
                    "bad-placeholder" -> fields[13] = fields[14]
                    "extra-field" -> fields += JsonPrimitive("unexpected")
                }
            }
            val before = tree(f.base); refused { f.journal().readPendingMetadataForRecovery() }
            assertEquals(before, tree(f.base))
        }
    }

    private fun prepare(f: CanonicalJournalFixture, phase: String = "published"): CanonicalFileOperationJournal.Intent {
        val i = f.journal().admit(f.path, f.before(), f.proposal(), context); f.journal().capture(i.id, context)
        val input = File(f.base, "input"); CanonicalJournalFixture.write(input, f.proposedBytes)
        AnchoredWorkspaceFiles(f.base).use { f.journal().captureProposal(i.id, it, input.name, f.before(input), context) }
        f.journal().stagePublication(i.id, context); f.journal().retireCanonical(i.id, context); f.journal().recordRetainedSnapshot(i.id, context)
        if (phase == "recovered") {
            refused { f.journal(checkpoint = { if (it == "publication-after-move") throw IOException("lost native outcome") }).publishStaged(i.id, context) }
            f.journal().recoverPublishedState(i.id, context)
        } else f.journal().publishStaged(i.id, context)
        if (phase == "completed") f.journal().recordPublicationCompletion(i.id, context)
        return f.journal().read()!!
    }
    private fun conflict(f: CanonicalJournalFixture, role: CanonicalFileOperationJournal.ConflictRole = CanonicalFileOperationJournal.ConflictRole.CANONICAL,
                         phase: String = "published"): CanonicalFileOperationJournal.Intent {
        val i = prepare(f, phase)
        val target = when (role) {
            CanonicalFileOperationJournal.ConflictRole.RETIRED -> File(f.custody, i.retiredName)
            CanonicalFileOperationJournal.ConflictRole.STAGE -> File(f.custody, i.publicationName)
            CanonicalFileOperationJournal.ConflictRole.CANONICAL -> f.source
        }
        CanonicalJournalFixture.write(target, conflictBytes)
        f.journal().recordPublicationConflict(i.id, context)
        return f.journal().read()!!
    }
    private fun protectedCopies(f: CanonicalJournalFixture, i: CanonicalFileOperationJournal.Intent) {
        assertArrayEquals(f.bytes, f.snapshotFile(i.id).readBytes()); assertArrayEquals(f.proposedBytes, File(f.custody, i.proposalName).readBytes())
    }
    private fun copyFile(f: CanonicalJournalFixture, i: CanonicalFileOperationJournal.Intent) = File(f.custody, i.conflictCopyName)
    private fun rewrite(f: CanonicalJournalFixture, edit: (MutableList<JsonElement>) -> Unit) = f.sql { db ->
        val old = db.rawQuery("SELECT record_json FROM operation", null).use { it.moveToFirst(); it.getString(0) }
        val fields = Json.parseToJsonElement(old).jsonArray.toMutableList(); edit(fields); val record = JsonArray(fields).toString()
        db.execSQL("UPDATE operation SET record_json=?", arrayOf(record)); db.execSQL("UPDATE meta SET record_digest=?", arrayOf(CanonicalJournalFixture.sha256(record.toByteArray())))
    }
    private fun fixture(action: (CanonicalJournalFixture) -> Unit) = CanonicalJournalFixture().use(action)
    private fun refused(action: () -> Unit): Exception {
        try { action() } catch (failure: Exception) { return failure }
        throw AssertionError("Expected explicit refusal")
    }
    private fun tree(root: File, dataOnly: Boolean = false): Map<String, String> = Files.walk(root.toPath()).use { paths ->
        paths.filter { !Files.isDirectory(it, NOFOLLOW_LINKS) && (!dataOnly || !it.fileName.toString().startsWith("state.sqlite")) }.toList().associate { p ->
            p.toString() to if (Files.isSymbolicLink(p)) "link:${Files.readSymbolicLink(p)}" else
                "${CanonicalJournalFixture.key(p.toFile())}:${Files.getLastModifiedTime(p)}:${Files.getAttribute(p, "unix:mode")}:${CanonicalJournalFixture.sha256(Files.readAllBytes(p))}"
        }
    }
}
