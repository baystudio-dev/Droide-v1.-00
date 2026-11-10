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
class SavedCatalogStartupTest {
    private class Fixture(val app: File, val limit: Int, val maxBytes: Long = 1_000_000) {
        val disk = FileProjectStore(app)
        val default = DroideProject("default", "Default", File(app, "projects/default").apply { mkdirs() }.canonicalPath)
        val target = DroideProject("stored", "Stored", File(app, "projects/stored").apply { mkdirs() }.canonicalPath)
        val storage = File(app, "catalog-startup").apply { mkdir() }
        val admission = ProjectCatalogAdmission(app, storage, maxBytes)
        val policy = CanonicalJournalRetention.Policy(limit, 100_000_000)
        lateinit var selection: ProjectCatalogAdmission.UpdateSelection
        lateinit var enrollment: CanonicalFileOperationJournal.Enrollment
        lateinit var store: JournaledProjectStore
        lateinit var manager: ProjectManager
        var hook: (String) -> Unit = {}
        var admissions = 0
        suspend fun start() {
            disk.save(listOf(default, target)); disk.saveActiveId("default")
            enrollment = CanonicalJournalBootstrap(admission.updateSlot, maxBytes).createWithMembership(
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
    @Test fun applicationReopensAlreadySelectedReadyCatalog() = fixture { f ->
        val manager = ProjectManager.forApplication(f.app)
        manager.init()
        assertTrue(manager.supportsCatalogRecoveryReview)
        assertEquals(f.disk.load(), manager.projects.value)
        assertEquals("default", manager.activeId.value)
    }
    @Test fun pendingStartupFailureKeepsExistingRecoverySurfaceAvailable() = fixture { f ->
        f.manager.removeWorkspace("stored")
        f.hook = { if (it == "admission-after-commit") throw IOException("lost return") }
        assertNotNull(runCatching { f.manager.restoreMembershipCommand("stored") }.exceptionOrNull())
        f.hook = {}; val history = f.store.inspectRecovery()
        val manager = ProjectManager.forApplication(f.app)
        assertNotNull(runCatching { manager.init() }.exceptionOrNull())
        assertTrue(manager.supportsCatalogRecoveryReview)
        assertEquals(history, manager.inspectCatalogRecovery())
        assertTrue(manager.projects.value.isEmpty()); assertNull(manager.activeId.value)
    }
    @Test fun persistedPolicyIsUsedRatherThanConstructorDefault() = fixture(limit = 2) { f ->
        val manager = ProjectManager.forApplication(f.app); manager.init()
        manager.removeWorkspace("stored"); manager.restoreWorkspace("stored")
        assertEquals(2, manager.inspectCatalogRecovery().completed.size)
        val before = f.disk.loadCatalog()
        assertNotNull(runCatching { manager.removeWorkspace("stored") }.exceptionOrNull())
        assertEquals(before, f.disk.loadCatalog())
    }
    @Test fun readOnlyReopenDoesNotPublishOrEnroll() = fixture { f ->
        val tables = f.immutableTables(); val files = f.custody(); val before = f.disk.loadCatalog()
        val manager = ProjectManager.forApplication(f.app); manager.init(readOnlyExisting = true)
        assertTrue(manager.supportsCatalogRecoveryReview); assertEquals(before, f.disk.loadCatalog())
        assertEquals(tables, f.immutableTables()); assertEquals(files, f.custody())
    }
    @Test fun malformedChoiceDoesNotFallback() = fixture { f ->
        val record = File(f.storage, "startup-admission.json"); record.writeText("{broken")
        val before = f.disk.loadCatalog(); val manager = ProjectManager.forApplication(f.app)
        assertNotNull(runCatching { manager.init() }.exceptionOrNull()); assertFalse(manager.supportsCatalogRecoveryReview)
        assertEquals(before, f.disk.loadCatalog()); assertEquals("{broken", record.readText())
    }
    @Test fun missingAuthorityDoesNotFallback() = fixture { f ->
        val authority = File(f.enrollment.authorityPath); assertTrue(authority.renameTo(File(f.app, "saved-authority")))
        val manager = ProjectManager.forApplication(f.app); val before = f.disk.loadCatalog()
        assertNotNull(runCatching { manager.init() }.exceptionOrNull()); assertFalse(manager.supportsCatalogRecoveryReview)
        assertEquals(before, f.disk.loadCatalog()); assertFalse(authority.exists())
    }
    @Test fun partialChoiceRefusesEvenWithValidFinalRecord() = fixture { f ->
        File(f.storage, ".startup-admission.json.pending").writeText("pending")
        val manager = ProjectManager.forApplication(f.app)
        assertNotNull(runCatching { manager.init() }.exceptionOrNull()); assertFalse(manager.supportsCatalogRecoveryReview)
        assertEquals("pending", File(f.storage, ".startup-admission.json.pending").readText())
    }
    @Test fun missingRetentionPolicyNeverInventsDefaults() = fixture { f ->
        f.db { it.execSQL("DELETE FROM retention") }
        val manager = ProjectManager.forApplication(f.app)
        assertNotNull(runCatching { manager.init() }.exceptionOrNull()); assertFalse(manager.supportsCatalogRecoveryReview)
        f.db { db -> db.rawQuery("SELECT count(*) FROM retention", null).use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) } }
    }
    @Test fun cancellationBeforeResolutionPublishesNoPartialRoute() = fixture { f ->
        var once = true
        val manager = ProjectManager.forApplication(f.app) { if (it == "application-catalog-before-resolution" && once) {
            once = false; throw kotlinx.coroutines.CancellationException("cancel resolution")
        } }
        assertTrue(runCatching { manager.init() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertFalse(manager.supportsCatalogRecoveryReview); assertTrue(manager.projects.value.isEmpty())
        manager.init(); assertTrue(manager.supportsCatalogRecoveryReview)
    }
    @Test fun finalChoiceReplacementRefusesBeforePublishingRoute() = fixture { f ->
        val record = File(f.storage, "startup-admission.json")
        val manager = ProjectManager.forApplication(f.app) { if (it == "application-catalog-before-resolution") {
            assertTrue(record.renameTo(File(f.app, "original-choice"))); record.writeBytes(File(f.app, "original-choice").readBytes())
        } }
        assertNotNull(runCatching { manager.init() }.exceptionOrNull()); assertFalse(manager.supportsCatalogRecoveryReview)
        assertTrue(manager.projects.value.isEmpty())
    }
    @Test fun resolvedSelectionNeverFallsBackWhenChoiceDisappears() = fixture { f ->
        var resolutions = 0
        val manager = ProjectManager.forApplication(f.app) { if (it == "application-catalog-before-resolution") resolutions++ }
        manager.init(); val before = manager.projects.value
        assertTrue(File(f.storage, "startup-admission.json").renameTo(File(f.app, "saved-choice")))
        assertNotNull(runCatching { manager.init() }.exceptionOrNull())
        assertTrue(manager.supportsCatalogRecoveryReview); assertEquals(1, resolutions); assertEquals(before, manager.projects.value)
    }
    @Test fun unresolvedManagerDoesNotReadDiskForRecoverySupport() = fixture { f ->
        var reads = 0; val manager = ProjectManager.forApplication(f.app) { reads++ }
        assertFalse(manager.supportsCatalogRecoveryReview); assertEquals(0, reads)
        assertNotNull(runCatching { manager.inspectCatalogRecovery() }.exceptionOrNull()); assertEquals(0, reads)
    }
    @Test fun savedMaximumIsRestoredWithoutAssumingNewDefault() = runBlocking {
        val app = Files.createTempDirectory("saved-max-").toFile()
        try {
            val f = Fixture(app, 3, 777_777); f.start()
            val manager = ProjectManager.forApplication(app); manager.init()
            assertTrue(manager.supportsCatalogRecoveryReview); manager.removeWorkspace("stored")
            assertEquals(1, manager.inspectCatalogRecovery().completed.size)
        } finally { PathSecurity.deleteTreeNoFollow(app) }
    }
    @Test fun actualCancellationBeforeRouteAssignmentLeavesResolutionUnpublished() = fixture { f ->
        var reached = false; var child: Job? = null
        val manager = ProjectManager.forApplication(f.app) { if (it == "application-catalog-before-resolution") {
            reached = true; child!!.cancel()
        } }
        kotlinx.coroutines.coroutineScope {
            child = launch(start = CoroutineStart.LAZY) { manager.init() }
            child!!.start(); child!!.join()
        }
        assertTrue(reached); assertTrue(child!!.isCancelled); assertFalse(manager.supportsCatalogRecoveryReview)
        assertTrue(manager.projects.value.isEmpty())
    }
    @Test fun missingCompletedPayloadStillConstructsReadOnlyRecoveryOwner() = fixture { f ->
        f.manager.removeWorkspace("stored")
        val id = f.store.inspectRecovery().completed.single().id
        val retained = File(f.enrollment.authorityPath, "custody/$id.after")
        assertTrue(retained.renameTo(File(f.app, "retained-outside")))
        val manager = ProjectManager.forApplication(f.app)
        assertNotNull(runCatching { manager.init() }.exceptionOrNull())
        assertTrue(manager.supportsCatalogRecoveryReview)
        assertEquals(id, manager.inspectCatalogRecovery().completed.single().id)
        assertTrue(manager.projects.value.isEmpty())
    }
    private fun rejectedRecord(bytes: ByteArray) = fixture { f ->
        val record = File(f.storage, "startup-admission.json"); record.writeBytes(bytes)
        val before = f.disk.loadCatalog(); val manager = ProjectManager.forApplication(f.app)
        assertNotNull(runCatching { manager.init() }.exceptionOrNull())
        assertFalse(manager.supportsCatalogRecoveryReview); assertEquals(before, f.disk.loadCatalog()); assertArrayEquals(bytes, record.readBytes())
    }
    @Test fun oversizedChoiceRejectedBeforeDecode() = rejectedRecord(ByteArray(16_385) { 32 })
    @Test fun malformedUtf8ChoiceRejectedBeforeDecode() = rejectedRecord(byteArrayOf(0xC3.toByte(), 0x28))
    @Test fun savedMaximumOverflowRejectedWithoutMutation() = fixture { f ->
        val record = File(f.storage, "startup-admission.json")
        record.writeText(record.readText().replace("\"maxBytes\":1000000", "\"maxBytes\":9223372036854775807"))
        val bytes = record.readBytes(); val manager = ProjectManager.forApplication(f.app)
        assertNotNull(runCatching { manager.init() }.exceptionOrNull()); assertFalse(manager.supportsCatalogRecoveryReview)
        assertArrayEquals(bytes, record.readBytes())
    }
    @Test fun storageReplacementAtPolicyBoundaryRejectsOwner() = fixture { f ->
        val manager = ProjectManager.forApplication(f.app) { if (it == "application-catalog-policy-read") {
            assertTrue(f.storage.renameTo(File(f.app, "old-storage"))); assertTrue(f.storage.mkdir())
        } }
        assertNotNull(runCatching { manager.init() }.exceptionOrNull()); assertFalse(manager.supportsCatalogRecoveryReview)
    }
    @Test fun metadataReplacementAtFinalBoundaryRejectsOwner() = fixture { f ->
        val manager = ProjectManager.forApplication(f.app) { if (it == "application-catalog-before-resolution") {
            val metadata = File(f.app, ".droide"); assertTrue(metadata.renameTo(File(f.app, "old-metadata"))); assertTrue(metadata.mkdir())
        } }
        assertNotNull(runCatching { manager.init() }.exceptionOrNull()); assertFalse(manager.supportsCatalogRecoveryReview)
    }
    @Test fun applicationReplacementAtFinalBoundaryRejectsOwner() = fixture { f ->
        val backup = File(f.app.parentFile, f.app.name + ".preserved")
        try {
            val manager = ProjectManager.forApplication(f.app) { if (it == "application-catalog-before-resolution") {
                assertTrue(f.app.renameTo(backup)); assertTrue(f.app.mkdir())
            } }
            assertNotNull(runCatching { manager.init() }.exceptionOrNull()); assertFalse(manager.supportsCatalogRecoveryReview)
        } finally { PathSecurity.deleteTreeNoFollow(backup) }
    }
    @Test fun legacyRouteNeverSwitchesAfterNewSavedSelectionAppears() = runBlocking {
        val app = Files.createTempDirectory("legacy-owner-").toFile()
        try {
            val disk = FileProjectStore(app)
            val default = DroideProject("default", "Default", File(app, "projects/default").apply { mkdirs() }.path)
            disk.save(listOf(default)); disk.saveActiveId("default")
            var resolutions = 0
            val original = ProjectManager.forApplication(app) { if (it == "application-catalog-before-resolution") resolutions++ }
            original.init(); assertFalse(original.supportsCatalogRecoveryReview)
            val storage = File(app, "catalog-startup").apply { mkdir() }
            val admission = ProjectCatalogAdmission(app, storage, 1_000_000)
            val enrollment = CanonicalJournalBootstrap(admission.updateSlot, 1_000_000).createWithMembership(
                File(app, ".droide"), ProjectCatalogUpdate.AUTHORITY_ID, admission.updateMembership, EmptyCoroutineContext)
            val selection = admission.recordUpdateChoice(enrollment)
            JournaledProjectStore(selection, CanonicalJournalRetention.Policy(3, 100_000_000)).enableMembershipCommands()
            assertNotNull(runCatching { original.init() }.exceptionOrNull())
            assertFalse(original.supportsCatalogRecoveryReview); assertEquals(1, resolutions)
            val reopened = ProjectManager.forApplication(app); reopened.init(); assertTrue(reopened.supportsCatalogRecoveryReview)
        } finally { PathSecurity.deleteTreeNoFollow(app) }
    }
    @Test fun changedPersistedPolicyAtFinalBoundaryRejectsStaleRoute() = fixture { f ->
        val manager = ProjectManager.forApplication(f.app) { if (it == "application-catalog-before-resolution") {
            f.db { db ->
                db.beginTransaction()
                try {
                    val state = checkNotNull(CanonicalJournalRetention.read(db)); assertTrue(state.entries.isEmpty())
                    val replacement = CanonicalJournalRetention.Policy(7, state.policy.maxBytes)
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    for (value in listOf(replacement.maxEntries.toString(), replacement.maxBytes.toString())) {
                        val bytes = value.toByteArray(); digest.update(bytes.size.toString().toByteArray()); digest.update(':'.code.toByte()); digest.update(bytes)
                    }
                    val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                    db.execSQL("UPDATE retention SET max_entries=?,records_digest=? WHERE id=1", arrayOf<Any>(7, hash))
                    assertEquals(replacement, CanonicalJournalRetention.read(db)!!.policy)
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
        } }
        assertNotNull(runCatching { manager.init() }.exceptionOrNull())
        assertFalse(manager.supportsCatalogRecoveryReview); assertTrue(manager.projects.value.isEmpty())
    }
}
