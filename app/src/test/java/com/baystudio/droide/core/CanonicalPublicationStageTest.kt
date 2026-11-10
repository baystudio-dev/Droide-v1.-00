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
import kotlinx.serialization.json.JsonNull
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
class CanonicalPublicationStageTest {
    private val context = EmptyCoroutineContext

    @Test fun recordsIndependentStageOnlyAfterForceAndCommit() = fixture { f ->
        val intent = prepare(f)
        val points = mutableListOf<String>()
        val stage = f.journal(checkpoint = { at ->
            points += at
            if (at.startsWith("publication-stage-") && !at.endsWith("commit")) {
                assertEquals(intent, f.journal().read())
            }
        }).stagePublication(intent.id, context)
        val recorded = f.journal().read()!!
        assertEquals(CanonicalFileOperationJournal.Phase.PUBLICATION_STAGED, recorded.phase)
        assertEquals(stage, recorded.publicationStage)
        assertEquals(intent.proposal, CanonicalFileOperationJournal.Content(stage.size, stage.sha256, stage.mode))
        assertFalse(stage.identity in listOf(checkNotNull(intent.before).identity, intent.snapshot!!.identity, intent.proposalCopy!!.identity))
        assertTrue(points.indexOf("publication-stage-FORCED") < points.indexOf("publication-stage-before-commit"))
        assertEquals("publication-stage-after-commit", points.last())
        assertEquals(stage, f.journal().verifyPublicationStage(intent.id, context))
        originals(f, intent)
    }

    @Test fun emptyPrivateAndExecutableStagesRetainExactMode() {
        for ((bytes, mode) in listOf(ByteArray(0) to 384, byteArrayOf(1, 2) to 384, byteArrayOf(3) to 493)) fixture { f ->
            val intent = prepare(f, bytes, mode)
            val staged = f.journal().stagePublication(intent.id, context)
            assertEquals(mode, staged.mode)
            assertEquals(mode, mode(stage(f, intent)))
            assertArrayEquals(bytes, stage(f, intent).readBytes())
            assertEquals(staged, f.journal().verifyPublicationStage(intent.id, context))
        }
    }

    @Test fun repeatedStagingAndVerificationDoNotRewriteReceiptsOrCopies() = fixture { f ->
        val intent = prepare(f)
        val staged = f.journal().stagePublication(intent.id, context)
        val before = tree(f.base)
        repeat(4) {
            assertEquals(staged, f.journal().stagePublication(intent.id, context))
            assertEquals(staged, f.journal().verifyPublicationStage(intent.id, context))
            assertEquals(intent.proposalCopy, f.journal().verifyProposal(intent.id, context))
            assertEquals(intent.snapshot, f.journal().verifySnapshot(intent.id, context).copy)
        }
        assertEquals(before, tree(f.base))
    }

    @Test fun admissionOrSnapshotAloneCannotAuthorizeStaging() {
        for (captured in listOf(false, true)) fixture { f ->
            val intent = f.journal().admit(f.path, f.before(), f.proposal(), context)
            if (captured) f.journal().capture(intent.id, context)
            val before = tree(f.base)
            refused { f.journal().stagePublication(intent.id, context) }
            refused { f.journal().verifyPublicationStage(intent.id, context) }
            assertEquals(before, tree(f.base))
        }
    }

    @Test fun foreignOperationCannotCreateOrVerifyAStage() = fixture { f ->
        val intent = prepare(f)
        val other = UUID.randomUUID().toString()
        val before = tree(f.base)
        refused { f.journal().stagePublication(other, context) }
        assertEquals(before, tree(f.base))
        f.journal().stagePublication(intent.id, context)
        val after = tree(f.base)
        refused { f.journal().verifyPublicationStage(other, context) }
        assertEquals(after, tree(f.base))
    }

    @Test fun canonicalEditsAreNotOverwrittenOrMisrepresentedAsPublished() = fixture { f ->
        val intent = prepare(f)
        f.source.writeText("new editor version")
        val canonical = f.before()
        f.journal().stagePublication(intent.id, context)
        assertEquals(canonical, f.before())
        assertEquals(intent.before, f.journal().read()!!.before)
        assertArrayEquals(f.bytes, f.snapshotFile(intent.id).readBytes())
        assertArrayEquals(f.proposedBytes, stage(f, intent).readBytes())
    }

    @Test fun occupiedStageNamesAreNeverReplacedOrAdopted() {
        for (kind in listOf("file", "directory", "symlink", "hardlink")) fixture { f ->
            val intent = prepare(f)
            val output = stage(f, intent)
            when (kind) {
                "file" -> output.writeBytes(f.proposedBytes)
                "directory" -> check(output.mkdir())
                "symlink" -> Files.createSymbolicLink(output.toPath(), File(f.custody, intent.proposalName).toPath())
                "hardlink" -> Files.createLink(output.toPath(), File(f.custody, intent.proposalName).toPath())
            }
            val before = tree(f.base)
            refused { f.journal().stagePublication(intent.id, context) }
            assertEquals(before, tree(f.base))
            assertEquals(intent, f.journal().read())
        }
    }

    @Test fun missingProposalBlocksStagingWithoutCreatingOutput() = fixture { f ->
        val intent = prepare(f)
        Files.move(File(f.custody, intent.proposalName).toPath(), File(f.base, "retained-proposal").toPath())
        val before = tree(f.base)
        refused { f.journal().stagePublication(intent.id, context) }
        assertEquals(before, tree(f.base)); assertFalse(stage(f, intent).exists())
    }

    @Test fun sameContentProposalReplacementCannotUseAnOldReceipt() = fixture { f ->
        val intent = prepare(f)
        val proposal = File(f.custody, intent.proposalName)
        Files.move(proposal.toPath(), File(f.base, "retained-proposal").toPath())
        CanonicalJournalFixture.write(proposal, f.proposedBytes)
        val before = tree(f.base)
        refused { f.journal().stagePublication(intent.id, context) }
        assertEquals(before, tree(f.base)); assertFalse(stage(f, intent).exists())
    }

    @Test fun changedOriginalCopyBlocksStaging() = fixture { f ->
        val intent = prepare(f)
        f.snapshotFile(intent.id).writeText("changed retained original")
        val before = tree(f.base)
        refused { f.journal().stagePublication(intent.id, context) }
        assertEquals(before, tree(f.base)); assertFalse(stage(f, intent).exists())
    }

    @Test fun proposalWriterDuringCopyCannotPromoteAStage() = fixture { f ->
        val intent = prepare(f)
        val proposal = File(f.custody, intent.proposalName)
        val journal = f.journal(checkpoint = { at ->
            if (at == "publication-stage-COPIED") proposal.writeText("concurrent proposal")
        })
        refused { journal.stagePublication(intent.id, context) }
        assertEquals(intent, f.journal().read())
        assertEquals("concurrent proposal", proposal.readText())
        assertArrayEquals(f.proposedBytes, stage(f, intent).readBytes())
        assertArrayEquals(f.bytes, f.source.readBytes())
    }

    @Test fun originalCopyChangeAfterStageVerificationDoesNotCommitReceipt() = fixture { f ->
        val intent = prepare(f)
        val journal = f.journal(checkpoint = { at ->
            if (at == "publication-stage-VERIFIED") f.snapshotFile(intent.id).writeText("changed original copy")
        })
        refused { journal.stagePublication(intent.id, context) }
        assertEquals(intent, f.journal().read())
        assertArrayEquals(f.proposedBytes, stage(f, intent).readBytes())
        assertArrayEquals(f.bytes, f.source.readBytes())
    }

    @Test fun stageReplacementAfterForcePreservesBothVersionsWithoutReceipt() = fixture { f ->
        val intent = prepare(f)
        val retained = File(f.base, "retained-stage")
        val journal = f.journal(checkpoint = { at ->
            if (at == "publication-stage-FORCED") {
                Files.move(stage(f, intent).toPath(), retained.toPath())
                CanonicalJournalFixture.write(stage(f, intent), f.proposedBytes)
            }
        })
        refused { journal.stagePublication(intent.id, context) }
        assertEquals(intent, f.journal().read())
        assertArrayEquals(f.proposedBytes, retained.readBytes())
        assertArrayEquals(f.proposedBytes, stage(f, intent).readBytes())
        originals(f, intent)
    }

    @Test fun copyAndCommitFailuresLeaveUnregisteredStageForExplicitRecovery() {
        for (point in listOf("publication-stage-COPIED", "publication-stage-before-commit")) fixture { f ->
            val intent = prepare(f)
            val failure = IOException("injected interruption")
            val journal = f.journal(checkpoint = { if (it == point) throw failure })
            assertSame(failure, refused { journal.stagePublication(intent.id, context) })
            assertEquals(intent, f.journal().read())
            val before = tree(f.base)
            refused { f.journal().stagePublication(intent.id, context) }
            assertEquals(before, tree(f.base))
            assertArrayEquals(f.proposedBytes, stage(f, intent).readBytes())
            originals(f, intent)
        }
    }

    @Test fun cancellationBeforeStagingCreatesNothing() = fixture { f ->
        val intent = prepare(f)
        val before = tree(f.base)
        val job = Job().also { it.cancel() }
        assertTrue(refused { f.journal().stagePublication(intent.id, job) } is CancellationException)
        assertEquals(before, tree(f.base))
    }

    @Test fun cancellationAtCommitRollsBackMetadataAndKeepsFiles() = fixture { f ->
        val intent = prepare(f)
        val job = Job()
        val journal = f.journal(checkpoint = { if (it == "publication-stage-before-commit") job.cancel() })
        assertTrue(refused { journal.stagePublication(intent.id, job) } is CancellationException)
        assertEquals(intent, f.journal().read())
        assertArrayEquals(f.proposedBytes, stage(f, intent).readBytes())
        originals(f, intent)
    }

    @Test fun lostReturnAfterCommitCanBeVerifiedWithoutRewritingAnything() = fixture { f ->
        val intent = prepare(f)
        val failure = IOException("lost committed return")
        val journal = f.journal(checkpoint = { if (it == "publication-stage-after-commit") throw failure })
        assertSame(failure, refused { journal.stagePublication(intent.id, context) })
        val recorded = f.journal().read()!!
        val before = tree(f.base)
        assertEquals(recorded.publicationStage, f.journal().stagePublication(intent.id, context))
        assertEquals(before, tree(f.base))
        originals(f, intent)
    }

    @Test fun stageChangesAfterCommitAreReportedWithoutDiscardingTheReceipt() = fixture { f ->
        val intent = prepare(f)
        val journal = f.journal(checkpoint = { at ->
            if (at == "publication-stage-after-commit") stage(f, intent).writeText("late stage edit")
        })
        refused { journal.stagePublication(intent.id, context) }
        assertEquals(CanonicalFileOperationJournal.Phase.PUBLICATION_STAGED, f.journal().read()!!.phase)
        assertEquals("late stage edit", stage(f, intent).readText())
        val before = tree(f.base)
        refused { f.journal().stagePublication(intent.id, context) }
        assertEquals(before, tree(f.base)); originals(f, intent)
    }

    @Test fun deletedStageIsNotSilentlyRecreated() = fixture { f ->
        val intent = prepare(f)
        f.journal().stagePublication(intent.id, context)
        val retained = File(f.base, "retained-stage")
        Files.move(stage(f, intent).toPath(), retained.toPath())
        val before = tree(f.base)
        refused { f.journal().verifyPublicationStage(intent.id, context) }
        refused { f.journal().stagePublication(intent.id, context) }
        assertEquals(before, tree(f.base)); originals(f, intent)
    }

    @Test fun recordedSameContentReplacementIsDetectedByIdentity() = fixture { f ->
        val intent = prepare(f)
        f.journal().stagePublication(intent.id, context)
        Files.move(stage(f, intent).toPath(), File(f.base, "retained-stage").toPath())
        CanonicalJournalFixture.write(stage(f, intent), f.proposedBytes)
        val before = tree(f.base)
        refused { f.journal().verifyPublicationStage(intent.id, context) }
        assertEquals(before, tree(f.base)); originals(f, intent)
    }

    @Test fun heldDescriptorCannotChangeTheIndependentProposal() = fixture { f ->
        val intent = prepare(f)
        f.journal().stagePublication(intent.id, context)
        RandomAccessFile(stage(f, intent), "rw").use { writer ->
            writer.write(byteArrayOf(8, 9)); writer.fd.sync()
            val before = tree(f.base)
            refused { f.journal().verifyPublicationStage(intent.id, context) }
            assertEquals(before, tree(f.base)); originals(f, intent)
        }
    }

    @Test fun missingSourceTreeStillAllowsHistoricalMetadataWithoutRestoration() = fixture { f ->
        val intent = prepare(f)
        f.journal().stagePublication(intent.id, context)
        val recorded = f.journal().read()!!
        Files.move(f.root.toPath(), File(f.base, "moved-project").toPath())
        val before = tree(f.base)
        assertEquals(recorded, f.journal().readPendingMetadataForRecovery())
        refused { f.journal().verifyPublicationStage(intent.id, context) }
        assertEquals(before, tree(f.base))
    }

    @Test fun corruptStageReceiptsFailClosedWithoutNormalizingMetadata() {
        for (fault in listOf("alias", "null", "phase", "mode", "extra")) fixture { f ->
            val intent = prepare(f)
            f.journal().stagePublication(intent.id, context)
            f.sql { db ->
                val old = db.rawQuery("SELECT record_json FROM operation", null).use { it.moveToFirst(); it.getString(0) }
                val fields = (Json.parseToJsonElement(old) as JsonArray).toMutableList()
                when (fault) {
                    "alias" -> fields[8] = fields[7]
                    "null" -> fields[8] = JsonNull
                    "phase" -> fields[5] = JsonPrimitive("PROPOSAL_RECORDED")
                    "mode" -> fields[8] = JsonArray((fields[8] as JsonArray).toMutableList().also { it[3] = JsonPrimitive(493) })
                    "extra" -> fields += JsonNull
                }
                val record = JsonArray(fields).toString()
                db.execSQL("UPDATE operation SET record_json=?", arrayOf(record))
                db.execSQL("UPDATE meta SET record_digest=?", arrayOf(CanonicalJournalFixture.sha256(record.toByteArray())))
            }
            val before = tree(f.base)
            refused { f.journal().readPendingMetadataForRecovery() }
            refused { f.journal().verifyPublicationStage(intent.id, context) }
            assertEquals(before, tree(f.base)); originals(f, intent)
        }
    }

    @Test fun missingOperationCannotReclassifyStagingAsAnInitialSetup() = fixture { f ->
        val intent = prepare(f)
        f.journal().stagePublication(intent.id, context)
        f.sql { it.execSQL("DELETE FROM operation") }
        val before = tree(f.base)
        refused { f.journal().read() }
        refused { f.journal().stagePublication(intent.id, context) }
        assertEquals(before, tree(f.base)); originals(f, intent)
    }

    @Test(timeout = 30_000) fun concurrentStageRequestsCannotClobberEachOther() = fixture { f ->
        val intent = prepare(f)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val calls = (0..1).map { pool.submit<Result<CanonicalFileSnapshot.Version>> {
                ready.countDown(); check(start.await(5, TimeUnit.SECONDS))
                runCatching { f.journal().stagePublication(intent.id, context) }
            } }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown()
            val results = calls.map { it.get(15, TimeUnit.SECONDS) }
            val actual = f.journal().verifyPublicationStage(intent.id, context)
            assertTrue(results.any { it.isSuccess })
            results.filter { it.isSuccess }.forEach { assertEquals(actual, it.getOrThrow()) }
            assertEquals(3, f.custody.listFiles()!!.size)
            originals(f, intent)
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    private fun prepare(f: CanonicalJournalFixture, bytes: ByteArray = f.proposedBytes,
                        mode: Int = 420): CanonicalFileOperationJournal.Intent {
        val input = File(f.base, "input").apply { writeBytes(bytes) }
        Files.setAttribute(input.toPath(), "unix:mode", mode)
        val expected = f.before(input)
        val intent = f.journal().admit(f.path, f.before(),
            CanonicalFileOperationJournal.Content(expected.size, expected.sha256, expected.mode), context)
        f.journal().capture(intent.id, context)
        AnchoredWorkspaceFiles(f.base).use { f.journal().captureProposal(intent.id, it, input.name, expected, context) }
        return f.journal().read()!!
    }

    private fun stage(f: CanonicalJournalFixture, intent: CanonicalFileOperationJournal.Intent) = File(f.custody, intent.publicationName)
    private fun originals(f: CanonicalJournalFixture, intent: CanonicalFileOperationJournal.Intent) {
        assertArrayEquals(f.bytes, f.source.readBytes())
        assertArrayEquals(f.bytes, f.snapshotFile(intent.id).readBytes())
        assertArrayEquals(f.proposedBytes, File(f.custody, intent.proposalName).readBytes())
        assertArrayEquals(f.proposedBytes, File(f.base, "input").readBytes())
    }
    private fun mode(file: File) = (Files.getAttribute(file.toPath(), "unix:mode", NOFOLLOW_LINKS) as Number).toInt() and 4095
    private fun fixture(action: (CanonicalJournalFixture) -> Unit) = CanonicalJournalFixture().use(action)
    private fun refused(action: () -> Unit): Exception {
        try { action() } catch (error: Exception) { return error }
        throw AssertionError("Expected explicit refusal")
    }
    private fun tree(root: File): Map<String, String> = Files.walk(root.toPath()).use { paths ->
        paths.filter { !Files.isDirectory(it, NOFOLLOW_LINKS) }.toList().associate { path ->
            path.toString() to if (Files.isSymbolicLink(path)) "link:${Files.readSymbolicLink(path)}"
            else "${CanonicalJournalFixture.key(path.toFile())}:${Files.getLastModifiedTime(path)}:${mode(path.toFile())}:${CanonicalJournalFixture.sha256(Files.readAllBytes(path))}"
        }
    }
}
