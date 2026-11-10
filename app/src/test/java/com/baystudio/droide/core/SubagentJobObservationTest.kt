package com.baystudio.droide.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SubagentJobObservationTest {
    @Test fun exactAttemptSubstitutionInvalidatesWithoutJobStatusProbe()=runBlocking {
        val owners=SubagentJobOwners();val first=SubagentOwnedJob(1,Job());owners["same"]=first
        val observed=owners.observe();owners["same"]=SubagentOwnedJob(2,Job())
        assertTrue(runCatching{observed.validateMetadata()}.isFailure);assertFalse(owners.remove("same",first));assertEquals(2,owners["same"]?.attempt)
        owners.values.forEach{it.job.cancel()};first.job.cancel();owners.clear()
    }
    @Test fun oldResourceDetachCannotClearReplacementAttempt()=runBlocking {
        val owners=SubagentJobOwners();val first=SubagentOwnedJob(1,Job());owners["same"]=first
        val old=SubagentOwnedResources{_,_->};val binding=owners.attachResources("same",1,first.job,"/old",old)
        val second=SubagentOwnedJob(2,Job());owners["same"]=second;val current=SubagentOwnedResources{_,_->};val next=owners.attachResources("same",2,second.job,"/new",current)
        val observation=owners.observe();binding.close();observation.validateMetadata();assertSame(current,owners["same"]?.resource)
        old.use{};current.use{};next.close();first.job.cancel();second.job.cancel();owners.clear()
    }
    @Test fun mismatchedAttemptCannotAttachResources()=runBlocking {
        val owners=SubagentJobOwners();val job=Job();owners["same"]=SubagentOwnedJob(2,job)
        assertTrue(runCatching{owners.attachResources("same",1,job,"/foreign",SubagentOwnedResources{_,_->})}.isFailure)
        job.cancel();owners.clear()
    }
    @Test fun clearDuringDisposalInvalidatesButDoesNotInvokeOrCancelIt()=runBlocking {
        val owners=SubagentJobOwners();val job=Job();owners["task"]=SubagentOwnedJob(1,job)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var completions=0
        val resources=SubagentOwnedResources{_,_->};resources.register("pending"){entered.complete(Unit);release.await();completions++}
        val binding=owners.attachResources("task",1,job,"/child",resources);val finishing=async{resources.use{}}
        try{entered.await();val observed=owners.observe();owners.clear();assertTrue(runCatching{observed.validateMetadata()}.isFailure);assertEquals(0,completions)
            val empty=owners.observe();assertTrue(empty.evidence().unknownCoverage.isNotEmpty());release.complete(Unit);finishing.await();assertEquals(1,completions)
        }finally{release.complete(Unit);finishing.join();binding.close();job.cancel()}
    }
    @Test fun resourceUseEndIsUnavailableNotCleanEvenBeforeJobRemoval()=runBlocking {
        val owners=SubagentJobOwners();val job=Job();owners["task"]=SubagentOwnedJob(1,job);val resources=SubagentOwnedResources{_,_->}
        val binding=owners.attachResources("task",1,job,"/child",resources);val observed=owners.observe();resources.use{}
        assertTrue(runCatching{observed.validateMetadata()}.isFailure);assertTrue(runCatching{owners.observe()}.isFailure)
        binding.close();assertTrue(owners.observe().evidence().unknownCoverage.isNotEmpty());job.cancel();owners.clear()
    }
    @Test fun finalIdentityMutationIsRejectedByActualReviewBoundary()=runBlocking {
        val owners=SubagentJobOwners();val observed=owners.observe()
        val editor=WorkspaceEditorObservation(emptyList(),emptySet(),emptySet(),emptySet()){}
        val review=CatalogEditorReview("/catalog",editor,false,{owners.clear()},children=observed){}
        assertTrue(runCatching{review.validate()}.isFailure)
    }
    @Test fun cancellationOfCollectionDoesNotRemoveOrCancelJob()=runBlocking {
        val owners=SubagentJobOwners();val job=Job();val record=SubagentOwnedJob(1,job);owners["task"]=record
        val cancel=Job().apply{cancel()};assertTrue(runCatching{withContext(cancel){owners.observe()}}.exceptionOrNull() is CancellationException)
        assertSame(record,owners["task"]);assertFalse(job.isCancelled);job.cancel();owners.clear()
    }
    @Test fun sameAttemptReplacementRejectsOldExecutingJobBinding()=runBlocking {
        val owners=SubagentJobOwners();val oldJob=Job();val newJob=Job()
        owners["same"]=SubagentOwnedJob(1,oldJob)
        owners["same"]=SubagentOwnedJob(1,newJob)
        assertTrue(runCatching{owners.attachResources("same",1,oldJob,"/old",SubagentOwnedResources{_,_->})}.isFailure)
        assertNull(owners["same"]?.resource)
        oldJob.cancel();newJob.cancel();owners.clear()
    }

}
