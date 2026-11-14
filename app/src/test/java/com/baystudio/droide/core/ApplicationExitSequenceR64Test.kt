package com.baystudio.droide.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class ApplicationExitSequenceR64Test {
    @Test fun duplicateExitJoinsExactOperation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>(); var calls = 0
        val sequence = ApplicationExitSequence(scope, listOf(ApplicationExitSequence.Step("Save") { calls++; release.await() }))
        val first = sequence.request(); assertSame(first, sequence.request()); assertEquals(1, calls)
        release.complete(Unit); first.join(); assertTrue(sequence.state.value.completed); assertSame(first, sequence.request()); scope.cancel()
    }
    @Test fun failedSaveNeverStartsStopAndRetryDoesNotReplayAdmission() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var admitted = 0; var saves = 0; var stopped = 0
        val sequence = ApplicationExitSequence(scope, listOf(
            ApplicationExitSequence.Step("Fence") { admitted++ },
            ApplicationExitSequence.Step("Save") { if (++saves == 1) error("disk unavailable") },
            ApplicationExitSequence.Step("Stop") { stopped++ }))
        sequence.request().join(); assertFalse(sequence.state.value.completed); assertEquals(0, stopped)
        assertTrue(sequence.state.value.failure!!.contains("disk unavailable"))
        sequence.request().join(); assertTrue(sequence.state.value.completed)
        assertEquals(1, admitted); assertEquals(2, saves); assertEquals(1, stopped); scope.cancel()
    }
    @Test fun observationTimeoutDoesNotCancelSaveOrCreateSecondWriter() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>(); var writes = 0; var cancellations = 0; var stops = 0
        val sequence = ApplicationExitSequence(scope, listOf(
            ApplicationExitSequence.Step("Save") { writes++; try { release.await() } catch (e: CancellationException) { cancellations++; throw e } },
            ApplicationExitSequence.Step("Stop") { stops++ }), observationMs = 40)
        val exiting = sequence.request()
        withTimeout(1_000) { sequence.state.first { it.stage.contains("still finishing") } }
        assertFalse(sequence.state.value.completed); assertEquals(0, cancellations); assertEquals(0, stops)
        assertFalse(sequence.state.value.running); assertNotNull(sequence.state.value.failure)
        sequence.request(); assertEquals(1, writes)
        release.complete(Unit); withTimeout(1_000) { sequence.state.first { it.completed } }; assertTrue(sequence.state.value.completed)
        assertEquals(1, writes); assertEquals(1, stops); scope.cancel()
    }
    @Test fun failedLaterOwnerDoesNotRepeatSuccessfulEarlierOwner() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var a = 0; var b = 0
        val barrier = ExitOwnerBarrier(scope, listOf(ApplicationExitSequence.Step("A") { a++ }, ApplicationExitSequence.Step("B") { if (++b == 1) error("pending") }))
        assertTrue(runCatching { barrier.stopAndAwait() }.isFailure)
        barrier.stopAndAwait(); barrier.stopAndAwait(); assertEquals(1,a); assertEquals(2,b); scope.cancel()
    }
    @Test fun allStopRequestsStartBeforeFirstJoin() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val first = CompletableDeferred<Unit>(); val secondStarted = CompletableDeferred<Unit>()
        val barrier = ExitOwnerBarrier(scope, listOf(ApplicationExitSequence.Step("first") { first.await() }, ApplicationExitSequence.Step("second") { secondStarted.complete(Unit) }))
        val stop = async { barrier.stopAndAwait() }; withTimeout(1000) { secondStarted.await() }; assertFalse(stop.isCompleted)
        first.complete(Unit); stop.await(); scope.cancel()
    }
    @Test fun stopRequestsCanStartBeforeTheLaterJoinWithoutReplayingOwners() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val finish = CompletableDeferred<Unit>(); var firstCalls = 0; var secondCalls = 0
        val barrier = ExitOwnerBarrier(scope, listOf(
            ApplicationExitSequence.Step("Slow owner") { firstCalls++; finish.await() },
            ApplicationExitSequence.Step("Independent owner") { secondCalls++ },
        ))
        barrier.requestStop(); barrier.requestStop()
        assertEquals(1, firstCalls); assertEquals(1, secondCalls)
        val joined = async { barrier.stopAndAwait() }
        yield(); assertFalse(joined.isCompleted)
        finish.complete(Unit); joined.await(); barrier.stopAndAwait()
        assertEquals(1, firstCalls); assertEquals(1, secondCalls); scope.cancel()
    }
    @Test fun timeoutRetainsParallelOwnerReceiptsAndContinuesAutomatically() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val finish = CompletableDeferred<Unit>(); var calls = 0
        val barrier = ExitOwnerBarrier(scope, listOf(ApplicationExitSequence.Step("Native") { calls++; finish.await() }))
        val sequence = ApplicationExitSequence(scope, listOf(ApplicationExitSequence.Step("Stop") { barrier.stopAndAwait() }), 40)
        val exiting = sequence.request()
        withTimeout(1_000) { sequence.state.first { it.stage.contains("still finishing") } }
        sequence.request(); assertEquals(1,calls); finish.complete(Unit)
        withTimeout(1_000) { sequence.state.first { it.completed } }; assertEquals(1,calls); assertTrue(sequence.state.value.completed); scope.cancel()
    }
    @Test fun failureAfterObservationStillBlocksLaterStagesAndAllowsExplicitRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val finish = CompletableDeferred<Unit>(); var attempts = 0; var stops = 0
        try {
            val sequence = ApplicationExitSequence(scope, listOf(
                ApplicationExitSequence.Step("Save") { if (++attempts == 1) { finish.await(); error("disk unavailable") } },
                ApplicationExitSequence.Step("Stop") { stops++ },
            ), 40)
            val exiting = sequence.request()
            withTimeout(1_000) { sequence.state.first { it.stage.contains("still finishing") } }
            finish.complete(Unit); withTimeout(1_000) { sequence.state.first { it.failure == "disk unavailable" } }
            assertEquals("disk unavailable", sequence.state.value.failure)
            assertEquals(0, stops); assertFalse(sequence.state.value.running)
            sequence.request().join()
            assertTrue(sequence.state.value.completed); assertEquals(2, attempts); assertEquals(1, stops)
        } finally { finish.complete(Unit); scope.cancel() }
    }
    @Test fun cancelledStageIsNotSuccessOrFinish() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); var finish = 0
        val sequence = ApplicationExitSequence(scope,listOf(ApplicationExitSequence.Step("Save") { throw CancellationException("interrupted") },ApplicationExitSequence.Step("Finish") { finish++ }))
        sequence.request().join(); assertEquals(0,finish); assertFalse(sequence.state.value.completed); assertNotNull(sequence.state.value.failure); scope.cancel()
    }
    @Test fun oneWaitBudgetCoversAllOwnersAndRetryReusesTheSameStops() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val finish = CompletableDeferred<Unit>(); var calls = 0
        val barrier = ExitOwnerBarrier(scope, List(4) { index -> ApplicationExitSequence.Step("Owner $index") { calls++; finish.await() } }, waitMs = 30)
        try {
            val start = System.nanoTime()
            assertTrue(runCatching { barrier.stopAndAwait() }.isFailure)
            assertTrue((System.nanoTime() - start) / 1_000_000 < 200)
            assertEquals(4, calls)
            finish.complete(Unit); barrier.stopAndAwait(); assertEquals(4, calls)
        } finally { scope.cancel() }
    }
    @Test fun zeroStageSequenceCompletesOnlyOnce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sequence = ApplicationExitSequence(scope,emptyList()); val first = sequence.request(); first.join(); assertTrue(sequence.state.value.completed); assertSame(first,sequence.request()); scope.cancel()
    }
    @Test fun failureMessageBoundedAndStageRetained() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sequence = ApplicationExitSequence(scope,listOf(ApplicationExitSequence.Step("Checkpoint") { error("x".repeat(1000)) }))
        sequence.request().join(); assertEquals(500,sequence.state.value.failure!!.length); assertEquals("Checkpoint",sequence.state.value.stage); scope.cancel()
    }
}
