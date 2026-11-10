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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CanonicalJournalRetentionTest {
    private val context = EmptyCoroutineContext
    private val policy = CanonicalJournalRetention.Policy(4, 10_000_000)

    @Test fun batchCustodyChecksOneExactHistoryAndPreservesSchemaOne() = fixture { f ->
        val history = f.journal().inspectRecoveryMetadata()
        val before = tree(f.base)
        f.journal().requireCompletedCustody(history, context)
        assertEquals(before, tree(f.base))
        assertEquals(1, schema(f))
    }

    @Test fun batchCustodyVisitsEveryCompletionOnceWithoutChangingEvidence() = fixture { f ->
        enable(f)
        repeat(3) { index ->
            val intent = complete(f, "publication $index".toByteArray())
            f.journal().archiveCompleted(intent.id, context)
        }
        val history = f.journal().inspectRecoveryMetadata()
        val before = tree(f.base)
        var visited = 0
        f.observedJournal { if (it == "completed-custody-reviewed") visited++ }
            .requireCompletedCustody(history, context)
        assertEquals(3, visited)
        history.completed.forEach {
            assertTrue(f.journal().reviewCompletedCustody(it.id, context).all { review -> review.matches })
            assertEquals(it.id, f.journal().inspectOperationRecovery(it.id, context).intent.id)
        }
        assertEquals(before, tree(f.base))
    }

    @Test fun batchCustodyRejectsStaleMembershipPendingAndUsage() = fixture { f ->
        enable(f)
        val first = complete(f)
        f.journal().archiveCompleted(first.id, context)
        val history = f.journal().inspectRecoveryMetadata()
        val before = tree(f.base)
        listOf(history.copy(completed = emptyList()), history.copy(pending = first),
            history.copy(retentionUsage = history.retentionUsage!!.copy(retainedBytes = 0))).forEach { stale ->
            refused { f.journal().requireCompletedCustody(stale, context) }
        }
        assertEquals(before, tree(f.base))
    }

    @Test fun batchCustodyDetectsChangesAtEveryHistoryPosition() {
        for (position in 0..2) for (role in listOf("before", "after", "retired"))
            for (mutation in listOf("content", "inode", "mode", "missing", "symlink")) fixture { f ->
            enable(f)
            repeat(3) { index ->
                val intent = complete(f, "publication $index".toByteArray())
                f.journal().archiveCompleted(intent.id, context)
            }
            val history = f.journal().inspectRecoveryMetadata()
            val file = File(f.custody, "${history.completed[position].id}.$role")
            when (mutation) {
                "content" -> { val bytes = file.readBytes(); bytes[0] = (bytes[0].toInt() xor 1).toByte(); file.writeBytes(bytes) }
                "inode" -> { val copy = File(f.base, "replacement"); CanonicalJournalFixture.write(copy, file.readBytes()); Files.move(copy.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                "mode" -> Files.setAttribute(file.toPath(), "unix:mode", 384)
                "missing" -> check(file.delete())
                "symlink" -> { check(file.delete()); Files.createSymbolicLink(file.toPath(), f.source.toPath()) }
            }
            val before = tree(f.base)
            refused { f.journal().requireCompletedCustody(history, context) }
            assertEquals(before, tree(f.base))
        }
    }

    @Test fun batchCustodyRejectsUnknownCustodyAndBadDigestWithoutWrites() = fixture { f ->
        enable(f)
        val intent = complete(f)
        f.journal().archiveCompleted(intent.id, context)
        val history = f.journal().inspectRecoveryMetadata()
        val unknown = File(f.custody, "unclaimed").apply { writeText("keep") }
        val beforeUnknown = tree(f.base)
        refused { f.journal().requireCompletedCustody(history, context) }
        assertEquals(beforeUnknown, tree(f.base))
        check(unknown.delete())
        f.sql { it.execSQL("UPDATE retention SET records_digest=?", arrayOf("0".repeat(64))) }
        val beforeDigest = tree(f.base)
        refused { f.journal().requireCompletedCustody(history, context) }
        assertEquals(beforeDigest, tree(f.base))
    }

    @Test fun batchCustodyCancellationAtFirstAndLastEntryNeverReturnsSuccess() = fixture { f ->
        enable(f)
        repeat(3) { index ->
            val intent = complete(f, "publication $index".toByteArray())
            f.journal().archiveCompleted(intent.id, context)
        }
        val history = f.journal().inspectRecoveryMetadata()
        val before = tree(f.base)
        for (stop in listOf(1, 3)) {
            val job = Job()
            var visited = 0
            val journal = f.observedJournal {
                if (it == "completed-custody-reviewed" && ++visited == stop) job.cancel()
            }
            assertTrue(refused { journal.requireCompletedCustody(history, job) } is CancellationException)
            assertEquals(stop, visited)
            assertEquals(before, tree(f.base))
        }
    }

    @Test fun capacityInspectionIsReadOnlyAndReportsOnlyEnabledPolicy() = fixture { f ->
        val initial = tree(f.base)
        assertNull(f.journal().inspectRecoveryMetadata().retentionUsage)
        assertEquals(initial, tree(f.base))
        assertEquals(1, schema(f))
        enable(f)
        val first = complete(f)
        f.journal().archiveCompleted(first.id, context)
        val before = tree(f.base)
        repeat(3) {
            val observed = f.journal().inspectRecoveryMetadata()
            assertEquals(1, observed.retentionUsage!!.completedEntries)
            assertEquals(policy.maxEntries, observed.retentionUsage.maxEntries)
            assertEquals(policy.maxBytes, observed.retentionUsage.maxRetainedBytes)
            assertTrue(observed.retentionUsage.retainedBytes > 0)
            assertEquals(listOf(first), observed.completed)
        }
        assertEquals(before, tree(f.base))
    }

    @Test fun upgradeIsExplicitAndRepeatedReadsDoNotRewriteMetadata() = fixture { f ->
        val initial = tree(f.base)
        assertTrue(f.journal().readCompletedMetadataForRecovery().isEmpty())
        assertEquals(initial, tree(f.base))
        assertEquals(1, schema(f))
        f.journal().enableCompletionRetention(policy, context)
        assertEquals(2, schema(f))
        val enabled = tree(f.base)
        repeat(3) {
            f.observedJournal { error("Unexpected upgrade") }.enableCompletionRetention(policy, context)
            assertNull(f.journal().read())
            assertTrue(f.journal().readCompletedMetadataForRecovery().isEmpty())
        }
        assertEquals(enabled, tree(f.base))
    }

    @Test fun existingPendingSnapshotSurvivesExplicitUpgrade() = fixture { f ->
        val intent = f.journal().admit(f.path, f.before(), f.proposal(), context)
        val copy = f.journal().capture(intent.id, context)
        val pending = f.journal().read()
        val payload = tree(f.base, true)
        f.journal().enableCompletionRetention(policy, context)
        assertEquals(pending, f.journal().read())
        assertEquals(copy, f.journal().verifySnapshot(intent.id, context))
        assertEquals(payload, tree(f.base, true))
    }

    @Test fun completedArchiveFreesSlotAndPreservesAllVersionsAcrossAnotherPublication() = fixture { f ->
        enable(f)
        val first = complete(f)
        val payload = tree(f.base, true)
        assertEquals(first, f.journal().archiveCompleted(first.id, context))
        assertNull(f.journal().read())
        assertEquals(payload, tree(f.base, true))
        assertEquals(listOf(first), f.journal().readCompletedMetadataForRecovery())
        assertTrue(f.journal().reviewCompletedCustody(first.id, context).all { it.matches })
        val second = complete(f, "second publication".toByteArray())
        f.journal().archiveCompleted(second.id, context)
        assertEquals(setOf(first.id, second.id), f.journal().readCompletedMetadataForRecovery().map { it.id }.toSet())
        assertEquals("second publication", f.source.readText())
        assertTrue(f.journal().reviewCompletedCustody(first.id, context).all { it.matches })
        assertTrue(f.journal().reviewCompletedCustody(second.id, context).all { it.matches })
        assertArrayEquals(f.bytes, f.snapshotFile(first.id).readBytes())
    }

    @Test fun archivingAgainCannotClearAnUnrelatedPendingOperation() = fixture { f ->
        enable(f)
        val first = complete(f)
        f.journal().archiveCompleted(first.id, context)
        val pending = f.journal().admit(f.path, f.before(), f.proposal(), context)
        val before = tree(f.base)
        repeat(3) { assertEquals(first, f.observedJournal { error("Unexpected archive") }.archiveCompleted(first.id, context)) }
        assertEquals(pending, f.journal().read())
        assertEquals(before, tree(f.base))
    }

    @Test fun incompleteForeignAndDisabledOperationsCannotBeArchived() = fixture { f ->
        val intent = complete(f)
        val before = tree(f.base)
        refused { f.journal().archiveCompleted(intent.id, context) }
        assertEquals(before, tree(f.base))
        enable(f)
        refused { f.journal().archiveCompleted(UUID.randomUUID().toString(), context) }
        f.journal().archiveCompleted(intent.id, context)
        val pending = f.journal().admit(f.path, f.before(), f.proposal(), context)
        val admitted = tree(f.base)
        refused { f.journal().archiveCompleted(pending.id, context) }
        assertEquals(admitted, tree(f.base))
    }

    @Test fun recordedConflictCannotBeArchivedEvenIfOriginalBytesReturn() = fixture { f ->
        enable(f)
        val intent = complete(f)
        f.source.writeText("concurrent edit")
        f.journal().recordPublicationConflict(intent.id, context)
        f.source.writeBytes(f.proposedBytes)
        val before = tree(f.base)
        refused { f.journal().archiveCompleted(intent.id, context) }
        assertEquals(before, tree(f.base))
        assertTrue(f.journal().readCompletedMetadataForRecovery().isEmpty())
    }

    @Test fun countLimitRefusesAdmissionWithoutDeletingOldWork() = fixture { f ->
        f.journal().enableCompletionRetention(policy.copy(maxEntries = 2), context)
        repeat(2) { val intent = complete(f); f.journal().archiveCompleted(intent.id, context) }
        val before = tree(f.base)
        val failure = refused { f.journal().admit(f.path, f.before(), f.proposal(), context) }
        assertTrue(failure.message.orEmpty().contains("retention is full"))
        assertTrue(failure is CanonicalJournalRetention.CapacityException)
        assertEquals(setOf(CanonicalJournalRetention.CapacityBlocker.ENTRY_LIMIT),
            (failure as CanonicalJournalRetention.CapacityException).blockers)
        assertEquals(2, failure.usage.completedEntries)
        assertNull(f.journal().read())
        assertEquals(2, f.journal().readCompletedMetadataForRecovery().size)
        assertEquals(before, tree(f.base))
    }

    @Test fun byteLimitReservesPeakCopiesBeforeAdmittingWork() = fixture { f ->
        val peak = 3L * f.bytes.size + 2L * f.proposedBytes.size
        f.journal().enableCompletionRetention(policy.copy(maxBytes = peak - 1), context)
        val before = tree(f.base)
        refused { f.journal().admit(f.path, f.before(), f.proposal(), context) }
        assertEquals(before, tree(f.base))
        assertNull(f.journal().read())
    }

    @Test fun exactByteBudgetAdmitsThenAccountsForRetainedHistory() = fixture { f ->
        val peak = 3L * f.bytes.size + 2L * f.proposedBytes.size
        f.journal().enableCompletionRetention(policy.copy(maxBytes = peak), context)
        val intent = complete(f)
        f.journal().archiveCompleted(intent.id, context)
        val before = tree(f.base)
        refused { f.journal().admit(f.path, f.before(), f.proposal(), context) }
        assertEquals(before, tree(f.base))
    }

    @Test fun changingRetentionLimitsOrEnablingAnInsufficientBudgetRequiresReview() = fixture { f ->
        val pending = f.journal().admit(f.path, f.before(), f.proposal(), context)
        val before = tree(f.base)
        refused { f.journal().enableCompletionRetention(policy.copy(maxBytes = 1), context) }
        assertEquals(before, tree(f.base))
        assertEquals(1, schema(f))
        enable(f)
        val enabled = tree(f.base)
        refused { f.journal().enableCompletionRetention(policy.copy(maxEntries = 3), context) }
        assertEquals(enabled, tree(f.base))
        assertEquals(pending, f.journal().read())
    }

    @Test fun openDescriptorWritesAfterArchiveRemainVisibleAndDoNotChangeIndependentCopy() = fixture { f ->
        enable(f)
        val intent = complete(f)
        RandomAccessFile(File(f.custody, intent.retiredName), "rw").use { writer ->
            f.journal().archiveCompleted(intent.id, context)
            writer.seek(0)
            writer.write("late retained work".toByteArray())
            writer.fd.sync()
            val review = f.journal().reviewCompletedCustody(intent.id, context)
            assertEquals(listOf(intent.retiredName), review.filterNot { it.matches }.map { it.name })
            val next = complete(f, "next operation".toByteArray())
            f.journal().archiveCompleted(next.id, context)
            assertTrue(File(f.custody, intent.retiredName).readText().startsWith("late retained work"))
            assertArrayEquals(f.bytes, f.snapshotFile(intent.id).readBytes())
            assertEquals(intent, f.journal().readCompletedMetadataForRecovery().find { it.id == intent.id })
        }
    }

    @Test fun changesAtBothPrecommitBoundariesRefuseArchiveAndPreservePendingState() {
        for (point in listOf("completion-archive-verified", "completion-archive-before-commit")) {
            for (name in listOf("canonical", "before", "after", "retired", "publish")) fixture { f ->
                enable(f)
                val intent = complete(f)
                val changed = if (name == "canonical") f.source else File(f.custody, "${intent.id}.$name")
                refused { f.observedJournal { if (it == point) changed.writeText("concurrent $name") }.archiveCompleted(intent.id, context) }
                assertEquals("concurrent $name", changed.readText())
                assertEquals(intent, f.journal().read())
                assertTrue(f.journal().readCompletedMetadataForRecovery().isEmpty())
            }
        }
    }

    @Test fun postcommitChangeDoesNotEraseCustodyOrPretendReceiptIsCurrentContent() = fixture { f ->
        enable(f)
        val intent = complete(f)
        val retired = File(f.custody, intent.retiredName)
        assertEquals(intent, f.observedJournal { if (it == "completion-archive-after-commit") retired.writeText("after commit") }
            .archiveCompleted(intent.id, context))
        assertFalse(f.journal().reviewCompletedCustody(intent.id, context).first { it.name == retired.name }.matches)
        assertArrayEquals(f.bytes, f.snapshotFile(intent.id).readBytes())
    }

    @Test fun replacedOrMissingCustodyIsReportedAndPreventsNewAdmission() {
        for (change in listOf("missing", "directory", "symlink")) fixture { f ->
            enable(f)
            val intent = complete(f)
            f.journal().archiveCompleted(intent.id, context)
            val original = f.snapshotFile(intent.id)
            val retained = File(f.base, "retained-original")
            Files.move(original.toPath(), retained.toPath())
            when (change) {
                "directory" -> { check(original.mkdir()); File(original, "work").writeText("keep") }
                "symlink" -> Files.createSymbolicLink(original.toPath(), retained.toPath())
            }
            val before = tree(f.base)
            assertFalse(f.journal().reviewCompletedCustody(intent.id, context).first { it.name == original.name }.matches)
            refused { f.journal().admit(f.path, f.before(), f.proposal(), context) }
            assertEquals(before, tree(f.base))
            assertArrayEquals(f.bytes, retained.readBytes())
        }
    }

    @Test fun unownedCustodyIsPreservedAndBlocksAdmission() = fixture { f ->
        enable(f)
        val intent = complete(f)
        f.journal().archiveCompleted(intent.id, context)
        File(f.custody, "unrecorded-work").writeText("must remain")
        val before = tree(f.base)
        refused { f.journal().admit(f.path, f.before(), f.proposal(), context) }
        assertEquals(before, tree(f.base))
    }

    @Test fun rootLossDoesNotHideCompletedMetadataOrRetainedFileReview() = fixture { f ->
        enable(f)
        val intent = complete(f)
        f.journal().archiveCompleted(intent.id, context)
        Files.move(f.root.toPath(), File(f.base, "retained-root").toPath())
        val before = tree(f.base)
        assertEquals(listOf(intent), f.journal().readCompletedMetadataForRecovery())
        assertTrue(f.journal().reviewCompletedCustody(intent.id, context).all { it.matches })
        assertEquals(before, tree(f.base))
    }

    @Test fun historyMembershipCorruptionIsNeverRepairedSilently() {
        val mutations = listOf(
            "DELETE FROM completed_operation",
            "DELETE FROM retention",
            "UPDATE retention SET expected_count=0",
            "UPDATE retention SET records_digest=zeroblob(64)",
            "UPDATE completed_operation SET record_json='[]'",
            "UPDATE completed_operation SET retained_bytes=0",
            "UPDATE completed_operation SET id='00000000-0000-0000-0000-000000000000'",
            "UPDATE completed_operation SET record_json=zeroblob(524289)",
            "PRAGMA user_version=1",
            "PRAGMA user_version=3",
        )
        for (sql in mutations) fixture { f ->
            enable(f)
            val intent = complete(f)
            f.journal().archiveCompleted(intent.id, context)
            f.sql { it.execSQL(sql) }
            val before = tree(f.base)
            refused { f.journal().readCompletedMetadataForRecovery() }
            refused { f.journal().admit(f.path, f.before(), f.proposal(), context) }
            assertEquals(sql, before, tree(f.base))
        }
    }

    @Test fun archiveDatabaseFailuresRollBackMembershipWithoutTouchingFiles() {
        val mutations = listOf(
            "CREATE TRIGGER refuse_archive BEFORE INSERT ON completed_operation BEGIN SELECT RAISE(ABORT,'injected'); END",
            "CREATE TRIGGER refuse_clear BEFORE DELETE ON operation BEGIN SELECT RAISE(ABORT,'injected'); END",
            "CREATE TRIGGER refuse_membership BEFORE UPDATE ON meta BEGIN SELECT RAISE(ABORT,'injected'); END",
        )
        for (sql in mutations) fixture { f ->
            enable(f)
            val intent = complete(f)
            f.sql { it.execSQL(sql) }
            val before = tree(f.base, true)
            refused { f.journal().archiveCompleted(intent.id, context) }
            assertEquals(intent, f.journal().read())
            assertTrue(f.journal().readCompletedMetadataForRecovery().isEmpty())
            assertEquals(before, tree(f.base, true))
        }
    }

    @Test fun sqliteCapacityExhaustionPreservesPendingCompletionAndPayload() = fixture { f ->
        enable(f)
        val path = (1..24).joinToString("/") { "nested-directory-$it" } + "/file.kt"
        val file = File(f.root, path)
        check(file.parentFile!!.mkdirs())
        CanonicalJournalFixture.write(file, f.bytes)
        val intent = complete(f, path = path)
        val before = tree(f.base, true)
        val failure = refused { f.journal(sizeLimit = f.database.length()).archiveCompleted(intent.id, context) }
        assertTrue("Expected actual SQLite capacity failure: $failure", failure is android.database.sqlite.SQLiteFullException)
        assertEquals(intent, f.journal().read())
        assertTrue(f.journal().readCompletedMetadataForRecovery().isEmpty())
        assertEquals(before, tree(f.base, true))
    }

    @Test fun semanticallyInvalidArchivedRecordsCannotHideBehindValidMembershipDigest() {
        for (change in listOf("phase", "accounting", "pending-duplicate")) fixture { f ->
            enable(f)
            val intent = complete(f)
            f.sql { db ->
                val record = db.rawQuery("SELECT record_json FROM operation", null).use { row ->
                    check(row.moveToFirst())
                    row.getString(0)
                }
                val changed = if (change == "phase") record.replace("PUBLICATION_COMPLETED", "PUBLICATION_RECORDED") else record
                val bytes = if (change == "accounting") 1L else 2L * checkNotNull(intent.before).size + intent.proposal.size
                CanonicalJournalRetention.append(db, checkNotNull(CanonicalJournalRetention.read(db)),
                    CanonicalJournalRetention.Entry(intent.id, changed, bytes))
                if (change != "pending-duplicate") {
                    db.execSQL("DELETE FROM operation")
                    db.execSQL("UPDATE meta SET expected_operation=NULL,record_digest=NULL")
                }
            }
            val before = tree(f.base)
            refused { f.journal().readPendingMetadataForRecovery() }
            assertEquals(before, tree(f.base))
        }
    }

    @Test fun cancelledUpgradeLeavesOriginalSchemaAndPendingRecord() {
        for (point in listOf("start", "retention-enable-verified", "retention-enable-before-commit")) fixture { f ->
            val job = Job()
            if (point == "start") job.cancel()
            val pending = f.journal().admit(f.path, f.before(), f.proposal(), context)
            val before = tree(f.base, true)
            assertTrue(refused { f.observedJournal { if (it == point) job.cancel() }.enableCompletionRetention(policy, job) } is CancellationException)
            assertEquals(1, schema(f))
            assertEquals(pending, f.journal().read())
            assertEquals(before, tree(f.base, true))
        }
    }

    @Test fun cancelledArchiveLeavesTheCompletedOperationPending() {
        for (point in listOf("start", "completion-archive-verified", "completion-archive-before-commit")) fixture { f ->
            enable(f)
            val intent = complete(f)
            val job = Job()
            if (point == "start") job.cancel()
            val before = tree(f.base, true)
            assertTrue(refused { f.observedJournal { if (it == point) job.cancel() }.archiveCompleted(intent.id, job) } is CancellationException)
            assertEquals(intent, f.journal().read())
            assertTrue(f.journal().readCompletedMetadataForRecovery().isEmpty())
            assertEquals(before, tree(f.base, true))
        }
    }

    @Test fun lostUpgradeReturnReusesCommittedSchemaWithoutWritingAgain() = fixture { f ->
        refused { f.observedJournal { if (it == "retention-enable-after-commit") throw IOException("lost return") }
            .enableCompletionRetention(policy, context) }
        val before = tree(f.base)
        f.observedJournal { error("Unexpected second upgrade") }.enableCompletionRetention(policy, context)
        assertEquals(before, tree(f.base))
    }

    @Test fun lostArchiveReturnReusesHistoricalReceiptWithoutWritingAgain() = fixture { f ->
        enable(f)
        val intent = complete(f)
        refused { f.observedJournal { if (it == "completion-archive-after-commit") throw IOException("lost return") }
            .archiveCompleted(intent.id, context) }
        val before = tree(f.base)
        assertEquals(intent, f.observedJournal { error("Unexpected second archive") }.archiveCompleted(intent.id, context))
        assertEquals(before, tree(f.base))
    }

    @Test fun concurrentCompletionCallersArchiveExactlyOnce() = fixture { f ->
        enable(f)
        val intent = complete(f)
        val committed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<CanonicalFileOperationJournal.Intent> {
                f.observedJournal { if (it == "completion-archive-after-commit") {
                    committed.countDown()
                    check(release.await(20, TimeUnit.SECONDS))
                } }.archiveCompleted(intent.id, context)
            }
            assertTrue(committed.await(20, TimeUnit.SECONDS))
            val before = tree(f.base)
            assertEquals(intent, f.journal().archiveCompleted(intent.id, context))
            release.countDown()
            assertEquals(intent, first.get(20, TimeUnit.SECONDS))
            assertEquals(listOf(intent), f.journal().readCompletedMetadataForRecovery())
            assertEquals(before, tree(f.base))
        } finally {
            release.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(20, TimeUnit.SECONDS))
        }
    }

    @Test fun changedParentAtArchiveCommitPreservesBothTreesAndPendingMetadata() = fixture { f ->
        enable(f)
        val intent = complete(f)
        val parent = requireNotNull(f.source.parentFile)
        val saved = File(f.base, "retained-parent")
        refused { f.observedJournal { if (it == "completion-archive-before-commit") {
            Files.move(parent.toPath(), saved.toPath())
            check(parent.mkdir())
            f.source.writeText("replacement work")
        } }.archiveCompleted(intent.id, context) }
        assertEquals(intent, f.journal().readPendingMetadataForRecovery())
        assertEquals("replacement work", f.source.readText())
        assertArrayEquals(f.proposedBytes, File(saved, f.source.name).readBytes())
    }

    @Test fun upgradedAuthorityCanNeverBeMistakenForUnusedEnrollment() = fixture { f ->
        val slot = File(f.base, "registration")
        val bootstrap = CanonicalJournalBootstrap(slot, f.bytes.size.toLong())
        val enrollment = bootstrap.createNew(f.root, "project", context)
        val journal = CanonicalFileOperationJournal(f.root, File(slot, "authority"), enrollment, f.bytes.size.toLong())
        journal.enableCompletionRetention(policy, context)
        Files.move(File(slot, "enrollment.json").toPath(), File(f.base, "original-enrollment.json").toPath())
        val before = tree(f.base)
        refused { bootstrap.recoverCommittedEnrollment(f.root, "project", context) }
        assertEquals(before, tree(f.base))
    }

    @Test fun archivedAuthorityCannotBeReenrolledAfterReceiptLoss() = fixture { f ->
        enable(f)
        val intent = complete(f)
        f.journal().archiveCompleted(intent.id, context)
        val before = tree(f.base)
        refused { f.journal().inspectUnusedEnrollmentForRecovery() }
        assertEquals(before, tree(f.base))
        assertEquals(listOf(intent), f.journal().readCompletedMetadataForRecovery())
    }

    private fun CanonicalJournalFixture.observedJournal(checkpoint: (String) -> Unit) = journal(checkpoint = checkpoint)

    private fun enable(f: CanonicalJournalFixture) = f.journal().enableCompletionRetention(policy, context)

    private fun complete(f: CanonicalJournalFixture, bytes: ByteArray = f.proposedBytes,
                         path: String = f.path): CanonicalFileOperationJournal.Intent {
        val proposal = CanonicalFileOperationJournal.Content(bytes.size.toLong(), CanonicalJournalFixture.sha256(bytes), 420)
        val journal = f.journal()
        val intent = journal.admit(path, f.before(File(f.root, path)), proposal, context)
        journal.capture(intent.id, context)
        val input = File(f.base, "proposal-input")
        CanonicalJournalFixture.write(input, bytes)
        AnchoredWorkspaceFiles(f.base).use { journal.captureProposal(intent.id, it, input.name, f.before(input), context) }
        journal.stagePublication(intent.id, context)
        journal.retireCanonical(intent.id, context)
        journal.recordRetainedSnapshot(intent.id, context)
        journal.publishStaged(intent.id, context)
        journal.recordPublicationCompletion(intent.id, context)
        return checkNotNull(journal.read())
    }

    private fun schema(f: CanonicalJournalFixture): Int {
        var version = 0
        f.sql { version = it.version }
        return version
    }

    private fun fixture(action: (CanonicalJournalFixture) -> Unit) = CanonicalJournalFixture().use(action)

    private fun refused(action: () -> Unit): Exception {
        try { action() } catch (failure: Exception) { return failure }
        throw AssertionError("Expected refusal")
    }

    private fun tree(root: File, dataOnly: Boolean = false): Map<String, String> = Files.walk(root.toPath()).use { paths ->
        paths.filter { !Files.isDirectory(it, NOFOLLOW_LINKS) && (!dataOnly || !it.fileName.toString().startsWith("state.sqlite")) }
            .toList().associate { path ->
                path.toString() to if (Files.isSymbolicLink(path)) "link:${Files.readSymbolicLink(path)}"
                else "${CanonicalJournalFixture.key(path.toFile())}:${Files.getLastModifiedTime(path)}:" +
                    "${Files.getAttribute(path, "unix:mode")}:${CanonicalJournalFixture.sha256(Files.readAllBytes(path))}"
            }
    }
}
