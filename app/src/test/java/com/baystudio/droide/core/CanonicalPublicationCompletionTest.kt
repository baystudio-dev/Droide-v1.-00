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
class CanonicalPublicationCompletionTest {
    private val context = EmptyCoroutineContext

    @Test fun completionRetainsMembershipAndEveryVersionWithoutMutatingFiles() = fixture { f ->
        val i = prepare(f); val data = tree(f.base, true); val points = mutableListOf<String>()
        assertEquals(i.publicationStage, f.journal(checkpoint = { points += it }).recordPublicationCompletion(i.id, context))
        assertEquals(listOf("publication-completion-verified", "publication-completion-before-commit", "publication-completion-after-commit"), points)
        assertEquals(i.copy(phase = CanonicalFileOperationJournal.Phase.PUBLICATION_COMPLETED), f.journal().read())
        assertEquals(data, tree(f.base, true)); custody(f, i)
        refused { f.journal().admit(f.path, f.before(), f.proposal(), context) }
        refused { f.journal().publishStaged(i.id, context) }
    }

    @Test fun repeatedCompletionDoesNotRewriteMetadataOrFiles() = fixture { f ->
        val i = prepare(f); f.journal().recordPublicationCompletion(i.id, context); val before = tree(f.base)
        repeat(3) { assertEquals(i.publicationStage, f.journal(checkpoint = { error("Unexpected new completion") }).recordPublicationCompletion(i.id, context)) }
        assertEquals(before, tree(f.base))
    }

    @Test fun foreignOperationAndPrepublicationCannotComplete() = fixture { f ->
        val i = prepare(f, publish = false); val before = tree(f.base)
        refused { f.journal().recordPublicationCompletion(i.id, context) }
        refused { f.journal().recordPublicationCompletion(UUID.randomUUID().toString(), context) }
        assertEquals(before, tree(f.base))
    }

    @Test fun uncertainPublicationCannotBeInferredFromMatchingBytes() = fixture { f ->
        val i = prepare(f, publish = false)
        refused { f.journal(checkpoint = { if (it == "publication-after-move") throw IOException("lost outcome") }).publishStaged(i.id, context) }
        assertEquals(i.publicationStage, f.before()); val before = tree(f.base)
        refused { f.journal().recordPublicationCompletion(i.id, context) }
        assertEquals(before, tree(f.base))
    }

    @Test fun failedOutcomeCannotCompleteOrRemoveCompetingFile() = fixture { f ->
        val i = prepare(f, publish = false)
        refused { f.journal(checkpoint = { if (it == "publication-before-move") f.source.writeText("competing file") }).publishStaged(i.id, context) }
        val before = tree(f.base); refused { f.journal().recordPublicationCompletion(i.id, context) }
        assertEquals(before, tree(f.base)); assertEquals("competing file", f.source.readText())
    }

    @Test fun everyCustodyOrCanonicalChangeBlocksCompletion() {
        for (name in listOf("before", "after", "retired", "canonical", "publish")) fixture { f ->
            val i = prepare(f); val file = if (name == "canonical") f.source else File(f.custody, "${i.id}.$name")
            file.writeText("preserve changed $name"); val before = tree(f.base)
            refused { f.journal().recordPublicationCompletion(i.id, context) }; assertEquals(before, tree(f.base))
            assertEquals(CanonicalFileOperationJournal.Phase.PUBLICATION_RECORDED, f.journal().read()!!.phase)
        }
    }

    @Test fun storedConflictCannotCompleteEvenAfterExactVersionReturns() = fixture { f ->
        val i = prepare(f); f.source.writeText("CLI change"); val conflict = f.journal().recordPublicationConflict(i.id, context)
        f.source.writeBytes(f.proposedBytes); assertEquals(i.publicationStage, f.before()); val before = tree(f.base)
        refused { f.journal().recordPublicationCompletion(i.id, context) }
        assertEquals(conflict, f.journal().read()!!.publicationConflict); assertEquals(before, tree(f.base))
    }

    @Test fun mutationBetweenVerificationAndCommitIsRechecked() {
        for (name in listOf("canonical", "retired", "before", "after", "publish")) fixture { f ->
            val i = prepare(f)
            refused { f.journal(checkpoint = { if (it == "publication-completion-verified") {
                (if (name == "canonical") f.source else File(f.custody, "${i.id}.$name")).writeText("concurrent change")
            } }).recordPublicationCompletion(i.id, context) }
            assertEquals(CanonicalFileOperationJournal.Phase.PUBLICATION_RECORDED, f.journal().read()!!.phase)
        }
    }

    @Test fun lastCommitGapChangePreservesHistoricalReceiptAndFailsCurrentVerification() = fixture { f ->
        val i = prepare(f)
        refused { f.journal(checkpoint = { if (it == "publication-completion-before-commit") f.source.writeText("last-gap edit") })
            .recordPublicationCompletion(i.id, context) }
        assertEquals(CanonicalFileOperationJournal.Phase.PUBLICATION_COMPLETED, f.journal().read()!!.phase)
        assertEquals("last-gap edit", f.source.readText()); custody(f, i)
        val receipt = f.journal().recordPublicationConflict(i.id, context)
        assertEquals(CanonicalFileOperationJournal.ConflictRole.CANONICAL, receipt.role)
        assertEquals(receipt, f.journal().read()!!.publicationConflict)
    }

    @Test fun editAfterCommitIsReportedBeforeReturningSuccess() = fixture { f ->
        val i = prepare(f)
        refused { f.journal(checkpoint = { if (it == "publication-completion-after-commit") f.source.writeText("after commit edit") })
            .recordPublicationCompletion(i.id, context) }
        assertEquals("after commit edit", f.source.readText()); custody(f, i)
    }

    @Test fun heldRetiredDescriptorCannotBeMistakenForImmutableSnapshot() = fixture { f ->
        val i = prepare(f)
        RandomAccessFile(File(f.custody, i.retiredName), "rw").use { writer ->
            refused { f.journal(checkpoint = { if (it == "publication-completion-verified") {
                writer.seek(0); writer.write("held writer".toByteArray()); writer.fd.sync()
            } }).recordPublicationCompletion(i.id, context) }
        }
        assertEquals(CanonicalFileOperationJournal.Phase.PUBLICATION_RECORDED, f.journal().read()!!.phase)
        custody(f, i)
    }

    @Test fun completionIsNotPermissionToIgnoreNewContentInRepeatedCalls() = fixture { f ->
        val i = prepare(f); f.journal().recordPublicationCompletion(i.id, context); f.source.writeText("later work")
        val before = tree(f.base); refused { f.journal().recordPublicationCompletion(i.id, context) }
        refused { f.journal().verifyPublication(i.id, context) }; assertEquals(before, tree(f.base))
    }

    @Test fun inodeModeAndKindChangesCannotComplete() {
        for (change in listOf("inode", "mode", "directory", "symlink", "missing")) fixture { f ->
            val i = prepare(f)
            if (change == "mode") Files.setAttribute(f.source.toPath(), "unix:mode", 292)
            else {
                val saved = File(f.base, "saved"); Files.move(f.source.toPath(), saved.toPath())
                when (change) {
                    "inode" -> CanonicalJournalFixture.write(f.source, f.proposedBytes)
                    "directory" -> { check(f.source.mkdir()); File(f.source, "data").writeText("keep") }
                    "symlink" -> Files.createSymbolicLink(f.source.toPath(), saved.toPath())
                }
            }
            val before = tree(f.base); refused { f.journal().recordPublicationCompletion(i.id, context) }
            assertEquals(before, tree(f.base))
        }
    }

    @Test fun exceptionBeforeCommitRollsBackMetadataOnly() = fixture { f ->
        val i = prepare(f); val before = tree(f.base, true); val failure = IOException("commit interrupted")
        assertSame(failure, refused { f.journal(checkpoint = { if (it == "publication-completion-before-commit") throw failure })
            .recordPublicationCompletion(i.id, context) })
        assertEquals(i, f.journal().read()); assertEquals(before, tree(f.base, true))
    }

    @Test fun cancellationAtEachPrecommitBoundaryLeavesDataAndPriorState() {
        for (point in listOf("start", "publication-completion-verified", "publication-completion-before-commit")) fixture { f ->
            val i = prepare(f); val job = Job(); if (point == "start") job.cancel(); val before = tree(f.base, true)
            assertTrue(refused { f.journal(checkpoint = { if (it == point) job.cancel() }).recordPublicationCompletion(i.id, job) } is CancellationException)
            assertEquals(i, f.journal().read()); assertEquals(before, tree(f.base, true))
        }
    }

    @Test fun lostReturnAfterCommitReusesReceiptWithoutRewriting() = fixture { f ->
        val i = prepare(f)
        refused { f.journal(checkpoint = { if (it == "publication-completion-after-commit") throw IOException("lost return") })
            .recordPublicationCompletion(i.id, context) }
        val before = tree(f.base)
        assertEquals(i.publicationStage, f.journal(checkpoint = { error("Unexpected rewrite") }).recordPublicationCompletion(i.id, context))
        assertEquals(before, tree(f.base))
    }

    @Test fun changedParentBeforeCommitRefusesAndPreservesBothTrees() = fixture { f ->
        val i = prepare(f); val parent = f.source.parentFile!!; val old = File(f.base, "previous-parent")
        refused { f.journal(checkpoint = { if (it == "publication-completion-before-commit") {
            Files.move(parent.toPath(), old.toPath()); check(parent.mkdir()); f.source.writeText("replacement tree")
        } }).recordPublicationCompletion(i.id, context) }
        assertEquals(i, f.journal().readPendingMetadataForRecovery()); assertArrayEquals(f.proposedBytes, File(old, f.source.name).readBytes())
        assertEquals("replacement tree", f.source.readText())
    }

    @Test fun missingSourceDoesNotHideHistoricalCompletionMetadata() = fixture { f ->
        val i = prepare(f); f.journal().recordPublicationCompletion(i.id, context); val recorded = f.journal().read()!!
        Files.move(f.root.toPath(), File(f.base, "retained-project").toPath()); val before = tree(f.base)
        assertEquals(recorded, f.journal().readPendingMetadataForRecovery())
        refused { f.journal().recordPublicationCompletion(i.id, context) }; assertEquals(before, tree(f.base))
    }

    @Test fun invalidDurabilityInCompletedMetadataFailsClosed() = fixture { f ->
        val i = prepare(f); f.journal().recordPublicationCompletion(i.id, context)
        rewrite(f) { it[11] = Json.parseToJsonElement("[1,5]") }
        val before = tree(f.base); refused { f.journal().readPendingMetadataForRecovery() }; assertEquals(before, tree(f.base))
    }

    @Test fun aSecondCallerAfterCommitDoesNotPublishOrWriteAgain() = fixture { f ->
        val i = prepare(f); val committed = CountDownLatch(1); val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<CanonicalFileSnapshot.Version> {
                f.journal(checkpoint = { if (it == "publication-completion-after-commit") {
                    committed.countDown(); check(release.await(20, TimeUnit.SECONDS))
                } }).recordPublicationCompletion(i.id, context)
            }
            assertTrue(committed.await(20, TimeUnit.SECONDS)); val before = tree(f.base)
            assertEquals(i.publicationStage, f.journal().recordPublicationCompletion(i.id, context))
            release.countDown(); assertEquals(i.publicationStage, first.get(20, TimeUnit.SECONDS)); assertEquals(before, tree(f.base))
        } finally { release.countDown(); executor.shutdownNow(); check(executor.awaitTermination(20, TimeUnit.SECONDS)) }
    }

    private fun prepare(f: CanonicalJournalFixture, publish: Boolean = true): CanonicalFileOperationJournal.Intent {
        val i = f.journal().admit(f.path, f.before(), f.proposal(), context); f.journal().capture(i.id, context)
        val input = File(f.base, "input"); CanonicalJournalFixture.write(input, f.proposedBytes)
        AnchoredWorkspaceFiles(f.base).use { f.journal().captureProposal(i.id, it, input.name, f.before(input), context) }
        f.journal().stagePublication(i.id, context); f.journal().retireCanonical(i.id, context); f.journal().recordRetainedSnapshot(i.id, context)
        if (publish) f.journal().publishStaged(i.id, context)
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
