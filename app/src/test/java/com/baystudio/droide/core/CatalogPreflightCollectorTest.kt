package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CatalogPreflightCollectorTest {
    private val editor = CatalogReleasePreflight.EditorEvidence(emptyList(), emptyList(), emptyList(), emptyList())
    private val processes = CatalogReleasePreflight.ProcessEvidence(emptyList(), emptyList(), emptyList())
    @Test fun actualReceiptAndCustodyCaptureMatchesDurableBytes() = runBlocking { fixture { f ->
        f.publish(2); val before=tree(f.app); val captured=f.update.collectReleaseReview()
        assertEquals(CanonicalFileOperationJournal.enrollmentHash(f.enrollment),captured.scope.authority)
        assertEquals(f.enrollment.rootKey,captured.scope.rootIdentity)
        val raw=mutableMapOf<String, String>(); var durableDigest=""
        CanonicalJournalFixture.openSql(f.database) { db ->
            db.rawQuery("SELECT id,record_json FROM completed_operation",null).use { row -> while(row.moveToNext()) raw[row.getString(0)]=row.getString(1) }
            db.rawQuery("SELECT records_digest FROM retention",null).use { row -> check(row.moveToFirst()); durableDigest=row.getString(0) }
        }
        assertEquals(raw,captured.records.associate { it.id to it.encoded })
        captured.records.forEach { r ->
            assertEquals("REPLACE",r.kind); assertEquals("projects.json",r.path)
            assertEquals(r.retainedBytes,r.custody.sumOf { it.version.size })
            r.custody.forEach { c -> assertEquals(CanonicalJournalFixture.key(File(f.enrollment.authorityPath,"custody/${c.name}")),c.version.identity) }
        }
        val result=f.update.reviewRelease(captured,captured.records.map { it.id },editor,processes) as CatalogReleasePreflight.Result.ObservedForReview
        assertEquals(durableDigest,result.observation.historyDigest)
        assertEquals(before,tree(f.app))
    } }
    @Test fun missingSubsystemProducersBlockActualCapture() = runBlocking { fixture { f ->
        f.publish(1); val captured=f.update.collectReleaseReview()
        val result=f.update.reviewRelease(captured,captured.records.map { it.id }) as CatalogReleasePreflight.Result.Blocked
        assertTrue(CatalogReleasePreflight.Blocker.EDITOR_EVIDENCE_UNAVAILABLE in result.blockers)
        assertTrue(CatalogReleasePreflight.Blocker.PROCESS_EVIDENCE_UNAVAILABLE in result.blockers)
    } }
    @Test fun collectorNeverEnablesRetention() = runBlocking { fixture { f ->
        val before=tree(f.app)
        refused("retention is unavailable") { f.update.collectReleaseReview() }
        assertEquals(before,tree(f.app))
    } }
    @Test fun policyMismatchIsRejectedWithoutRewrite() = runBlocking { fixture { f ->
        f.publish(1); val before=tree(f.app)
        val other=ProjectCatalogUpdate(f.app,f.enrollment,f.membership,1_000_000,CanonicalJournalRetention.Policy(128,100_000_000))
        refused("retention policy differs") { other.collectReleaseReview() }
        assertEquals(before,tree(f.app))
    } }
    @Test fun pendingOperationIsNotCertifiedAway() = runBlocking { fixture { f ->
        f.publish(1); f.update.prepare(listOf(f.initial.copy(name="Pending")),f.store.loadCatalog().revision)
        val captured=f.update.collectReleaseReview(); val before=tree(f.app)
        val result=f.update.reviewRelease(captured,captured.records.map { it.id },editor,processes) as CatalogReleasePreflight.Result.Blocked
        assertTrue(CatalogReleasePreflight.Blocker.PENDING_CATALOG_OPERATION in result.blockers)
        assertEquals(before,tree(f.app))
    } }
    @Test fun changedCurrentCatalogAfterSelectionBlocks() = runBlocking { fixture { f ->
        f.publish(1); val captured=f.update.collectReleaseReview()
        f.catalog.writeText(f.store.encodeCatalog(listOf(f.initial.copy(name="External edit"))))
        val result=f.update.reviewRelease(captured,captured.records.map { it.id },editor,processes) as CatalogReleasePreflight.Result.Blocked
        assertTrue(CatalogReleasePreflight.Blocker.CATALOG_CHANGED in result.blockers)
    } }
    @Test fun laterPublicationChangesFullHistory() = runBlocking { fixture { f ->
        f.publish(1); val captured=f.update.collectReleaseReview(); val prepared=f.update.prepare(listOf(f.initial.copy(name="Next")),f.store.loadCatalog().revision); f.update.resume(prepared.operationId)
        val result=f.update.reviewRelease(captured,captured.records.map { it.id },editor,processes) as CatalogReleasePreflight.Result.Blocked
        assertTrue(CatalogReleasePreflight.Blocker.HISTORY_CHANGED in result.blockers)
    } }
    @Test fun lateCustodyMutationIsRefused() = runBlocking { fixture { f ->
        f.publish(2); val id=f.update.inspect().completed.first().id
        f.hook={ if(it=="preflight-receipts-observed") mutate(f.custody(id,"before")) }
        refused("custody changed") { f.update.collectReleaseReview() }
    } }
    @Test fun secondPassCustodyReplacementIsRefused() = runBlocking { fixture { f ->
        f.publish(1); val intent=f.update.inspect().completed.single()
        f.hook={ if(it=="catalog-release-review-observed") {
            val target=f.custody(intent.id,"before"); val replacement=File(f.app,"replacement")
            replacement.writeBytes(target.readBytes()); Files.setAttribute(replacement.toPath(),"unix:mode",intent.snapshot!!.mode)
            Files.move(replacement.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING)
        } }
        refused("custody changed") { f.update.collectReleaseReview() }
    } }
    @Test fun catalogMutationDuringFinalCustodyPassIsRefused() = runBlocking { fixture { f ->
        f.publish(1); var visits=0; val changed=f.store.encodeCatalog(listOf(f.initial.copy(name="Changed")))
        f.hook={ if(it=="preflight-receipts-observed" && ++visits==2) f.catalog.writeText(changed) }
        refused("Catalog changed during preflight") { f.update.collectReleaseReview() }
        assertEquals(2,visits)
    } }
    @Test fun unknownCustodyIsPreservedAndRefused() = runBlocking { fixture { f ->
        f.publish(1); val unknown=File(f.enrollment.authorityPath,"custody/unknown")
        f.hook={ if(it=="preflight-receipts-observed") unknown.writeText("evidence") }
        refused("Custody exists without matching membership") { f.update.collectReleaseReview() }
        assertEquals("evidence",unknown.readText())
    } }
    @Test fun databaseReplacementCannotBeAccepted() = runBlocking { fixture { f ->
        f.publish(1)
        f.hook={ if(it=="catalog-release-review-observed") {
            val replacement=File(f.app,"replacement.sqlite"); replacement.writeBytes(f.database.readBytes())
            Files.move(replacement.toPath(),f.database.toPath(),StandardCopyOption.REPLACE_EXISTING)
        } }
        assertNotNull(runCatching { f.update.collectReleaseReview() }.exceptionOrNull())
    } }
    @Test fun cancellationPreservesBytesAndReturnsNoObservation() = runBlocking { fixture { f ->
        f.publish(1); val before=tree(f.app); val job=Job()
        f.hook={ if(it=="preflight-receipts-observed") job.cancel() }
        val failure=runCatching { withContext(job) { f.update.collectReleaseReview() } }.exceptionOrNull()
        assertTrue(failure is CancellationException); assertEquals(before,tree(f.app))
    } }
    @Test fun fullCapacityCollectionDoesNotRetireRetryReceipt() = runBlocking { fixture(limit=1) { f ->
        f.publish(1); val captured=f.update.collectReleaseReview(); val before=tree(f.app)
        val result=f.update.reviewRelease(captured,captured.records.map { it.id },editor,processes) as CatalogReleasePreflight.Result.Blocked
        assertTrue(CatalogReleasePreflight.Blocker.CAPACITY_EXHAUSTED in result.blockers)
        f.update.resume(captured.records.single().id); assertEquals(before,tree(f.app))
    } }
    @Test fun rawCodecCorruptionWithRecomputedDigestIsRejected() = runBlocking { fixture { f ->
        f.publish(1); rewrite(f) { it.copy(record=it.record+" ") }; val before=tree(f.app)
        assertNotNull(runCatching { f.update.collectReleaseReview() }.exceptionOrNull()); assertEquals(before,tree(f.app))
    } }
    @Test fun forgedChargeWithRecomputedDigestIsRejected() = runBlocking { fixture { f ->
        f.publish(1); rewrite(f) { it.copy(retainedBytes=0) }; val before=tree(f.app)
        assertNotNull(runCatching { f.update.collectReleaseReview() }.exceptionOrNull()); assertEquals(before,tree(f.app))
    } }
    @Test fun foreignPathWithValidMembershipDigestIsRejected() = runBlocking { fixture { f ->
        f.publish(1); rewrite(f) { it.copy(record=it.record.replace("projects.json","foreign.json")) }; val before=tree(f.app)
        assertNotNull(runCatching { f.update.collectReleaseReview() }.exceptionOrNull()); assertEquals(before,tree(f.app))
    } }
    @Test fun metadataRootReplacementIsRefusedAndPreserved() = runBlocking { fixture { f ->
        f.publish(1); val bytes=f.catalog.readBytes(); val original=File(f.app,"preserved-metadata")
        f.hook={ if(it=="catalog-release-review-observed") {
            Files.move(f.catalog.parentFile.toPath(),original.toPath())
            check(f.catalog.parentFile.mkdir()); f.catalog.writeBytes(bytes)
        } }
        assertNotNull(runCatching { f.update.collectReleaseReview() }.exceptionOrNull())
        assertArrayEquals(bytes,File(original,"projects.json").readBytes())
        assertArrayEquals(bytes,f.catalog.readBytes())
    } }
    @Test fun membershipOwnerSubstitutionIsRefusedAndPreserved() = runBlocking { fixture { f ->
        f.publish(1); val changed=f.membership.readText().replace(f.enrollment.projectId,"foreign-owner")
        assertNotEquals(f.membership.readText(),changed)
        f.hook={ if(it=="catalog-release-review-observed") f.membership.writeText(changed) }
        assertNotNull(runCatching { f.update.collectReleaseReview() }.exceptionOrNull())
        assertEquals(changed,f.membership.readText())
    } }
    private fun rewrite(f: Fixture, change: (CanonicalJournalRetention.Entry) -> CanonicalJournalRetention.Entry) {
        CanonicalJournalFixture.openSql(f.database) { db ->
            val state=checkNotNull(CanonicalJournalRetention.read(db)); val entry=change(state.entries.single())
            db.execSQL("UPDATE completed_operation SET record_json=?,retained_bytes=? WHERE id=?",arrayOf<Any>(entry.record,entry.retainedBytes,entry.id))
            val digest=java.security.MessageDigest.getInstance("SHA-256")
            listOf(state.policy.maxEntries.toString(),state.policy.maxBytes.toString(),entry.id,entry.retainedBytes.toString(),entry.record).forEach {
                val bytes=it.toByteArray(Charsets.UTF_8); digest.update(bytes.size.toString().toByteArray());digest.update(':'.code.toByte());digest.update(bytes)
            }
            val hash=digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            db.execSQL("UPDATE retention SET records_digest=? WHERE id=1",arrayOf(hash))
        }
    }
    private suspend fun refused(message: String, action: suspend () -> Unit) {
        val failure = runCatching { action() }.exceptionOrNull()
        assertTrue("Expected IllegalStateException containing '$message', got $failure",
            failure is IllegalStateException && failure.message.orEmpty().contains(message))
    }

    private class Fixture(val app: File, private val limit: Int) {
        val root = File(app, "projects/owned").apply { check(mkdirs()) }
        val initial = DroideProject("owned", "Initial", root.canonicalPath)
        val store = FileProjectStore(app)
        val catalog = File(app, ".droide/projects.json")
        val directory = File(app, "explicit-updates").apply { check(mkdir()) }
        val membership = File(directory, "membership.json")
        lateinit var enrollment: CanonicalFileOperationJournal.Enrollment
        lateinit var update: ProjectCatalogUpdate
        val database get() = File(enrollment.authorityPath, "state.sqlite")
        var hook: (String) -> Unit = {}
        suspend fun start() {
            store.save(listOf(initial))
            enrollment = CanonicalJournalBootstrap(File(directory, "slot"), 1_000_000)
                .createWithMembership(File(app, ".droide"), ProjectCatalogUpdate.AUTHORITY_ID, membership, EmptyCoroutineContext)
            update = fresh()
        }
        fun fresh() = ProjectCatalogUpdate(app, enrollment, membership, 1_000_000,
            CanonicalJournalRetention.Policy(limit, 100_000_000)) { hook(it) }
        fun custody(id: String, role: String) = File(enrollment.authorityPath, "custody/$id.$role")
        suspend fun publish(count: Int) { repeat(count) { index ->
            val prepared = update.prepare(listOf(initial.copy(name = "Publication $index")), store.loadCatalog().revision)
            update.resume(prepared.operationId)
        } }
    }
    private suspend fun fixture(limit: Int = 4, action: suspend (Fixture) -> Unit) {
        val app = Files.createTempDirectory("catalog-custody-boundary-").toFile()
        try { val f = Fixture(app, limit); f.start(); action(f) } finally { app.deleteRecursively() }
    }
    private fun mutate(file: File) { val bytes = file.readBytes(); bytes[0] = (bytes[0].toInt() xor 1).toByte(); file.writeBytes(bytes) }
    private fun tree(root: File): Map<String, String> = root.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(root).path to CanonicalJournalFixture.sha256(it.readBytes())
    }
}
