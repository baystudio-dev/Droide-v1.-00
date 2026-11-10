package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Real v2 journal boundaries. These are injected failures/reopens, not power-loss tests or v3 integration. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CatalogRequestCrashContractTest {
    private class Fixture(val app: File) {
        val root = File(app, "projects/owned").apply { check(mkdirs()) }
        val initial = DroideProject("owned", "Initial", root.canonicalPath)
        val store = FileProjectStore(app)
        val directory = File(app, "explicit-updates").apply { check(mkdir()) }
        val membership = File(directory, "membership.json")
        lateinit var enrollment: CanonicalFileOperationJournal.Enrollment
        var hook: (String) -> Unit = {}
        suspend fun start() {
            store.save(listOf(initial))
            enrollment = CanonicalJournalBootstrap(File(directory, "slot"), 1_000_000)
                .createWithMembership(File(app, ".droide"), ProjectCatalogUpdate.AUTHORITY_ID, membership, EmptyCoroutineContext)
        }
        fun update() = ProjectCatalogUpdate(app, enrollment, membership, 1_000_000,
            CanonicalJournalRetention.Policy(4, 100_000_000)) { hook(it) }
        suspend fun prepare() = update().prepare(listOf(initial.copy(name = "Proposed")), store.loadCatalog().revision)
        fun assertVersion2() = CanonicalJournalFixture.openSql(File(enrollment.authorityPath, "state.sqlite")) {
            assertEquals(2, it.version)
        }
    }
    private fun fixture(action: suspend (Fixture) -> Unit) = runBlocking {
        val app = Files.createTempDirectory("request-contract-").toFile()
        try { val f = Fixture(app); f.start(); action(f); f.assertVersion2() }
        finally { PathSecurity.deleteTreeNoFollow(app) }
    }
    @Test fun admissionBeforeCommitRollsBackPendingWithoutCanonicalMutation() = fixture { f ->
        val before = f.store.loadCatalog()
        f.hook = { if (it == "admission-before-commit") throw IOException("injected") }
        assertTrue(runCatching { f.prepare() }.isFailure)
        f.hook = {}
        assertNull(f.update().inspect().pending)
        assertEquals(before, f.store.loadCatalog())
    }
    @Test fun admissionLostReturnIsDiscoverableButHashCannotResumePreparation() = fixture { f ->
        val before = f.store.loadCatalog()
        f.hook = { if (it == "admission-after-commit") throw IOException("lost return") }
        assertTrue(runCatching { f.prepare() }.isFailure)
        f.hook = {}
        val pending = f.update().inspect().pending!!
        assertEquals(CanonicalFileOperationJournal.Phase.PREPARED, pending.phase)
        val descriptor = CatalogRequestContract.Descriptor(CatalogRequestContract.Token(
            CatalogRequestContract.Authority(f.enrollment.generation, CanonicalFileOperationJournal.enrollmentHash(f.enrollment)), 1),
            pending.parents.single().key, pending.before!!, pending.proposal)
        assertEquals(descriptor, CatalogRequestContract.decodeDescriptor(CatalogRequestContract.encodeDescriptor(descriptor)))
        val failure = runCatching { f.update().resume(pending.id) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("preparation is incomplete"))
        assertTrue(runCatching { f.prepare() }.isFailure)
        assertEquals(pending, f.update().inspect().pending)
        assertEquals(before, f.store.loadCatalog())
    }
    @Test fun stagedPrepareLostReturnCanResumeOnlyTheDiscoveredExactOperation() = fixture { f ->
        f.hook = { if (it == "catalog-update-prepared") throw IOException("lost prepared return") }
        assertTrue(runCatching { f.prepare() }.isFailure)
        f.hook = {}
        val pending = f.update().inspect().pending!!
        assertEquals(CanonicalFileOperationJournal.Phase.PUBLICATION_STAGED, pending.phase)
        val published = f.update().resume(pending.id)
        assertEquals("Proposed", published.projects.single().name)
        assertEquals(listOf(pending.id), f.update().inspect().completed.map { it.id })
    }
    @Test fun completionLostReturnReadsSameReceiptWithoutRepublishing() = fixture { f ->
        val prepared = f.prepare()
        f.hook = { if (it == "catalog-update-completed") throw IOException("lost completion return") }
        assertTrue(runCatching { f.update().resume(prepared.operationId) }.isFailure)
        f.hook = {}
        val history = f.update().inspect()
        val before = f.store.loadCatalog()
        assertNull(history.pending)
        assertEquals(prepared.operationId, history.completed.single().id)
        assertEquals(before, f.update().resume(prepared.operationId))
        assertEquals(history, f.update().inspect())
    }
    @Test fun unknownLegacyUuidNeverRecreatesAnOperation() = fixture { f ->
        val prepared = f.prepare(); f.update().resume(prepared.operationId)
        val before = f.store.loadCatalog(); val history = f.update().inspect()
        val failure = runCatching { f.update().resume("3066e46f-30db-4415-8197-66d972246771") }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("no update was recreated"))
        assertEquals(before, f.store.loadCatalog()); assertEquals(history, f.update().inspect())
    }
}
