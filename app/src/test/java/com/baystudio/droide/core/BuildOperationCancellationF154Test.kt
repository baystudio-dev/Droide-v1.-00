package com.baystudio.droide.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Actual gate, deterministic callback/cleanup boundaries; no Android transport claim. */
@OptIn(InternalCoroutinesApi::class)
class BuildOperationCancellationF154Test {
    private fun CountDownLatch.reached() = await(2, TimeUnit.SECONDS)

    @Test(timeout = 10_000) fun exitKeepsCleanupAfterEarlierCancellationHasReturned() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = BuildRunDebugOperationGate<String>()
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val operation = scope.launch {
            gate.run("BUILD") {
                entered.complete(Unit)
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { cleaning.complete(Unit); release.await() } }
            }
        }
        entered.await()
        assertTrue(gate.cancelActive("Explicit cancel"))
        cleaning.await()
        // The cancellation thread can return before the operation's cleanup does.
        delay(50)
        val close = async { gate.closeAndJoin() }
        try {
            delay(30)
            assertFalse("Exit must include previously cancelled owners still cleaning", close.isCompleted)
            assertTrue(runCatching { gate.run("LATE") {} }.exceptionOrNull() is IllegalStateException)
        } finally { release.complete(Unit); withTimeout(2_000) { close.await(); operation.join() }; scope.cancel() }
    }

    @Test(timeout = 10_000) fun cancelledExitObserverRetainsRequestForLaterJoin() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = BuildRunDebugOperationGate<String>()
        val entered = CompletableDeferred<Unit>()
        val cancelEntered = CountDownLatch(1)
        val cancelRelease = CountDownLatch(1)
        val count = AtomicInteger()
        val operation = scope.launch {
            gate.run("DEBUG") {
                suspendCancellableCoroutine<Unit> { continuation ->
                    continuation.invokeOnCancellation {
                        count.incrementAndGet(); cancelEntered.countDown()
                        check(cancelRelease.await(5, TimeUnit.SECONDS))
                    }
                    entered.complete(Unit)
                }
            }
        }
        entered.await()
        val first = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(80) { gate.closeAndJoin(); true } }
        var retry: Deferred<Unit>? = null
        try {
            assertTrue(cancelEntered.reached())
            assertNull("Observer timeout must remain responsive", withTimeout(1_000) { first.await() })
            retry = async { gate.closeAndJoin() }
            delay(30)
            assertFalse(retry.isCompleted)
            assertEquals(1, count.get())
        } finally {
            cancelRelease.countDown()
            withTimeout(2_000) { first.join(); retry?.await(); operation.join() }
            scope.cancel()
        }
        assertEquals(1, count.get())
    }

    @Test(timeout = 10_000) fun exitDispatchesBuildAndStopCancellationIndependently() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = BuildRunDebugOperationGate<String>()
        val buildReady = CompletableDeferred<Unit>()
        val stopReady = CompletableDeferred<Unit>()
        val buildCancel = CountDownLatch(1)
        val stopCancel = CountDownLatch(1)
        val callbacksRelease = CountDownLatch(1)
        val stopRelease = CompletableDeferred<Unit>()
        val build = scope.launch {
            gate.run("BUILD") {
                suspendCancellableCoroutine<Unit> { continuation ->
                    continuation.invokeOnCancellation {
                        buildCancel.countDown(); check(callbacksRelease.await(5, TimeUnit.SECONDS))
                    }
                    buildReady.complete(Unit)
                }
            }
        }
        buildReady.await()
        val stop = scope.launch { gate.stopDebug("DEBUG") { stopReady.complete(Unit); stopRelease.await() } }
        stopReady.await()
        // Stop owns an explicit child scope; accepted cleanup runs NonCancellable below it.
        val stopOwner = stop.children.single()
        stopOwner.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
            stopCancel.countDown(); check(callbacksRelease.await(5, TimeUnit.SECONDS))
        }
        val close = async { gate.closeAndJoin() }
        try {
            // Both handlers must start before either is released, including with IO.parallelism=1.
            withContext(Dispatchers.Default) {
                assertTrue("Build cancellation was not requested", buildCancel.reached())
                assertTrue("Stop cancellation must not wait for Build's callback", stopCancel.reached())
            }
            assertFalse(close.isCompleted)
            callbacksRelease.countDown()
            delay(30)
            assertFalse("Stop's accepted adapter cleanup remains owned", close.isCompleted)
        } finally {
            callbacksRelease.countDown(); stopRelease.complete(Unit)
            withTimeout(2_000) { close.await(); stop.join(); build.join() }
            scope.cancel()
        }
    }

    @Test(timeout = 10_000) fun stateCallbackDoesNotHoldAdmissionAndStopReconcilesItsState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstIdleEntered = CountDownLatch(1)
        val firstIdleRelease = CountDownLatch(1)
        val callbacks = AtomicInteger()
        val state = AtomicReference("building")
        val gate = BuildRunDebugOperationGate<String> { stopping ->
            if (callbacks.incrementAndGet() == 1) {
                firstIdleEntered.countDown()
                check(firstIdleRelease.await(5, TimeUnit.SECONDS))
            }
            state.set(if (stopping) "stopping" else "idle")
        }
        val buildReady = CompletableDeferred<Unit>()
        val buildRelease = CompletableDeferred<Unit>()
        val build = scope.async { gate.run("BUILD") { buildReady.complete(Unit); buildRelease.await() } }
        buildReady.await(); buildRelease.complete(Unit)
        assertTrue(firstIdleEntered.reached())
        val stopReady = CompletableDeferred<Unit>()
        val stopRelease = CompletableDeferred<Unit>()
        val stop = scope.async { gate.stopDebug("DEBUG") { stopReady.complete(Unit); stopRelease.await() } }
        try {
            withTimeout(1_000) { stopReady.await() }
            assertTrue("Stop must enter while the callback is outside admission", stop.isActive)
            assertTrue(runCatching { gate.run("LATE") {} }.exceptionOrNull() is IllegalStateException)
            firstIdleRelease.countDown()
            build.await()
            assertEquals("A concurrent Stop must replace the earlier idle snapshot", "stopping", state.get())
            stopRelease.complete(Unit); stop.await()
            assertEquals("idle", state.get())
        } finally {
            firstIdleRelease.countDown(); stopRelease.complete(Unit)
            withTimeout(2_000) { build.join(); stop.join() }; scope.cancel()
        }
    }
    @Test(timeout = 10_000) fun failedAcceptedStatePublicationStillRunsAdapterCleanup() = runBlocking {
        val cleaned = AtomicInteger()
        val gate = BuildRunDebugOperationGate<String> { stopping ->
            if (stopping) error("controlled state publication failure")
        }
        val failure = runCatching { gate.stopDebug("DEBUG") { cleaned.incrementAndGet() } }.exceptionOrNull()
        assertEquals("controlled state publication failure", failure?.message)
        assertEquals("Accepted adapter cleanup must not be skipped", 1, cleaned.get())
        assertEquals("ready", gate.run("BUILD") { "ready" })
        gate.closeAndJoin()
    }
    @Test(timeout = 10_000) fun definitelyUnstartedCancellationSurfacesAndExitCanRetry() = runBlocking {
        val attempts = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = BuildRunDebugOperationGate<String>(cancellationThread = { action ->
            if (attempts.incrementAndGet() == 1) error("controlled worker creation failure")
            Thread(action, "f154-retry-cancel").apply { isDaemon = true }
        })
        val entered = CompletableDeferred<Unit>()
        val cleaned = AtomicInteger()
        val operation = scope.launch {
            gate.run("DEBUG") {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cleaned.incrementAndGet() }
            }
        }
        try {
            entered.await()
            val failure = withTimeout(1_000) { runCatching { gate.closeAndJoin() }.exceptionOrNull() }
            assertEquals("controlled worker creation failure", failure?.message)
            assertTrue("A failed dispatch is not owner completion", operation.isActive)
            assertEquals(0, cleaned.get())
            assertTrue(runCatching { gate.run("LATE") {} }.exceptionOrNull() is IllegalStateException)
            withTimeout(2_000) { gate.closeAndJoin(); operation.join() }
            assertEquals(2, attempts.get())
            assertEquals(1, cleaned.get())
        } finally { scope.cancel() }
    }

    @Test(timeout = 10_000) fun failedStopDispatchKeepsStartupAndAdapterOrderedUntilExplicitRetry() = runBlocking {
        val attempts = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val failed = CompletableDeferred<Unit>()
        val gate = BuildRunDebugOperationGate<String>(cancellationThread = { action ->
            if (attempts.incrementAndGet() == 1) {
                failed.complete(Unit)
                error("controlled Stop dispatch failure")
            }
            Thread(action, "f154-stop-retry-cancel").apply { isDaemon = true }
        })
        val entered = CompletableDeferred<Unit>()
        val operationCleaned = AtomicInteger()
        val adapterCleaned = AtomicInteger()
        val operation = scope.launch {
            gate.run("DEBUG") {
                entered.complete(Unit)
                try { awaitCancellation() } finally { operationCleaned.incrementAndGet() }
            }
        }
        entered.await()
        val stop = scope.async {
            runCatching { gate.stopDebug("DEBUG") {
                assertEquals("Startup must finish before adapter cleanup", 1, operationCleaned.get())
                adapterCleaned.incrementAndGet()
            } }.exceptionOrNull()
        }
        try {
            failed.await()
            delay(30)
            assertTrue(operation.isActive)
            assertFalse("Accepted Stop remains pending after failed dispatch", stop.isCompleted)
            assertEquals(0, adapterCleaned.get())
            assertTrue(runCatching { gate.run("LATE") {} }.exceptionOrNull() is IllegalStateException)
            assertTrue(gate.cancelActive("Retry failed startup cancellation"))
            val failure = withTimeout(2_000) { stop.await() }
            assertEquals("controlled Stop dispatch failure", failure?.message)
            operation.join()
            assertEquals(1, operationCleaned.get())
            assertEquals(1, adapterCleaned.get())
            assertEquals(2, attempts.get())
            assertEquals("ready", gate.run("BUILD") { "ready" })
        } finally { scope.cancel() }
    }

}
