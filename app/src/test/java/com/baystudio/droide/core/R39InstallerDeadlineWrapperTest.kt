package com.baystudio.droide.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Actual helper and coordinator, inert suspend callbacks only; no Android/runtime construction. */
class R39InstallerDeadlineWrapperTest {
    @Test fun zeroAndPositiveModesExecuteExactlyOnceAndAllowNullableSuccess() = runBlocking {
        for (deadline in listOf(0L, 1_000L)) {
            var calls = 0
            assertEquals("done", withInstallerOperationDeadline(deadline) { calls++; "done" })
            assertEquals(1, calls)
            assertNull(withInstallerOperationDeadline<String?>(deadline) { null })
        }
    }

    @Test fun negativeDeadlineRejectsBeforeCallback() = runBlocking {
        var called = false
        try { withInstallerOperationDeadline(-1) { called = true }; fail("Negative deadline accepted") }
        catch (_: IllegalArgumentException) { }
        assertFalse(called)
    }

    @Test(timeout = 10_000) fun ownExpiryWaitsForCallbackCleanupAndRemainsFailure() = runBlocking {
        withTimeout(2_000) {
            var cleaned = false
            try {
                withInstallerOperationDeadline(25) { try { awaitCancellation() } finally { cleaned = true } }
                fail("Own deadline did not expire")
            } catch (expired: InstallerOperationDeadlineExceeded) {
                assertEquals(25L, expired.timeoutMs); assertTrue(cleaned)
            }
        }
    }

    @Test(timeout = 10_000) fun callerStopCancelsBothModesAndJoinsCleanup() = runBlocking {
        for (deadline in listOf(0L, 60_000L)) {
            val entered = CompletableDeferred<Unit>()
            var cleaned = false
            val job = launch {
                withInstallerOperationDeadline(deadline) {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { cleaned = true }
                }
            }
            try { withTimeout(2_000) { entered.await(); job.cancelAndJoin() } }
            finally { withContext(NonCancellable) { withTimeout(2_000) { job.cancelAndJoin() } } }
            assertTrue(job.isCancelled); assertTrue(cleaned)
        }
    }

    @Test(timeout = 10_000) fun externalAndNestedTimeoutsAreNotRelabeledAsOwnExpiry() = runBlocking {
        for (deadline in listOf(0L, 60_000L)) {
            try {
                withTimeout(25) { withInstallerOperationDeadline(deadline) { awaitCancellation() } }
                fail("External deadline disappeared")
            } catch (_: TimeoutCancellationException) { }
            try {
                withInstallerOperationDeadline(deadline) { withTimeout(25) { awaitCancellation() } }
                fail("Nested callback deadline disappeared")
            } catch (_: TimeoutCancellationException) { }
        }
    }

    @Test(timeout = 10_000) fun callerCancellationWinsWhenOwnExpiryIsWaitingForCleanup() = runBlocking {
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val observed = CompletableDeferred<Throwable>()
        val job = async {
            try {
                withInstallerOperationDeadline(25) {
                    try { awaitCancellation() } finally {
                        withContext(NonCancellable) { cleanupEntered.complete(Unit); releaseCleanup.await() }
                    }
                }
            } catch (failure: Throwable) { observed.complete(failure); throw failure }
        }
        try {
            withTimeout(2_000) {
                cleanupEntered.await()
                job.cancel(CancellationException("caller stop during cleanup"))
                releaseCleanup.complete(Unit)
                job.join()
                assertTrue(observed.await() is CancellationException)
            }
        } finally {
            releaseCleanup.complete(Unit)
            withContext(NonCancellable) { withTimeout(2_000) { job.cancelAndJoin() } }
        }
    }

    @Test(timeout = 10_000) fun coordinatorRetainsOwnershipUntilCleanupForCancellationAndExpiry() = runBlocking {
        withTimeout(5_000) {
            for (ownExpiry in listOf(false, true)) supervisorScope {
                val coordinator = InstallerOperationCoordinator()
                val entered = CompletableDeferred<Unit>()
                val cleanupEntered = CompletableDeferred<Unit>()
                val releaseCleanup = CompletableDeferred<Unit>()
                var cleaned = false
                var starts = 0
                var finishes = 0
                var finalFailure: Throwable? = null
                val operation = async {
                    coordinator.operation(onStart = { starts++ }, onFinish = {
                        assertTrue(cleaned); finalFailure = it; finishes++
                    }) {
                        coordinator.operation(onStart = { fail("Nested operation stole ownership") }) {
                            withInstallerOperationDeadline(if (ownExpiry) 25L else 0L) {
                                entered.complete(Unit)
                                try { awaitCancellation() } finally {
                                    withContext(NonCancellable) {
                                        cleanupEntered.complete(Unit); releaseCleanup.await(); cleaned = true
                                    }
                                }
                            }
                        }
                    }
                }
                var snapshot: Deferred<Unit>? = null
                try {
                    entered.await()
                    if (!ownExpiry) coordinator.cancel()
                    cleanupEntered.await()
                    assertTrue(coordinator.active)
                    val queued = CompletableDeferred<Unit>()
                    val reader = async { queued.complete(Unit); coordinator.snapshot { assertTrue(cleaned) } }
                    snapshot = reader
                    queued.await(); assertFalse(reader.isCompleted)
                    releaseCleanup.complete(Unit)
                    try { operation.await(); fail("Expected cancellation or expiry") }
                    catch (failure: Throwable) {
                        if (ownExpiry) assertTrue(failure is InstallerOperationDeadlineExceeded)
                        else assertTrue(failure is CancellationException)
                    }
                    reader.await()
                    assertEquals(1, starts); assertEquals(1, finishes); assertFalse(coordinator.active)
                    if (ownExpiry) assertTrue(finalFailure is InstallerOperationDeadlineExceeded)
                    else assertTrue(finalFailure is CancellationException)
                } finally {
                    releaseCleanup.complete(Unit)
                    withContext(NonCancellable) { withTimeout(2_000) { operation.cancelAndJoin(); snapshot?.cancelAndJoin() } }
                }
            }
        }
    }

    @Test(timeout = 10_000) fun ordinaryAndNestedDedicatedFailuresRemainIdentifiable() = runBlocking {
        for (deadline in listOf(0L, 60_000L)) {
            try { withInstallerOperationDeadline(deadline) { throw IllegalArgumentException("fixture failure") }; fail("Failure swallowed") }
            catch (failure: IllegalArgumentException) { assertEquals("fixture failure", failure.message) }
            try {
                withInstallerOperationDeadline(deadline) { withInstallerOperationDeadline(25) { awaitCancellation() } }
                fail("Nested owned expiry swallowed")
            } catch (failure: InstallerOperationDeadlineExceeded) { assertEquals(25L, failure.timeoutMs) }
        }
    }
}
