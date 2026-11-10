package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CanonicalRecoveryMetadataTest {
    @Test fun metadataRemainsReadableAfterRootDisappearsInEveryPendingPhase() {
        for (phase in CanonicalFileOperationJournal.Phase.values()) fixture { f ->
            val intent = prepare(f, phase)
            assertEquals(phase, intent.phase)
            Files.move(f.root.toPath(), File(f.base, "retained-project").toPath())
            val before = tree(f.base)
            assertEquals(intent, f.journal().readPendingMetadataForRecovery())
            refuse { f.journal().read() }
            refuse { f.journal().capture(intent.id, empty) }
            refuse { f.journal().verifySnapshot(intent.id, empty) }
            refuse { f.journal().verifyProposal(intent.id, empty) }
            refuse { f.journal().recoverRetiredState(intent.id, empty) }
            refuse { f.journal().admit(f.path, checkNotNull(intent.before), intent.proposal, empty) }
            assertEquals(before, tree(f.base))
            assertFalse(f.root.exists())
        }
    }

    @Test fun missingNestedParentDoesNotHideHistoricalOperation() = fixture { f ->
        val intent = prepare(f, CanonicalFileOperationJournal.Phase.PROPOSAL_RECORDED)
        Files.move(f.source.parentFile.toPath(), File(f.base, "retained-directory").toPath())
        val before = tree(f.base)
        assertEquals(intent, f.journal().readPendingMetadataForRecovery())
        refuse { f.journal().read() }
        assertEquals(before, tree(f.base))
        assertFalse(f.source.parentFile.exists())
    }

    @Test fun replacementRootDoesNotAcquireOldOperationAuthority() = fixture { f ->
        val intent = prepare(f, CanonicalFileOperationJournal.Phase.SNAPSHOT_RECORDED)
        Files.move(f.root.toPath(), File(f.base, "retained-project").toPath())
        check(f.root.mkdir())
        File(f.root, "new-user-work").writeText("new project content")
        val before = tree(f.base)
        assertEquals(intent, f.journal().readPendingMetadataForRecovery())
        refuse { f.journal().read() }
        refuse { f.journal().capture(intent.id, empty) }
        refuse { f.journal().verifySnapshot(intent.id, empty) }
        assertEquals(before, tree(f.base))
    }

    @Test fun fileInPlaceOfRootAllowsMetadataButNoWorkspaceOperation() = fixture { f ->
        val intent = prepare(f, CanonicalFileOperationJournal.Phase.PREPARED)
        Files.move(f.root.toPath(), File(f.base, "retained-project").toPath())
        f.root.writeText("replacement file")
        val before = tree(f.base)
        assertEquals(intent, f.journal().readPendingMetadataForRecovery())
        refuse { f.journal().read() }
        assertEquals(before, tree(f.base))
    }

    @Test fun emptyJournalDoesNotRecreateMissingWorkspace() = fixture { f ->
        Files.move(f.root.toPath(), File(f.base, "retained-project").toPath())
        val before = tree(f.base)
        assertNull(f.journal().readPendingMetadataForRecovery())
        refuse { f.journal().read() }
        assertEquals(before, tree(f.base))
    }

    @Test fun restoringExactOriginalRootReenablesExistingStrictReadWithoutReenrollment() = fixture { f ->
        val intent = prepare(f, CanonicalFileOperationJournal.Phase.PROPOSAL_RECORDED)
        val retained = File(f.base, "retained-project")
        Files.move(f.root.toPath(), retained.toPath())
        assertEquals(intent, f.journal().readPendingMetadataForRecovery())
        refuse { f.journal().read() }
        Files.move(retained.toPath(), f.root.toPath())
        val before = tree(f.base)
        assertEquals(intent, f.journal().read())
        assertEquals(intent.proposalCopy, f.journal().verifyProposal(intent.id, empty))
        assertEquals(before, tree(f.base))
    }

    @Test fun metadataReadDoesNotPretendToValidateCorruptCustodyBytes() = fixture { f ->
        val intent = prepare(f, CanonicalFileOperationJournal.Phase.PROPOSAL_RECORDED)
        File(f.custody, intent.proposalName).writeText("changed proposal")
        val before = tree(f.base)
        assertEquals(intent, f.journal().readPendingMetadataForRecovery())
        refuse { f.journal().verifyProposal(intent.id, empty) }
        assertEquals(before, tree(f.base))
    }

    @Test fun missingOrReplacedDatabaseIsNotRecreatedOrAdoptedByMetadataRead() {
        for (replace in listOf(false, true)) fixture { f ->
            prepare(f, CanonicalFileOperationJournal.Phase.SNAPSHOT_RECORDED)
            val retained = File(f.base, "retained.sqlite")
            Files.move(f.database.toPath(), retained.toPath())
            if (replace) Files.copy(retained.toPath(), f.database.toPath())
            val before = tree(f.base)
            refuse { f.journal().readPendingMetadataForRecovery() }
            assertEquals(before, tree(f.base))
            assertEquals(replace, f.database.exists())
        }
    }

    @Test fun corruptMetadataRemainsUntouchedAndCannotBecomeEmptyRecoveryState() {
        for (kind in listOf("database", "operation", "digest", "generation")) fixture { f ->
            prepare(f, CanonicalFileOperationJournal.Phase.SNAPSHOT_RECORDED)
            if (kind == "database") f.database.writeBytes(ByteArray(8192) { 73 })
            else f.sql { db -> db.execSQL(when (kind) {
                "operation" -> "DELETE FROM operation"
                "digest" -> "UPDATE meta SET record_digest='${"0".repeat(64)}'"
                else -> "UPDATE meta SET generation='1066e46f-30db-4415-8197-66d972246772'"
            }) }
            val before = tree(f.base)
            refuse { f.journal().readPendingMetadataForRecovery() }
            assertEquals(before, tree(f.base))
        }
    }

    @Test fun metadataReadNeverFollowsDatabaseOrCustodySymlinks() {
        for (database in listOf(false, true)) fixture { f ->
            prepare(f, CanonicalFileOperationJournal.Phase.SNAPSHOT_RECORDED)
            val entry = if (database) f.database else f.custody
            val retained = File(f.base, "retained-entry")
            Files.move(entry.toPath(), retained.toPath())
            Files.createSymbolicLink(entry.toPath(), retained.toPath())
            val before = tree(f.base)
            refuse { f.journal().readPendingMetadataForRecovery() }
            assertEquals(before, tree(f.base))
        }
    }

    @Test fun sourceRootSymlinkStillFailsConstructorBindingWithoutTouchingTarget() = fixture { f ->
        prepare(f, CanonicalFileOperationJournal.Phase.PREPARED)
        val retained = File(f.base, "retained-project")
        Files.move(f.root.toPath(), retained.toPath())
        Files.createSymbolicLink(f.root.toPath(), retained.toPath())
        val before = tree(f.base)
        refuse { f.journal().readPendingMetadataForRecovery() }
        assertEquals(before, tree(f.base))
    }

    @Test fun orphanCustodyStillPreventsEmptyMetadataAdmission() = fixture { f ->
        File(f.custody, "unfinished-proposal").writeText("retained work")
        Files.move(f.root.toPath(), File(f.base, "retained-project").toPath())
        val before = tree(f.base)
        refuse { f.journal().readPendingMetadataForRecovery() }
        assertEquals(before, tree(f.base))
    }

    @Test fun repeatedMetadataReadsDoNotRewriteStoredRecordOrPayload() = fixture { f ->
        val intent = prepare(f, CanonicalFileOperationJournal.Phase.PROPOSAL_RECORDED)
        Files.move(f.root.toPath(), File(f.base, "retained-project").toPath())
        val before = tree(f.base)
        repeat(10) { assertEquals(intent, f.journal().readPendingMetadataForRecovery()) }
        assertEquals(before, tree(f.base))
    }

    private fun prepare(f: CanonicalJournalFixture, phase: CanonicalFileOperationJournal.Phase): CanonicalFileOperationJournal.Intent {
        val journal = f.journal()
        val intent = journal.admit(f.path, f.before(), f.proposal(), empty)
        fun result() = checkNotNull(journal.read()).also { assertEquals(phase, it.phase) }
        fun interruptedAt(point: String, action: (CanonicalFileOperationJournal) -> Unit) {
            val failure = java.io.IOException("Interrupted at $point")
            assertSame(failure, refuse { action(f.journal(checkpoint = { if (it == point) throw failure })) })
        }
        if (phase == CanonicalFileOperationJournal.Phase.PREPARED) return result()
        journal.capture(intent.id, empty)
        if (phase == CanonicalFileOperationJournal.Phase.SNAPSHOT_RECORDED) return result()
        val dir = File(f.base, "stage").also { check(it.mkdir()) }
        val stage = File(dir, "proposal.bin")
        CanonicalJournalFixture.write(stage, f.proposedBytes)
        val version = f.before(stage)
        AnchoredWorkspaceFiles(dir).use { journal.captureProposal(intent.id, it, stage.name, version, empty) }
        if (phase == CanonicalFileOperationJournal.Phase.PROPOSAL_RECORDED) return result()
        journal.stagePublication(intent.id, empty)
        if (phase == CanonicalFileOperationJournal.Phase.PUBLICATION_STAGED) return result()
        if (phase == CanonicalFileOperationJournal.Phase.RETIREMENT_PREPARED) {
            interruptedAt("retirement-intent-after-commit") { it.retireCanonical(intent.id, empty) }
            return result()
        }
        if (phase == CanonicalFileOperationJournal.Phase.RETIREMENT_RECOVERED) {
            interruptedAt("retirement-after-move") { it.retireCanonical(intent.id, empty) }
            journal.recoverRetiredState(intent.id, empty)
            return result()
        }
        journal.retireCanonical(intent.id, empty)
        if (phase == CanonicalFileOperationJournal.Phase.RETIREMENT_RECORDED) return result()
        journal.recordRetainedSnapshot(intent.id, empty)
        if (phase == CanonicalFileOperationJournal.Phase.RETENTION_RECORDED) return result()
        if (phase == CanonicalFileOperationJournal.Phase.PUBLICATION_PREPARED) {
            interruptedAt("publication-intent-after-commit") { it.publishStaged(intent.id, empty) }
            return result()
        }
        if (phase == CanonicalFileOperationJournal.Phase.PUBLICATION_RECOVERED) {
            interruptedAt("publication-after-move") { it.publishStaged(intent.id, empty) }
            journal.recoverPublishedState(intent.id, empty)
            return result()
        }
        journal.publishStaged(intent.id, empty)
        if (phase == CanonicalFileOperationJournal.Phase.PUBLICATION_RECORDED) return result()
        check(phase == CanonicalFileOperationJournal.Phase.PUBLICATION_COMPLETED) { "Unsupported fixture phase: $phase" }
        journal.recordPublicationCompletion(intent.id, empty)
        return result()
    }
    private fun fixture(action: (CanonicalJournalFixture) -> Unit) = CanonicalJournalFixture().use(action)
    private fun refuse(action: () -> Unit): Exception {
        try { action() } catch (error: Exception) { return error }
        throw AssertionError("Expected explicit refusal")
    }
    private fun tree(root: File): Map<String, String> = Files.walk(root.toPath()).use { paths ->
        paths.filter { !Files.isDirectory(it, NOFOLLOW_LINKS) }.toList().associate { path ->
            path.toString() to if (Files.isSymbolicLink(path)) "link:${Files.readSymbolicLink(path)}"
            else "${CanonicalJournalFixture.key(path.toFile())}:${Files.getLastModifiedTime(path)}:${CanonicalJournalFixture.sha256(Files.readAllBytes(path))}"
        }
    }
    companion object { private val empty = EmptyCoroutineContext }
}
