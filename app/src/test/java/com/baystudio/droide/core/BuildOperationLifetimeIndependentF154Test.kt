package com.baystudio.droide.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Independent direct-production gate checks, not an Android/device execution claim. */
@OptIn(InternalCoroutinesApi::class)
class BuildOperationLifetimeIndependentF154Test {
    private class MainHarness : AutoCloseable {
        val thread = AtomicReference<Thread>()
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "f154-lifetime-main").apply { isDaemon = true; thread.set(this) }
        }
        val dispatcher = object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext) = Thread.currentThread() !== thread.get()
            override fun dispatch(context: CoroutineContext, block: Runnable) { executor.execute(block) }
        }
        val main = CoroutineScope(SupervisorJob() + dispatcher)
        val workers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        override fun close() {
            main.cancel(); workers.cancel(); executor.shutdown()
            assertTrue("Test Main executor must terminate", executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }
    private fun CountDownLatch.reached(ms: Long = 1500) = await(ms, TimeUnit.MILLISECONDS)
    private suspend fun Job.finished() = withTimeout(2500) { join() }
    private fun Thread.retired() {
        join(2000)
        assertFalse("Dedicated cancellation thread must retire after its callback returns", isAlive)
    }
    private fun origin() = println("GATE_ORIGIN ${BuildRunDebugOperationGate::class.java.protectionDomain.codeSource.location}")

    @Test(timeout = 8000) fun operationJobEndsBeforeCallerMovesToUnrelatedWork() = runBlocking {
        origin()
        MainHarness().use { harness ->
            val gate = BuildRunDebugOperationGate<String>()
            val jobs = CompletableDeferred<Pair<Job, Job>>()
            val unrelated = CompletableDeferred<Unit>()
            val caller = harness.workers.async {
                val callerJob = currentCoroutineContext()[Job]!!
                val operationJob = gate.run("BUILD") { currentCoroutineContext()[Job]!! }
                jobs.complete(callerJob to operationJob)
                unrelated.await()
            }
            try {
                val (outer, operation) = withTimeout(1500) { jobs.await() }
                assertNotSame("Cancellation must target a bounded operation, never its reusable caller", outer, operation)
                assertTrue("Operation lifetime must end before gate.run returns", operation.isCompleted)
                assertTrue("Unrelated caller work stays active", caller.isActive)
                gate.closeAndJoin()
                assertTrue("Closing an idle gate must not cancel the old caller", caller.isActive)
            } finally {
                unrelated.complete(Unit); caller.finished()
            }
        }
    }

    @Test(timeout = 10000) fun cancelActiveReturnsOnMainAndDoesNotHoldAdmissionDuringBlockingCallback() = runBlocking {
        MainHarness().use { harness ->
            val gate = BuildRunDebugOperationGate<String>()
            val started = CompletableDeferred<Unit>()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val cancelReturned = CountDownLatch(1)
            val mainPing = CountDownLatch(1)
            val admissionChecked = CountDownLatch(1)
            val callbackThread = AtomicReference<Thread>()
            val cancels = AtomicInteger()
            val operation = harness.workers.launch {
                gate.run("BUILD") {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        continuation.invokeOnCancellation {
                            callbackThread.set(Thread.currentThread()); cancels.incrementAndGet(); entered.countDown()
                            check(release.reached(5000))
                        }
                        started.complete(Unit)
                    }
                }
            }
            started.await()
            val requester = harness.main.async {
                val accepted = gate.cancelActive("explicit cancellation")
                cancelReturned.countDown(); accepted
            }
            var probe: Deferred<Throwable?>? = null
            try {
                assertTrue("Actual cancellation callback must be reached", entered.reached())
                harness.main.launch { mainPing.countDown() }
                probe = harness.workers.async {
                    runCatching { gate.run("LATE") { error("Late work must be rejected") } }.exceptionOrNull()
                        .also { admissionChecked.countDown() }
                }
                val returnedWhileHeld = cancelReturned.reached(400)
                val mainWhileHeld = mainPing.reached(400)
                val monitorFree = admissionChecked.reached(400)
                println("CANCEL_HELD returned=$returnedWhileHeld main=$mainWhileHeld monitorFree=$monitorFree callbackOnMain=${callbackThread.get() === harness.thread.get()}")
                assertTrue("cancelActive must dispatch blocking cancellation rather than execute it on Main", returnedWhileHeld)
                assertTrue("Main must remain responsive", mainWhileHeld)
                assertTrue("Admission query must not wait behind transport close", monitorFree)
                assertNotSame("Transport cancellation must not run on Main", harness.thread.get(), callbackThread.get())
                assertTrue(probe.await() is IllegalStateException)
            } finally {
                release.countDown(); operation.finished(); requester.finished(); probe?.finished()
            }
            assertTrue(requester.await())
            assertEquals(1, cancels.get())
            gate.closeAndJoin()
            callbackThread.get().retired()
        }
    }

    @Test(timeout = 12000) fun exitRetainsCancelReturnReceiptAfterOperationAndCallerHaveMovedOn() = runBlocking {
        MainHarness().use { harness ->
            val gate = BuildRunDebugOperationGate<String>()
            val started = CompletableDeferred<Job>()
            val releaseBody = CompletableDeferred<Unit>()
            val callerMovedOn = CountDownLatch(1)
            val releaseCaller = CompletableDeferred<Unit>()
            val callbackEntered = CountDownLatch(1)
            val callbackRelease = CountDownLatch(1)
            val callbackCount = AtomicInteger()
            val callbackThread = AtomicReference<Thread>()
            val mainPing = CountDownLatch(1)
            val caller = harness.workers.async {
                try {
                    gate.run("DEBUG") {
                        val owned = currentCoroutineContext()[Job]!!
                        owned.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
                            callbackThread.set(Thread.currentThread()); callbackCount.incrementAndGet(); callbackEntered.countDown()
                            check(callbackRelease.reached(6000))
                        }
                        withContext(NonCancellable) {
                            started.complete(owned)
                            releaseBody.await()
                        }
                    }
                } catch (_: CancellationException) { }
                callerMovedOn.countDown()
                withContext(NonCancellable) { releaseCaller.await() }
            }
            val operation = started.await()
            val close = harness.main.async { gate.closeAndJoin() }
            var retry: Deferred<Unit>? = null
            try {
                assertTrue("Cancellation callback must be held", callbackEntered.reached())
                releaseBody.complete(Unit)
                assertTrue("Operation body must be able to retire while callback is held", callerMovedOn.reached())
                harness.main.launch { mainPing.countDown() }
                val responsive = mainPing.reached(400)
                retry = harness.workers.async { gate.closeAndJoin() }
                // A completed coroutine is not proof that its external cancel() invocation returned.
                println("RECEIPT_HELD operationCompleted=${operation.isCompleted} callerActive=${caller.isActive} closeDone=${close.isCompleted} retryDone=${retry.isCompleted} main=$responsive")
                assertTrue("Per-operation child must be complete independently of caller", operation.isCompleted)
                assertTrue("Cancelled operation must not cancel unrelated caller work", caller.isActive)
                assertTrue("Exit observer must remain responsive", responsive)
                assertFalse("Exit must retain callback-return receipt beyond operation completion", close.isCompleted)
                assertFalse("Retry must await the same held callback", retry.isCompleted)
                assertNotSame(harness.thread.get(), callbackThread.get())
                close.cancel()
                close.finished()
                assertFalse("Cancelling only the observer cannot discard the retained request", retry.isCompleted)
            } finally {
                callbackRelease.countDown(); releaseBody.complete(Unit); releaseCaller.complete(Unit)
                caller.finished(); close.finished(); retry?.finished()
            }
            assertTrue(close.isCancelled); retry?.await()
            callbackThread.get().retired()
            assertEquals("Repeated close shares the exact cancellation request", 1, callbackCount.get())
            assertTrue(runCatching { gate.run("LATE") {} }.exceptionOrNull() is IllegalStateException)
        }
    }

    @Test(timeout = 12000) fun acceptedStopSurvivesCallerCancellationAndSharesFailureWithoutCancelingBuild() = runBlocking {
        MainHarness().use { harness ->
            val gate = BuildRunDebugOperationGate<String>()
            val started = CompletableDeferred<Unit>()
            val callbackEntered = CountDownLatch(1)
            val callbackRelease = CountDownLatch(1)
            val callbackThread = AtomicReference<Thread>()
            val mainPing = CountDownLatch(1)
            val cleanupEntered = CompletableDeferred<Unit>()
            val cleanupRelease = CompletableDeferred<Unit>()
            val cleanups = AtomicInteger()
            val operation = harness.workers.launch {
                gate.run("DEBUG") {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        continuation.invokeOnCancellation {
                            callbackThread.set(Thread.currentThread()); callbackEntered.countDown()
                            check(callbackRelease.reached(6000))
                        }
                        started.complete(Unit)
                    }
                }
            }
            started.await()
            val first = harness.main.async {
                runCatching {
                    gate.stopDebug("DEBUG") {
                        cleanups.incrementAndGet(); cleanupEntered.complete(Unit); cleanupRelease.await()
                        error("controlled adapter close failure")
                    }
                }.exceptionOrNull()
            }
            var second: Deferred<Throwable?>? = null
            var registration: Job? = null
            val secondRegistered = CountDownLatch(1)
            try {
                assertTrue(callbackEntered.reached())
                harness.main.launch { mainPing.countDown() }
                registration = harness.workers.launch {
                    second = harness.workers.async(start = CoroutineStart.UNDISPATCHED) {
                        runCatching { gate.stopDebug("DEBUG") { error("Duplicate cleanup") } }.exceptionOrNull()
                    }
                    // UNDISPATCHED on an independent worker proves the second call
                    // reached its coalesced wait; never block the test observer itself.
                    secondRegistered.countDown()
                }
                val responsive = mainPing.reached(400)
                assertTrue("Stop cancellation must not block Main", responsive)
                assertTrue("Duplicate Stop must enter without waiting for transport close", secondRegistered.reached(400))
                assertNotSame(harness.thread.get(), callbackThread.get())
                first.cancel()
                assertFalse("Accepted Stop remains owned while cancellation callback is held", first.isCompleted)
                callbackRelease.countDown()
                withTimeout(2000) { cleanupEntered.await() }
                assertFalse(first.isCompleted)
                assertFalse(second!!.isCompleted)
                assertTrue(runCatching { gate.run("BUILD") {} }.exceptionOrNull() is IllegalStateException)
            } finally {
                callbackRelease.countDown(); cleanupRelease.complete(Unit)
                operation.finished(); first.finished(); registration?.finished(); second?.finished()
            }
            callbackThread.get().retired()
            assertEquals(1, cleanups.get())
            assertEquals("controlled adapter close failure", second?.await()?.message)
            assertTrue(first.isCancelled)
            val buildEntered = CompletableDeferred<Unit>()
            val buildRelease = CompletableDeferred<Unit>()
            val build = harness.workers.async { gate.run("BUILD") { buildEntered.complete(Unit); buildRelease.await(); "fresh" } }
            try {
                buildEntered.await()
                gate.stopDebug("DEBUG") { cleanups.incrementAndGet() }
                assertTrue("Stop Debug must not cancel independent Build", build.isActive)
                buildRelease.complete(Unit)
                assertEquals("fresh", build.await())
            } finally { buildRelease.complete(Unit); build.finished() }
            gate.closeAndJoin()
        }
    }

    @Test(timeout = 8000) fun operationChildrenKeepAdmissionUntilTheirOwnCleanupFinishes() = runBlocking {
        MainHarness().use { harness ->
            val gate = BuildRunDebugOperationGate<String>()
            val childEntered = CompletableDeferred<Unit>()
            val childRelease = CompletableDeferred<Unit>()
            val parentBodyReturned = CompletableDeferred<Unit>()
            val operation = harness.workers.async {
                gate.run("BUILD") {
                    CoroutineScope(currentCoroutineContext()).launch {
                        childEntered.complete(Unit)
                        childRelease.await()
                    }
                    parentBodyReturned.complete(Unit)
                    "finished"
                }
            }
            try {
                childEntered.await(); parentBodyReturned.await()
                assertFalse("Gate return must include operation children", operation.isCompleted)
                assertTrue("Admission must remain reserved for the child's owned work",
                    runCatching { gate.run("DEBUG") {} }.exceptionOrNull() is IllegalStateException)
            } finally { childRelease.complete(Unit); operation.finished() }
            assertEquals("finished", operation.await())
            assertEquals("next", gate.run("DEBUG") { "next" })
        }
    }

    @Test(timeout = 12000) fun exitCancelsOnlyAcceptedStopLifetimeAndRetainsItsCallbackReceipt() = runBlocking {
        MainHarness().use { harness ->
            val gate = BuildRunDebugOperationGate<String>()
            val cleanupEntered = CompletableDeferred<Unit>()
            val cleanupRelease = CompletableDeferred<Unit>()
            val callerMovedOn = CountDownLatch(1)
            val callerRelease = CompletableDeferred<Unit>()
            val callbackEntered = CountDownLatch(1)
            val callbackRelease = CountDownLatch(1)
            val callbackThread = AtomicReference<Thread>()
            val callbackCount = AtomicInteger()
            val caller = harness.workers.async {
                try {
                    gate.stopDebug("DEBUG") { cleanupEntered.complete(Unit); cleanupRelease.await() }
                } catch (_: CancellationException) { }
                callerMovedOn.countDown()
                withContext(NonCancellable) { callerRelease.await() }
            }
            cleanupEntered.await()
            // This is the structured child created by the accepted Stop call, not a
            // reflected private field or a test replacement for the production owner.
            val stopOwner = caller.children.singleOrNull()
            if (stopOwner == null) {
                cleanupRelease.complete(Unit); callerRelease.complete(Unit); caller.finished()
                fail("Accepted Stop needs a bounded structured child lifetime")
            }
            checkNotNull(stopOwner).invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
                callbackThread.set(Thread.currentThread()); callbackCount.incrementAndGet(); callbackEntered.countDown()
                check(callbackRelease.reached(6000))
            }
            val close = harness.main.async { gate.closeAndJoin() }
            try {
                assertTrue(callbackEntered.reached())
                cleanupRelease.complete(Unit)
                assertTrue(callerMovedOn.reached())
                assertTrue("Accepted Stop lifetime may finish while its cancel callback is pending", stopOwner.isCompleted)
                assertTrue("Exit must not cancel work following stopDebug in the same caller", caller.isActive)
                assertFalse("Stop's finished Job cannot replace its cancel-return receipt", close.isCompleted)
                assertNotSame(harness.thread.get(), callbackThread.get())
            } finally {
                callbackRelease.countDown(); cleanupRelease.complete(Unit); callerRelease.complete(Unit)
                close.finished(); caller.finished()
            }
            close.await(); callbackThread.get().retired()
            assertEquals(1, callbackCount.get())
        }
    }

}
