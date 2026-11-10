package com.baystudio.droide.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LongRunningOperationJournalTest {
    @Test fun completingAfterClockRollbackPreservesTheCommittedState() = withJournal { root ->
        var now = 2_000_000L
        val journal = LongRunningOperationJournal(root, "packages", 111, "live") { now }
        val lease = journal.begin(LongRunningOperationJournal.Kind.PACKAGE, "runtime.python")
        now = 1_000_000L
        lease.complete("Package transaction committed")
        val record = journal.list().single()
        assertEquals(LongRunningOperationJournal.State.COMPLETED, record.state)
        assertEquals("Package transaction committed", record.detail)
        assertEquals(2_000_000L, record.updatedAtEpochMs)
    }

    @Test fun failureAfterClockRollbackDoesNotHideTheOriginalOutcome() = withJournal { root ->
        var now = 2_000_000L
        val journal = LongRunningOperationJournal(root, "packages", 111, "live") { now }
        val lease = journal.begin(LongRunningOperationJournal.Kind.PACKAGE, "runtime.python")
        now = 1_000_000L
        lease.fail("Download failed")
        assertEquals(LongRunningOperationJournal.State.FAILED, journal.list().single().state)
        assertEquals("Download failed", journal.list().single().detail)
    }

    @Test fun cancellationAfterClockRollbackRemainsFinalAcrossRestart() = withJournal { root ->
        var now = 2_000_000L
        val journal = LongRunningOperationJournal(root, "builds", 111, "live") { now }
        val lease = journal.begin(LongRunningOperationJournal.Kind.BUILD, "Gradle assembleDebug")
        now = 1_000_000L
        lease.cancel("Canceled by user")
        val next = LongRunningOperationJournal(root, "builds", 222, "replacement") { now }
        assertTrue(next.reconcileInterrupted().isEmpty())
        assertEquals(LongRunningOperationJournal.State.CANCELED, next.list().single().state)
    }

    @Test fun correctingFutureDatedInterruptedRecordDoesNotBlockStartup() = withJournal { root ->
        var now = 2_000_000L
        LongRunningOperationJournal(root, "packages", 111, "previous") { now }
            .begin(LongRunningOperationJournal.Kind.PACKAGE, "runtime.python")
        now = 1_000_000L
        val next = LongRunningOperationJournal(root, "packages", 111, "replacement") { now }
        val interrupted = next.reconcileInterrupted().single()
        assertEquals(LongRunningOperationJournal.State.INTERRUPTED, interrupted.state)
        assertEquals(2_000_000L, interrupted.updatedAtEpochMs)
        assertTrue(next.reconcileInterrupted().isEmpty())
        next.begin(LongRunningOperationJournal.Kind.PACKAGE, "runtime.node").complete()
        assertEquals(2, next.list().size)
    }

    @Test fun clockMovingForwardStillRecordsTheActualTransitionTime() = withJournal { root ->
        var now = 1_000_000L
        val journal = LongRunningOperationJournal(root, "builds", 111, "live") { now }
        val lease = journal.begin(LongRunningOperationJournal.Kind.BUILD, "Gradle assembleDebug")
        now += 31L * 24L * 60L * 60L * 1_000L
        lease.complete()
        assertEquals(now, journal.list().single().updatedAtEpochMs)
    }

    @Test fun repeatedOperationsBoundFinishedHistoryWithoutPruningLiveWork() = withJournal { root ->
        var now = 1_000_000L
        val journal = LongRunningOperationJournal(root, "builds", 111, "live") { ++now }
        val active = journal.begin(LongRunningOperationJournal.Kind.BUILD, "Long user build")
        repeat(500) { journal.begin(LongRunningOperationJournal.Kind.PACKAGE, "Package $it").complete() }
        val records = journal.list()
        assertEquals(48, records.count { it.state == LongRunningOperationJournal.State.COMPLETED })
        assertEquals(active.id, records.single { it.state == LongRunningOperationJournal.State.RUNNING }.id)
        assertEquals(49, java.io.File(root, "builds").listFiles()!!.size)
        active.complete()
        assertEquals(48, journal.list().size)
    }

    @Test fun repeatedProcessDeathAndClockCorrectionsLeaveNoStuckRunningRecords() = withJournal { root ->
        var now = 2_000_000L
        repeat(100) { cycle ->
            val previous = LongRunningOperationJournal(root, "packages", 111, "process-$cycle") { now }
            previous.begin(LongRunningOperationJournal.Kind.PACKAGE, "Package $cycle")
            now -= 1_000L
            val replacement = LongRunningOperationJournal(root, "packages", 111, "replacement-$cycle") { now }
            assertEquals(1, replacement.reconcileInterrupted().size)
            assertTrue(replacement.list().none { it.state == LongRunningOperationJournal.State.RUNNING })
        }
        val final = LongRunningOperationJournal(root, "packages", 111, "final") { now }
        assertEquals(48, final.list().size)
        assertTrue(java.io.File(root, "packages").listFiles()!!.none { it.extension == "tmp" })
    }

    private fun withJournal(block: (java.io.File) -> Unit) {
        val root = Files.createTempDirectory("droide-op-longevity-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun processDeathMarksOnlyForeignRunningRecordsInterrupted() {
        val root = Files.createTempDirectory("droide-op-journal").toFile()
        try {
            var now = 100L
            val first = LongRunningOperationJournal(root, "workspace", 111, "process-a") { ++now }
            first.begin(LongRunningOperationJournal.Kind.BUILD, "Gradle assembleDebug")
            val second = LongRunningOperationJournal(root, "workspace", 222, "process-b") { ++now }
            val interrupted = second.reconcileInterrupted()
            assertEquals(1, interrupted.size)
            assertEquals(LongRunningOperationJournal.State.INTERRUPTED, interrupted.single().state)
            assertTrue(second.list().none { it.state == LongRunningOperationJournal.State.RUNNING })
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun pidReuseStillInterruptsRecordFromPreviousProcessIdentity() {
        val root = Files.createTempDirectory("droide-op-journal-pid-reuse").toFile()
        try {
            var now = 300L
            LongRunningOperationJournal(root, "workspace", 111, "process-old") { ++now }
                .begin(LongRunningOperationJournal.Kind.BUILD, "Gradle assembleDebug")
            val replacement = LongRunningOperationJournal(root, "workspace", 111, "process-new") { ++now }
            val interrupted = replacement.reconcileInterrupted()
            assertEquals(1, interrupted.size)
            assertEquals("process-old", interrupted.single().ownerProcessIdentity)
            assertEquals(LongRunningOperationJournal.State.INTERRUPTED, replacement.list().single().state)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun sameProcessIdentitySurvivesActivityRecreation() {
        val root = Files.createTempDirectory("droide-op-journal-activity").toFile()
        try {
            var now = 400L
            LongRunningOperationJournal(root, "workspace", 111, "process-live") { ++now }
                .begin(LongRunningOperationJournal.Kind.BUILD, "Gradle assembleDebug")
            val recreated = LongRunningOperationJournal(root, "workspace", 111, "process-live") { ++now }
            assertTrue(recreated.reconcileInterrupted().isEmpty())
            assertEquals(LongRunningOperationJournal.State.RUNNING, recreated.list().single().state)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun completedOperationIsNeverRewrittenAsInterrupted() {
        val root = Files.createTempDirectory("droide-op-journal").toFile()
        try {
            var now = 200L
            val first = LongRunningOperationJournal(root, "packages", 111, "process-a") { ++now }
            val lease = first.begin(LongRunningOperationJournal.Kind.PACKAGE, "runtime.python@3.13")
            lease.complete("committed")
            val second = LongRunningOperationJournal(root, "packages", 222, "process-b") { ++now }
            assertTrue(second.reconcileInterrupted().isEmpty())
            assertEquals(LongRunningOperationJournal.State.COMPLETED, second.list().single().state)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun multilineSdkFailureSummaryDoesNotMaskItsCauseOrLeaveRunningRecord() = withJournal { root ->
        val journal = LongRunningOperationJournal(root, "packages", 111, "live")
        val lease = journal.begin(LongRunningOperationJournal.Kind.PACKAGE, "sdk.android@latest")
        val original = "Android SDK Manager update failed (exit 1). \nAndroid SDK command-line tools are missing\r\n"
        lease.fail(LongRunningOperationJournal.summary(original))
        val record = journal.list().single()
        assertEquals(LongRunningOperationJournal.State.FAILED, record.state)
        assertEquals("Android SDK Manager update failed (exit 1). Android SDK command-line tools are missing", record.detail)
        assertTrue(record.detail != "Failed requirement.")
    }

    @Test fun operationSummaryBoundsLongProviderOutputAndRemovesRecordControls() = withJournal { root ->
        val journal = LongRunningOperationJournal(root, "packages", 111, "live")
        val lease = journal.begin(LongRunningOperationJournal.Kind.PACKAGE, "sdk.android@36")
        val summary = LongRunningOperationJournal.summary("\u0000\r\n\t Error=" + "x".repeat(900))
        assertEquals(400, summary.length)
        assertTrue(summary.startsWith("Error="))
        lease.fail(summary)
        assertEquals(summary, journal.list().single().detail)
    }

    @Test fun successfulProviderOutputAlsoAcceptsABoundedSummary() = withJournal { root ->
        val journal = LongRunningOperationJournal(root, "packages", 111, "live")
        val lease = journal.begin(LongRunningOperationJournal.Kind.PACKAGE, "sdk.android@36")
        lease.complete(LongRunningOperationJournal.summary("Installed SDK 36\nHealth check passed"))
        assertEquals(LongRunningOperationJournal.State.COMPLETED, journal.list().single().state)
        assertEquals("Installed SDK 36 Health check passed", journal.list().single().detail)
    }

}
