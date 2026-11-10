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
class CanonicalPublicationOutcomeTest {
    private val context = EmptyCoroutineContext

    @Test fun durableIntentPrecedesOneMoveAndExactOutcomeIsRecorded() = fixture { f ->
        val i = prepare(f)
        val points = mutableListOf<String>()
        val published = f.journal(checkpoint = { point ->
            points += point
            if (point == "publication-before-move") {
                assertEquals(CanonicalFileOperationJournal.Phase.PUBLICATION_PREPARED, f.journal().read()!!.phase)
                assertFalse(f.source.exists()); assertTrue(stage(f, i).exists())
            }
            if (point == "publication-after-move") {
                assertEquals(CanonicalFileOperationJournal.Phase.PUBLICATION_PREPARED, f.journal().read()!!.phase)
                assertTrue(f.source.exists()); assertFalse(stage(f, i).exists())
            }
        }).publishStaged(i.id, context)
        assertEquals(i.publicationStage, published)
        assertEquals(i.publicationStage, f.before())
        assertEquals(AnchoredWorkspaceFiles.MoveReceipt(true, 0), f.journal().read()!!.publication)
        assertEquals(listOf("publication-intent-before-commit", "publication-intent-after-commit",
            "publication-before-move", "publication-after-move", "publication-outcome-before-commit",
            "publication-outcome-after-commit"), points)
        assertEquals(i.snapshot, f.journal().verifyRetainedSnapshot(i.id, context))
        custody(f, i)
    }

    @Test fun recordedVerificationDoesNotRewriteMetadataOrData() = fixture { f ->
        val i = prepare(f); f.journal().publishStaged(i.id, context)
        val before = tree(f.base)
        repeat(3) {
            assertEquals(i.publicationStage, f.journal().verifyPublication(i.id, context))
            assertEquals(i.snapshot, f.journal().verifyRetainedSnapshot(i.id, context))
            assertEquals(i.proposalCopy, f.journal().verifyProposal(i.id, context))
        }
        assertEquals(before, tree(f.base))
    }

    @Test fun secondAttemptCannotOverwritePublishedOrNewlyEditedWork() = fixture { f ->
        val i = prepare(f); f.journal().publishStaged(i.id, context)
        refused { f.journal().publishStaged(i.id, context) }
        f.source.writeText("new CLI edit")
        val before = tree(f.base)
        refused { f.journal().publishStaged(i.id, context) }
        refused { f.journal().verifyPublication(i.id, context) }
        assertEquals(before, tree(f.base)); custody(f, i)
    }

    @Test fun retirementWithoutRetentionReferenceCannotAuthorizePublication() = fixture { f ->
        val i = prepare(f, retention = false)
        val before = tree(f.base)
        refused { f.journal().publishStaged(i.id, context) }
        refused { f.journal().verifyPublication(i.id, context) }
        assertEquals(before, tree(f.base))
    }

    @Test fun foreignOperationCannotPublishOrVerifyAnything() = fixture { f ->
        prepare(f)
        val before = tree(f.base)
        refused { f.journal().publishStaged(UUID.randomUUID().toString(), context) }
        refused { f.journal().verifyPublication(UUID.randomUUID().toString(), context) }
        assertEquals(before, tree(f.base))
    }

    @Test fun recreatedDestinationBeforeAdmissionLeavesEveryVersionUntouched() {
        for (kind in listOf("file", "directory", "symlink")) fixture { f ->
            val i = prepare(f); createTarget(f, i, kind)
            val before = tree(f.base)
            refused { f.journal().publishStaged(i.id, context) }
            assertEquals(before, tree(f.base)); assertEquals(i, f.journal().read())
        }
    }

    @Test fun destinationCreatedInLastGapWinsAtomicallyWithoutClobber() {
        for (kind in listOf("file", "directory", "symlink")) fixture { f ->
            val i = prepare(f)
            val failure = refused { f.journal(checkpoint = { if (it == "publication-before-move") createTarget(f, i, kind) })
                .publishStaged(i.id, context) }
            assertTrue(failure.message!!.contains("moved=false, errno=17"))
            assertEquals(AnchoredWorkspaceFiles.MoveReceipt(false, 17), f.journal().read()!!.publication)
            assertArrayEquals(f.proposedBytes, stage(f, i).readBytes())
            val before = tree(f.base)
            refused { f.journal().publishStaged(i.id, context) }
            refused { f.journal().verifyPublication(i.id, context) }
            assertEquals(before, tree(f.base)); custody(f, i)
        }
    }

    @Test fun retiredOrCustodyEditsPreventAdmissionWithoutDeletingThem() {
        for (which in listOf("before", "after", "publish", "retired")) fixture { f ->
            val i = prepare(f)
            File(f.custody, "${i.id}.$which").writeText("changed work")
            val before = tree(f.base)
            refused { f.journal().publishStaged(i.id, context) }
            assertEquals(before, tree(f.base)); assertEquals(i, f.journal().read())
        }
    }

    @Test fun retiredEditAfterIntentLeavesExplicitPendingStateWithoutMovingStage() = fixture { f ->
        val i = prepare(f)
        refused { f.journal(checkpoint = { if (it == "publication-intent-after-commit") retired(f, i).writeText("late CLI edit") })
            .publishStaged(i.id, context) }
        assertEquals(CanonicalFileOperationJournal.Phase.PUBLICATION_PREPARED, f.journal().read()!!.phase)
        assertFalse(f.source.exists()); assertArrayEquals(f.proposedBytes, stage(f, i).readBytes())
        assertEquals("late CLI edit", retired(f, i).readText())
        assertEquals(i.snapshot, f.journal().verifyRetainedSnapshot(i.id, context))
    }

    @Test fun heldDescriptorWriterInLastGapPreservesBothVersionsAndReportsConflict() = fixture { f ->
        val staged = prepare(f, retire = false)
        RandomAccessFile(f.source, "rw").use { writer ->
            f.journal().retireCanonical(staged.id, context); f.journal().recordRetainedSnapshot(staged.id, context)
            refused { f.journal(checkpoint = { if (it == "publication-before-move") {
                writer.seek(0); writer.write("held CLI descriptor".toByteArray()); writer.fd.sync()
            } }).publishStaged(staged.id, context) }
        }
        assertEquals(AnchoredWorkspaceFiles.MoveReceipt(true, 0), f.journal().read()!!.publication)
        assertArrayEquals(f.proposedBytes, f.source.readBytes())
        assertTrue(retired(f, staged).readText().startsWith("held CLI descriptor"))
        assertArrayEquals(f.bytes, f.snapshotFile(staged.id).readBytes())
        assertEquals(staged.snapshot, f.journal().verifyRetainedSnapshot(staged.id, context))
        refused { f.journal().verifyPublication(staged.id, context) }
    }

    @Test fun stageReplacementInLastGapCannotMasqueradeAsAdmittedProposal() {
        for (sameBytes in listOf(false, true)) fixture { f ->
            val i = prepare(f)
            val savedStage = File(f.base, "old-stage")
            refused { f.journal(checkpoint = { if (it == "publication-before-move") {
                Files.move(stage(f, i).toPath(), savedStage.toPath())
                CanonicalJournalFixture.write(stage(f, i), if (sameBytes) f.proposedBytes else "foreign stage".toByteArray())
            } }).publishStaged(i.id, context) }
            assertNotEquals(i.publicationStage!!.identity, f.before().identity)
            assertArrayEquals(f.proposedBytes, savedStage.readBytes()); custody(f, i)
            assertEquals(AnchoredWorkspaceFiles.MoveReceipt(true, 0), f.journal().read()!!.publication)
        }
    }

    @Test fun stageDirectoryOrSymlinkTransitionIsReportedWithoutFollowingTheLink() {
        for (kind in listOf("directory", "symlink")) fixture { f ->
            val i = prepare(f)
            val target = File(f.base, "outside").apply { writeText("outside work") }
            refused { f.journal(checkpoint = { if (it == "publication-before-move") {
                Files.move(stage(f, i).toPath(), File(f.base, "original-stage").toPath())
                if (kind == "directory") { check(stage(f, i).mkdir()); File(stage(f, i), "work").writeText("directory work") }
                else Files.createSymbolicLink(stage(f, i).toPath(), target.toPath())
            } }).publishStaged(i.id, context) }
            assertEquals("outside work", target.readText())
            if (kind == "directory") assertEquals("directory work", File(f.source, "work").readText())
            else assertEquals(target.toPath(), Files.readSymbolicLink(f.source.toPath()))
            custody(f, i)
        }
    }

    @Test fun rootOrNestedParentReplacementStopsBeforePublication() {
        for (root in listOf(false, true)) fixture { f ->
            val i = prepare(f)
            val parent = if (root) f.root else f.source.parentFile
            refused { f.journal(checkpoint = { if (it == "publication-before-move") {
                Files.move(parent.toPath(), File(f.base, "moved-parent").toPath())
                check(parent.mkdir()); File(parent, "new-work").writeText("replacement work")
            } }).publishStaged(i.id, context) }
            assertEquals("replacement work", File(parent, "new-work").readText())
            assertArrayEquals(f.proposedBytes, stage(f, i).readBytes()); custody(f, i)
            assertEquals(CanonicalFileOperationJournal.Phase.PUBLICATION_PREPARED, f.journal().readPendingMetadataForRecovery()!!.phase)
        }
    }

    @Test fun parentMoveAfterPublicationKeepsPendingMetadataAndMovedFile() = fixture { f ->
        val i = prepare(f)
        val old = f.source.parentFile; val moved = File(f.base, "moved-parent")
        refused { f.journal(checkpoint = { if (it == "publication-after-move") {
            Files.move(old.toPath(), moved.toPath()); check(old.mkdir()); f.source.writeText("replacement directory work")
        } }).publishStaged(i.id, context) }
        assertEquals(CanonicalFileOperationJournal.Phase.PUBLICATION_PREPARED, f.journal().readPendingMetadataForRecovery()!!.phase)
        assertArrayEquals(f.proposedBytes, File(moved, f.source.name).readBytes())
        assertEquals("replacement directory work", f.source.readText()); custody(f, i)
    }

    @Test fun intentCommitFailureNeverMovesTheStage() = fixture { f ->
        val i = prepare(f); val error = IOException("intent commit rejected")
        assertSame(error, refused { f.journal(checkpoint = { if (it == "publication-intent-before-commit") throw error })
            .publishStaged(i.id, context) })
        assertEquals(i, f.journal().read()); assertFalse(f.source.exists())
        assertArrayEquals(f.proposedBytes, stage(f, i).readBytes()); custody(f, i)
    }

    @Test fun interruptionAtEveryBoundaryLeavesExplicitStateAndNeverReplays() {
        for (point in listOf("publication-intent-after-commit", "publication-before-move", "publication-after-move",
                "publication-outcome-before-commit", "publication-outcome-after-commit")) fixture { f ->
            val i = prepare(f); val error = IOException("lost return")
            assertSame(error, refused { f.journal(checkpoint = { if (it == point) throw error }).publishStaged(i.id, context) })
            val moved = point in listOf("publication-after-move", "publication-outcome-before-commit", "publication-outcome-after-commit")
            val recorded = point == "publication-outcome-after-commit"
            assertEquals(moved, f.source.exists()); assertEquals(!moved, stage(f, i).exists())
            assertArrayEquals(f.proposedBytes, (if (moved) f.source else stage(f, i)).readBytes())
            assertEquals(if (recorded) CanonicalFileOperationJournal.Phase.PUBLICATION_RECORDED else
                CanonicalFileOperationJournal.Phase.PUBLICATION_PREPARED, f.journal().read()!!.phase)
            val before = tree(f.base)
            refused { f.journal().publishStaged(i.id, context) }
            if (recorded) assertEquals(i.publicationStage, f.journal().verifyPublication(i.id, context))
            else refused { f.journal().verifyPublication(i.id, context) }
            assertEquals(before, tree(f.base)); custody(f, i)
        }
    }

    @Test fun cancellationBeforeAdmissionOrMovePreservesUnpublishedStage() {
        for (point in listOf("start", "publication-intent-before-commit", "publication-intent-after-commit", "publication-before-move")) fixture { f ->
            val i = prepare(f); val job = Job(); if (point == "start") job.cancel()
            assertTrue(refused { f.journal(checkpoint = { if (it == point) job.cancel() }).publishStaged(i.id, job) } is CancellationException)
            assertFalse(f.source.exists()); assertArrayEquals(f.proposedBytes, stage(f, i).readBytes()); custody(f, i)
        }
    }

    @Test fun cancellationAfterMoveDoesNotEraseThePublishedFileOrReplay() = fixture { f ->
        val i = prepare(f); val job = Job()
        assertTrue(refused { f.journal(checkpoint = { if (it == "publication-after-move") job.cancel() })
            .publishStaged(i.id, job) } is CancellationException)
        assertEquals(CanonicalFileOperationJournal.Phase.PUBLICATION_PREPARED, f.journal().read()!!.phase)
        val before = tree(f.base)
        refused { f.journal().publishStaged(i.id, context) }
        assertEquals(before, tree(f.base)); assertArrayEquals(f.proposedBytes, f.source.readBytes()); custody(f, i)
    }

    @Test fun publishedPathDeletionOrReplacementIsNotSilentlyRepaired() {
        for (kind in listOf("missing", "same-bytes", "directory", "symlink")) fixture { f ->
            val i = prepare(f); f.journal().publishStaged(i.id, context)
            Files.move(f.source.toPath(), File(f.base, "external-published").toPath())
            when (kind) {
                "same-bytes" -> CanonicalJournalFixture.write(f.source, f.proposedBytes)
                "directory" -> check(f.source.mkdir())
                "symlink" -> Files.createSymbolicLink(f.source.toPath(), File(f.base, "external-published").toPath())
            }
            val before = tree(f.base)
            refused { f.journal().verifyPublication(i.id, context) }
            refused { f.journal().publishStaged(i.id, context) }
            assertEquals(before, tree(f.base)); custody(f, i)
        }
    }

    @Test fun recreatedStageAfterSuccessfulMoveIsPreservedAndReported() = fixture { f ->
        val i = prepare(f); f.journal().publishStaged(i.id, context)
        stage(f, i).writeText("new custody work")
        val before = tree(f.base)
        refused { f.journal().verifyPublication(i.id, context) }
        assertEquals(i.snapshot, f.journal().verifyRetainedSnapshot(i.id, context))
        assertEquals(before, tree(f.base)); custody(f, i)
    }

    @Test fun malformedOutcomeMetadataFailsClosedWithoutRepair() {
        for (bad in listOf("null", "[0,0]", "[2,0]", "[1,-1]", "[1,4096]", "[1,4294967296]", "[true,0]", "[1,\"0\"]")) fixture { f ->
            val i = prepare(f); f.journal().publishStaged(i.id, context)
            rewrite(f) { it[11] = Json.parseToJsonElement(bad) }
            val before = tree(f.base)
            refused { f.journal().readPendingMetadataForRecovery() }
            assertEquals(before, tree(f.base)); custody(f, i)
        }
    }

    @Test fun publicationOutcomeCannotAppearBeforeAnAttemptOrWithoutRetention() {
        for (bad in listOf("wrong-phase", "wrong-reference", "uncertain-retirement")) fixture { f ->
            val i = prepare(f); f.journal().publishStaged(i.id, context)
            rewrite(f) {
                when (bad) {
                    "wrong-phase" -> it[5] = JsonPrimitive("PUBLICATION_PREPARED")
                    "wrong-reference" -> it[10] = JsonPrimitive("foreign")
                    else -> it[9] = Json.parseToJsonElement("[1,5]")
                }
            }
            val before = tree(f.base)
            refused { f.journal().readPendingMetadataForRecovery() }
            assertEquals(before, tree(f.base)); custody(f, i)
        }
    }

    @Test fun privateEmptyAndExecutableProposalsKeepExactModeAndIdentity() {
        for (mode in listOf(384, 420, 493)) fixture { f ->
            val bytes = if (mode == 384) byteArrayOf() else f.proposedBytes
            val i = prepare(f, proposed = bytes, mode = mode)
            assertEquals(i.publicationStage, f.journal().publishStaged(i.id, context))
            assertEquals(mode, f.before().mode); assertArrayEquals(bytes, f.source.readBytes())
        }
    }

    @Test fun twoCallersCanOnlyAdmitOnePublicationAttempt() = fixture { f ->
        val i = prepare(f); val prepared = CountDownLatch(1); val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val winner = executor.submit<CanonicalFileSnapshot.Version> {
                f.journal(checkpoint = { if (it == "publication-intent-after-commit") {
                    prepared.countDown(); check(release.await(20, TimeUnit.SECONDS))
                } }).publishStaged(i.id, context)
            }
            assertTrue(prepared.await(20, TimeUnit.SECONDS))
            refused { f.journal().publishStaged(i.id, context) }
            assertFalse(f.source.exists()); release.countDown()
            assertEquals(i.publicationStage, winner.get(20, TimeUnit.SECONDS)); custody(f, i)
        } finally { release.countDown(); executor.shutdownNow(); check(executor.awaitTermination(20, TimeUnit.SECONDS)) }
    }

    private fun prepare(f: CanonicalJournalFixture, retention: Boolean = true, retire: Boolean = true,
                        proposed: ByteArray = f.proposedBytes, mode: Int = 420): CanonicalFileOperationJournal.Intent {
        val content = CanonicalFileOperationJournal.Content(proposed.size.toLong(), CanonicalJournalFixture.sha256(proposed), mode)
        val i = f.journal().admit(f.path, f.before(), content, context)
        f.journal().capture(i.id, context)
        val input = File(f.base, "input"); CanonicalJournalFixture.write(input, proposed)
        Files.setAttribute(input.toPath(), "unix:mode", mode)
        AnchoredWorkspaceFiles(f.base).use { f.journal().captureProposal(i.id, it, input.name, f.before(input), context) }
        f.journal().stagePublication(i.id, context)
        if (retire) {
            f.journal().retireCanonical(i.id, context)
            if (retention) f.journal().recordRetainedSnapshot(i.id, context)
        }
        return f.journal().read()!!
    }
    private fun createTarget(f: CanonicalJournalFixture, i: CanonicalFileOperationJournal.Intent, kind: String) {
        when (kind) {
            "file" -> f.source.writeText("competing user work")
            "directory" -> { check(f.source.mkdir()); File(f.source, "work").writeText("directory work") }
            else -> Files.createSymbolicLink(f.source.toPath(), retired(f, i).toPath())
        }
    }
    private fun rewrite(f: CanonicalJournalFixture, edit: (MutableList<JsonElement>) -> Unit) = f.sql { db ->
        val old = db.rawQuery("SELECT record_json FROM operation", null).use { it.moveToFirst(); it.getString(0) }
        val fields = Json.parseToJsonElement(old).jsonArray.toMutableList(); edit(fields)
        val record = JsonArray(fields).toString()
        db.execSQL("UPDATE operation SET record_json=?", arrayOf(record))
        db.execSQL("UPDATE meta SET record_digest=?", arrayOf(CanonicalJournalFixture.sha256(record.toByteArray())))
    }
    private fun stage(f: CanonicalJournalFixture, i: CanonicalFileOperationJournal.Intent) = File(f.custody, i.publicationName)
    private fun retired(f: CanonicalJournalFixture, i: CanonicalFileOperationJournal.Intent) = File(f.custody, i.retiredName)
    private fun custody(f: CanonicalJournalFixture, i: CanonicalFileOperationJournal.Intent) {
        assertArrayEquals(f.bytes, f.snapshotFile(i.id).readBytes())
        assertArrayEquals(f.bytes, retired(f, i).readBytes())
        assertArrayEquals(f.proposedBytes, File(f.custody, i.proposalName).readBytes())
    }
    private fun fixture(action: (CanonicalJournalFixture) -> Unit) = CanonicalJournalFixture().use(action)
    private fun refused(action: () -> Unit): Exception {
        try { action() } catch (failure: Exception) { return failure }
        throw AssertionError("Expected explicit refusal")
    }
    private fun tree(root: File): Map<String, String> = Files.walk(root.toPath()).use { paths ->
        paths.filter { !Files.isDirectory(it, NOFOLLOW_LINKS) }.toList().associate { path ->
            path.toString() to if (Files.isSymbolicLink(path)) "link:${Files.readSymbolicLink(path)}"
            else "${CanonicalJournalFixture.key(path.toFile())}:${Files.getLastModifiedTime(path)}:${Files.getAttribute(path, "unix:mode")}:${CanonicalJournalFixture.sha256(Files.readAllBytes(path))}"
        }
    }
}
