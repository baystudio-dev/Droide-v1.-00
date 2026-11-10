package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class MirrorProcessCleanupReceiptFilesTest {
    private class Interrupted : RuntimeException()
    private fun owner(boot: WorkspaceProcessBootIdentity.Identity? = null) =
        MirrorProcessCustodyLedger.Owner(UUID.randomUUID().toString(), UUID.randomUUID().toString(), boot)
    private fun fixture(action: (File) -> Unit) {
        val root = Files.createTempDirectory("cleanup-files-").toFile()
        root.resolve("identity").writeText("disposable authority nonce ${UUID.randomUUID()}")
        try { action(root) } finally { root.deleteRecursively() }
    }
    private fun proof(root: File, owner: MirrorProcessCustodyLedger.Owner) = root.resolve("process-cleanup-receipts/${owner.id}.cleanup")

    @Test fun exactUnknownBootProofReopensAndRetiresOnlyAfterOwnerAbsence() = fixture { root ->
        val owner = owner(); val store = MirrorProcessCleanupReceipts(root, "binding")
        store.requireCapacity(emptyList()); store.record(owner, listOf(owner))
        assertTrue(MirrorProcessCleanupReceipts(root, "binding").remaining(listOf(owner)).isEmpty())
        store.retireCompleted(listOf(owner)); assertTrue(proof(root, owner).isFile)
        store.retireCompleted(emptyList()); assertFalse(proof(root, owner).exists())
        store.retireCompleted(emptyList())
    }
    @Test fun moreThan256SequentialCompletionsDoNotExhaustCapacity() = fixture { root ->
        val store = MirrorProcessCleanupReceipts(root, "binding")
        repeat(260) {
            store.retireCompleted(emptyList()); store.requireCapacity(emptyList())
            val owner = owner(); store.record(owner, listOf(owner))
            assertEquals(1, root.resolve("process-cleanup-receipts").listFiles()!!.size)
        }
    }
    @Test fun outstandingReservationsAreCountedButCanStillComplete() = fixture { root ->
        val store = MirrorProcessCleanupReceipts(root, "binding")
        val pending = List(256) { owner() }
        assertNotNull(runCatching { store.requireCapacity(pending) }.exceptionOrNull())
        store.record(pending.first(), pending)
        assertEquals(pending.drop(1), store.remaining(pending))
        store.retireCompleted(pending); assertTrue(proof(root, pending.first()).isFile)
    }
    @Test fun wrongBindingAndBootCannotReplayOrDeleteProof() = fixture { root ->
        val boot = WorkspaceProcessBootIdentity.Identity("a".repeat(64), UUID.randomUUID().toString())
        val owner = owner(boot); val store = MirrorProcessCleanupReceipts(root, "binding")
        store.record(owner, listOf(owner)); val bytes = proof(root, owner).readBytes()
        assertNotNull(runCatching { MirrorProcessCleanupReceipts(root, "other").remaining(listOf(owner)) }.exceptionOrNull())
        assertNotNull(runCatching { MirrorProcessCleanupReceipts(root, "other").retireCompleted(emptyList()) }.exceptionOrNull())
        assertNotNull(runCatching { store.remaining(listOf(owner.copy(boot = null))) }.exceptionOrNull())
        assertNotNull(runCatching { store.remaining(listOf(owner.copy(runtimeId = UUID.randomUUID().toString()))) }.exceptionOrNull())
        assertArrayEquals(bytes, proof(root, owner).readBytes())
    }
    @Test fun tornProofCannotReplayRetryOrRetire() = fixture { root ->
        val owner = owner(); val store = MirrorProcessCleanupReceipts(root, "binding")
        store.record(owner, listOf(owner)); proof(root, owner).writeText("torn")
        assertNotNull(runCatching { store.remaining(listOf(owner)) }.exceptionOrNull())
        assertNotNull(runCatching { store.record(owner, listOf(owner)) }.exceptionOrNull())
        assertNotNull(runCatching { store.retireCompleted(emptyList()) }.exceptionOrNull())
        assertEquals("torn", proof(root, owner).readText())
    }
    @Test fun interruptedForceRetainsReplayableProofAndRetryIsIdempotent() = fixture { root ->
        val owner = owner()
        for (point in listOf("writer-cleanup-receipt-before-force", "writer-cleanup-receipt-after-force")) {
            val store = MirrorProcessCleanupReceipts(root, "binding") { if (it == point) throw Interrupted() }
            assertTrue(runCatching { store.record(owner, listOf(owner)) }.exceptionOrNull() is Interrupted)
            val reopened = MirrorProcessCleanupReceipts(root, "binding")
            reopened.record(owner, listOf(owner)); reopened.record(owner, listOf(owner))
            assertTrue(reopened.remaining(listOf(owner)).isEmpty()); reopened.retireCompleted(emptyList())
        }
    }
    @Test fun retirementInterruptionBeforeOrAfterUnlinkLeavesPendingProofUntouched() = fixture { root ->
        for (point in listOf("writer-cleanup-retirement-before-unlink", "writer-cleanup-retirement-after-unlink")) {
            val done = owner(); val pending = owner(); val store = MirrorProcessCleanupReceipts(root, "binding")
            store.record(done, listOf(done)); store.record(pending, listOf(pending))
            val bytes = proof(root, pending).readBytes()
            assertTrue(runCatching {
                MirrorProcessCleanupReceipts(root, "binding") { if (it == point) throw Interrupted() }.retireCompleted(listOf(pending))
            }.exceptionOrNull() is Interrupted)
            assertArrayEquals(bytes, proof(root, pending).readBytes())
            store.retireCompleted(listOf(pending)); assertFalse(proof(root, done).exists())
            store.retireCompleted(emptyList())
        }
    }
    @Test fun swappedProofBetweenValidationAndUnlinkIsNotDeleted() = fixture { root ->
        val owner = owner(); MirrorProcessCleanupReceipts(root, "binding").record(owner, listOf(owner))
        val store = MirrorProcessCleanupReceipts(root, "binding") { if (it == "writer-cleanup-retirement-before-unlink") {
            Files.move(proof(root, owner).toPath(), root.resolve("saved").toPath())
            proof(root, owner).writeText("replacement data")
        } }
        assertNotNull(runCatching { store.retireCompleted(emptyList()) }.exceptionOrNull())
        assertEquals("replacement data", proof(root, owner).readText()); assertTrue(root.resolve("saved").isFile)
    }
    @Test fun symlinkEvidenceCannotReplayOrRetire() = fixture { root ->
        val owner = owner(); val store = MirrorProcessCleanupReceipts(root, "binding")
        store.record(owner, listOf(owner)); val saved = root.resolve("saved")
        Files.move(proof(root, owner).toPath(), saved.toPath()); Files.createSymbolicLink(proof(root, owner).toPath(), saved.toPath())
        val bytes = saved.readBytes()
        assertNotNull(runCatching { store.remaining(listOf(owner)) }.exceptionOrNull())
        assertNotNull(runCatching { store.retireCompleted(emptyList()) }.exceptionOrNull())
        assertArrayEquals(bytes, saved.readBytes())
    }
    @Test fun unsupportedPinnedUnlinkPreservesProofAndUsesOnlyRemainingCapacity() = fixture { root ->
        val store = MirrorProcessCleanupReceipts(root, "binding", deleteMetadata = { _, _, _ -> false })
        repeat(MirrorProcessCleanupReceipts.LIMIT) {
            store.retireCompleted(emptyList()); store.requireCapacity(emptyList())
            val owner = owner(); store.record(owner, listOf(owner))
        }
        store.retireCompleted(emptyList())
        assertEquals(256, root.resolve("process-cleanup-receipts").listFiles()!!.size)
        assertNotNull(runCatching { store.requireCapacity(emptyList()) }.exceptionOrNull())
        MirrorProcessCleanupReceipts(root, "binding").retireCompleted(emptyList())
        store.requireCapacity(emptyList())
    }

    @Test fun replacedAuthorityNonceInvalidatesProof() = fixture { root ->
        val owner = owner(); val store = MirrorProcessCleanupReceipts(root, "binding")
        store.record(owner, listOf(owner)); val bytes = proof(root, owner).readBytes()
        root.resolve("identity").writeText("replacement authority nonce")
        assertNotNull(runCatching { store.remaining(listOf(owner)) }.exceptionOrNull())
        assertNotNull(runCatching { store.retireCompleted(emptyList()) }.exceptionOrNull())
        assertArrayEquals(bytes, proof(root, owner).readBytes())
    }
}
