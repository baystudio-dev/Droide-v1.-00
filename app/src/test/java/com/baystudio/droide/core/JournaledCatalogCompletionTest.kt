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

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class JournaledCatalogCompletionTest {
    private class Fixture(val app: File) {
        val root = File(app, "projects/default").apply { check(mkdirs()) }
        val initial = DroideProject("default", "Default", root.canonicalPath)
        val disk = FileProjectStore(app)
        val storage = File(app, "explicit-startup").apply { check(mkdir()) }
        val admission = ProjectCatalogAdmission(app, storage, 1_000_000)
        lateinit var enrollment: CanonicalFileOperationJournal.Enrollment
        lateinit var selected: ProjectCatalogAdmission.UpdateSelection
        lateinit var store: JournaledProjectStore
        lateinit var manager: ProjectManager
        var hook: (String) -> Unit = {}
        var admissions = 0
        var reviews = 0
        private fun checkpoint(point: String) {
            if (point == "admission-after-commit") admissions++
            if (point == "catalog-update-completion-review") reviews++
            hook(point)
        }
        fun updater() = selected.guardedUpdate(CanonicalJournalRetention.Policy(8, 100_000_000), EmptyCoroutineContext, ::checkpoint)
        suspend fun reopenedStore() = JournaledProjectStore(checkNotNull(admission.inspectUpdateChoice()),
            CanonicalJournalRetention.Policy(8, 100_000_000), checkpoint = ::checkpoint)
        suspend fun requested() = store.loadCatalog().projects.map { it.copy(name = "Proven") }
        fun tree(): Map<String, Pair<String, String>> = app.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(app).path to (CanonicalJournalFixture.key(it) to CanonicalJournalFixture.sha256(it.readBytes()))
        }
        suspend fun start() {
            disk.save(listOf(initial)); disk.saveActiveId(initial.id)
            enrollment = CanonicalJournalBootstrap(admission.updateSlot, 1_000_000)
                .createWithMembership(File(app, ".droide"), ProjectCatalogUpdate.AUTHORITY_ID, admission.updateMembership, EmptyCoroutineContext)
            selected = admission.recordUpdateChoice(enrollment)
            store = JournaledProjectStore(selected, CanonicalJournalRetention.Policy(8, 100_000_000), checkpoint = ::checkpoint)
            manager = ProjectManager(app, store).apply { init() }
        }
    }
    private fun fixture(action: suspend (Fixture) -> Unit) = runBlocking {
        val app = Files.createTempDirectory("journaled-completion-").toFile()
        try { val f = Fixture(app); f.start(); action(f) }
        finally { PathSecurity.deleteTreeNoFollow(app) }
    }
    @Test fun committedStoreFailureReturnsExactProvenRevisionWithoutSecondAdmission() = fixture { f ->
        val before = f.store.loadCatalog()
        val requested = listOf(f.initial.copy(name = "Proven"))
        f.hook = { if (it == "catalog-update-completed") throw IOException("lost completion return") }
        val result = f.store.saveCatalog(requested, before.revision)
        assertEquals(f.disk.catalogRevision(requested), result)
        assertEquals(requested, f.store.load())
        assertEquals(1, f.admissions)
        assertEquals(1, f.store.inspectRecovery().completed.size)
    }
    @Test fun managerNormalSuccessPublishesOnceAndAllowsNextCommand() = fixture { f ->
        var once = true
        f.hook = { if (it == "catalog-update-completed" && once) { once = false; throw IOException("lost return") } }
        val created = f.manager.add("First")
        assertTrue(f.manager.projects.value.any { it.id == created.id })
        assertEquals("default", f.manager.activeId.value)
        val next = f.manager.add("Second")
        assertTrue(f.manager.projects.value.any { it.id == next.id })
        assertEquals(2, f.admissions)
        assertEquals(2, f.store.inspectRecovery().completed.size)
    }

    @Test fun reconciliationReadsWithoutChangingAnyReceiptCatalogOrCustodyFile() = fixture { f ->
        val before = f.store.loadCatalog(); val requested = f.requested()
        var committed: Map<String, Pair<String, String>>? = null
        f.hook = { if (it == "catalog-update-completed") { committed = f.tree(); throw IOException("lost") } }
        f.store.saveCatalog(requested, before.revision)
        assertEquals(committed, f.tree())
        assertEquals(1, f.admissions); assertEquals(1, f.reviews)
    }

    @Test fun selectedStorePostPublicationFailureAlsoReconcilesOnce() = fixture { f ->
        val before = f.store.loadCatalog(); val requested = f.requested()
        f.hook = { if (it == "selected-store-published") throw IOException("lost store return") }
        assertEquals(f.disk.catalogRevision(requested), f.store.saveCatalog(requested, before.revision))
        assertEquals(1, f.admissions); assertEquals(1, f.reviews)
    }

    private fun unresolvedAt(point: String) = fixture { f ->
        val before = f.store.loadCatalog(); val original = IOException("injected $point")
        f.hook = { if (it == point) throw original }
        val failure = runCatching { f.store.saveCatalog(f.requested(), before.revision) }.exceptionOrNull()!!
        assertTrue(ProjectMetadataPublication.isUnconfirmed(failure))
        assertSame(original, failure.cause)
        f.hook = {}
        assertNotNull(f.store.inspectRecovery().pending)
        assertTrue(f.store.inspectRecovery().completed.isEmpty())
        assertEquals(1, f.admissions); assertEquals(0, f.reviews)
    }
    @Test fun lostEarlyPreparationReturnNeverRecreatesOrResumes() = unresolvedAt("admission-after-commit")
    @Test fun knownStagedRequestIsNotCompletedByResolver() = unresolvedAt("selected-store-prepared")
    @Test fun archiveRollbackIsNotRearchivedByResolver() = unresolvedAt("completion-archive-before-commit")

    private fun originalInterruption(original: Throwable) = fixture { f ->
        val before = f.store.loadCatalog()
        f.hook = { if (it == "catalog-update-completed") throw original }
        val failure = runCatching { f.store.saveCatalog(f.requested(), before.revision) }.exceptionOrNull()!!
        assertSame(original, failure.cause)
        if (original is Error) assertTrue(failure is Error)
        else assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertEquals(0, f.reviews)
        f.hook = {}; assertEquals(1, f.store.inspectRecovery().completed.size)
    }
    @Test fun originalCancellationNeverEntersReconciliation() = originalInterruption(kotlinx.coroutines.CancellationException("injected cancellation"))
    @Test fun originalFatalNeverEntersReconciliation() = originalInterruption(AssertionError("injected fatal"))

    private fun proofInterruption(proof: Throwable) = fixture { f ->
        val original = IOException("original completion loss"); val before = f.store.loadCatalog()
        f.hook = {
            if (it == "catalog-update-completed") throw original
            if (it == "catalog-update-completion-review") throw proof
        }
        val failure = runCatching { f.store.saveCatalog(f.requested(), before.revision) }.exceptionOrNull()!!
        assertSame(proof, failure.cause)
        assertTrue(proof.suppressed.any { it === original })
        if (proof is Error) assertTrue(failure is Error) else assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertEquals(1, f.reviews)
    }
    @Test fun proofCancellationRemainsPrimaryWithOriginalSuppressed() = proofInterruption(kotlinx.coroutines.CancellationException("proof cancellation"))
    @Test fun proofFatalRemainsPrimaryWithOriginalSuppressed() = proofInterruption(AssertionError("proof fatal"))

    @Test fun ordinaryProofFailureKeepsOriginalAndDoesNotRetry() = fixture { f ->
        val original = IOException("original"); val proof = IOException("proof"); val before = f.store.loadCatalog()
        f.hook = {
            if (it == "catalog-update-completed") throw original
            if (it == "catalog-update-completion-review") throw proof
        }
        val failure = runCatching { f.store.saveCatalog(f.requested(), before.revision) }.exceptionOrNull()!!
        assertSame(original, failure.cause); assertTrue(original.suppressed.any { it === proof })
        assertEquals(1, f.reviews); assertEquals(1, f.admissions)
    }

    private fun replacedAfterCommit(selection: Boolean) = fixture { f ->
        val original = IOException("return lost after substitution"); val before = f.store.loadCatalog()
        f.hook = { if (it == "catalog-update-completed") {
            val target = if (selection) File(f.storage, "startup-admission.json") else File(f.app, ".droide/projects.json")
            val replacement = File(f.app, "substitute")
            replacement.writeBytes(target.readBytes())
            Files.setAttribute(replacement.toPath(), "unix:mode", Files.getAttribute(target.toPath(), "unix:mode"))
            Files.move(replacement.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            throw original
        } }
        val failure = runCatching { f.store.saveCatalog(f.requested(), before.revision) }.exceptionOrNull()!!
        assertSame(original, failure.cause); assertTrue(original.suppressed.isNotEmpty())
        assertEquals(1, f.admissions)
    }
    @Test fun sameBytesNewCatalogInodeCannotProveThisPublication() = replacedAfterCommit(false)
    @Test fun changedSelectionCannotBeReconciled() = replacedAfterCommit(true)

    @Test fun changedCustodyCannotBeReconciled() = fixture { f ->
        val original = IOException("lost with changed custody"); val before = f.store.loadCatalog()
        f.hook = { if (it == "catalog-update-completed") {
            val retained = File(f.enrollment.authorityPath, "custody").listFiles()!!.first { it.name.endsWith(".before") }
            retained.appendText("changed"); throw original
        } }
        val failure = runCatching { f.store.saveCatalog(f.requested(), before.revision) }.exceptionOrNull()!!
        assertSame(original, failure.cause); assertTrue(original.suppressed.isNotEmpty())
        assertEquals(1, f.admissions)
    }

    @Test fun directResolverRejectsMissingForeignAndChangedRequestCarriers() = fixture { f ->
        val updater = f.updater(); val before = f.store.loadCatalog()
        val operation = updater.prepareOperation(f.requested(), before.revision)
        updater.resume(operation.prepared.operationId)
        val receipt = f.store.inspectRecovery().completed.single()
        val binding = CanonicalFileOperationJournal.enrollmentHash(f.enrollment)
        val variants = listOf(
            ProjectCatalogUpdate.PreparedOperation(operation.prepared.copy(operationId = "3066e46f-30db-4415-8197-66d972246771"), binding, receipt),
            ProjectCatalogUpdate.PreparedOperation(operation.prepared, "0".repeat(64), receipt),
            ProjectCatalogUpdate.PreparedOperation(operation.prepared.copy(proposed = operation.prepared.before), binding, receipt),
            ProjectCatalogUpdate.PreparedOperation(operation.prepared, binding, receipt.copy(proposal = receipt.proposal.copy(mode = 420))),
        )
        val tree = f.tree()
        for (variant in variants) assertTrue(runCatching { updater.readCompleted(variant) }.isFailure)
        assertEquals(tree, f.tree())
    }

    @Test fun carrierOwnsParentIdentitySnapshotAcrossFreshUpdater() = fixture { f ->
        val updater = f.updater(); val before = f.store.loadCatalog()
        val operation = updater.prepareOperation(f.requested(), before.revision)
        updater.resume(operation.prepared.operationId)
        val receipt = f.store.inspectRecovery().completed.single()
        val parents = receipt.parents.toMutableList()
        val carrier = ProjectCatalogUpdate.PreparedOperation(operation.prepared,
            CanonicalFileOperationJournal.enrollmentHash(f.enrollment), receipt.copy(parents = parents))
        parents.clear()
        val tree = f.tree()
        assertEquals(f.store.loadCatalog(), f.updater().readCompleted(carrier))
        assertEquals(tree, f.tree()); assertEquals(1, f.admissions)
    }

    @Test fun activeSelectionFailureDoesNotUseCatalogResolver() = fixture { f ->
        f.hook = { if (it == "selected-store-active-saved") throw IOException("selection failure") }
        assertTrue(runCatching { f.store.saveActiveId("default") }.isFailure)
        assertEquals(0, f.reviews)
    }

    @Test fun actualJobCancellationDuringProofRemainsCancelled() = fixture { f ->
        val original = IOException("original"); val before = f.store.loadCatalog()
        val requested = f.requested(); val job = kotlinx.coroutines.Job()
        f.hook = {
            if (it == "catalog-update-completed") throw original
            if (it == "catalog-update-completion-review") job.cancel()
        }
        val failure = runCatching {
            kotlinx.coroutines.withContext(job) { f.store.saveCatalog(requested, before.revision) }
        }.exceptionOrNull()!!
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertEquals(1, f.reviews); assertEquals(1, f.admissions)
        f.hook = {}; assertEquals(1, f.store.inspectRecovery().completed.size)
    }

    @Test fun freshSelectedStoreAndManagerReadCompletedWithoutReadmission() = fixture { f ->
        val before = f.store.loadCatalog(); val requested = f.requested()
        f.hook = { if (it == "catalog-update-completed") throw IOException("lost") }
        f.store.saveCatalog(requested, before.revision)
        f.hook = {}
        val history = f.store.inspectRecovery()
        val reopened = f.reopenedStore()
        val manager = ProjectManager(f.app, reopened).apply { init() }
        assertEquals(requested, manager.projects.value)
        assertEquals(history, reopened.inspectRecovery()); assertEquals(1, f.admissions)
    }

    @Test fun normalManagerRetainedRelocationFinishesAfterProvenCatalogCommit() = fixture { f ->
        val project = f.manager.add("Retain")
        File(project.rootPath, "payload").writeText("preserve")
        f.hook = { if (it == "catalog-update-completed") throw IOException("lost deletion catalog return") }
        val removed = f.manager.deleteWorkspace(project.id)!!
        assertEquals(project.id, removed.projectId)
        assertFalse(File(project.rootPath).exists())
        assertEquals("preserve", File(checkNotNull(removed.recoveryPath), "payload").readText())
        assertFalse(f.manager.projects.value.any { it.id == project.id })
        assertEquals(2, f.admissions); assertEquals(1, f.reviews)
    }

    @Test fun pendingAdmissionDuringProofCannotRebaseCompletedHistory() = fixture { f ->
        val original = IOException("original loss"); val before = f.store.loadCatalog()
        val bytes = f.disk.encodeCatalog(listOf(f.initial.copy(name = "Concurrent next request"))).toByteArray()
        f.hook = {
            if (it == "catalog-update-completed") throw original
            if (it == "catalog-update-completion-review") {
                val metadata = File(f.app, ".droide")
                val observed = AnchoredWorkspaceFiles(metadata).use { files ->
                    CanonicalFileSnapshot(1_000_000).inspect(files, "projects.json", EmptyCoroutineContext)
                }
                CanonicalFileOperationJournal(metadata, File(f.enrollment.authorityPath), f.enrollment, 1_000_000)
                    .admit("projects.json", observed,
                        CanonicalFileOperationJournal.Content(bytes.size.toLong(), CanonicalJournalFixture.sha256(bytes), 384),
                        EmptyCoroutineContext)
            }
        }
        val failure = runCatching { f.store.saveCatalog(f.requested(), before.revision) }.exceptionOrNull()
        assertNotNull("Changed history cannot be accepted as this completed proof", failure)
        assertSame(original, failure!!.cause)
        assertTrue(original.suppressed.isNotEmpty())
        f.hook = {}
        assertNotNull(f.store.inspectRecovery().pending)
        assertEquals(1, f.store.inspectRecovery().completed.size)
    }
}
