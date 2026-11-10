package com.baystudio.droide.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ChildResourceObservationTest {
    private class Terminal : TerminalOwnershipObservationSource {
        var captures=0;var validations=0;var onCapture:()->Unit={};var onValidate:()->Unit={};var token:Any=Any()
        override suspend fun observeTerminalOwnership():TerminalOwnershipObservation {
            captures++;onCapture();val before=token
            return TerminalOwnershipObservation(emptyList(),false,listOf("fixture retained terminal")){
                validations++;onValidate();check(token===before){"fixture terminal changed"}
            }
        }
    }
    private fun lock(owner:SubagentOwnedResources):Any=SubagentOwnedResources::class.java.getDeclaredField("ownerLock").apply{isAccessible=true}.get(owner)
    @Test fun typedOwnerCaptureInvokesNoDisposerAndRunsOutsideLock()=runBlocking {
        var disposal=0;val resources=SubagentOwnedResources{_,_->};val terminal=Terminal()
        resources.registerTerminal("terminal",terminal,"/child/exact"){disposal++}
        terminal.onCapture={assertFalse(Thread.holdsLock(lock(resources)))}
        val observed=resources.observeTerminals();observed.validateMetadata()
        assertEquals(0,disposal);assertTrue(observed.retainedOwners.any{it.contains("/child/exact")})
        resources.use{}
        val before=terminal.validations;assertTrue(runCatching{observed.validateMetadata()}.isFailure);assertEquals(before,terminal.validations)
    }
    @Test fun registrationInvalidatesEarlierCapture()=runBlocking {
        val resources=SubagentOwnedResources{_,_->};val observed=resources.observeTerminals()
        resources.register("new"){};assertTrue(runCatching{observed.validateMetadata()}.isFailure);resources.use{}
    }
    @Test fun disposalIsVisibleUntilActualCallbackReturns()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val resources=SubagentOwnedResources{_,_->};val terminal=Terminal()
        resources.registerTerminal("terminal",terminal,"/child"){entered.complete(Unit);release.await()}
        val before=resources.observeTerminals();val finish=async{resources.use{}}
        try{withTimeout(3000){entered.await()};assertTrue(runCatching{before.validateMetadata()}.isFailure)
            val during=resources.observeTerminals();during.validateMetadata();assertTrue(during.retainedOwners.any{it.contains("disposal is in progress")})
            release.complete(Unit);finish.await();assertTrue(runCatching{during.validateMetadata()}.isFailure)
        }finally{release.complete(Unit);finish.join()}
    }
    @Test fun cancellationPreservesObservableNonCancellableDisposal()=runBlocking {
        val active=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val resources=SubagentOwnedResources{_,_->};resources.registerTerminal("terminal",Terminal(),"/child"){entered.complete(Unit);release.await()}
        val task=launch{resources.use{active.complete(Unit);awaitCancellation()}}
        try{active.await();task.cancel();withTimeout(3000){entered.await()};val during=resources.observeTerminals();during.validateMetadata()
            release.complete(Unit);task.join();assertTrue(runCatching{during.validateMetadata()}.isFailure)
        }finally{release.complete(Unit);task.cancelAndJoin()}
    }
    @Test fun reportingFailureStillClearsBoundedSlotsAtLifetimeEnd()=runBlocking {
        val resources=SubagentOwnedResources{_,_->error("reporting fixture failure")};val terminal=Terminal()
        resources.registerTerminal("terminal",terminal,"/child"){error("cleanup fixture failure")}
        val observed=resources.observeTerminals();resources.use{}
        for(name in listOf("captured","finishing","disposers")){
            val value=SubagentOwnedResources::class.java.getDeclaredField(name).apply{isAccessible=true}.get(resources)
            assertTrue("$name retained observation resources",value==null || (value is Collection<*> && value.isEmpty()))
        }
        val before=terminal.validations;assertTrue(runCatching{observed.validateMetadata()}.isFailure);assertEquals(before,terminal.validations)
        assertTrue(runCatching{resources.observeTerminals()}.isFailure)
    }
    @Test fun terminalMutationInvalidatesWithoutCleanup()=runBlocking {
        val resources=SubagentOwnedResources{_,_->};val terminal=Terminal();resources.registerTerminal("terminal",terminal,"/child"){}
        val observed=resources.observeTerminals();terminal.token=Any();assertTrue(runCatching{observed.validateMetadata()}.isFailure);resources.use{}
    }
    @Test fun newerCaptureSupersedesSingleBoundedSlot()=runBlocking {
        val resources=SubagentOwnedResources{_,_->};resources.registerTerminal("terminal",Terminal(),"/child"){}
        val first=resources.observeTerminals();val second=resources.observeTerminals()
        assertTrue(runCatching{first.validateMetadata()}.isFailure);second.validateMetadata();resources.use{}
    }
    @Test fun collectionMutationRejectsSnapshotWithoutReattaching()=runBlocking {
        val resources=SubagentOwnedResources{_,_->};val terminal=Terminal();resources.registerTerminal("terminal",terminal,"/child"){}
        terminal.onCapture={resources.register("racing acquisition") {}}
        assertTrue(runCatching{resources.observeTerminals()}.isFailure);resources.use{}
    }
    @Test fun immutableSummariesNeverCertifyClosure()=runBlocking {
        val resources=SubagentOwnedResources{_,_->};val observed=resources.observeTerminals()
        assertTrue(observed.evidence().unknownCoverage.isNotEmpty());assertTrue(observed.evidence().unknownDescendants.isNotEmpty());assertTrue(observed.evidence().unknownDescriptors.isNotEmpty())
        assertTrue(runCatching{(observed.retainedOwners as MutableList<String>).add("forged")}.isFailure);resources.use{}
    }
    @Test fun lifetimeEndingInsideTerminalValidatorCannotReattachCapturedSlot()=runBlocking {
        val resources=SubagentOwnedResources{_,_->};val terminal=Terminal();resources.registerTerminal("terminal",terminal,"/child"){}
        val observed=resources.observeTerminals()
        terminal.onValidate={runBlocking{resources.use{}}}
        assertTrue(runCatching{observed.validateMetadata()}.isFailure)
        assertNull(SubagentOwnedResources::class.java.getDeclaredField("captured").apply{isAccessible=true}.get(resources))
    }
    @Test fun failedDisposerRemainsObservableDuringReportingButNotAfterUse()=runBlocking {
        var during:ChildOwnershipObservation?=null
        lateinit var resources:SubagentOwnedResources
        resources=SubagentOwnedResources{_,_->
            assertFalse(Thread.holdsLock(lock(resources)))
            during=runBlocking{resources.observeTerminals()}
            assertTrue(during!!.retainedOwners.any{it.contains("disposal is in progress")})
        }
        resources.registerTerminal("terminal",Terminal(),"/child"){error("fixture cleanup failure")}
        resources.use{}
        assertNotNull(during);assertTrue(runCatching{during!!.validateMetadata()}.isFailure)
    }

}
