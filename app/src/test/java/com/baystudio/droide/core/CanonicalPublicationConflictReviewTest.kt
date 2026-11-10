package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
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
class CanonicalPublicationConflictReviewTest {
    private val context = EmptyCoroutineContext
    private val bytes = ByteArray(90_000) { (it % 193).toByte() }

    @Test fun matchingObservationIsReportedForEachRoleWithoutWrites() {
        for (role in CanonicalFileOperationJournal.ConflictRole.entries) fixture { f ->
            val i = prepare(f, role); val before = tree(f.base)
            val result = f.journal().inspectPublicationConflict(i.id, context)
            assertEquals(i.id, result.operationId); assertEquals(i.publicationConflict, result.recorded)
            assertEquals(i.publicationConflictCopy, result.captured)
            assertEquals(i.publicationConflict!!.observed, result.currentVersion)
            assertEquals(AnchoredWorkspaceFiles.Kind.FILE, result.currentKind); assertTrue(result.currentMatchesRecordedObservation)
            assertEquals(before, tree(f.base)); unresolved(f, i)
        }
    }

    @Test fun newerCanonicalVersionRemainsDistinctFromTheHistoricalCopy() = fixture { f ->
        val i = prepare(f); f.source.writeText("newer CLI work"); val before = tree(f.base)
        val result = f.journal().inspectPublicationConflict(i.id, context)
        assertFalse(result.currentMatchesRecordedObservation); assertEquals(f.before(), result.currentVersion)
        assertEquals(i.publicationConflictCopy, result.captured); assertEquals(i.publicationConflict, result.recorded)
        assertEquals(before, tree(f.base)); assertArrayEquals(bytes, copy(f, i).readBytes()); unresolved(f, i)
    }

    @Test fun heldOldDescriptorEditsAreVisibleWithoutChangingHistoricalCustody() = fixture { f ->
        RandomAccessFile(f.source, "rw").use { writer ->
            val i = prepare(f, CanonicalFileOperationJournal.ConflictRole.RETIRED)
            writer.seek(0); writer.write("late writer".toByteArray()); writer.fd.sync()
            val before = tree(f.base); val result = f.journal().inspectPublicationConflict(i.id, context)
            assertFalse(result.currentMatchesRecordedObservation)
            assertEquals(f.before(File(f.custody, i.retiredName)), result.currentVersion)
            assertEquals(before, tree(f.base)); assertArrayEquals(bytes, copy(f, i).readBytes())
            assertArrayEquals(f.proposedBytes, f.source.readBytes())
        }
    }

    @Test fun absentDirectoryAndSymlinkCurrentPathsAreReportedWithoutFollowingOrRepairing() {
        for (kind in listOf("absent", "directory", "symlink")) fixture { f ->
            val i = prepare(f); val saved = File(f.base, "saved-current")
            Files.move(f.source.toPath(), saved.toPath())
            if (kind == "directory") { check(f.source.mkdir()); File(f.source, "work").writeText("directory work") }
            if (kind == "symlink") Files.createSymbolicLink(f.source.toPath(), saved.toPath())
            val before = tree(f.base); val result = f.journal().inspectPublicationConflict(i.id, context)
            assertEquals(when (kind) {
                "absent" -> AnchoredWorkspaceFiles.Kind.ABSENT
                "directory" -> AnchoredWorkspaceFiles.Kind.DIRECTORY
                else -> AnchoredWorkspaceFiles.Kind.SYMLINK
            }, result.currentKind)
            assertNull(result.currentVersion); assertFalse(result.currentMatchesRecordedObservation)
            assertEquals(before, tree(f.base)); assertArrayEquals(bytes, copy(f, i).readBytes()); unresolved(f, i)
        }
    }

    @Test fun missingReceiptCannotBeConfusedWithReviewedOrResolvedWork() = fixture { f ->
        val i = prepare(f, capture = false); val before = tree(f.base)
        refused { f.journal().inspectPublicationConflict(i.id, context) }
        assertEquals(before, tree(f.base)); assertFalse(copy(f, i).exists()); unresolved(f, i)
    }

    @Test fun unrecordedOccupiedCopyIsNotAdoptedByReview() = fixture { f ->
        val i = prepare(f, capture = false); CanonicalJournalFixture.write(copy(f, i), bytes)
        val before = tree(f.base); refused { f.journal().inspectPublicationConflict(i.id, context) }
        assertEquals(before, tree(f.base)); assertNull(f.journal().read()!!.publicationConflictCopy)
    }

    @Test fun corruptedOrMissingCopyRefusesReviewWithoutRepair() {
        for (missing in listOf(false, true)) fixture { f ->
            val i = prepare(f)
            if (missing) Files.move(copy(f, i).toPath(), File(f.base, "saved-copy").toPath()) else copy(f, i).writeText("changed copy")
            val before = tree(f.base); refused { f.journal().inspectPublicationConflict(i.id, context) }
            assertEquals(before, tree(f.base))
        }
    }

    @Test fun changeBetweenObservationsRefusesAStableReviewResult() = fixture { f ->
        val i = prepare(f)
        val failure = refused { f.journal(checkpoint = { if (it == "conflict-review-observed") f.source.writeText("new edit during review") })
            .inspectPublicationConflict(i.id, context) }
        assertTrue(failure.message!!.contains("changed during review")); assertEquals("new edit during review", f.source.readText())
        assertArrayEquals(bytes, copy(f, i).readBytes()); assertEquals(i, f.journal().read())
    }

    @Test fun sameContentOnAReplacementInodeIsNotMistakenForTheSameObservation() = fixture { f ->
        val i = prepare(f)
        refused { f.journal(checkpoint = { if (it == "conflict-review-observed") {
            Files.move(f.source.toPath(), File(f.base, "first-observation").toPath()); CanonicalJournalFixture.write(f.source, bytes)
        } }).inspectPublicationConflict(i.id, context) }
        assertArrayEquals(bytes, f.source.readBytes()); assertArrayEquals(bytes, File(f.base, "first-observation").readBytes())
        assertEquals(i, f.journal().read())
    }

    @Test fun changedCopyBetweenObservationsIsReportedAndPreserved() = fixture { f ->
        val i = prepare(f)
        refused { f.journal(checkpoint = { if (it == "conflict-review-observed") copy(f, i).writeText("changed historical copy") })
            .inspectPublicationConflict(i.id, context) }
        assertEquals("changed historical copy", copy(f, i).readText()); assertArrayEquals(bytes, f.source.readBytes())
        assertEquals(i, f.journal().read())
    }

    @Test fun sourceParentReplacementDoesNotMoveOrRecreateUserData() = fixture { f ->
        val i = prepare(f); val parent = f.source.parentFile; val saved = File(f.base, "saved-parent")
        refused { f.journal(checkpoint = { if (it == "conflict-review-observed") {
            Files.move(parent.toPath(), saved.toPath()); check(parent.mkdir()); f.source.writeText("new tree work")
        } }).inspectPublicationConflict(i.id, context) }
        assertArrayEquals(bytes, File(saved, f.source.name).readBytes()); assertEquals("new tree work", f.source.readText())
        assertEquals(i, f.journal().readPendingMetadataForRecovery()); assertArrayEquals(bytes, copy(f, i).readBytes())
    }

    @Test fun cancellationDoesNotChangeFilesMetadataOrTheConflictLatch() {
        for (point in listOf("start", "conflict-review-observed")) fixture { f ->
            val i = prepare(f); val before = tree(f.base); val job = Job(); if (point == "start") job.cancel()
            assertTrue(refused { f.journal(checkpoint = { if (it == point) job.cancel() }).inspectPublicationConflict(i.id, job) } is CancellationException)
            assertEquals(before, tree(f.base)); unresolved(f, i)
        }
    }

    @Test fun inspectionFailureAndRepeatedReadsNeverRewriteTheReceipt() = fixture { f ->
        val i = prepare(f); val before = tree(f.base); val error = IOException("inspection interrupted")
        assertSame(error, refused { f.journal(checkpoint = { if (it == "conflict-review-observed") throw error }).inspectPublicationConflict(i.id, context) })
        val first = f.journal().inspectPublicationConflict(i.id, context)
        repeat(3) { assertEquals(first, f.journal().inspectPublicationConflict(i.id, context)) }
        assertEquals(before, tree(f.base))
    }

    @Test fun foreignOperationAndMissingSourceRootFailWithoutResettingMembership() = fixture { f ->
        val i = prepare(f); val before = tree(f.base)
        refused { f.journal().inspectPublicationConflict(UUID.randomUUID().toString(), context) }
        assertEquals(before, tree(f.base))
        Files.move(f.root.toPath(), File(f.base, "moved-root").toPath()); val moved = tree(f.base)
        refused { f.journal().inspectPublicationConflict(i.id, context) }
        assertEquals(i, f.journal().readPendingMetadataForRecovery()); assertEquals(moved, tree(f.base)); assertFalse(f.root.exists())
    }

    @Test fun permissionOnlyChangeIsVisibleWithoutWideningCapturePermissions() = fixture { f ->
        val i = prepare(f); Files.setAttribute(f.source.toPath(), "unix:mode", 292); val before = tree(f.base)
        val result = f.journal().inspectPublicationConflict(i.id, context)
        assertEquals(292, result.currentVersion!!.mode); assertEquals(420, result.captured.mode)
        assertFalse(result.currentMatchesRecordedObservation); assertEquals(before, tree(f.base))
    }

    @Test fun restoredPublishedContentStillRequiresExplicitResolution() = fixture { f ->
        val i = prepare(f); CanonicalJournalFixture.write(f.source, f.proposedBytes)
        val before = tree(f.base); val result = f.journal().inspectPublicationConflict(i.id, context)
        assertEquals(i.publicationStage, result.currentVersion); assertFalse(result.currentMatchesRecordedObservation)
        assertEquals(i.publicationConflict, result.recorded); assertEquals(before, tree(f.base)); unresolved(f, i)
    }

    @Test fun oversizedCurrentFileIsRefusedWithoutReadingItAsAValidReviewVersion() = fixture { f ->
        val i = prepare(f); f.source.writeBytes(ByteArray(f.bytes.size + 1) { 1 }); val before = tree(f.base)
        refused { f.journal().inspectPublicationConflict(i.id, context) }
        assertEquals(before, tree(f.base)); assertArrayEquals(bytes, copy(f, i).readBytes())
    }

    private fun prepare(f: CanonicalJournalFixture, role: CanonicalFileOperationJournal.ConflictRole = CanonicalFileOperationJournal.ConflictRole.CANONICAL,
                        capture: Boolean = true): CanonicalFileOperationJournal.Intent {
        val i = f.journal().admit(f.path, f.before(), f.proposal(), context); f.journal().capture(i.id, context)
        val input = File(f.base, "input"); CanonicalJournalFixture.write(input, f.proposedBytes)
        AnchoredWorkspaceFiles(f.base).use { f.journal().captureProposal(i.id, it, input.name, f.before(input), context) }
        f.journal().stagePublication(i.id, context); f.journal().retireCanonical(i.id, context)
        f.journal().recordRetainedSnapshot(i.id, context); f.journal().publishStaged(i.id, context)
        val target = when (role) {
            CanonicalFileOperationJournal.ConflictRole.RETIRED -> File(f.custody, i.retiredName)
            CanonicalFileOperationJournal.ConflictRole.STAGE -> File(f.custody, i.publicationName)
            CanonicalFileOperationJournal.ConflictRole.CANONICAL -> f.source
        }
        CanonicalJournalFixture.write(target, bytes); f.journal().recordPublicationConflict(i.id, context)
        if (capture) f.journal().capturePublicationConflict(i.id, context)
        return f.journal().read()!!
    }
    private fun copy(f: CanonicalJournalFixture, i: CanonicalFileOperationJournal.Intent) = File(f.custody, i.conflictCopyName)
    private fun unresolved(f: CanonicalJournalFixture, i: CanonicalFileOperationJournal.Intent) {
        assertEquals(i.publicationConflict, f.journal().read()!!.publicationConflict)
        refused { f.journal().verifyPublication(i.id, context) }; refused { f.journal().recordPublicationCompletion(i.id, context) }
    }
    private fun fixture(action: (CanonicalJournalFixture) -> Unit) = CanonicalJournalFixture().use(action)
    private fun refused(action: () -> Unit): Exception {
        try { action() } catch (failure: Exception) { return failure }
        throw AssertionError("Expected explicit refusal")
    }
    private fun tree(root: File): Map<String, String> = Files.walk(root.toPath()).use { paths ->
        paths.filter { !Files.isDirectory(it, NOFOLLOW_LINKS) }.toList().associate { p ->
            p.toString() to if (Files.isSymbolicLink(p)) "link:${Files.readSymbolicLink(p)}" else
                "${CanonicalJournalFixture.key(p.toFile())}:${Files.getLastModifiedTime(p)}:${Files.getAttribute(p, "unix:mode")}:${CanonicalJournalFixture.sha256(Files.readAllBytes(p))}"
        }
    }
}
