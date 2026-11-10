package com.baystudio.droide.core

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactTransferWaiterRegistryTest {
    @Test fun cancelingOneOfTwoCallersKeepsTheirSharedWorkerRunning() {
        val registry = ArtifactTransferWaiterRegistry()
        var starts = 0
        var stops = 0
        val first = registry.join("artifact") {
            assertEquals(1, registry.waiterCount()) // The worker never starts without its caller.
            starts++
        }
        val second = registry.join("artifact") { starts++ }
        registry.leave(first, canceled = true) { stops++ }
        second.ensureActive()
        assertEquals(1, starts)
        assertEquals(0, stops)
        assertEquals(1, registry.waiterCount())
        registry.leave(second, canceled = false) { stops++ }
        assertEquals(0, stops)
        assertEquals(0, registry.waiterCount())
    }

    @Test fun theLastCanceledCallerStopsTheWorkerOnceAndReleasesItsRegistration() {
        val registry = ArtifactTransferWaiterRegistry()
        var stops = 0
        val waiter = registry.join("artifact") {}
        registry.leave(waiter, canceled = true) {
            assertEquals(0, registry.waiterCount())
            stops++
        }
        registry.leave(waiter, canceled = true) { stops++ }
        assertEquals(1, stops)
        assertEquals(0, registry.waiterCount())
    }

    @Test fun successAndFailureBothReleaseTheirCallers() {
        val registry = ArtifactTransferWaiterRegistry()
        var stops = 0
        for (fail in listOf(false, true)) {
            val waiter = registry.join("artifact") {}
            try {
                if (fail) error("consumer failed")
            } catch (_: IllegalStateException) {
                assertTrue(fail)
            } finally {
                registry.leave(waiter, canceled = false) { stops++ }
            }
            assertEquals(0, registry.waiterCount())
        }
        assertEquals(0, stops)
    }

    @Test fun rejectedSchedulingDoesNotRetainTheCallerOrPreventRetry() {
        val registry = ArtifactTransferWaiterRegistry()
        var starts = 0
        try {
            registry.join("artifact") { error("Android rejected scheduling") }
            error("Expected schedule failure")
        } catch (failure: IllegalStateException) {
            assertEquals("Android rejected scheduling", failure.message)
        }
        assertEquals(0, registry.waiterCount())
        val retry = registry.join("artifact") { starts++ }
        assertEquals(1, starts)
        registry.leave(retry, canceled = false) {}
        assertEquals(0, registry.waiterCount())
    }

    @Test fun explicitGlobalStopCancelsEveryCurrentCallerAndLeavesANewRetryAlone() {
        val registry = ArtifactTransferWaiterRegistry()
        var starts = 0
        var stops = 0
        val first = registry.join("artifact") { starts++ }
        val second = registry.join("artifact") { starts++ }
        registry.cancelAll("artifact", "Android user stop") { stops++ }
        for (waiter in listOf(first, second)) {
            try {
                waiter.ensureActive()
                error("Expected global cancellation")
            } catch (canceled: CancellationException) {
                assertEquals("Android user stop", canceled.message)
            }
        }
        val retry = registry.join("artifact") { starts++ }
        registry.leave(first, canceled = true) { stops++ }
        registry.leave(second, canceled = true) { stops++ }
        retry.ensureActive()
        assertEquals(2, starts)
        assertEquals(1, stops)
        assertEquals(1, registry.waiterCount())
        registry.leave(retry, canceled = true) { stops++ }
        assertEquals(2, stops)
        assertEquals(0, registry.waiterCount())
    }

    @Test fun aGlobalStopStillStopsAPersistedWorkerWithoutLiveCallers() {
        val registry = ArtifactTransferWaiterRegistry()
        var stops = 0
        registry.cancelAll("artifact", "notification stop") { stops++ }
        assertEquals(1, stops)
        assertEquals(0, registry.waiterCount())
    }

    @Test fun aNewJoinCannotSlipBetweenTheLastCallerDecisionAndWorkerCancellation() {
        val registry = ArtifactTransferWaiterRegistry()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val waiter = registry.join("artifact") { events.add("old start") }
        val cancellationEntered = CountDownLatch(1)
        val finishCancellation = CountDownLatch(1)
        val joinAttempted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val cancellation = executor.submit {
                registry.leave(waiter, canceled = true) {
                    cancellationEntered.countDown()
                    check(finishCancellation.await(2, TimeUnit.SECONDS))
                    events.add("old stop")
                }
            }
            assertTrue(cancellationEntered.await(2, TimeUnit.SECONDS))
            val join = executor.submit<ArtifactTransferWaiterRegistry.Waiter> {
                joinAttempted.countDown()
                registry.join("artifact") { events.add("new start") }
            }
            assertTrue(joinAttempted.await(2, TimeUnit.SECONDS))
            assertFalse(join.isDone)
            finishCancellation.countDown()
            cancellation.get(2, TimeUnit.SECONDS)
            val next = join.get(2, TimeUnit.SECONDS)
            next.ensureActive()
            assertEquals(listOf("old start", "old stop", "new start"), events.toList())
            registry.leave(next, canceled = false) {}
            assertEquals(0, registry.waiterCount())
        } finally {
            finishCancellation.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun aStopFailureStillDetachesTheLastCanceledCaller() {
        val registry = ArtifactTransferWaiterRegistry()
        val waiter = registry.join("artifact") {}
        try {
            registry.leave(waiter, canceled = true) { error("Android service unavailable") }
            error("Expected stop failure")
        } catch (failure: IllegalStateException) {
            assertEquals("Android service unavailable", failure.message)
        }
        registry.leave(waiter, canceled = false) {}
        assertEquals(0, registry.waiterCount())
    }

    @Test fun anOldAndroidStopDoesNotCancelTheWaitersOfANewRequest() {
        val registry = ArtifactTransferWaiterRegistry()
        val waiter = registry.join("artifact") {}
        assertFalse(registry.cancelAllIf("artifact", "old Android stop") { false })
        waiter.ensureActive()
        assertTrue(registry.cancelAllIf("artifact", "current Android stop") { true })
        try {
            waiter.ensureActive()
            error("Expected cancellation")
        } catch (canceled: CancellationException) {
            assertEquals("current Android stop", canceled.message)
        }
        registry.leave(waiter, canceled = true) { error("Already stopped globally") }
        assertEquals(0, registry.waiterCount())
    }
}
