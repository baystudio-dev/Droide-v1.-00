package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class CanonicalJournalCapacityTest {
    private val policy = CanonicalJournalRetention.Policy(128, 1_000_000)
    private fun state(count: Int, record: String = "x", bytes: Long = 1) =
        CanonicalJournalRetention.State(policy, List(count) { CanonicalJournalRetention.Entry("$it", record, bytes) })

    @Test fun countBoundaryHasTypedBlockerAndDoesNotMutateHistory() {
        state(127).requireAdmission(1)
        for (count in listOf(128, 129)) {
            val state = state(count)
            val before = state.entries.toList()
            val failure = failure { state.requireAdmission(1) }
            assertEquals(setOf(CanonicalJournalRetention.CapacityBlocker.ENTRY_LIMIT), failure.blockers)
            assertEquals(count, failure.usage.completedEntries)
            assertEquals(before, state.entries)
            assertTrue(failure.message!!.contains("$count/128"))
        }
    }

    @Test fun byteBoundaryIncludesRequestedPeakAndRetainedAccounting() {
        val state = state(1, bytes = 40)
        state.requireAdmission(999_960)
        val failure = failure { state.requireAdmission(999_961) }
        assertEquals(setOf(CanonicalJournalRetention.CapacityBlocker.RETAINED_BYTE_BUDGET), failure.blockers)
        assertEquals(40L, failure.usage.retainedBytes)
        assertEquals(999_961L, failure.requestedPeakBytes)
    }

    @Test fun metadataBoundaryReservesFullNextRecordAndCountsUtf8Bytes() {
        val exact = state(7, record = "é".repeat(262_144), bytes = 0)
        assertEquals(3_670_016L, exact.usage.metadataBytes)
        exact.requireAdmission(0)
        val over = CanonicalJournalRetention.State(policy,
            exact.entries + CanonicalJournalRetention.Entry("extra", "x", 0))
        assertEquals(setOf(CanonicalJournalRetention.CapacityBlocker.METADATA_BUDGET), failure { over.requireAdmission(0) }.blockers)
    }

    @Test fun reportsAllExhaustedDimensionsWithoutClaimingReservation() {
        val state = state(128, record = "x".repeat(32_768), bytes = 7_812)
        val failure = failure { state.requireAdmission(1_000_000) }
        assertEquals(CanonicalJournalRetention.CapacityBlocker.entries.toSet(), failure.blockers)
        assertTrue(failure.message!!.contains("No retained files were released"))
        assertEquals(state.usage, failure.usage)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativePeakIsRejected() { state(0).requireAdmission(-1) }

    @Test(expected = IllegalArgumentException::class)
    fun entryCapCannotBeRaisedToMirrorOwnerLimit() { CanonicalJournalRetention.Policy(256, 1_000_000) }

    @Test(expected = ArithmeticException::class)
    fun retainedAccountingOverflowFailsClosed() { state(2, bytes = Long.MAX_VALUE) }

    @Test fun changedUsageInvalidatesRecoveryObservationEquality() {
        val before = CanonicalFileOperationJournal.RecoveryMetadata(null, emptyList(), state(0).usage)
        val changed = before.copy(retentionUsage = state(1).usage)
        assertNotEquals(before, changed)
        assertEquals(before, before.copy())
    }

    private fun failure(action: () -> Unit): CanonicalJournalRetention.CapacityException {
        try { action(); fail("Expected capacity rejection") }
        catch (failure: CanonicalJournalRetention.CapacityException) { return failure }
        error("unreachable")
    }
}
