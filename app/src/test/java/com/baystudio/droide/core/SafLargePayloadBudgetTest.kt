package com.baystudio.droide.core

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import org.junit.Assert.*
import org.junit.Test

class SafLargePayloadBudgetTest {
    @Test fun actualStreamAccountingPassesFourGiBWithoutAllocatingThoseBytes() {
        val budget = SafReadBudget(SafJournalLimits().treeBytes)
        repeat(3) { budget.consume(Int.MAX_VALUE) }
        assertEquals(3L * Int.MAX_VALUE, budget.bytes)
        val limited = SafReadBudget(100)
        limited.consume(100)
        assertThrows(IllegalStateException::class.java) { limited.consume(1) }
        assertEquals(100L, limited.bytes)
    }

    @Test fun payloadAdmissionCanExceedOldJournalLimitWhileMetadataRemainsBounded() {
        val root = Files.createTempDirectory("saf-large-payload-admission-").toFile()
        try {
            val workspace = root.resolve("project").apply { mkdirs() }
            val storage = SafOperationStorage(forceDirectory = {
                FileChannel.open(it.toPath(), StandardOpenOption.READ).use { channel -> channel.force(true) }
            })
            val directory = root.resolve("journal")
            val journal = SafOperationJournal.create(workspace, "content://r115/tree/root", directory, storage)
            val reservation = SafJournalReservation(1, 1024 * 1024, 32L * 1024 * 1024 * 1024,
                indexBytes = 8192, evidenceFiles = 1)
            val operation = journal.begin(SafOperationRequest(SafOperationKind.SYNC, listOf("artifact.bin")),
                listOf("artifact.bin"), reservation, checkActive = {})
            val recovered = SafOperationJournal.open(workspace, "content://r115/tree/root", directory, storage)
                .recover(operation.id)
            assertEquals(reservation, recovered.envelope.reservation)
            assertEquals(0L, recovered.payloadBytes)
            assertTrue(SafJournalLimits().recordBytes <= 1024 * 1024)
            // Only a reservation was made; this test does not claim a 32 GiB transfer.
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun retainedPayloadArithmeticCannotWrapOrEvadeAnExplicitBudget() {
        assertEquals(Long.MAX_VALUE, SafOperationJournal.boundedAdd(Long.MAX_VALUE - 1, 1, Long.MAX_VALUE))
        assertThrows(IllegalStateException::class.java) {
            SafOperationJournal.boundedAdd(Long.MAX_VALUE, 1, Long.MAX_VALUE)
        }
        assertThrows(IllegalStateException::class.java) { SafOperationJournal.boundedAdd(7, 4, 10) }
        assertThrows(IllegalStateException::class.java) { SafOperationJournal.boundedAdd(7, -1, 10) }
    }

    @Test fun legacyEvidenceCanBeReadWithoutTheFormerSixteenGiBPayloadCeiling() {
        assertTrue(SafLegacyLimits().payloadBytes >= 32L * 1024 * 1024 * 1024)
        assertTrue(SafLegacyLimits().metadataBytes <= 16L * 1024 * 1024)
        assertEquals(1L, SafLegacyLimits(payloadBytes = 1).payloadBytes)
    }
}
