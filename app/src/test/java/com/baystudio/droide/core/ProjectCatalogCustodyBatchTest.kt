package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext
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
class ProjectCatalogCustodyBatchTest {
    @Test fun readyReadAndNextPrepareUseWholeHistoryTraversal() = runBlocking {
        val app = Files.createTempDirectory("catalog-batch-").toFile()
        try {
            val root = File(app, "projects/owned").apply { check(mkdirs()) }
            val initial = DroideProject("owned", "Initial", root.canonicalPath)
            val store = FileProjectStore(app)
            store.save(listOf(initial))
            val directory = File(app, "explicit-updates").apply { check(mkdir()) }
            val membership = File(directory, "membership.json")
            val enrollment = CanonicalJournalBootstrap(File(directory, "slot"), 1_000_000)
                .createWithMembership(File(app, ".droide"), ProjectCatalogUpdate.AUTHORITY_ID,
                    membership, EmptyCoroutineContext)
            var visits = 0
            val update = ProjectCatalogUpdate(app, enrollment, membership, 1_000_000,
                CanonicalJournalRetention.Policy(4, 100_000_000)) {
                if (it == "completed-custody-reviewed") visits++
            }
            repeat(3) { index ->
                val prepared = update.prepare(listOf(initial.copy(name = "Publication $index")), store.loadCatalog().revision)
                update.resume(prepared.operationId)
            }
            val history = update.inspect()
            assertEquals(3, history.completed.size)
            visits = 0
            assertEquals("Publication 2", update.readReadyCatalog().projects.single().name)
            assertEquals(3, visits)
            visits = 0
            val prepared = update.prepare(listOf(initial.copy(name = "Next")), store.loadCatalog().revision)
            assertEquals(3, visits)
            assertEquals(prepared.operationId, update.inspect().pending!!.id)
            assertEquals(history.completed, update.inspect().completed)
        } finally { app.deleteRecursively() }
    }

    @Test fun changedHistoricalCustodyBlocksActualReadAndPrepare() = runBlocking {
        val app = Files.createTempDirectory("catalog-batch-changed-").toFile()
        try {
            val root = File(app, "projects/owned").apply { check(mkdirs()) }
            val initial = DroideProject("owned", "Initial", root.canonicalPath)
            val store = FileProjectStore(app)
            store.save(listOf(initial))
            val directory = File(app, "explicit-updates").apply { check(mkdir()) }
            val membership = File(directory, "membership.json")
            val enrollment = CanonicalJournalBootstrap(File(directory, "slot"), 1_000_000)
                .createWithMembership(File(app, ".droide"), ProjectCatalogUpdate.AUTHORITY_ID,
                    membership, EmptyCoroutineContext)
            val update = ProjectCatalogUpdate(app, enrollment, membership, 1_000_000,
                CanonicalJournalRetention.Policy(4, 100_000_000))
            val prepared = update.prepare(listOf(initial.copy(name = "Published")), store.loadCatalog().revision)
            update.resume(prepared.operationId)
            val current = store.loadCatalog()
            val oldSnapshot = File(enrollment.authorityPath, "custody/${prepared.operationId}.before")
            val bytes = oldSnapshot.readBytes(); bytes[0] = (bytes[0].toInt() xor 1).toByte(); oldSnapshot.writeBytes(bytes)
            val database = File(enrollment.authorityPath, "state.sqlite").readBytes()
            assertTrue(runCatching { update.readReadyCatalog() }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { update.prepare(listOf(initial.copy(name = "Rejected")), current.revision) }
                .exceptionOrNull() is IllegalStateException)
            assertEquals(current, store.loadCatalog())
            assertNull(update.inspect().pending)
            assertArrayEquals(bytes, oldSnapshot.readBytes())
            assertArrayEquals(database, File(enrollment.authorityPath, "state.sqlite").readBytes())
        } finally { app.deleteRecursively() }
    }
}
