package com.baystudio.droide.core

import android.database.sqlite.SQLiteDatabase
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MirrorProcessBootReconciliationTest {
    @get:org.junit.Rule val admission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()
    private class Interrupted : RuntimeException()
    private val previous = WorkspaceProcessBootIdentity.Identity("a".repeat(64), "e9bc19df-5a35-4a8b-bbf9-cf98316c8423")
    private val current = previous.copy(boot = "58362bbf-79b4-4644-bea9-e6361e2851e1")
    private val oldRuntime = UUID.randomUUID().toString()

    private class Fixture {
        val root = Files.createTempDirectory("mirror-boot-recovery-").toFile()
        val source = root.resolve("source").apply { mkdir(); resolve("file").writeText("original") }
        val working = root.resolve("working")
        val directory = root.resolve(".working-sync-authority")
        val database get() = directory.resolve("state.sqlite")
        suspend fun prepare() {
            val mirror = LocalAgentWorkspaceMirror.prepareRetained(source, working)
            source.resolve("file").writeText("published"); mirror.refresh()
            check(directory.resolve("retained").listFiles().orEmpty().isNotEmpty())
        }
        fun authority(boot: WorkspaceProcessBootIdentity.Identity? = null, checkpoint: (String) -> Unit = {}) =
            MirrorMetadataAuthority(directory, source, working, processCheckpoint = checkpoint, processBootIdentity = { boot })
        fun db() = SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE)
        fun metadata() = MessageDigest.getInstance("SHA-256").digest(database.readBytes()).toList()
        fun owners() = authority().pendingProcessOwners()
        fun payloads() = listOf(source, working, directory.resolve("retained"), directory.resolve("prepared")).flatMap { base ->
            base.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path to it.readBytes().toList() }.toList()
        }.toMap()

        // Frozen F113 on-disk format, including its length-framed checksum. This is a
        // valid old database, unlike changing only user_version on a schema-5 database.
        fun makeLegacySchema4() = db().use { db ->
            db.beginTransaction()
            try {
                db.execSQL("ALTER TABLE process_owners RENAME TO newer_process_owners")
                db.execSQL("CREATE TABLE process_owners(id TEXT PRIMARY KEY NOT NULL,runtime_id TEXT NOT NULL)")
                db.execSQL("INSERT INTO process_owners SELECT id,runtime_id FROM newer_process_owners")
                db.execSQL("DROP TABLE newer_process_owners")
                val hash = MessageDigest.getInstance("SHA-256")
                fun part(value: String) {
                    val bytes = value.toByteArray(Charsets.UTF_8)
                    hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); hash.update(bytes)
                }
                db.rawQuery("SELECT binding FROM meta", null).use { check(it.moveToFirst()); part(it.getString(0)) }
                db.rawQuery("SELECT id,runtime_id FROM process_owners ORDER BY id", null).use {
                    while (it.moveToNext()) { part(it.getString(0)); part(it.getString(1)) }
                }
                db.execSQL("UPDATE process_custody SET members_digest=?", arrayOf(hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }))
                db.version = 4; db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }
    private fun fixture(action: suspend Fixture.() -> Unit) = runBlocking {
        val f = Fixture()
        try { withTimeout(60_000) { f.prepare(); f.action() } }
        finally { assertTrue(PathSecurity.deleteTreeNoFollow(f.root)) }
    }

    @Test fun readOnlyInspectionPreservesOwnersFromAnEarlierBoot() = fixture {
        authority(previous).processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        val before = metadata(); val payloads = payloads()
        assertEquals(previous, authority(current).pendingProcessOwners().single().boot)
        authority(current).load(); authority(current).operations()
        assertEquals(before, metadata()); assertEquals(payloads, payloads())
    }

    @Test fun sameDeviceNewBootAdmitsReplacementAndOldReceiptsCannotClearIt() = fixture {
        val old = authority(previous).processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        val state = authority().load(); val history = authority().operations(); val payloads = payloads()
        authority(current).processCustody().acquire(working).also { it.withFactory({}, {}) }.use {
            val replacement = owners().single()
            assertEquals(current, replacement.boot); assertEquals(WorkspaceProcessCustody.runtimeId, replacement.runtimeId)
            old.close(); old.close(); assertEquals(listOf(replacement), owners())
            assertEquals(state, authority().load()); assertEquals(history, authority().operations())
            assertEquals(payloads, payloads())
        }
        assertTrue(owners().isEmpty())
    }

    @Test fun sameBootOrDifferentDeviceRefusesBeforeTheProcessFactory() = fixture {
        authority(previous).processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        for (candidate in listOf(previous, current.copy(device = "b".repeat(64)), null)) {
            val before = metadata(); var started = false
            val failure = runCatching {
                WorkspaceProcessWriteAdmission.create<HostedStdioProcess>(working, authority(candidate).processCustody()) {
                    started = true; error("unexpected process creation")
                }
            }.exceptionOrNull()
            assertTrue(failure.toString(), failure?.message.orEmpty().contains("earlier runtime"))
            assertFalse(started); assertEquals(before, metadata()); assertEquals(previous, owners().single().boot)
            assertNotNull(runCatching { authority(candidate).requireNoPendingProcessOwners() }.exceptionOrNull())
            assertEquals(before, metadata())
        }
        ExportWriterAdmission.beginCapture(working).close()
    }

    @Test fun unknownRecordedIdentityRemainsOwnedWhenCurrentBootBecomesAvailable() = fixture {
        authority().processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        val before = metadata()
        assertNotNull(runCatching { authority(current).processCustody().acquire(working).also { it.withFactory({}, {}) } }.exceptionOrNull())
        assertNotNull(runCatching { authority(current).requireNoPendingProcessOwners() }.exceptionOrNull())
        assertEquals(before, metadata()); assertNull(owners().single().boot)
    }

    @Test fun currentRuntimeOwnershipIsNeverRetiredByChangedBootObservation() = fixture {
        authority(previous).processCustody().acquire(working).also { it.withFactory({}, {}) }.use {
            val before = metadata()
            assertNotNull(runCatching { authority(current).requireNoPendingProcessOwners() }.exceptionOrNull())
            assertEquals(before, metadata()); assertEquals(previous, owners().single().boot)
        }
    }

    @Test fun mixedEligibleAndUnknownOwnersDoNotPartiallyReconcile() = fixture {
        authority(previous).processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        authority().processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        val before = metadata(); val owners = owners()
        assertNotNull(runCatching { authority(current).processCustody().acquire(working).also { it.withFactory({}, {}) } }.exceptionOrNull())
        assertEquals(before, metadata()); assertEquals(owners, owners())
        assertNotNull(runCatching { authority(current).requireNoPendingProcessOwners() }.exceptionOrNull())
        assertEquals(before, metadata()); assertEquals(owners, owners())
    }

    @Test fun mixedEligibleAndSameBootOwnersDoNotPartiallyReconcile() = fixture {
        authority(previous).processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        authority(current).processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        val before = metadata()
        assertNotNull(runCatching { authority(current).processCustody().acquire(working).also { it.withFactory({}, {}) } }.exceptionOrNull())
        assertNotNull(runCatching { authority(current).requireNoPendingProcessOwners() }.exceptionOrNull())
        assertEquals(before, metadata()); assertEquals(2, owners().size)
    }

    @Test fun recoveryReconciliationPreservesRevisionHistoryAndEveryPayload() = fixture {
        authority(previous).processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        val state = authority().load(); val history = authority().operations(); val payloads = payloads()
        authority(current).requireNoPendingProcessOwners()
        assertTrue(owners().isEmpty()); assertEquals(state, authority().load())
        assertEquals(history, authority().operations()); assertEquals(payloads, payloads())
        val after = metadata(); authority(current).requireNoPendingProcessOwners(); assertEquals(after, metadata())
    }

    @Test fun interruptedReconciliationRollsBackAndCanRetry() = fixture {
        authority(previous).processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        val before = metadata(); var fail = true
        val recovery = authority(current) { if (fail && it == "writer-boot-reconciliation-before-commit") throw Interrupted() }
        assertTrue(runCatching { recovery.requireNoPendingProcessOwners() }.exceptionOrNull() is Interrupted)
        assertEquals(before, metadata()); assertEquals(previous, owners().single().boot)
        fail = false; recovery.requireNoPendingProcessOwners(); assertTrue(owners().isEmpty())
    }

    @Test fun lostReconciliationResponseCanRetryWithoutClearingANewOwner() = fixture {
        val old = authority(previous).processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        var fail = true
        val recovery = authority(current) { if (fail && it == "writer-boot-reconciliation-after-commit") throw Interrupted() }
        assertTrue(runCatching { recovery.requireNoPendingProcessOwners() }.exceptionOrNull() is Interrupted)
        assertTrue(owners().isEmpty()); fail = false; recovery.requireNoPendingProcessOwners()
        authority(current).processCustody().acquire(working).also { it.withFactory({}, {}) }.use {
            val replacement = owners(); val before = metadata()
            assertNotNull(runCatching { recovery.requireNoPendingProcessOwners() }.exceptionOrNull())
            old.close(); assertEquals(replacement, owners()); assertEquals(before, metadata())
        }
        recovery.requireNoPendingProcessOwners()
    }

    @Test fun interruptedAdmissionRollsBackBothRetirementAndReplacement() = fixture {
        authority(previous).processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        val before = metadata(); var started = false
        val custody = authority(current) { if (it == "writer-admission-before-commit") throw Interrupted() }.processCustody()
        assertTrue(runCatching {
            WorkspaceProcessWriteAdmission.create<HostedStdioProcess>(working, custody) {
                started = true; error("unexpected process creation")
            }
        }.exceptionOrNull() is Interrupted)
        assertFalse(started); assertEquals(before, metadata()); assertEquals(previous, owners().single().boot)
    }

    @Test fun pendingFilesystemIntentPreventsAdmissionAndRetirement() = fixture {
        authority(previous).processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        authority().prepare("pending", "create", MirrorMetadataAuthority.Node.absent,
            MirrorMetadataAuthority.Node.file(WorkspaceSyncManifest.Entry("a".repeat(64), 420)))
        val before = metadata()
        assertNotNull(runCatching { authority(current).processCustody().acquire(working).also { it.withFactory({}, {}) } }.exceptionOrNull())
        assertEquals(before, metadata()); assertEquals(previous, owners().single().boot)
    }

    @Test fun alteredBootOrDeviceCannotTurnAnOwnerIntoARebootProof() = fixture {
        authority(previous).processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        for ((column, altered, original) in listOf(
            Triple("boot_id", current.boot, previous.boot), Triple("device_identity", "b".repeat(64), previous.device))) {
            db().use { it.execSQL("UPDATE process_owners SET $column=?", arrayOf(altered)) }
            val broken = metadata()
            assertNotNull(runCatching { authority(current).requireNoPendingProcessOwners() }.exceptionOrNull())
            assertNotNull(runCatching { authority(current).processCustody().acquire(working).also { it.withFactory({}, {}) } }.exceptionOrNull())
            assertEquals(broken, metadata())
            db().use { it.execSQL("UPDATE process_owners SET $column=?", arrayOf(original)) }
        }
        assertEquals(previous, owners().single().boot)
    }

    @Test fun partialOrInvalidBootFieldsFailClosedWithoutChangingMetadata() = fixture {
        authority(previous).processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        for (invalid in listOf<Any?>(null, "", "x".repeat(1_024), ByteArray(36), 123)) {
            db().use { it.execSQL("UPDATE process_owners SET boot_id=?", arrayOf(invalid)) }
            val broken = metadata()
            assertNotNull(runCatching { authority(current).requireNoPendingProcessOwners() }.exceptionOrNull())
            assertEquals(broken, metadata())
        }
    }

    @Test fun schemaHeaderDowngradeCannotHideBootIdentityColumns() = fixture {
        authority(previous).processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        db().use { it.version = 4 }
        val broken = metadata()
        assertNotNull(runCatching { owners() }.exceptionOrNull())
        assertNotNull(runCatching { authority(current).requireNoPendingProcessOwners() }.exceptionOrNull())
        assertEquals(broken, metadata())
    }

    @Test fun legacyOwnerStaysUnknownAndBlocksMigrationUntilExactConfirmation() = fixture {
        val old = authority().processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        makeLegacySchema4()
        val before = metadata(); val payloads = payloads()
        assertNull(authority(current).pendingProcessOwners().single().boot); assertEquals(before, metadata())
        assertNotNull(runCatching { authority(current).processCustody().acquire(working).also { it.withFactory({}, {}) } }.exceptionOrNull())
        assertNotNull(runCatching { authority(current).requireNoPendingProcessOwners() }.exceptionOrNull())
        assertEquals(before, metadata()); db().use { assertEquals(4, it.version) }
        old.close()
        authority(current).processCustody().acquire(working).also { it.withFactory({}, {}) }.use {
            db().use { assertEquals(6, it.version) }; assertEquals(current, owners().single().boot)
            assertEquals(payloads, payloads())
        }
    }

    @Test fun liveLegacyOwnerDefersUpgradeUntilExactCleanup() = fixture {
        val old = authority().processCustody().acquire(working).also { it.withFactory({}, {}) }
        makeLegacySchema4()
        val before = metadata()
        assertNotNull(runCatching { authority(current).processCustody().acquire(working) }.exceptionOrNull())
        db().use { assertEquals(4, it.version) }; assertEquals(before, metadata())
        old.close()
        authority(current).processCustody().acquire(working).use {
            db().use { assertEquals(6, it.version) }; assertEquals(current, owners().single().boot)
        }
        assertTrue(owners().isEmpty())
    }

    @Test fun reconciliationOfOneWorkspaceLeavesAnotherWorkspaceUnchanged() = fixture {
        val other = Fixture()
        try {
            other.prepare()
            val foreign = other.authority(previous).processCustody(oldRuntime).acquire(other.working)
            authority(previous).processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
            val metadata = other.metadata(); val payloads = other.payloads()
            authority(current).requireNoPendingProcessOwners()
            assertTrue(owners().isEmpty()); assertEquals(metadata, other.metadata()); assertEquals(payloads, other.payloads())
            assertEquals(previous, other.owners().single().boot); foreign.close()
        } finally { assertTrue(PathSecurity.deleteTreeNoFollow(other.root)) }
    }

    @Test fun unknownEnteredOwnerRequiresObservedLaterDeviceBootAndPreservesWork() = fixture {
        val old = authority().processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        val payloads = payloads(); val state = authority().load(); val before = metadata()
        val blocked = runCatching { authority(previous).processCustody().acquire(working) }.exceptionOrNull()
        assertTrue(blocked is WorkspaceProcessRecoveryRequiredException)
        assertTrue(blocked!!.message!!.contains("Restart the Android device"))
        assertEquals(before, metadata()); assertEquals(1, owners().size)
        repeat(2) { assertNotNull(runCatching { authority(previous).requireNoPendingProcessOwners() }.exceptionOrNull()) }
        authority(current).processCustody().acquire(working).use {
            val replacement = owners().single(); assertEquals(current, replacement.boot)
            old.close(); assertEquals(listOf(replacement), owners())
            assertEquals(payloads, payloads()); assertEquals(state, authority().load())
        }
    }

    @Test fun observationSurvivesFailureBeforeBlockedLaunchReturns() = fixture {
        authority().processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        val before = metadata()
        val stopped = authority(previous) { if (it == "writer-reboot-observation-after-force") throw Interrupted() }
        assertTrue(runCatching { stopped.processCustody().acquire(working) }.exceptionOrNull() is Interrupted)
        assertEquals(before, metadata()); assertNull(owners().single().boot)
        authority(current).requireNoPendingProcessOwners(); assertTrue(owners().isEmpty())
    }

    @Test fun interruptedStagingNeverPublishesProofOrPoisonsRetry() = fixture {
        authority().processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        val stopped = authority(previous) { if (it == "writer-reboot-observation-before-publish") throw Interrupted() }
        assertTrue(runCatching { stopped.processCustody().acquire(working) }.exceptionOrNull() is Interrupted)
        assertTrue(directory.resolve("process-reboot-observations").listFiles().orEmpty().isEmpty())
        // The uncommitted staging file does not prove owners predate this boot.
        assertNotNull(runCatching { authority(current).requireNoPendingProcessOwners() }.exceptionOrNull())
        assertEquals(1, owners().size)
        val later = current.copy(boot = UUID.randomUUID().toString())
        authority(later).requireNoPendingProcessOwners(); assertTrue(owners().isEmpty())
    }

    @Test fun unknownOwnerObservationCannotBeSatisfiedByAnotherDeviceOrProofSource() = fixture {
        authority().processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        assertNotNull(runCatching { authority(previous).requireNoPendingProcessOwners() }.exceptionOrNull())
        for (candidate in listOf(current.copy(device = "b".repeat(64)), null, previous)) {
            assertNotNull(runCatching { authority(candidate).requireNoPendingProcessOwners() }.exceptionOrNull())
            assertEquals(1, owners().size)
        }
        authority(current).requireNoPendingProcessOwners(); assertTrue(owners().isEmpty())
    }

    @Test fun lateUnknownOwnersReceiveTheirOwnObservationWithoutPartialRetirement() = fixture {
        authority().processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        assertNotNull(runCatching { authority(previous).requireNoPendingProcessOwners() }.exceptionOrNull())
        authority().processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        val before = metadata(); val all = owners()
        assertNotNull(runCatching { authority(current).requireNoPendingProcessOwners() }.exceptionOrNull())
        assertEquals(before, metadata()); assertEquals(all, owners())
        authority(current.copy(boot = UUID.randomUUID().toString())).requireNoPendingProcessOwners()
        assertTrue(owners().isEmpty())
    }

    @Test fun changedObservationOwnerNeverGrantsAdmission() = fixture {
        authority().processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        assertNotNull(runCatching { authority(previous).requireNoPendingProcessOwners() }.exceptionOrNull())
        val proof = directory.resolve("process-reboot-observations").listFiles()!!.single()
        proof.writeText(proof.readText().replace(oldRuntime, UUID.randomUUID().toString()))
        val before = metadata()
        assertNotNull(runCatching { authority(current).processCustody().acquire(working) }.exceptionOrNull())
        assertEquals(before, metadata()); assertEquals(1, owners().size)
    }

    @Test fun legacyUnknownOwnerCanRecoverAfterObservedRebootBeforeSchemaMigration() = fixture {
        authority().processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        makeLegacySchema4()
        assertNotNull(runCatching { authority(previous).processCustody().acquire(working) }.exceptionOrNull())
        db().use { assertEquals(4, it.version) }
        authority(current).processCustody().acquire(working).use {
            db().use { assertEquals(6, it.version) }; assertEquals(current, owners().single().boot)
        }
    }

    @Test fun schemaFivePreviousBootProofIsReconciledBeforeLaunchMigration() = fixture {
        authority(previous).processCustody(oldRuntime).acquire(working).close()
        makeLegacySchema4()
        db().use { db ->
            db.beginTransaction()
            try {
                val binding = db.rawQuery("SELECT binding FROM meta", null).use { check(it.moveToFirst()); it.getString(0) }
                LegacyV5ProcessCustodyLedger.enableBootIdentity(db, binding)
                LegacyV5ProcessCustodyLedger.append(db, binding, emptyList(),
                    LegacyV5ProcessCustodyLedger.Owner(UUID.randomUUID().toString(), oldRuntime, previous))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        authority(current).processCustody().acquire(working).use {
            db().use { assertEquals(6, it.version) }; assertEquals(current, owners().single().boot)
        }
    }

    @Test fun tornPublishedObservationCannotProveCleanup() = fixture {
        authority().processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        assertNotNull(runCatching { authority(previous).requireNoPendingProcessOwners() }.exceptionOrNull())
        directory.resolve("process-reboot-observations").listFiles()!!.single().writeText("{\"format\":")
        val before = metadata()
        assertNotNull(runCatching { authority(current).requireNoPendingProcessOwners() }.exceptionOrNull())
        assertEquals(before, metadata()); assertEquals(1, owners().size)
    }

    @Test fun copiedObservationFromAnotherAuthorityCannotAuthorizeRecovery() = fixture {
        authority().processCustody(oldRuntime).acquire(working).also { it.withFactory({}, {}) }
        assertNotNull(runCatching { authority(previous).requireNoPendingProcessOwners() }.exceptionOrNull())
        val other = Fixture()
        try {
            other.prepare()
            other.authority().processCustody(oldRuntime).acquire(other.working).also { it.withFactory({}, {}) }
            val copied = other.directory.resolve("process-reboot-observations").apply { mkdir() }
            directory.resolve("process-reboot-observations").listFiles()!!.single().let { it.copyTo(copied.resolve(it.name)) }
            val before = other.metadata()
            assertNotNull(runCatching { other.authority(current).requireNoPendingProcessOwners() }.exceptionOrNull())
            assertEquals(before, other.metadata()); assertEquals(1, other.owners().size)
        } finally { assertTrue(PathSecurity.deleteTreeNoFollow(other.root)) }
    }
}
