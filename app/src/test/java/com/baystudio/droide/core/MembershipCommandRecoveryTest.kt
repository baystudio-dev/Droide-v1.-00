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
class MembershipCommandRecoveryTest {
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
    private suspend fun recover(f: Fixture, r: CatalogMembershipCommands.Receipt) = f.manager.recoverMembershipCommand(
        r.token, r.operationId!!, CatalogMembershipCommands.admissionEnvelopeHash(r))
    private fun refusal(point: String) = fixture { f ->
        val r = interrupted(f, point); val history = f.store.inspectRecovery(); val files = f.custody()
        val catalog = File(f.app, ".droide/projects.json").takeIf { it.exists() }?.readBytes(); val admissions = f.admissions
        val calls = mutableListOf<String>(); f.hook = { calls += it }
        assertNotNull(runCatching { recover(f, r) }.exceptionOrNull())
        assertEquals(history, f.store.inspectRecovery()); assertEquals(files, f.custody())
        assertArrayEquals(catalog, File(f.app, ".droide/projects.json").takeIf { it.exists() }?.readBytes())
        assertEquals(admissions, f.admissions); assertFalse(calls.any { it.endsWith("-before-move") || it.endsWith("-after-move") })
    }
    private fun recovered(point: String, expectedPublicationMoves: Int) = fixture { f ->
        val r = interrupted(f, point); val admissions = f.admissions; val beforeFiles = f.custody()
        f.reopen(); val projects = f.manager.projects.value; val active = f.disk.loadActiveId()
        val calls = mutableListOf<String>(); f.hook = { calls += it }
        val result = recover(f, r)
        assertEquals(r.token, result.token); assertEquals(r.operationId, result.operationId)
        assertEquals(CatalogMembershipCommands.admissionEnvelopeHash(r), CatalogMembershipCommands.admissionEnvelopeHash(result))
        assertNotNull(result.outcome); assertFalse(result.acknowledged); assertEquals(admissions, f.admissions)
        assertEquals(0, calls.count { it == "retirement-before-move" }); assertEquals(expectedPublicationMoves, calls.count { it == "publication-before-move" })
        assertNull(f.store.inspectRecovery().pending); assertEquals(projects, f.manager.projects.value); assertEquals(active, f.disk.loadActiveId())
        for ((name, hash) in beforeFiles.filterKeys { !it.endsWith(".publish") }) assertEquals(hash, f.custody()[name])
        val accepted = f.manager.acknowledgeMembershipResult(result.token, result.outcome!!)
        calls.clear(); assertEquals(accepted, recover(f, r)); assertTrue(recover(f, r).acknowledged)
        assertFalse(calls.any { it.endsWith("-before-move") })
    }
    @Test fun retirementIntentBeforeMoveRefusesWithoutReplay() = refusal("retirement-intent-after-commit")
    @Test fun publicationIntentBeforeMoveRefusesWithoutReplay() = refusal("publication-intent-after-commit")
    @Test fun retirementMoveLostOutcomeRecoversAndPublishesOnce() = recovered("retirement-after-move", 1)
    @Test fun recordedRetirementPublishesOnce() = recovered("retirement-outcome-after-commit", 1)
    @Test fun retainedSnapshotPublishesOnce() = recovered("retention-after-commit", 1)
    @Test fun publicationMoveLostOutcomeRecoversWithoutMove() = recovered("publication-after-move", 0)
    @Test fun recordedPublicationCompletesWithoutMove() = recovered("publication-outcome-after-commit", 0)
    @Test fun completedPendingArchivesWithoutMove() = recovered("publication-completion-after-commit", 0)
    @Test fun stagedScopeStillRequiresSeparateCompletion() = refusal("publication-stage-after-commit")
    @Test fun earlyPreparationNeverRecaptured() = refusal("admission-after-commit")
    private fun recoveredReturnLoss(first: String, second: String) = fixture { f ->
        val r = interrupted(f, first)
        f.hook = { if (it == second) throw IOException("lost recovery return") }
        assertNotNull(runCatching { recover(f, r) }.exceptionOrNull())
        f.hook = {}; f.reopen()
        val calls = mutableListOf<String>(); f.hook = { calls += it }
        val result = recover(f, r); assertNotNull(result.outcome)
        assertFalse(calls.contains("retirement-before-move"))
        assertEquals(if (second.startsWith("retirement")) 1 else 0, calls.count { it == "publication-before-move" })
    }
    @Test fun retirementRecoveredReturnLossContinuesWithoutReretirement() = recoveredReturnLoss("retirement-after-move", "retirement-recovery-after-commit")
    @Test fun publicationRecoveredReturnLossContinuesWithoutRepublication() = recoveredReturnLoss("publication-after-move", "publication-recovery-after-commit")
    private fun substituted(point: String, target: (Fixture, CatalogMembershipCommands.Receipt) -> File) = fixture { f ->
        val r = interrupted(f, point); val file = target(f, r)
        val old = File(f.app, file.name + ".preserved"); assertTrue(file.renameTo(old)); file.writeBytes(old.readBytes())
        val history = f.store.inspectRecovery(); val calls = mutableListOf<String>(); f.hook = { calls += it }
        assertNotNull(runCatching { recover(f, r) }.exceptionOrNull())
        assertEquals(history, f.store.inspectRecovery()); assertTrue(old.exists()); assertTrue(file.exists())
        assertFalse(calls.any { it.endsWith("-before-move") })
    }
    @Test fun retiredSameBytesForeignInodeRefuses() = substituted("retirement-after-move") { f, r -> custodyFile(f, r, "retired") }
    @Test fun publishedSameBytesForeignInodeRefuses() = substituted("publication-after-move") { f, _ -> File(f.app, ".droide/projects.json") }
    @Test fun recreatedCanonicalAfterRetirementRefuses() = fixture { f ->
        val r = interrupted(f, "retirement-after-move")
        File(f.app, ".droide/projects.json").writeBytes(custodyFile(f, r, "before").readBytes())
        val history = f.store.inspectRecovery(); assertNotNull(runCatching { recover(f, r) }.exceptionOrNull())
        assertEquals(history, f.store.inspectRecovery())
    }
    @Test fun recreatedStageAfterPublicationRefuses() = fixture { f ->
        val r = interrupted(f, "publication-after-move")
        custodyFile(f, r, "publish").writeBytes(File(f.app, ".droide/projects.json").readBytes())
        val history = f.store.inspectRecovery(); assertNotNull(runCatching { recover(f, r) }.exceptionOrNull())
        assertEquals(history, f.store.inspectRecovery())
    }
    @Test fun wrongCommitmentDoesNotRecover() = fixture { f ->
        val r = interrupted(f, "retirement-after-move"); val history = f.store.inspectRecovery(); val files = f.custody()
        assertNotNull(runCatching { f.manager.recoverMembershipCommand(r.token, r.operationId!!, "0".repeat(64)) }.exceptionOrNull())
        assertEquals(history, f.store.inspectRecovery()); assertEquals(files, f.custody())
    }
    @Test fun rootReplacementAtRecoveryCallbackStopsBeforePublication() = fixture { f ->
        val r = interrupted(f, "retirement-after-move"); var moved = false
        f.hook = { if (it == "retirement-recovery-verified") {
            val root = File(f.target.rootPath); assertTrue(root.renameTo(File(root.parentFile, "old-root"))); assertTrue(root.mkdir())
        }; if (it == "publication-before-move") moved = true }
        assertNotNull(runCatching { recover(f, r) }.exceptionOrNull()); assertFalse(moved)
        assertEquals(CanonicalFileOperationJournal.Phase.RETIREMENT_PREPARED, f.store.inspectRecovery().pending!!.phase)
    }
    @Test fun actualCancellationDuringRecoveryPreservesPhaseAndDoesNotMove() = fixture { f ->
        val r = interrupted(f, "retirement-after-move"); val history = f.store.inspectRecovery()
        var reached = false; var moved = false
        val job = kotlinx.coroutines.coroutineScope {
            val child = launch(start = CoroutineStart.LAZY) { recover(f, r) }
            f.hook = { if (it == "retirement-recovery-verified") { reached = true; child.cancel() }; if (it == "publication-before-move") moved = true }
            child.start(); child.join(); child
        }
        f.hook = {}; assertTrue(reached); assertTrue(job.isCancelled); assertFalse(moved)
        assertEquals(history, f.store.inspectRecovery())
        assertNotNull(recover(f, r).outcome)
    }
    @Test fun fatalAfterRecoveryCommitRemainsFatalWithDurableAdvance() = fixture { f ->
        val r = interrupted(f, "retirement-after-move"); val fatal = AssertionError("recovery fatal")
        f.hook = { if (it == "retirement-recovery-after-commit") throw fatal }
        val thrown = runCatching { recover(f, r) }.exceptionOrNull(); assertTrue(thrown is Error); assertTrue(generateSequence(thrown) { it.cause }.any { it === fatal })
        f.hook = {}; assertEquals(CanonicalFileOperationJournal.Phase.RETIREMENT_RECOVERED, f.store.inspectRecovery().pending!!.phase)
        assertNotNull(recover(f, r).outcome)
    }
    @Test fun lostArchiveReturnIsHistoricalWithoutAnotherMove() = fixture { f ->
        val r = interrupted(f, "publication-after-move")
        f.hook = { if (it == "completion-archive-after-commit") throw IOException("archive return lost") }
        assertNotNull(runCatching { recover(f, r) }.exceptionOrNull())
        f.hook = {}; assertNull(f.store.inspectRecovery().pending); f.reopen()
        val calls = mutableListOf<String>(); f.hook = { calls += it }
        assertNotNull(recover(f, r).outcome); assertFalse(calls.any { it.endsWith("-before-move") })
    }
    @Test fun finalCallbackCustodySwapRejectsResultButPreservesArchivedHistory() = fixture { f ->
        val r = interrupted(f, "publication-after-move")
        f.hook = { if (it == "membership-command-completed-before-return") {
            val file = custodyFile(f, r, "after"); val old = File(f.app, "preserved-after")
            assertTrue(file.renameTo(old)); file.writeBytes(old.readBytes())
        } }
        assertNotNull(runCatching { recover(f, r) }.exceptionOrNull())
        f.hook = {}; assertNull(f.store.inspectRecovery().pending)
        assertNotNull(runCatching { recover(f, r) }.exceptionOrNull())
    }
}
