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
class CanonicalPublicationRecoveryTest {
    private val context = EmptyCoroutineContext

    @Test fun explicitRecoveryPreservesUnknownNativeOutcomeAndEveryVersion() = fixture { f ->
        val i = prepare(f); val data = tree(f.base, true)
        assertNull(i.publication)
        assertEquals(i.publicationStage, f.journal().recoverPublishedState(i.id, context))
        val recovered = f.journal().read()!!
        assertEquals(CanonicalFileOperationJournal.Phase.PUBLICATION_RECOVERED, recovered.phase)
        assertNull(recovered.publication); assertEquals(i.publicationStage, recovered.publicationRecovery)
        assertEquals(data, tree(f.base, true)); custody(f, i)
        refused { f.journal().admit(f.path, f.before(), f.proposal(), context) }
        refused { f.journal().publishStaged(i.id, context) }
    }

    @Test fun recordedUncertainNativeOutcomeIsPreservedRatherThanReplacedWithSuccess() = fixture { f ->
        val i = prepare(f)
        rewrite(f) { fields -> fields[5] = JsonPrimitive("PUBLICATION_RECORDED"); fields += Json.parseToJsonElement("[1,5]") }
        f.journal().recoverPublishedState(i.id, context)
        assertEquals(AnchoredWorkspaceFiles.MoveReceipt(true, 5), f.journal().read()!!.publication)
        assertEquals(i.publicationStage, f.journal().read()!!.publicationRecovery)
        assertEquals(i.publicationStage, f.journal().verifyPublication(i.id, context))
    }

    @Test fun notYetMovedStageNeverGetsReplayedByRecovery() = fixture { f ->
        val i = prepare(f, stop = "publication-intent-after-commit"); val before = tree(f.base)
        refused { f.journal().recoverPublishedState(i.id, context) }
        assertFalse(f.source.exists()); assertArrayEquals(f.proposedBytes, File(f.custody, i.publicationName).readBytes())
        assertEquals(before, tree(f.base))
    }

    @Test fun failedNotMovedOutcomeCannotBeChangedIntoSuccessfulRecovery() = fixture { f ->
        val i = prepare(f)
        rewrite(f) { fields -> fields[5] = JsonPrimitive("PUBLICATION_RECORDED"); fields += Json.parseToJsonElement("[0,17]") }
        val before = tree(f.base); refused { f.journal().recoverPublishedState(i.id, context) }
        assertEquals(before, tree(f.base)); assertEquals(AnchoredWorkspaceFiles.MoveReceipt(false, 17), f.journal().read()!!.publication)
    }

    @Test fun foreignAndAlreadyDurableOperationsCannotAcquireRecoveryReceipts() = fixture { f ->
        val i = prepare(f, stop = "none"); val before = tree(f.base)
        refused { f.journal().recoverPublishedState(UUID.randomUUID().toString(), context) }
        refused { f.journal().recoverPublishedState(i.id, context) }; assertEquals(before, tree(f.base))
    }

    @Test fun repeatedRecoveryChecksCurrentStateWithoutAnotherForceOrWrite() = fixture { f ->
        val i = prepare(f); f.journal().recoverPublishedState(i.id, context); val before = tree(f.base)
        repeat(3) { assertEquals(i.publicationStage, f.journal(checkpoint = { error("Unexpected repeat operation: $it") }).recoverPublishedState(i.id, context)) }
        assertEquals(before, tree(f.base))
    }

    @Test fun completionOfRecoveredPublicationRetainsOriginalUncertaintyAndRecoveryReceipt() = fixture { f ->
        val i = prepare(f); f.journal().recoverPublishedState(i.id, context); f.journal().recordPublicationCompletion(i.id, context)
        val recorded = f.journal().read()!!; val before = tree(f.base)
        assertEquals(CanonicalFileOperationJournal.Phase.PUBLICATION_COMPLETED, recorded.phase)
        assertNull(recorded.publication); assertEquals(i.publicationStage, recorded.publicationRecovery)
        assertEquals(i.publicationStage, f.journal().recoverPublishedState(i.id, context)); assertEquals(before, tree(f.base))
    }

    @Test fun everyChangedCustodyOrCanonicalVersionBlocksRecovery() {
        for (name in listOf("before", "after", "retired", "publish", "canonical")) fixture { f ->
            val i = prepare(f); (if (name == "canonical") f.source else File(f.custody, "${i.id}.$name")).writeText("unpublished work")
            val before = tree(f.base); refused { f.journal().recoverPublishedState(i.id, context) }
            assertEquals(before, tree(f.base)); assertNull(f.journal().read()!!.publicationRecovery)
        }
    }

    @Test fun changedCanonicalIdentityModeAndKindAreNeverAdopted() {
        for (change in listOf("inode", "mode", "directory", "symlink", "missing")) fixture { f ->
            val i = prepare(f)
            if (change == "mode") Files.setAttribute(f.source.toPath(), "unix:mode", 292)
            else {
                val saved = File(f.base, "saved-version"); Files.move(f.source.toPath(), saved.toPath())
                when (change) {
                    "inode" -> CanonicalJournalFixture.write(f.source, f.proposedBytes)
                    "directory" -> { check(f.source.mkdir()); File(f.source, "data").writeText("directory work") }
                    "symlink" -> Files.createSymbolicLink(f.source.toPath(), saved.toPath())
                }
            }
            val before = tree(f.base); refused { f.journal().recoverPublishedState(i.id, context) }; assertEquals(before, tree(f.base))
        }
    }

    @Test fun contentChangesBetweenObservationAndFileForceAreRejected() = fixture { f ->
        val i = prepare(f)
        refused { f.journal(checkpoint = { if (it == "publication-recovery-verified") f.source.writeText("racing CLI edit") }).recoverPublishedState(i.id, context) }
        assertNull(f.journal().read()!!.publicationRecovery); assertEquals("racing CLI edit", f.source.readText()); custody(f, i)
    }

    @Test fun openedDescriptorCannotValidateAnotherNamedInode() = fixture { f ->
        val i = prepare(f); val saved = File(f.base, "pinned-version")
        refused { f.journal(checkpoint = { if (it == "snapshot-OPENED") {
            Files.move(f.source.toPath(), saved.toPath()); CanonicalJournalFixture.write(f.source, f.proposedBytes)
        } }).recoverPublishedState(i.id, context) }
        assertNull(f.journal().read()!!.publicationRecovery)
        assertArrayEquals(f.proposedBytes, saved.readBytes()); assertArrayEquals(f.proposedBytes, f.source.readBytes())
    }

    @Test fun heldDescriptorWriteAfterForceIsDetectedBeforeRecoveryCommit() = fixture { f ->
        val i = prepare(f)
        RandomAccessFile(f.source, "rw").use { writer ->
            refused { f.journal(checkpoint = { if (it == "snapshot-FORCED") { writer.seek(0); writer.write("newer bytes".toByteArray()); writer.fd.sync() } })
                .recoverPublishedState(i.id, context) }
        }
        assertNull(f.journal().read()!!.publicationRecovery); assertTrue(f.source.readText().startsWith("newer bytes")); custody(f, i)
    }

    @Test fun mutationAfterEachForceIsRecheckedBeforeRecording() {
        for (point in listOf("publication-recovery-file-forced", "publication-recovery-parent-forced", "publication-recovery-directories-forced")) fixture { f ->
            val i = prepare(f)
            refused { f.journal(checkpoint = { if (it == point) f.source.writeText("after-force work") }).recoverPublishedState(i.id, context) }
            assertNull(f.journal().read()!!.publicationRecovery); assertEquals("after-force work", f.source.readText()); custody(f, i)
        }
    }

    @Test fun changedParentBeforeForceLeavesBothTreesIntact() = fixture { f ->
        val i = prepare(f); val parent = f.source.parentFile!!; val old = File(f.base, "previous-parent")
        refused { f.journal(checkpoint = { if (it == "publication-recovery-verified") {
            Files.move(parent.toPath(), old.toPath()); check(parent.mkdir()); f.source.writeText("new tree")
        } }).recoverPublishedState(i.id, context) }
        assertEquals(i, f.journal().readPendingMetadataForRecovery()); assertEquals("new tree", f.source.readText())
        assertArrayEquals(f.proposedBytes, File(old, f.source.name).readBytes())
    }

    @Test fun lastCommitGapEditNeverReturnsSuccessfulCurrentRecovery() = fixture { f ->
        val i = prepare(f)
        refused { f.journal(checkpoint = { if (it == "publication-recovery-before-commit") f.source.writeText("last-gap work") }).recoverPublishedState(i.id, context) }
        assertEquals(i.publicationStage, f.journal().read()!!.publicationRecovery); assertNull(f.journal().read()!!.publication)
        assertEquals("last-gap work", f.source.readText()); custody(f, i)
    }

    @Test fun cancellationAtEveryBoundaryNeverDeletesWork() {
        for (point in listOf("start", "publication-recovery-verified", "snapshot-OPENED", "snapshot-COPIED", "snapshot-FORCED",
                "publication-recovery-file-forced", "publication-recovery-parent-forced", "publication-recovery-directories-forced", "publication-recovery-before-commit")) fixture { f ->
            val i = prepare(f); val job = Job(); if (point == "start") job.cancel(); val data = tree(f.base, true)
            assertTrue(refused { f.journal(checkpoint = { if (it == point) job.cancel() }).recoverPublishedState(i.id, job) } is CancellationException)
            assertEquals(i, f.journal().read()); assertEquals(data, tree(f.base, true))
        }
    }

    @Test fun exceptionBeforeCommitPreservesPriorMetadataAndFileData() = fixture { f ->
        val i = prepare(f); val data = tree(f.base, true); val failure = IOException("commit interrupted")
        assertSame(failure, refused { f.journal(checkpoint = { if (it == "publication-recovery-before-commit") throw failure }).recoverPublishedState(i.id, context) })
        assertEquals(i, f.journal().read()); assertEquals(data, tree(f.base, true))
    }

    @Test fun sqliteUpdateFailureDoesNotTurnAnUnknownOutcomeIntoEmptyState() = fixture { f ->
        val i = prepare(f)
        f.sql { it.execSQL("CREATE TRIGGER refuse_update BEFORE UPDATE ON operation BEGIN SELECT RAISE(ABORT,'test update failure'); END") }
        val before = tree(f.base); refused { f.journal().recoverPublishedState(i.id, context) }
        assertEquals(i, f.journal().read()); assertEquals(before, tree(f.base))
    }

    @Test fun lostReturnAfterRecoveryCommitIsIdempotent() = fixture { f ->
        val i = prepare(f)
        refused { f.journal(checkpoint = { if (it == "publication-recovery-after-commit") throw IOException("lost return") }).recoverPublishedState(i.id, context) }
        val before = tree(f.base); assertEquals(i.publicationStage, f.journal().recoverPublishedState(i.id, context)); assertEquals(before, tree(f.base))
    }

    @Test fun recoveryMetadataSurvivesAMissingRootWithoutAdoptingNewPaths() = fixture { f ->
        val i = prepare(f); f.journal().recoverPublishedState(i.id, context); val recorded = f.journal().read()!!
        Files.move(f.root.toPath(), File(f.base, "saved-project").toPath()); val before = tree(f.base)
        assertEquals(recorded, f.journal().readPendingMetadataForRecovery()); refused { f.journal().recoverPublishedState(i.id, context) }
        assertEquals(before, tree(f.base))
    }

    @Test fun recordedConflictAfterRecoveryStillRequiresExplicitResolution() = fixture { f ->
        val i = prepare(f); f.journal().recoverPublishedState(i.id, context); f.source.writeText("new CLI edit")
        val conflict = f.journal().recordPublicationConflict(i.id, context); f.source.writeBytes(f.proposedBytes)
        val before = tree(f.base); refused { f.journal().recoverPublishedState(i.id, context) }
        refused { f.journal().recordPublicationCompletion(i.id, context) }
        assertEquals(conflict, f.journal().read()!!.publicationConflict); assertEquals(before, tree(f.base))
    }

    @Test fun malformedRecoveryReceiptsAreRejectedWithoutNormalization() {
        for (bad in listOf("null", "foreign", "wrong-phase", "not-moved", "already-durable", "missing", "extra")) fixture { f ->
            val i = prepare(f); f.journal().recoverPublishedState(i.id, context)
            rewrite(f) { fields -> when (bad) {
                "null" -> fields[13] = JsonNull
                "foreign" -> fields[13] = fields[7]
                "wrong-phase" -> fields[5] = JsonPrimitive("PUBLICATION_PREPARED")
                "not-moved" -> fields[11] = Json.parseToJsonElement("[0,17]")
                "already-durable" -> fields[11] = Json.parseToJsonElement("[1,0]")
                "missing" -> fields.removeAt(13)
                "extra" -> fields.add(JsonPrimitive("unexpected"))
            } }
            val before = tree(f.base); refused { f.journal().readPendingMetadataForRecovery() }; assertEquals(before, tree(f.base))
        }
    }

    private fun prepare(f: CanonicalJournalFixture, stop: String = "publication-after-move"): CanonicalFileOperationJournal.Intent {
        val i = f.journal().admit(f.path, f.before(), f.proposal(), context); f.journal().capture(i.id, context)
        val input = File(f.base, "input"); CanonicalJournalFixture.write(input, f.proposedBytes)
        AnchoredWorkspaceFiles(f.base).use { f.journal().captureProposal(i.id, it, input.name, f.before(input), context) }
        f.journal().stagePublication(i.id, context); f.journal().retireCanonical(i.id, context); f.journal().recordRetainedSnapshot(i.id, context)
        if (stop == "none") f.journal().publishStaged(i.id, context)
        else refused { f.journal(checkpoint = { if (it == stop) throw IOException("interrupted publication") }).publishStaged(i.id, context) }
        return f.journal().read()!!
    }
    private fun custody(f: CanonicalJournalFixture, i: CanonicalFileOperationJournal.Intent) {
        assertArrayEquals(f.bytes, f.snapshotFile(i.id).readBytes()); assertArrayEquals(f.proposedBytes, File(f.custody, i.proposalName).readBytes())
        assertEquals(i.snapshot, f.journal().verifyRetainedSnapshot(i.id, context))
    }
    private fun fixture(action: (CanonicalJournalFixture) -> Unit) = CanonicalJournalFixture().use(action)
    private fun refused(action: () -> Unit): Exception {
        try { action() } catch (failure: Exception) { return failure }; throw AssertionError("Expected refusal")
    }
    private fun tree(root: File, dataOnly: Boolean = false): Map<String, String> = Files.walk(root.toPath()).use { paths ->
        paths.filter { !Files.isDirectory(it, NOFOLLOW_LINKS) && (!dataOnly || !it.fileName.toString().startsWith("state.sqlite")) }.toList().associate { path ->
            path.toString() to if (Files.isSymbolicLink(path)) "link:${Files.readSymbolicLink(path)}"
            else "${CanonicalJournalFixture.key(path.toFile())}:${Files.getLastModifiedTime(path)}:${Files.getAttribute(path, "unix:mode")}:${CanonicalJournalFixture.sha256(Files.readAllBytes(path))}"
        }
    }
    private fun rewrite(f: CanonicalJournalFixture, action: (MutableList<JsonElement>) -> Unit) = f.sql { db ->
        val record = db.rawQuery("SELECT record_json FROM operation", null).use { check(it.moveToFirst()); it.getString(0) }
        val fields = Json.parseToJsonElement(record).jsonArray.toMutableList(); action(fields); val changed = JsonArray(fields).toString()
        db.execSQL("UPDATE operation SET record_json=?", arrayOf(changed))
        db.execSQL("UPDATE meta SET record_digest=?", arrayOf(CanonicalJournalFixture.sha256(changed.toByteArray())))
    }
}
