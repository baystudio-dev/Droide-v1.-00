package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class UbuntuGuestRecoveryInspectionTest {
    private fun fixture(block: (File) -> Unit) {
        val base = Files.createTempDirectory("ubuntu-recovery-inspection-").toFile()
        try { block(base) } finally { PathSecurity.deleteTreeNoFollow(base) }
    }

    @Test fun existingRootWithRetainedBackupNeedsNoBootstrapMutation() = fixture { base ->
        val previous = base.resolve("guest/.previous/ubuntu").apply { mkdirs() }
        val current = base.resolve("guest/ubuntu").apply { mkdirs() }
        val stage = base.resolve("guest/.staging/ubuntu")
        val data = previous.resolve("user-data").apply { writeText("saved user packages") }
        repeat(100) {
            val state = UbuntuGuestRecoveryInspection.bootstrap(previous, stage, current)
            assertTrue(state.finalExists); assertTrue(state.previousExists); assertFalse(state.stageExists)
        }
        assertEquals("saved user packages", data.readText())
        stage.mkdirs()
        assertTrue(UbuntuGuestRecoveryInspection.bootstrap(previous, stage, current).stageExists)
        current.delete()
        assertFalse(UbuntuGuestRecoveryInspection.bootstrap(previous, stage, current).finalExists)
    }

    @Test fun absentLegacyTransactionDoesNotStartAnyRecoveryWork() = fixture { base -> runBlocking {
        val launcher = base.resolve("launch").apply { writeText("launcher") }
        val transaction = base.resolve("rootfs/var/cache/legacy-compaction")
        var recoveries = 0
        repeat(100) { UbuntuGuestRecoveryInspection.recoverLegacyCompactionIfPresent(launcher, transaction) { recoveries++ } }
        assertEquals(0, recoveries)
        transaction.mkdirs()
        UbuntuGuestRecoveryInspection.recoverLegacyCompactionIfPresent(launcher, transaction) { recoveries++ }
        assertEquals(1, recoveries)
        assertTrue(transaction.isDirectory)
    } }

    @Test fun unsafeTransactionsFailBeforeRecoveryCallback() = fixture { base -> runBlocking {
        val launcher = base.resolve("launch").apply { writeText("launcher") }
        val outside = base.resolve("saved").apply { mkdirs() }
        val transaction = base.resolve("transaction")
        Files.createSymbolicLink(transaction.toPath(), outside.toPath())
        var recoveries = 0
        try {
            UbuntuGuestRecoveryInspection.recoverLegacyCompactionIfPresent(launcher, transaction) { recoveries++ }
            fail("Symlinked transaction was accepted")
        } catch (_: IllegalStateException) { }
        assertEquals(0, recoveries); assertTrue(outside.isDirectory)
    } }

    @Test fun inspectionErrorsAreNotReportedAsAHealthyAbsentTransaction() = fixture { base ->
        val invalidParent = base.resolve("not-a-directory").apply { writeText("keep") }
        try {
            UbuntuGuestRecoveryInspection.bootstrap(base.resolve("previous"), base.resolve("stage"), invalidParent.resolve("current"))
            fail("Not-directory IO error was swallowed as absence")
        } catch (_: java.io.IOException) { }
        assertEquals("keep", invalidParent.readText())
    }

    @Test fun cancellationDuringRealRecoveryPropagatesWithoutRemovingItsJournal() = fixture { base -> runBlocking {
        val launcher = base.resolve("launch").apply { writeText("launcher") }
        val transaction = base.resolve("transaction").apply { mkdirs() }
        try {
            UbuntuGuestRecoveryInspection.recoverLegacyCompactionIfPresent(launcher, transaction) { throw CancellationException("stop recovery") }
            fail("Recovery cancellation was swallowed")
        } catch (expected: CancellationException) { assertEquals("stop recovery", expected.message) }
        assertTrue(transaction.isDirectory)
    } }
}
