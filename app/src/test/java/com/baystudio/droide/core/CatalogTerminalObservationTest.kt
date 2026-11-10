package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class CatalogTerminalObservationTest {
    @get:org.junit.Rule val admission:org.junit.rules.TestRule=ApplicationWorkAdmissionFixture()
    private open class Session:ITerminalSession,TerminalOwnershipObservationSource {
        override val output=MutableStateFlow("")
        val native=CompletableDeferred<NativePtyCleanupReceipt>();val stop=CompletableDeferred<Unit>()
        var destroyed=0;var stops=0;var observations=0;var failStop=false;var callback:()->Unit={}
        override fun start()=Unit
        override fun destroy(){destroyed++}
        override fun send(cmd:String)=Unit
        override fun interrupt()=Unit
        override fun clear()=Unit
        override suspend fun execOnce(cmd:String,timeoutMs:Long)=ExecResult(0,"",false)
        override suspend fun execArgv(argv:List<String>,timeoutMs:Long)=ExecResult(0,"",false)
        override suspend fun stopAndAwaitForExit(){stops++;stop.await();check(!failStop){"fixture retained failure"}}
        override suspend fun observeTerminalOwnership():TerminalOwnershipObservation{
            observations++;callback();return NativePtyOwnershipObservation.capture(native,emptyList(),emptyList(),false){}
        }
    }
    private fun fixture(action:suspend CoroutineScope.(TerminalManager,Session,File)->Unit)=runBlocking {
        val root=Files.createTempDirectory("terminal-observer").toFile();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val session=Session()
        val manager=TerminalManager(scope,root){_,_,_->session}
        try{withTimeout(10000){action(manager,session,root)}}finally{session.stop.complete(Unit);manager.dispose(false);scope.cancel();root.deleteRecursively()}
    }
    @Test fun currentSessionObservationNeverCallsCleanupAndRetainsUnknowns()=fixture{manager,session,_->
        manager.createLocal();session.callback={assertFalse(Thread.holdsLock(manager))}
        val observation=CatalogTerminalObservation.capture(manager,null);observation.validate();val evidence=observation.evidence()
        assertTrue(evidence.retainedOwners.isNotEmpty());assertTrue(evidence.unknownCoverage.isNotEmpty())
        assertTrue(evidence.unknownDescendants.isNotEmpty());assertTrue(evidence.unknownDescriptors.isNotEmpty());assertTrue(evidence.liveOwners.isEmpty())
        assertEquals(0,session.destroyed);assertEquals(0,session.stops)
    }
    @Test fun successfulAndStartupFailedReceiptsNeverCertifyGlobalClosure()=fixture{manager,session,_->
        manager.createLocal();session.native.complete(NativePtyCleanupReceipt(0,1,0))
        val observed=CatalogTerminalObservation.capture(manager,null);observed.validate()
        assertTrue(observed.evidence().unknownDescendants.isNotEmpty());assertTrue(observed.evidence().unknownDescriptors.isNotEmpty())
        assertTrue(observed.evidence().liveOwners.isEmpty())
    }
    @Test fun receiptAndInputFailureDiagnosticsStaySeparate()=fixture{manager,session,_->
        manager.createLocal();session.native.complete(NativePtyCleanupReceipt(-1,4,2))
        val evidence=CatalogTerminalObservation.capture(manager,null).evidence()
        assertTrue(evidence.retainedOwners.any{it.contains("custody failure: 4")})
        assertTrue(evidence.retainedOwners.any{it.contains("input delivery failure: 2")})
    }
    @Test fun hiddenRetiredSessionAndPendingAttemptRemainObserved()=fixture{manager,session,_->
        val tab=manager.createLocal();manager.close(tab.id)
        withTimeout(5000){while(session.stops==0)yield()}
        val registry=manager.observeOwnership();assertTrue(manager.tabs.value.isEmpty());assertTrue(registry.sessions.any{it===session})
        assertEquals(1,registry.pendingCleanup)
        val evidence=CatalogTerminalObservation.capture(manager,null).evidence()
        assertTrue(evidence.retainedOwners.any{it.contains("Pending terminal cleanup")})
        assertEquals(1,session.stops)
    }
    @Test fun failedStopReceiptRemainsOwnedAndSnapshotDoesNotRetry()=fixture{manager,session,_->
        val tab=manager.createLocal();session.failStop=true;session.stop.complete(Unit);manager.close(tab.id)
        withTimeout(5000){while(manager.observeOwnership().failedCleanup==0)delay(5)}
        val before=session.stops;val observed=CatalogTerminalObservation.capture(manager,null);observed.validate()
        assertEquals(before,session.stops);assertTrue(observed.evidence().retainedOwners.any{it.contains("Failed terminal cleanup")})
    }
    @Test fun managerPublicationDuringSessionCaptureIsRejected()=fixture{manager,session,_->
        val tab=manager.createLocal();session.callback={manager.switch(tab.id)}
        assertTrue(runCatching{CatalogTerminalObservation.capture(manager,null)}.isFailure)
    }
    @Test fun managerRegistryAbaInvalidatesPriorObservation()=fixture{manager,_,_->
        val empty=manager.observeOwnership();val tab=manager.createLocal();manager.close(tab.id)
        assertTrue(runCatching{empty.validate()}.isFailure)
    }
    @Test fun disposedManagerCannotValidateOldObservation()=fixture{manager,session,_->
        manager.createLocal();val observed=CatalogTerminalObservation.capture(manager,null)
        manager.dispose(false);assertTrue(runCatching{observed.validate()}.isFailure)
    }
    @Test fun emptyManagerAndMissingHostStillHaveUnknownCoverage()=fixture{manager,_,_->
        val evidence=CatalogTerminalObservation.capture(manager,null).evidence()
        assertTrue(evidence.unknownCoverage.any{it.contains("host")});assertTrue(evidence.unknownDescendants.isNotEmpty())
    }
    @Test fun cancellationStartsNoCleanup()=fixture{manager,session,_->
        manager.createLocal();val observed=CatalogTerminalObservation.capture(manager,null);val job=Job().apply{cancel()}
        assertTrue(runCatching{withContext(job){observed.validate()}}.exceptionOrNull() is CancellationException)
        assertEquals(0,session.stops);assertEquals(0,session.destroyed)
    }
    @Test fun receiptCompletionDuringFinalOwnerCheckIsRejected()=fixture{manager,session,root->
        manager.createLocal();val observed=CatalogTerminalObservation.capture(manager,null)
        val editor=WorkspaceEditorObservation(emptyList(),emptySet(),emptySet(),emptySet()){}
        var checks=0
        val review=CatalogEditorReview(root.path,editor,false,{},terminals=observed){
            if(++checks==2)session.native.complete(NativePtyCleanupReceipt(0,0,0))
        }
        assertTrue(runCatching{review.validate()}.exceptionOrNull()?.message.orEmpty().contains("receipts changed"))
        assertEquals(0,session.stops);assertEquals(0,session.destroyed)
    }
    @Test fun registryChangeDuringFinalEditorCheckIsRejected()=fixture{manager,session,root->
        val tab=manager.createLocal();val observed=CatalogTerminalObservation.capture(manager,null)
        var checks=0
        val editor=WorkspaceEditorObservation(emptyList(),emptySet(),emptySet(),emptySet()){
            if(++checks==2)manager.switch(tab.id)
        }
        val review=CatalogEditorReview(root.path,editor,false,{},terminals=observed){}
        assertTrue(runCatching{review.validate()}.isFailure)
        assertEquals(0,session.stops);assertEquals(0,session.destroyed)
    }
    @Test fun registryChangeDuringFinalIdentityCheckIsRejected()=fixture{manager,session,root->
        val tab=manager.createLocal();val observed=CatalogTerminalObservation.capture(manager,null)
        val editor=WorkspaceEditorObservation(emptyList(),emptySet(),emptySet(),emptySet()){}
        val review=CatalogEditorReview(root.path,editor,false,{manager.switch(tab.id)},terminals=observed){}
        assertTrue(runCatching{review.validate()}.isFailure)
        assertEquals(0,session.stops);assertEquals(0,session.destroyed)
    }

}
