package com.baystudio.droide.core

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
class MirrorProcessCustodyTest {
    @get:org.junit.Rule val admission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()
    private class Interrupted : RuntimeException()
    private class Fixture {
        val root = Files.createTempDirectory("mirror-process-custody-").toFile()
        val source = root.resolve("source").apply { mkdir(); resolve("file").writeText("user data") }
        val working = root.resolve("working")
        val directory = root.resolve(".working-sync-authority")
        val database get() = directory.resolve("state.sqlite")
        lateinit var mirror: LocalAgentWorkspaceMirror
        suspend fun prepare() { mirror = LocalAgentWorkspaceMirror.prepareRetained(source, working) }
        fun authority(checkpoint: (String) -> Unit = {}) = MirrorMetadataAuthority(directory, source, working, processCheckpoint = checkpoint)
        fun db() = SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE)
        fun metadata() = MessageDigest.getInstance("SHA-256").digest(database.readBytes()).toList()
        fun owners() = authority().pendingProcessOwners()
        fun payloads() = listOf(source, working, directory.resolve("retained"), directory.resolve("prepared")).flatMap { base ->
            base.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path to it.readBytes().toList() }.toList()
        }.toMap()
    }
    private fun fixture(action: suspend Fixture.() -> Unit) = runBlocking {
        val f = Fixture()
        try { withTimeout(45_000) { f.prepare(); f.action() } }
        finally { assertTrue(PathSecurity.deleteTreeNoFollow(f.root)) }
    }

    @Test fun legacyReadDoesNotUpgradeAndAdmissionPreservesHistoryAndPayloads() = fixture {
        val state = authority().load(); val operations = authority().operations(); val payloads = payloads(); val before = metadata()
        assertTrue(owners().isEmpty()); assertEquals(before, metadata())
        authority().processCustody().acquire(working).also { it.withFactory({}, {}) }.use {
            assertEquals(1, owners().size)
            assertEquals(state, authority().load()); assertEquals(operations, authority().operations())
            assertEquals(payloads, payloads()); db().use { assertEquals(6, it.version) }
        }
        assertTrue(owners().isEmpty()); assertEquals(payloads, payloads())
    }

    @Test fun hostedLaunchRecordsOwnershipBeforeTheActualFactoryAndReleasesAfterCleanup() = fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val host = LocalLinuxProcessHost(working, scope)
        val owner = SessionHostedProcessOwner()
        try {
            val process = host.startAdmitted(listOf("/bin/sh"), emptyMap(), null, prepareStart = { {
                    assertEquals(1, owners().size)
                    LocalHostedProcess(ProcessBuilder("/bin/sh", "-c", "exec sleep 30").directory(working).start(), scope) {}
                } }, processCustody = authority().processCustody(), admitStart = { it() }).also(owner::retain)
            assertTrue(process.isAlive); assertEquals(1, owners().size)
            owner.stopAndAwait()
            assertFalse(process.isAlive); assertTrue(owners().isEmpty())
            ExportWriterAdmission.beginCapture(working).close()
        } finally { withContext(NonCancellable) { owner.stopAndAwait(); scope.cancel() } }
    }

    @Test fun actualMirrorCaptureKeepsDurableOwnershipThroughDelayedCleanup() = fixture {
        coroutineScope {
            val cleaning = CountDownLatch(1); val release = CountDownLatch(1)
            val capture = async { mirror.withProcessCustody {
                LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", "exit 0"), working, cleanup = {
                    cleaning.countDown(); check(release.await(10, TimeUnit.SECONDS))
                })
            } }
            try {
                assertTrue(withContext(Dispatchers.IO) { cleaning.await(5, TimeUnit.SECONDS) })
                assertEquals(1, owners().size)
                assertNotNull(runCatching { authority().requireNoPendingProcessOwners() }.exceptionOrNull())
            } finally { release.countDown() }
            assertEquals(0, capture.await().exitCode)
        }
        assertTrue(owners().isEmpty()); authority().requireNoPendingProcessOwners()
    }

    @Test fun failedCaptureReceiptSurvivesAuthorityRecreation() = fixture {
        val before = payloads()
        val failure = runCatching { mirror.withProcessCustody {
            LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", "exit 0"), working,
                cleanup = { error("Owned lease cleanup failed") })
        } }.exceptionOrNull()
        assertTrue(failure.toString(), failure?.message.orEmpty().contains("Owned lease cleanup failed"))
        assertEquals(1, owners().size)
        assertNotNull(runCatching { authority().requireNoPendingProcessOwners() }.exceptionOrNull())
        assertEquals(before, payloads())
    }

    @Test fun earlierRuntimeOwnershipRefusesReplacementBeforeItsFactoryRuns() = fixture {
        val first = authority().processCustody(UUID.randomUUID().toString()).acquire(working).also { it.withFactory({}, {}) }
        try {
            val before = metadata(); var created = false
            val failure = runCatching { WorkspaceProcessWriteAdmission.create<HostedStdioProcess>(working,
                authority().processCustody(UUID.randomUUID().toString())) { created = true; error("unexpected spawn") } }.exceptionOrNull()
            assertTrue(failure.toString(), failure?.message.orEmpty().contains("earlier runtime"))
            assertFalse(created); assertEquals(before, metadata()); assertEquals(1, owners().size)
        } finally { first.close() }
        authority().processCustody(UUID.randomUUID().toString()).acquire(working).also { it.withFactory({}, {}) }.close()
        assertTrue(owners().isEmpty())
    }

    @Test fun admissionInterruptedBeforeCommitRollsBackWithoutRunningFactory() = fixture {
        val before = metadata(); var created = false
        val custody = authority { if (it == "writer-admission-before-commit") throw Interrupted() }.processCustody()
        assertTrue(runCatching { WorkspaceProcessWriteAdmission.create<HostedStdioProcess>(working, custody) {
            created = true; error("unexpected spawn")
        } }.exceptionOrNull() is Interrupted)
        assertFalse(created); assertTrue(owners().isEmpty()); assertEquals(before, metadata())
        ExportWriterAdmission.beginCapture(working).close()
    }

    @Test fun admissionInterruptedAfterCommitLeavesEvidenceEvenWithoutAProcess() = fixture {
        val before = payloads(); var created = false
        val custody = authority { if (it == "writer-admission-after-commit") throw Interrupted() }.processCustody()
        assertTrue(runCatching { WorkspaceProcessWriteAdmission.create<HostedStdioProcess>(working, custody) {
            created = true; error("unexpected spawn")
        } }.exceptionOrNull() is Interrupted)
        assertFalse(created); assertEquals(1, owners().size); assertEquals(before, payloads())
        assertNotNull(runCatching { authority().requireNoPendingProcessOwners() }.exceptionOrNull())
        authority().processCustody(UUID.randomUUID().toString()).acquire(working).use { assertEquals(1, owners().size) }
    }

    @Test fun confirmationInterruptedBeforeCommitRetainsOwnershipAndCanRetry() = fixture {
        var fail = true
        val receipt = authority { if (fail && it == "writer-completion-before-commit") throw Interrupted() }.processCustody().acquire(working).also { it.withFactory({}, {}) }
        val before = metadata()
        assertTrue(runCatching { receipt.close() }.exceptionOrNull() is Interrupted)
        assertEquals(before, metadata()); assertEquals(1, owners().size)
        fail = false; receipt.close(); assertTrue(owners().isEmpty())
    }

    @Test fun lostConfirmationResponseAndRepeatedOldReceiptCannotClearNewOwnership() = fixture {
        var fail = true
        val old = authority { if (fail && it == "writer-completion-after-commit") throw Interrupted() }.processCustody().acquire(working).also { it.withFactory({}, {}) }
        assertTrue(runCatching { old.close() }.exceptionOrNull() is Interrupted)
        assertTrue(owners().isEmpty())
        authority().processCustody(UUID.randomUUID().toString()).acquire(working).also { it.withFactory({}, {}) }.use {
            val current = owners(); fail = false
            old.close(); old.close()
            assertEquals(current, owners())
        }
        assertTrue(owners().isEmpty())
    }

    @Test fun rejectionBeforeFactoryConfirmsOnlyItsOwnAdmission() = fixture {
        authority().processCustody().acquire(working).also { it.withFactory({}, {}) }.use {
            val current = owners()
            val failure = runCatching { WorkspaceProcessWriteAdmission.create<HostedStdioProcess>(working,
                authority().processCustody(), beforeFactory = { error("Process factory failed") }) { error("Unexpected factory entry") } }.exceptionOrNull()
            assertEquals("Process factory failed", failure?.message)
            assertEquals(current, owners())
        }
        assertTrue(owners().isEmpty()); ExportWriterAdmission.beginCapture(working).close()
    }

    @Test fun cancelledCaptureHandoffConfirmsCleanupBeforeItsOwnerLeaves() = fixture {
        coroutineScope {
            val born = CompletableDeferred<Unit>()
            val capture = launch { mirror.withProcessCustody {
                LocalProcessSupervisor.captureWithAdmission(listOf("/bin/sh", "-c", "exec sleep 30"), working,
                    emptyMap(), 1024, 0, admitStart = { create -> create(); born.complete(Unit); awaitCancellation() })
            } }
            born.await(); assertEquals(1, owners().size)
            capture.cancelAndJoin()
        }
        assertTrue(owners().isEmpty()); ExportWriterAdmission.beginCapture(working).close()
    }

    @Test fun cancellationDuringDurableAdmissionPreventsCaptureCreation() = fixture {
        coroutineScope {
            val task = launch {
                val caller = currentCoroutineContext().job
                val custody = authority { if (it == "writer-admission-after-commit") caller.cancel() }.processCustody()
                withContext(custody) {
                    LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", "printf spawned > unexpected-process"), working)
                }
            }
            task.join(); assertTrue(task.isCancelled)
        }
        assertFalse(working.resolve("unexpected-process").exists())
        assertTrue(owners().isEmpty()); ExportWriterAdmission.beginCapture(working).close()
    }

    @Test fun cancellationDuringDurableAdmissionPreventsHostedProcessCreation() = fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var created = false
        try {
            coroutineScope {
                val task = launch {
                    val caller = currentCoroutineContext().job
                    val custody = authority { if (it == "writer-admission-after-commit") caller.cancel() }.processCustody()
                    LocalLinuxProcessHost(working, scope).startAdmitted(listOf("/bin/sh"), emptyMap(), null,
                        prepareStart = { { created = true; error("Unexpected process creation") } },
                        processCustody = custody, admitStart = { it() })
                }
                task.join(); assertTrue(task.isCancelled)
            }
            assertFalse(created); assertTrue(owners().isEmpty())
            ExportWriterAdmission.beginCapture(working).close()
        } finally { scope.cancel() }
    }

    @Test fun nativePreflightFailureDropsExactMirrorOwnerAndAllowsRetry() = fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val owner = SessionHostedProcessOwner()
        try {
            val host = LocalLinuxProcessHost(working, scope)
            val failure = runCatching {
                host.startAdmitted(listOf("/bin/sh"), emptyMap(), null,
                    prepareStart = { PreparedLocalNativeProcess(
                        ProcessBuilder(working.resolve("missing").path).directory(working), scope) {} },
                    processCustody = mirror.processCustody, admitStart = { it() })
            }.exceptionOrNull()
            assertTrue(failure.toString(), failure is java.io.IOException)
            assertTrue(owners().isEmpty())
            assertTrue(WorkspaceProcessWriteAdmission.observe(working).unknownFactoryRoots.isEmpty())
            ExportWriterAdmission.beginCapture(working).close()
            val process = host.startAdmitted(listOf("/bin/sh"), emptyMap(), null,
                prepareStart = { PreparedLocalNativeProcess(
                    ProcessBuilder("/bin/sh", "-c", "exit 0").directory(working), scope) {} },
                processCustody = mirror.processCustody, admitStart = { it() }).also(owner::retain)
            assertEquals(0, process.awaitExit())
            owner.stopAndAwait()
            assertTrue(owners().isEmpty())
        } finally { withContext(NonCancellable) { owner.stopAndAwait(); scope.cancel() } }
    }

    @Test fun toolchainPreparationDoesNotInheritTheMirrorProcessCustody() = fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val owner = SessionHostedProcessOwner()
        try {
            LocalLinuxProcessHost(working, scope).startAdmitted(listOf("/bin/sh"), emptyMap(), null,
                prepareStart = {
                    // Preparation may capture a toolchain probe outside the CLI mirror.
                    assertEquals(0, LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", "exit 0"), source).exitCode)
                    assertTrue(owners().isEmpty())
                    val factory: () -> LocalHostedProcess = {
                        assertEquals(1, owners().size)
                        LocalHostedProcess(ProcessBuilder("/bin/sh", "-c", "exec sleep 30").directory(working).start(), scope) {}
                    }
                    factory
                }, processCustody = mirror.processCustody, admitStart = { it() }).also(owner::retain)
            owner.stopAndAwait(); assertTrue(owners().isEmpty())
        } finally { withContext(NonCancellable) { owner.stopAndAwait(); scope.cancel() } }
    }

    @Test fun missingMembershipIsRejectedWithoutReconstructingAnEmptyLedger() = fixture {
        authority().processCustody().acquire(working).also { it.withFactory({}, {}) }
        db().use { it.execSQL("DELETE FROM process_owners") }
        val broken = metadata()
        assertNotNull(runCatching { owners() }.exceptionOrNull())
        assertNotNull(runCatching { authority().processCustody().acquire(working).also { it.withFactory({}, {}) } }.exceptionOrNull())
        assertEquals(broken, metadata())
    }

    @Test fun alteredRuntimeCannotBeAdoptedAsCurrentOwnership() = fixture {
        authority().processCustody(UUID.randomUUID().toString()).acquire(working).also { it.withFactory({}, {}) }
        db().use { it.execSQL("UPDATE process_owners SET runtime_id=?", arrayOf(WorkspaceProcessCustody.runtimeId)) }
        val broken = metadata()
        assertNotNull(runCatching { authority().requireNoPendingProcessOwners() }.exceptionOrNull())
        assertNotNull(runCatching { authority().processCustody().acquire(working).also { it.withFactory({}, {}) } }.exceptionOrNull())
        assertEquals(broken, metadata())
    }

    @Test fun schemaDowngradeCannotHidePendingOwners() = fixture {
        authority().processCustody().acquire(working).also { it.withFactory({}, {}) }
        db().use { it.version = 3 }
        val broken = metadata()
        assertNotNull(runCatching { owners() }.exceptionOrNull()); assertEquals(broken, metadata())
    }

    @Test fun custodyCannotBeTransferredToAnotherWorkspace() = fixture {
        val other = root.resolve("other").apply { mkdir(); resolve("file").writeText("unrelated") }
        val before = metadata()
        assertNotNull(runCatching { authority().processCustody().acquire(other) }.exceptionOrNull())
        assertEquals(before, metadata()); assertEquals("unrelated", other.resolve("file").readText())
    }

    @Test fun concurrentReceiptsAndMetadataReadsUseTheSameAuthorityGate() = fixture {
        coroutineScope {
            val records = (1..12).map { async(Dispatchers.IO) { authority().processCustody().acquire(working).also { it.withFactory({}, {}) } } }.awaitAll()
            assertEquals(12, owners().size)
            (records.map { receipt -> async(Dispatchers.IO) { receipt.close() } } +
                (1..12).map { async(Dispatchers.IO) { authority().load(); Unit } }).awaitAll()
        }
        assertTrue(owners().isEmpty())
    }

    @Test fun fullOwnerCapacityPreservesRecordsAndReopensOnlyAfterConfirmation() = fixture {
        val receipts = (1..MirrorProcessCustodyLedger.MAX_OWNERS).map { authority().processCustody().acquire(working).also { it.withFactory({}, {}) } }.toMutableList()
        try {
            val before = metadata()
            assertNotNull(runCatching { authority().processCustody().acquire(working).also { it.withFactory({}, {}) } }.exceptionOrNull())
            assertEquals(before, metadata()); assertEquals(MirrorProcessCustodyLedger.MAX_OWNERS, owners().size)
            receipts.removeAt(0).close()
            receipts += authority().processCustody().acquire(working).also { it.withFactory({}, {}) }
            assertEquals(MirrorProcessCustodyLedger.MAX_OWNERS, owners().size)
        } finally { receipts.forEach { it.close() } }
        assertTrue(owners().isEmpty())
    }

    @Test fun sqliteCapacityFailureRollsBackAdmissionAndNeverStartsAProcess() = fixture {
        val limited = MirrorMetadataAuthority(directory, source, working, databaseSizeLimitBytes = database.length())
        val before = metadata(); var created = false
        val failure = runCatching { WorkspaceProcessWriteAdmission.create<HostedStdioProcess>(working, limited.processCustody()) {
            created = true; error("unexpected spawn")
        } }.exceptionOrNull()
        assertTrue(failure.toString(), failure is android.database.sqlite.SQLiteFullException)
        assertFalse(created); assertEquals(before, metadata()); assertTrue(owners().isEmpty())
    }

    @Test fun pendingFilesystemIntentRefusesProcessAdmission() = fixture {
        val before = authority().load()
        authority().prepare("pending", "create", MirrorMetadataAuthority.Node.absent,
            MirrorMetadataAuthority.Node.file(WorkspaceSyncManifest.Entry("a".repeat(64), 420)))
        val metadata = metadata()
        assertNotNull(runCatching { authority().processCustody().acquire(working).also { it.withFactory({}, {}) } }.exceptionOrNull())
        assertEquals(metadata, metadata()); assertEquals(before.baseline, authority().load().baseline)
        assertTrue(owners().isEmpty())
    }
}
