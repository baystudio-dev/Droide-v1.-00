package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class ProjectCopyRetirementTest {
    @Test fun boundedActiveHistoryRetainsEveryCompletedMetadataFile() = fixture { f ->
        val first = f.commit("first")
        val evidence = f.snapshot(f.record(first))
        f.commit("second")
        assertFalse(f.record(first).exists())
        f.assertEvidence(evidence, f.archived(first))
        assertTrue(f.archive.resolve(".retirement-$first.json").isFile)
        assertFalse(f.pending.exists())
        assertNull(f.fresh().recovery(f.scope).blockingProblem)
        assertEquals(1, f.fresh().recovery(f.scope).records.size)
        assertEquals("payload-first", f.root.resolve("first.bin").readText())
    }

    @Test fun repeatedRetirementKeepsTheActiveScanBoundedAcrossRestart() = fixture { f ->
        val records = linkedMapOf<String, Map<String, Pair<String, String>>>()
        repeat(12) { number ->
            f.journal = f.fresh()
            val id = f.commit("copy-$number")
            records[id] = f.snapshot(f.record(id))
            assertEquals(1, f.journal.recovery(f.scope).records.size)
        }
        records.entries.toList().dropLast(1).forEach { (id, before) -> f.assertEvidence(before, f.archived(id)) }
        assertEquals(22, f.archive.listFiles()!!.size)
    }

    @Test fun preparedAndUncertainHistoryNeverMovesToArchive() = fixture { f ->
        val prepared = f.journal.begin(f.owner("prepared"), "prepared.bin").also { it.close() }.intent.id
        val uncertain = f.journal.begin(f.owner("uncertain"), "uncertain.bin")
        uncertain.beforePublish(f.preparation("uncertain")); uncertain.close()
        val preparedBefore = f.snapshot(f.record(prepared))
        val uncertainBefore = f.snapshot(f.record(uncertain.intent.id))
        repeat(3) { f.commit("success-$it") }
        f.assertEvidence(preparedBefore, f.record(prepared))
        f.assertEvidence(uncertainBefore, f.record(uncertain.intent.id))
        assertFalse(f.archived(prepared).exists())
        assertFalse(f.archived(uncertain.intent.id).exists())
    }

    @Test fun existingArchiveDestinationIsNeverReplaced() = fixture { f ->
        val id = f.commit("first"); val before = f.snapshot(f.record(id))
        f.archived(id).mkdirs(); f.archived(id).resolve("foreign.txt").writeText("foreign")
        val failure = f.refuse { f.commit("second") }
        assertTrue(failure is ProjectCopyJournal.Blocked)
        assertTrue(failure.message.orEmpty().contains("copy-recovery-archive"))
        assertNotNull(failure.cause)
        f.assertEvidence(before, f.record(id))
        assertEquals("foreign", f.archived(id).resolve("foreign.txt").readText())
        f.assertPendingBlocksFresh()
    }

    @Test fun fileAtArchiveDestinationIsNeverReplaced() = fixture { f ->
        val id = f.commit("first"); val before = f.snapshot(f.record(id))
        f.archive.mkdir(); f.archived(id).writeText("foreign-file")
        f.refuse { f.commit("second") }
        f.assertEvidence(before, f.record(id))
        assertEquals("foreign-file", f.archived(id).readText())
    }

    @Test fun archiveSymlinkIsNotFollowed() = fixture { f ->
        val id = f.commit("first"); val before = f.snapshot(f.record(id))
        val outside = f.root.resolve("outside").also { it.mkdir() }
        Files.createSymbolicLink(f.archive.toPath(), outside.toPath())
        f.refuse { f.commit("second") }
        f.assertEvidence(before, f.record(id))
        assertTrue(outside.listFiles()!!.isEmpty())
    }

    @Test fun fileAtArchiveRootIsNotReplaced() = fixture { f ->
        val id = f.commit("first"); val before = f.snapshot(f.record(id))
        f.archive.writeText("foreign-root")
        f.refuse { f.commit("second") }
        f.assertEvidence(before, f.record(id))
        assertEquals("foreign-root", f.archive.readText())
    }

    @Test fun existingPendingIntentIsPreservedAndBlocksNewAdmission() = fixture { f ->
        val id = f.commit("first"); val before = f.snapshot(f.record(id))
        f.pending.writeText("incomplete or foreign intent")
        f.refuse { f.commit("second") }
        f.assertEvidence(before, f.record(id))
        assertEquals("incomplete or foreign intent", f.pending.readText())
        f.assertPendingBlocksFresh()
    }

    @Test fun pendingSymlinkIsPreservedWithoutReadingItsTarget() = fixture { f ->
        val id = f.commit("first"); val before = f.snapshot(f.record(id))
        val outside = f.root.resolve("outside.txt").also { it.writeText("outside") }
        Files.createSymbolicLink(f.pending.toPath(), outside.toPath())
        f.refuse { f.commit("second") }
        f.assertEvidence(before, f.record(id))
        assertEquals("outside", outside.readText())
        assertNotNull(f.fresh().recovery(f.scope).blockingProblem)
    }

    @Test fun archiveReceiptCollisionPreservesBothIntentsAndRecord() = fixture { f ->
        val id = f.commit("first"); val before = f.snapshot(f.record(id))
        val receipt = f.archive.resolve(".retirement-$id.json")
        f.once("before-intent-transfer") { receipt.writeText("foreign-receipt") }
        f.refuse { f.commit("second") }
        f.assertEvidence(before, f.archived(id))
        assertEquals("foreign-receipt", receipt.readText())
        f.assertPendingBlocksFresh()
    }

    @Test fun concurrentLeafReplacementAfterValidationPreservesBothVersions() = fixture { f ->
        val id = f.commit("first")
        val selected = f.record(id).resolve("attempt.json")
        val original = selected.readText()
        val retained = f.root.resolve("writer-held.json")
        val start = CountDownLatch(1); val done = CountDownLatch(1)
        var writerFailure: Throwable? = null
        val writer = thread {
            try {
                check(start.await(10, TimeUnit.SECONDS))
                Files.move(selected.toPath(), retained.toPath())
                selected.writeText("unpublished external replacement")
            } catch (failure: Throwable) { writerFailure = failure } finally { done.countDown() }
        }
        try {
            f.once("before-record-transfer") { start.countDown(); check(done.await(10, TimeUnit.SECONDS)) }
            f.refuse { f.commit("second") }
        } finally { start.countDown(); writer.join(10_000) }
        assertFalse(writer.isAlive); assertNull(writerFailure)
        assertEquals(original, retained.readText())
        assertEquals("unpublished external replacement", f.archived(id).resolve("attempt.json").readText())
        f.assertPendingBlocksFresh()
    }

    @Test fun writerHoldingAnOpenDescriptorCanChangeRetainedInodeAndIsDetected() = fixture { f ->
        val id = f.commit("first")
        FileChannel.open(f.record(id).resolve("attempt.json").toPath(), StandardOpenOption.WRITE).use { writer ->
            f.once("record-transferred") {
                writer.truncate(0)
                writer.write(java.nio.ByteBuffer.wrap("changed through held fd".toByteArray()))
                writer.force(true)
            }
            f.refuse { f.commit("second") }
        }
        assertEquals("changed through held fd", f.archived(id).resolve("attempt.json").readText())
        f.assertPendingBlocksFresh()
    }

    @Test fun recordDirectoryReplacementRetainsTheForeignDirectoryAndOriginalEvidence() = fixture { f ->
        val id = f.commit("first"); val before = f.snapshot(f.record(id))
        val held = f.root.resolve("writer-held-directory")
        f.once("before-record-transfer") {
            Files.move(f.record(id).toPath(), held.toPath())
            f.record(id).mkdir(); f.record(id).resolve("unpublished.txt").writeText("unpublished")
        }
        f.refuse { f.commit("second") }
        f.assertEvidence(before, held)
        assertEquals("unpublished", f.archived(id).resolve("unpublished.txt").readText())
        f.assertPendingBlocksFresh()
    }

    @Test fun archiveParentReplacementStopsTransferWithoutDeletingRecords() = fixture { f ->
        val id = f.commit("first"); val before = f.snapshot(f.record(id))
        f.once("before-record-transfer") {
            Files.move(f.archive.toPath(), f.root.resolve("held-archive").toPath())
            f.archive.mkdir(); f.archive.resolve("foreign.txt").writeText("foreign")
        }
        f.refuse { f.commit("second") }
        f.assertEvidence(before, f.record(id))
        assertEquals("foreign", f.archive.resolve("foreign.txt").readText())
        f.assertPendingBlocksFresh()
    }

    @Test fun pendingIntentReplacementIsNotSilentlyOverwritten() = fixture { f ->
        val id = f.commit("first"); val before = f.snapshot(f.record(id))
        val held = f.root.resolve("held-pending.json")
        f.once("before-intent-transfer") {
            Files.move(f.pending.toPath(), held.toPath()); f.pending.writeText("foreign-pending")
        }
        f.refuse { f.commit("second") }
        f.assertEvidence(before, f.archived(id))
        assertTrue(held.readText().contains(id))
        assertEquals("foreign-pending", f.pending.readText())
        f.assertPendingBlocksFresh()
    }

    @Test fun interruptedIntentCreationPreservesTheOriginalHistory() = fixture { f ->
        val id = f.commit("first"); val before = f.snapshot(f.record(id))
        f.once("intent-durable") { throw IOException("interrupted") }
        f.refuse { f.commit("second") }
        f.assertEvidence(before, f.record(id)); f.assertPendingBlocksFresh()
    }

    @Test fun interruptedRecordTransferKeepsTheArchiveAndBlocksAfterRestart() = fixture { f ->
        val id = f.commit("first"); val before = f.snapshot(f.record(id))
        f.once("record-transferred") { throw IOException("interrupted") }
        f.refuse { f.commit("second") }
        f.assertEvidence(before, f.archived(id)); f.assertPendingBlocksFresh()
    }

    @Test fun cancellationPropagatesWithoutRemovingRecoveryEvidence() = fixture { f ->
        val id = f.commit("first"); val before = f.snapshot(f.record(id))
        f.once("before-intent-transfer") { throw CancellationException("cancelled") }
        assertTrue(f.refuse { f.commit("second") } is CancellationException)
        f.assertEvidence(before, f.archived(id)); f.assertPendingBlocksFresh()
    }

    @Test fun directoryForceFailureLeavesADurablePendingIntent() = fixture { f ->
        val id = f.commit("first"); val before = f.snapshot(f.record(id))
        f.once("record-transferred") { f.forceFailure = true }
        f.refuse { f.commit("second") }
        f.assertEvidence(before, f.archived(id)); f.assertPendingBlocksFresh()
    }

    @Test fun completedIntentTransferNeverReplaysOrDeletesProjectDataAfterRestart() = fixture { f ->
        val id = f.commit("first"); val before = f.snapshot(f.record(id))
        f.once("intent-transferred") { throw IOException("lost return") }
        f.refuse { f.commit("second") }
        f.assertEvidence(before, f.archived(id))
        assertTrue(f.archive.resolve(".retirement-$id.json").isFile)
        assertFalse(f.pending.exists())
        assertNotNull(f.journal.recovery(f.scope).blockingProblem)
        assertNull(f.fresh().recovery(f.scope).blockingProblem)
        assertEquals("payload-first", f.root.resolve("first.bin").readText())
        assertFalse(f.root.resolve("second.bin").exists())
    }

    private class Fixture(val root: File) {
        val scope = ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.EXPLORER_IMPORT)
        val archive = root.resolve(".droide/copy-recovery-archive")
        val pending = root.resolve(".droide/copy-recovery/.retirement-pending")
        var forceFailure = false
        var tick = 1000L
        val io = object : ProjectCopyJournal.Durability {
            override fun forceFile(channel: FileChannel) = channel.force(true)
            override fun forceDirectory(directory: File) {
                if (forceFailure) throw IOException("injected directory force failure")
                ProjectCopyJournal.Durability.platform.forceDirectory(directory)
            }
        }
        var journal = fresh()
        fun fresh() = ProjectCopyJournal(root, clock = { tick++ }, durability = io, historyLimit = 1,
            processEpoch = UUID.randomUUID().toString())
        fun owner(name: String) = ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.EXPLORER_IMPORT, null,
            "run-$name", ProjectCopyJournal.explorerSourceId("content://test/$name"))
        fun preparation(name: String): StreamPublicationPreparation {
            val bytes = "payload-$name".toByteArray()
            return StreamPublicationPreparation(UUID.randomUUID().toString(), root.canonicalPath,
                ProjectCopyEvidence.root(root).second, "$name.bin", bytes.size.toLong(),
                ProjectCopyEvidence.sha256(bytes), "stage-test-identity")
        }
        fun commit(name: String): String {
            val lease = journal.begin(owner(name), "$name.bin")
            lease.use {
                val prepared = preparation(name)
                it.beforePublish(prepared)
                root.resolve("$name.bin").writeText("payload-$name")
                it.onPublished(StreamPublicationReceipt(prepared, prepared.stageIdentity))
            }
            return lease.intent.id
        }
        fun record(id: String) = root.resolve(".droide/copy-recovery/$id")
        fun archived(id: String) = archive.resolve(id)
        fun snapshot(dir: File) = dir.listFiles()!!.associate { it.name to
            (ProjectCopyEvidence.identity(it).key to it.readText()) }
        fun assertEvidence(before: Map<String, Pair<String, String>>, dir: File) {
            before.forEach { (name, expected) ->
                assertTrue("Retained $name", dir.resolve(name).isFile)
                assertEquals(expected, ProjectCopyEvidence.identity(dir.resolve(name)).key to dir.resolve(name).readText())
            }
        }
        fun once(boundary: String, action: () -> Unit) {
            var ran = false
            journal.retirementCheckpoint = { phase -> if (phase == boundary && !ran) { ran = true; action() } }
        }
        fun assertPendingBlocksFresh() {
            assertTrue(pending.isFile)
            val restarted = fresh()
            val problem = restarted.recovery(scope).blockingProblem
            assertNotNull(problem)
            assertTrue(problem!!, problem.contains("retirement") && problem.contains("copy-recovery-archive"))
            refuse { restarted.begin(owner("after-restart"), "after-restart.bin") }
            assertFalse(root.resolve("after-restart.bin").exists())
        }
        fun refuse(action: () -> Unit): Exception {
            try { action() } catch (failure: Exception) { return failure }
            throw AssertionError("Expected refusal without deletion or overwrite")
        }
    }
    private fun fixture(block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("copy-retirement-").toFile()
        try { block(Fixture(root)) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
