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
class CatalogProcessReviewTest {

    @Test fun realCatalogReviewMapsActualChildResourceObservationToRetainedNotLive()=runBlocking {fixture {f->
        f.publish(1);val selected=f.update.collectReleaseReview();val jobs=SubagentJobOwners();val job=Job()
        jobs["fixture-child"]=SubagentOwnedJob(1,job)
        val terminal=TerminalSession(f.root,this);val resources=SubagentOwnedResources{_,_->}
        resources.registerTerminal("fixture terminal",terminal,f.root.absolutePath){terminal.destroy()}
        val binding=jobs.attachResources("fixture-child",1,job,f.root.absolutePath,resources)
        try {
            val children=jobs.observe();val processes=CatalogProcessObservation.capture(f.root)
            val editor=WorkspaceEditorObservation(emptyList(),emptySet(),emptySet(),emptySet()){}
            val result=CatalogEditorReview(selected.scope.root,editor,false,{},processes,children=children){}.review(f.update,selected,selected.records.map{it.id}) as CatalogReleasePreflight.Result.Blocked
            assertTrue(CatalogReleasePreflight.Blocker.RETAINED_PROCESS_OWNERS in result.blockers)
            assertTrue(CatalogReleasePreflight.Blocker.UNKNOWN_PROCESS_COVERAGE in result.blockers)
            assertFalse(CatalogReleasePreflight.Blocker.LIVE_PROCESS_OWNERS in result.blockers)
        } finally {resources.use{};binding.close();jobs.clear();job.cancel()}
    }}
    @Test fun childOwnerRemovalDuringRealCatalogCollectionInvalidatesReview()=runBlocking {fixture {f->
        f.publish(1);val selected=f.update.collectReleaseReview();val jobs=SubagentJobOwners();val job=Job()
        jobs["fixture-child"]=SubagentOwnedJob(1,job)
        try {
            val children=jobs.observe();val processes=CatalogProcessObservation.capture(f.root)
            val editor=WorkspaceEditorObservation(emptyList(),emptySet(),emptySet(),emptySet()){}
            f.hook={if(it=="catalog-release-review-observed")jobs.clear()}
            refused("Child task ownership changed"){CatalogEditorReview(selected.scope.root,editor,false,{},processes,children=children){}.review(f.update,selected,selected.records.map{it.id})}
        } finally {jobs.clear();job.cancel()}
    }}
    @Test fun actualProcessProducerAlwaysRetainsClosureAndCoverageBlockers()=runBlocking {fixture {f->
        f.publish(1);val selected=f.update.collectReleaseReview()
        val editor=WorkspaceEditorObservation(emptyList(),emptySet(),emptySet(),emptySet()){}
        val processes=CatalogProcessObservation.capture(f.root)
        val result=CatalogEditorReview(selected.scope.root,editor,false,{},processes){}.review(f.update,selected,selected.records.map{it.id}) as CatalogReleasePreflight.Result.Blocked
        assertTrue(CatalogReleasePreflight.Blocker.UNKNOWN_PROCESS_COVERAGE in result.blockers)
        assertTrue(CatalogReleasePreflight.Blocker.UNKNOWN_DESCENDANT_OWNERSHIP in result.blockers)
        assertTrue(CatalogReleasePreflight.Blocker.UNKNOWN_DESCRIPTOR_OWNERSHIP in result.blockers)
        assertFalse(CatalogReleasePreflight.Blocker.PROCESS_EVIDENCE_UNAVAILABLE in result.blockers)
    }}
    @Test fun transientWriterDuringCatalogCollectionInvalidatesObservation()=runBlocking {fixture {f->
        f.publish(1);val selected=f.update.collectReleaseReview()
        val editor=WorkspaceEditorObservation(emptyList(),emptySet(),emptySet(),emptySet()){}
        val processes=CatalogProcessObservation.capture(f.root)
        f.hook={if(it=="catalog-release-review-observed")ExportWriterAdmission.beginWriter(f.root,"transient").close()}
        refused("writer registrations changed"){CatalogEditorReview(selected.scope.root,editor,false,{},processes){}.review(f.update,selected,selected.records.map{it.id})}
    }}
    @Test fun knownWriterRemainsBlockerWithoutInvokingOwner()=runBlocking {fixture {f->
        f.publish(1);val selected=f.update.collectReleaseReview();val ticket=ExportWriterAdmission.beginWriter(f.root,"fixture owner")
        try {
            val editor=WorkspaceEditorObservation(emptyList(),emptySet(),emptySet(),emptySet()){}
            val processes=CatalogProcessObservation.capture(f.root)
            val result=CatalogEditorReview(selected.scope.root,editor,false,{},processes){}.review(f.update,selected,selected.records.map{it.id}) as CatalogReleasePreflight.Result.Blocked
            assertTrue(CatalogReleasePreflight.Blocker.PARTICIPATING_WRITERS in result.blockers)
            assertFalse(CatalogReleasePreflight.Blocker.LIVE_PROCESS_OWNERS in result.blockers)
            assertTrue(ExportWriterAdmission.snapshotBlockers(f.root).contains("fixture owner"))
        } finally {ticket.close()}
    }}
    @Test fun deadUnconfirmedHandleIsRetainedNotReportedLive()=runBlocking {fixture {f->
        f.publish(1);val selected=f.update.collectReleaseReview();val done=kotlinx.coroutines.CompletableDeferred<Unit>()
        val peer=object:HostedStdioProcess {
            override val stdin=java.io.ByteArrayOutputStream()
            override val stdout=java.io.ByteArrayInputStream(byteArrayOf())
            override val stderr=java.io.ByteArrayInputStream(byteArrayOf())
            override val isAlive=false
            override val exitCode=0
            override fun close(){error("Review must not stop")}
            override suspend fun awaitExit():Int{done.await();return 0}
        }
        val retainedBefore=ExitHostedProcessRegistry.observe().retainedHandles
        WorkspaceProcessWriteAdmission.create(f.root){peer}
        try {
            val editor=WorkspaceEditorObservation(emptyList(),emptySet(),emptySet(),emptySet()){}
            val processes=CatalogProcessObservation.capture(f.root)
            assertTrue(processes.evidence().liveOwners.isEmpty());assertTrue(processes.evidence().retainedOwners.isNotEmpty())
            val result=CatalogEditorReview(selected.scope.root,editor,false,{},processes){}.review(f.update,selected,selected.records.map{it.id}) as CatalogReleasePreflight.Result.Blocked
            assertTrue(CatalogReleasePreflight.Blocker.RETAINED_PROCESS_OWNERS in result.blockers)
            assertFalse(CatalogReleasePreflight.Blocker.LIVE_PROCESS_OWNERS in result.blockers)
        } finally {
            done.complete(Unit)
            kotlinx.coroutines.withTimeout(5000){while(WorkspaceProcessWriteAdmission.observe(f.root).registeredHandles!=0 || ExitHostedProcessRegistry.observe().retainedHandles!=retainedBefore)kotlinx.coroutines.delay(5)}
        }
    }}
    @Test fun writerAbaDuringFinalOwnerValidationIsRejected()=runBlocking {fixture {f->
        f.publish(1);val selected=f.update.collectReleaseReview();var checks=0
        val editor=WorkspaceEditorObservation(emptyList(),emptySet(),emptySet(),emptySet()){}
        val processes=CatalogProcessObservation.capture(f.root)
        val review=CatalogEditorReview(selected.scope.root,editor,false,{},processes){
            if(++checks==4)ExportWriterAdmission.beginWriter(f.root,"late writer").close()
        }
        refused("writer registrations changed"){review.review(f.update,selected,selected.records.map{it.id})}
        assertEquals(4,checks)
    }}
    @Test fun editorChangeDuringFinalOwnerValidationIsRejected()=runBlocking {fixture {f->
        f.publish(1);val selected=f.update.collectReleaseReview();var checks=0
        val state=com.baystudio.droide.ui.EditorWorkspaceState().apply{completeRecovery()}
        val editor=com.baystudio.droide.ui.EditorWorkspaceDocumentAuthority(state,kotlinx.coroutines.Dispatchers.Unconfined).observeAllDocuments()
        val processes=CatalogProcessObservation.capture(f.root)
        val review=CatalogEditorReview(selected.scope.root,editor,false,{},processes){if(++checks==4)state.document("late editor")}
        refused("Editor version or pending work changed"){review.review(f.update,selected,selected.records.map{it.id})}
        assertEquals(4,checks)
    }}
    @Test fun ownerChangeInsideFinalEditorValidationIsRejected()=runBlocking {fixture {f->
        f.publish(1);val selected=f.update.collectReleaseReview();var owner=true;var editorChecks=0
        val editor=WorkspaceEditorObservation(emptyList(),emptySet(),emptySet(),emptySet()){
            if(++editorChecks==4)owner=false
        }
        val processes=CatalogProcessObservation.capture(f.root)
        val review=CatalogEditorReview(selected.scope.root,editor,false,{check(owner){"Final owner changed"}},processes){check(owner)}
        refused("Final owner changed"){review.review(f.update,selected,selected.records.map{it.id})}
        assertEquals(4,editorChecks)
    }}
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
