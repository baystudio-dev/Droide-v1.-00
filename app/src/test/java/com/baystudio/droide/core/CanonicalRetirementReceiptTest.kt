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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CanonicalRetirementReceiptTest {
    private val context = EmptyCoroutineContext

    @Test fun durableIntentPrecedesMoveAndExactOutcomeIsRecorded() = fixture { f ->
        val intent = prepare(f)
        val points = mutableListOf<String>()
        val actual = f.journal(checkpoint = { at ->
            points += at
            if (at == "retirement-before-move") {
                assertEquals(CanonicalFileOperationJournal.Phase.RETIREMENT_PREPARED, f.journal().read()!!.phase)
                assertEquals(intent.before, f.before())
            }
            if (at == "retirement-after-move") {
                assertEquals(CanonicalFileOperationJournal.Phase.RETIREMENT_PREPARED, f.journal().read()!!.phase)
                assertFalse(f.source.exists())
            }
        }).retireCanonical(intent.id, context)
        assertEquals(intent.before, actual)
        val recorded = f.journal().read()!!
        assertEquals(CanonicalFileOperationJournal.Phase.RETIREMENT_RECORDED, recorded.phase)
        assertEquals(AnchoredWorkspaceFiles.MoveReceipt(true, 0), recorded.retirement)
        assertTrue(points.indexOf("retirement-intent-after-commit") < points.indexOf("retirement-before-move"))
        assertTrue(points.indexOf("retirement-after-move") < points.indexOf("retirement-outcome-before-commit"))
        assertEquals(intent.before, f.journal().verifyRetirement(intent.id, context))
        assertArrayEquals(f.bytes, retired(f, intent).readBytes())
        custody(f, intent)
    }

    @Test fun repeatedAttemptCannotMoveARecreatedCanonicalFile() = fixture { f ->
        val i = prepare(f)
        f.journal().retireCanonical(i.id, context)
        CanonicalJournalFixture.write(f.source, "new file".toByteArray())
        val before = tree(f.base)
        refused { f.journal().retireCanonical(i.id, context) }
        refused { f.journal().verifyRetirement(i.id, context) }
        assertEquals(before, tree(f.base))
        assertEquals("new file", f.source.readText())
        custody(f, i)
    }

    @Test fun recordedVerificationDoesNotRewriteMetadataOrFiles() = fixture { f ->
        val i = prepare(f)
        f.journal().retireCanonical(i.id, context)
        val before = tree(f.base)
        repeat(4) {
            assertEquals(i.before, f.journal().verifyRetirement(i.id, context))
            assertEquals(i.publicationStage, f.journal().verifyPublicationStage(i.id, context))
            assertEquals(i.proposalCopy, f.journal().verifyProposal(i.id, context))
        }
        assertEquals(before, tree(f.base))
    }

    @Test fun missingRecordedStageCannotAuthorizeRetirement() = fixture { f ->
        val i = prepare(f)
        Files.move(File(f.custody, i.publicationName).toPath(), File(f.base, "retained-stage").toPath())
        val before = tree(f.base)
        refused { f.journal().retireCanonical(i.id, context) }
        assertEquals(before, tree(f.base))
        assertEquals(i, f.journal().read())
    }

    @Test fun foreignOperationCannotRetireOrVerifyAnything() = fixture { f ->
        prepare(f)
        val before = tree(f.base)
        refused { f.journal().retireCanonical(UUID.randomUUID().toString(), context) }
        refused { f.journal().verifyRetirement(UUID.randomUUID().toString(), context) }
        assertEquals(before, tree(f.base))
    }

    @Test fun proposalWithoutStageIsNotRetirementAuthority() = fixture { f ->
        val i = prepare(f, stage = false)
        val before = tree(f.base)
        refused { f.journal().retireCanonical(i.id, context) }
        assertEquals(before, tree(f.base))
    }

    @Test fun preexistingDestinationIsNeverOverwrittenOrAdopted() {
        for (kind in listOf("file", "directory", "symlink")) fixture { f ->
            val i = prepare(f)
            val destination = retired(f, i)
            when (kind) {
                "file" -> destination.writeText("existing work")
                "directory" -> check(destination.mkdir())
                else -> Files.createSymbolicLink(destination.toPath(), f.source.toPath())
            }
            val before = tree(f.base)
            refused { f.journal().retireCanonical(i.id, context) }
            assertEquals(before, tree(f.base)); assertEquals(i, f.journal().read())
        }
    }

    @Test fun destinationCreatedAfterPreflightProducesAnExplicitFailedOutcome() {
        for (kind in listOf("file", "directory", "symlink")) fixture { f ->
            val i = prepare(f)
            val journal = f.journal(checkpoint = { if (it == "retirement-before-move") {
                when (kind) {
                    "file" -> retired(f, i).writeText("competing writer")
                    "directory" -> check(retired(f, i).mkdir())
                    else -> Files.createSymbolicLink(retired(f, i).toPath(), f.source.toPath())
                }
            } })
            val failure = refused { journal.retireCanonical(i.id, context) }
            assertTrue(failure.message!!.contains("moved=false, errno=17"))
            assertEquals(AnchoredWorkspaceFiles.MoveReceipt(false, 17), f.journal().read()!!.retirement)
            assertEquals(i.before, f.before())
            val after = tree(f.base)
            refused { f.journal().retireCanonical(i.id, context) }
            assertEquals(after, tree(f.base)); custody(f, i)
        }
    }

    @Test fun sourceEditsBeforeAdmissionLeaveJournalAndFilesUnchanged() = fixture { f ->
        val i = prepare(f)
        f.source.writeText("editor edits")
        val before = tree(f.base)
        refused { f.journal().retireCanonical(i.id, context) }
        assertEquals(before, tree(f.base)); assertEquals(i, f.journal().read())
    }

    @Test fun sourceEditAfterIntentStopsBeforeMoveAndCannotBeAutomaticallyRetried() = fixture { f ->
        val i = prepare(f)
        refused { f.journal(checkpoint = { if (it == "retirement-intent-after-commit") f.source.writeText("late edit") })
            .retireCanonical(i.id, context) }
        assertEquals(CanonicalFileOperationJournal.Phase.RETIREMENT_PREPARED, f.journal().read()!!.phase)
        assertFalse(retired(f, i).exists())
        val before = tree(f.base)
        refused { f.journal().retireCanonical(i.id, context) }
        assertEquals(before, tree(f.base)); assertEquals("late edit", f.source.readText())
    }

    @Test fun replacedSourceInLastGapIsRetainedAndReportedAsConflict() = fixture { f ->
        val i = prepare(f)
        val old = File(f.base, "external-old")
        refused { f.journal(checkpoint = { if (it == "retirement-before-move") {
            Files.move(f.source.toPath(), old.toPath())
            CanonicalJournalFixture.write(f.source, "external new".toByteArray())
        } }).retireCanonical(i.id, context) }
        assertEquals(AnchoredWorkspaceFiles.MoveReceipt(true, 0), f.journal().read()!!.retirement)
        assertArrayEquals(f.bytes, old.readBytes())
        assertEquals("external new", retired(f, i).readText())
        custody(f, i)
    }

    @Test fun sameBytesDifferentSourceInodeStillConflicts() = fixture { f ->
        val i = prepare(f)
        refused { f.journal(checkpoint = { if (it == "retirement-before-move") {
            Files.move(f.source.toPath(), File(f.base, "external-old").toPath())
            CanonicalJournalFixture.write(f.source, f.bytes)
        } }).retireCanonical(i.id, context) }
        assertNotEquals(checkNotNull(i.before).identity, f.before(retired(f, i)).identity)
        assertArrayEquals(f.bytes, retired(f, i).readBytes()); custody(f, i)
    }

    @Test fun heldWriterCanChangeRetiredInodeButCannotDestroyIndependentOriginal() = fixture { f ->
        val i = prepare(f)
        RandomAccessFile(f.source, "rw").use { writer ->
            refused { f.journal(checkpoint = { if (it == "retirement-after-move") {
                writer.seek(0); writer.write("changed via open FD".toByteArray()); writer.fd.sync()
            } }).retireCanonical(i.id, context) }
        }
        assertTrue(retired(f, i).readText().startsWith("changed via open FD"))
        assertEquals(AnchoredWorkspaceFiles.MoveReceipt(true, 0), f.journal().read()!!.retirement)
        custody(f, i)
    }

    @Test fun fileToDirectoryOrSymlinkTransitionIsRetainedWithoutFollowingLinks() {
        for (kind in listOf("directory", "symlink")) fixture { f ->
            val i = prepare(f)
            val old = File(f.base, "external-old")
            refused { f.journal(checkpoint = { if (it == "retirement-before-move") {
                Files.move(f.source.toPath(), old.toPath())
                if (kind == "directory") {
                    check(f.source.mkdir()); File(f.source, "work").writeText("directory contents")
                } else Files.createSymbolicLink(f.source.toPath(), old.toPath())
            } }).retireCanonical(i.id, context) }
            if (kind == "directory") assertEquals("directory contents", File(retired(f, i), "work").readText())
            else assertEquals(old.toPath(), Files.readSymbolicLink(retired(f, i).toPath()))
            assertArrayEquals(f.bytes, old.readBytes()); custody(f, i)
        }
    }

    @Test fun rootAndNestedParentReplacementStopBeforeAnyMove() {
        for (root in listOf(false, true)) fixture { f ->
            val i = prepare(f)
            val original = if (root) f.root else f.source.parentFile
            val elsewhere = File(f.base, "moved-parent")
            refused { f.journal(checkpoint = { if (it == "retirement-before-move") {
                Files.move(original.toPath(), elsewhere.toPath())
                check(original.mkdir()); File(original, "new-work").writeText("replacement directory")
            } }).retireCanonical(i.id, context) }
            assertFalse(retired(f, i).exists())
            assertEquals(CanonicalFileOperationJournal.Phase.RETIREMENT_PREPARED, f.journal().readPendingMetadataForRecovery()!!.phase)
            assertArrayEquals(f.bytes, File(elsewhere, if (root) f.path else f.source.name).readBytes())
            custody(f, i)
        }
    }

    @Test fun corruptCustodyStopsAdmissionWithoutTouchingCanonicalFile() {
        for (which in listOf("before", "after", "publish")) fixture { f ->
            val i = prepare(f)
            File(f.custody, "${i.id}.$which").writeText("corrupt custody")
            val before = tree(f.base)
            refused { f.journal().retireCanonical(i.id, context) }
            assertEquals(before, tree(f.base)); assertEquals(i, f.journal().read())
        }
    }

    @Test fun intentCommitFailureNeverMovesSource() = fixture { f ->
        val i = prepare(f)
        val failure = IOException("intent commit rejected")
        assertSame(failure, refused { f.journal(checkpoint = { if (it == "retirement-intent-before-commit") throw failure })
            .retireCanonical(i.id, context) })
        assertEquals(i, f.journal().read()); assertEquals(i.before, f.before())
        assertFalse(retired(f, i).exists()); custody(f, i)
    }

    @Test fun interruptionsAtEachBoundaryKeepExplicitPendingStateWithoutReplay() {
        for (point in listOf("retirement-intent-after-commit", "retirement-before-move", "retirement-after-move",
                "retirement-outcome-before-commit", "retirement-outcome-after-commit")) fixture { f ->
            val i = prepare(f)
            val failure = IOException("simulated lost return")
            assertSame(failure, refused { f.journal(checkpoint = { if (it == point) throw failure })
                .retireCanonical(i.id, context) })
            val moved = point in listOf("retirement-after-move", "retirement-outcome-before-commit", "retirement-outcome-after-commit")
            val recorded = point == "retirement-outcome-after-commit"
            assertEquals(!moved, f.source.exists()); assertEquals(moved, retired(f, i).exists())
            assertEquals(if (recorded) CanonicalFileOperationJournal.Phase.RETIREMENT_RECORDED else
                CanonicalFileOperationJournal.Phase.RETIREMENT_PREPARED, f.journal().read()!!.phase)
            val before = tree(f.base)
            refused { f.journal().retireCanonical(i.id, context) }
            if (recorded) assertEquals(i.before, f.journal().verifyRetirement(i.id, context))
            else refused { f.journal().verifyRetirement(i.id, context) }
            assertEquals(before, tree(f.base)); custody(f, i)
        }
    }

    @Test fun cancellationBeforeAdmissionWritesNothing() = fixture { f ->
        val i = prepare(f)
        val before = tree(f.base)
        val job = Job().also { it.cancel() }
        assertTrue(refused { f.journal().retireCanonical(i.id, job) } is CancellationException)
        assertEquals(before, tree(f.base))
    }

    @Test fun cancellationAfterMovePreservesDataAndPendingIntent() = fixture { f ->
        val i = prepare(f)
        val job = Job()
        assertTrue(refused { f.journal(checkpoint = { if (it == "retirement-after-move") job.cancel() })
            .retireCanonical(i.id, job) } is CancellationException)
        assertEquals(CanonicalFileOperationJournal.Phase.RETIREMENT_PREPARED, f.journal().read()!!.phase)
        assertArrayEquals(f.bytes, retired(f, i).readBytes()); custody(f, i)
    }

    @Test fun missingRetiredFileIsNotRecreatedFromAnOldReceipt() = fixture { f ->
        val i = prepare(f)
        f.journal().retireCanonical(i.id, context)
        Files.move(retired(f, i).toPath(), File(f.base, "external-retired").toPath())
        val before = tree(f.base)
        refused { f.journal().verifyRetirement(i.id, context) }
        refused { f.journal().retireCanonical(i.id, context) }
        assertEquals(before, tree(f.base)); custody(f, i)
    }

    @Test fun corruptOutcomeEncodingFailsClosedWithoutRepairingMetadata() {
        for (bad in listOf("[0,0]", "[2,0]", "[1,-1]", "[1,4096]", "[1,4294967296]", "[true,0]", "[1,\"0\"]", "null")) fixture { f ->
            val i = prepare(f)
            f.journal().retireCanonical(i.id, context)
            f.sql { db ->
                val old = db.rawQuery("SELECT record_json FROM operation", null).use { it.moveToFirst(); it.getString(0) }
                val fields = (Json.parseToJsonElement(old) as JsonArray).toMutableList()
                fields[9] = Json.parseToJsonElement(bad)
                val record = JsonArray(fields).toString()
                db.execSQL("UPDATE operation SET record_json=?", arrayOf(record))
                db.execSQL("UPDATE meta SET record_digest=?", arrayOf(CanonicalJournalFixture.sha256(record.toByteArray())))
            }
            val before = tree(f.base)
            refused { f.journal().readPendingMetadataForRecovery() }
            assertEquals(before, tree(f.base)); custody(f, i)
        }
    }

    @Test fun outcomeCannotAppearInStagePhase() = fixture { f ->
        val i = prepare(f)
        f.journal().retireCanonical(i.id, context)
        f.sql { db ->
            val old = db.rawQuery("SELECT record_json FROM operation", null).use { it.moveToFirst(); it.getString(0) }
            val fields = (Json.parseToJsonElement(old) as JsonArray).toMutableList()
            fields[5] = JsonPrimitive("PUBLICATION_STAGED")
            val record = JsonArray(fields).toString()
            db.execSQL("UPDATE operation SET record_json=?", arrayOf(record))
            db.execSQL("UPDATE meta SET record_digest=?", arrayOf(CanonicalJournalFixture.sha256(record.toByteArray())))
        }
        val before = tree(f.base)
        refused { f.journal().read() }; assertEquals(before, tree(f.base))
    }

    @Test fun twoCallersCanOnlyAdmitOneAttempt() = fixture { f ->
        val i = prepare(f)
        val prepared = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val winner = executor.submit<CanonicalFileSnapshot.Version> {
                f.journal(checkpoint = { if (it == "retirement-intent-after-commit") {
                    prepared.countDown(); check(release.await(20, TimeUnit.SECONDS))
                } }).retireCanonical(i.id, context)
            }
            assertTrue(prepared.await(20, TimeUnit.SECONDS))
            refused { f.journal().retireCanonical(i.id, context) }
            assertEquals(i.before, f.before())
            release.countDown()
            assertEquals(i.before, winner.get(20, TimeUnit.SECONDS))
            assertEquals(AnchoredWorkspaceFiles.MoveReceipt(true, 0), f.journal().read()!!.retirement)
            custody(f, i)
        } finally { release.countDown(); executor.shutdownNow(); check(executor.awaitTermination(20, TimeUnit.SECONDS)) }
    }

    private fun prepare(f: CanonicalJournalFixture, stage: Boolean = true): CanonicalFileOperationJournal.Intent {
        val i = f.journal().admit(f.path, f.before(), f.proposal(), context)
        f.journal().capture(i.id, context)
        val input = File(f.base, "input")
        CanonicalJournalFixture.write(input, f.proposedBytes)
        AnchoredWorkspaceFiles(f.base).use { f.journal().captureProposal(i.id, it, input.name, f.before(input), context) }
        if (stage) f.journal().stagePublication(i.id, context)
        return f.journal().read()!!
    }

    private fun retired(f: CanonicalJournalFixture, i: CanonicalFileOperationJournal.Intent) = File(f.custody, i.retiredName)
    private fun custody(f: CanonicalJournalFixture, i: CanonicalFileOperationJournal.Intent) {
        assertArrayEquals(f.bytes, f.snapshotFile(i.id).readBytes())
        assertArrayEquals(f.proposedBytes, File(f.custody, i.proposalName).readBytes())
        assertArrayEquals(f.proposedBytes, File(f.custody, i.publicationName).readBytes())
    }
    private fun fixture(action: (CanonicalJournalFixture) -> Unit) = CanonicalJournalFixture().use(action)
    private fun refused(action: () -> Unit): Exception {
        try { action() } catch (failure: Exception) { return failure }
        throw AssertionError("Expected explicit refusal")
    }
    private fun tree(root: File): Map<String, String> = Files.walk(root.toPath()).use { paths ->
        paths.filter { !Files.isDirectory(it, NOFOLLOW_LINKS) }.toList().associate { path ->
            path.toString() to if (Files.isSymbolicLink(path)) "link:${Files.readSymbolicLink(path)}"
            else "${CanonicalJournalFixture.key(path.toFile())}:${Files.getLastModifiedTime(path)}:${CanonicalJournalFixture.sha256(Files.readAllBytes(path))}"
        }
    }
}
