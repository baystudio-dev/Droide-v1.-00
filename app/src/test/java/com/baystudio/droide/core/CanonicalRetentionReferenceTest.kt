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
class CanonicalRetentionReferenceTest {
    private val context = EmptyCoroutineContext

    @Test fun recordsExistingIndependentSnapshotWithoutCreatingOrWritingData() = fixture { f ->
        val i = prepare(f)
        val data = tree(f.base, dataOnly = true)
        val points = mutableListOf<String>()
        val snapshot = f.journal(checkpoint = { points += it }).recordRetainedSnapshot(i.id, context)
        val next = f.journal().read()!!
        assertEquals(i.snapshot, snapshot)
        assertNotEquals(checkNotNull(i.before).identity, snapshot.identity)
        assertEquals(CanonicalFileOperationJournal.Phase.RETENTION_RECORDED, next.phase)
        assertEquals(snapshot.identity, next.retainedSnapshotIdentity)
        assertEquals(listOf("retention-verified", "retention-before-commit", "retention-after-commit"), points)
        assertEquals(data, tree(f.base, dataOnly = true))
        assertEquals(i.before, f.journal().verifyRetirement(i.id, context))
    }

    @Test fun emptyPrivateAndExecutableFilesKeepTheirOriginalVersionAndMode() {
        for (mode in listOf(384, 420, 493)) fixture { f ->
            f.source.writeBytes(byteArrayOf())
            Files.setAttribute(f.source.toPath(), "unix:mode", mode)
            val i = prepare(f)
            val data = tree(f.base, dataOnly = true)
            val result = f.journal().recordRetainedSnapshot(i.id, context)
            assertEquals(i.snapshot, result); assertEquals(0L, result.size); assertEquals(mode, result.mode)
            assertEquals(data, tree(f.base, dataOnly = true))
        }
    }

    @Test fun repeatedRecordingAndVerificationDoNotRewriteMetadataOrData() = fixture { f ->
        val i = prepare(f)
        f.journal().recordRetainedSnapshot(i.id, context)
        val before = tree(f.base)
        repeat(3) {
            assertEquals(i.snapshot, f.journal().recordRetainedSnapshot(i.id, context))
            assertEquals(i.snapshot, f.journal().verifyRetainedSnapshot(i.id, context))
        }
        assertEquals(before, tree(f.base))
    }

    @Test fun stageOrInterruptedRetirementIsNotAuthorityForRetention() {
        for (interrupted in listOf(false, true)) fixture { f ->
            val i = prepare(f, retire = false)
            if (interrupted) refused {
                f.journal(checkpoint = { if (it == "retirement-after-move") throw IOException("lost return") })
                    .retireCanonical(i.id, context)
            }
            val before = tree(f.base)
            refused { f.journal().recordRetainedSnapshot(i.id, context) }
            refused { f.journal().verifyRetainedSnapshot(i.id, context) }
            assertEquals(before, tree(f.base))
        }
    }

    @Test fun failedRetirementCannotBeTurnedIntoRetentionAuthority() = fixture { f ->
        val i = prepare(f, retire = false)
        refused { f.journal(checkpoint = { if (it == "retirement-before-move") retired(f, i).writeText("other work") })
            .retireCanonical(i.id, context) }
        val before = tree(f.base)
        refused { f.journal().recordRetainedSnapshot(i.id, context) }
        assertEquals(before, tree(f.base)); assertEquals("other work", retired(f, i).readText())
    }

    @Test fun foreignIdsCannotRecordOrReadRetention() = fixture { f ->
        prepare(f)
        val before = tree(f.base)
        refused { f.journal().recordRetainedSnapshot(UUID.randomUUID().toString(), context) }
        refused { f.journal().verifyRetainedSnapshot(UUID.randomUUID().toString(), context) }
        assertEquals(before, tree(f.base))
    }

    @Test fun editedRetiredBytesArePreservedWithoutRecordingAReference() = fixture { f ->
        val i = prepare(f)
        retired(f, i).writeText("unpublished CLI work")
        val before = tree(f.base)
        refused { f.journal().recordRetainedSnapshot(i.id, context) }
        assertEquals(before, tree(f.base)); assertEquals(i, f.journal().read())
        assertArrayEquals(f.bytes, f.snapshotFile(i.id).readBytes())
    }

    @Test fun recreatedCanonicalEntryPreventsRecordingWithoutOverwritingIt() {
        for (kind in listOf("file", "directory", "symlink")) fixture { f ->
            val i = prepare(f)
            when (kind) {
                "file" -> f.source.writeText("new canonical work")
                "directory" -> check(f.source.mkdir())
                else -> Files.createSymbolicLink(f.source.toPath(), retired(f, i).toPath())
            }
            val before = tree(f.base)
            refused { f.journal().recordRetainedSnapshot(i.id, context) }
            assertEquals(before, tree(f.base)); assertEquals(i, f.journal().read())
        }
    }

    @Test fun missingCorruptReplacedOrSymlinkedSnapshotIsNeverAdoptedOrRecreated() {
        for (kind in listOf("missing", "corrupt", "replacement", "symlink")) fixture { f ->
            val i = prepare(f)
            val snapshot = f.snapshotFile(i.id)
            Files.move(snapshot.toPath(), File(f.base, "original-snapshot").toPath())
            when (kind) {
                "corrupt" -> snapshot.writeText("damaged")
                "replacement" -> CanonicalJournalFixture.write(snapshot, f.bytes)
                "symlink" -> Files.createSymbolicLink(snapshot.toPath(), retired(f, i).toPath())
            }
            val before = tree(f.base)
            refused { f.journal().recordRetainedSnapshot(i.id, context) }
            assertEquals(before, tree(f.base)); assertEquals(i, f.journal().read())
        }
    }

    @Test fun heldWriterCannotChangeTheRecordedIndependentSnapshot() = fixture { f ->
        val staged = prepare(f, retire = false)
        RandomAccessFile(f.source, "rw").use { writer ->
            f.journal().retireCanonical(staged.id, context)
            f.journal().recordRetainedSnapshot(staged.id, context)
            writer.seek(0); writer.write("late CLI descriptor write".toByteArray()); writer.fd.sync()
        }
        val before = tree(f.base)
        assertEquals(staged.snapshot, f.journal().verifyRetainedSnapshot(staged.id, context))
        refused { f.journal().verifyRetirement(staged.id, context) }
        refused { f.journal().recordRetainedSnapshot(staged.id, context) }
        assertEquals(before, tree(f.base))
        assertTrue(retired(f, staged).readText().startsWith("late CLI descriptor write"))
        assertArrayEquals(f.bytes, f.snapshotFile(staged.id).readBytes())
    }

    @Test fun historicalSnapshotDoesNotAuthorizePublicationAfterPathsChange() {
        for (kind in listOf("missing-retired", "recreated-canonical")) fixture { f ->
            val i = prepare(f)
            f.journal().recordRetainedSnapshot(i.id, context)
            if (kind == "missing-retired") Files.move(retired(f, i).toPath(), File(f.base, "external-retired").toPath())
            else f.source.writeText("new canonical")
            val before = tree(f.base)
            assertEquals(i.snapshot, f.journal().verifyRetainedSnapshot(i.id, context))
            refused { f.journal().verifyRetirement(i.id, context) }
            refused { f.journal().recordRetainedSnapshot(i.id, context) }
            assertEquals(before, tree(f.base))
        }
    }

    @Test fun exceptionBeforeCommitRollsBackOnlyMetadataAndDoesNotCreateAnOrphanCopy() = fixture { f ->
        val i = prepare(f)
        val data = tree(f.base, dataOnly = true)
        val failure = IOException("commit rejected")
        assertSame(failure, refused { f.journal(checkpoint = { if (it == "retention-before-commit") throw failure })
            .recordRetainedSnapshot(i.id, context) })
        assertEquals(i, f.journal().read()); assertEquals(data, tree(f.base, dataOnly = true))
        assertEquals(i.snapshot, f.journal().recordRetainedSnapshot(i.id, context))
    }

    @Test fun cancellationBeforeWorkOrCommitPreservesAllVersions() {
        for (point in listOf("start", "retention-verified", "retention-before-commit")) fixture { f ->
            val i = prepare(f)
            val job = Job(); if (point == "start") job.cancel()
            val data = tree(f.base, dataOnly = true)
            assertTrue(refused { f.journal(checkpoint = { if (it == point) job.cancel() })
                .recordRetainedSnapshot(i.id, job) } is CancellationException)
            assertEquals(i, f.journal().read()); assertEquals(data, tree(f.base, dataOnly = true))
        }
    }

    @Test fun lostReturnAfterCommitCanBeVerifiedAndReusedWithoutRewriting() = fixture { f ->
        val i = prepare(f)
        refused { f.journal(checkpoint = { if (it == "retention-after-commit") throw IOException("lost return") })
            .recordRetainedSnapshot(i.id, context) }
        val before = tree(f.base)
        assertEquals(i.snapshot, f.journal().verifyRetainedSnapshot(i.id, context))
        assertEquals(i.snapshot, f.journal().recordRetainedSnapshot(i.id, context))
        assertEquals(before, tree(f.base))
    }

    @Test fun lastGapWriterPreservesHistoricalReceiptButCannotPassCurrentVerification() = fixture { f ->
        val i = prepare(f, retire = false)
        RandomAccessFile(f.source, "rw").use { writer ->
            f.journal().retireCanonical(i.id, context)
            refused { f.journal(checkpoint = { if (it == "retention-before-commit") {
                writer.seek(0); writer.write("last gap CLI work".toByteArray()); writer.fd.sync()
            } }).recordRetainedSnapshot(i.id, context) }
        }
        assertEquals(CanonicalFileOperationJournal.Phase.RETENTION_RECORDED, f.journal().read()!!.phase)
        assertEquals(i.snapshot, f.journal().verifyRetainedSnapshot(i.id, context))
        refused { f.journal().verifyRetirement(i.id, context) }
        assertTrue(retired(f, i).readText().startsWith("last gap CLI work"))
    }

    @Test fun lastGapSnapshotTamperingCannotReturnSuccessOrRepairTheDamageSilently() = fixture { f ->
        val i = prepare(f)
        refused { f.journal(checkpoint = { if (it == "retention-before-commit") f.snapshotFile(i.id).writeText("tampered") })
            .recordRetainedSnapshot(i.id, context) }
        assertEquals(CanonicalFileOperationJournal.Phase.RETENTION_RECORDED, f.journal().read()!!.phase)
        val before = tree(f.base)
        refused { f.journal().verifyRetainedSnapshot(i.id, context) }
        assertEquals(before, tree(f.base)); assertArrayEquals(f.bytes, retired(f, i).readBytes())
    }

    @Test fun malformedReferenceFailsClosedWithoutNormalizingMetadata() {
        for (bad in listOf(JsonNull, JsonPrimitive(1), JsonPrimitive("foreign-inode"))) fixture { f ->
            val i = prepare(f); f.journal().recordRetainedSnapshot(i.id, context)
            rewrite(f) { it[10] = bad }
            val before = tree(f.base)
            refused { f.journal().readPendingMetadataForRecovery() }
            refused { f.journal().verifyRetainedSnapshot(i.id, context) }
            assertEquals(before, tree(f.base))
        }
    }

    @Test fun wrongPhaseOrNonDurableOutcomeCannotCarryRetentionAuthority() {
        for (bad in listOf("phase", "failed", "uncertain")) fixture { f ->
            val i = prepare(f); f.journal().recordRetainedSnapshot(i.id, context)
            rewrite(f) {
                if (bad == "phase") it[5] = JsonPrimitive("RETIREMENT_RECORDED")
                else it[9] = Json.parseToJsonElement(if (bad == "failed") "[0,17]" else "[1,5]")
            }
            val before = tree(f.base)
            refused { f.journal().readPendingMetadataForRecovery() }
            assertEquals(before, tree(f.base))
        }
    }

    @Test fun secondCallerAfterCommitReusesTheSameReference() = fixture { f ->
        val i = prepare(f)
        val committed = CountDownLatch(1); val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val winner = executor.submit<CanonicalFileSnapshot.Version> {
                f.journal(checkpoint = { if (it == "retention-after-commit") {
                    committed.countDown(); check(release.await(20, TimeUnit.SECONDS))
                } }).recordRetainedSnapshot(i.id, context)
            }
            assertTrue(committed.await(20, TimeUnit.SECONDS))
            val before = tree(f.base)
            val second = f.journal(checkpoint = { error("Unexpected second commit: $it") }).recordRetainedSnapshot(i.id, context)
            assertEquals(before, tree(f.base)); release.countDown()
            assertEquals(i.snapshot, second); assertEquals(second, winner.get(20, TimeUnit.SECONDS))
        } finally { release.countDown(); executor.shutdownNow(); check(executor.awaitTermination(20, TimeUnit.SECONDS)) }
    }

    @Test fun parentReplacementRollsBackReceiptAndLeavesRecoveryMetadataReadable() = fixture { f ->
        val i = prepare(f)
        val old = f.source.parentFile
        val moved = File(f.base, "moved-parent")
        refused { f.journal(checkpoint = { if (it == "retention-verified") {
            Files.move(old.toPath(), moved.toPath()); check(old.mkdir()); File(old, "new-work").writeText("preserve")
        } }).recordRetainedSnapshot(i.id, context) }
        assertEquals(i, f.journal().readPendingMetadataForRecovery())
        assertEquals("preserve", File(old, "new-work").readText())
        assertArrayEquals(f.bytes, retired(f, i).readBytes())
        assertArrayEquals(f.bytes, f.snapshotFile(i.id).readBytes())
    }

    private fun prepare(f: CanonicalJournalFixture, retire: Boolean = true): CanonicalFileOperationJournal.Intent {
        val i = f.journal().admit(f.path, f.before(), f.proposal(), context)
        f.journal().capture(i.id, context)
        val input = File(f.base, "input"); CanonicalJournalFixture.write(input, f.proposedBytes)
        AnchoredWorkspaceFiles(f.base).use { f.journal().captureProposal(i.id, it, input.name, f.before(input), context) }
        f.journal().stagePublication(i.id, context)
        if (retire) f.journal().retireCanonical(i.id, context)
        return f.journal().read()!!
    }
    private fun rewrite(f: CanonicalJournalFixture, edit: (MutableList<JsonElement>) -> Unit) = f.sql { db ->
        val old = db.rawQuery("SELECT record_json FROM operation", null).use { it.moveToFirst(); it.getString(0) }
        val fields = Json.parseToJsonElement(old).jsonArray.toMutableList(); edit(fields)
        val record = JsonArray(fields).toString()
        db.execSQL("UPDATE operation SET record_json=?", arrayOf(record))
        db.execSQL("UPDATE meta SET record_digest=?", arrayOf(CanonicalJournalFixture.sha256(record.toByteArray())))
    }
    private fun retired(f: CanonicalJournalFixture, i: CanonicalFileOperationJournal.Intent) = File(f.custody, i.retiredName)
    private fun fixture(action: (CanonicalJournalFixture) -> Unit) = CanonicalJournalFixture().use(action)
    private fun refused(action: () -> Unit): Exception {
        try { action() } catch (failure: Exception) { return failure }
        throw AssertionError("Expected explicit refusal")
    }
    private fun tree(root: File, dataOnly: Boolean = false): Map<String, String> = Files.walk(root.toPath()).use { paths ->
        paths.filter { !Files.isDirectory(it, NOFOLLOW_LINKS) && (!dataOnly || !it.fileName.toString().startsWith("state.sqlite")) }
            .toList().associate { path -> path.toString() to if (Files.isSymbolicLink(path)) "link:${Files.readSymbolicLink(path)}"
                else "${CanonicalJournalFixture.key(path.toFile())}:${Files.getLastModifiedTime(path)}:${Files.getAttribute(path, "unix:mode")}:${CanonicalJournalFixture.sha256(Files.readAllBytes(path))}" }
    }
}
