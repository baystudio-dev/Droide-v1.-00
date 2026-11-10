package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProjectCopyRetirementRecoveryTest {
    @Test fun inspectionReadsEvidenceWithoutChangingFilesOrPermissions() = fixture { f ->
        f.interrupt("intent-durable")
        val before = f.snapshot()
        val inspected = f.journal.inspectRetirement()
        assertEquals(f.id, inspected.transactionId)
        assertEquals(ProjectCopyJournal.RetirementLocation.ACTIVE_HISTORY, inspected.location)
        assertEquals(before, f.snapshot())
        assertNotNull(f.journal.recovery(f.scope).blockingProblem)
    }

    @Test fun explicitConfirmationCompletesAnActiveRecordAndAllowsANewImport() = fixture { f ->
        f.interrupt("intent-durable")
        val proof = f.recordProof()
        val inspected = f.journal.inspectRetirement()
        f.journal.finishRetirement(inspected.token)
        f.assertComplete(proof)
        val after = f.snapshot()
        f.refuse { f.journal.finishRetirement(inspected.token) }
        assertEquals(after, f.snapshot())
        f.commit("third")
        assertEquals("payload-third", f.root.resolve("third.bin").readText())
    }

    @Test fun restartedJournalCompletesAlreadyArchivedEvidence() = fixture { f ->
        f.interrupt("record-transferred")
        f.journal = f.newJournal()
        val proof = f.recordProof()
        val inspected = f.journal.inspectRetirement()
        assertEquals(ProjectCopyJournal.RetirementLocation.ARCHIVE, inspected.location)
        f.journal.finishRetirement(inspected.token)
        f.assertComplete(proof)
    }

    @Test fun expiredInspectionCannotChangeTheNamespace() = fixture { f ->
        f.interrupt("intent-durable")
        val inspected = f.journal.inspectRetirement(); f.elapsed = inspected.expiresAt + 1
        val before = f.snapshot()
        f.refuse { f.journal.finishRetirement(inspected.token) }
        assertEquals(before, f.snapshot())
    }

    @Test fun anotherJournalCannotUseAnInspectionCapability() = fixture { f ->
        f.interrupt("intent-durable")
        val inspected = f.journal.inspectRetirement(); val before = f.snapshot()
        f.refuse { f.newJournal().finishRetirement(inspected.token) }
        assertEquals(before, f.snapshot())
        f.journal.finishRetirement(inspected.token)
        assertFalse(f.pending.exists())
    }

    @Test fun newInspectionInvalidatesThePreviousDecision() = fixture { f ->
        f.interrupt("intent-durable")
        val old = f.journal.inspectRetirement(); f.journal.inspectRetirement()
        val before = f.snapshot()
        f.refuse { f.journal.finishRetirement(old.token) }
        assertEquals(before, f.snapshot())
    }

    @Test fun invalidTokenIsConsumedWithoutAnyFilesystemMutation() = fixture { f ->
        f.interrupt("intent-durable")
        val good = f.journal.inspectRetirement(); val before = f.snapshot()
        f.refuse { f.journal.finishRetirement("untrusted-token") }
        f.refuse { f.journal.finishRetirement(good.token) }
        assertEquals(before, f.snapshot())
    }

    @Test fun replacedPendingIntentWithIdenticalTextRequiresAnotherInspection() = fixture { f ->
        f.interrupt("intent-durable")
        val inspected = f.journal.inspectRetirement(); val text = f.pending.readText()
        Files.move(f.pending.toPath(), f.root.resolve("held-intent").toPath()); f.pending.writeText(text)
        val before = f.snapshot()
        f.refuse { f.journal.finishRetirement(inspected.token) }
        assertEquals(before, f.snapshot())
    }

    @Test fun corruptPendingMetadataIsNotRepairedOrReplaced() = fixture { f ->
        f.interrupt("intent-durable"); f.pending.writeText("{truncated")
        val before = f.snapshot()
        f.refuse { f.journal.inspectRetirement() }
        assertEquals(before, f.snapshot())
    }

    @Test fun traversalInPendingIntentCannotSelectAnotherDirectory() = fixture { f ->
        f.interrupt("intent-durable")
        f.pending.writeText(f.pending.readText().replace("\"id\":\"${f.id}\"", "\"id\":\"../outside\""))
        val before = f.snapshot()
        f.refuse { f.journal.inspectRetirement() }
        assertEquals(before, f.snapshot())
    }

    @Test fun changedRecordThroughHeldDescriptorStopsConfirmation() = fixture { f ->
        f.interrupt("record-transferred")
        val inspected = f.journal.inspectRetirement()
        FileChannel.open(f.archiveRecord.resolve("attempt.json").toPath(), StandardOpenOption.WRITE).use {
            it.truncate(0); it.write(java.nio.ByteBuffer.wrap("external bytes".toByteArray())); it.force(true)
        }
        val before = f.snapshot()
        f.refuse { f.journal.finishRetirement(inspected.token) }
        assertEquals(before, f.snapshot())
        assertEquals("external bytes", f.archiveRecord.resolve("attempt.json").readText())
    }

    @Test fun competingRecordAtBothLocationsRemainsUntouched() = fixture { f ->
        f.interrupt("intent-durable")
        f.archiveRecord.mkdir(); f.archiveRecord.resolve("foreign.txt").writeText("foreign")
        val before = f.snapshot()
        f.refuse { f.journal.inspectRetirement() }
        assertEquals(before, f.snapshot())
    }

    @Test fun existingReceiptPreventsRecoveryWithoutOverwritingEitherIntent() = fixture { f ->
        f.interrupt("record-transferred"); f.receipt.writeText("foreign-receipt")
        val before = f.snapshot()
        f.refuse { f.journal.inspectRetirement() }
        assertEquals(before, f.snapshot())
    }

    @Test fun pendingSymlinkIsRejectedAndItsTargetPreserved() = fixture { f ->
        f.interrupt("intent-durable")
        val held = f.root.resolve("held-intent"); Files.move(f.pending.toPath(), held.toPath())
        Files.createSymbolicLink(f.pending.toPath(), held.toPath())
        val original = held.readBytes()
        f.refuse { f.journal.inspectRetirement() }
        assertArrayEquals(original, held.readBytes())
        assertTrue(Files.isSymbolicLink(f.pending.toPath()))
    }

    @Test fun archiveDirectoryReplacementCannotBeAdopted() = fixture { f ->
        f.interrupt("intent-durable")
        val inspected = f.journal.inspectRetirement()
        Files.move(f.archive.toPath(), f.root.resolve("held-archive").toPath()); f.archive.mkdir()
        val before = f.snapshot()
        f.refuse { f.journal.finishRetirement(inspected.token) }
        assertEquals(before, f.snapshot())
    }

    @Test fun retainedPayloadAppearingAfterInspectionPreventsCompletion() = fixture { f ->
        f.interrupt("intent-durable")
        val inspected = f.journal.inspectRetirement()
        f.root.resolve(f.stagePath).writeText("unpublished stage")
        val before = f.snapshot()
        f.refuse { f.journal.finishRetirement(inspected.token) }
        assertEquals(before, f.snapshot())
        assertEquals("unpublished stage", f.root.resolve(f.stagePath).readText())
    }

    @Test fun replacementAfterValidationIsRetainedAndNotReportedAsSuccessful() = fixture { f ->
        f.interrupt("intent-durable")
        val inspected = f.journal.inspectRetirement(); val selected = f.activeRecord.resolve("attempt.json")
        val original = selected.readText()
        f.journal.retirementCheckpoint = { phase ->
            if (phase == "recovery-before-record-transfer") {
                Files.move(selected.toPath(), f.root.resolve("writer-held").toPath())
                selected.writeText("unpublished replacement")
            }
        }
        assertTrue(f.refuse { f.journal.finishRetirement(inspected.token) } is ProjectCopyJournal.Blocked)
        assertEquals(original, f.root.resolve("writer-held").readText())
        assertEquals("unpublished replacement", f.archiveRecord.resolve("attempt.json").readText())
        assertTrue(f.pending.isFile)
        assertNotNull(f.newJournal().recovery(f.scope).blockingProblem)
    }

    @Test fun cancelledOwnerCannotAuthorizeRecovery() = fixture { f ->
        f.interrupt("intent-durable")
        val inspected = f.journal.inspectRetirement(); val before = f.snapshot()
        assertTrue(f.refuse { f.journal.finishRetirement(inspected.token) { throw CancellationException("closed") } } is CancellationException)
        assertEquals(before, f.snapshot())
    }

    @Test fun cancellationDuringRecoveryPreservesPendingEvidenceForFreshInspection() = fixture { f ->
        f.interrupt("intent-durable")
        val inspected = f.journal.inspectRetirement(); val before = f.snapshot()
        f.journal.retirementCheckpoint = { phase -> if (phase == "recovery-intent-durable") throw CancellationException("closed") }
        assertTrue(f.refuse { f.journal.finishRetirement(inspected.token) } is CancellationException)
        assertEquals(before, f.snapshot())
        f.journal.retirementCheckpoint = null
        f.journal.finishRetirement(f.journal.inspectRetirement().token)
        assertFalse(f.pending.exists())
    }

    @Test fun observingANewProjectNeverCreatesRecoveryMetadata() = fixture { f ->
        val before = f.snapshot()
        f.refuse { f.journal.inspectRetirement() }
        assertEquals(before, f.snapshot())
        assertFalse(f.root.resolve(".droide").exists())
    }

    @Test fun forcingAReadOnlyDescriptorPreservesPrivatePermissionBitsAndBytes() = fixture { f ->
        val file = f.root.resolve("private.txt").also { it.writeText("private") }
        Files.setPosixFilePermissions(file.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        val before = f.snapshot()
        AnchoredWorkspaceFiles(f.root).use { files -> files.openRead(file.name).use { it.forceContents() } }
        assertEquals(before, f.snapshot())
    }

    private fun fixture(block: (RetirementRecoveryFixture) -> Unit) {
        val root = Files.createTempDirectory("retirement-recovery-").toFile()
        try { block(RetirementRecoveryFixture(root)) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}

internal class RetirementRecoveryFixture(val root: File) {
    val scope = ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.EXPLORER_IMPORT)
    var elapsed = 1000L
    var journal = newJournal()
    lateinit var id: String
    lateinit var stagePath: String
    val archive get() = root.resolve(".droide/copy-recovery-archive")
    val activeRecord get() = root.resolve(".droide/copy-recovery/$id")
    val archiveRecord get() = archive.resolve(id)
    val receipt get() = archive.resolve(".retirement-$id.json")
    val pending get() = root.resolve(".droide/copy-recovery/.retirement-pending")
    fun newJournal() = ProjectCopyJournal(root, historyLimit = 1, processEpoch = UUID.randomUUID().toString(), monotonicClock = { elapsed })
    fun owner(name: String) = ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.EXPLORER_IMPORT, null, "run-$name",
        ProjectCopyJournal.explorerSourceId("content://test/$name"))
    fun commit(name: String): ProjectCopyJournal.Intent {
        val lease = journal.begin(owner(name), "$name.bin", planStage = true)
        lease.use { runBlocking {
            FileRepository(root, newFilePublisher = NewProjectFilePublisher.native()).writeStream("$name.bin",
                "payload-$name".byteInputStream(), requireAbsent = true, publicationObserver = it)
        } }
        return lease.intent
    }
    fun interrupt(phase: String) {
        val first = commit("first"); id = first.id; stagePath = requireNotNull(first.plannedStagePath)
        root.resolve("unpublished.txt").writeText("CLI work")
        journal.retirementCheckpoint = { if (it == phase) throw IOException("interrupted at $phase") }
        refuse { commit("second") }
        journal.retirementCheckpoint = null
        check(pending.isFile)
    }
    fun recordProof() = (if (activeRecord.exists()) activeRecord else archiveRecord).listFiles()!!.associate {
        it.name to (ProjectCopyEvidence.identity(it).key to it.readText())
    }
    fun assertComplete(proof: Map<String, Pair<String, String>>) {
        assertFalse(pending.exists()); assertFalse(activeRecord.exists()); assertTrue(receipt.isFile)
        proof.forEach { (name, value) -> assertEquals(value, ProjectCopyEvidence.identity(archiveRecord.resolve(name)).key to archiveRecord.resolve(name).readText()) }
        assertNull(journal.recovery(scope).blockingProblem)
        assertEquals("payload-first", root.resolve("first.bin").readText())
        assertEquals("CLI work", root.resolve("unpublished.txt").readText())
        assertFalse(root.resolve("second.bin").exists())
    }
    fun snapshot() = root.walkTopDown().filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }.associate {
        it.relativeTo(root).path to listOf(ProjectCopyEvidence.identity(it).toString(), it.readText(),
            Files.getPosixFilePermissions(it.toPath()).toString())
    }
    fun refuse(block: () -> Unit): Exception {
        try { block() } catch (failure: Exception) { return failure }
        throw AssertionError("Expected a conservative refusal")
    }
}
