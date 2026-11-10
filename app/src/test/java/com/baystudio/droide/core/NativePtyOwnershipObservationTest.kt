package com.baystudio.droide.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Executes the production receipt observer, not Android native child creation. */
class NativePtyOwnershipObservationTest {
    @Test fun humanPendingAndFrozenExitUnionPreservesEveryReceipt()=runBlocking {
        val human=CompletableDeferred<NativePtyCleanupReceipt>();val agent=CompletableDeferred<NativePtyCleanupReceipt>();val retired=CompletableDeferred<NativePtyCleanupReceipt>()
        human.complete(NativePtyCleanupReceipt(0,0,0));retired.complete(NativePtyCleanupReceipt(-1,4,2))
        val observed=NativePtyOwnershipObservation.capture(human,listOf(agent),listOf(agent,retired),false){}
        assertEquals(3,observed.receipts.size)
        assertEquals(1,observed.receipts.count{it.status==TerminalReceiptStatus.PENDING})
        assertTrue(observed.receipts.any{it.role=="retained-agent+exit-agent"})
        assertTrue(observed.receipts.any{it.processFailure==4 && it.inputFailure==2});observed.validate()
    }
    @Test fun allRecordedFailuresRemainDistinct()=runBlocking {
        for(reason in 0..4)for(input in 0..4){
            val done=CompletableDeferred<NativePtyCleanupReceipt>();done.complete(NativePtyCleanupReceipt(17,reason,input))
            val observed=NativePtyOwnershipObservation.capture(done,emptyList(),emptyList(),false){}
            val receipt=observed.receipts.single();assertEquals(reason,receipt.processFailure);assertEquals(input,receipt.inputFailure);assertEquals(17,receipt.exitStatus)
            observed.validate()
        }
    }
    @Test fun pendingCompletionInvalidatesPriorReceiptObservation()=runBlocking {
        val done=CompletableDeferred<NativePtyCleanupReceipt>();val observed=NativePtyOwnershipObservation.capture(done,emptyList(),emptyList(),false){}
        done.complete(NativePtyCleanupReceipt(0,0,0));assertTrue(runCatching{observed.validate()}.isFailure)
    }
    @Test fun duplicateCompletionCannotRewriteFirstAcceptedReceipt()=runBlocking {
        val done=CompletableDeferred<NativePtyCleanupReceipt>();assertTrue(done.complete(NativePtyCleanupReceipt(-1,3,4)))
        val observed=NativePtyOwnershipObservation.capture(done,emptyList(),emptyList(),false){}
        assertFalse(done.complete(NativePtyCleanupReceipt(0,0,0)));observed.validate()
        assertEquals(3,observed.receipts.single().processFailure);assertEquals(4,observed.receipts.single().inputFailure)
    }
    @Test fun unavailableReceiptIsNotInventedAsNoChild()=runBlocking {
        val done=CompletableDeferred<NativePtyCleanupReceipt>();done.cancel()
        val observed=NativePtyOwnershipObservation.capture(done,emptyList(),emptyList(),false){}
        assertEquals(TerminalReceiptStatus.UNAVAILABLE,observed.receipts.single().status)
        assertNull(observed.receipts.single().processFailure);observed.validate()
    }
    @Test fun ownerAbaAndDestroyAreRejectedBySharedValidator()=runBlocking {
        var token=Any();val captured=token;var destroyed=false
        val observed=NativePtyOwnershipObservation.capture(CompletableDeferred(),emptyList(),emptyList(),false){check(token===captured && !destroyed)}
        observed.validate();token=Any();assertTrue(runCatching{observed.validate()}.isFailure)
        token=captured;destroyed=true;assertTrue(runCatching{observed.validate()}.isFailure)
    }
    @Test fun cancellationInvokesNoOwnerCallbackOrCompletion()=runBlocking {
        var calls=0;val done=CompletableDeferred<NativePtyCleanupReceipt>()
        val observed=NativePtyOwnershipObservation.capture(done,emptyList(),emptyList(),false){calls++}
        val job=Job().apply{cancel()}
        assertTrue(runCatching{withContext(job){observed.validate()}}.exceptionOrNull() is CancellationException)
        assertEquals(0,calls);assertFalse(done.isCompleted)
    }
    @Test fun receiptListIsDetachedAndUnmodifiable()=runBlocking {
        val pending=mutableListOf<Deferred<NativePtyCleanupReceipt>>(CompletableDeferred())
        val observed=NativePtyOwnershipObservation.capture(CompletableDeferred(),pending,emptyList(),false){}
        pending.clear();assertEquals(2,observed.receipts.size)
        assertTrue(runCatching{(observed.receipts as MutableList).clear()}.exceptionOrNull() is UnsupportedOperationException)
    }
}
