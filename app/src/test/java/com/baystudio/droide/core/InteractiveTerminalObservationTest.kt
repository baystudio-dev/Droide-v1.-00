package com.baystudio.droide.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class InteractiveTerminalObservationTest {
    @Test fun emptyObservationDoesNotLaunchOrClean() = runBlocking {
        val scope=CoroutineScope(SupervisorJob());var calls=0
        val owner=InteractiveTerminalProcess(scope,{calls++;error("not requested")},{calls++},{calls++})
        try{val observed=owner.observeOwnership();observed.validate();assertTrue(observed.retainedOwners.isEmpty());assertEquals(0,calls)}finally{scope.cancel()}
    }
    @Test fun queuedInputMutationInvalidates() = runBlocking {
        val scope=CoroutineScope(SupervisorJob());val owner=InteractiveTerminalProcess(scope,{error("not requested")},{},{})
        try{owner.send("a");val observed=owner.observeOwnership();owner.send("b");assertTrue(runCatching{observed.validateMetadata()}.isFailure)}finally{owner.destroy();scope.cancel()}
    }
    @Test fun startupWorkerBeforeProcessPublicationIsRetained()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);var launches=0
        val owner=InteractiveTerminalProcess(scope,{launches++;error("not requested")},{},{},{},{},64000,256000,null,{
            InteractiveTerminalProcess.LaunchAdmission{entered.complete(Unit);release.await();it()}
        })
        try{owner.start();withTimeout(3000){entered.await()};val observed=owner.observeOwnership();observed.validateMetadata()
            assertTrue(observed.retainedOwners.any{it.contains("worker registration")});assertFalse(observed.retainedOwners.any{it.contains("process registration")});assertEquals(0,launches)
        }finally{owner.destroy();scope.coroutineContext.job.cancelAndJoin()}
    }
    @Test fun detachedRetiringWorkerRemainsWithoutCurrentProcess()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val owner=InteractiveTerminalProcess(scope,{error("not requested")},{},{},{},{},64000,256000,null,{
            InteractiveTerminalProcess.LaunchAdmission{entered.complete(Unit);try{awaitCancellation()}finally{withContext(NonCancellable){release.await()}}}
        })
        try{owner.start();withTimeout(3000){entered.await()};val before=owner.observeOwnership();owner.destroy()
            assertTrue(runCatching{before.validateMetadata()}.isFailure)
            val after=owner.observeOwnership();assertTrue(after.disposed);assertTrue(after.retainedOwners.any{it.contains("retained workers")});assertFalse(after.retainedOwners.any{it.contains("process registration")})
        }finally{release.complete(Unit);scope.coroutineContext.job.cancelAndJoin()}
    }
    @Test fun failedCleanupRemainsAfterWorkerCompletion()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val owner=InteractiveTerminalProcess(scope,{ProcessBuilder("/bin/sh","-c","exit 0").start()},{error("fixture lease failure")},{})
        try{owner.start();withTimeout(5000){while(owner.observeOwnership().retainedOwners.any{it.contains("retained workers")})delay(5)}
            val observed=owner.observeOwnership();observed.validateMetadata();assertTrue(observed.retainedOwners.any{it.contains("retained cleanup owners")});assertFalse(observed.retainedOwners.any{it.contains("worker registration")})
        }finally{owner.destroy();scope.coroutineContext.job.cancelAndJoin()}
    }
    @Test fun cancellationOfObservationDoesNotDestroyOwner()=runBlocking {
        val scope=CoroutineScope(SupervisorJob());val owner=InteractiveTerminalProcess(scope,{error("not requested")},{},{})
        try{val observed=owner.observeOwnership();val cancelled=Job().apply{cancel()};assertTrue(runCatching{withContext(cancelled){observed.validate()}}.exceptionOrNull() is CancellationException);observed.validateMetadata();assertFalse(owner.observeOwnership().disposed)}finally{scope.cancel()}
    }
    @Test fun diagnosticsAreOwnedImmutableCopies()=runBlocking {
        val scope=CoroutineScope(SupervisorJob());val owner=InteractiveTerminalProcess(scope,{error("not requested")},{},{})
        try{owner.send("a");val observed=owner.observeOwnership();owner.send("b");assertEquals(1,observed.retainedOwners.size);assertTrue(runCatching{(observed.retainedOwners as MutableList<String>).clear()}.isFailure)}finally{owner.destroy();scope.cancel()}
    }
    @Test fun startupFailureReturnsToEmptyButInvalidatesPreviousToken()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val entered=CompletableDeferred<Unit>()
        val owner=InteractiveTerminalProcess(scope,{entered.complete(Unit);error("fixture startup failure")},{},{})
        try{val before=owner.observeOwnership();owner.start();withTimeout(3000){entered.await();while(owner.observeOwnership().retainedOwners.isNotEmpty())delay(5)}
            assertTrue(owner.observeOwnership().retainedOwners.isEmpty());assertTrue(runCatching{before.validateMetadata()}.isFailure)
        }finally{owner.destroy();scope.coroutineContext.job.cancelAndJoin()}
    }

}
