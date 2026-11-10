package com.baystudio.droide.core

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Host journal inspection and durability checks with bounded, owned file fixtures. */
class SafCheckpointAdmissionReadBaselineTest {
    private val roots = arrayListOf<File>()
    @After fun cleanup() { roots.asReversed().forEach { PathSecurity.deleteTreeNoFollow(it) } }

    @Test(timeout = 30_000) fun checkpointPresentBeginSharesOneVerifiedAdmission(): Unit = runBlocking {
        val f = Fixture()
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val poll = { guard.requireRoot(f.workspace) }
            f.populate(poll)
            val journal = f.open()
            val checkpointBefore = f.checkpoint.readBytes()
            val manifestBefore = f.manifest.readBytes()
            val unlinksBefore = f.unlinks
            val movesBefore = f.checkpointMoves
            f.checkpointReads = 0
            val incoming = journal.begin(SafOperationRequest(SafOperationKind.WRITE, listOf("incoming")),
                listOf("incoming"), f.reservation, poll)
            val reads = f.checkpointReads
            println("checkpoint admission existing-seam reads: phase=begin reads=$reads ceiling=$READ_CEILING")
            assertArrayEquals(checkpointBefore, f.checkpoint.readBytes())
            assertArrayEquals(manifestBefore, f.manifest.readBytes())
            assertEquals("This fixture must not select retirement during the measured phase", unlinksBefore, f.unlinks)
            assertEquals(movesBefore, f.checkpointMoves)
            assertEquals(checkpointBefore.safDigest(), incoming.envelope.headDigest)
            assertEquals(setOf("operation.json"), incoming.files)
            f.assertSentinel()
            assertTrue("A checkpoint-present begin reread the complete checkpoint $reads times; one verified admission plus its final exact reseal allows at most $READ_CEILING", reads <= READ_CEILING)
        }
    }

    @Test(timeout = 30_000) fun checkpointPresentPlanSharesOneVerifiedAdmission(): Unit = runBlocking {
        val f = Fixture()
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val poll = { guard.requireRoot(f.workspace) }
            f.populate(poll)
            val journal = f.open()
            val incoming = journal.begin(SafOperationRequest(SafOperationKind.WRITE, listOf("incoming")),
                listOf("incoming"), f.reservation, poll)
            val checkpointBefore = f.checkpoint.readBytes()
            val manifestBefore = f.manifest.readBytes()
            val unlinksBefore = f.unlinks
            val movesBefore = f.checkpointMoves
            f.checkpointReads = 0
            SafDurableManifestCommit(journal).plan(incoming, mapOf("incoming" to f.fingerprint), poll)
            val reads = f.checkpointReads
            println("checkpoint admission existing-seam reads: phase=plan reads=$reads ceiling=$READ_CEILING")
            assertArrayEquals(checkpointBefore, f.checkpoint.readBytes())
            assertArrayEquals(manifestBefore, f.manifest.readBytes())
            assertEquals(unlinksBefore, f.unlinks)
            assertEquals(movesBefore, f.checkpointMoves)
            assertTrue("manifest-plan.json" in incoming.files)
            assertFalse("Planning alone must not acknowledge or publish", "committed.json" in incoming.files)
            val plan = journal.read(incoming, "manifest-plan.json", SafManifestPlan.serializer(), poll)
            assertEquals(4L, plan.parentRevision)
            assertEquals(manifestBefore.safDigest(), plan.parentDigest)
            assertEquals(5L, plan.revision)
            f.assertSentinel()
            assertTrue("A checkpoint-present plan reread the complete checkpoint $reads times; one verified admission plus its final exact reseal allows at most $READ_CEILING", reads <= READ_CEILING)
        }
    }

    @Test(timeout = 30_000) fun exportInspectionNeverForcesWritesOrRetiresEvidence(): Unit = runBlocking {
        val f = Fixture()
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val poll = { guard.requireRoot(f.workspace) }
            f.populate(poll)
            val before = f.checkpoint.readBytes()
            val manifest = f.manifest.readBytes()
            val forces = f.forces
            val unlinks = f.unlinks
            val moves = f.checkpointMoves
            val journal = f.open()
            val admission = journal.inspectExportReadOnly(poll)
            val publisher = SafDurableManifestCommit(journal)
            publisher.admitBeforeEffects(admission, poll)
            publisher.trusted(admission, poll)
            assertEquals(forces, f.forces)
            assertEquals(unlinks, f.unlinks)
            assertEquals(moves, f.checkpointMoves)
            assertArrayEquals(before, f.checkpoint.readBytes())
            assertArrayEquals(manifest, f.manifest.readBytes())
        }
    }

    @Test(timeout = 30_000) fun exportInspectionRefusesPendingWithoutAbandonOrCleanup(): Unit = runBlocking {
        val f = Fixture()
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val poll = { guard.requireRoot(f.workspace) }
            val journal = f.open()
            val pending = journal.begin(SafOperationRequest(SafOperationKind.WRITE, listOf("incoming")),
                listOf("incoming"), f.reservation, poll)
            val forces = f.forces
            val unlinks = f.unlinks
            try { journal.inspectExportReadOnly(poll); fail("Pending publication must block export") }
            catch (_: IllegalStateException) { }
            assertEquals(forces, f.forces)
            assertEquals(unlinks, f.unlinks)
            assertFalse(journal.isTerminal(journal.inspectReadOnly(poll).operations.single { it.id == pending.id }))
        }
    }

    @Test(timeout = 30_000) fun readOnlyExportDoesNotForceWithOrWithoutACheckpoint(): Unit = runBlocking {
        for (operations in listOf(2, 4)) {
            val f = Fixture()
            WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
                val poll = { guard.requireRoot(f.workspace) }
                f.populate(poll, operations)
                val before = f.evidence()
                val forces = f.forces
                val unlinks = f.unlinks
                val moves = f.checkpointMoves
                f.rejectForces = true
                val journal = f.open()
                val inspection = journal.inspectExportReadOnly(poll)
                val publisher = SafDurableManifestCommit(journal)
                publisher.admitBeforeEffects(inspection, poll)
                assertEquals(operations.toLong(), publisher.trusted(inspection, poll).revision)
                assertEquals(forces, f.forces)
                assertEquals(unlinks, f.unlinks)
                assertEquals(moves, f.checkpointMoves)
                assertEquals(before, f.evidence())
                try {
                    journal.recoverAdmission(poll)
                    fail("Mutation admission must still force durable acknowledgements")
                } catch (failure: java.io.IOException) {
                    assertEquals("injected durability failure", failure.message)
                }
                assertEquals(before, f.evidence())
                f.rejectForces = false
                val recovered = journal.recoverAdmission(poll)
                assertTrue(f.forces > forces)
                assertEquals(operations.toLong(), publisher.trusted(recovered, poll).revision)
                assertEquals(before, f.evidence())
            }
        }
    }

    @Test(timeout = 30_000) fun readOnlyExportStillRejectsCorruptAcknowledgementWithoutCleanup(): Unit = runBlocking {
        val f = Fixture()
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val poll = { guard.requireRoot(f.workspace) }
            f.populate(poll, operations = 2)
            val journal = f.open()
            val operation = journal.inspectExportReadOnly(poll).operations.first()
            journal.file(operation, "committed.json").writeText("{}")
            val before = f.evidence()
            val forces = f.forces
            val unlinks = f.unlinks
            f.rejectForces = true
            try {
                journal.inspectExportReadOnly(poll)
                fail("Malformed acknowledgement must not be trusted")
            } catch (_: IllegalArgumentException) { }
            assertEquals(forces, f.forces)
            assertEquals(unlinks, f.unlinks)
            assertEquals(before, f.evidence())
            f.assertSentinel()
        }
    }

    private inner class Fixture {
        val root = Files.createTempDirectory("saf-checkpoint-admission-reads-").toFile().also { roots += it }
        val workspace = root.resolve("workspace").apply { check(mkdir()) }
        val directory = root.resolve("journal")
        val checkpoint = directory.resolve(SafJournalRetirement.CURRENT)
        val manifest = workspace.resolve(".droide/saf_manifest.json")
        private val sentinel = byteArrayOf(0, -1, 42, 10)
        val fingerprint = "f:${sentinel.safDigest()}"
        // high-water = 3, tail = 1. Begin #4 retires two successes; afterward only two remain.
        val limits = SafJournalLimits(operations = 6, indexBytes = 128L * 1024, recordBytes = 64L * 1024,
            manifestBytes = 4096, aggregateMetadataBytes = 2L * 1024 * 1024,
            aggregatePayloadBytes = 2L * 1024 * 1024, actionsPerOperation = 2, evidenceFilesPerOperation = 8)
        val reservation = SafJournalReservation(2, 32L * 1024, 64L * 1024, 8192, 8)
        var forces = 0
        var rejectForces = false
        var checkpointReads = 0
        var checkpointMoves = 0
        var unlinks = 0
        val storage = SafOperationStorage(
            forceDirectory = { file ->
                if (rejectForces) throw java.io.IOException("injected durability failure")
                forces++
                FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
            },
            forceFile = { file ->
                if (rejectForces) throw java.io.IOException("injected durability failure")
                forces++
                FileChannel.open(file.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
            },
            replaceAtomically = { source, target ->
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                if (target == checkpoint) checkpointMoves++
                Unit
            },
            beforeRead = { if (it == checkpoint) checkpointReads++ },
            removeOwned = { file -> Files.delete(file.toPath()); unlinks++ },
        )
        init {
            workspace.resolve("sentinel.bin").writeBytes(sentinel)
            check(manifest.parentFile!!.mkdir())
            manifest.writeBytes(SafJournalCodec.encode(SafLegacyTrustedManifest.serializer(),
                SafLegacyTrustedManifest(entries = sortedMapOf("sentinel.bin" to fingerprint))))
            SafOperationJournal.create(workspace, TREE, directory, storage, limits)
        }
        fun open() = SafOperationJournal.open(workspace, TREE, directory, storage, limits)
        fun populate(poll: () -> Unit, operations: Int = 4) {
            repeat(operations) { ordinal ->
                val journal = open()
                val path = "value-$ordinal"
                val operation = journal.begin(SafOperationRequest(SafOperationKind.WRITE, listOf(path)),
                    listOf(path), reservation, poll)
                val publisher = SafDurableManifestCommit(journal)
                publisher.plan(operation, mapOf(path to fingerprint), poll)
                assertTrue(publisher.publish(operation, poll).acknowledged)
            }
            assertEquals(operations == 4, checkpoint.isFile)
            assertEquals(2, open().summaries(poll).size)
            assertEquals(operations == 4, unlinks > 0)
            assertSentinel()
        }
        fun evidence(): Map<String, Pair<String, List<Byte>>> = root.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(root).path to (Files.readAttributes(it.toPath(),
                java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                .fileKey().toString() to it.readBytes().toList())
        }
        fun assertSentinel() { assertArrayEquals(sentinel, workspace.resolve("sentinel.bin").readBytes()) }
    }

    companion object {
        private const val TREE = "content://fixture/tree/root"
        // Four current SAF1 load reads plus one final exact checkpoint reseal, never a cache.
        private const val READ_CEILING = 5
    }
}
