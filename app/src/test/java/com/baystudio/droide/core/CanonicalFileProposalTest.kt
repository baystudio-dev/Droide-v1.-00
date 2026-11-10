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
import kotlin.coroutines.CoroutineContext
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
class CanonicalFileProposalTest {
    @Test fun captureRecordsIndependentDurableProposalOnlyAfterVerification() = fixture { f ->
        val p = prepare(f)
        val phases = mutableListOf<String>()
        val journal = f.journal(checkpoint = { point ->
            phases += point
            if (point.startsWith("proposal-") && !point.endsWith("commit")) assertEquals(p.intent, f.journal().read())
        })
        val copy = capture(f, p, journal)
        assertEquals(p.expected.copy(identity = copy.identity), copy)
        assertNotEquals(p.expected.identity, copy.identity)
        assertNotEquals(checkNotNull(p.intent.before).identity, copy.identity)
        assertNotEquals(p.intent.snapshot!!.identity, copy.identity)
        assertEquals(CanonicalFileOperationJournal.Phase.PROPOSAL_RECORDED, f.journal().read()!!.phase)
        assertEquals(copy, f.journal().read()!!.proposalCopy)
        assertEquals(copy, f.journal().verifyProposal(p.intent.id, empty))
        assertTrue(phases.indexOf("proposal-FORCED") < phases.indexOf("proposal-VERIFIED"))
        assertTrue(phases.indexOf("proposal-VERIFIED") < phases.indexOf("proposal-before-commit"))
        assertEquals("proposal-after-commit", phases.last())
        assertArrayEquals(payload, output(f, p).readBytes())
        assertOriginals(f, p)
    }

    @Test fun emptyAndExecutableProposalsRoundTripWithExactMode() {
        for ((bytes, mode) in listOf(ByteArray(0) to 420, payload to 493)) fixture { f ->
            val p = prepare(f, bytes, mode)
            val copy = capture(f, p)
            assertEquals(mode, copy.mode)
            assertEquals(bytes.size.toLong(), copy.size)
            assertArrayEquals(bytes, output(f, p).readBytes())
            assertEquals(mode, (Files.getAttribute(output(f, p).toPath(), "unix:mode") as Number).toInt() and 4095)
        }
    }

    @Test fun proposalRequiresOriginalSnapshotReceiptAndMatchingOperationId() = fixture { f ->
        val p = prepare(f, captureOriginal = false)
        val prior = tree(f.base)
        refused { capture(f, p) }
        assertEquals(prior, tree(f.base))
        f.journal().capture(p.intent.id, empty)
        val current = tree(f.base)
        refused { capture(f, p.copy(intent = p.intent.copy(id = UUID.randomUUID().toString()))) }
        assertEquals(current, tree(f.base))
    }

    @Test fun mismatchedProposalContentSizeOrModeCannotCreateCustody() {
        for (alter in 0..2) fixture { f ->
            val p = prepare(f)
            val changed = when (alter) {
                0 -> p.expected.copy(sha256 = "0".repeat(64))
                1 -> p.expected.copy(size = p.expected.size - 1)
                else -> p.expected.copy(mode = 493)
            }
            val prior = tree(f.base)
            refused { capture(f, p.copy(expected = changed)) }
            assertEquals(prior, tree(f.base))
        }
    }

    @Test fun changedStageCannotBeMistakenForTheAdmittedProposal() = fixture { f ->
        val p = prepare(f)
        val changed = payload.copyOf().apply { this[lastIndex] = 19 }
        val time = Files.getLastModifiedTime(p.stage.toPath())
        p.stage.writeBytes(changed)
        Files.setLastModifiedTime(p.stage.toPath(), time)
        refused { capture(f, p) }
        assertEquals(p.intent, f.journal().read())
        assertArrayEquals(changed, p.stage.readBytes())
        assertArrayEquals(changed, output(f, p).readBytes())
        assertArrayEquals(f.bytes, f.source.readBytes())
        refused { f.journal().verifyProposal(p.intent.id, empty) }
    }

    @Test fun sameBytesStageReplacementFailsWithoutCreatingProposal() = fixture { f ->
        val p = prepare(f)
        Files.move(p.stage.toPath(), File(p.stage.parentFile, "retained").toPath())
        CanonicalJournalFixture.write(p.stage, payload)
        val prior = tree(f.base)
        refused { capture(f, p) }
        assertEquals(prior, tree(f.base))
    }

    @Test fun stageSymlinkAndTraversalCannotReadOutsideFiles() {
        for (name in listOf("proposal.bin", "../outside", "/outside", "nested/name")) fixture { f ->
            val p = prepare(f)
            val outside = File(f.base, "outside").apply { writeBytes(payload) }
            if (name == "proposal.bin") {
                Files.move(p.stage.toPath(), File(p.stage.parentFile, "retained").toPath())
                Files.createSymbolicLink(p.stage.toPath(), outside.toPath())
            }
            val prior = tree(f.base)
            refused { AnchoredWorkspaceFiles(p.stage.parentFile).use {
                f.journal().captureProposal(p.intent.id, it, name, p.expected, empty)
            } }
            assertEquals(prior, tree(f.base))
        }
    }

    @Test fun partialFullDirectorySymlinkAndHardlinkDestinationsAreNeverReplacedOrAdopted() {
        for (kind in listOf("partial", "full", "directory", "symlink", "hardlink")) fixture { f ->
            val p = prepare(f)
            val out = output(f, p)
            when (kind) {
                "partial" -> out.writeBytes(payload.copyOf(11))
                "full" -> CanonicalJournalFixture.write(out, payload)
                "directory" -> { check(out.mkdir()); File(out, "user-file").writeBytes(payload) }
                "symlink" -> Files.createSymbolicLink(out.toPath(), p.stage.toPath())
                "hardlink" -> Files.createLink(out.toPath(), p.stage.toPath())
            }
            val prior = tree(f.base)
            refused { capture(f, p) }
            refused { f.journal().verifyProposal(p.intent.id, empty) }
            assertEquals(p.intent, f.journal().read())
            assertEquals(prior, tree(f.base))
        }
    }

    @Test fun destinationAppearingAfterSourceOpenWinsWithoutClobber() = fixture { f ->
        val p = prepare(f)
        val contender = byteArrayOf(1, 3, 5)
        var reached = false
        val j = f.journal(checkpoint = { if (it == "proposal-OPENED") {
            reached = true; CanonicalJournalFixture.write(output(f, p), contender)
        } })
        refused { capture(f, p, j) }
        assertTrue(reached)
        assertArrayEquals(contender, output(f, p).readBytes())
        assertOriginals(f, p)
        assertEquals(p.intent, f.journal().read())
    }

    @Test fun descriptorWriterDuringCopyCannotProduceAnAcceptedMixedProposal() = fixture { f ->
        val p = prepare(f)
        val changed = payload.copyOf().apply { this[lastIndex] = 77 }
        var reached = false
        RandomAccessFile(p.stage, "rw").use { writer ->
            val j = f.journal(checkpoint = { if (it == "proposal-COPIED_BLOCK" && !reached) {
                reached = true; writer.seek(payload.lastIndex.toLong()); writer.write(77); writer.fd.sync()
            } })
            refused { capture(f, p, j) }
        }
        assertTrue(reached)
        assertEquals(p.intent, f.journal().read())
        assertArrayEquals(changed, p.stage.readBytes())
        assertArrayEquals(f.bytes, f.source.readBytes())
        assertTrue(output(f, p).exists())
    }

    @Test fun stageGrowthAndTruncationDuringCopyPreserveBothNamedFiles() {
        for (grow in listOf(false, true)) fixture { f ->
            val p = prepare(f)
            var reached = false
            val j = f.journal(checkpoint = { if (it == "proposal-COPIED_BLOCK" && !reached) {
                reached = true
                RandomAccessFile(p.stage, "rw").use { it.setLength(if (grow) payload.size + 1L else 1L); it.fd.sync() }
            } })
            refused { capture(f, p, j) }
            assertTrue(reached)
            assertEquals(if (grow) payload.size + 1L else 1L, p.stage.length())
            assertTrue(output(f, p).exists())
            assertEquals(p.intent, f.journal().read())
            assertArrayEquals(f.bytes, f.source.readBytes())
        }
    }

    @Test fun oldStageDescriptorCannotModifyIndependentRecordedCustody() = fixture { f ->
        val p = prepare(f)
        RandomAccessFile(p.stage, "rw").use { writer ->
            val copy = capture(f, p)
            Files.move(p.stage.toPath(), File(p.stage.parentFile, "renamed-stage").toPath())
            writer.seek(0); writer.write(55); writer.fd.sync()
            assertEquals(copy, f.journal().verifyProposal(p.intent.id, empty))
            assertArrayEquals(payload, output(f, p).readBytes())
            assertArrayEquals(f.bytes, f.source.readBytes())
        }
    }

    @Test fun replacedOutputDuringCaptureIsNotRecordedAndBothVersionsRemain() = fixture { f ->
        val p = prepare(f)
        val retained = File(f.custody, "retained-proposal")
        val j = f.journal(checkpoint = { if (it == "proposal-FORCED") {
            Files.move(output(f, p).toPath(), retained.toPath())
            CanonicalJournalFixture.write(output(f, p), payload)
        } })
        refused { capture(f, p, j) }
        assertEquals(p.intent, f.journal().read())
        assertArrayEquals(payload, retained.readBytes())
        assertArrayEquals(payload, output(f, p).readBytes())
        assertOriginals(f, p)
    }

    @Test fun changedOriginalSnapshotBlocksProposalAdmissionAndRetainsCopies() = fixture { f ->
        val p = prepare(f)
        val snapshot = f.snapshotFile(p.intent.id)
        snapshot.writeBytes(byteArrayOf(99))
        val prior = tree(f.base)
        refused { capture(f, p) }
        assertEquals(prior, tree(f.base))
        assertFalse(output(f, p).exists())
    }

    @Test fun originalSnapshotChangedDuringProposalCopyPreventsReceipt() = fixture { f ->
        val p = prepare(f)
        val j = f.journal(checkpoint = { if (it == "proposal-VERIFIED") {
            f.snapshotFile(p.intent.id).writeBytes(byteArrayOf(99))
        } })
        refused { capture(f, p, j) }
        assertEquals(p.intent, f.journal().read())
        assertArrayEquals(payload, output(f, p).readBytes())
        assertArrayEquals(byteArrayOf(99), f.snapshotFile(p.intent.id).readBytes())
        assertArrayEquals(f.bytes, f.source.readBytes())
    }

    @Test fun cancellationAtEachCopyBoundaryRetainsPendingReceiptAndAnyCreatedBytes() {
        for (point in CanonicalFileSnapshot.Boundary.values()) fixture { f ->
            val p = prepare(f)
            val job = Job()
            var reached = false
            val j = f.journal(checkpoint = { if (it == "proposal-${point.name}") { reached = true; job.cancel() } })
            assertTrue(refused { capture(f, p, j, job) } is CancellationException)
            assertTrue(reached)
            assertEquals(p.intent, f.journal().read())
            assertOriginals(f, p)
            if (point == CanonicalFileSnapshot.Boundary.OPENED) assertFalse(output(f, p).exists())
            else {
                val actual = output(f, p).readBytes()
                assertArrayEquals(payload.copyOf(actual.size), actual)
                refused { capture(f, p) }
                assertArrayEquals(actual, output(f, p).readBytes())
            }
        }
    }

    @Test fun cancelledCallerDoesNotCreateOrRecordProposal() = fixture { f ->
        val p = prepare(f)
        val prior = tree(f.base)
        assertTrue(refused { capture(f, p, context = Job().apply { cancel() }) } is CancellationException)
        assertEquals(prior, tree(f.base))
    }

    @Test fun cancellationBeforeAndAfterCommitPreservesHonestPendingState() {
        for (after in listOf(false, true)) fixture { f ->
            val p = prepare(f)
            val job = Job()
            val j = f.journal(checkpoint = { if (it == "proposal-${if (after) "after" else "before"}-commit") job.cancel() })
            assertTrue(refused { capture(f, p, j, job) } is CancellationException)
            if (after) assertEquals(f.journal().read()!!.proposalCopy, f.journal().verifyProposal(p.intent.id, empty))
            else {
                assertEquals(p.intent, f.journal().read())
                refused { f.journal().verifyProposal(p.intent.id, empty) }
            }
            assertArrayEquals(payload, output(f, p).readBytes())
            assertOriginals(f, p)
        }
    }

    @Test fun exceptionBeforeAndAfterCommitNeverCausesDeletionOrRetryAdoption() {
        for (after in listOf(false, true)) fixture { f ->
            val p = prepare(f)
            val error = IOException("receipt boundary")
            val j = f.journal(checkpoint = { if (it == "proposal-${if (after) "after" else "before"}-commit") throw error })
            assertSame(error, refused { capture(f, p, j) })
            val prior = tree(f.base)
            refused { capture(f, p) }
            assertEquals(prior, tree(f.base))
            if (after) assertEquals(f.journal().read()!!.proposalCopy, f.journal().verifyProposal(p.intent.id, empty))
            else assertEquals(p.intent, f.journal().read())
            assertArrayEquals(payload, output(f, p).readBytes())
        }
    }

    @Test fun proposalEditedDuringReceiptCommitIsRejectedOnReturn() = fixture { f ->
        val p = prepare(f)
        val changed = payload.copyOf().apply { this[0] = 17 }
        val j = f.journal(checkpoint = { if (it == "proposal-before-commit") output(f, p).writeBytes(changed) })
        refused { capture(f, p, j) }
        assertEquals(CanonicalFileOperationJournal.Phase.PROPOSAL_RECORDED, f.journal().read()!!.phase)
        refused { f.journal().verifyProposal(p.intent.id, empty) }
        assertArrayEquals(changed, output(f, p).readBytes())
        assertOriginals(f, p)
    }

    @Test fun changedOrSameBytesReplacedRecordedProposalIsRejectedWithoutMutation() {
        for (replace in listOf(false, true)) fixture { f ->
            val p = prepare(f)
            capture(f, p)
            if (replace) Files.move(output(f, p).toPath(), File(f.custody, "retained").toPath())
            CanonicalJournalFixture.write(output(f, p), if (replace) payload else byteArrayOf(89))
            val prior = tree(f.base)
            refused { f.journal().verifyProposal(p.intent.id, empty) }
            refused { capture(f, p) }
            assertEquals(prior, tree(f.base))
        }
    }

    @Test fun originalAndStageRemovalDoNotEraseRecordedProposal() = fixture { f ->
        val p = prepare(f)
        val copy = capture(f, p)
        Files.move(f.source.toPath(), File(f.base, "retained-original").toPath())
        Files.move(p.stage.toPath(), File(f.base, "retained-stage").toPath())
        val prior = tree(f.base)
        assertEquals(copy, f.journal().verifyProposal(p.intent.id, empty))
        assertEquals(prior, tree(f.base))
    }

    @Test fun metadataLossAfterProposalRecordingBlocksRecoveryWithoutDiscardingBytes() {
        for (database in listOf(false, true)) fixture { f ->
            val p = prepare(f)
            capture(f, p)
            if (database) Files.move(f.database.toPath(), File(f.base, "retained.sqlite").toPath())
            else f.sql { it.execSQL("DELETE FROM operation") }
            val prior = tree(f.base)
            refused { f.journal().read() }
            refused { f.journal().verifyProposal(p.intent.id, empty) }
            assertEquals(prior, tree(f.base))
            assertArrayEquals(payload, output(f, p).readBytes())
            assertOriginals(f, p)
        }
    }

    @Test fun proposalPhaseBlocksAnotherOperationAndRepeatedReadsDoNotRewriteMetadata() = fixture { f ->
        val p = prepare(f)
        capture(f, p)
        val prior = tree(f.base)
        repeat(4) {
            assertEquals(f.journal().read()!!.proposalCopy, f.journal().verifyProposal(p.intent.id, empty))
            refused { f.journal().admit(f.path, f.before(), f.proposal(), empty) }
        }
        assertEquals(prior, tree(f.base))
    }

    @Test(timeout = 30_000) fun twoProposalOwnersCannotOverwriteOneAnother() = fixture { f ->
        val p = prepare(f)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val calls = (0..1).map { pool.submit<Result<CanonicalFileSnapshot.Version>> {
                ready.countDown(); check(start.await(5, TimeUnit.SECONDS))
                runCatching { capture(f, p) }
            } }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown()
            val results = calls.map { it.get(15, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, results.count { it.isFailure })
            assertEquals(results.single { it.isSuccess }.getOrThrow(), f.journal().verifyProposal(p.intent.id, empty))
            assertOriginals(f, p)
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    private data class Plan(val intent: CanonicalFileOperationJournal.Intent, val stage: File,
                            val expected: CanonicalFileSnapshot.Version)
    private fun prepare(f: CanonicalJournalFixture, bytes: ByteArray = payload, mode: Int = 420,
                        captureOriginal: Boolean = true): Plan {
        val dir = File(f.base, "stage").also { check(it.mkdir()) }
        val stage = File(dir, "proposal.bin")
        CanonicalJournalFixture.write(stage, bytes)
        Files.setAttribute(stage.toPath(), "unix:mode", mode)
        val expected = f.before(stage)
        val admitted = f.journal().admit(f.path, f.before(),
            CanonicalFileOperationJournal.Content(expected.size, expected.sha256, expected.mode), empty)
        if (captureOriginal) f.journal().capture(admitted.id, empty)
        return Plan(f.journal().read()!!, stage, expected)
    }
    private fun capture(f: CanonicalJournalFixture, p: Plan, journal: CanonicalFileOperationJournal = f.journal(),
                        context: CoroutineContext = empty): CanonicalFileSnapshot.Version =
        AnchoredWorkspaceFiles(p.stage.parentFile).use { journal.captureProposal(p.intent.id, it, p.stage.name, p.expected, context) }
    private fun output(f: CanonicalJournalFixture, p: Plan) = File(f.custody, p.intent.proposalName)
    private fun assertOriginals(f: CanonicalJournalFixture, p: Plan) {
        assertArrayEquals(f.bytes, f.source.readBytes())
        assertArrayEquals(f.bytes, f.snapshotFile(p.intent.id).readBytes())
        assertArrayEquals(payload, p.stage.readBytes())
    }
    private fun fixture(action: (CanonicalJournalFixture) -> Unit) = CanonicalJournalFixture().use(action)
    private fun refused(action: () -> Unit): Throwable {
        try { action() } catch (error: Exception) { return error }
        throw AssertionError("Expected explicit refusal")
    }
    private fun tree(root: File): Map<String, String> = Files.walk(root.toPath()).use { paths ->
        paths.filter { !Files.isDirectory(it, NOFOLLOW_LINKS) }.toList().associate { path ->
            path.toString() to if (Files.isSymbolicLink(path)) "link:${Files.readSymbolicLink(path)}"
            else "${CanonicalJournalFixture.key(path.toFile())}:${Files.getLastModifiedTime(path)}:${CanonicalJournalFixture.sha256(Files.readAllBytes(path))}"
        }
    }
    companion object {
        private val empty = EmptyCoroutineContext
        private val payload = ByteArray(140_000) { (it % 239).toByte() }
    }
}
