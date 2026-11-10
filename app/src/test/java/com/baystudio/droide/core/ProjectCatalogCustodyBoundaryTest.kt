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
class ProjectCatalogCustodyBoundaryTest {
    @Test fun lateMutationOfEachHistoricalRoleRefusesReadyRead() = runBlocking {
        for (role in listOf("before", "after", "retired")) fixture { f ->
            f.publish(3)
            val history = f.update.inspect()
            val selected = f.custody(history.completed.first().id, role)
            val database = f.database.readBytes()
            var visits = 0
            f.hook = { if (it == "completed-custody-reviewed" && ++visits == 3) mutate(selected) }
            refused("Completed catalog custody changed") { f.update.readReadyCatalog() }
            assertEquals(3, visits)
            f.hook = {}
            assertEquals(history, f.update.inspect())
            assertArrayEquals(database, f.database.readBytes())
            assertTrue(selected.exists())
        }
    }

    @Test fun lateIdentityReplacementRefusesReadyRead() = runBlocking { fixture { f ->
        f.publish(2)
        val history = f.update.inspect()
        val selected = f.custody(history.completed.first().id, "before")
        val bytes = selected.readBytes()
        var visits = 0
        f.hook = { if (it == "completed-custody-reviewed" && ++visits == 2) {
            val replacement = File(f.app, "independent-replacement")
            replacement.writeBytes(bytes)
            Files.setAttribute(replacement.toPath(), "unix:mode", history.completed.first().snapshot!!.mode)
            Files.move(replacement.toPath(), selected.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } }
        refused("Completed catalog custody changed") { f.update.readReadyCatalog() }
        f.hook = {}
        assertEquals(history, f.update.inspect())
        assertArrayEquals(bytes, selected.readBytes())
    } }

    @Test fun lateUnknownCustodyRefusesReadyReadAndPreservesIt() = runBlocking { fixture { f ->
        f.publish(1)
        val unknown = File(f.enrollment.authorityPath, "custody/unclaimed")
        val database = f.database.readBytes()
        f.hook = { if (it == "completed-custody-reviewed") unknown.writeText("preserve unknown evidence") }
        refused("Custody exists without matching membership") { f.update.readReadyCatalog() }
        assertEquals("preserve unknown evidence", unknown.readText())
        assertArrayEquals(database, f.database.readBytes())
    } }

    @Test fun catalogChangeDuringCustodyReadCannotReturnStaleReadyCatalog() = runBlocking { fixture { f ->
        f.publish(1)
        val database = f.database.readBytes()
        val changed = f.store.encodeCatalog(listOf(f.initial.copy(name = "Concurrent edit")))
        f.hook = { if (it == "completed-custody-reviewed") f.catalog.writeText(changed) }
        refused("Catalog changed during custody validation") { f.update.readReadyCatalog() }
        assertEquals(changed, f.catalog.readText())
        assertArrayEquals(database, f.database.readBytes())
    } }

    @Test fun changedCustodyCannotAdmitNextIntent() = runBlocking { fixture { f ->
        f.publish(2)
        val history = f.update.inspect()
        val current = f.store.loadCatalog()
        val database = f.database.readBytes()
        val selected = f.custody(history.completed.first().id, "before")
        var visits = 0
        f.hook = { if (it == "completed-custody-reviewed" && ++visits == 2) mutate(selected) }
        refused("Completed catalog custody changed") {
            f.update.prepare(listOf(f.initial.copy(name = "Must not be admitted")), current.revision)
        }
        f.hook = {}
        assertEquals(history, f.update.inspect())
        assertEquals(current, f.store.loadCatalog())
        assertArrayEquals(database, f.database.readBytes())
    } }

    @Test fun resumedNewCompletionMustAlsoValidateOlderCustody() = runBlocking { fixture { f ->
        f.publish(1)
        val first = f.update.inspect().completed.single()
        val prepared = f.update.prepare(listOf(f.initial.copy(name = "Second")), f.store.loadCatalog().revision)
        var changed = false
        f.hook = { if (it == "catalog-update-completed") { mutate(f.custody(first.id, "before")); changed = true } }
        refused("Completed catalog custody changed") { f.update.resume(prepared.operationId) }
        assertTrue(changed)
        f.hook = {}
        val history = f.update.inspect()
        assertNull(history.pending)
        assertEquals(2, history.completed.size)
        assertEquals("Second", f.store.loadCatalog().projects.single().name)
        val database = f.database.readBytes()
        refused("Completed catalog custody changed") { f.update.resume(prepared.operationId) }
        assertArrayEquals(database, f.database.readBytes())
        assertEquals(history, f.update.inspect())
    } }

    @Test fun committedReturnLossReusesReceiptAtCapacityWithoutAddingHistory() = runBlocking { fixture(limit = 1) { f ->
        val prepared = f.update.prepare(listOf(f.initial.copy(name = "Committed")), f.store.loadCatalog().revision)
        f.hook = { if (it == "catalog-update-completed") error("Injected return loss") }
        refused("Injected return loss") { f.update.resume(prepared.operationId) }
        f.hook = {}
        val history = f.update.inspect()
        val allFiles = tree(f.app)
        assertNull(history.pending)
        assertEquals(1, history.retentionUsage!!.completedEntries)
        repeat(3) {
            assertEquals("Committed", f.fresh().resume(prepared.operationId).projects.single().name)
            assertEquals(history, f.update.inspect())
            assertEquals(allFiles, tree(f.app))
        }
        val failure = runCatching {
            f.fresh().prepare(listOf(f.initial.copy(name = "Over capacity")), f.store.loadCatalog().revision)
        }.exceptionOrNull()
        assertTrue(failure is CanonicalJournalRetention.CapacityException)
        assertEquals(history, f.update.inspect())
        assertEquals(allFiles, tree(f.app))
    } }

    @Test fun unchangedReadsRemainReadOnlyAndDoNotAdvanceHistory() = runBlocking { fixture { f ->
        f.publish(3)
        val history = f.update.inspect()
        val allFiles = tree(f.app)
        repeat(3) {
            assertEquals("Publication 2", f.fresh().readReadyCatalog().projects.single().name)
            assertEquals(history, f.update.inspect())
            assertEquals(allFiles, tree(f.app))
        }
    } }

    @Test fun completedReceiptResumeRechecksCatalogAfterCustodyValidation() = runBlocking { fixture { f ->
        f.publish(1)
        val history = f.update.inspect()
        val database = f.database.readBytes()
        val changed = f.store.encodeCatalog(listOf(f.initial.copy(name = "Concurrent resume edit")))
        var changedAtBoundary = false
        f.hook = { if (it == "completed-custody-reviewed") { f.catalog.writeText(changed); changedAtBoundary = true } }
        refused("Catalog changed after publication") { f.fresh().resume(history.completed.single().id) }
        assertTrue(changedAtBoundary)
        f.hook = {}
        assertEquals(history, f.update.inspect())
        assertEquals(changed, f.catalog.readText())
        assertArrayEquals(database, f.database.readBytes())
    } }

    @Test fun cancellationAtCustodyBoundaryNeverReturnsReadyOrRewritesEvidence() = runBlocking { fixture { f ->
        f.publish(2)
        val history = f.update.inspect()
        val allFiles = tree(f.app)
        val job = Job()
        var visits = 0
        f.hook = { if (it == "completed-custody-reviewed" && ++visits == 2) job.cancel() }
        val failure = runCatching { withContext(job) { f.update.readReadyCatalog() } }.exceptionOrNull()
        assertTrue("Expected cancellation, got $failure", failure is CancellationException)
        assertEquals(2, visits)
        f.hook = {}
        assertEquals(history, f.update.inspect())
        assertEquals(allFiles, tree(f.app))
    } }

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
