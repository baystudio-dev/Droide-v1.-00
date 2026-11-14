package com.baystudio.droide.core

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MembershipCommandCompletionTest {
    private class Fixture(val app: File, val limit: Int) {
        val disk = FileProjectStore(app)
        val default = DroideProject("default", "Default", File(app, "projects/default").apply { mkdirs() }.canonicalPath)
        val target = DroideProject("stored", "Stored", File(app, "projects/stored").apply { mkdirs() }.canonicalPath)
        val storage = File(app, "explicit-startup").apply { mkdir() }
        val admission = ProjectCatalogAdmission(app, storage, 1_000_000)
        val policy = CanonicalJournalRetention.Policy(limit, 100_000_000)
        lateinit var selection: ProjectCatalogAdmission.UpdateSelection
        lateinit var enrollment: CanonicalFileOperationJournal.Enrollment
        lateinit var store: JournaledProjectStore
        lateinit var manager: ProjectManager
        var hook: (String) -> Unit = {}
        var admissions = 0
        suspend fun start() {
            disk.save(listOf(default, target)); disk.saveActiveId("default")
            enrollment = CanonicalJournalBootstrap(admission.updateSlot, 1_000_000).createWithMembership(
                File(app, ".droide"), ProjectCatalogUpdate.AUTHORITY_ID, admission.updateMembership, EmptyCoroutineContext)
            selection = admission.recordUpdateChoice(enrollment)
            reopen(); store.enableMembershipCommands()
        }
        suspend fun reopen() {
            store = JournaledProjectStore(selection, policy, checkpoint = {
                if (it == "admission-after-commit") admissions++
                hook(it)
            })
            manager = ProjectManager(app, store)
            if (store.inspectRecovery().pending == null) manager.init()
        }
        suspend fun accepted(): CatalogMembershipCommands.Receipt {
            val receipt = manager.restoreMembershipCommand("stored")
            check(receipt.operationId == null)
            return manager.acknowledgeMembershipResult(receipt.token, receipt.outcome!!)
        }
        suspend fun acceptedOperation(): CatalogMembershipCommands.Receipt {
            manager.removeWorkspace("stored")
            val receipt = manager.restoreMembershipCommand("stored")
            check(receipt.operationId != null)
            return manager.acknowledgeMembershipResult(receipt.token, receipt.outcome!!)
        }
        fun <T> db(action: (SQLiteDatabase) -> T): T = SQLiteDatabase.openDatabase(
            File(enrollment.authorityPath, "state.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE).use(action)
        fun state() = db { checkNotNull(CatalogMembershipCommands.read(it)) }
        fun charge() = db { CatalogMembershipCommands.envelopeBytes(it) }
        fun immutableTables(): Map<String, List<List<String?>>> = SQLiteDatabase.openDatabase(
            File(enrollment.authorityPath, "state.sqlite").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            listOf("meta", "operation", "retention", "completed_operation", "membership_operations").associateWith { table ->
                db.rawQuery("SELECT * FROM $table ORDER BY 1", null).use { row ->
                    buildList { while (row.moveToNext()) add((0 until row.columnCount).map { if (row.isNull(it)) null else row.getString(it) }) }
                }
            }
        }
        fun custody(): Map<String, String> = File(enrollment.authorityPath, "custody").walkTopDown().filter { it.isFile }
            .associate { it.name to CanonicalJournalFixture.sha256(it.readBytes()) }
    }
    private fun fixture(limit: Int = 8, action: suspend (Fixture) -> Unit) = runBlocking {
        val app = Files.createTempDirectory("membership-complete-").toFile()
        try { Fixture(app, limit).also { it.start() }.let { action(it) } }
        finally { PathSecurity.deleteTreeNoFollow(app) }
    }
    private suspend fun interrupted(f: Fixture, point: String): CatalogMembershipCommands.Receipt {
        f.manager.removeWorkspace("stored")
        f.hook = { if (it == point) throw IOException("lost phase return") }
        assertNotNull(runCatching { f.manager.restoreMembershipCommand("stored") }.exceptionOrNull())
        f.hook = {}
        return f.store.inspectMembershipCommands().single { it.outcome == null }.also { assertNotNull(it.operationId); assertNull(it.outcome) }
    }
    private suspend fun continueExact(f: Fixture, r: CatalogMembershipCommands.Receipt) = f.manager.continueMembershipPreparation(
        r.token, r.operationId!!, CatalogMembershipCommands.hash(CatalogMembershipCommands.encode(r)))
    private fun custodyFile(f: Fixture, r: CatalogMembershipCommands.Receipt, suffix: String) = File(f.enrollment.authorityPath, "custody/${r.operationId}.$suffix")
    private suspend fun staged(f: Fixture): CatalogMembershipCommands.Receipt = interrupted(f, "publication-stage-after-commit")
    private suspend fun complete(f: Fixture, r: CatalogMembershipCommands.Receipt) = f.manager.completeMembershipCommand(
        r.token, r.operationId!!, CatalogMembershipCommands.admissionEnvelopeHash(r))
    @Test fun stagedCommandCompletesSameOperationWithoutAdoptionOrAcknowledgement() = fixture { f ->
        val r = staged(f); val count = f.admissions; val projects = f.manager.projects.value; val active = f.disk.loadActiveId()
        val result = complete(f, r)
        assertEquals(r.token, result.token); assertEquals(r.operationId, result.operationId); assertNotNull(result.outcome); assertFalse(result.acknowledged)
        assertEquals(count, f.admissions); assertNull(f.store.inspectRecovery().pending)
        assertEquals(1, f.store.inspectRecovery().completed.count { it.id == r.operationId })
        assertEquals(projects, f.manager.projects.value); assertEquals(active, f.disk.loadActiveId())
        assertNotNull(runCatching { f.manager.requireSelectionCertain() }.exceptionOrNull())
        assertFalse(f.disk.load().single { it.id == "stored" }.workspaceRemoved)
    }
    @Test fun completedReadNeverInvokesPublicationAndPreservesActualAckBit() = fixture { f ->
        val r = staged(f); val result = complete(f, r)
        val accepted = f.manager.acknowledgeMembershipResult(result.token, result.outcome!!)
        val tables = f.immutableTables(); val files = f.custody(); val current = f.disk.loadCatalog()
        f.hook = { check(!it.startsWith("retirement-") && !it.startsWith("publication-") && !it.startsWith("completion-archive")) }
        assertEquals(accepted, complete(f, r)); assertTrue(complete(f, r).acknowledged)
        assertEquals(tables, f.immutableTables()); assertEquals(files, f.custody()); assertEquals(current, f.disk.loadCatalog())
    }
    @Test fun lostArchiveReturnReopensAsHistoricalResultWithoutSecondResume() = fixture { f ->
        val r = staged(f); f.hook = { if (it == "completion-archive-after-commit") throw IOException("lost result") }
        assertNotNull(runCatching { complete(f, r) }.exceptionOrNull()); f.hook = {}; f.reopen()
        assertNull(f.store.inspectRecovery().pending); val files = f.custody(); val count = f.admissions
        assertNotNull(complete(f, r).outcome); assertEquals(files, f.custody()); assertEquals(count, f.admissions)
    }
    @Test fun laterOrdinaryPublicationDoesNotTurnHistoricalLookupIntoAdoption() = fixture { f ->
        val r = staged(f); val result = complete(f, r)
        val other = JournaledProjectStore(f.selection, f.policy); val before = other.loadCatalog()
        other.saveCatalog(before.projects.map { if (it.id == "stored") it.copy(workspaceRemoved = true) else it }, before.revision)
        val current = f.disk.loadCatalog(); val projects = f.manager.projects.value
        assertEquals(result, complete(f, r)); assertEquals(current, f.disk.loadCatalog()); assertEquals(projects, f.manager.projects.value)
        assertTrue(f.disk.load().single { it.id == "stored" }.workspaceRemoved)
    }
    @Test fun historicalLookupDoesNotRequireTargetRootLiveness() = fixture { f ->
        val r = staged(f); val result = complete(f, r); val root = File(r.request.rootPath)
        assertTrue(root.renameTo(File(root.parentFile, "retained-target")))
        assertEquals(result, complete(f, r)); assertFalse(root.exists())
    }
    @Test fun wrongIdentityCannotPublish() = fixture { f ->
        val r = staged(f); val current = f.disk.loadCatalog(); val history = f.store.inspectRecovery()
        val hash = CatalogMembershipCommands.admissionEnvelopeHash(r)
        for (args in listOf(Triple(java.util.UUID.randomUUID().toString(), r.operationId!!, hash),
            Triple(r.token, java.util.UUID.randomUUID().toString(), hash), Triple(r.token, r.operationId, "f".repeat(64)))) {
            assertNotNull(runCatching { f.manager.completeMembershipCommand(args.first, args.second, args.third) }.exceptionOrNull())
        }
        assertEquals(current, f.disk.loadCatalog()); assertEquals(history, f.store.inspectRecovery())
    }
    @Test fun earlyPreparationRefusesWithoutImplicitContinuation() = fixture { f ->
        val r = interrupted(f, "admission-after-commit"); val history = f.store.inspectRecovery()
        assertNotNull(runCatching { complete(f, r) }.exceptionOrNull()); assertEquals(history, f.store.inspectRecovery())
        assertFalse(custodyFile(f, r, "before").exists())
    }
    @Test fun laterRetirementPhaseRefusesWithoutRetryingNativeMove() = fixture { f ->
        val r = staged(f); f.hook = { if (it == "retirement-intent-after-commit") throw IOException("interrupted retirement") }
        assertNotNull(runCatching { complete(f, r) }.exceptionOrNull()); f.hook = {}
        val history = f.store.inspectRecovery(); assertEquals(CanonicalFileOperationJournal.Phase.RETIREMENT_PREPARED, history.pending!!.phase)
        val files = f.custody(); assertNotNull(runCatching { complete(f, r) }.exceptionOrNull())
        assertEquals(history, f.store.inspectRecovery()); assertEquals(files, f.custody())
    }
    @Test fun rootReplacementInPreRetirementCallbackRefusesBeforeMove() = fixture { f ->
        val r = staged(f); val catalog = f.disk.loadCatalog()
        f.hook = { if (it == "catalog-update-before-retirement") {
            val root = File(r.request.rootPath); assertTrue(root.renameTo(File(root.parentFile, "old-root"))); assertTrue(root.mkdir())
        } }
        assertNotNull(runCatching { complete(f, r) }.exceptionOrNull()); f.hook = {}
        assertEquals(catalog, f.disk.loadCatalog()); assertFalse(custodyFile(f, r, "retired").exists())
        assertEquals(CanonicalFileOperationJournal.Phase.PUBLICATION_STAGED, f.store.inspectRecovery().pending!!.phase)
    }
    @Test fun cancellationAfterArchiveIsUncertainThenExplicitlyReadable() = fixture { f ->
        val r = staged(f); val job = Job(); f.hook = { if (it == "completion-archive-after-commit") job.cancel() }
        assertTrue(runCatching { kotlinx.coroutines.withContext(job) { complete(f, r) } }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        f.hook = {}; assertNotNull(complete(f, r).outcome); assertFalse(complete(f, r).acknowledged)
    }
    @Test fun fatalBeforePublicationRemainsFatalAndDoesNotAutoResume() = fixture { f ->
        val r = staged(f); val fatal = AssertionError("fatal")
        f.hook = { if (it == "catalog-update-before-retirement") throw fatal }
        val failure = runCatching { complete(f, r) }.exceptionOrNull(); f.hook = {}
        assertTrue(failure is Error); assertTrue(generateSequence(failure) { it.cause }.any { it === fatal })
        assertEquals(CanonicalFileOperationJournal.Phase.PUBLICATION_STAGED, f.store.inspectRecovery().pending!!.phase)
    }
    @Test fun admissionCommitmentSurvivesCompletionAndAckButBindsAllFrozenFields() = fixture { f ->
        val r = staged(f); val hash = CatalogMembershipCommands.admissionEnvelopeHash(r)
        assertEquals(hash, CatalogMembershipCommands.hash(CatalogMembershipCommands.encode(r)))
        val completed = complete(f, r); val accepted = f.manager.acknowledgeMembershipResult(completed.token, completed.outcome!!)
        assertEquals(hash, CatalogMembershipCommands.admissionEnvelopeHash(completed)); assertEquals(hash, CatalogMembershipCommands.admissionEnvelopeHash(accepted))
        val altered = listOf(r.copy(token = java.util.UUID.randomUUID().toString()), r.copy(authority = "f".repeat(64)),
            r.copy(operationId = java.util.UUID.randomUUID().toString()), r.copy(frozenIntent = r.frozenIntent + " "),
            r.copy(request = r.request.copy(targetId = "other")), r.copy(request = r.request.copy(rootPath = r.request.rootPath + "x")),
            r.copy(request = r.request.copy(rootKey = "other")), r.copy(request = r.request.copy(beforeBytes = r.request.beforeBytes + 1)),
            r.copy(request = r.request.copy(beforeHash = "f".repeat(64))), r.copy(request = r.request.copy(proposal = r.request.proposal + " ")))
        altered.forEach { assertNotEquals(hash, CatalogMembershipCommands.admissionEnvelopeHash(it)) }
        assertNotNull(runCatching { CatalogMembershipCommands.admissionEnvelopeHash(r.copy(recipient = "other")) }.exceptionOrNull())
    }
    @Test fun noChangeAndCompactedOutcomesCannotBecomePublicationRequests() = fixture { f ->
        val noop = f.accepted(); assertNotNull(runCatching { CatalogMembershipCommands.admissionEnvelopeHash(noop) }.exceptionOrNull())
        val r = staged(f); val completed = complete(f, r); f.manager.acknowledgeMembershipResult(r.token, completed.outcome!!)
        f.store.enableMembershipCompaction(); f.manager.expireAcceptedOperation(r.token, completed.outcome)
        val current = f.disk.loadCatalog(); assertNotNull(runCatching { complete(f, r) }.exceptionOrNull()); assertEquals(current, f.disk.loadCatalog())
    }
    @Test fun historicalCustodySwapAfterReviewRefusesRatherThanRebasingProof() = fixture { f ->
        val r = staged(f); complete(f, r)
        f.hook = { if (it == "membership-completed-result-reviewed") {
            val file = custodyFile(f, r, "after"); val bytes = file.readBytes(); assertTrue(file.renameTo(File(f.app, "old-after"))); file.writeBytes(bytes)
        } }
        assertNotNull(runCatching { complete(f, r) }.exceptionOrNull()); f.hook = {}
    }
    @Test fun explicitPreparationThenCompletionBridgesLostAdmissionWithoutNewCommand() = fixture { f ->
        val r = interrupted(f, "admission-after-commit"); val count = f.admissions
        continueExact(f, r); val result = complete(f, r)
        assertEquals(r.token, result.token); assertEquals(r.operationId, result.operationId)
        assertNotNull(result.outcome); assertFalse(result.acknowledged); assertEquals(count, f.admissions)
    }
    @Test fun sameByteOriginalCatalogReplacementRefusesBeforeRetirement() = fixture { f ->
        val r = staged(f); val catalog = File(f.app, ".droide/projects.json"); val bytes = catalog.readBytes()
        assertTrue(catalog.renameTo(File(f.app, "old-catalog"))); catalog.writeBytes(bytes)
        assertNotNull(runCatching { complete(f, r) }.exceptionOrNull())
        assertEquals(CanonicalFileOperationJournal.Phase.PUBLICATION_STAGED, f.store.inspectRecovery().pending!!.phase)
        assertFalse(custodyFile(f, r, "retired").exists())
    }
    @Test fun historicalResultDoesNotResumeAnotherPendingOrdinaryOperation() = fixture { f ->
        val r = staged(f); val result = complete(f, r); val updater = f.selection.guardedUpdate(f.policy, EmptyCoroutineContext) {}
        val current = f.store.loadCatalog(); updater.prepare(current.projects.map { it.copy(name = it.name + "!") }, current.revision)
        val history = f.store.inspectRecovery(); val files = f.custody()
        assertNotEquals(r.operationId, history.pending!!.id); assertEquals(result, complete(f, r))
        assertEquals(history, f.store.inspectRecovery()); assertEquals(files, f.custody())
    }
    @Test fun schemaFourStagedCommandCompletesWithoutImplicitCompaction() = fixture { f ->
        f.store.enableMembershipCompaction(); val r = staged(f); val result = complete(f, r)
        assertEquals(4, f.db { it.version }); assertTrue(f.state().compacted.isEmpty())
        assertEquals(listOf(result), f.store.inspectMembershipCommands()); assertFalse(result.acknowledged)
    }
    @Test fun cancellationBeforeRetirementLeavesStagedReceiptAndNoMove() = fixture { f ->
        val r = staged(f); val job = Job(); val catalog = f.disk.loadCatalog(); val files = f.custody()
        f.hook = { if (it == "catalog-update-before-retirement") job.cancel() }
        assertTrue(runCatching { kotlinx.coroutines.withContext(job) { complete(f, r) } }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        f.hook = {}; assertEquals(catalog, f.disk.loadCatalog()); assertEquals(files, f.custody())
        assertEquals(CanonicalFileOperationJournal.Phase.PUBLICATION_STAGED, f.store.inspectRecovery().pending!!.phase)
    }
    @Test fun changedAckDuringHistoricalReviewRefusesOriginalProofInsteadOfRebasing() = fixture { f ->
        val r = staged(f); val result = complete(f, r)
        f.hook = { if (it == "membership-completed-result-reviewed") f.db { db ->
            // Synthetic concurrent-state fixture: valid atomic acknowledgement, outside the normal shared mutex.
            db.beginTransaction()
            try { CatalogMembershipCommands.acknowledge(db, result.token, result.outcome!!); db.setTransactionSuccessful() }
            finally { db.endTransaction() }
        } }
        assertNotNull(runCatching { complete(f, r) }.exceptionOrNull()); f.hook = {}
        assertTrue(complete(f, r).acknowledged)
    }
    @Test fun finalCompletionCallbackCustodySwapCannotReturnSuccess() = fixture { f ->
        val r = staged(f)
        f.hook = { if (it == "membership-command-completed-before-return") {
            val file = custodyFile(f, r, "after"); val bytes = file.readBytes()
            assertTrue(file.renameTo(File(f.app, "late-original-after"))); file.writeBytes(bytes)
        } }
        assertNotNull(runCatching { complete(f, r) }.exceptionOrNull()); f.hook = {}
        assertNull(f.store.inspectRecovery().pending)
        assertTrue(File(f.app, "late-original-after").isFile)
        assertNotNull(runCatching { complete(f, r) }.exceptionOrNull())
    }
    @Test fun fatalAfterArchiveStaysFatalUntilSeparateExplicitLookup() = fixture { f ->
        val r = staged(f); val fatal = AssertionError("archived fatal")
        f.hook = { if (it == "completion-archive-after-commit") throw fatal }
        val failure = runCatching { complete(f, r) }.exceptionOrNull(); f.hook = {}
        assertTrue(failure is Error); assertTrue(generateSequence(failure) { it.cause }.any { it === fatal })
        assertNull(f.store.inspectRecovery().pending); assertNotNull(complete(f, r).outcome)
    }
}
