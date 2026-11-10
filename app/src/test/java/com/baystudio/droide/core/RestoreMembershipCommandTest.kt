package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RestoreMembershipCommandTest {
    private class Fixture(val app: File) {
        val disk = FileProjectStore(app)
        val default = DroideProject("default", "Default", File(app, "projects/default").apply { mkdirs() }.canonicalPath)
        val target = DroideProject("stored", "Stored", File(app, "projects/stored").apply { mkdirs() }.canonicalPath, workspaceRemoved = true)
        val storage = File(app, "explicit-startup").apply { mkdir() }
        val admission = ProjectCatalogAdmission(app, storage, 1_000_000, checkpoint = { admissionHook(it) })
        val policy = CanonicalJournalRetention.Policy(8, 100_000_000)
        lateinit var selection: ProjectCatalogAdmission.UpdateSelection
        lateinit var enrollment: CanonicalFileOperationJournal.Enrollment
        lateinit var store: JournaledProjectStore
        lateinit var manager: ProjectManager
        var hook: (String) -> Unit = {}
        var admissionHook: (String) -> Unit = {}
        var admissions = 0
        suspend fun start(enable: Boolean = true) {
            disk.save(listOf(default, target)); disk.saveActiveId("default")
            enrollment = CanonicalJournalBootstrap(admission.updateSlot, 1_000_000).createWithMembership(
                File(app, ".droide"), ProjectCatalogUpdate.AUTHORITY_ID, admission.updateMembership, EmptyCoroutineContext)
            selection = admission.recordUpdateChoice(enrollment)
            reopen()
            if (enable) store.enableMembershipCommands()
        }
        suspend fun reopen() {
            store = JournaledProjectStore(selection, policy, checkpoint = {
                if (it == "admission-after-commit") admissions++
                hook(it)
            })
            manager = ProjectManager(app, store)
            // Pending commands intentionally prevent manager initialization. Discovery is store-owned and
            // does not loadReady.
            if (store.inspectRecovery().pending == null) manager.init()
        }
    }
    private fun fixture(enable: Boolean = true, action: suspend (Fixture) -> Unit) = runBlocking {
        val app = Files.createTempDirectory("restore-membership-").toFile()
        try { Fixture(app).also { it.start(enable) }.let { action(it) } }
        finally { PathSecurity.deleteTreeNoFollow(app) }
    }
    @Test fun realManagerRestoreAndExplicitAcceptanceDoNotSelectOrDelete() = fixture { f ->
        val payload = File(f.target.rootPath, "keep.txt").apply { writeText("kept") }
        val active = File(f.app, ".droide/active_project.txt").readBytes()
        val receipt = f.manager.restoreMembershipCommand("stored")
        assertFalse(receipt.needsReview); assertFalse(receipt.acknowledged)
        assertNotNull(receipt.operationId); assertEquals(1, f.admissions)
        assertFalse(f.manager.projects.value.single { it.id == "stored" }.workspaceRemoved)
        assertEquals("default", f.manager.activeId.value)
        assertArrayEquals(active, File(f.app, ".droide/active_project.txt").readBytes())
        assertEquals("kept", payload.readText())
        val accepted = f.manager.acknowledgeMembershipResult(receipt.token, receipt.outcome!!)
        assertTrue(accepted.acknowledged)
        assertEquals(accepted, f.manager.acknowledgeMembershipResult(receipt.token, receipt.outcome))
        assertEquals(1, f.store.inspectRecovery().completed.size)
    }
    @Test fun noChangeCompletesAtomicallyWithoutOperationAndStillNeedsAcceptance() = fixture { f ->
        f.manager.restoreWorkspace("stored")
        val before = f.store.inspectRecovery()
        val receipt = f.manager.restoreMembershipCommand("stored")
        assertNull(receipt.operationId); assertFalse(receipt.needsReview)
        assertEquals(before, f.store.inspectRecovery())
        assertNotNull(runCatching { f.manager.restoreMembershipCommand("stored") }.exceptionOrNull())
        assertEquals(listOf(receipt), f.manager.discoverMembershipCommands())
    }
    @Test fun lostAdmissionReturnIsDiscoverableWithoutSecondOperationOrReplay() = fixture { f ->
        f.hook = { if (it == "admission-after-commit") throw IOException("lost admission") }
        assertNotNull(runCatching { f.manager.restoreMembershipCommand("stored") }.exceptionOrNull())
        f.hook = {}; f.reopen()
        val receipt = f.store.inspectMembershipCommands().single()
        assertTrue(receipt.needsReview); assertNotNull(receipt.operationId)
        assertEquals(receipt.operationId, f.store.inspectRecovery().pending!!.id)
        assertEquals(CanonicalFileOperationJournal.Phase.PREPARED, f.store.inspectRecovery().pending!!.phase)
        assertTrue(f.disk.load().single { it.id == "stored" }.workspaceRemoved)
        assertTrue(receipt.request.proposal.contains("stored")); assertEquals(1, f.admissions)
        assertNotNull(runCatching { f.store.acknowledgeMembership(receipt.token, "0".repeat(64)) }.exceptionOrNull())
    }
    @Test fun lostCompletedReturnUsesExactReceiptAndSurvivesRecreation() = fixture { f ->
        f.hook = { if (it == "catalog-update-completed") throw IOException("lost completed return") }
        val receipt = f.manager.restoreMembershipCommand("stored")
        f.hook = {}; f.reopen()
        assertEquals(receipt, f.manager.discoverMembershipCommands().single())
        assertEquals(1, f.admissions)
        assertNotNull(runCatching { f.manager.acknowledgeMembershipResult(receipt.token, "f".repeat(64)) }.exceptionOrNull())
    }
    @Test fun failedAdmissionRollsBackCommandAndPendingTogether() = fixture { f ->
        f.hook = { if (it == "admission-before-commit") throw IOException("before commit") }
        assertNotNull(runCatching { f.manager.restoreMembershipCommand("stored") }.exceptionOrNull())
        f.hook = {}
        assertTrue(f.store.inspectMembershipCommands().isEmpty()); assertNull(f.store.inspectRecovery().pending)
        f.reopen()
        assertFalse(f.manager.restoreMembershipCommand("stored").needsReview)
    }
    @Test fun explicitAcceptanceLostReturnIsRepeatableWithoutSecondPublication() = fixture { f ->
        val r = f.manager.restoreMembershipCommand("stored")
        f.hook = { if (it == "membership-ack-after-commit") throw IOException("lost ack") }
        assertNotNull(runCatching { f.manager.acknowledgeMembershipResult(r.token, r.outcome!!) }.exceptionOrNull())
        f.hook = {}; f.reopen()
        assertTrue(f.manager.discoverMembershipCommands().single().acknowledged)
        assertTrue(f.manager.acknowledgeMembershipResult(r.token, r.outcome!!).acknowledged)
        assertEquals(1, f.admissions)
    }
    @Test fun acknowledgementPrecommitFailureKeepsUnresolvedSlot() = fixture { f ->
        val r = f.manager.restoreMembershipCommand("stored")
        f.hook = { if (it == "membership-ack-before-commit") throw IOException("failed ack") }
        assertNotNull(runCatching { f.manager.acknowledgeMembershipResult(r.token, r.outcome!!) }.exceptionOrNull())
        f.hook = {}
        assertFalse(f.manager.discoverMembershipCommands().single().acknowledged)
        assertNotNull(runCatching { f.manager.restoreMembershipCommand("stored") }.exceptionOrNull())
    }
    @Test fun historicalCompletionNeverReappliesLaterRemovedMembership() = fixture { f ->
        val r = f.manager.restoreMembershipCommand("stored")
        f.manager.acknowledgeMembershipResult(r.token, r.outcome!!)
        f.manager.removeWorkspace("stored")
        assertTrue(f.manager.projects.value.single { it.id == "stored" }.workspaceRemoved)
        assertEquals(r.copy(acknowledged = true), f.manager.discoverMembershipCommands().single())
        assertTrue(f.manager.projects.value.single { it.id == "stored" }.workspaceRemoved)
        assertEquals(2, f.store.inspectRecovery().completed.size)
    }
    @Test fun unknownTokenCannotStartOperation() = fixture { f ->
        assertNotNull(runCatching { f.manager.acknowledgeMembershipResult(java.util.UUID.randomUUID().toString(), "0".repeat(64)) }.exceptionOrNull())
        assertTrue(f.store.inspectMembershipCommands().isEmpty()); assertEquals(0, f.admissions)
    }
    @Test fun omittedCommandRowCannotFreeSlotOrPermitMutation() = fixture { f ->
        f.manager.restoreMembershipCommand("stored")
        SQLiteDatabase.openDatabase(File(f.enrollment.authorityPath, "state.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DELETE FROM membership_commands")
        }
        assertNotNull(runCatching { f.store.inspectMembershipCommands() }.exceptionOrNull())
        assertNotNull(runCatching { f.store.loadCatalog() }.exceptionOrNull())
        assertEquals(1, f.admissions)
    }
    @Test fun omittedClassificationIsRejectedEvenForOrdinaryOperations() = fixture { f ->
        f.manager.restoreWorkspace("stored")
        SQLiteDatabase.openDatabase(File(f.enrollment.authorityPath, "state.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DELETE FROM membership_operations")
        }
        assertNotNull(runCatching { f.store.inspectRecovery() }.exceptionOrNull())
    }
    @Test fun rootReplacementDuringAckRollsBackAcceptance() = fixture { f ->
        val r = f.manager.restoreMembershipCommand("stored")
        f.hook = { if (it == "membership-ack-before-commit") {
            val root = File(f.target.rootPath)
            check(root.renameTo(File(root.parentFile, "stored-original"))); check(root.mkdir())
        } }
        assertNotNull(runCatching { f.manager.acknowledgeMembershipResult(r.token, r.outcome!!) }.exceptionOrNull())
        f.hook = {}
        assertFalse(f.store.inspectMembershipCommands().single().acknowledged)
    }
    @Test fun boundedNoChangeCapacityNeverCreditsCustodyOrEvictsResults() = fixture { f ->
        f.manager.restoreWorkspace("stored")
        val original = f.store.inspectRecovery()
        repeat(8) {
            val r = f.manager.restoreMembershipCommand("stored")
            f.manager.acknowledgeMembershipResult(r.token, r.outcome!!)
        }
        assertNotNull(runCatching { f.manager.restoreMembershipCommand("stored") }.exceptionOrNull())
        assertEquals(8, f.store.inspectMembershipCommands().size)
        assertEquals(original, f.store.inspectRecovery())
    }
    @Test fun actualJobCancellationAfterAdmissionRetainsDiscoverableNeedsReview() = fixture { f ->
        kotlinx.coroutines.coroutineScope {
            lateinit var task: Job
            f.hook = { if (it == "admission-after-commit") task.cancel() }
            task = launch(start = CoroutineStart.LAZY) { f.manager.restoreMembershipCommand("stored") }
            task.start(); task.join(); assertTrue(task.isCancelled)
        }
        f.hook = {}; f.reopen()
        assertTrue(f.store.inspectMembershipCommands().single().needsReview)
        assertEquals(1, f.admissions)
    }
    @Test fun actualJobCancellationBeforeAdmissionCommitRollsBackBothRows() = fixture { f ->
        kotlinx.coroutines.coroutineScope {
            lateinit var task: Job
            f.hook = { if (it == "admission-before-commit") task.cancel() }
            task = launch(start = CoroutineStart.LAZY) { f.manager.restoreMembershipCommand("stored") }
            task.start(); task.join(); assertTrue(task.isCancelled)
        }
        f.hook = {}; f.reopen()
        assertTrue(f.store.inspectMembershipCommands().isEmpty()); assertNull(f.store.inspectRecovery().pending)
    }
    @Test fun fatalPublicationErrorDoesNotAutoAcknowledge() = fixture { f ->
        val fatal = AssertionError("fatal after archive")
        f.hook = { if (it == "completion-archive-after-commit") throw fatal }
        val failure = runCatching { f.manager.restoreMembershipCommand("stored") }.exceptionOrNull()
        assertTrue(failure is Error)
        f.hook = {}; f.reopen()
        val r = f.store.inspectMembershipCommands().single()
        assertFalse(r.needsReview); assertFalse(r.acknowledged); assertEquals(1, f.admissions)
    }
    private fun partialPreparation(point: String) = fixture { f ->
        f.hook = { if (it == point) throw IOException(point) }
        assertNotNull(runCatching { f.manager.restoreMembershipCommand("stored") }.exceptionOrNull())
        f.hook = {}; f.reopen()
        val before = f.store.inspectRecovery()
        val r = f.store.inspectMembershipCommands().single()
        assertTrue(r.needsReview)
        assertEquals(before, f.store.inspectRecovery()); assertEquals(1, f.admissions)
    }
    @Test fun capturedBeforeReturnLossDoesNotReplayPreparation() = partialPreparation("snapshot-after-commit")
    @Test fun capturedProposalReturnLossDoesNotReplayPreparation() = partialPreparation("proposal-after-commit")
    @Test fun stagedProposalReturnLossDoesNotResumeImplicitly() = partialPreparation("publication-stage-after-commit")

    @Test fun realSecondStorePublicationBeforeAckPreservesHistoricalResultWithoutAdoption() = fixture { f ->
        val r = f.manager.restoreMembershipCommand("stored")
        val other = JournaledProjectStore(f.selection, f.policy)
        val current = other.loadCatalog()
        val updated = current.projects.map { if (it.id == "stored") it.copy(name = "Other session") else it }
        other.saveCatalog(updated, current.revision)
        val beforeAck = other.loadCatalog()
        val accepted = f.manager.acknowledgeMembershipResult(r.token, r.outcome!!)
        assertTrue(accepted.acknowledged)
        assertEquals(beforeAck, other.loadCatalog())
        assertEquals("Stored", f.manager.projects.value.single { it.id == "stored" }.name)
        assertNotNull(runCatching { f.manager.restoreMembershipCommand("stored") }.exceptionOrNull())
        f.reopen()
        val noChange = f.manager.restoreMembershipCommand("stored")
        assertNull(noChange.operationId); assertNotEquals(r.token, noChange.token)
        assertEquals(2, f.store.inspectRecovery().completed.size)
    }
    @Test fun lateSecondStorePublicationBlocksManagerWithoutPublishingStaleMemory() = fixture { f ->
        val other = JournaledProjectStore(f.selection, f.policy)
        var once = true
        f.hook = { if (it == "membership-before-return" && once) {
            once = false
            runBlocking {
                val current = other.loadCatalog()
                other.saveCatalog(current.projects.map { p -> if (p.id == "stored") p.copy(name = "Late") else p }, current.revision)
            }
        } }
        assertNotNull(runCatching { f.manager.restoreMembershipCommand("stored") }.exceptionOrNull())
        f.hook = {}
        assertTrue(f.manager.projects.value.single { it.id == "stored" }.workspaceRemoved)
        assertNotNull(runCatching { f.manager.requireSelectionCertain() }.exceptionOrNull())
        val r = f.manager.discoverMembershipCommands().single()
        f.manager.acknowledgeMembershipResult(r.token, r.outcome!!)
        assertNotNull(runCatching { f.manager.requireSelectionCertain() }.exceptionOrNull())
        assertEquals("Late", f.disk.load().single { it.id == "stored" }.name)
    }
    @Test fun explicitMigrationPreservesLegacyCompletedReceiptAndCanBeRepeated() = fixture(false) { f ->
        f.manager.restoreWorkspace("stored")
        val before = f.store.inspectRecovery()
        f.store.enableMembershipCommands(); f.store.enableMembershipCommands()
        assertEquals(before, f.store.inspectRecovery())
        assertTrue(f.store.inspectMembershipCommands().isEmpty())
        assertNull(f.manager.restoreMembershipCommand("stored").operationId)
    }
    @Test fun pendingLegacyOperationRefusesMigrationWithoutAssigningCommand() = fixture(false) { f ->
        f.hook = { if (it == "admission-after-commit") throw IOException("legacy interrupted") }
        assertNotNull(runCatching { f.manager.restoreWorkspace("stored") }.exceptionOrNull())
        f.hook = {}
        val before = f.store.inspectRecovery()
        assertNotNull(runCatching { f.store.enableMembershipCommands() }.exceptionOrNull())
        assertEquals(before, f.store.inspectRecovery())
        assertNotNull(runCatching { f.store.inspectMembershipCommands() }.exceptionOrNull())
    }
    @Test fun migrationPrecommitFailureLeavesLegacyFormatAndCanRetry() = fixture(false) { f ->
        f.hook = { if (it == "membership-migration-before-commit") throw IOException("migration interrupted") }
        assertNotNull(runCatching { f.store.enableMembershipCommands() }.exceptionOrNull())
        f.hook = {}
        SQLiteDatabase.openDatabase(File(f.enrollment.authorityPath, "state.sqlite").path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(2, it.version)
            it.rawQuery("SELECT count(*) FROM sqlite_master WHERE name='membership_commands'", null).use { row ->
                assertTrue(row.moveToFirst()); assertEquals(0, row.getInt(0))
            }
        }
        f.store.enableMembershipCommands()
        assertFalse(f.manager.restoreMembershipCommand("stored").needsReview)
    }
    @Test fun rootSubstitutionAtAdmissionCheckpointRollsBackCommandAndOperation() = fixture { f ->
        f.hook = { if (it == "admission-before-commit") {
            val root = File(f.target.rootPath); check(root.renameTo(File(root.parentFile, "old-stored"))); check(root.mkdir())
        } }
        assertNotNull(runCatching { f.manager.restoreMembershipCommand("stored") }.exceptionOrNull())
        f.hook = {}
        assertTrue(f.store.inspectMembershipCommands().isEmpty()); assertNull(f.store.inspectRecovery().pending)
    }
    @Test fun rootSubstitutionAtNoChangeCheckpointRollsBackCommand() = fixture { f ->
        f.manager.restoreWorkspace("stored")
        f.hook = { if (it == "membership-no-change-before-commit") {
            val root = File(f.target.rootPath); check(root.renameTo(File(root.parentFile, "old-stored"))); check(root.mkdir())
        } }
        assertNotNull(runCatching { f.manager.restoreMembershipCommand("stored") }.exceptionOrNull())
        f.hook = {}; assertTrue(f.store.inspectMembershipCommands().isEmpty())
    }
    @Test fun serializedCompletedRowHeadroomIsRequiredBeforeAdmission() {
        val db = SQLiteDatabase.create(null)
        try {
            db.version = 2; CatalogMembershipCommands.enable(db, emptyList())
            val id = java.util.UUID.randomUUID().toString()
            val request = CatalogMembershipCommands.Request("stored", "/projects/stored", "key", 1, "a".repeat(64), "x")
            val initial = CatalogMembershipCommands.Receipt(java.util.UUID.randomUUID().toString(), "b".repeat(64), request, id, "intent", null)
            val overhead = CatalogMembershipCommands.encode(initial).toByteArray().size - 1
            val nearLimit = initial.copy(request = request.copy(proposal = "x".repeat(2 * 1024 * 1024 - overhead)))
            assertEquals(2 * 1024 * 1024, CatalogMembershipCommands.encode(nearLimit).toByteArray().size)
            assertNotNull(runCatching { CatalogMembershipCommands.admit(db, id, nearLimit, 8) }.exceptionOrNull())
            assertTrue(CatalogMembershipCommands.read(db)!!.receipts.isEmpty())
        } finally { db.close() }
    }

    @Test fun cancelledAcknowledgementKeepsCompletedResultAndNoAutomaticAcceptance() = fixture { f ->
        val r = f.manager.restoreMembershipCommand("stored")
        kotlinx.coroutines.coroutineScope {
            lateinit var task: Job
            f.hook = { if (it == "membership-ack-before-commit") task.cancel() }
            task = launch(start = CoroutineStart.LAZY) { f.manager.acknowledgeMembershipResult(r.token, r.outcome!!) }
            task.start(); task.join(); assertTrue(task.isCancelled)
        }
        f.hook = {}; f.reopen()
        assertEquals(r, f.manager.discoverMembershipCommands().single())
    }
    @Test fun cancellationBeforeManagerReturnKeepsResultButDoesNotPublishSuccess() = fixture { f ->
        kotlinx.coroutines.coroutineScope {
            lateinit var task: Job
            f.hook = { if (it == "membership-before-return") task.cancel() }
            task = launch(start = CoroutineStart.LAZY) { f.manager.restoreMembershipCommand("stored") }
            task.start(); task.join(); assertTrue(task.isCancelled)
        }
        f.hook = {}
        assertTrue(f.manager.projects.value.single { it.id == "stored" }.workspaceRemoved)
        assertNotNull(runCatching { f.manager.requireSelectionCertain() }.exceptionOrNull())
        val r = f.manager.discoverMembershipCommands().single()
        assertFalse(r.needsReview); assertFalse(r.acknowledged)
    }
    @Test fun externalStagingConfigurationIsExplicitlyUnsupportedNotSilentlyIgnored() = fixture(false) { f ->
        var called = false
        val custom = JournaledProjectStore(f.selection, f.policy, stageProposal = { called = true; error("not invoked") })
        assertNotNull(runCatching { custom.enableMembershipCommands() }.exceptionOrNull())
        assertFalse(called)
        assertNotNull(runCatching { f.store.inspectMembershipCommands() }.exceptionOrNull())
    }
    @Test fun malformedRecipientAndUnicodeAreRejectedBeforeCommandAdmission() {
        val db = SQLiteDatabase.create(null)
        try {
            db.version = 2; CatalogMembershipCommands.enable(db, emptyList())
            val initial = CatalogMembershipCommands.Receipt(java.util.UUID.randomUUID().toString(), "b".repeat(64),
                CatalogMembershipCommands.Request("stored", "/projects/stored", "key", 1, "a".repeat(64), "x"), null, null, "c".repeat(64))
            assertNotNull(runCatching { CatalogMembershipCommands.admit(db, null, initial.copy(recipient = "human-ui"), 8) }.exceptionOrNull())
            assertNotNull(runCatching { CatalogMembershipCommands.admit(db, null, initial.copy(request = initial.request.copy(targetId = "\uD800")), 8) }.exceptionOrNull())
            assertTrue(CatalogMembershipCommands.read(db)!!.receipts.isEmpty())
        } finally { db.close() }
    }

    @Test fun secondStoreWriterCannotCrossMigrationAndCanRetryAfterDrain() = fixture(false) { f ->
        val current = f.store.loadCatalog()
        val other = JournaledProjectStore(f.selection, f.policy)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        f.hook = { if (it == "membership-migration-before-commit") {
            entered.countDown(); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
        } }
        kotlinx.coroutines.coroutineScope {
            val migration = launch { f.store.enableMembershipCommands() }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { check(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
            val writer = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { other.saveCatalog(current.projects.map { if (it.id == "stored") it.copy(name = "After migration") else it }, current.revision) }
            }
            try { assertEquals("Stored", f.disk.load().single { it.id == "stored" }.name) }
            finally { release.countDown() }
            migration.join()
            val outcome = writer.await()
            if (outcome.isFailure) {
                assertEquals("Stored", f.disk.load().single { it.id == "stored" }.name)
                other.saveCatalog(current.projects.map { if (it.id == "stored") it.copy(name = "After migration") else it }, current.revision)
            }
        }
        f.hook = {}
        assertEquals("After migration", other.load().single { it.id == "stored" }.name)
        assertTrue(other.inspectMembershipCommands().isEmpty()); assertEquals(1, other.inspectRecovery().completed.size)
    }

    @Test fun fatalPostStoreReadRemainsExactPrimaryAndMarksManagerUncertain() = fixture { f ->
        val fatal = AssertionError("fatal manager post-store read")
        var returnedBoundary = false
        var bindingReads = 0
        f.hook = { if (it == "membership-before-return") returnedBoundary = true }

        f.admissionHook = { if (returnedBoundary && it == "choice-read" && ++bindingReads == 3) throw fatal }
        val failure = runCatching { f.manager.restoreMembershipCommand("stored") }.exceptionOrNull()
        assertSame(fatal, failure); assertEquals(3, bindingReads)
        f.hook = {}; f.admissionHook = {}
        assertTrue(f.manager.projects.value.single { it.id == "stored" }.workspaceRemoved)
        assertNotNull(runCatching { f.manager.requireSelectionCertain() }.exceptionOrNull())
        assertFalse(f.manager.discoverMembershipCommands().single().needsReview)
    }

}
