package com.baystudio.droide.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ExitUiWorkOwnerR64Test {
    @Test fun waitsForNoncancellableUiPublicationCleanup() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val released = CompletableDeferred<Unit>(); val cleanup = CompletableDeferred<Unit>()
        val action = scope.launch { try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.complete(Unit); released.await() } } }
        val owner = ExitUiWorkOwner(); owner.register(checkNotNull(scope.coroutineContext[Job]))
        val stop = async { owner.stopAndAwait() }; cleanup.await(); assertFalse(stop.isCompleted)
        released.complete(Unit); stop.await(); assertTrue(action.isCompleted)
    }
    @Test fun lateUiJobIsCancelledAndRetainedUntilItFinishes() = runBlocking {
        val owner = ExitUiWorkOwner(); owner.stopAndAwait()
        val job = SupervisorJob(); owner.register(job); owner.stopAndAwait(); assertTrue(job.isCancelled); assertTrue(job.isCompleted)
    }
    @Test fun oldOwnerCannotCancelNewSessionScope() = runBlocking {
        val old = ExitUiWorkOwner(); old.stopAndAwait()
        val current = ExitUiWorkOwner(); val job = SupervisorJob(); current.register(job)
        old.stopAndAwait(); assertTrue(job.isActive); current.stopAndAwait(); assertTrue(job.isCompleted)
    }
    @Test fun repeatedRegistrationAndStopDoNotDuplicateCleanup() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); var cleanup = 0
        scope.launch { try { awaitCancellation() } finally { cleanup++ } }
        val owner = ExitUiWorkOwner(); val job = checkNotNull(scope.coroutineContext[Job]); owner.register(job); owner.register(job)
        owner.stopAndAwait(); owner.stopAndAwait(); assertEquals(1,cleanup)
    }
}
