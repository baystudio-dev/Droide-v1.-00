package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CatalogEditorObservationTest {
    @Test fun overlappingRealSavesAreBothObservedWithoutDraining() = runBlocking {
        val root=Files.createTempDirectory("editor-overlap").toFile()
        val release=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>()
        try {
            File(root,"a").writeText("a");File(root,"b").writeText("b")
            val files=FileRepository(root,beforeMutation={entered.complete(Unit);release.await()})
            val state=EditorWorkspaceState().apply{completeRecovery()}
            val a=state.document("a");val b=state.document("b");a.ensureLoaded(files);b.ensureLoaded(files)
            a.capture("changed a");b.capture("changed b")
            val owner=coroutineContext[kotlin.coroutines.ContinuationInterceptor] as CoroutineDispatcher
            val authority=EditorWorkspaceDocumentAuthority(state,owner)
            val first=async{a.save(files)};val second=async{b.save(files)}
            withTimeout(5000){entered.await();while(!a.isSaveInProgress || !b.isSaveInProgress)yield()}
            val observed=authority.observeAllDocuments()
            assertEquals(setOf("a","b"),observed.pendingSaves);assertFalse(first.isCompleted);assertFalse(second.isCompleted)
            release.complete(Unit);first.await().getOrThrow();second.await().getOrThrow()
            assertTrue(runCatching{observed.validate()}.isFailure)
        } finally {release.complete(Unit);root.deleteRecursively()}
    }
    @Test fun nativeBufferCapturePendingIsObservedWithoutCapturing() = runBlocking {
        val root=Files.createTempDirectory("editor-buffer-observe").toFile()
        try {
            File(root,"large").writeText("x".repeat(EditorLargeFilePolicy.FULL_INTELLIGENCE_MAX_BYTES.toInt()+1))
            val state=EditorWorkspaceState().apply{completeRecovery()};val doc=state.document("large")
            doc.ensureLoaded(FileRepository(root));assertTrue(doc.captureInsert(0,"y"));assertTrue(doc.requiresBufferCapture)
            val observed=EditorWorkspaceDocumentAuthority(state,Dispatchers.Unconfined).observeAllDocuments()
            assertEquals(setOf("large"),observed.pendingSaves);observed.validate();assertTrue(doc.requiresBufferCapture)
        } finally {root.deleteRecursively()}
    }
    @Test fun uninitializedRecoveryIsUnavailable() = runBlocking {
        val state=EditorWorkspaceState()
        assertTrue(runCatching {EditorWorkspaceDocumentAuthority(state,Dispatchers.Unconfined).observeAllDocuments()}.exceptionOrNull() is WorkspaceRecoveryEditorUnavailableException)
    }
    @Test fun unboundAndLegacyAuthorityAreUnavailable() = runBlocking {
        val bridge=WorkspaceDocumentAuthorityBridge()
        assertTrue(runCatching { bridge.observeAllDocuments() }.exceptionOrNull() is WorkspaceRecoveryEditorUnavailableException)
        bridge.bind(object:WorkspaceDocumentAuthority {
            override suspend fun snapshot(path:String):WorkspaceDocumentSnapshot?=null
            override suspend fun snapshots()=emptyList<WorkspaceDocumentSnapshot>()
        })
        assertTrue(runCatching { bridge.observeAllDocuments() }.exceptionOrNull() is WorkspaceRecoveryEditorUnavailableException)
    }
    @Test fun allPathsCapturedWithoutSavingOrReadingDeferredBuffers() = runBlocking {
        val state=EditorWorkspaceState().apply{completeRecovery()}; val first=state.document("src/a.kt"); val second=state.document("outside/b.kt")
        var reads=0
        state.retainRecoveryConflict("hidden/c.kt",null,EditorRecoveryBuffer("hidden/c.kt","owned-blob","ab".repeat(32),Any(),loader={reads++;error("Must not read")}))
        val observed=EditorWorkspaceDocumentAuthority(state,Dispatchers.Unconfined).observeAllDocuments()
        assertEquals(setOf(first.path,second.path),observed.snapshots.map{it.path}.toSet())
        assertEquals(setOf("hidden/c.kt"),observed.retainedPaths); observed.validate();assertEquals(0,reads)
    }
    @Test fun dirtyAndPendingSyncReportedSeparately() = runBlocking {
        val root=Files.createTempDirectory("editor-observation").toFile()
        try {
            File(root,"a").writeText("original");val state=EditorWorkspaceState().apply{completeRecovery()};val doc=state.document("a")
            doc.ensureLoaded(FileRepository(root));doc.capture("unsaved");doc.markSaveNotificationPending()
            val observed=EditorWorkspaceDocumentAuthority(state,Dispatchers.Unconfined).observeAllDocuments()
            assertTrue(observed.snapshots.single().dirty);assertEquals(setOf("a"),observed.pendingSync)
            assertEquals("original",File(root,"a").readText());observed.validate()
        } finally {root.deleteRecursively()}
    }
    @Test fun pendingFlagSubstitutionCannotHideBehindSamePath() = runBlocking {
        val state=EditorWorkspaceState().apply{completeRecovery()};val doc=state.document("a");doc.markSaveNotificationPending()
        val observed=EditorWorkspaceDocumentAuthority(state,Dispatchers.Unconfined).observeAllDocuments()
        // Exact flag transition injection, no alternate observation producer.
        EditorDocument::class.java.getDeclaredField("saveNotificationPending").also {it.isAccessible=true}.setBoolean(doc,false)
        EditorDocument::class.java.getDeclaredField("saveSynchronizationPending").also {it.isAccessible=true}.setBoolean(doc,true)
        assertTrue(runCatching {observed.validate()}.isFailure)
    }
    @Test fun newDocumentAndDetachedSameDelegateInvalidate() = runBlocking {
        val state=EditorWorkspaceState().apply{completeRecovery()};val authority=EditorWorkspaceDocumentAuthority(state,Dispatchers.Unconfined)
        val bridge=WorkspaceDocumentAuthorityBridge();val binding=bridge.bind(authority);val before=bridge.observeAllDocuments()
        state.document("new");assertTrue(runCatching{before.validate()}.isFailure)
        val after=bridge.observeAllDocuments();binding.close();bridge.bind(authority)
        assertTrue(runCatching{after.validate()}.isFailure)
    }
    @Test fun nativeLifecycleAbaDetectedWithoutNativeCapture() = runBlocking {
        val state=EditorWorkspaceState().apply{completeRecovery()};val active=ActiveEditorBridge();var captures=0;active.snapshot={captures++}
        val binding=EditorNativeHandoffBinding(Any(),begin={error("Must not begin")},requireReady={})
        active.nativeHandoff=binding
        state.bindProjectHandoffCapture(active::captureForSave,{active.nativeHandoff},{active.recoveryLifecycleGeneration})
        val observed=EditorWorkspaceDocumentAuthority(state,Dispatchers.Unconfined).observeAllDocuments()
        active.nativeHandoff=null;active.nativeHandoff=binding
        assertTrue(runCatching{observed.validate()}.isFailure);assertEquals(0,captures)
    }
    @Test fun cancellationDoesNotYieldCleanEvidence() = runBlocking {
        val observed=EditorWorkspaceDocumentAuthority(EditorWorkspaceState().apply{completeRecovery()},Dispatchers.Unconfined).observeAllDocuments()
        val job=Job().apply{cancel()}
        assertTrue(runCatching{withContext(job){observed.validate()}}.exceptionOrNull() is CancellationException)
    }
    @Test fun handoffReserveClearAndOwnerPublicationAbaDetected() {
        val gate=WorkspaceHandoffGate();val initial=gate.observe()
        val retention=object:WorkspaceHandoffRetention{override suspend fun retryPreservation(){error("No retry")}}
        gate.reserve(retention);gate.clear(retention)
        assertTrue(runCatching{gate.requireUnchanged(initial)}.isFailure)
        val clear=gate.observe();gate.publishOwner(Any());gate.publishOwner(null)
        assertTrue(runCatching{gate.requireUnchanged(clear)}.isFailure)
    }
    @Test fun observationOwnsUnmodifiableCollections() = runBlocking {
        val paths=mutableSetOf("a");val observed=WorkspaceEditorObservation(emptyList(),paths,emptySet(),emptySet()){}
        paths.clear();assertEquals(setOf("a"),observed.pendingSaves)
        assertTrue(runCatching{(observed.pendingSaves as MutableSet).clear()}.exceptionOrNull() is UnsupportedOperationException)
    }
}
