package com.baystudio.droide.core

import android.database.sqlite.SQLiteException
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
class CanonicalRetirementRecoveryTest {
    private val context = EmptyCoroutineContext

    @Test fun missingOutcomeIsRecoveredWithoutInventingANativeReceipt() = fixture { f ->
        val intent = prepare(f)
        val data = tree(f.base, dataOnly = true)
        assertNull(intent.retirement)
        assertEquals(intent.before, f.journal().recoverRetiredState(intent.id, context))
        val recovered = checkNotNull(f.journal().read())
        assertEquals(CanonicalFileOperationJournal.Phase.RETIREMENT_RECOVERED, recovered.phase)
        assertNull(recovered.retirement)
        assertEquals(intent.before, recovered.retirementRecovery)
        assertEquals(data, tree(f.base, dataOnly = true))
        refused { f.journal().retireCanonical(intent.id, context) }
    }

    @Test fun uncertainMovedOutcomeIsPreservedAfterRecovery() = fixture { f ->
        val intent = prepare(f)
        rewrite(f) {
            it[5] = JsonPrimitive("RETIREMENT_RECORDED")
            it += Json.parseToJsonElement("[1,5]")
        }
        f.journal().recoverRetiredState(intent.id, context)
        val recovered = checkNotNull(f.journal().read())
        assertEquals(AnchoredWorkspaceFiles.MoveReceipt(true, 5), recovered.retirement)
        assertEquals(intent.before, recovered.retirementRecovery)
    }

    @Test fun recoveryDoesNotReplayAMoveThatNeverHappened() = fixture { f ->
        val intent = prepare(f, "retirement-intent-after-commit")
        val before = tree(f.base)
        refused { f.journal().recoverRetiredState(intent.id, context) }
        assertEquals(before, tree(f.base))
        assertEquals(intent.before, f.before())
        assertFalse(File(f.custody, intent.retiredName).exists())
    }

    @Test fun notMovedReceiptCannotBeReinterpretedAsSuccess() = fixture { f ->
        val intent = prepare(f)
        rewrite(f) {
            it[5] = JsonPrimitive("RETIREMENT_RECORDED")
            it += Json.parseToJsonElement("[0,17]")
        }
        val before = tree(f.base)
        refused { f.journal().recoverRetiredState(intent.id, context) }
        assertEquals(before, tree(f.base))
        assertEquals(AnchoredWorkspaceFiles.MoveReceipt(false, 17), f.journal().read()!!.retirement)
    }

    @Test fun durableAndForeignOperationsDoNotAcquireRecoveryReceipts() = fixture { f ->
        val intent = prepare(f, stop = null)
        val before = tree(f.base)
        refused { f.journal().recoverRetiredState(UUID.randomUUID().toString(), context) }
        refused { f.journal().recoverRetiredState(intent.id, context) }
        assertEquals(before, tree(f.base))
    }

    @Test fun repeatedRecoveryChecksWithoutWritingOrForcingAgain() = fixture { f ->
        val intent = prepare(f)
        f.journal().recoverRetiredState(intent.id, context)
        val before = tree(f.base)
        repeat(3) {
            val journal = f.journal(checkpoint = { error("Unexpected repeated recovery work: $it") })
            assertEquals(intent.before, journal.recoverRetiredState(intent.id, context))
        }
        assertEquals(before, tree(f.base))
    }

    @Test fun recoveredRetirementCanContinueToPublicationWithoutLosingItsHistory() = fixture { f ->
        val intent = prepare(f)
        f.journal().recoverRetiredState(intent.id, context)
        f.journal().recordRetainedSnapshot(intent.id, context)
        f.journal().publishStaged(intent.id, context)
        f.journal().recordPublicationCompletion(intent.id, context)
        val completed = checkNotNull(f.journal().read())
        assertNull(completed.retirement)
        assertEquals(intent.before, completed.retirementRecovery)
        assertEquals(CanonicalFileOperationJournal.Phase.PUBLICATION_COMPLETED, completed.phase)
        assertArrayEquals(f.proposedBytes, f.source.readBytes())
        assertArrayEquals(f.bytes, File(f.custody, intent.retiredName).readBytes())
        assertArrayEquals(f.bytes, f.snapshotFile(intent.id).readBytes())
        refused { f.journal().recoverRetiredState(intent.id, context) }
    }

    @Test fun interruptedPublicationCanRecoverAfterARetirementRecovery() = fixture { f ->
        val intent = prepare(f)
        f.journal().recoverRetiredState(intent.id, context)
        f.journal().recordRetainedSnapshot(intent.id, context)
        refused {
            f.journal(checkpoint = { if (it == "publication-after-move") throw IOException("lost publication outcome") })
                .publishStaged(intent.id, context)
        }
        f.journal().recoverPublishedState(intent.id, context)
        f.journal().recordPublicationCompletion(intent.id, context)
        val completed = checkNotNull(f.journal().read())
        assertNull(completed.retirement)
        assertNull(completed.publication)
        assertEquals(intent.before, completed.retirementRecovery)
        assertEquals(intent.publicationStage, completed.publicationRecovery)
        assertArrayEquals(f.proposedBytes, f.source.readBytes())
    }

    @Test fun changedCopiesOrRetiredBytesRequireReview() {
        for (suffix in listOf("before", "after", "publish", "retired")) fixture { f ->
            val intent = prepare(f)
            File(f.custody, "${intent.id}.$suffix").writeText("unpublished changes")
            val before = tree(f.base)
            refused { f.journal().recoverRetiredState(intent.id, context) }
            assertEquals(before, tree(f.base))
            assertNull(f.journal().read()!!.retirementRecovery)
        }
    }

    @Test fun recreatedCanonicalPathIsNotReplacedByRecovery() {
        for (kind in listOf("file", "directory", "symlink")) fixture { f ->
            val intent = prepare(f)
            when (kind) {
                "file" -> f.source.writeText("new canonical work")
                "directory" -> { check(f.source.mkdir()); File(f.source, "work").writeText("directory work") }
                "symlink" -> Files.createSymbolicLink(f.source.toPath(), File(f.custody, intent.retiredName).toPath())
            }
            val before = tree(f.base)
            refused { f.journal().recoverRetiredState(intent.id, context) }
            assertEquals(before, tree(f.base))
        }
    }

    @Test fun changedRetirementIdentityModeOrKindIsNotAdopted() {
        for (change in listOf("inode", "mode", "missing", "directory", "symlink")) fixture { f ->
            val intent = prepare(f)
            val retired = File(f.custody, intent.retiredName)
            if (change == "mode") Files.setAttribute(retired.toPath(), "unix:mode", 292)
            else {
                val retained = File(f.base, "externally-retained")
                Files.move(retired.toPath(), retained.toPath())
                when (change) {
                    "inode" -> CanonicalJournalFixture.write(retired, f.bytes)
                    "directory" -> check(retired.mkdir())
                    "symlink" -> Files.createSymbolicLink(retired.toPath(), retained.toPath())
                }
            }
            val before = tree(f.base)
            refused { f.journal().recoverRetiredState(intent.id, context) }
            assertEquals(before, tree(f.base))
        }
    }

    @Test fun heldDescriptorWritesAfterForceArePreservedAndRejected() = fixture { f ->
        val intent = prepare(f)
        val retired = File(f.custody, intent.retiredName)
        RandomAccessFile(retired, "rw").use { writer ->
            val journal = f.journal(checkpoint = { point ->
                if (point == "snapshot-FORCED") {
                    writer.seek(0)
                    writer.write("late CLI writes".toByteArray())
                    writer.fd.sync()
                }
            })
            refused { journal.recoverRetiredState(intent.id, context) }
        }
        assertNull(f.journal().read()!!.retirementRecovery)
        assertTrue(retired.readText().startsWith("late CLI writes"))
        assertArrayEquals(f.bytes, f.snapshotFile(intent.id).readBytes())
    }

    @Test fun mutationsAtForceBoundariesPreventRecoveryCommit() {
        for (point in listOf("retirement-recovery-verified", "retirement-recovery-file-forced",
                "retirement-recovery-parent-forced", "retirement-recovery-directories-forced")) fixture { f ->
            val intent = prepare(f)
            val journal = f.journal(checkpoint = { if (it == point) f.source.writeText("racing canonical writer") })
            refused { journal.recoverRetiredState(intent.id, context) }
            assertNull(f.journal().read()!!.retirementRecovery)
            assertEquals("racing canonical writer", f.source.readText())
            assertArrayEquals(f.bytes, File(f.custody, intent.retiredName).readBytes())
        }
    }

    @Test fun replacingTheCanonicalParentDoesNotAdoptANewTree() = fixture { f ->
        val intent = prepare(f)
        val parent = checkNotNull(f.source.parentFile)
        val saved = File(f.base, "original-parent")
        val journal = f.journal(checkpoint = { if (it == "retirement-recovery-verified") {
            Files.move(parent.toPath(), saved.toPath())
            check(parent.mkdir())
            f.source.writeText("replacement tree")
        } })
        refused { journal.recoverRetiredState(intent.id, context) }
        assertEquals(intent, f.journal().readPendingMetadataForRecovery())
        assertEquals("replacement tree", f.source.readText())
        assertTrue(saved.isDirectory)
    }

    @Test fun lateCommitGapMutationCannotReturnSuccessfulCurrentRecovery() = fixture { f ->
        val intent = prepare(f)
        val journal = f.journal(checkpoint = {
            if (it == "retirement-recovery-before-commit") f.source.writeText("late work")
        })
        refused { journal.recoverRetiredState(intent.id, context) }
        assertEquals(intent.before, f.journal().read()!!.retirementRecovery)
        assertEquals("late work", f.source.readText())
        refused { f.journal().recordRetainedSnapshot(intent.id, context) }
    }

    @Test fun cancellationAtEveryRecoveryBoundaryRetainsAllVersions() {
        for (point in listOf("start", "retirement-recovery-verified", "snapshot-OPENED", "snapshot-COPIED",
                "snapshot-FORCED", "retirement-recovery-file-forced", "retirement-recovery-parent-forced",
                "retirement-recovery-directories-forced", "retirement-recovery-before-commit")) fixture { f ->
            val intent = prepare(f)
            val before = tree(f.base, dataOnly = true)
            val job = Job()
            if (point == "start") job.cancel()
            val failure = refused { f.journal(checkpoint = { if (it == point) job.cancel() }).recoverRetiredState(intent.id, job) }
            assertTrue(failure is CancellationException)
            assertEquals(intent, f.journal().read())
            assertEquals(before, tree(f.base, dataOnly = true))
        }
    }

    @Test fun sqliteCommitFailureRetainsUnknownOutcomeAndCustody() = fixture { f ->
        val intent = prepare(f)
        f.sql { it.execSQL("CREATE TRIGGER refuse_update BEFORE UPDATE ON operation BEGIN SELECT RAISE(ABORT,'test update failure'); END") }
        val before = tree(f.base)
        refused { f.journal().recoverRetiredState(intent.id, context) }
        assertEquals(intent, f.journal().read())
        assertEquals(before, tree(f.base))
    }

    @Test fun lostReturnAfterCommitIsIdempotentAfterReopening() = fixture { f ->
        val intent = prepare(f)
        refused {
            f.journal(checkpoint = { if (it == "retirement-recovery-after-commit") throw IOException("lost return") })
                .recoverRetiredState(intent.id, context)
        }
        val before = tree(f.base)
        assertEquals(intent.before, f.journal().recoverRetiredState(intent.id, context))
        assertEquals(before, tree(f.base))
    }

    @Test fun missingRootAllowsHistoricalMetadataButNoFurtherRecovery() = fixture { f ->
        val intent = prepare(f)
        f.journal().recoverRetiredState(intent.id, context)
        val recovered = f.journal().read()
        Files.move(f.root.toPath(), File(f.base, "retained-project").toPath())
        val before = tree(f.base)
        assertEquals(recovered, f.journal().readPendingMetadataForRecovery())
        refused { f.journal().recoverRetiredState(intent.id, context) }
        assertEquals(before, tree(f.base))
    }

    @Test fun malformedRecoveryMetadataIsNeverNormalized() {
        for (bad in listOf("null", "object", "string", "foreign", "phase", "not-moved", "durable", "missing", "extra")) fixture { f ->
            val intent = prepare(f)
            f.journal().recoverRetiredState(intent.id, context)
            rewrite(f) { fields -> when (bad) {
                "null" -> fields[15] = JsonNull
                "object" -> fields[15] = buildJsonObject { put("invalid", true) }
                "string" -> fields[15] = JsonPrimitive("invalid")
                "foreign" -> fields[15] = fields[7]
                "phase" -> fields[5] = JsonPrimitive("RETIREMENT_PREPARED")
                "not-moved" -> fields[9] = Json.parseToJsonElement("[0,17]")
                "durable" -> fields[9] = Json.parseToJsonElement("[1,0]")
                "missing" -> fields.removeAt(15)
                "extra" -> fields += JsonPrimitive("unexpected")
            } }
            val before = tree(f.base)
            refused { f.journal().readPendingMetadataForRecovery() }
            assertEquals(before, tree(f.base))
        }
    }

    private fun prepare(f: CanonicalJournalFixture, stop: String? = "retirement-after-move"): CanonicalFileOperationJournal.Intent {
        val intent = f.journal().admit(f.path, f.before(), f.proposal(), context)
        f.journal().capture(intent.id, context)
        val input = File(f.base, "input")
        CanonicalJournalFixture.write(input, f.proposedBytes)
        AnchoredWorkspaceFiles(f.base).use { f.journal().captureProposal(intent.id, it, input.name, f.before(input), context) }
        f.journal().stagePublication(intent.id, context)
        if (stop == null) f.journal().retireCanonical(intent.id, context)
        else refused {
            f.journal(checkpoint = { if (it == stop) throw IOException("interrupted retirement") })
                .retireCanonical(intent.id, context)
        }
        return checkNotNull(f.journal().read())
    }

    private fun fixture(action: (CanonicalJournalFixture) -> Unit) = CanonicalJournalFixture().use(action)

    private fun refused(action: () -> Unit): Exception {
        try { action() } catch (failure: Exception) {
            assertTrue("Unexpected failure type: $failure", failure is IOException || failure is IllegalStateException ||
                failure is IllegalArgumentException || failure is SQLiteException)
            return failure
        }
        throw AssertionError("Expected recovery to be refused")
    }

    private fun tree(root: File, dataOnly: Boolean = false): Map<String, String> = Files.walk(root.toPath()).use { paths ->
        paths.filter { !Files.isDirectory(it, NOFOLLOW_LINKS) && (!dataOnly || !it.fileName.toString().startsWith("state.sqlite")) }
            .toList().associate { path ->
                path.toString() to if (Files.isSymbolicLink(path)) "link:${Files.readSymbolicLink(path)}"
                else "${CanonicalJournalFixture.key(path.toFile())}:${Files.getLastModifiedTime(path)}:${Files.getAttribute(path, "unix:mode")}:${CanonicalJournalFixture.sha256(Files.readAllBytes(path))}"
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
}
