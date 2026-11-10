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
class MembershipOperationCompactionTest {
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
        val app = Files.createTempDirectory("membership-compaction-").toFile()
        try { Fixture(app, limit).also { it.start() }.let { action(it) } }
        finally { PathSecurity.deleteTreeNoFollow(app) }
    }
    @Test fun explicitMigrationAndCompactionPreserveExactCanonicalReceiptAndCustody() = fixture { f ->
        val r = f.acceptedOperation(); val tables = f.immutableTables(); val custody = f.custody()
        f.store.enableMembershipCompaction()
        assertEquals(4, f.db { it.version }); assertEquals(listOf(r), f.state().receipts)
        assertTrue(f.state().compacted.isEmpty()); assertEquals(tables, f.immutableTables())
        val charge = f.charge()
        assertEquals(CatalogMembershipCommands.Expiry.EXPIRED, f.manager.expireAcceptedOperation(r.token, r.outcome!!))
        assertEquals(listOf(CatalogMembershipCommands.compact(r)), f.state().compacted)
        println("ENVELOPE_BYTES before=$charge after=${f.charge()}"); assertTrue(f.charge() < charge); assertEquals(tables, f.immutableTables()); assertEquals(custody, f.custody())
        assertTrue(f.manager.discoverMembershipCommands().isEmpty())
        assertEquals(1, f.store.inspectRecovery().completed.count { it.id == r.operationId })
        f.store.enableMembershipCommands(); f.store.enableMembershipCompaction()
    }
    @Test fun reconstructedPreparedMatchesOriginalFrozenBytes() = fixture { f ->
        val r = f.acceptedOperation()
        val j = CanonicalFileOperationJournal(File(f.enrollment.rootPath), File(f.enrollment.authorityPath), f.enrollment, 1_000_000)
        val completed = f.store.inspectRecovery().completed.single { it.id == r.operationId }
        assertEquals(r.frozenIntent, j.preparedIdentityRecord(completed))
    }
    @Test fun compactedTokenIsUnavailableAfterReopenWithoutReplayOrAdoption() = fixture { f ->
        val r = f.acceptedOperation(); f.store.enableMembershipCompaction()
        f.manager.expireAcceptedOperation(r.token, r.outcome!!); val count = f.admissions; val catalog = f.disk.loadCatalog()
        f.reopen(); f.manager.requireSelectionCertain()
        val failure = runCatching { f.manager.acknowledgeMembershipResult(r.token, r.outcome) }.exceptionOrNull()
        assertTrue(generateSequence(failure) { it.cause }.any { it is CatalogMembershipCommands.OutcomeUnavailableException })
        assertEquals(CatalogMembershipCommands.Expiry.OUTCOME_UNAVAILABLE, f.manager.expireAcceptedOperation(r.token, r.outcome))
        assertEquals(CatalogMembershipCommands.Expiry.OUTCOME_UNAVAILABLE, f.manager.expireAcceptedNoChange(r.token, r.outcome))
        assertEquals(count, f.admissions); assertEquals(catalog, f.disk.loadCatalog()); f.manager.requireSelectionCertain()
    }
    @Test fun migrationIsExplicitAndSchemaThreeUnchangedOnRefusal() = fixture { f ->
        val r = f.acceptedOperation(); val before = f.state()
        assertNotNull(runCatching { f.manager.expireAcceptedOperation(r.token, r.outcome!!) }.exceptionOrNull())
        assertEquals(3, f.db { it.version }); assertEquals(before, f.state())
    }
    @Test fun noChangeAndUnacknowledgedAndWrongOutcomeRefuse() = fixture { f ->
        f.store.enableMembershipCompaction()
        val noop = f.accepted()
        assertNotNull(runCatching { f.manager.expireAcceptedOperation(noop.token, noop.outcome!!) }.exceptionOrNull())
        f.manager.expireAcceptedNoChange(noop.token, noop.outcome!!)
        f.manager.removeWorkspace("stored"); val r = f.manager.restoreMembershipCommand("stored")
        assertNotNull(runCatching { f.manager.expireAcceptedOperation(r.token, r.outcome!!) }.exceptionOrNull())
        f.manager.acknowledgeMembershipResult(r.token, r.outcome!!)
        assertNotNull(runCatching { f.manager.expireAcceptedOperation(r.token, "f".repeat(64)) }.exceptionOrNull())
        assertEquals(1, f.state().receipts.size); assertTrue(f.state().compacted.isEmpty())
    }
    @Test fun migrationFailureRollsBackVersionAndTables() = fixture { f ->
        val r = f.acceptedOperation()
        f.hook = { if (it == "membership-compaction-migration-before-commit") throw IOException("migration") }
        assertNotNull(runCatching { f.store.enableMembershipCompaction() }.exceptionOrNull())
        f.hook = {}; f.reopen(); assertEquals(3, f.db { it.version }); assertEquals(listOf(r), f.state().receipts)
        f.store.enableMembershipCompaction(); assertEquals(4, f.db { it.version })
    }
    @Test fun beforeCommitFailureRollsBackExactEnvelopeAndLink() = fixture { f ->
        val r = f.acceptedOperation(); f.store.enableMembershipCompaction(); val before = f.state()
        f.hook = { if (it == "membership-compaction-before-commit") throw IOException("before") }
        assertNotNull(runCatching { f.manager.expireAcceptedOperation(r.token, r.outcome!!) }.exceptionOrNull())
        f.hook = {}; f.reopen(); assertEquals(before, f.state())
    }
    @Test fun lostReturnDoesNotFabricateRepeatSuccess() = fixture { f ->
        val r = f.acceptedOperation(); f.store.enableMembershipCompaction()
        f.hook = { if (it == "membership-compaction-after-commit") throw IOException("lost") }
        assertNotNull(runCatching { f.manager.expireAcceptedOperation(r.token, r.outcome!!) }.exceptionOrNull())
        f.hook = {}; f.reopen()
        assertEquals(CatalogMembershipCommands.Expiry.OUTCOME_UNAVAILABLE, f.manager.expireAcceptedOperation(r.token, r.outcome!!))
        assertEquals(1, f.state().compacted.size)
    }
    @Test fun actualCancellationBeforeCommitRollsBack() = fixture { f ->
        val r = f.acceptedOperation(); f.store.enableMembershipCompaction(); val before = f.state()
        val job = Job(); f.hook = { if (it == "membership-compaction-before-commit") job.cancel() }
        val failure = runCatching { kotlinx.coroutines.withContext(job) { f.manager.expireAcceptedOperation(r.token, r.outcome!!) } }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        f.hook = {}; f.reopen(); assertEquals(before, f.state())
    }
    @Test fun ordinaryWriterAndNoChangeExpiryPreserveCompactMappings() = fixture { f ->
        val r = f.acceptedOperation(); f.store.enableMembershipCompaction(); f.manager.expireAcceptedOperation(r.token, r.outcome!!)
        val link = f.state().compacted.single(); val other = JournaledProjectStore(f.selection, f.policy)
        val current = other.loadCatalog(); other.saveCatalog(current.projects.map { it.copy(name = it.name + "!") }, current.revision)
        f.reopen(); val noop = f.accepted(); f.manager.expireAcceptedNoChange(noop.token, noop.outcome!!)
        assertEquals(listOf(link), f.state().compacted); assertEquals(r.token, f.state().operations[r.operationId])
        assertEquals(3, f.store.inspectRecovery().completed.size)
    }
    @Test fun omittedLinkFailsClosed() = fixture { f ->
        val r = f.acceptedOperation(); f.store.enableMembershipCompaction(); f.manager.expireAcceptedOperation(r.token, r.outcome!!)
        f.db { it.execSQL("DELETE FROM membership_compacted") }
        assertNotNull(runCatching { f.store.inspectMembershipCommands() }.exceptionOrNull())
    }
    @Test fun malformedBlobAndOversizedCompactRecordsFailBeforeDecode() = fixture { f ->
        val r = f.acceptedOperation(); f.store.enableMembershipCompaction(); f.manager.expireAcceptedOperation(r.token, r.outcome!!)
        val raw = CatalogMembershipCommands.encode(f.state().compacted.single())
        for (bad in listOf<Any>(byteArrayOf(1,2,3), "x".repeat(4097), "x".repeat(2 * 1024 * 1024 + 1), "{}")) {
            f.db { it.execSQL("UPDATE membership_compacted SET record=?", arrayOf(bad)) }
            assertNotNull(runCatching { f.store.inspectMembershipCommands() }.exceptionOrNull())
            f.db { it.execSQL("UPDATE membership_compacted SET record=?", arrayOf(raw)) }
        }
        assertEquals(1, f.store.inspectRecovery().completed.count { it.id == r.operationId })
    }
    private fun rewriteCanonicalState(f: Fixture, state: CatalogMembershipCommands.State) {
        // Synthetic corruption fixture: recompute envelope membership so owner validation, not digest
        // mismatch, is exercised.
        val method = CatalogMembershipCommands::class.java.getDeclaredMethod("write", SQLiteDatabase::class.java,
            List::class.java, Map::class.java, List::class.java).apply { isAccessible = true }
        f.db { method.invoke(CatalogMembershipCommands, it, state.receipts, state.operations, state.compacted) }
    }
    @Test fun wrongCompletedAndFrozenCommitmentsFailEvenWithRecomputedMembership() = fixture { f ->
        val r = f.acceptedOperation(); f.store.enableMembershipCompaction(); f.manager.expireAcceptedOperation(r.token, r.outcome!!)
        val good = f.state(); val link = good.compacted.single()
        for (bad in listOf(link.copy(completedRecordHash = "f".repeat(64)), link.copy(frozenIntentHash = "f".repeat(64)),
            link.copy(authority = "f".repeat(64)))) {
            rewriteCanonicalState(f, good.copy(compacted = listOf(bad)))
            assertNotNull(runCatching { f.store.inspectMembershipCommands() }.exceptionOrNull())
            rewriteCanonicalState(f, good)
        }
        assertEquals(good, f.state())
    }
    @Test fun duplicateTokenAndOperationAndUntrackedRelabelFailClosed() = fixture { f ->
        val r = f.acceptedOperation(); f.store.enableMembershipCompaction(); f.manager.expireAcceptedOperation(r.token, r.outcome!!)
        val good = f.state(); val link = good.compacted.single()
        val variants = listOf(good.copy(receipts = listOf(r)),
            good.copy(compacted = listOf(link, link.copy(token = java.util.UUID.randomUUID().toString()))),
            good.copy(operations = good.operations + (link.operationId to null)))
        for (bad in variants) {
            rewriteCanonicalState(f, bad)
            assertNotNull(runCatching { f.store.inspectMembershipCommands() }.exceptionOrNull())
            rewriteCanonicalState(f, good)
        }
    }
    @Test fun compactTokenCollisionCannotBeAdmittedAsNewCommand() = fixture { f ->
        val r = f.acceptedOperation(); f.store.enableMembershipCompaction(); f.manager.expireAcceptedOperation(r.token, r.outcome!!)
        val before = f.state()
        val freshOperation = java.util.UUID.randomUUID().toString()
        val failure = runCatching { f.db { CatalogMembershipCommands.admit(it, freshOperation,
            r.copy(operationId = freshOperation), f.limit) } }.exceptionOrNull()
        assertEquals("Membership command capacity exhausted", failure?.message)
        assertEquals(before, f.state())
    }
    @Test fun pendingPreparationRefusesMigrationWithoutChangingSchema() = fixture { f ->
        f.manager.removeWorkspace("stored")
        f.hook = { if (it == "admission-after-commit") throw IOException("lost admission") }
        assertNotNull(runCatching { f.manager.restoreMembershipCommand("stored") }.exceptionOrNull())
        f.hook = {}; val history = f.store.inspectRecovery(); assertNotNull(history.pending)
        assertNotNull(runCatching { f.store.enableMembershipCompaction() }.exceptionOrNull())
        assertEquals(3, f.db { it.version }); assertEquals(history, f.store.inspectRecovery())
    }
    @Test fun pendingOrdinaryOperationSurvivesCompactionAndCannotBecomeCompactTarget() = fixture { f ->
        val r = f.acceptedOperation(); f.store.enableMembershipCompaction()
        val updater = f.selection.guardedUpdate(f.policy, EmptyCoroutineContext) {}
        val current = f.store.loadCatalog()
        val prepared = updater.prepare(current.projects.map { it.copy(name = it.name + "?") }, current.revision)
        val pending = f.store.inspectRecovery().pending!!
        f.manager.expireAcceptedOperation(r.token, r.outcome!!)
        assertEquals(pending, f.store.inspectRecovery().pending)
        val good = f.state(); val link = good.compacted.single()
        val changedMappings = good.operations - link.operationId + (pending.id to link.token) + (link.operationId to null)
        rewriteCanonicalState(f, good.copy(operations = changedMappings, compacted = listOf(link.copy(operationId = pending.id))))
        assertNotNull(runCatching { f.store.inspectMembershipCommands() }.exceptionOrNull())
        rewriteCanonicalState(f, good)
        updater.resume(prepared.operationId)
        assertNull(f.store.inspectRecovery().pending); assertEquals(1, f.state().compacted.size)
    }
    @Test fun fullCommandWindowRecoversOneSlotButCanonicalCapacityDoesNot() = fixture(2) { f ->
        val r = f.acceptedOperation(); f.store.enableMembershipCompaction(); val noop = f.accepted()
        assertNotNull(runCatching { f.manager.restoreMembershipCommand("stored") }.exceptionOrNull())
        val charge = f.charge(); val tables = f.immutableTables(); val custody = f.custody()
        f.manager.expireAcceptedOperation(r.token, r.outcome!!)
        println("ENVELOPE_BYTES before=$charge after=${f.charge()}"); assertTrue(f.charge() < charge); assertEquals(tables, f.immutableTables()); assertEquals(custody, f.custody())
        f.reopen(); val next = f.accepted(); assertNotEquals(noop.token, next.token)
        assertEquals(2, f.state().receipts.size); assertEquals(1, f.state().compacted.size)
        val current = f.store.loadCatalog()
        assertNotNull(runCatching { f.store.saveCatalog(current.projects.map { it.copy(name = it.name + "!") }, current.revision) }.exceptionOrNull())
        assertEquals(2, f.store.inspectRecovery().completed.size)
    }
    @Test fun actualCancellationAfterCommitLeavesUnavailableResult() = fixture { f ->
        val r = f.acceptedOperation(); f.store.enableMembershipCompaction(); val job = Job()
        f.hook = { if (it == "membership-compaction-after-commit") job.cancel() }
        val failure = runCatching { kotlinx.coroutines.withContext(job) { f.manager.expireAcceptedOperation(r.token, r.outcome!!) } }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        f.hook = {}; f.reopen()
        assertEquals(CatalogMembershipCommands.Expiry.OUTCOME_UNAVAILABLE, f.manager.expireAcceptedOperation(r.token, r.outcome!!))
        assertEquals(1, f.state().compacted.size)
    }
    @Test fun realCanonical128And384CustodyFilesRemainCharged() = fixture(128) { f ->
        val r = f.acceptedOperation(); f.store.enableMembershipCompaction()
        repeat(126) { index ->
            val current = f.store.loadCatalog()
            f.store.saveCatalog(current.projects.map { it.copy(name = it.name.take(20) + index) }, current.revision)
        }
        val history = f.store.inspectRecovery(); assertEquals(128, history.completed.size)
        val tables = f.immutableTables(); val custody = f.custody(); assertEquals(384, custody.size)
        f.manager.expireAcceptedOperation(r.token, r.outcome!!)
        assertEquals(tables, f.immutableTables()); assertEquals(custody, f.custody()); assertEquals(history, f.store.inspectRecovery())
        val current = f.store.loadCatalog()
        assertNotNull(runCatching { f.store.saveCatalog(current.projects.map { it.copy(name = it.name + "x") }, current.revision) }.exceptionOrNull())
        assertEquals(history, f.store.inspectRecovery())
    }
    @Test fun oversizedIdentifiersAndDigestRejectBeforeMaterialization() = fixture { f ->
        val r = f.acceptedOperation(); f.store.enableMembershipCompaction(); f.manager.expireAcceptedOperation(r.token, r.outcome!!)
        val good = f.state()
        for (sql in listOf("UPDATE membership_compacted SET id=?", "UPDATE membership_state SET digest=?",
            "UPDATE membership_operations SET command=? WHERE command IS NOT NULL", "UPDATE membership_operations SET id=? WHERE command IS NOT NULL")) {
            f.db { it.execSQL(sql, arrayOf("x".repeat(1024 * 1024))) }
            assertNotNull(runCatching { f.store.inspectMembershipCommands() }.exceptionOrNull())
            rewriteCanonicalState(f, good)
        }
        assertEquals(good, f.state())
    }
    @Test fun undeclaredCompactionTableOnOldSchemasRefuses() = fixture { f ->
        // Isolated codec fixture, not a downgrade of the enrolled authority.
        val file = File(f.app, "old-format.sqlite")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE membership_compacted(id TEXT,record TEXT)")
            for (version in listOf(1, 2)) {
                db.version = version
                assertNotNull(runCatching { CatalogMembershipCommands.read(db) }.exceptionOrNull())
            }
        }
    }
}
