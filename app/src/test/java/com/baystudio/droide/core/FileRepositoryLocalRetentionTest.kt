package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Fake-participant seam tests only: no SAF provider, durability or native execution claims. */
class FileRepositoryLocalRetentionTest {
    private lateinit var root: File
    @Before fun setup() { root = Files.createTempDirectory("repo-local-retention-").toFile() }
    @After fun cleanup() { PathSecurity.deleteTreeNoFollow(root) }

    private class Probe : WorkspaceMutationParticipant {
        lateinit var session: Session
        var retention: () -> Boolean = { false }
        var onAttempt: (WorkspaceLocalPreparation) -> Unit = {}
        var onSync: suspend () -> Unit = {}
        var custody: (WorkspaceLocalReceipt) -> Boolean = { false }
        var custodyCalls = 0
        var finishCalls = 0
        var receipt: WorkspaceLocalReceipt? = null
        var outcome: WorkspaceMutationOutcome? = null
        override suspend fun begin(guard: WorkspaceMutationGuard, mutation: FileRepository.Mutation): WorkspaceMutationSession =
            Session(guard, mutation).also { session = it }
        inner class Session(guard: WorkspaceMutationGuard, mutation: FileRepository.Mutation) : WorkspaceMutationSession(guard, mutation) {
            override val retainLocalOnSyncFailure: Boolean get() = retention()
            override fun beforeLocalAttempt(preparation: WorkspaceLocalPreparation) = onAttempt(preparation)
            override fun localReturned(receipt: WorkspaceLocalReceipt) { this@Probe.receipt = receipt }
            override suspend fun synchronize() = onSync()
            override fun retainsDeleteStage(receipt: WorkspaceLocalReceipt): Boolean {
                custodyCalls++
                return custody(receipt)
            }
            override fun finish(outcome: WorkspaceMutationOutcome) { finishCalls++; this@Probe.outcome = outcome }
        }
    }

    private fun repository(
        participant: WorkspaceMutationParticipant? = null,
        after: (suspend (FileRepository.Mutation) -> Unit)? = null,
        mover: WorkspacePathMove = WorkspacePathMove { from, to -> Files.move(from.toPath(), to.toPath()); Unit },
    ) = FileRepository(root, onMutation = after, newFilePublisher = NewProjectFilePublisher.platform,
        pathMover = mover, participant = participant)

    private suspend fun failure(action: suspend () -> Unit): Throwable {
        try { action() } catch (failure: Throwable) { return failure }
        throw AssertionError("Expected failure")
    }

    private fun stage(): File = root.listFiles()!!.single { it.name.startsWith(".droide-tmp-") }

    @Test fun durableLocalAttemptRetainsDeleteStageOnSyncCancellationBeforeProviderAttempt() = runBlocking {
        root.resolve("original.txt").writeText("delete sentinel")
        val cancelled = CancellationException("verification cancelled")
        var attempting = false
        var afterCalls = 0
        val probe = Probe().apply {
            onAttempt = { attempting = true }
            retention = { attempting }
            onSync = { throw cancelled }
        }
        val actual = failure { repository(probe, after = { afterCalls++ }).delete("original.txt") }
        assertOriginatingFailure(cancelled, actual)
        assertFalse(root.resolve("original.txt").exists())
        assertEquals("delete sentinel", stage().readText())
        assertFalse(probe.session.externalEffectAttempted)
        assertSame(probe.receipt, probe.outcome!!.lastLocalReceipt)
        assertSame(cancelled, probe.outcome!!.failure)
        assertEquals(WorkspaceLocalEffect.DELETE_STAGE, probe.receipt!!.preparation.effect)
        assertTrue(probe.outcome!!.localAttempted)
        assertEquals(0, probe.custodyCalls)
        assertEquals(0, afterCalls)
        assertEquals(1, probe.finishCalls)
    }

    @Test fun durableLocalAttemptRetainsMovedOriginalOnSyncCancellationBeforeProviderAttempt() = runBlocking {
        root.resolve("from.txt").writeText("move sentinel")
        val cancelled = CancellationException("verification cancelled")
        var attempting = false
        val probe = Probe().apply {
            onAttempt = { attempting = true }
            retention = { attempting }
            onSync = { throw cancelled }
        }
        assertOriginatingFailure(cancelled, failure { repository(probe).move("from.txt", "to.txt") })
        assertFalse(root.resolve("from.txt").exists())
        assertEquals("move sentinel", root.resolve("to.txt").readText())
        assertFalse(probe.outcome!!.externalEffectAttempted)
        assertSame(probe.receipt, probe.outcome!!.lastLocalReceipt)
        assertSame(cancelled, probe.outcome!!.failure)
        assertEquals(1, probe.finishCalls)
    }

    @Test fun exactDeleteReceiptCustodyAfterSyncAndObserverSkipsRecursiveCleanup() = runBlocking {
        root.resolve("folder").mkdir()
        root.resolve("folder/child.txt").writeText("retained child")
        val events = mutableListOf<String>()
        val probe = Probe().apply {
            onSync = { events += "sync"; stage().resolve("child.txt").writeText("newer retained child") }
            custody = { saved ->
                events += "custody"
                assertSame(receipt, saved)
                assertEquals(WorkspaceLocalEffect.DELETE_STAGE, saved.preparation.effect)
                assertEquals("folder", saved.preparation.sourcePath)
                assertEquals(saved.preparation.sourceIdentity, saved.destinationIdentity)
                assertTrue(saved.returned && saved.successful)
                true
            }
        }
        assertTrue(repository(probe, after = { events += "observer" }).delete("folder"))
        assertEquals(listOf("sync", "observer", "custody"), events)
        assertEquals("newer retained child", stage().resolve("child.txt").readText())
        assertSame(probe.receipt, probe.outcome!!.lastLocalReceipt)
        assertNull(probe.outcome!!.failure)
        assertEquals(1, probe.custodyCalls)
        assertEquals(1, probe.finishCalls)
    }

    @Test fun custodyRefusesEqualButForeignReceiptAndRetainsStage() = runBlocking {
        root.resolve("original.txt").writeText("foreign receipt sentinel")
        val mismatch = IllegalStateException("persisted receipt belongs to another invocation")
        lateinit var foreign: WorkspaceLocalReceipt
        val probe = Probe().apply {
            onSync = { foreign = receipt!!.copy() }
            custody = { saved ->
                assertEquals(foreign, saved)
                if (saved !== foreign) throw mismatch
                true
            }
        }
        assertOriginatingFailure(mismatch, failure { repository(probe).delete("original.txt") })
        assertEquals("foreign receipt sentinel", stage().readText())
        assertFalse(root.resolve("original.txt").exists())
        assertSame(mismatch, probe.outcome!!.failure)
        assertSame(probe.receipt, probe.outcome!!.lastLocalReceipt)
    }

    @Test fun custodyFailureRetainsStageWithoutRollbackAndKeepsFailureIdentity() = runBlocking {
        root.resolve("original.txt").writeText("custody sentinel")
        val rejected = IOException("stage inventory is unverified")
        val probe = Probe().apply { custody = { throw rejected } }
        assertOriginatingFailure(rejected, failure { repository(probe).delete("original.txt") })
        assertEquals("custody sentinel", stage().readText())
        assertFalse(root.resolve("original.txt").exists())
        assertSame(rejected, probe.outcome!!.failure)
        assertEquals(1, probe.custodyCalls)
        assertEquals(1, probe.finishCalls)
    }

    @Test fun custodyCancellationRetainsStageAndOriginalCancellationIdentity() = runBlocking {
        root.resolve("original.txt").writeText("cancel custody sentinel")
        val cancelled = CancellationException("custody cancelled")
        val probe = Probe().apply { custody = { throw cancelled } }
        assertOriginatingFailure(cancelled, failure { repository(probe).delete("original.txt") })
        assertEquals("cancel custody sentinel", stage().readText())
        assertSame(cancelled, probe.outcome!!.failure)
        assertSame(probe.receipt, probe.outcome!!.lastLocalReceipt)
    }

    @Test fun throwingRetentionDispositionNeverRollsBackOrReplacesSyncCancellation() = runBlocking {
        for (delete in listOf(false, true)) {
            val source = if (delete) "delete.txt" else "move.txt"
            root.resolve(source).writeText("retention sentinel")
            val cancelled = CancellationException("sync cancelled")
            val broken = IOException("retention unavailable")
            val probe = Probe().apply { retention = { throw broken }; onSync = { throw cancelled } }
            val actual = failure {
                if (delete) repository(probe).delete(source) else repository(probe).move(source, "moved.txt")
            }
            assertOriginatingFailure(cancelled, actual)
            assertTrue(cancelled.suppressed.any { it === broken })
            assertFalse(root.resolve(source).exists())
            assertEquals("retention sentinel", (if (delete) stage() else root.resolve("moved.txt")).readText())
            assertSame(cancelled, probe.outcome!!.failure)
            assertSame(probe.receipt, probe.outcome!!.lastLocalReceipt)
        }
    }

    @Test fun sameCancellationFromDispositionDoesNotCauseSelfSuppression() = runBlocking {
        root.resolve("from.txt").writeText("same cancellation sentinel")
        val cancelled = CancellationException("same failure")
        val probe = Probe().apply { retention = { throw cancelled }; onSync = { throw cancelled } }
        assertOriginatingFailure(cancelled, failure { repository(probe).move("from.txt", "to.txt") })
        assertEquals("same cancellation sentinel", root.resolve("to.txt").readText())
        assertFalse(root.resolve("from.txt").exists())
        assertFalse(cancelled.suppressed.any { it === cancelled })
    }

    @Test fun observerCancellationAfterSyncUsesRetentionAndKeepsSavedReceipt() = runBlocking {
        root.resolve("original.txt").writeText("observer sentinel")
        val cancelled = CancellationException("observer cancelled")
        var syncCalls = 0
        var afterCalls = 0
        val probe = Probe().apply { retention = { true }; onSync = { syncCalls++ } }
        val repo = repository(probe, after = { afterCalls++; throw cancelled })
        assertOriginatingFailure(cancelled, failure { repo.delete("original.txt") })
        assertEquals("observer sentinel", stage().readText())
        assertSame(cancelled, probe.outcome!!.failure)
        assertSame(probe.receipt, probe.outcome!!.lastLocalReceipt)
        assertEquals(1, syncCalls)
        assertEquals(1, afterCalls)
        assertEquals(0, probe.custodyCalls)
    }

    @Test fun defaultSessionStillCleansSuccessfulDeleteStage() = runBlocking {
        root.resolve("folder").mkdir()
        root.resolve("folder/child.txt").writeText("ordinary child")
        val participant = WorkspaceMutationParticipant { guard, mutation ->
            object : WorkspaceMutationSession(guard, mutation) {}
        }
        assertTrue(repository(participant).delete("folder"))
        assertTrue(root.list()!!.isEmpty())
    }

    @Test fun explicitFalseCustodyKeepsOrdinaryCleanup() = runBlocking {
        root.resolve("original.txt").writeText("ordinary sentinel")
        val probe = Probe()
        assertTrue(repository(probe).delete("original.txt"))
        assertTrue(root.list()!!.isEmpty())
        assertEquals(1, probe.custodyCalls)
    }

    @Test fun noParticipantKeepsDirectAndCallbackDeleteCleanup() = runBlocking {
        for (callback in listOf(false, true)) {
            root.resolve("folder").mkdir()
            root.resolve("folder/child.txt").writeText("ordinary child")
            val after: (suspend (FileRepository.Mutation) -> Unit)? = if (callback) ({ _ -> }) else null
            assertTrue(repository(after = after).delete("folder"))
            assertTrue(root.list()!!.isEmpty())
        }
    }

    @Test fun defaultRetentionStillAllowsUnchangedFileRollbackWithoutReplay() = runBlocking {
        for (delete in listOf(false, true)) {
            val source = if (delete) "delete.txt" else "move.txt"
            root.resolve(source).writeText("rollback sentinel")
            val rejected = IOException("no durable local attempt")
            var syncCalls = 0
            val probe = Probe().apply { onSync = { syncCalls++; throw rejected } }
            val actual = failure {
                if (delete) repository(probe).delete(source) else repository(probe).move(source, "moved.txt")
            }
            assertOriginatingFailure(rejected, actual)
            assertEquals("rollback sentinel", root.resolve(source).readText())
            assertFalse(root.resolve("moved.txt").exists())
            assertTrue(root.listFiles()!!.none { it.name.startsWith(".droide-tmp-") })
            assertEquals(1, syncCalls)
            assertEquals(0, probe.custodyCalls)
        }
    }

    @Test fun changedStageIdentityCannotReachSynchronizationOrCustody() = runBlocking {
        root.resolve("original.txt").writeText("original identity sentinel")
        val retained = root.resolve("retained-original.txt")
        var syncCalls = 0
        val probe = Probe().apply { onSync = { syncCalls++ }; custody = { true } }
        val repo = repository(probe, mover = WorkspacePathMove { from, to ->
            Files.move(from.toPath(), retained.toPath())
            to.writeText("foreign stage sentinel")
        })
        assertTrue(failure { repo.delete("original.txt") } is IllegalStateException)
        assertEquals("original identity sentinel", retained.readText())
        assertEquals("foreign stage sentinel", stage().readText())
        assertEquals(0, syncCalls)
        assertEquals(0, probe.custodyCalls)
    }

    @Test fun throwingRetentionDispositionKeepsFatalFailurePrimary() = runBlocking {
        root.resolve("from.txt").writeText("fatal sentinel")
        val fatal = AssertionError("fatal sync failure")
        val broken = IOException("retention unavailable")
        val probe = Probe().apply { retention = { throw broken }; onSync = { throw fatal } }
        assertOriginatingFailure(fatal, failure { repository(probe).move("from.txt", "to.txt") })
        assertSame(fatal, probe.outcome!!.failure)
        assertTrue(fatal.suppressed.any { it === broken })
        assertFalse(root.resolve("from.txt").exists())
        assertEquals("fatal sentinel", root.resolve("to.txt").readText())
    }

    @Test fun exchangedRootCannotReachCustodyOrCleanup() = runBlocking {
        root.resolve("original.txt").writeText("original root sentinel")
        val originalRoot = File(root.parentFile, root.name + "-retained")
        val probe = Probe().apply {
            onSync = {
                Files.move(root.toPath(), originalRoot.toPath())
                assertTrue(root.mkdir())
                root.resolve("newer.txt").writeText("new root sentinel")
            }
            custody = { true }
        }
        try {
            assertTrue(failure { repository(probe).delete("original.txt") } is IllegalStateException)
            assertEquals("original root sentinel", originalRoot.listFiles()!!.single().readText())
            assertEquals("new root sentinel", root.resolve("newer.txt").readText())
            assertEquals(0, probe.custodyCalls)
            assertEquals(1, probe.finishCalls)
        } finally { PathSecurity.deleteTreeNoFollow(originalRoot) }
    }
}
