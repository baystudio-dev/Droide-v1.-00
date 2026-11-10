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
class CatalogEditorReviewTest {
    @Test fun realAdapterCleanEditorStillBlocksMissingProcessProducer() = runBlocking { fixture { f ->
        f.publish(1);val selected=f.update.collectReleaseReview();val state=com.baystudio.droide.ui.EditorWorkspaceState().apply{completeRecovery()}
        val editor=com.baystudio.droide.ui.EditorWorkspaceDocumentAuthority(state,kotlinx.coroutines.Dispatchers.Unconfined).observeAllDocuments()
        var validates=0;val review=CatalogEditorReview(selected.scope.root,editor,false,{}){validates++}
        val result=review.review(f.update,selected,selected.records.map{it.id}) as CatalogReleasePreflight.Result.Blocked
        assertEquals(setOf(CatalogReleasePreflight.Blocker.PROCESS_EVIDENCE_UNAVAILABLE),result.blockers)
        assertTrue(validates>=4)
    } }
    @Test fun changedOwnerDuringCatalogCollectionCannotReturnReview() = runBlocking { fixture { f ->
        f.publish(1);val selected=f.update.collectReleaseReview();var owner=true
        val editor=WorkspaceEditorObservation(emptyList(),emptySet(),emptySet(),emptySet()){}
        val review=CatalogEditorReview(selected.scope.root,editor,false,{}){check(owner){"Owner changed"}}
        f.hook={if(it=="catalog-release-review-observed")owner=false}
        refused("Owner changed"){review.review(f.update,selected,selected.records.map{it.id})}
    } }
    @Test fun changedEditorDuringCatalogCollectionCannotReturnReview() = runBlocking { fixture { f ->
        f.publish(1);val selected=f.update.collectReleaseReview();val state=com.baystudio.droide.ui.EditorWorkspaceState().apply{completeRecovery()}
        val editor=com.baystudio.droide.ui.EditorWorkspaceDocumentAuthority(state,kotlinx.coroutines.Dispatchers.Unconfined).observeAllDocuments()
        val review=CatalogEditorReview(selected.scope.root,editor,false,{}){}
        f.hook={if(it=="catalog-release-review-observed")state.document("late")}
        refused("Editor version or pending work changed"){review.review(f.update,selected,selected.records.map{it.id})}
    } }
    @Test fun retainedEditorAndPendingWorkRemainSeparateBlockers() = runBlocking { fixture { f ->
        f.publish(1);val selected=f.update.collectReleaseReview()
        val editor=WorkspaceEditorObservation(emptyList(),setOf("saving"),setOf("sync"),setOf("retained")){}
        val result=CatalogEditorReview(selected.scope.root,editor,true,{}){}.review(f.update,selected,selected.records.map{it.id}) as CatalogReleasePreflight.Result.Blocked
        assertEquals(setOf(CatalogReleasePreflight.Blocker.PENDING_SAVES,CatalogReleasePreflight.Blocker.PENDING_SYNC,
            CatalogReleasePreflight.Blocker.UNRESOLVED_HANDOFF,CatalogReleasePreflight.Blocker.PROCESS_EVIDENCE_UNAVAILABLE),result.blockers)
    } }
    @Test fun foreignApplicationCatalogCannotBeReviewed() = runBlocking { fixture { f ->
        f.publish(1);val selected=f.update.collectReleaseReview();val editor=WorkspaceEditorObservation(emptyList(),emptySet(),emptySet(),emptySet()){}
        refused("different application"){CatalogEditorReview("/foreign",editor,false,{}){}.review(f.update,selected,selected.records.map{it.id})}
    } }
    @Test fun ownerChangeDuringRootValidationIsRejected() = runBlocking {
        var owner=1;val order=mutableListOf<String>()
        val validator=CatalogEditorOwnerValidator(checkOwner={order+="owner";check(owner==1)},checkRoot={order+="root";owner=2})
        assertTrue(runCatching{validator.validate()}.isFailure)
        assertEquals(listOf("owner","root","owner"),order)
    }
    @Test fun cancellationDuringRootValidationCannotReturnEvidence() = runBlocking {
        val job=Job();var checks=0
        val validator=CatalogEditorOwnerValidator(checkOwner={checks++},checkRoot={job.cancel()})
        assertTrue(runCatching{withContext(job){validator.validate()}}.exceptionOrNull() is CancellationException)
        assertEquals(1,checks)
    }
    @Test fun gateAbaDuringRootValidationIsRejected() = runBlocking {
        val gate=WorkspaceHandoffGate();val observed=gate.observe()
        val validator=CatalogEditorOwnerValidator(checkOwner={gate.requireUnchanged(observed)},checkRoot={gate.publishOwner(Any());gate.publishOwner(null)})
        assertTrue(runCatching{validator.validate()}.isFailure)
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
