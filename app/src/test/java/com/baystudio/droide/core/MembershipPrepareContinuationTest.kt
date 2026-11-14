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
class MembershipPrepareContinuationTest {
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
        val app = Files.createTempDirectory("membership-continue-").toFile()
        try { Fixture(app, limit).also { it.start() }.let { action(it) } }
        finally { PathSecurity.deleteTreeNoFollow(app) }
    }
    private suspend fun interrupted(f: Fixture, point: String): CatalogMembershipCommands.Receipt {
        f.manager.removeWorkspace("stored")
        f.hook = { if (it == point) throw IOException("lost phase return") }
        assertNotNull(runCatching { f.manager.restoreMembershipCommand("stored") }.exceptionOrNull())
        f.hook = {}
        return f.store.inspectMembershipCommands().single().also { assertNotNull(it.operationId); assertNull(it.outcome) }
    }
    private suspend fun continueExact(f: Fixture, r: CatalogMembershipCommands.Receipt) = f.manager.continueMembershipPreparation(
        r.token, r.operationId!!, CatalogMembershipCommands.hash(CatalogMembershipCommands.encode(r)))
    private fun custodyFile(f: Fixture, r: CatalogMembershipCommands.Receipt, suffix: String) = File(f.enrollment.authorityPath, "custody/${r.operationId}.$suffix")
    @Test fun lostAdmissionReturnExplicitlyStagesSameCommandWithoutPublishing() = fixture { f ->
        val r = interrupted(f, "admission-after-commit")
        val before = f.disk.loadCatalog(); val active = f.disk.loadActiveId(); val history = f.store.inspectRecovery(); val admissions = f.admissions
        val prepared = continueExact(f, r)
        assertEquals(r.operationId, prepared.operationId)
        assertEquals(CanonicalFileOperationJournal.Phase.PUBLICATION_STAGED, f.store.inspectRecovery().pending!!.phase)
        assertEquals(history.completed, f.store.inspectRecovery().completed)
        assertEquals(history.retentionUsage, f.store.inspectRecovery().retentionUsage)
        assertEquals(before, f.disk.loadCatalog()); assertEquals(active, f.disk.loadActiveId())
        assertEquals(listOf(r), f.store.inspectMembershipCommands()); assertEquals(admissions, f.admissions)
        assertNotNull(runCatching { f.manager.requireSelectionCertain() }.exceptionOrNull()) // no guard clearing
    }
    @Test fun durableSnapshotReturnLossContinuesWithoutReplacingRecordedCopy() = fixture { f ->
        val r = interrupted(f, "snapshot-after-commit"); val snapshot = f.store.inspectRecovery().pending!!.snapshot
        f.reopen(); continueExact(f, r)
        assertEquals(snapshot, f.store.inspectRecovery().pending!!.snapshot)
        assertEquals(CanonicalFileOperationJournal.Phase.PUBLICATION_STAGED, f.store.inspectRecovery().pending!!.phase)
    }
    @Test fun durableProposalReturnLossContinuesExactProposal() = fixture { f ->
        val r = interrupted(f, "proposal-after-commit"); val proposal = f.store.inspectRecovery().pending!!.proposalCopy
        continueExact(f, r); assertEquals(proposal, f.store.inspectRecovery().pending!!.proposalCopy)
        assertEquals(r.request.proposal, custodyFile(f, r, "after").readText())
    }
    @Test fun alreadyStagedOnlyVerifiesAndNeverPublishes() = fixture { f ->
        val r = interrupted(f, "publication-stage-after-commit"); val history = f.store.inspectRecovery(); val files = f.custody(); val catalog = f.disk.loadCatalog()
        continueExact(f, r); continueExact(f, r)
        assertEquals(history, f.store.inspectRecovery()); assertEquals(files, f.custody()); assertEquals(catalog, f.disk.loadCatalog())
    }
    @Test fun unrecordedCompleteSnapshotIsPreservedAndRefusesBeforeOtherWrites() = fixture { f ->
        val r = interrupted(f, "snapshot-before-commit"); val bytes = custodyFile(f, r, "before").readBytes(); val history = f.store.inspectRecovery()
        assertNotNull(runCatching { continueExact(f, r) }.exceptionOrNull())
        assertArrayEquals(bytes, custodyFile(f, r, "before").readBytes()); assertFalse(custodyFile(f, r, "after").exists())
        assertEquals(history, f.store.inspectRecovery())
    }
    @Test fun unrelatedLaterSlotBlocksBeforeSnapshotCreation() = fixture { f ->
        val r = interrupted(f, "admission-after-commit"); custodyFile(f, r, "after").writeText(r.request.proposal)
        val history = f.store.inspectRecovery()
        assertNotNull(runCatching { continueExact(f, r) }.exceptionOrNull())
        assertFalse(custodyFile(f, r, "before").exists()); assertEquals(r.request.proposal, custodyFile(f, r, "after").readText())
        assertEquals(history, f.store.inspectRecovery())
    }
    @Test fun retiredAndConflictExtrasNeverBecomePreparationAuthority() = fixture { f ->
        val r = interrupted(f, "admission-after-commit")
        for (suffix in listOf("retired", "conflict", "publish")) {
            val file = custodyFile(f, r, suffix); file.writeText("retained")
            assertNotNull(runCatching { continueExact(f, r) }.exceptionOrNull()); assertFalse(custodyFile(f, r, "before").exists())
            assertEquals("retained", file.readText()); assertTrue(file.delete()) // synthetic fixture reset only
        }
    }
    @Test fun sameByteCanonicalInodeReplacementRefusesWithoutCopies() = fixture { f ->
        val r = interrupted(f, "admission-after-commit"); val catalog = File(f.app, ".droide/projects.json"); val bytes = catalog.readBytes()
        assertTrue(catalog.renameTo(File(f.app, "original-catalog"))); catalog.writeBytes(bytes)
        assertNotNull(runCatching { continueExact(f, r) }.exceptionOrNull()); assertFalse(custodyFile(f, r, "before").exists())
    }
    @Test fun replacedProjectRootRefusesWithoutCopies() = fixture { f ->
        val r = interrupted(f, "admission-after-commit"); val root = File(r.request.rootPath)
        assertTrue(root.renameTo(File(root.parentFile, "retained-root"))); assertTrue(root.mkdir())
        assertNotNull(runCatching { continueExact(f, r) }.exceptionOrNull()); assertFalse(custodyFile(f, r, "before").exists())
    }
    @Test fun wrongTokenOperationOrEnvelopeNeverAdmits() = fixture { f ->
        val r = interrupted(f, "admission-after-commit"); val count = f.admissions
        val hash = CatalogMembershipCommands.hash(CatalogMembershipCommands.encode(r))
        for (args in listOf(Triple(java.util.UUID.randomUUID().toString(), r.operationId!!, hash),
            Triple(r.token, java.util.UUID.randomUUID().toString(), hash), Triple(r.token, r.operationId, "f".repeat(64)))) {
            assertNotNull(runCatching { f.manager.continueMembershipPreparation(args.first, args.second, args.third) }.exceptionOrNull())
        }
        assertFalse(custodyFile(f, r, "before").exists()); assertEquals(count, f.admissions)
    }
    @Test fun extraInsertedInsideCopyCallbackStopsBeforeProposal() = fixture { f ->
        val r = interrupted(f, "admission-after-commit")
        f.hook = { if (it == "snapshot-CREATED") custodyFile(f, r, "retired").writeText("unexpected") }
        assertNotNull(runCatching { continueExact(f, r) }.exceptionOrNull()); f.hook = {}
        assertEquals("unexpected", custodyFile(f, r, "retired").readText()); assertFalse(custodyFile(f, r, "after").exists())
        assertEquals(CanonicalFileOperationJournal.Phase.PREPARED, f.store.inspectRecovery().pending!!.phase)
    }
    @Test fun actualCancellationAfterSnapshotCommitPreservesExactContinuableReceipt() = fixture { f ->
        val r = interrupted(f, "admission-after-commit"); val job = Job()
        f.hook = { if (it == "snapshot-after-commit") job.cancel() }
        val failure = runCatching { kotlinx.coroutines.withContext(job) { continueExact(f, r) } }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException); f.hook = {}
        assertEquals(CanonicalFileOperationJournal.Phase.SNAPSHOT_RECORDED, f.store.inspectRecovery().pending!!.phase)
        continueExact(f, r); assertEquals(CanonicalFileOperationJournal.Phase.PUBLICATION_STAGED, f.store.inspectRecovery().pending!!.phase)
    }
    private suspend fun swappedBeforeCommit(f: Fixture, initialPoint: String, swapPoint: String, suffix: String,
                                           expected: CanonicalFileOperationJournal.Phase) {
        val r = interrupted(f, initialPoint)
        val originalCatalog = f.disk.loadCatalog()
        f.hook = { if (it == swapPoint) {
            val file = custodyFile(f, r, suffix); val bytes = file.readBytes()
            assertTrue(file.renameTo(File(f.app, "preserved-$suffix"))); file.writeBytes(bytes)
        } }
        assertNotNull(runCatching { continueExact(f, r) }.exceptionOrNull()); f.hook = {}
        assertEquals(expected, f.store.inspectRecovery().pending!!.phase)
        assertEquals(originalCatalog, f.disk.loadCatalog())
        assertTrue(custodyFile(f, r, suffix).isFile); assertTrue(File(f.app, "preserved-$suffix").isFile)
        assertNotNull(runCatching { continueExact(f, r) }.exceptionOrNull()) // no same-byte adoption on retry
    }
    @Test fun transientSnapshotSwapBeforeCommitRollsBackReceipt() = fixture { f ->
        swappedBeforeCommit(f, "admission-after-commit", "snapshot-before-commit", "before", CanonicalFileOperationJournal.Phase.PREPARED)
    }
    @Test fun transientProposalSwapBeforeCommitRollsBackReceipt() = fixture { f ->
        swappedBeforeCommit(f, "snapshot-after-commit", "proposal-before-commit", "after", CanonicalFileOperationJournal.Phase.SNAPSHOT_RECORDED)
    }
    @Test fun transientStageSwapBeforeCommitRollsBackReceipt() = fixture { f ->
        swappedBeforeCommit(f, "proposal-after-commit", "publication-stage-before-commit", "publish", CanonicalFileOperationJournal.Phase.PROPOSAL_RECORDED)
    }
    @Test fun finalReturnCallbackRootReplacementCannotReturnPreparedSuccess() = fixture { f ->
        val r = interrupted(f, "admission-after-commit")
        f.hook = { if (it == "membership-prepare-before-return") {
            val root = File(r.request.rootPath); assertTrue(root.renameTo(File(root.parentFile, "old-target"))); assertTrue(root.mkdir())
        } }
        assertNotNull(runCatching { continueExact(f, r) }.exceptionOrNull()); f.hook = {}
        assertEquals(CanonicalFileOperationJournal.Phase.PUBLICATION_STAGED, f.store.inspectRecovery().pending!!.phase)
        assertNull(f.store.inspectMembershipCommands().single().outcome)
    }
    @Test fun partialCopyCancellationRemainsBlockedWithBytesPreserved() = fixture { f ->
        val r = interrupted(f, "admission-after-commit"); val job = Job()
        f.hook = { if (it == "snapshot-CREATED") job.cancel() }
        assertTrue(runCatching { kotlinx.coroutines.withContext(job) { continueExact(f, r) } }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        f.hook = {}; val bytes = custodyFile(f, r, "before").readBytes()
        assertEquals(CanonicalFileOperationJournal.Phase.PREPARED, f.store.inspectRecovery().pending!!.phase)
        assertNotNull(runCatching { continueExact(f, r) }.exceptionOrNull()); assertArrayEquals(bytes, custodyFile(f, r, "before").readBytes())
    }
    @Test fun completedAndCompactedCommandsNeverStageAgain() = fixture { f ->
        val r = f.acceptedOperation(); val catalog = f.disk.loadCatalog(); val files = f.custody(); val count = f.admissions
        assertNotNull(runCatching { continueExact(f, r) }.exceptionOrNull())
        f.store.enableMembershipCompaction(); f.manager.expireAcceptedOperation(r.token, r.outcome!!)
        assertNotNull(runCatching { continueExact(f, r) }.exceptionOrNull())
        assertEquals(catalog, f.disk.loadCatalog()); assertEquals(files, f.custody()); assertEquals(count, f.admissions)
    }
    @Test fun explicitSchemaFourUsesSameTrackedContinuationWithoutCompactingPending() = fixture { f ->
        f.store.enableMembershipCompaction()
        val r = interrupted(f, "admission-after-commit"); continueExact(f, r)
        assertEquals(4, f.db { it.version }); assertTrue(f.state().compacted.isEmpty())
        assertEquals(listOf(r), f.store.inspectMembershipCommands())
        assertEquals(CanonicalFileOperationJournal.Phase.PUBLICATION_STAGED, f.store.inspectRecovery().pending!!.phase)
    }
    @Test fun ordinarySelectedWriterRefusesPendingAndContinuationDoesNotAdoptItsProposal() = fixture { f ->
        val current = f.store.loadCatalog(); val r = interrupted(f, "admission-after-commit")
        val other = JournaledProjectStore(f.selection, f.policy)
        assertNotNull(runCatching { other.saveCatalog(current.projects.map { it.copy(name = "foreign") }, current.revision) }.exceptionOrNull())
        continueExact(f, r)
        assertEquals(r.request.proposal, custodyFile(f, r, "after").readText())
        assertTrue(f.disk.load().single { it.id == "stored" }.workspaceRemoved)
    }
    @Test fun fatalCallbackRemainsFatalAndNeverAdvancesLaterSteps() = fixture { f ->
        val r = interrupted(f, "admission-after-commit"); val fatal = AssertionError("injected fatal")
        f.hook = { if (it == "snapshot-before-commit") throw fatal }
        val failure = runCatching { continueExact(f, r) }.exceptionOrNull(); f.hook = {}
        assertTrue(failure is Error)
        assertTrue(generateSequence(failure) { it.cause }.any { it === fatal })
        assertEquals(CanonicalFileOperationJournal.Phase.PREPARED, f.store.inspectRecovery().pending!!.phase)
        assertFalse(custodyFile(f, r, "after").exists())
    }
}
