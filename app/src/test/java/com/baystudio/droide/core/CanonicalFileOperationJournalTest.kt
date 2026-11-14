package com.baystudio.droide.core

import android.database.sqlite.SQLiteFullException
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Framework SQLite and actual Droide JNI; no fake filesystem or SQL storage. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CanonicalFileOperationJournalTest {
    @Test fun emptyAuthorityReadLeavesTheExplicitEnrollmentAndWorkspaceUnchanged() = fixture { f ->
        val before = tree(f.base)
        repeat(3) { assertNull(f.journal().read()) }
        assertEquals(before, tree(f.base))
        assertCustodyEmpty(f)
    }

    @Test fun orphanCustodyFilePreventsEmptyAuthorityFromAdmittingNewWork() = fixture { f ->
        val orphan = File(f.custody, "unrecorded.before")
        val orphanBytes = byteArrayOf(41, 42, 43, 44)
        CanonicalJournalFixture.write(orphan, orphanBytes)
        val before = tree(f.base)
        refused { f.journal().read() }
        refused { admit(f) }
        assertEquals(before, tree(f.base))
        assertArrayEquals(orphanBytes, orphan.readBytes())
        assertArrayEquals(f.bytes, f.source.readBytes())
    }

    @Test fun coherentMetadataResetCannotHideCustodyLeftByARecordedSnapshot() = fixture { f ->
        val intent = admit(f)
        val captured = f.journal().capture(intent.id, empty)
        f.sql { db ->
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM operation")
                db.execSQL("UPDATE meta SET expected_operation=NULL,record_digest=NULL")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        val before = tree(f.base)
        refused { f.journal().read() }
        refused { admit(f) }
        assertEquals(before, tree(f.base))
        assertEquals(captured.copy.identity, CanonicalJournalFixture.key(f.snapshotFile(intent.id)))
        assertArrayEquals(f.bytes, f.snapshotFile(intent.id).readBytes())
        assertArrayEquals(f.bytes, f.source.readBytes())
    }

    @Test fun durableAdmissionPrecedesAnyCustodyCreationAndBindsEveryNestedParent() = fixture { f ->
        val checkpoints = mutableListOf<String>()
        val before = f.before()
        val journal = f.journal(checkpoint = { at ->
            checkpoints += at
            assertCustodyEmpty(f)
            assertArrayEquals(f.bytes, f.source.readBytes())
        })
        val intent = journal.admit(f.path, before, f.proposal(), empty)
        assertEquals(listOf("admission-before-commit", "admission-after-commit"), checkpoints)
        assertEquals(f.path, intent.path)
        assertEquals(before, intent.before)
        assertEquals(f.proposal(), intent.proposal)
        assertEquals(CanonicalFileOperationJournal.Phase.PREPARED, intent.phase)
        assertNull(intent.snapshot)
        assertEquals("${intent.id}.before", intent.snapshotName)
        assertEquals(listOf(
            CanonicalFileOperationJournal.Parent("", CanonicalJournalFixture.key(f.root)),
            CanonicalFileOperationJournal.Parent("src", CanonicalJournalFixture.key(File(f.root, "src"))),
            CanonicalFileOperationJournal.Parent("src/deep", CanonicalJournalFixture.key(f.source.parentFile)),
        ), intent.parents)
        assertEquals(intent, f.journal().read())
        assertCustodyEmpty(f)
    }

    @Test fun eitherPendingPhaseBlocksASecondAdmissionWithoutOverwritingItsRecord() {
        for (recordSnapshot in listOf(false, true)) fixture { f ->
            val first = admit(f)
            if (recordSnapshot) f.journal().capture(first.id, empty)
            val pending = f.journal().read()
            val before = tree(f.base)
            refused { f.journal().admit(f.path, f.before(), f.proposal(), empty) }
            assertEquals(pending, f.journal().read())
            assertEquals(before, tree(f.base))
        }
    }

    @Test fun snapshotReceiptIsRecordedOnlyAfterCopyForceAndVerification() = fixture { f ->
        val intent = admit(f)
        val checkpoints = mutableListOf<String>()
        val journal = f.journal(checkpoint = { at ->
            checkpoints += at
            if (at.startsWith("snapshot-") && at !in listOf("snapshot-before-commit", "snapshot-after-commit")) {
                assertEquals(intent, f.journal().read())
                if (at in listOf("snapshot-COPIED", "snapshot-FORCED", "snapshot-VERIFIED")) {
                    assertArrayEquals(f.bytes, f.snapshotFile(intent.id).readBytes())
                }
            }
        })
        val capture = journal.capture(intent.id, empty)
        assertTrue(checkpoints.indexOf("snapshot-COPIED") < checkpoints.indexOf("snapshot-FORCED"))
        assertTrue(checkpoints.indexOf("snapshot-FORCED") < checkpoints.indexOf("snapshot-VERIFIED"))
        assertTrue(checkpoints.indexOf("snapshot-VERIFIED") < checkpoints.indexOf("snapshot-before-commit"))
        assertEquals("snapshot-after-commit", checkpoints.last())
        val recorded = checkNotNull(f.journal().read())
        assertEquals(intent.copy(phase = CanonicalFileOperationJournal.Phase.SNAPSHOT_RECORDED,
            snapshot = capture.copy), recorded)
        assertEquals(intent.before, capture.source)
        assertNotEquals(checkNotNull(intent.before).identity, capture.copy.identity)
        assertEquals(CanonicalJournalFixture.key(f.snapshotFile(intent.id)), capture.copy.identity)
        assertEquals(intent.parents.last().key, capture.sourceParent)
        assertEquals(f.enrollment.custodyKey, capture.copyParent)
        assertEquals(capture, f.journal().verifySnapshot(intent.id, empty))
        assertArrayEquals(f.bytes, f.source.readBytes())
    }

    @Test fun admissionBeforeCommitFailureRollsBackWithoutCreatingCustody() = fixture { f ->
        val interrupted = IOException("admission before commit")
        val journal = f.journal(checkpoint = { if (it == "admission-before-commit") throw interrupted })
        assertSame(interrupted, refused { journal.admit(f.path, f.before(), f.proposal(), empty) })
        assertNull(f.journal().read())
        assertCustodyEmpty(f)
        assertArrayEquals(f.bytes, f.source.readBytes())
        assertEquals(CanonicalFileOperationJournal.Phase.PREPARED, admit(f).phase)
    }

    @Test fun admissionAfterCommitReturnFailureStillLeavesADurableIntentThatBlocksRetry() = fixture { f ->
        val interrupted = IOException("admission after commit")
        val journal = f.journal(checkpoint = { if (it == "admission-after-commit") throw interrupted })
        assertSame(interrupted, refused { journal.admit(f.path, f.before(), f.proposal(), empty) })
        val pending = checkNotNull(f.journal().read())
        assertEquals(CanonicalFileOperationJournal.Phase.PREPARED, pending.phase)
        assertEquals(f.before(), pending.before)
        refused { admit(f) }
        assertEquals(pending, f.journal().read())
        assertCustodyEmpty(f)
        assertArrayEquals(f.bytes, f.source.readBytes())
    }

    @Test fun snapshotBeforeCommitFailureRetainsFullCopyButDoesNotInventAReceipt() = fixture { f ->
        val intent = admit(f)
        val interrupted = IOException("snapshot before commit")
        val journal = f.journal(checkpoint = { if (it == "snapshot-before-commit") throw interrupted })
        assertSame(interrupted, refused { journal.capture(intent.id, empty) })
        assertEquals(intent, f.journal().read())
        assertArrayEquals(f.bytes, f.snapshotFile(intent.id).readBytes())
        assertArrayEquals(f.bytes, f.source.readBytes())
        val before = tree(f.base)
        refused { f.journal().verifySnapshot(intent.id, empty) }
        refused { f.journal().capture(intent.id, empty) }
        assertEquals(before, tree(f.base))
    }

    @Test fun snapshotAfterCommitReturnFailureLeavesAVerifiableReceiptAndBlocksRecapture() = fixture { f ->
        val intent = admit(f)
        val interrupted = IOException("snapshot after commit")
        val journal = f.journal(checkpoint = { if (it == "snapshot-after-commit") throw interrupted })
        assertSame(interrupted, refused { journal.capture(intent.id, empty) })
        val recorded = checkNotNull(f.journal().read())
        assertEquals(CanonicalFileOperationJournal.Phase.SNAPSHOT_RECORDED, recorded.phase)
        assertEquals(recorded.snapshot, f.journal().verifySnapshot(intent.id, empty).copy)
        val before = tree(f.base)
        refused { f.journal().capture(intent.id, empty) }
        refused { admit(f) }
        assertEquals(before, tree(f.base))
    }

    @Test fun custodyContentChangedBeforeReceiptCommitCannotReceiveASuccessfulCaptureReturn() = fixture { f ->
        val intent = admit(f)
        val output = f.snapshotFile(intent.id)
        val changed = f.bytes.copyOf().apply { this[0] = (this[0].toInt() xor 127).toByte() }
        var reached = false
        val journal = f.journal(checkpoint = { at ->
            if (at == "snapshot-before-commit") {
                reached = true
                val modified = Files.getLastModifiedTime(output.toPath())
                output.writeBytes(changed)
                Files.setLastModifiedTime(output.toPath(), modified)
            }
        })
        refused { journal.capture(intent.id, empty) }
        assertTrue(reached)
        val record = checkNotNull(f.journal().read())
        assertEquals(intent.id, record.id)
        assertEquals(intent.before, record.before)
        assertEquals(intent.proposal, record.proposal)
        // A committed receipt is historical evidence, never proof of the current custody bytes.
        if (record.phase == CanonicalFileOperationJournal.Phase.SNAPSHOT_RECORDED) {
            assertEquals(checkNotNull(intent.before).sha256, checkNotNull(record.snapshot).sha256)
        } else {
            assertEquals(intent, record)
        }
        val before = tree(f.base)
        refused { f.journal().verifySnapshot(intent.id, empty) }
        refused { f.journal().capture(intent.id, empty) }
        assertEquals(before, tree(f.base))
        assertArrayEquals(changed, output.readBytes())
        assertArrayEquals(f.bytes, f.source.readBytes())
    }

    @Test fun missingEnrolledDatabaseIsNeverRecreated() = fixture { f ->
        val intent = admit(f)
        f.journal().capture(intent.id, empty)
        Files.move(f.database.toPath(), File(f.base, "retained.sqlite").toPath())
        assertAuthorityRefusesWithoutMutation(f, intent.id)
        assertFalse(Files.exists(f.database.toPath(), NOFOLLOW_LINKS))
    }

    @Test fun sameBytesReplacementDatabaseCannotBeAdoptedUnderOldEnrollment() = fixture { f ->
        val intent = admit(f)
        val bytes = f.database.readBytes()
        val retained = File(f.base, "retained.sqlite")
        Files.move(f.database.toPath(), retained.toPath())
        f.database.writeBytes(bytes)
        assertNotEquals(f.enrollment.databaseKey, CanonicalJournalFixture.key(f.database))
        assertAuthorityRefusesWithoutMutation(f, intent.id)
        assertArrayEquals(bytes, retained.readBytes())
        assertArrayEquals(bytes, f.database.readBytes())
    }

    @Test fun corruptDatabaseBytesArePreservedWithoutDestructiveSqliteRecovery() = fixture { f ->
        val intent = admit(f)
        val corrupt = ByteArray(8_192) { 0x35 }
        f.database.writeBytes(corrupt)
        assertAuthorityRefusesWithoutMutation(f, intent.id)
        assertArrayEquals(corrupt, f.database.readBytes())
    }

    @Test fun databaseSymlinkCannotReadModifyOrRemoveItsOutsideTarget() = fixture { f ->
        val intent = admit(f)
        Files.move(f.database.toPath(), File(f.base, "retained.sqlite").toPath())
        val outside = File(f.base, "outside-target").apply { writeText("outside data") }
        Files.createSymbolicLink(f.database.toPath(), outside.toPath())
        assertAuthorityRefusesWithoutMutation(f, intent.id)
        assertEquals(outside.toPath(), Files.readSymbolicLink(f.database.toPath()))
        assertEquals("outside data", outside.readText())
    }

    @Test fun changedEnrollmentCannotAdoptExistingRowsOrWriteWorkspaceData() = fixture { f ->
        val intent = admit(f)
        val enrollments = listOf(
            f.enrollment.copy(projectId = "different-project"),
            f.enrollment.copy(generation = UUID.randomUUID().toString()),
            f.enrollment.copy(rootKey = "foreign-root-key"),
            f.enrollment.copy(custodyKey = "foreign-custody-key"),
            f.enrollment.copy(authorityParentKey = "foreign-parent-key"),
        )
        val before = tree(f.base)
        for (enrollment in enrollments) {
            val journal = CanonicalFileOperationJournal(f.root, f.authority, enrollment, f.bytes.size.toLong())
            refused { journal.read() }
            refused { journal.capture(intent.id, empty) }
            refused { journal.admit(f.path, f.before(), f.proposal(), empty) }
        }
        assertEquals(before, tree(f.base))
        assertEquals(intent, f.journal().read())
    }

    @Test fun deletedOperationRowIsDetectedByDurableMetaMembership() = fixture { f ->
        val intent = admit(f)
        f.sql { it.execSQL("DELETE FROM operation") }
        assertAuthorityRefusesWithoutMutation(f, intent.id)
    }

    @Test fun unexpectedOperationWithEmptyMetaIsNotAdoptedOrTreatedAsNoWork() = fixture { f ->
        val intent = admit(f)
        var record = ""
        f.sql { db -> db.rawQuery("SELECT record_json FROM operation", null).use {
            check(it.moveToFirst()); record = it.getString(0)
        } }
        f.sql { db ->
            db.execSQL("DELETE FROM operation")
            db.execSQL("UPDATE meta SET expected_operation=NULL,record_digest=NULL")
            db.execSQL("INSERT INTO operation VALUES(1,?)", arrayOf(record))
        }
        assertAuthorityRefusesWithoutMutation(f, intent.id)
    }

    @Test fun alteredOperationJsonIsRejectedByItsRecordedChecksum() = fixture { f ->
        val intent = admit(f)
        f.sql { it.execSQL("UPDATE operation SET record_json=replace(record_json,'src/deep/file.kt','src/deep/else.kt')") }
        assertAuthorityRefusesWithoutMutation(f, intent.id)
    }

    @Test fun alteredRecordDigestDoesNotAuthorizeTheExistingOperation() = fixture { f ->
        val intent = admit(f)
        f.sql { it.execSQL("UPDATE meta SET record_digest=?", arrayOf("0".repeat(64))) }
        assertAuthorityRefusesWithoutMutation(f, intent.id)
    }

    @Test fun oversizedNullableMembershipFieldsAndOperationRecordAreRejectedWithoutReset() {
        val oversized = "x".repeat(600_000)
        for (column in listOf("expected_operation", "record_digest", "record_json")) fixture { f ->
            val id = if (column == "record_json") admit(f).id else UUID.randomUUID().toString()
            f.sql { db ->
                val table = if (column == "record_json") "operation" else "meta"
                db.execSQL("UPDATE $table SET $column=?", arrayOf(oversized))
            }
            assertAuthorityRefusesWithoutMutation(f, id)
            assertCustodyEmpty(f)
        }
    }

    @Test fun unsupportedSchemaIsPreservedWithoutUpgradeOrReset() = fixture { f ->
        val intent = admit(f)
        f.sql { it.version = 2 }
        assertAuthorityRefusesWithoutMutation(f, intent.id)
        f.sql { assertEquals(2, it.version) }
    }

    @Test fun replacedNestedParentRejectsRecoveryAndPreservesBothTrees() = fixture { f ->
        val intent = admit(f)
        val retained = File(f.base, "retained-deep")
        Files.move(f.source.parentFile.toPath(), retained.toPath())
        check(f.source.parentFile.mkdir())
        val contender = byteArrayOf(71, 72, 73)
        CanonicalJournalFixture.write(f.source, contender)
        assertAuthorityRefusesWithoutMutation(f, intent.id)
        assertArrayEquals(f.bytes, File(retained, "file.kt").readBytes())
        assertArrayEquals(contender, f.source.readBytes())
        assertCustodyEmpty(f)
    }

    @Test fun existingPartialFullDirectorySymlinkAndHardlinkCustodyEntriesAreNeverAdopted() {
        for (kind in listOf("partial", "full", "directory", "symlink", "hardlink")) fixture { f ->
            val intent = admit(f)
            val output = f.snapshotFile(intent.id)
            val contender = byteArrayOf(81, 82, 83)
            val outside = File(f.base, "contender").apply { writeBytes(contender) }
            when (kind) {
                "partial" -> output.writeBytes(contender)
                "full" -> CanonicalJournalFixture.write(output, f.bytes)
                "directory" -> { check(output.mkdir()); File(output, "kept").writeBytes(contender) }
                "symlink" -> Files.createSymbolicLink(output.toPath(), outside.toPath())
                "hardlink" -> Files.createLink(output.toPath(), f.source.toPath())
            }
            val before = tree(f.base)
            refused { f.journal().capture(intent.id, empty) }
            refused { f.journal().verifySnapshot(intent.id, empty) }
            assertEquals(intent, f.journal().read())
            assertEquals(before, tree(f.base))
            assertArrayEquals(f.bytes, f.source.readBytes())
        }
    }

    @Test fun sourceContentChangeAfterAdmissionRetainsOriginalProposalAndAnyPartialCopy() = fixture { f ->
        val intent = admit(f)
        val original = File(f.base, "original-retained").apply { writeBytes(f.bytes) }
        val proposed = File(f.base, "proposal-retained").apply { writeBytes(f.proposedBytes) }
        val changed = f.bytes.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 127).toByte() }
        val modified = Files.getLastModifiedTime(f.source.toPath())
        f.source.writeBytes(changed)
        Files.setLastModifiedTime(f.source.toPath(), modified)
        refused { f.journal().capture(intent.id, empty) }
        assertEquals(intent, f.journal().read())
        assertArrayEquals(changed, f.source.readBytes())
        assertArrayEquals(f.bytes, original.readBytes())
        assertArrayEquals(f.proposedBytes, proposed.readBytes())
        val output = f.snapshotFile(intent.id)
        if (output.exists()) assertArrayEquals(changed.copyOf(output.length().toInt()), output.readBytes())
    }

    @Test fun sameContentSourceInodeReplacementRejectsBeforeCustodyCreation() = fixture { f ->
        val intent = admit(f)
        val retained = File(f.base, "original-retained")
        Files.move(f.source.toPath(), retained.toPath())
        CanonicalJournalFixture.write(f.source, f.bytes)
        assertNotEquals(checkNotNull(intent.before).identity, CanonicalJournalFixture.key(f.source))
        refused { f.journal().capture(intent.id, empty) }
        assertEquals(intent, f.journal().read())
        assertArrayEquals(f.bytes, retained.readBytes())
        assertArrayEquals(f.bytes, f.source.readBytes())
        assertCustodyEmpty(f)
    }

    @Test fun changedRecordedSnapshotIsDetectedAndEveryNamedVersionIsPreserved() {
        for (replace in listOf(false, true)) fixture { f ->
            val intent = admit(f)
            f.journal().capture(intent.id, empty)
            val recorded = f.journal().read()
            val output = f.snapshotFile(intent.id)
            val retained = File(f.custody, "retained-copy")
            val changed = f.bytes.copyOf().apply { this[0] = (this[0].toInt() xor 127).toByte() }
            val modified = Files.getLastModifiedTime(output.toPath())
            if (replace) Files.move(output.toPath(), retained.toPath())
            CanonicalJournalFixture.write(output, if (replace) f.bytes else changed)
            Files.setLastModifiedTime(output.toPath(), modified)
            val before = tree(f.base)
            refused { f.journal().verifySnapshot(intent.id, empty) }
            refused { f.journal().capture(intent.id, empty) }
            assertEquals(recorded, f.journal().read())
            assertEquals(before, tree(f.base))
            assertArrayEquals(f.bytes, f.source.readBytes())
            if (replace) assertArrayEquals(f.bytes, retained.readBytes())
        }
    }

    @Test fun repeatedReadAndSnapshotVerificationDoNotRewriteTheDatabase() = fixture { f ->
        val intent = admit(f)
        f.journal().capture(intent.id, empty)
        val recorded = f.journal().read()
        val before = tree(f.base)
        repeat(5) {
            assertEquals(recorded, f.journal().read())
            assertEquals(recorded!!.snapshot, f.journal().verifySnapshot(intent.id, empty).copy)
        }
        assertEquals(before, tree(f.base))
    }

    @Test fun intactRecordedSnapshotStillVerifiesHistoricalSourceAfterSourceLeafReplacement() = fixture { f ->
        val intent = admit(f)
        val captured = f.journal().capture(intent.id, empty)
        val recorded = f.journal().read()
        val retained = File(f.base, "historical-source-retained")
        Files.move(f.source.toPath(), retained.toPath())
        CanonicalJournalFixture.write(f.source, f.proposedBytes)
        assertNotEquals(checkNotNull(intent.before).identity, CanonicalJournalFixture.key(f.source))
        val before = tree(f.base)
        val verified = f.journal().verifySnapshot(intent.id, empty)
        assertEquals(captured, verified)
        assertEquals(intent.before, verified.source)
        assertEquals(recorded, f.journal().read())
        assertEquals(before, tree(f.base))
        assertArrayEquals(f.bytes, retained.readBytes())
        assertArrayEquals(f.proposedBytes, f.source.readBytes())
        assertArrayEquals(f.bytes, f.snapshotFile(intent.id).readBytes())
    }

    @Test fun cancellationAtSnapshotBoundariesKeepsPreparedIntentAndAlreadyCreatedBytes() {
        for (boundary in CanonicalFileSnapshot.Boundary.values()) fixture { f ->
            val intent = admit(f)
            val job = Job()
            var reached = false
            val journal = f.journal(checkpoint = { at ->
                if (at == "snapshot-${boundary.name}") { reached = true; job.cancel() }
            })
            assertTrue(refused { journal.capture(intent.id, job) } is CancellationException)
            assertTrue("Checkpoint $boundary was reached", reached)
            assertEquals(intent, f.journal().read())
            assertArrayEquals(f.bytes, f.source.readBytes())
            val output = f.snapshotFile(intent.id)
            if (boundary == CanonicalFileSnapshot.Boundary.OPENED) {
                assertFalse(Files.exists(output.toPath(), NOFOLLOW_LINKS))
            } else {
                assertTrue(output.isFile)
                val partial = output.readBytes()
                assertArrayEquals(f.bytes.copyOf(partial.size), partial)
                if (boundary == CanonicalFileSnapshot.Boundary.COPIED_BLOCK) {
                    assertTrue(partial.isNotEmpty() && partial.size < f.bytes.size)
                }
            }
        }
    }

    @Test fun cancelledAdmissionLeavesNoMembershipOrCustodyEntry() = fixture { f ->
        val job = Job().apply { cancel() }
        val before = tree(f.base)
        assertTrue(refused { f.journal().admit(f.path, f.before(), f.proposal(), job) } is CancellationException)
        assertNull(f.journal().read())
        assertEquals(before, tree(f.base))
        assertCustodyEmpty(f)
    }

    @Test fun cancellationAfterEitherCommitRejectsNormalReturnWhileRetainingDurableState() {
        for (snapshot in listOf(false, true)) fixture { f ->
            val intent = if (snapshot) admit(f) else null
            val job = Job()
            val stop = if (snapshot) "snapshot-after-commit" else "admission-after-commit"
            var reached = false
            val journal = f.journal(checkpoint = { at ->
                if (at == stop) { reached = true; job.cancel() }
            })
            val failure = refused {
                if (snapshot) journal.capture(checkNotNull(intent).id, job)
                else journal.admit(f.path, f.before(), f.proposal(), job)
            }
            assertTrue(failure is CancellationException)
            assertTrue(reached)
            val persisted = checkNotNull(f.journal().read())
            assertEquals(f.before(), persisted.before)
            assertEquals(f.proposal(), persisted.proposal)
            if (snapshot) {
                assertEquals(checkNotNull(intent).id, persisted.id)
                assertEquals(CanonicalFileOperationJournal.Phase.SNAPSHOT_RECORDED, persisted.phase)
                assertEquals(persisted.snapshot, f.journal().verifySnapshot(persisted.id, empty).copy)
                assertArrayEquals(f.bytes, f.snapshotFile(persisted.id).readBytes())
            } else {
                assertEquals(CanonicalFileOperationJournal.Phase.PREPARED, persisted.phase)
                assertNull(persisted.snapshot)
                assertCustodyEmpty(f)
            }
            val before = tree(f.base)
            refused { admit(f) }
            assertEquals(persisted, f.journal().read())
            assertEquals(before, tree(f.base))
            assertArrayEquals(f.bytes, f.source.readBytes())
        }
    }

    @Test(timeout = 30_000) fun twoConcurrentCaptureOwnersCannotOverwriteTheSameCustodyName() = fixture { f ->
        val intent = admit(f)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val attempts = (0..1).map {
                pool.submit<Result<CanonicalFileSnapshot.Capture>> {
                    val journal = f.journal()
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    runCatching { journal.capture(intent.id, empty) }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val results = attempts.map { it.get(15, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, results.count { it.isFailure })
            val winner = results.single { it.isSuccess }.getOrThrow()
            assertEquals(winner, f.journal().verifySnapshot(intent.id, empty))
            assertEquals(CanonicalJournalFixture.key(f.snapshotFile(intent.id)), winner.copy.identity)
            assertEquals(listOf(intent.snapshotName), f.custody.list()!!.toList())
            assertArrayEquals(f.bytes, f.snapshotFile(intent.id).readBytes())
            assertArrayEquals(f.bytes, f.source.readBytes())
        } finally {
            start.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun nativeSqlitePageQuotaRollsBackLargeParentChainAdmissionWithoutTouchingFiles() = fixture { f ->
        // Legal components keep the path below PATH_MAX while repeated parent prefixes exceed the existing free pages.
        val components = (0 until 18).map { "directory-$it-" + "x".repeat(110) }
        val path = components.joinToString("/") + "/file.kt"
        val source = File(f.root, path)
        check(source.parentFile.mkdirs())
        CanonicalJournalFixture.write(source, f.bytes)
        val expected = f.before(source)
        val original = File(f.base, "original-retained").apply { writeBytes(f.bytes) }
        val proposed = File(f.base, "proposal-retained").apply { writeBytes(f.proposedBytes) }
        val restricted = f.journal(sizeLimit = f.database.length())
        val failure = refused { restricted.admit(path, expected, f.proposal(), empty) }
        assertTrue("Expected real native SQLite FULL, got $failure", failure is SQLiteFullException)
        assertNull(f.journal().read())
        assertCustodyEmpty(f)
        assertArrayEquals(f.bytes, source.readBytes())
        assertArrayEquals(f.bytes, f.source.readBytes())
        assertArrayEquals(f.bytes, original.readBytes())
        assertArrayEquals(f.proposedBytes, proposed.readBytes())
    }

    private val empty = EmptyCoroutineContext

    private fun fixture(block: (CanonicalJournalFixture) -> Unit) = CanonicalJournalFixture().use(block)

    private fun admit(f: CanonicalJournalFixture) = f.journal().admit(f.path, f.before(), f.proposal(), empty)

    private fun assertCustodyEmpty(f: CanonicalJournalFixture) {
        Files.newDirectoryStream(f.custody.toPath()).use { assertFalse(it.iterator().hasNext()) }
    }

    private fun assertAuthorityRefusesWithoutMutation(f: CanonicalJournalFixture, id: String) {
        val before = tree(f.base)
        refused { f.journal().read() }
        refused { f.journal().capture(id, empty) }
        refused { f.journal().verifySnapshot(id, empty) }
        refused { f.journal().admit(f.path, f.before(), f.proposal(), empty) }
        assertEquals(before, tree(f.base))
    }

    private fun refused(action: () -> Any?): Exception {
        try { action() } catch (failure: Exception) { return failure }
        throw AssertionError("Conflicting or cancelled operation returned a successful result")
    }

    private data class Entry(val key: String, val kind: String, val size: Long, val modified: String,
        val mode: Int, val digestOrTarget: String)

    private fun tree(base: File): Map<String, Entry> {
        val root = base.toPath()
        val result = sortedMapOf<String, Entry>()
        fun record(path: Path, attributes: BasicFileAttributes) {
            val kind = when {
                attributes.isSymbolicLink -> "symlink"
                attributes.isDirectory -> "directory"
                attributes.isRegularFile -> "file"
                else -> "other"
            }
            val data = when (kind) {
                "file" -> CanonicalJournalFixture.sha256(Files.readAllBytes(path))
                "symlink" -> Files.readSymbolicLink(path).toString()
                else -> ""
            }
            result[root.relativize(path).toString()] = Entry(
                attributes.fileKey().toString(), kind, attributes.size(), attributes.lastModifiedTime().toString(),
                (Files.getAttribute(path, "unix:mode", NOFOLLOW_LINKS) as Number).toInt() and 4095, data,
            )
        }
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                record(dir, attrs)
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                record(file, attrs)
                return FileVisitResult.CONTINUE
            }
        })
        return result
    }
}
