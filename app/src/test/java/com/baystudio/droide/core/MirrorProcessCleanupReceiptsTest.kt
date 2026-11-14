package com.baystudio.droide.core

import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MirrorProcessCleanupReceiptsTest {
    @get:org.junit.Rule val admission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()
    private class Interrupted : RuntimeException()
    private val oldRuntime = UUID.randomUUID().toString()
    private val boot = WorkspaceProcessBootIdentity.Identity("a".repeat(64), "e9bc19df-5a35-4a8b-bbf9-cf98316c8423")
    private class Fixture {
        val root = Files.createTempDirectory("mirror-exact-cleanup-").toFile()
        val source = root.resolve("source").apply { mkdir(); resolve("file").writeText("original") }
        val working = root.resolve("working")
        val directory = root.resolve(".working-sync-authority")
        suspend fun prepare() { LocalAgentWorkspaceMirror.prepareRetained(source, working) }
        fun authority(boot: WorkspaceProcessBootIdentity.Identity? = null, checkpoint: (String) -> Unit = {}) =
            MirrorMetadataAuthority(directory, source, working, processCheckpoint = checkpoint, processBootIdentity = { boot })
        fun owners() = authority().pendingProcessOwners()
        fun receipt() = directory.resolve("process-cleanup-receipts").listFiles()!!.single()
    }
    private fun fixture(action: suspend Fixture.() -> Unit) = runBlocking {
        val f = Fixture()
        try { f.prepare(); f.action() } finally { assertTrue(PathSecurity.deleteTreeNoFollow(f.root)) }
    }

    @Test fun realSpawnThenThrowNeverProducesCleanupProofOrReopensRecovery() = fixture {
        var child: Process? = null
        val ready = working.resolve("factory-child-ready")
        try {
            val failure = runCatching {
                WorkspaceProcessWriteAdmission.create<HostedStdioProcess>(working, authority().processCustody(oldRuntime)) {
                    child = ProcessBuilder("/bin/sh", "-c",
                        "exec 3<>file; cd /; printf ready > '$ready'; exec sleep 30")
                        .directory(working).start()
                    error("wrapper failed after native spawn")
                }
            }.exceptionOrNull()
            assertEquals("wrapper failed after native spawn", failure?.message)
            val process = checkNotNull(child)
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
            while (!ready.isFile && process.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(ready.isFile); assertTrue(process.isAlive)
            assertEquals(1, owners().size)
            assertTrue(directory.resolve("process-cleanup-receipts").listFiles().orEmpty().isEmpty())
            assertNotNull(runCatching { authority().requireNoPendingProcessOwners() }.exceptionOrNull())
            assertNotNull(runCatching { authority().processCustody().acquire(working).also { it.withFactory({}, {}) } }.exceptionOrNull())
            assertNotNull(runCatching { ExportWriterAdmission.beginCapture(working).close() }.exceptionOrNull())
            // A root PID/cwd scan is not proof: the live child has changed cwd while
            // its inherited descriptor still names a file in this working copy.
            assertEquals("original", working.resolve("file").readText())
        } finally {
            child?.let { it.destroyForcibly(); assertTrue(it.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) }
        }
        // Test-only exact child cleanup cannot retroactively certify an unknown
        // production factory or clear its durable/memory ownership.
        assertEquals(1, owners().size)
        assertTrue(directory.resolve("process-cleanup-receipts").listFiles().orEmpty().isEmpty())
    }

    @Test fun hostedFactorySpawnThenThrowKeepsDurableCustodyThroughHandoffFailure() = fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var child: Process? = null
        try {
            val failure = runCatching {
                LocalLinuxProcessHost(working, scope).startAdmitted(
                    listOf("/bin/sh"), emptyMap(), null,
                    prepareStart = { {
                        child = ProcessBuilder("/bin/sh", "-c", "exec sleep 30").directory(working).start()
                        error("host wrapper construction failed")
                    } }, processCustody = authority().processCustody(oldRuntime), admitStart = { it() },
                )
            }.exceptionOrNull()
            assertEquals("host wrapper construction failed", failure?.message)
            assertTrue(checkNotNull(child).isAlive)
            assertEquals(1, owners().size)
            assertTrue(directory.resolve("process-cleanup-receipts").listFiles().orEmpty().isEmpty())
            assertNotNull(runCatching { authority().requireNoPendingProcessOwners() }.exceptionOrNull())
            assertNotNull(runCatching { ExportWriterAdmission.beginCapture(working).close() }.exceptionOrNull())
        } finally {
            child?.let { it.destroyForcibly(); assertTrue(it.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) }
            scope.cancel()
        }
    }

    @Test fun sameBootCompletedCleanupReplaysAfterLostLedgerCommit() = fixture {
        val authority = authority(boot) { if (it == "writer-completion-before-commit") throw Interrupted() }
        val receipt = authority.processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        assertTrue(runCatching { receipt.close() }.exceptionOrNull() is Interrupted)
        assertEquals(1, owners().size)
        val proof = receipt().readBytes()
        authority(boot).processCustody().acquire(working).also { it.withFactory({}, {}) }.use {
            assertEquals(WorkspaceProcessCustody.runtimeId, owners().single().runtimeId)
            assertArrayEquals(proof, receipt().readBytes())
        }
        assertTrue(owners().isEmpty())
        assertEquals("original", source.resolve("file").readText())
        assertEquals("original", working.resolve("file").readText())
    }

    @Test fun unknownBootExactCleanupAllowsRecoveryWithoutChangingRevision() = fixture {
        val old = authority { if (it == "writer-cleanup-receipt-after-force") throw Interrupted() }
            .processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        val state = authority().load()
        assertTrue(runCatching { old.close() }.exceptionOrNull() is Interrupted)
        assertEquals(1, owners().size)
        authority().requireNoPendingProcessOwners()
        assertTrue(owners().isEmpty()); assertEquals(state, authority().load())
        old.close() // already reconciled, never affects a replacement owner
    }

    @Test fun noExactCleanupProofStillBlocksUnknownPriorRuntime() = fixture {
        authority().processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        assertNotNull(runCatching { authority().requireNoPendingProcessOwners() }.exceptionOrNull())
        assertNotNull(runCatching { authority().processCustody().acquire(working).also { it.withFactory({}, {}) } }.exceptionOrNull())
        assertEquals(1, owners().size)
    }

    @Test fun truncatedProofIsPreservedAndCannotClearOwner() = fixture {
        val old = authority { if (it == "writer-cleanup-receipt-after-force") throw Interrupted() }
            .processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        assertNotNull(runCatching { old.close() }.exceptionOrNull())
        val proof = receipt(); proof.writeBytes(proof.readBytes().take(20).toByteArray())
        val bytes = proof.readBytes()
        assertNotNull(runCatching { authority().requireNoPendingProcessOwners() }.exceptionOrNull())
        assertNotNull(runCatching { old.close() }.exceptionOrNull())
        assertArrayEquals(bytes, proof.readBytes()); assertEquals(1, owners().size)
    }

    @Test fun proofCannotBeTransferredToAnotherAuthority() = fixture {
        val old = authority { if (it == "writer-cleanup-receipt-after-force") throw Interrupted() }
            .processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        assertNotNull(runCatching { old.close() }.exceptionOrNull())
        val owner = owners().single()
        val isolated = root.resolve("isolated").apply { mkdir() }
        directory.resolve("identity").copyTo(isolated.resolve("identity"))
        val target = isolated.resolve("process-cleanup-receipts").apply { mkdir() }
        receipt().copyTo(target.resolve(receipt().name))
        assertNotNull(runCatching { MirrorProcessCleanupReceipts(isolated, "other-binding").remaining(listOf(owner)) }.exceptionOrNull())
        assertEquals(listOf(owner), owners())
    }

    @Test fun mixedCleanedAndUnconfirmedOwnersDoNotPartiallyReconcile() = fixture {
        val old = authority { if (it == "writer-cleanup-receipt-after-force") throw Interrupted() }
            .processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        authority().processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        assertNotNull(runCatching { old.close() }.exceptionOrNull())
        val before = owners()
        assertNotNull(runCatching { authority().requireNoPendingProcessOwners() }.exceptionOrNull())
        assertNotNull(runCatching { authority().processCustody().acquire(working).also { it.withFactory({}, {}) } }.exceptionOrNull())
        assertEquals(before, owners())
    }

    @Test fun foreignCompletionEvidenceBlocksBeforeFactoryAndKeepsProofs() = fixture {
        val store = MirrorProcessCleanupReceipts(directory, "test-binding")
        repeat(MirrorProcessCleanupReceipts.LIMIT) {
            val owner = MirrorProcessCustodyLedger.Owner(UUID.randomUUID().toString(), oldRuntime)
            store.requireCapacity(listOf())
            store.record(owner, listOf(owner))
        }
        var started = false
        assertNotNull(runCatching {
            WorkspaceProcessWriteAdmission.create<HostedStdioProcess>(working, authority().processCustody()) {
                started = true; error("unexpected")
            }
        }.exceptionOrNull())
        assertFalse(started); assertTrue(owners().isEmpty())
        assertEquals(MirrorProcessCleanupReceipts.LIMIT, directory.resolve("process-cleanup-receipts").listFiles()!!.size)
    }

    @Test fun  outstandingProductionOwnersBlockFactoryAtCapacity() = fixture {
        val custody = authority().processCustody()
        val pending = List(MirrorProcessCleanupReceipts.LIMIT) { custody.acquire(working).also { it.withFactory({}, {}) } }
        var started = false
        assertNotNull(runCatching {
            WorkspaceProcessWriteAdmission.create<HostedStdioProcess>(working, custody) { started = true; error("unexpected") }
        }.exceptionOrNull())
        assertFalse(started); assertEquals(MirrorProcessCleanupReceipts.LIMIT, owners().size)
        pending.forEach { it.close() }
        authority().processCustody().acquire(working).also { it.withFactory({}, {}) }.close()
        assertTrue(owners().isEmpty())
    }

    @Test fun successfulSequentialLaunchesDoNotHaveALifetimeLimit() = fixture {
        val authority = authority()
        repeat(MirrorProcessCleanupReceipts.LIMIT + 1) {
            authority.processCustody().acquire(working).also { it.withFactory({}, {}) }.close()
            assertTrue(owners().isEmpty())
            assertEquals(1, directory.resolve("process-cleanup-receipts").listFiles()!!.size)
        }
    }

    @Test fun activeReservationsConsumeReceiptCapacityBeforeFactory() = fixture {
        val store = MirrorProcessCleanupReceipts(directory, "test-binding")
        val pending = List(MirrorProcessCleanupReceipts.LIMIT) {
            MirrorProcessCustodyLedger.Owner(UUID.randomUUID().toString(), oldRuntime)
        }
        assertNotNull(runCatching { store.requireCapacity(pending) }.exceptionOrNull())
        store.record(pending.first(), pending) // its reserved completion slot still works
        assertNotNull(runCatching { store.requireCapacity(pending) }.exceptionOrNull())
        assertEquals(pending.drop(1), store.remaining(pending))
    }

    @Test fun retirementInterruptionCannotResurrectOwnerOrDeleteReplacement() = fixture {
        authority().processCustody().acquire(working).also { it.withFactory({}, {}) }.close()
        assertTrue(owners().isEmpty())
        val failure = runCatching {
            authority { if (it == "writer-cleanup-retirement-after-unlink") throw Interrupted() }
                .processCustody().acquire(working).also { it.withFactory({}, {}) }
        }.exceptionOrNull()
        assertTrue(failure is Interrupted); assertTrue(owners().isEmpty())
        authority().processCustody().acquire(working).also { it.withFactory({}, {}) }.use {
            val replacement = owners()
            assertEquals(1, replacement.size)
            assertNotNull(runCatching { authority().requireNoPendingProcessOwners() }.exceptionOrNull())
            assertEquals(replacement, owners())
        }
    }

    @Test fun pendingCleanupProofIsNotRetiredBeforeReconciliationCommit() = fixture {
        val old = authority { if (it == "writer-cleanup-receipt-after-force") throw Interrupted() }
            .processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        assertNotNull(runCatching { old.close() }.exceptionOrNull())
        val proof = receipt(); val bytes = proof.readBytes(); val original = owners()
        assertTrue(runCatching {
            authority { if (it == "writer-admission-before-commit") throw Interrupted() }
                .processCustody().acquire(working).also { it.withFactory({}, {}) }
        }.exceptionOrNull() is Interrupted)
        assertEquals(original, owners()); assertArrayEquals(bytes, proof.readBytes())
        authority().requireNoPendingProcessOwners(); assertTrue(owners().isEmpty())
    }

    @Test fun receiptWithDifferentRuntimeCannotClearMatchingOwnerId() = fixture {
        val old = authority { if (it == "writer-cleanup-receipt-after-force") throw Interrupted() }
            .processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        assertNotNull(runCatching { old.close() }.exceptionOrNull())
        val proof = receipt()
        proof.writeText(proof.readText().replace(oldRuntime, UUID.randomUUID().toString()))
        val bytes = proof.readBytes()
        assertNotNull(runCatching { authority().requireNoPendingProcessOwners() }.exceptionOrNull())
        assertArrayEquals(bytes, proof.readBytes()); assertEquals(1, owners().size)
    }

    @Test fun receiptSymlinkIsRejectedWithoutReadingTarget() = fixture {
        val old = authority { if (it == "writer-cleanup-receipt-after-force") throw Interrupted() }
            .processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        assertNotNull(runCatching { old.close() }.exceptionOrNull())
        val proof = receipt(); val saved = root.resolve("saved-proof")
        Files.move(proof.toPath(), saved.toPath()); Files.createSymbolicLink(proof.toPath(), saved.toPath())
        val bytes = saved.readBytes()
        assertNotNull(runCatching { authority().requireNoPendingProcessOwners() }.exceptionOrNull())
        assertArrayEquals(bytes, saved.readBytes()); assertEquals(1, owners().size)
    }
}
