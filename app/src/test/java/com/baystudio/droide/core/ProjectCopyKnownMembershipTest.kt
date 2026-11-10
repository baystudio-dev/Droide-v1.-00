package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Detect missing process-known records without claiming historical membership after close/restart. */
class ProjectCopyKnownMembershipTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.AGENT_MEDIA, "session")
    private fun owner(run: String = "first") = ProjectCopyJournal.Owner(
        ProjectCopyJournal.Kind.AGENT_MEDIA, "session", run, "same-source")
    private fun project() = temporary.newFolder("project")
    private fun record(root: File, id: String) = root.resolve(".droide/copy-recovery/$id")
    private fun snapshot(root: File): Map<String, String> = root.walkTopDown().associate {
        it.relativeTo(root).path to if (it.isDirectory) "directory" else ProjectCopyEvidence.sha256(it.readBytes())
    }
    private fun assertObservation(journal: ProjectCopyJournal, id: String) {
        val problem = journal.recovery(scope).blockingProblem
        val before = snapshot(temporary.root)
        assertNotNull("Known missing record must block: $id", problem)
        assertTrue(requireNotNull(problem).contains(id))
        val failure = runCatching { journal.begin(owner("later"), "later.bin").close() }.exceptionOrNull()
        assertTrue(failure is ProjectCopyJournal.Blocked)
        assertTrue(requireNotNull(failure).message.orEmpty().contains(id))
        assertEquals("Blocked admission must not change any retained file bytes", before, snapshot(temporary.root))

    }

    @Test fun movedWholeActiveRecordCannotDisappearFromSameInstance() {
        val root = project()
        val journal = ProjectCopyJournal(root, processEpoch = UUID.randomUUID().toString())
        journal.begin(owner(), "asset.bin").use { lease ->
            val preserved = temporary.newFolder("preserved").resolve(lease.intent.id)
            Files.move(record(root, lease.intent.id).toPath(), preserved.toPath())
            val before = snapshot(preserved)
            assertObservation(journal, lease.intent.id)
            assertEquals(before, snapshot(preserved))
            assertFalse(root.resolve("asset.bin").exists())
        }
    }

    @Test fun movedWholeActiveRecordCannotDisappearFromSharedEpochInstance() {
        val root = project()
        val epoch = UUID.randomUUID().toString()
        val journal = ProjectCopyJournal(root, processEpoch = epoch)
        journal.begin(owner(), "asset.bin").use { lease ->
            val preserved = temporary.newFolder("preserved").resolve(lease.intent.id)
            Files.move(record(root, lease.intent.id).toPath(), preserved.toPath())
            val before = snapshot(preserved)
            assertObservation(ProjectCopyJournal(root, processEpoch = epoch), lease.intent.id)
            assertEquals(before, snapshot(preserved))
        }
    }

    @Test fun movedTaintedRecordAfterCloseRemainsProcessKnown() {
        val root = project()
        var failNextForce = false
        val durability = object : ProjectCopyJournal.Durability {
            override fun forceFile(channel: FileChannel) {
                if (failNextForce) { failNextForce = false; throw IOException("Controlled attempt persistence failure") }
                ProjectCopyJournal.Durability.platform.forceFile(channel)
            }
            override fun forceDirectory(directory: File) = ProjectCopyJournal.Durability.platform.forceDirectory(directory)
        }
        val epoch = UUID.randomUUID().toString()
        val journal = ProjectCopyJournal(root, durability = durability, processEpoch = epoch)
        val lease = journal.begin(owner(), "asset.bin")
        failNextForce = true
        val preparation = StreamPublicationPreparation(UUID.randomUUID().toString(), root.canonicalPath,
            ProjectCopyEvidence.root(root).second, "asset.bin", 1L, ProjectCopyEvidence.sha256(byteArrayOf(1)), "fixture-stage")
        assertTrue(runCatching { lease.beforePublish(preparation) }.exceptionOrNull() is IOException)
        lease.close()
        assertEquals(ProjectCopyJournal.Status.UNKNOWN, journal.recovery(scope).records.single().status)
        val preserved = temporary.newFolder("preserved").resolve(lease.intent.id)
        Files.move(record(root, lease.intent.id).toPath(), preserved.toPath())
        val before = snapshot(preserved)
        assertObservation(ProjectCopyJournal(root, processEpoch = epoch), lease.intent.id)
        assertEquals(before, snapshot(preserved))
        assertFalse(root.resolve("asset.bin").exists())
    }

    @Test fun restoringExactLiveRecordReturnsToExistingActiveRule() {
        val root = project()
        val journal = ProjectCopyJournal(root, processEpoch = UUID.randomUUID().toString())
        journal.begin(owner(), "asset.bin").use { lease ->
            val original = record(root, lease.intent.id)
            val preserved = temporary.newFolder("preserved").resolve(lease.intent.id)
            Files.move(original.toPath(), preserved.toPath())
            Files.move(preserved.toPath(), original.toPath())
            val current = journal.recovery(scope)
            assertNull(current.blockingProblem)
            assertTrue(current.records.single().active)
            assertTrue(runCatching { journal.begin(owner("later"), "later.bin") }.exceptionOrNull() is ProjectCopyJournal.Blocked)
        }
    }

    @Test fun genuinelyFreshWorkspaceStillHasEmptyRecovery() {
        val root = project()
        val journal = ProjectCopyJournal(root, processEpoch = UUID.randomUUID().toString())
        assertEquals(ProjectCopyJournal.Recovery(emptyList(), null), journal.recovery(scope))
        assertFalse(root.resolve(".droide").exists())
    }

    @Test fun freshEpochRetainsDurablyAdmittedHistoricalMembership() {
        val root = project()
        val journal = ProjectCopyJournal(root, processEpoch = UUID.randomUUID().toString())
        val lease = journal.begin(owner(), "asset.bin")
        lease.close()
        Files.move(record(root, lease.intent.id).toPath(), temporary.newFolder("preserved").resolve(lease.intent.id).toPath())
        assertNotNull(journal.recovery(scope).blockingProblem)
        val restarted = ProjectCopyJournal(root, processEpoch = UUID.randomUUID().toString())
        assertNotNull(restarted.recovery(scope).blockingProblem)
        assertNotNull(runCatching { restarted.begin(owner("later"), "later.bin") }.exceptionOrNull())
    }
}
