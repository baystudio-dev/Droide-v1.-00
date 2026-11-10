package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WorkspaceIndexInvalidationTest {
    private open class Authority : WorkspaceDocumentAuthority {
        override suspend fun snapshot(path:String):WorkspaceDocumentSnapshot?=null
        override suspend fun snapshots()=emptyList<WorkspaceDocumentSnapshot>()
    }
    @Test fun persistedPublicationReconcilesBeforeInvalidationAndPreservesResult() = runBlocking<Unit> {
        val events=mutableListOf<String>();val expected=WorkspacePersistedReconciliation(refreshed=listOf("a.md"))
        val bridge=WorkspaceDocumentAuthorityBridge {events+="invalidate"}
        bridge.bind(object:Authority() { override suspend fun persistedMutation(mutation:FileRepository.Mutation):WorkspacePersistedReconciliation {events+="reconcile";return expected} })
        assertSame(expected,bridge.persistedMutation(FileRepository.Mutation.Write("a.md")))
        assertEquals(listOf("reconcile","invalidate"),events)
    }
    @Test fun preflightAndObservationNeverInvalidateEvenWhenTheyThrow() = runBlocking<Unit> {
        var invalidations=0;var observed=0;val original=IllegalStateException("preflight")
        val bridge=WorkspaceDocumentAuthorityBridge {invalidations++}
        bridge.bind(object:Authority() {
            override suspend fun validatePersistedMutation(mutation:FileRepository.Mutation) {throw original}
            override suspend fun reconcilePersistedWorkspace():WorkspacePersistedReconciliation {observed++;return WorkspacePersistedReconciliation()}
        })
        assertSame(original,runCatching {bridge.validatePersistedMutation(FileRepository.Mutation.Delete("a.md"))}.exceptionOrNull())
        repeat(10) {bridge.reconcilePersistedWorkspace()}
        assertEquals(10,observed);assertEquals(0,invalidations)
    }
    @Test fun explicitMutationReconciliationDefaultsToExistingDelegateAndNotifiesOnce() = runBlocking<Unit> {
        val events=mutableListOf<String>();val bridge=WorkspaceDocumentAuthorityBridge {events+="invalidate"}
        bridge.bind(object:Authority() {override suspend fun reconcilePersistedWorkspace():WorkspacePersistedReconciliation {events+="reconcile";return WorkspacePersistedReconciliation()}})
        bridge.reconcileAfterPersistedMutation();assertEquals(listOf("reconcile","invalidate"),events)
    }
    @Test fun missingEditorBindingDoesNotHideDiskMutation() = runBlocking<Unit> {
        var calls=0;val bridge=WorkspaceDocumentAuthorityBridge {calls++}
        bridge.persistedMutation(FileRepository.Mutation.Write("a.md"));bridge.reconcileAfterPersistedMutation()
        assertEquals(2,calls)
    }
    @Test fun throwingReconciliationStillInvalidatesAndPreservesOriginalFailure() = runBlocking<Unit> {
        val original=IllegalStateException("reconciliation");val cleanup=IllegalArgumentException("invalidation")
        val bridge=WorkspaceDocumentAuthorityBridge {throw cleanup}
        bridge.bind(object:Authority() {override suspend fun persistedMutation(mutation:FileRepository.Mutation):WorkspacePersistedReconciliation {throw original}})
        val result=runCatching {bridge.persistedMutation(FileRepository.Mutation.Move("old","new"))}
        assertSame(original,result.exceptionOrNull());assertEquals(listOf(cleanup),original.suppressed.toList())
    }
    @Test fun cancellationIdentitySurvivesPostMutationInvalidationFailure() = runBlocking<Unit> {
        val cancel=CancellationException("original");val cleanup=IllegalStateException("invalidation")
        val bridge=WorkspaceDocumentAuthorityBridge {throw cleanup}
        bridge.bind(object:Authority() {override suspend fun reconcilePersistedWorkspace():WorkspacePersistedReconciliation {throw cancel}})
        assertSame(cancel,runCatching {bridge.reconcileAfterPersistedMutation()}.exceptionOrNull())
        assertEquals(listOf(cleanup),cancel.suppressed.toList())
    }
    @Test fun defaultCallbackPreservesPriorBridgeBehaviorAndNewOwnerBinding() = runBlocking<Unit> {
        val bridge=WorkspaceDocumentAuthorityBridge();val first=bridge.bind(Authority());var calls=0
        bridge.bind(object:Authority() {override suspend fun persistedMutation(mutation:FileRepository.Mutation):WorkspacePersistedReconciliation {calls++;return WorkspacePersistedReconciliation()}})
        first.close();bridge.persistedMutation(FileRepository.Mutation.Write("a.md"));assertEquals(1,calls)
    }
}
