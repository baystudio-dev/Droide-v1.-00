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
class CanonicalPublicationConflictTest {
    private val context = EmptyCoroutineContext

    @Test fun recordsCanonicalConflictWithExactObservedVersionWithoutChangingData() = fixture { f ->
        val i = prepare(f); f.source.writeText("new CLI version")
        val version = f.before(); val data = tree(f.base, true)
        val receipt = f.journal().recordPublicationConflict(i.id, context)
        assertEquals(CanonicalFileOperationJournal.PublicationConflict(CanonicalFileOperationJournal.ConflictRole.CANONICAL,
            AnchoredWorkspaceFiles.Kind.FILE, version), receipt)
        assertEquals(receipt, f.journal().read()!!.publicationConflict)
        assertEquals(data, tree(f.base, true))
        assertTrue(refused { f.journal().verifyPublication(i.id, context) }.message!!.contains("explicit resolution"))
        custody(f, i)
    }

    @Test fun heldDescriptorConflictIsRetainedAfterSuccessfulPublication() = fixture { f ->
        val i = prepare(f, publish = false)
        RandomAccessFile(retired(f, i), "rw").use { writer ->
            f.journal().publishStaged(i.id, context)
            writer.seek(0); writer.write("late descriptor edit".toByteArray()); writer.fd.sync()
            val data = tree(f.base, true)
            val receipt = f.journal().recordPublicationConflict(i.id, context)
            assertEquals(CanonicalFileOperationJournal.ConflictRole.RETIRED, receipt.role)
            assertEquals(f.before(retired(f, i)), receipt.observed)
            assertEquals(data, tree(f.base, true))
        }
        assertEquals(i.snapshot, f.journal().verifyRetainedSnapshot(i.id, context))
        assertArrayEquals(f.bytes, f.snapshotFile(i.id).readBytes())
    }

    @Test fun recreatedStageIsAConflictRegardlessOfItsKind() {
        for (kind in listOf("file", "directory", "symlink")) fixture { f ->
            val i = prepare(f); val stage = File(f.custody, i.publicationName)
            when (kind) {
                "file" -> stage.writeText("new stage data")
                "directory" -> check(stage.mkdir())
                else -> Files.createSymbolicLink(stage.toPath(), f.source.toPath())
            }
            val data = tree(f.base, true)
            val receipt = f.journal().recordPublicationConflict(i.id, context)
            assertEquals(CanonicalFileOperationJournal.ConflictRole.STAGE, receipt.role)
            assertEquals(AnchoredWorkspaceFiles(f.custody).use { it.kind(stage.name) }, receipt.kind)
            assertEquals(data, tree(f.base, true)); custody(f, i)
        }
    }

    @Test fun missingDirectoryAndSymlinkVersionsDoNotFollowOrRecreateEntries() {
        for (role in listOf("canonical", "retired")) for (kind in listOf("missing", "directory", "symlink")) fixture { f ->
            val i = prepare(f); val target = if (role == "canonical") f.source else retired(f, i)
            val saved = File(f.base, "external-version"); Files.move(target.toPath(), saved.toPath())
            if (kind == "directory") { check(target.mkdir()); File(target, "kept").writeText("directory data") }
            if (kind == "symlink") Files.createSymbolicLink(target.toPath(), saved.toPath())
            val data = tree(f.base, true)
            val receipt = f.journal().recordPublicationConflict(i.id, context)
            assertEquals(if (role == "canonical") CanonicalFileOperationJournal.ConflictRole.CANONICAL else
                CanonicalFileOperationJournal.ConflictRole.RETIRED, receipt.role)
            assertNull(receipt.observed)
            assertEquals(if (kind == "missing") AnchoredWorkspaceFiles.Kind.ABSENT else if (kind == "directory")
                AnchoredWorkspaceFiles.Kind.DIRECTORY else AnchoredWorkspaceFiles.Kind.SYMLINK, receipt.kind)
            assertEquals(data, tree(f.base, true))
        }
    }

    @Test fun matchingPublicationCannotAcquireAConflictReceipt() = fixture { f ->
        val i = prepare(f); val before = tree(f.base)
        refused { f.journal().recordPublicationConflict(i.id, context) }
        assertEquals(before, tree(f.base)); assertNull(f.journal().read()!!.publicationConflict)
        assertEquals(i.publicationStage, f.journal().verifyPublication(i.id, context))
    }

    @Test fun foreignOrUnpublishedOperationCannotRecordAConflict() = fixture { f ->
        val i = prepare(f, publish = false); val before = tree(f.base)
        refused { f.journal().recordPublicationConflict(i.id, context) }
        refused { f.journal().recordPublicationConflict(UUID.randomUUID().toString(), context) }
        assertEquals(before, tree(f.base))
    }

    @Test fun failedNativeOutcomeStillRequiresRecoveryRatherThanAConflictReceipt() = fixture { f ->
        val i = prepare(f, publish = false)
        refused { f.journal(checkpoint = { if (it == "publication-before-move") f.source.writeText("occupied") })
            .publishStaged(i.id, context) }
        val before = tree(f.base)
        refused { f.journal().recordPublicationConflict(i.id, context) }
        assertEquals(before, tree(f.base)); assertEquals(AnchoredWorkspaceFiles.MoveReceipt(false, 17), f.journal().read()!!.publication)
    }

    @Test fun damagedIndependentCustodyCannotBeMisreportedAsAnOrdinaryConflict() {
        for (name in listOf("before", "after")) fixture { f ->
            val i = prepare(f); f.source.writeText("CLI edit"); File(f.custody, "${i.id}.$name").writeText("damaged custody")
            val before = tree(f.base)
            refused { f.journal().recordPublicationConflict(i.id, context) }
            assertEquals(before, tree(f.base)); assertNull(f.journal().read()!!.publicationConflict)
        }
    }

    @Test fun sameBytesOnDifferentCanonicalInodeRemainAConflict() = fixture { f ->
        val i = prepare(f); Files.move(f.source.toPath(), File(f.base, "original-published").toPath())
        CanonicalJournalFixture.write(f.source, f.proposedBytes)
        val receipt = f.journal().recordPublicationConflict(i.id, context)
        assertEquals(i.publicationStage!!.sha256, receipt.observed!!.sha256)
        assertNotEquals(i.publicationStage.identity, receipt.observed.identity)
        assertEquals(receipt, f.journal().readPendingMetadataForRecovery()!!.publicationConflict)
    }

    @Test fun permissionChangesAreRecordedWithoutWideningAdmissionPolicy() = fixture { f ->
        val i = prepare(f); Files.setAttribute(f.source.toPath(), "unix:mode", 292)
        val data = tree(f.base, true)
        val receipt = f.journal().recordPublicationConflict(i.id, context)
        assertEquals(292, receipt.observed!!.mode)
        assertEquals(receipt, f.journal().read()!!.publicationConflict)
        assertEquals(420, i.publicationStage!!.mode); assertEquals(data, tree(f.base, true))
    }

    @Test fun repeatedRecordingReturnsHistoricalReceiptWithoutRewriteOrObservation() = fixture { f ->
        val i = prepare(f); f.source.writeText("observed edit")
        val receipt = f.journal().recordPublicationConflict(i.id, context)
        f.source.writeText("later edit")
        val before = tree(f.base)
        repeat(3) { assertEquals(receipt, f.journal(checkpoint = { error("Unexpected new recording: $it") }).recordPublicationConflict(i.id, context)) }
        assertEquals(before, tree(f.base)); assertNotEquals(f.before(), receipt.observed)
    }

    @Test fun changingOrResolvingConflictBetweenObservationsDoesNotRecordStaleEvidence() {
        for (restore in listOf(false, true)) fixture { f ->
            val i = prepare(f); f.source.writeText("first edit")
            refused { f.journal(checkpoint = { if (it == "publication-conflict-observed") {
                f.source.writeBytes(if (restore) f.proposedBytes else "second edit".toByteArray())
            } }).recordPublicationConflict(i.id, context) }
            assertNull(f.journal().read()!!.publicationConflict)
            assertArrayEquals(if (restore) f.proposedBytes else "second edit".toByteArray(), f.source.readBytes())
            custody(f, i)
        }
    }

    @Test fun changesInLastCommitGapDoNotClearTheRecordedHistoricalConflict() = fixture { f ->
        val i = prepare(f); f.source.writeText("observed edit"); val observed = f.before()
        val receipt = f.journal(checkpoint = { if (it == "publication-conflict-before-commit") f.source.writeText("newer edit") })
            .recordPublicationConflict(i.id, context)
        assertEquals(observed, receipt.observed); assertEquals("newer edit", f.source.readText())
        assertEquals(receipt, f.journal().read()!!.publicationConflict)
        refused { f.journal().verifyPublication(i.id, context) }; custody(f, i)
    }

    @Test fun restoredMatchingBytesCannotSilentlyClearARecordedConflict() = fixture { f ->
        val i = prepare(f); f.source.writeText("CLI edit")
        val receipt = f.journal().recordPublicationConflict(i.id, context)
        f.source.writeBytes(f.proposedBytes)
        assertEquals(i.publicationStage, f.before())
        val before = tree(f.base)
        assertTrue(refused { f.journal().verifyPublication(i.id, context) }.message!!.contains("explicit resolution"))
        assertEquals(receipt, f.journal().recordPublicationConflict(i.id, context))
        assertEquals(before, tree(f.base)); custody(f, i)
    }

    @Test fun exceptionBeforeCommitRollsBackMetadataAndPreservesConflictingData() = fixture { f ->
        val i = prepare(f); f.source.writeText("CLI edit"); val error = IOException("commit rejected")
        val data = tree(f.base, true)
        assertSame(error, refused { f.journal(checkpoint = { if (it == "publication-conflict-before-commit") throw error })
            .recordPublicationConflict(i.id, context) })
        assertNull(f.journal().read()!!.publicationConflict); assertEquals(data, tree(f.base, true))
    }

    @Test fun cancellationBeforeCommitNeverDeletesConflictingVersions() {
        for (point in listOf("start", "publication-conflict-observed", "publication-conflict-before-commit")) fixture { f ->
            val i = prepare(f); f.source.writeText("CLI edit"); val job = Job(); if (point == "start") job.cancel()
            val data = tree(f.base, true)
            assertTrue(refused { f.journal(checkpoint = { if (it == point) job.cancel() }).recordPublicationConflict(i.id, job) } is CancellationException)
            assertNull(f.journal().read()!!.publicationConflict); assertEquals(data, tree(f.base, true))
        }
    }

    @Test fun lostReturnAfterCommitCanBeReadAndReusedWithoutRewriting() = fixture { f ->
        val i = prepare(f); f.source.writeText("CLI edit")
        refused { f.journal(checkpoint = { if (it == "publication-conflict-after-commit") throw IOException("lost return") })
            .recordPublicationConflict(i.id, context) }
        val stored = f.journal().read()!!.publicationConflict!!; val before = tree(f.base)
        assertEquals(stored, f.journal().recordPublicationConflict(i.id, context))
        assertEquals(before, tree(f.base))
    }

    @Test fun parentReplacementBeforeCommitRollsBackAndKeepsRecoveryMetadataReadable() = fixture { f ->
        val i = prepare(f); f.source.writeText("CLI edit"); val parent = f.source.parentFile; val moved = File(f.base, "moved-parent")
        refused { f.journal(checkpoint = { if (it == "publication-conflict-before-commit") {
            Files.move(parent.toPath(), moved.toPath()); check(parent.mkdir()); f.source.writeText("new parent work")
        } }).recordPublicationConflict(i.id, context) }
        assertNull(f.journal().readPendingMetadataForRecovery()!!.publicationConflict)
        assertEquals("CLI edit", File(moved, f.source.name).readText()); assertEquals("new parent work", f.source.readText())
    }

    @Test fun committedReceiptSurvivesMissingSourceRootForMetadataRecovery() = fixture { f ->
        val i = prepare(f); f.source.writeText("CLI edit"); val receipt = f.journal().recordPublicationConflict(i.id, context)
        Files.move(f.root.toPath(), File(f.base, "external-root").toPath())
        assertEquals(receipt, f.journal().readPendingMetadataForRecovery()!!.publicationConflict)
        refused { f.journal().verifyPublication(i.id, context) }
        assertArrayEquals(f.bytes, f.snapshotFile(i.id).readBytes())
    }

    @Test fun malformedOrNonConflictingReceiptsFailClosedWithoutRepair() {
        for (bad in listOf("null", "[\"UNKNOWN\",\"FILE\",null]", "[\"CANONICAL\",\"FILE\",null]",
                "[\"STAGE\",\"ABSENT\",null]", "matching", "wrong-phase", "failed-outcome", "overflow-mode")) fixture { f ->
            val i = prepare(f); f.source.writeText("CLI edit"); f.journal().recordPublicationConflict(i.id, context)
            rewrite(f) { fields ->
                when (bad) {
                    "matching" -> fields[12] = JsonArray(listOf(JsonPrimitive("CANONICAL"), JsonPrimitive("FILE"), fields[8]))
                    "wrong-phase" -> fields[5] = JsonPrimitive("PUBLICATION_PREPARED")
                    "failed-outcome" -> fields[11] = Json.parseToJsonElement("[1,5]")
                    "overflow-mode" -> {
                        val conflict = fields[12].jsonArray.toMutableList(); val version = conflict[2].jsonArray.toMutableList()
                        version[3] = JsonPrimitive(4294967296L); conflict[2] = JsonArray(version); fields[12] = JsonArray(conflict)
                    }
                    else -> fields[12] = Json.parseToJsonElement(bad)
                }
            }
            val before = tree(f.base)
            refused { f.journal().readPendingMetadataForRecovery() }; assertEquals(before, tree(f.base))
        }
    }

    @Test fun secondCallerReusesTheFirstReceiptAfterCommit() = fixture { f ->
        val i = prepare(f); f.source.writeText("CLI edit")
        val committed = CountDownLatch(1); val release = CountDownLatch(1); val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<CanonicalFileOperationJournal.PublicationConflict> {
                f.journal(checkpoint = { if (it == "publication-conflict-after-commit") {
                    committed.countDown(); check(release.await(20, TimeUnit.SECONDS))
                } }).recordPublicationConflict(i.id, context)
            }
            assertTrue(committed.await(20, TimeUnit.SECONDS)); val before = tree(f.base)
            val second = f.journal(checkpoint = { error("Unexpected second record") }).recordPublicationConflict(i.id, context)
            release.countDown(); assertEquals(first.get(20, TimeUnit.SECONDS), second); assertEquals(before, tree(f.base))
        } finally { release.countDown(); executor.shutdownNow(); check(executor.awaitTermination(20, TimeUnit.SECONDS)) }
    }

    @Test fun oversizedUnstableObservationIsRefusedWithoutChangingData() = fixture { f ->
        val i = prepare(f); f.source.writeBytes(ByteArray(f.bytes.size + 1) { 1 }); val before = tree(f.base)
        assertTrue(refused { f.journal().recordPublicationConflict(i.id, context) }.message!!.contains("snapshot budget"))
        assertNull(f.journal().read()!!.publicationConflict); assertEquals(before, tree(f.base))
    }

    private fun prepare(f: CanonicalJournalFixture, publish: Boolean = true): CanonicalFileOperationJournal.Intent {
        val i = f.journal().admit(f.path, f.before(), f.proposal(), context)
        f.journal().capture(i.id, context); val input = File(f.base, "input"); CanonicalJournalFixture.write(input, f.proposedBytes)
        AnchoredWorkspaceFiles(f.base).use { f.journal().captureProposal(i.id, it, input.name, f.before(input), context) }
        f.journal().stagePublication(i.id, context); f.journal().retireCanonical(i.id, context)
        f.journal().recordRetainedSnapshot(i.id, context); if (publish) f.journal().publishStaged(i.id, context)
        return f.journal().read()!!
    }
    private fun rewrite(f: CanonicalJournalFixture, edit: (MutableList<JsonElement>) -> Unit) = f.sql { db ->
        val old = db.rawQuery("SELECT record_json FROM operation", null).use { it.moveToFirst(); it.getString(0) }
        val fields = Json.parseToJsonElement(old).jsonArray.toMutableList(); edit(fields); val record = JsonArray(fields).toString()
        db.execSQL("UPDATE operation SET record_json=?", arrayOf(record)); db.execSQL("UPDATE meta SET record_digest=?", arrayOf(CanonicalJournalFixture.sha256(record.toByteArray())))
    }
    private fun retired(f: CanonicalJournalFixture, i: CanonicalFileOperationJournal.Intent) = File(f.custody, i.retiredName)
    private fun custody(f: CanonicalJournalFixture, i: CanonicalFileOperationJournal.Intent) {
        assertArrayEquals(f.bytes, f.snapshotFile(i.id).readBytes()); assertArrayEquals(f.bytes, retired(f, i).readBytes())
        assertArrayEquals(f.proposedBytes, File(f.custody, i.proposalName).readBytes())
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
