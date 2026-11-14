package com.baystudio.droide.core

import android.database.sqlite.SQLiteDatabase
import java.nio.file.Files
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
class MirrorProcessLaunchPermissionTest {
    @get:org.junit.Rule val admission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()
    private class Interrupted : RuntimeException()
    private val priorRuntime = UUID.randomUUID().toString()
    private class Fixture {
        val root = Files.createTempDirectory("mirror-launch-permit-").toFile()
        val source = root.resolve("source").apply { mkdir(); resolve("file").writeText("preserved") }
        val working = root.resolve("working")
        val directory = root.resolve(".working-sync-authority")
        suspend fun prepare() { LocalAgentWorkspaceMirror.prepareRetained(source, working) }
        fun authority(checkpoint: (String) -> Unit = {}) = MirrorMetadataAuthority(directory, source, working,
            processCheckpoint = checkpoint, processBootIdentity = { null })
        fun db() = SQLiteDatabase.openDatabase(directory.resolve("state.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE)
        fun owners() = authority().pendingProcessOwners()
        fun states() = db().use { MirrorProcessCustodyLedger.launchStates(it) }
        fun binding(db: SQLiteDatabase) = db.rawQuery("SELECT binding FROM meta", null).use { check(it.moveToFirst()); it.getString(0) }
        fun makeLegacy5() = db().use { db ->
            db.beginTransaction()
            try {
                val owners = MirrorProcessCustodyLedger.read(db, binding(db))
                db.execSQL("ALTER TABLE process_owners RENAME TO current_launch_owners")
                db.execSQL("CREATE TABLE process_owners(id TEXT PRIMARY KEY NOT NULL,runtime_id TEXT NOT NULL,device_identity TEXT,boot_id TEXT)")
                db.execSQL("DELETE FROM process_custody")
                db.execSQL("INSERT INTO process_custody VALUES(1,0,'')")
                db.version = 5
                // Use the frozen v5 append implementation to reconstruct its exact checksum.
                var previous = emptyList<LegacyV5ProcessCustodyLedger.Owner>()
                for (owner in owners) {
                    val old = LegacyV5ProcessCustodyLedger.Owner(owner.id, owner.runtimeId, owner.boot)
                    LegacyV5ProcessCustodyLedger.append(db, binding(db), previous, old); previous = previous + old
                }
                if (owners.isEmpty()) {
                    val temporary = LegacyV5ProcessCustodyLedger.Owner(UUID.randomUUID().toString(), UUID.randomUUID().toString())
                    LegacyV5ProcessCustodyLedger.append(db, binding(db), emptyList(), temporary)
                    LegacyV5ProcessCustodyLedger.confirm(db, binding(db), listOf(temporary), temporary)
                }
                db.execSQL("DROP TABLE current_launch_owners")
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }
    private fun fixture(action: suspend Fixture.() -> Unit) = runBlocking {
        val f = Fixture()
        try { withTimeout(60_000) { f.prepare(); f.action() } }
        finally { assertTrue(PathSecurity.deleteTreeNoFollow(f.root)) }
    }

    @Test fun delayedPreparedRuntimeLosesPermissionAfterCommittedRevocation() = fixture {
        val old = authority().processCustody(priorRuntime).acquire(working)
        authority().requireNoPendingProcessOwners(); assertTrue(owners().isEmpty())
        val replacement = authority().processCustody().acquire(working)
        val current = owners()
        var started = false
        assertNotNull(runCatching { old.withFactory({}, { started = true }) }.exceptionOrNull())
        assertFalse(started); old.close(); assertEquals(current, owners())
        replacement.close(); assertEquals("preserved", working.resolve("file").readText())
    }
    @Test fun enteredOwnerCannotBeRevokedWithoutExactCleanup() = fixture {
        val old = authority().processCustody(priorRuntime).acquire(working)
        old.withFactory({}, {})
        assertNotNull(runCatching { authority().requireNoPendingProcessOwners() }.exceptionOrNull())
        assertEquals(setOf(MirrorProcessCustodyLedger.LaunchState.FACTORY_ENTERED), states().values.toSet())
        old.close(); assertTrue(owners().isEmpty())
    }
    @Test fun mixedOwnersCommitRevocationButStillBlockBroadRecovery() = fixture {
        val entered = authority().processCustody(priorRuntime).acquire(working); entered.withFactory({}, {})
        val prepared = authority().processCustody(priorRuntime).acquire(working)
        assertNotNull(runCatching { authority().requireNoPendingProcessOwners() }.exceptionOrNull())
        assertEquals(setOf(MirrorProcessCustodyLedger.LaunchState.FACTORY_ENTERED,
            MirrorProcessCustodyLedger.LaunchState.CANCELLED_NO_START), states().values.toSet())
        assertNotNull(runCatching { prepared.withFactory({}, {}) }.exceptionOrNull())
        entered.close(); authority().requireNoPendingProcessOwners(); prepared.close(); assertTrue(owners().isEmpty())
    }
    @Test fun cancellationRollbackCannotAuthorizeRetirement() = fixture {
        val old = authority().processCustody(priorRuntime).acquire(working)
        assertTrue(runCatching {
            authority { if (it == "writer-reservation-cancel-before-commit") throw Interrupted() }.requireNoPendingProcessOwners()
        }.exceptionOrNull() is Interrupted)
        assertEquals(setOf(MirrorProcessCustodyLedger.LaunchState.PREPARED), states().values.toSet())
        assertTrue(directory.resolve("process-cleanup-receipts").listFiles().orEmpty().isEmpty())
        old.withFactory({}, {}); old.close()
    }
    @Test fun committedCancellationSurvivesLostResponseAndRetirementRollback() = fixture {
        val old = authority().processCustody(priorRuntime).acquire(working)
        assertTrue(runCatching {
            authority { if (it == "writer-reservation-cancel-after-commit") throw Interrupted() }.requireNoPendingProcessOwners()
        }.exceptionOrNull() is Interrupted)
        assertEquals(setOf(MirrorProcessCustodyLedger.LaunchState.CANCELLED_NO_START), states().values.toSet())
        assertTrue(runCatching {
            authority { if (it == "writer-boot-reconciliation-before-commit") throw Interrupted() }.requireNoPendingProcessOwners()
        }.exceptionOrNull() is Interrupted)
        assertEquals(setOf(MirrorProcessCustodyLedger.LaunchState.CANCELLED_NO_START), states().values.toSet())
        assertNotNull(runCatching { old.withFactory({}, {}) }.exceptionOrNull())
        authority().requireNoPendingProcessOwners(); old.close()
    }
    @Test fun lostEntryResponseConsumesPermissionWithoutInvokingFactory() = fixture {
        val old = authority { if (it == "writer-factory-entry-after-commit") throw Interrupted() }
            .processCustody(priorRuntime).acquire(working)
        var started = false
        assertTrue(runCatching { old.withFactory({}, { started = true }) }.exceptionOrNull() is Interrupted)
        assertNotNull(runCatching { old.withFactory({}, { started = true }) }.exceptionOrNull())
        assertFalse(started)
        assertNotNull(runCatching { authority().requireNoPendingProcessOwners() }.exceptionOrNull())
        old.close(); assertTrue(owners().isEmpty())
    }
    @Test fun reentrantValidationCannotConsumeTheSamePermitTwice() = fixture {
        val receipt = authority().processCustody().acquire(working)
        var calls = 0
        receipt.withFactory(validate = {
            assertNotNull(runCatching { receipt.withFactory({}, { calls++ }) }.exceptionOrNull())
        }, factory = { calls++ })
        assertEquals(1, calls); receipt.close()
    }
    @Test fun reentrantAbortAfterEntryPermanentlyPreventsFactory() = fixture {
        val receipt = authority().processCustody().acquire(working)
        var validations = 0; var started = false
        assertNotNull(runCatching { receipt.withFactory(validate = {
            if (++validations == 2) receipt.close()
        }, factory = { started = true }) }.exceptionOrNull())
        assertFalse(started); assertTrue(owners().isEmpty())
    }
    @Test fun reentrantCleanupInsideFactoryCannotPublishAFalseReceipt() = fixture {
        val receipt = authority().processCustody().acquire(working)
        receipt.withFactory({}, {
            assertNotNull(runCatching { receipt.close() }.exceptionOrNull())
            assertEquals(1, owners().size)
            assertTrue(directory.resolve("process-cleanup-receipts").listFiles().orEmpty().isEmpty())
        })
        receipt.close(); assertTrue(owners().isEmpty())
    }
    @Test fun failedCloseIrrevocablyConsumesLocalPermission() = fixture {
        var fail = true
        val old = authority { if (fail && it == "writer-completion-before-commit") throw Interrupted() }
            .processCustody(priorRuntime).acquire(working)
        assertTrue(runCatching { old.close() }.exceptionOrNull() is Interrupted)
        assertNotNull(runCatching { old.withFactory({}, {}) }.exceptionOrNull())
        fail = false; old.close(); assertTrue(owners().isEmpty())
    }
    @Test fun localCloseCannotPassFactoryHandoffInFlight() = fixture {
        val old = authority().processCustody().acquire(working)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        coroutineScope {
            val factory = async(Dispatchers.IO) { old.withFactory({}, { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }) }
            assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
            val closer = async(Dispatchers.IO) { old.close() }
            delay(50); assertFalse(closer.isCompleted); assertEquals(1, owners().size)
            release.countDown(); factory.await(); closer.await()
        }
        assertTrue(owners().isEmpty())
    }
    @Test fun cancellationDuringEntryIoPreventsActualFactory() = fixture {
        var started = false
        val caller = currentCoroutineContext().job
        // Use an isolated child Job so the fixture can assert after cancellation.
        val task = CoroutineScope(caller + Dispatchers.IO).launch {
            val child = currentCoroutineContext().job
            val custody = authority { if (it == "writer-factory-entry-after-commit") child.cancel() }.processCustody()
            runCatching { WorkspaceProcessWriteAdmission.create<HostedStdioProcess>(working, custody,
                beforeFactory = { child.ensureActive() }) { started = true; error("unexpected") } }
        }
        task.join(); assertFalse(started); assertTrue(owners().isEmpty())
    }
    @Test fun legacyLiveOwnerDefersMigrationAndFrozenV5CleanupStillWorks() = fixture {
        authority().processCustody(priorRuntime).acquire(working).withFactory({}, {})
        makeLegacy5()
        assertNotNull(runCatching { authority().processCustody().acquire(working) }.exceptionOrNull())
        db().use { db ->
            assertEquals(5, db.version); val old = LegacyV5ProcessCustodyLedger.read(db, binding(db)).single()
            db.beginTransaction()
            try { LegacyV5ProcessCustodyLedger.confirm(db, binding(db), listOf(old), old); db.setTransactionSuccessful() }
            finally { db.endTransaction() }
        }
        authority().processCustody().acquire(working).close(); db().use { assertEquals(6, it.version) }
    }
    @Test fun frozenV5CannotReadEmptyV6DigestOrInsertPhaselessOwner() = fixture {
        authority().processCustody().acquire(working).close()
        db().use { db ->
            assertNotNull(runCatching { LegacyV5ProcessCustodyLedger.read(db, binding(db)) }.exceptionOrNull())
            db.beginTransaction()
            try {
                assertNotNull(runCatching { LegacyV5ProcessCustodyLedger.append(db, binding(db), emptyList(),
                    LegacyV5ProcessCustodyLedger.Owner(UUID.randomUUID().toString(), UUID.randomUUID().toString())) }.exceptionOrNull())
            } finally { db.endTransaction() }
        }
        assertTrue(owners().isEmpty())
    }
    @Test fun phaseCorruptionAndHeaderDowngradeCannotAuthorizeLaunch() = fixture {
        authority().processCustody(priorRuntime).acquire(working)
        db().use { it.execSQL("UPDATE process_owners SET launch_state='CANCELLED_NO_START'") }
        assertNotNull(runCatching { authority().requireNoPendingProcessOwners() }.exceptionOrNull())
        db().use { it.version = 5 }
        assertNotNull(runCatching { authority().pendingProcessOwners() }.exceptionOrNull())
    }
}
