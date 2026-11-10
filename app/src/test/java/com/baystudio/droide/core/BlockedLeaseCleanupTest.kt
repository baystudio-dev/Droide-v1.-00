package com.baystudio.droide.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Actual owned OS children. A blocked callback is released only by test teardown. */
class BlockedLeaseCleanupTest {
    @Test fun captureTimeoutTerminatesChildWithoutFalselyCompletingBlockedLease() {
        val child = ProcessBuilder("/bin/sh", "-c", "exec sleep 30").start()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val calls = AtomicInteger(); val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(); val receipt = AtomicReference<Throwable?>()
        val worker = Thread {
            try { runBlocking { LocalProcessSupervisor.captureOwned(child, timeoutMs = 50,
                cleanup = { calls.incrementAndGet(); entered.countDown(); release.await() },
                onCleanupCompleted = { receipt.set(it) }) } }
            catch (error: Throwable) { failure.set(error) }
            finally { done.countDown() }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertTrue("Blocked lease must not prevent owned-child termination", child.waitFor(10, TimeUnit.SECONDS))
            assertTrue("Capture must return an unconfirmed-cleanup error", done.await(2, TimeUnit.SECONDS))
            assertNotNull(failure.get()); assertNotNull(receipt.get())
            assertEquals(1L, release.count); assertEquals(1, calls.get())
        } finally { release.countDown(); worker.join(5_000); LocalProcessSupervisor.terminate(child) }
    }

    @Test fun hostedCloseTerminatesOnlyOwnedChildAndRetainsFailedReceipt() = runBlocking {
        val child = ProcessBuilder("/bin/sh", "-c", "exec sleep 30").start()
        val unrelated = ProcessBuilder("/bin/sh", "-c", "exec sleep 30").start()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val hosted = LocalHostedProcess(child, scope) { calls.incrementAndGet(); entered.countDown(); release.await() }
        try {
            hosted.close()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertTrue("Blocked lease must not prevent owned-child termination", child.waitFor(10, TimeUnit.SECONDS))
            runCatching { withTimeout(2_000) { hosted.awaitExit() } }.exceptionOrNull().also {
                assertTrue(it is IllegalStateException)
                assertFalse(it is TimeoutCancellationException)
                assertEquals("Owned process lifecycle failed", it?.message)
            }
            assertTrue(unrelated.isAlive); assertEquals(1L, release.count)
            hosted.close(); assertEquals(1, calls.get())
            release.countDown()
            runCatching { withTimeout(2_000) { hosted.awaitExit() } }.exceptionOrNull().also {
                assertTrue(it is IllegalStateException)
                assertFalse(it is TimeoutCancellationException)
                assertEquals("Owned process lifecycle failed", it?.message)
            }
            assertEquals(1, calls.get())
        } finally { release.countDown(); hosted.close(); scope.cancel(); LocalProcessSupervisor.terminate(child); LocalProcessSupervisor.terminate(unrelated) }
    }
    @Test fun cancellationStillTerminatesChildAndPreservesCancellation() = runBlocking {
        val child = ProcessBuilder("/bin/sh", "-c", "exec sleep 30").start()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val receipt = AtomicReference<Throwable?>(); val failure = AtomicReference<Throwable?>()
        val job = launch(Dispatchers.IO) {
            try { LocalProcessSupervisor.captureOwned(child, timeoutMs = 30_000,
                cleanup = { entered.countDown(); release.await() }, onCleanupCompleted = { receipt.set(it) }) }
            catch (error: Throwable) { failure.set(error); throw error }
        }
        try {
            delay(100); job.cancel()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertTrue(child.waitFor(10, TimeUnit.SECONDS))
            withTimeout(2_000) { job.join() }
            assertTrue(failure.get() is CancellationException)
            assertNotNull(receipt.get()); assertEquals(1L, release.count)
        } finally { release.countDown(); job.cancelAndJoin(); LocalProcessSupervisor.terminate(child) }
    }

    @Test fun oneAttemptCanBeObservedAgainWithoutReplayingCallback() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(1)
        val calls = AtomicInteger()
        val attempt = OwnedLeaseCleanupAttempt { calls.incrementAndGet(); entered.countDown(); release.await(); finished.countDown() }
        val failure = AtomicReference<Throwable?>()
        val worker = Thread { try { attempt.observe() } catch (error: Throwable) { failure.set(error) } }
        worker.start()
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS)); worker.interrupt(); worker.join(2_000)
            assertNotNull(failure.get()); assertEquals(1, calls.get()); assertEquals(1L, release.count)
            release.countDown(); assertTrue(finished.await(3, TimeUnit.SECONDS))
            attempt.observe(); attempt.observe(); assertEquals(1, calls.get())
        } finally { release.countDown(); worker.join(2_000) }
    }

    @Test fun rejectedAdmissionReapsExactChildDespiteBlockedLease() {
        val root = java.nio.file.Files.createTempDirectory("rejected-cleanup-").toFile()
        val child = AtomicReference<Process?>(); val original = IllegalArgumentException("admission rejected after spawn")
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(); val receipt = AtomicReference<Throwable?>()
        val worker = Thread {
            try { runBlocking { LocalProcessSupervisor.captureWithAdmission(
                listOf("/bin/sh", "-c", "exec sleep 30"), root, emptyMap(), 1024, 30_000,
                cleanup = { entered.countDown(); release.await() }, onCleanupCompleted = { receipt.set(it) },
                admitStart = { create -> child.set(create()); throw original }) } }
            catch (error: Throwable) { failure.set(error) }
            finally { done.countDown() }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertTrue(checkNotNull(child.get()).waitFor(10, TimeUnit.SECONDS))
            assertTrue(done.await(2, TimeUnit.SECONDS)); assertSame(original, failure.get())
            assertTrue(original.suppressed.isNotEmpty()); assertNotNull(receipt.get())
            assertEquals(1L, release.count)
        } finally { release.countDown(); worker.join(5_000); child.get()?.let(LocalProcessSupervisor::terminate); root.delete() }
    }

}
