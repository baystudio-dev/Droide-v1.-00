package com.baystudio.droide.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ProjectCopyReceiptRecoveryTest {
    @Test fun completedReceiptCanBeInspectedWithoutChangingFiles() = fixture { f ->
        interruptedReceipt(f)
        val before = f.snapshot()
        val inspected = f.journal.inspectRetirement()
        assertEquals(f.id, inspected.transactionId)
        assertEquals(ProjectCopyJournal.RetirementLocation.ARCHIVE, inspected.location)
        assertEquals(before, f.snapshot())
        assertNotNull(f.journal.recovery(f.scope).blockingProblem)
    }

    @Test fun explicitReceiptConfirmationClearsOnlyVerifiedFailure() = fixture { f ->
        interruptedReceipt(f)
        val before = f.snapshot(); val proof = f.recordProof()
        f.journal.finishRetirement(f.journal.inspectRetirement().token)
        f.assertComplete(proof)
        assertEquals(before, f.snapshot())
        f.commit("third")
        assertEquals("payload-third", f.root.resolve("third.bin").readText())
    }

    @Test fun ordinaryRetirementLostReturnCanBeRecoveredExplicitly() = fixture { f ->
        val first = f.commit("first"); f.id = first.id; f.stagePath = requireNotNull(first.plannedStagePath)
        f.root.resolve("unpublished.txt").writeText("CLI work")
        f.journal.retirementCheckpoint = { if (it == "intent-transferred") throw IOException("lost retirement return") }
        f.refuse { f.commit("second") }; f.journal.retirementCheckpoint = null
        assertTrue(f.receipt.isFile); assertFalse(f.pending.exists())
        val before = f.snapshot(); val proof = f.recordProof()
        f.journal.finishRetirement(f.journal.inspectRetirement().token)
        f.assertComplete(proof); assertEquals(before, f.snapshot())
    }

    @Test fun reopenedJournalInSameProcessCanFinishKnownReceipt() = fixture { f ->
        val epoch = UUID.randomUUID().toString()
        f.journal = ProjectCopyJournal(f.root, historyLimit = 1, processEpoch = epoch)
        interruptedReceipt(f)
        f.journal = ProjectCopyJournal(f.root, historyLimit = 1, processEpoch = epoch)
        assertNotNull(f.journal.recovery(f.scope).blockingProblem)
        f.journal.finishRetirement(f.journal.inspectRetirement().token)
        assertNull(f.journal.recovery(f.scope).blockingProblem)
    }

    @Test fun anotherProcessDoesNotInferARecoveryRequestFromArchiveFiles() = fixture { f ->
        interruptedReceipt(f); f.journal = f.newJournal()
        val before = f.snapshot()
        assertNull(f.journal.recovery(f.scope).blockingProblem)
        f.refuse { f.journal.inspectRetirement() }
        assertEquals(before, f.snapshot())
    }

    @Test fun replacedReceiptBeforeInspectionIsPreservedAndRejected() = fixture { f ->
        interruptedReceipt(f); val text = f.receipt.readText()
        Files.move(f.receipt.toPath(), f.root.resolve("held-receipt").toPath()); f.receipt.writeText(text)
        val before = f.snapshot()
        f.refuse { f.journal.inspectRetirement() }
        assertEquals(before, f.snapshot()); assertNotNull(f.journal.recovery(f.scope).blockingProblem)
    }

    @Test fun replacedReceiptAfterInspectionCannotClearTheBlock() = fixture { f ->
        interruptedReceipt(f); val inspected = f.journal.inspectRetirement(); val text = f.receipt.readText()
        Files.move(f.receipt.toPath(), f.root.resolve("held-receipt").toPath()); f.receipt.writeText(text)
        val before = f.snapshot()
        f.refuse { f.journal.finishRetirement(inspected.token) }
        assertEquals(before, f.snapshot()); assertNotNull(f.journal.recovery(f.scope).blockingProblem)
    }

    @Test fun corruptReceiptIsNeverRewritten() = fixture { f ->
        interruptedReceipt(f); f.receipt.writeText("external incomplete receipt")
        val before = f.snapshot(); f.refuse { f.journal.inspectRetirement() }
        assertEquals(before, f.snapshot())
    }

    @Test fun missingReceiptDoesNotBecomePermissionToStartOver() = fixture { f ->
        interruptedReceipt(f); Files.move(f.receipt.toPath(), f.root.resolve("held-receipt").toPath())
        val before = f.snapshot(); f.refuse { f.journal.inspectRetirement() }
        assertEquals(before, f.snapshot()); assertNotNull(f.journal.recovery(f.scope).blockingProblem)
    }

    @Test fun newlyAppearedPendingIntentCannotBeIgnored() = fixture { f ->
        interruptedReceipt(f); f.pending.writeText(f.receipt.readText())
        val before = f.snapshot(); f.refuse { f.journal.inspectRetirement() }
        assertEquals(before, f.snapshot()); assertNotNull(f.journal.recovery(f.scope).blockingProblem)
    }

    @Test fun competingActiveRecordPreventsReceiptCompletion() = fixture { f ->
        interruptedReceipt(f); f.activeRecord.mkdir(); f.activeRecord.resolve("foreign").writeText("CLI")
        val before = f.snapshot(); f.refuse { f.journal.inspectRetirement() }
        assertEquals(before, f.snapshot())
    }

    @Test fun replacedArchiveDirectoryCannotSupplyAReceipt() = fixture { f ->
        interruptedReceipt(f); val old = f.root.resolve("held-archive"); Files.move(f.archive.toPath(), old.toPath()); old.copyRecursively(f.archive)
        val before = f.snapshot(); f.refuse { f.journal.inspectRetirement() }
        assertEquals(before, f.snapshot())
    }

    @Test fun receiptSymlinkCannotSelectOutsideEvidence() = fixture { f ->
        interruptedReceipt(f); val held = f.root.resolve("held-receipt"); Files.move(f.receipt.toPath(), held.toPath())
        Files.createSymbolicLink(f.receipt.toPath(), held.toPath())
        val before = f.snapshot(); f.refuse { f.journal.inspectRetirement() }
        assertTrue(Files.isSymbolicLink(f.receipt.toPath())); assertEquals(before, f.snapshot())
    }

    @Test fun recordChangedThroughOpenDescriptorIsNotAccepted() = fixture { f ->
        interruptedReceipt(f); val inspected = f.journal.inspectRetirement()
        FileChannel.open(f.archiveRecord.resolve("receipt.json").toPath(), WRITE).use {
            it.truncate(0); it.write(ByteBuffer.wrap("CLI evidence".toByteArray())); it.force(true)
        }
        val before = f.snapshot(); f.refuse { f.journal.finishRetirement(inspected.token) }
        assertEquals(before, f.snapshot()); assertNotNull(f.journal.recovery(f.scope).blockingProblem)
    }

    @Test fun closedOwnerCannotCompleteAnInspectedReceipt() = fixture { f ->
        interruptedReceipt(f); val inspected = f.journal.inspectRetirement(); val before = f.snapshot()
        assertTrue(f.refuse { f.journal.finishRetirement(inspected.token) { throw CancellationException("closed project") } } is CancellationException)
        assertEquals(before, f.snapshot()); assertNotNull(f.journal.recovery(f.scope).blockingProblem)
    }

    @Test fun forcePhaseFailureCanBeInspectedAgainWithoutMovingOrDeletingFiles() = fixture { f ->
        interruptedReceipt(f); val before = f.snapshot()
        val inspected = f.journal.inspectRetirement()
        f.journal.retirementCheckpoint = { if (it == "recovery-receipt-durable") throw IOException("receipt force phase interrupted") }
        f.refuse { f.journal.finishRetirement(inspected.token) }; f.journal.retirementCheckpoint = null
        assertNotNull(f.journal.recovery(f.scope).blockingProblem); assertEquals(before, f.snapshot())
        f.journal.finishRetirement(f.journal.inspectRetirement().token)
        assertNull(f.journal.recovery(f.scope).blockingProblem); assertEquals(before, f.snapshot())
    }

    @Test fun changingReceiptAfterForceCannotClearFailureState() = fixture { f ->
        interruptedReceipt(f); val inspected = f.journal.inspectRetirement()
        f.journal.retirementCheckpoint = { if (it == "recovery-receipt-durable") f.receipt.writeText("concurrent CLI content") }
        f.refuse { f.journal.finishRetirement(inspected.token) }; f.journal.retirementCheckpoint = null
        assertEquals("concurrent CLI content", f.receipt.readText()); assertNotNull(f.journal.recovery(f.scope).blockingProblem)
    }

    @Test fun expiredReceiptConfirmationRequiresFreshInspection() = fixture { f ->
        interruptedReceipt(f); val inspected = f.journal.inspectRetirement(); f.elapsed = inspected.expiresAt + 1
        val before = f.snapshot(); f.refuse { f.journal.finishRetirement(inspected.token) }
        assertEquals(before, f.snapshot()); assertNotNull(f.journal.recovery(f.scope).blockingProblem)
        f.journal.finishRetirement(f.journal.inspectRetirement().token)
        assertNull(f.journal.recovery(f.scope).blockingProblem)
    }

    private fun interruptedReceipt(f: RetirementRecoveryFixture) {
        f.interrupt("intent-durable")
        val inspected = f.journal.inspectRetirement()
        f.journal.retirementCheckpoint = { if (it == "recovery-intent-transferred") throw IOException("lost completion return") }
        f.refuse { f.journal.finishRetirement(inspected.token) }; f.journal.retirementCheckpoint = null
        assertFalse(f.pending.exists()); assertTrue(f.receipt.isFile)
        assertNotNull(f.journal.recovery(f.scope).blockingProblem)
    }

    private fun fixture(block: (RetirementRecoveryFixture) -> Unit) {
        val root = Files.createTempDirectory("receipt-recovery-").toFile()
        try { block(RetirementRecoveryFixture(root)) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
