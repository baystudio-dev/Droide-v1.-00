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
class MembershipNoChangeExpiryTest {
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
        val app = Files.createTempDirectory("membership-expiry-").toFile()
        try { Fixture(app, limit).also { it.start() }.let { action(it) } }
        finally { PathSecurity.deleteTreeNoFollow(app) }
    }
    @Test fun explicitExpiryOnlyRemovesAcceptedNoChangeEnvelope() = fixture { f ->
        val r = f.accepted()
        assertEquals(listOf(r), f.manager.discoverMembershipCommands()) // ack did not expire
        val history = f.store.inspectRecovery(); val tables = f.immutableTables(); val custody = f.custody()
        val catalog = f.disk.loadCatalog(); val active = f.disk.loadActiveId()
        assertEquals(CatalogMembershipCommands.Expiry.EXPIRED, f.manager.expireAcceptedNoChange(r.token, r.outcome!!))
        assertTrue(f.manager.discoverMembershipCommands().isEmpty())
        assertEquals(history, f.store.inspectRecovery()); assertEquals(tables, f.immutableTables()); assertEquals(custody, f.custody())
        assertEquals(catalog, f.disk.loadCatalog()); assertEquals(active, f.disk.loadActiveId()); assertEquals(0, f.admissions)
    }
    @Test fun recreatedStoreCannotAcknowledgeOrReplayExpiredToken() = fixture { f ->
        val r = f.accepted(); f.manager.expireAcceptedNoChange(r.token, r.outcome!!); f.reopen()
        assertTrue(f.manager.discoverMembershipCommands().isEmpty())
        val error = runCatching { f.manager.acknowledgeMembershipResult(r.token, r.outcome) }.exceptionOrNull()
        assertNotNull(error)
        val unavailable = generateSequence(error) { it.cause }.filterIsInstance<CatalogMembershipCommands.OutcomeUnavailableException>().single()
        assertEquals("Membership outcome unavailable: unknown or expired command", unavailable.message)
        assertEquals(CatalogMembershipCommands.Expiry.OUTCOME_UNAVAILABLE, f.manager.expireAcceptedNoChange(r.token, r.outcome))
        assertEquals(0, f.admissions); assertNull(f.store.inspectRecovery().pending)
    }
    @Test fun unknownUuidIsUnavailableNotProofOfPriorExpiry() = fixture { f ->
        assertEquals(CatalogMembershipCommands.Expiry.OUTCOME_UNAVAILABLE,
            f.manager.expireAcceptedNoChange(java.util.UUID.randomUUID().toString(), "0".repeat(64)))
        assertTrue(f.manager.discoverMembershipCommands().isEmpty()); assertEquals(0, f.admissions)
    }
    @Test fun unacknowledgedNoChangeCannotExpire() = fixture { f ->
        val r = f.manager.restoreMembershipCommand("stored")
        assertNotNull(runCatching { f.manager.expireAcceptedNoChange(r.token, r.outcome!!) }.exceptionOrNull())
        assertEquals(listOf(r), f.manager.discoverMembershipCommands())
    }
    @Test fun wrongOutcomeCannotExpireAcceptedResult() = fixture { f ->
        val r = f.accepted()
        assertNotNull(runCatching { f.manager.expireAcceptedNoChange(r.token, "f".repeat(64)) }.exceptionOrNull())
        assertEquals(listOf(r), f.manager.discoverMembershipCommands())
    }
    @Test fun acceptedOperationBackedCommandIsCategoricallyIneligible() = fixture { f ->
        f.manager.removeWorkspace("stored")
        val r = f.manager.restoreMembershipCommand("stored")
        assertNotNull(r.operationId)
        val accepted = f.manager.acknowledgeMembershipResult(r.token, r.outcome!!)
        val before = f.immutableTables(); val custody = f.custody()
        assertNotNull(runCatching { f.manager.expireAcceptedNoChange(r.token, r.outcome) }.exceptionOrNull())
        assertEquals(listOf(accepted), f.manager.discoverMembershipCommands())
        assertEquals(before, f.immutableTables()); assertEquals(custody, f.custody())
    }
    @Test fun repeatedExplicitCyclesExceedWindowWithoutAccumulatingRowsOrOperations() = fixture(2) { f ->
        val tokens = mutableSetOf<String>()
        repeat(7) {
            val r = f.accepted(); assertTrue(tokens.add(r.token))
            assertEquals(CatalogMembershipCommands.Expiry.EXPIRED, f.manager.expireAcceptedNoChange(r.token, r.outcome!!))
            assertTrue(f.store.inspectMembershipCommands().isEmpty())
        }
        assertTrue(f.store.inspectRecovery().completed.isEmpty()); assertEquals(0, f.admissions)
    }
    @Test fun precommitFailureRollsBackRowAndMembershipDigest() = fixture { f ->
        val r = f.accepted()
        f.hook = { if (it == "membership-expiry-before-commit") throw IOException("expiry interrupted") }
        assertNotNull(runCatching { f.manager.expireAcceptedNoChange(r.token, r.outcome!!) }.exceptionOrNull())
        f.hook = {}; f.reopen()
        assertEquals(listOf(r), f.manager.discoverMembershipCommands())
        assertEquals(CatalogMembershipCommands.Expiry.EXPIRED, f.manager.expireAcceptedNoChange(r.token, r.outcome!!))
    }
    @Test fun postcommitLostReturnOnlyPermitsUnavailableObservation() = fixture { f ->
        val r = f.accepted()
        f.hook = { if (it == "membership-expiry-after-commit") throw IOException("lost expiry return") }
        assertNotNull(runCatching { f.manager.expireAcceptedNoChange(r.token, r.outcome!!) }.exceptionOrNull())
        f.hook = {}; f.reopen()
        assertEquals(CatalogMembershipCommands.Expiry.OUTCOME_UNAVAILABLE, f.manager.expireAcceptedNoChange(r.token, r.outcome!!))
        assertNotNull(runCatching { f.manager.acknowledgeMembershipResult(r.token, r.outcome) }.exceptionOrNull())
    }
    @Test fun actualCancellationBeforeCommitRetainsAcknowledgedResult() = fixture { f ->
        val r = f.accepted()
        kotlinx.coroutines.coroutineScope {
            lateinit var task: Job
            f.hook = { if (it == "membership-expiry-before-commit") task.cancel() }
            task = launch(start = CoroutineStart.LAZY) { f.manager.expireAcceptedNoChange(r.token, r.outcome!!) }
            task.start(); task.join(); assertTrue(task.isCancelled)
        }
        f.hook = {}; f.reopen(); assertEquals(listOf(r), f.manager.discoverMembershipCommands())
    }
    @Test fun actualCancellationAfterCommitDoesNotReturnExactSuccess() = fixture { f ->
        val r = f.accepted()
        kotlinx.coroutines.coroutineScope {
            lateinit var task: Job
            f.hook = { if (it == "membership-expiry-after-commit") task.cancel() }
            task = launch(start = CoroutineStart.LAZY) { f.manager.expireAcceptedNoChange(r.token, r.outcome!!) }
            task.start(); task.join(); assertTrue(task.isCancelled)
        }
        f.hook = {}; f.reopen()
        assertEquals(CatalogMembershipCommands.Expiry.OUTCOME_UNAVAILABLE, f.manager.expireAcceptedNoChange(r.token, r.outcome!!))
    }
    @Test fun omittedRowWithUnchangedMembershipIsCorruptionNotExpired() = fixture { f ->
        val r = f.accepted()
        SQLiteDatabase.openDatabase(File(f.enrollment.authorityPath, "state.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DELETE FROM membership_commands")
        }
        assertNotNull(runCatching { f.manager.expireAcceptedNoChange(r.token, r.outcome!!) }.exceptionOrNull())
    }
    @Test fun ordinaryWriterCurrentStateIsNotOverwrittenOrAdoptedByExpiry() = fixture { f ->
        val r = f.accepted()
        val other = JournaledProjectStore(f.selection, f.policy)
        val before = other.loadCatalog()
        other.saveCatalog(before.projects.map { if (it.id == "stored") it.copy(name = "Other") else it }, before.revision)
        val current = other.loadCatalog(); val tables = f.immutableTables()
        assertNotNull(runCatching { f.manager.restoreMembershipCommand("stored") }.exceptionOrNull())
        assertNotNull(runCatching { f.manager.requireSelectionCertain() }.exceptionOrNull())
        f.manager.expireAcceptedNoChange(r.token, r.outcome!!)
        assertNotNull(runCatching { f.manager.requireSelectionCertain() }.exceptionOrNull())
        assertEquals(current, other.loadCatalog()); assertEquals(tables, f.immutableTables())
        assertEquals("Stored", f.manager.projects.value.single { it.id == "stored" }.name)
    }
    @Test fun historicalProjectRootNeedNotRemainLiveForMetadataOnlyExpiry() = fixture { f ->
        val r = f.accepted(); val root = File(f.target.rootPath)
        check(root.renameTo(File(root.parentFile, "moved-stored")))
        assertEquals(CatalogMembershipCommands.Expiry.EXPIRED, f.manager.expireAcceptedNoChange(r.token, r.outcome!!))
        assertFalse(root.exists()); assertTrue(File(root.parentFile, "moved-stored").isDirectory)
    }
    @Test fun pendingOrdinaryOperationAndCustodyRemainUnchanged() = fixture { f ->
        val r = f.accepted()
        val other = JournaledProjectStore(f.selection, f.policy, checkpoint = { if (it == "admission-after-commit") throw IOException("pending ordinary") })
        val before = other.loadCatalog()
        assertNotNull(runCatching { other.saveCatalog(before.projects.map { it.copy(name = "Pending") }, before.revision) }.exceptionOrNull())
        val pending = f.store.inspectRecovery(); val tables = f.immutableTables()
        assertNotNull(pending.pending)
        assertEquals(CatalogMembershipCommands.Expiry.EXPIRED, f.manager.expireAcceptedNoChange(r.token, r.outcome!!))
        assertEquals(pending, f.store.inspectRecovery()); assertEquals(tables, f.immutableTables())
    }
    @Test fun fullCanonical128RemainsFullAfterCommandEnvelopeExpiry() = fixture(128) { f ->
        var current = f.store.loadCatalog()
        repeat(128) { index ->
            val next = current.projects.map { if (it.id == "stored") it.copy(name = "Version $index") else it }
            f.store.saveCatalog(next, current.revision)
            current = f.store.loadCatalog()
        }
        f.reopen()
        val r = f.accepted(); val before = f.store.inspectRecovery(); val tables = f.immutableTables(); val custody = f.custody()
        assertEquals(128, before.completed.size)
        assertEquals(384, custody.size)
        f.manager.expireAcceptedNoChange(r.token, r.outcome!!)
        assertEquals(before, f.store.inspectRecovery()); assertEquals(tables, f.immutableTables()); assertEquals(custody, f.custody())
        val next = current.projects.map { it.copy(name = "Must refuse") }
        assertNotNull(runCatching { f.store.saveCatalog(next, current.revision) }.exceptionOrNull())
        assertEquals(current, f.store.loadCatalog()); assertEquals(128, f.store.inspectRecovery().completed.size)
    }
}
