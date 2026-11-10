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
class CanonicalCreationTest {
    private val context = EmptyCoroutineContext
    private val path = "src/deep/created.kt"
    private val policy = CanonicalJournalRetention.Policy(4, 10_000_000)

    @Test fun creationRequiresExplicitRetentionAndDoesNotImplyAnOriginalVersion() = fixture { f ->
        val before = tree(f.base)
        refused { f.journal().admitCreation(path, f.proposal(), context) }
        assertEquals(before, tree(f.base))
        val intent = admit(f)
        assertEquals(CanonicalFileOperationJournal.OperationKind.CREATE, intent.kind)
        assertNull(intent.before)
        assertNull(intent.snapshot)
        assertNull(intent.retirement)
        assertNull(intent.retainedSnapshotIdentity)
        assertFalse(target(f).exists())
        assertEquals(intent, f.journal().read())
    }

    @Test fun completeCreationRetainsOnlyProposalAndAllowsALaterReplacement() = fixture { f ->
        val intent = stage(f)
        val journal = f.journal()
        assertEquals(intent.publicationStage, journal.publishStaged(intent.id, context))
        journal.recordPublicationCompletion(intent.id, context)
        val completed = checkNotNull(journal.read())
        val data = tree(f.base, true)
        journal.archiveCompleted(intent.id, context)
        assertNull(journal.read())
        assertEquals(data, tree(f.base, true))
        assertEquals(listOf(completed), journal.readCompletedMetadataForRecovery())
        val review = journal.reviewCompletedCustody(intent.id, context)
        assertEquals(listOf(intent.proposalName), review.map { it.name })
        assertTrue(review.all { it.matches })
        assertArrayEquals(f.proposedBytes, target(f).readBytes())
        assertArrayEquals(f.bytes, f.source.readBytes())
        val replacement = journal.admit(path, f.before(target(f)), f.proposal(), context)
        assertEquals(CanonicalFileOperationJournal.OperationKind.REPLACE, replacement.kind)
        assertEquals(f.before(target(f)), replacement.before)
        journal.capture(replacement.id, context)
        assertTrue(journal.reviewCompletedCustody(intent.id, context).all { it.matches })
    }

    @Test fun originalFileApisRejectCreationWithoutFilesystemMutation() = fixture { f ->
        val intent = admit(f)
        val first = tree(f.base)
        refused { f.journal().capture(intent.id, context) }
        refused { f.journal().verifySnapshot(intent.id, context) }
        assertEquals(first, tree(f.base))
        copyProposal(f, intent.id)
        f.journal().stagePublication(intent.id, context)
        val staged = tree(f.base)
        refused { f.journal().retireCanonical(intent.id, context) }
        refused { f.journal().recordRetainedSnapshot(intent.id, context) }
        refused { f.journal().recoverRetiredState(intent.id, context) }
        assertEquals(staged, tree(f.base))
        f.journal().publishStaged(intent.id, context)
        val published = tree(f.base)
        refused { f.journal().verifyRetainedSnapshot(intent.id, context) }
        assertEquals(published, tree(f.base))
    }

    @Test fun existingTargetsAreNotAdoptedEvenWhenBytesEqualTheProposal() {
        for (kind in listOf("file", "same-bytes", "directory", "symlink")) fixture { f ->
            f.journal().enableCompletionRetention(policy, context)
            competingTarget(f, kind)
            val before = tree(f.base)
            refused { f.journal().admitCreation(path, f.proposal(), context) }
            assertEquals(before, tree(f.base))
            assertNull(f.journal().read())
        }
    }

    @Test fun contenderCreatedAfterAdmissionIsPreservedWithoutPublication() {
        for (kind in listOf("file", "same-bytes", "directory", "symlink")) fixture { f ->
            val intent = stage(f)
            competingTarget(f, kind)
            val before = tree(f.base)
            refused { f.journal().publishStaged(intent.id, context) }
            assertEquals(before, tree(f.base))
            assertEquals(intent, f.journal().read())
        }
    }

    @Test fun nativeNoReplacePreservesContendersCreatedInsideTheLastPublicationGap() {
        for (kind in listOf("file", "same-bytes", "directory", "symlink")) fixture { f ->
            val intent = stage(f)
            var contender: Map<String, String>? = null
            refused { f.journal(checkpoint = { if (it == "publication-before-move") {
                competingTarget(f, kind)
                contender = tree(f.base, true)
            } }).publishStaged(intent.id, context) }
            assertEquals(contender, tree(f.base, true))
            val recorded = checkNotNull(f.journal().read())
            assertFalse(checkNotNull(recorded.publication).published)
            assertFalse(checkNotNull(recorded.publication).durable)
            assertTrue(File(f.custody, intent.publicationName).isFile)
            val retained = tree(f.base)
            refused { f.journal().recoverPublishedState(intent.id, context) }
            refused { f.journal().publishStaged(intent.id, context) }
            assertEquals(retained, tree(f.base))
        }
    }

    @Test fun publicationRecoveryRequiresTheOriginalStagedInode() {
        for (change in listOf("none", "same-bytes-new-inode", "edited", "missing")) fixture { f ->
            val intent = stage(f)
            interrupted(f, intent.id, "publication-after-move")
            when (change) {
                "same-bytes-new-inode" -> {
                    Files.move(target(f).toPath(), File(f.base, "retained-published").toPath())
                    CanonicalJournalFixture.write(target(f), f.proposedBytes)
                }
                "edited" -> target(f).writeText("unpublished CLI work")
                "missing" -> Files.move(target(f).toPath(), File(f.base, "retained-published").toPath())
            }
            val before = tree(f.base, true)
            if (change == "none") {
                assertEquals(intent.publicationStage, f.journal().recoverPublishedState(intent.id, context))
                assertNull(f.journal().read()!!.publication)
                f.journal().recordPublicationCompletion(intent.id, context)
                f.journal().archiveCompleted(intent.id, context)
            } else refused { f.journal().recoverPublishedState(intent.id, context) }
            assertEquals(before, tree(f.base, true))
        }
    }

    @Test fun preparedButUnpublishedCreationIsNotReplayedByRecovery() = fixture { f ->
        val intent = stage(f)
        interrupted(f, intent.id, "publication-intent-after-commit")
        val before = tree(f.base)
        refused { f.journal().recoverPublishedState(intent.id, context) }
        assertEquals(before, tree(f.base))
        assertFalse(target(f).exists())
        assertTrue(File(f.custody, intent.publicationName).isFile)
    }

    @Test fun creationCompletionRetryDoesNotRewriteOrRepublish() = fixture { f ->
        val intent = stage(f)
        f.journal().publishStaged(intent.id, context)
        f.journal().recordPublicationCompletion(intent.id, context)
        val before = tree(f.base)
        repeat(3) { assertEquals(intent.publicationStage, f.journal().recordPublicationCompletion(intent.id, context)) }
        assertEquals(before, tree(f.base))
        refused { f.journal().publishStaged(intent.id, context) }
        assertEquals(before, tree(f.base))
    }

    @Test fun pendingCreationBlocksReplacementAndOtherCreations() = fixture { f ->
        val intent = admit(f)
        val before = tree(f.base)
        refused { f.journal().admit(f.path, f.before(), f.proposal(), context) }
        refused { f.journal().admitCreation("src/deep/another.kt", f.proposal(), context) }
        assertEquals(intent, f.journal().read())
        assertEquals(before, tree(f.base))
    }

    @Test fun traversalAndSymlinkedParentsAreRejectedWithoutAdmission() {
        for (candidate in listOf("../outside", "/outside", "src/../outside", "src//file", "src\\file", "linked/new.kt")) fixture { f ->
            f.journal().enableCompletionRetention(policy, context)
            Files.createSymbolicLink(File(f.root, "linked").toPath(), f.base.toPath())
            val before = tree(f.base)
            refused { f.journal().admitCreation(candidate, f.proposal(), context) }
            assertEquals(before, tree(f.base))
            assertNull(f.journal().read())
        }
    }

    @Test fun parentReplacementAtPublicationIsNotFollowed() = fixture { f ->
        val intent = stage(f)
        val parent = requireNotNull(target(f).parentFile)
        val retained = File(f.base, "retained-parent")
        refused { f.journal(checkpoint = { if (it == "publication-before-move") {
            Files.move(parent.toPath(), retained.toPath())
            check(parent.mkdir())
            target(f).writeText("replacement tree work")
        } }).publishStaged(intent.id, context) }
        assertEquals("replacement tree work", target(f).readText())
        assertFalse(File(retained, target(f).name).exists())
        assertArrayEquals(f.bytes, File(retained, f.source.name).readBytes())
        assertTrue(File(f.custody, intent.publicationName).exists())
    }

    @Test fun proposalAndStageTamperingArePreservedAndBlockPublication() {
        for (suffix in listOf("after", "publish", "before", "retired")) fixture { f ->
            val intent = stage(f)
            File(f.custody, "${intent.id}.$suffix").writeText("retained foreign bytes")
            val before = tree(f.base)
            refused { f.journal().publishStaged(intent.id, context) }
            assertEquals(before, tree(f.base))
            assertFalse(target(f).exists())
        }
    }

    @Test fun heldStagingDescriptorChangeIsDetectedAfterPublication() = fixture { f ->
        val intent = stage(f)
        RandomAccessFile(File(f.custody, intent.publicationName), "rw").use { writer ->
            refused { f.journal(checkpoint = { if (it == "publication-after-move") {
                writer.seek(0)
                writer.write("held writer work".toByteArray())
                writer.fd.sync()
            } }).publishStaged(intent.id, context) }
        }
        assertTrue(target(f).readText().startsWith("held writer work"))
        assertArrayEquals(f.proposedBytes, File(f.custody, intent.proposalName).readBytes())
        val conflict = f.journal().recordPublicationConflict(intent.id, context)
        assertEquals(CanonicalFileOperationJournal.ConflictRole.CANONICAL, conflict.role)
        f.journal().capturePublicationConflict(intent.id, context)
        val review = f.journal().inspectPublicationConflict(intent.id, context)
        assertTrue(review.currentMatchesRecordedObservation)
    }

    @Test fun cancellationBeforeAdmissionDoesNotCreateAnyCustody() {
        for (point in listOf("start", "admission-before-commit")) fixture { f ->
            f.journal().enableCompletionRetention(policy, context)
            val job = Job()
            if (point == "start") job.cancel()
            val before = tree(f.base, true)
            assertTrue(refused { f.journal(checkpoint = { if (it == point) job.cancel() })
                .admitCreation(path, f.proposal(), job) } is CancellationException)
            assertNull(f.journal().read())
            assertEquals(before, tree(f.base, true))
        }
    }

    @Test fun cancellationAtPublicationBoundariesPreservesBothProposalAndCanonicalState() {
        for (point in listOf("publication-intent-before-commit", "publication-intent-after-commit", "publication-before-move",
            "publication-after-move", "publication-outcome-before-commit", "publication-outcome-after-commit")) fixture { f ->
            val intent = stage(f)
            val job = Job()
            assertTrue(refused { f.journal(checkpoint = { if (it == point) job.cancel() }).publishStaged(intent.id, job) } is CancellationException)
            assertArrayEquals(f.proposedBytes, File(f.custody, intent.proposalName).readBytes())
            val moved = point in listOf("publication-after-move", "publication-outcome-before-commit", "publication-outcome-after-commit")
            assertEquals(moved, target(f).exists())
            if (moved) assertArrayEquals(f.proposedBytes, target(f).readBytes())
            else assertArrayEquals(f.proposedBytes, File(f.custody, intent.publicationName).readBytes())
        }
    }

    @Test fun sqliteFailureAfterMovePreservesRecoverableCreation() = fixture { f ->
        val intent = stage(f)
        refused { f.journal(checkpoint = { if (it == "publication-after-move") f.sql { db ->
            db.execSQL("CREATE TRIGGER refuse_outcome BEFORE UPDATE ON operation BEGIN SELECT RAISE(ABORT,'injected'); END")
        } }).publishStaged(intent.id, context) }
        assertArrayEquals(f.proposedBytes, target(f).readBytes())
        assertEquals(CanonicalFileOperationJournal.Phase.PUBLICATION_PREPARED, f.journal().read()!!.phase)
        f.sql { it.execSQL("DROP TRIGGER refuse_outcome") }
        assertEquals(intent.publicationStage, f.journal().recoverPublishedState(intent.id, context))
    }

    @Test fun missingWorkspaceDoesNotHideCreationMetadataOrArchivedProposal() = fixture { f ->
        val intent = stage(f)
        f.journal().publishStaged(intent.id, context)
        f.journal().recordPublicationCompletion(intent.id, context)
        f.journal().archiveCompleted(intent.id, context)
        Files.move(f.root.toPath(), File(f.base, "retained-workspace").toPath())
        val before = tree(f.base)
        assertEquals(intent.id, f.journal().readCompletedMetadataForRecovery().single().id)
        assertTrue(f.journal().reviewCompletedCustody(intent.id, context).all { it.matches })
        assertEquals(before, tree(f.base))
    }

    @Test fun malformedCreationCannotBeReinterpretedAsReplacementOrRetirement() {
        val mutations: List<(MutableList<JsonElement>) -> Unit> = listOf(
            { it.removeAt(16) },
            { it[16] = JsonNull },
            { it[16] = JsonPrimitive("REPLACE") },
            { it[16] = JsonPrimitive("UNKNOWN") },
            { it[3] = JsonArray(listOf(JsonPrimitive("fake"), JsonPrimitive(0), JsonPrimitive("0".repeat(64)), JsonPrimitive(420))) },
            { it[5] = JsonPrimitive("SNAPSHOT_RECORDED") },
            { it[5] = JsonPrimitive("RETIREMENT_PREPARED") },
            { it[5] = JsonPrimitive("RETENTION_RECORDED") },
            { it[9] = JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(0))) },
            { it[10] = JsonPrimitive("invented original") },
        )
        for (mutation in mutations) fixture { f ->
            stage(f)
            rewrite(f, mutation)
            val before = tree(f.base)
            refused { f.journal().readPendingMetadataForRecovery() }
            assertEquals(before, tree(f.base))
        }
    }

    @Test fun nullBeforeWithoutCreationTagIsCorruptReplacementMetadata() = fixture { f ->
        f.journal().admit(f.path, f.before(), f.proposal(), context)
        rewrite(f) { it[3] = JsonNull }
        val before = tree(f.base)
        refused { f.journal().readPendingMetadataForRecovery() }
        assertEquals(before, tree(f.base))
    }

    @Test fun creationCannotSurviveLossOfItsRequiredRetentionAuthority() = fixture { f ->
        val intent = admit(f)
        CanonicalJournalFixture.write(File(f.base, "proposal-input"), f.proposedBytes)
        f.sql { db ->
            db.execSQL("DROP TABLE completed_operation")
            db.execSQL("DROP TABLE retention")
            db.version = 1
        }
        val before = tree(f.base)
        refused { f.journal().readPendingMetadataForRecovery() }
        refused { copyProposal(f, intent.id) }
        assertEquals(before, tree(f.base))
    }

    @Test fun creationCapacityIncludesProposalStageAndConflictCustody() = fixture { f ->
        val peak = f.bytes.size + 2L * f.proposedBytes.size
        f.journal().enableCompletionRetention(policy.copy(maxBytes = peak - 1), context)
        val before = tree(f.base)
        refused { f.journal().admitCreation(path, f.proposal(), context) }
        assertEquals(before, tree(f.base))
        assertNull(f.journal().read())
    }

    @Test fun interruptedProposalReceiptPreservesPartialCustodyWithoutAdoption() = fixture { f ->
        val intent = admit(f)
        val error = IOException("proposal receipt interrupted")
        val journal = f.journal(checkpoint = { if (it == "proposal-before-commit") throw error })
        assertSame(error, refused { copyProposal(f, intent.id, journal) })
        val before = tree(f.base)
        assertEquals(intent, f.journal().read())
        refused { copyProposal(f, intent.id) }
        assertEquals(before, tree(f.base))
        assertArrayEquals(f.proposedBytes, File(f.custody, intent.proposalName).readBytes())
    }

    private fun admit(f: CanonicalJournalFixture): CanonicalFileOperationJournal.Intent {
        f.journal().enableCompletionRetention(policy, context)
        return f.journal().admitCreation(path, f.proposal(), context)
    }

    private fun stage(f: CanonicalJournalFixture): CanonicalFileOperationJournal.Intent {
        val intent = admit(f)
        copyProposal(f, intent.id)
        f.journal().stagePublication(intent.id, context)
        return checkNotNull(f.journal().read())
    }

    private fun copyProposal(f: CanonicalJournalFixture, id: String, journal: CanonicalFileOperationJournal = f.journal()) {
        val input = File(f.base, "proposal-input")
        if (!input.exists()) CanonicalJournalFixture.write(input, f.proposedBytes)
        AnchoredWorkspaceFiles(f.base).use { journal.captureProposal(id, it, input.name, f.before(input), context) }
    }

    private fun interrupted(f: CanonicalJournalFixture, id: String, point: String) {
        val failure = IOException("injected at $point")
        assertSame(failure, refused { f.journal(checkpoint = { if (it == point) throw failure }).publishStaged(id, context) })
    }

    private fun competingTarget(f: CanonicalJournalFixture, kind: String) {
        when (kind) {
            "file" -> target(f).writeText("competing work")
            "same-bytes" -> CanonicalJournalFixture.write(target(f), f.proposedBytes)
            "directory" -> { check(target(f).mkdir()); File(target(f), "user-work").writeText("keep") }
            "symlink" -> Files.createSymbolicLink(target(f).toPath(), f.source.toPath())
            else -> error("Unknown fixture kind")
        }
    }

    private fun rewrite(f: CanonicalJournalFixture, action: (MutableList<JsonElement>) -> Unit) = f.sql { db ->
        val record = db.rawQuery("SELECT record_json FROM operation", null).use { check(it.moveToFirst()); it.getString(0) }
        val fields = Json.parseToJsonElement(record).jsonArray.toMutableList()
        action(fields)
        val changed = JsonArray(fields).toString()
        db.execSQL("UPDATE operation SET record_json=?", arrayOf(changed))
        db.execSQL("UPDATE meta SET record_digest=?", arrayOf(CanonicalJournalFixture.sha256(changed.toByteArray())))
    }

    private fun target(f: CanonicalJournalFixture) = File(f.root, path)
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
