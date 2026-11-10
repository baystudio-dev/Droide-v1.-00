package com.baystudio.droide.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class InteractiveTerminalCleanupReceiptTest {
    private fun child() = ProcessBuilder("/bin/sh", "-c", "exec sleep 60").start()
    private class BlockingDestroy(val delegate: Process) : Process() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val ioReady = CountDownLatch(1)
        override fun getOutputStream() = delegate.outputStream
        override fun getInputStream(): java.io.InputStream { ioReady.countDown(); return delegate.inputStream }
        override fun getErrorStream() = delegate.errorStream
        override fun waitFor() = delegate.waitFor()
        override fun waitFor(timeout: Long, unit: TimeUnit) = delegate.waitFor(timeout, unit)
        override fun exitValue() = delegate.exitValue()
        override fun isAlive() = delegate.isAlive
        override fun destroy() { calls.incrementAndGet(); entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); delegate.destroy() }
        override fun destroyForcibly(): Process { delegate.destroyForcibly(); return this }
    }
    private suspend fun until(block: () -> Boolean) = withTimeout(3_000) { while (!block()) delay(5) }

    @Test fun failedGenerationReservesKnownChildBeforeBlockedLeaseObservation() = runBlocking {
        val wrapped = BlockingDestroy(ProcessBuilder("/bin/sh", "-c", "printf 'ready\\n'; exec sleep 60").start())
        val leaseEntered = CountDownLatch(1); val leaseRelease = CountDownLatch(1)
        val starts = AtomicInteger(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val terminal = InteractiveTerminalProcess(scope, { starts.incrementAndGet(); wrapped }, {
            leaseEntered.countDown(); check(leaseRelease.await(10, TimeUnit.SECONDS))
        }, { throw java.io.IOException("consumer failed") })
        try {
            terminal.start(); assertTrue(leaseEntered.await(3, TimeUnit.SECONDS))
            assertTrue(wrapped.isAlive)
            val attempt = withTimeout(3_500) { withContext(Dispatchers.IO) { runCatching { terminal.start() } } }
            assertTrue(attempt.isFailure); assertEquals(1, starts.get()); assertTrue(wrapped.isAlive)
            terminal.destroy(); wrapped.release.countDown(); leaseRelease.countDown()
            withTimeout(4_000) { while (runCatching { terminal.destroyAndJoinForExit() }.isFailure) delay(5) }
            assertFalse(wrapped.isAlive)
        } finally {
            wrapped.release.countDown(); leaseRelease.countDown(); terminal.destroy()
            scope.coroutineContext[Job]!!.cancelAndJoin(); wrapped.delegate.destroyForcibly(); wrapped.delegate.waitFor()
        }
    }

    @Test fun parentCancellationPreservesLiveRootForLeaseGroupAuthority() = runBlocking {
        val owned = child(); val launched = CountDownLatch(1)
        val sawLive = java.util.concurrent.atomic.AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val terminal = InteractiveTerminalProcess(scope, { launched.countDown(); owned }, {
            sawLive.set(it.isAlive); it.destroy(); check(it.waitFor(2, TimeUnit.SECONDS))
        }, {})
        try {
            terminal.start(); assertTrue(launched.await(3, TimeUnit.SECONDS))
            scope.coroutineContext[Job]!!.cancelAndJoin()
            terminal.destroyAndJoinForExit()
            assertTrue("Lease must retain live-root group authority on ordinary parent cancellation", sawLive.get())
            assertFalse(owned.isAlive)
        } finally { terminal.destroy(); scope.cancel(); owned.destroyForcibly(); owned.waitFor() }
    }

    @OptIn(InternalCoroutinesApi::class)
    @Test fun concurrentStartCannotBypassOrdinaryRestartWhileCancellationIsBlocked() = runBlocking {
        val wrapped = BlockingDestroy(child()); val cancelEntered = CountDownLatch(1)
        val cancelRelease = CountDownLatch(1); val starts = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val terminal = InteractiveTerminalProcess(scope, { starts.incrementAndGet(); wrapped }, {}, {},
            {}, {}, 64_000, 256_000, null, {
                InteractiveTerminalProcess.LaunchAdmission { factory ->
                    currentCoroutineContext()[Job]!!.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
                        cancelEntered.countDown(); check(cancelRelease.await(10, TimeUnit.SECONDS))
                    }
                    factory()
                }
            })
        var restart: Deferred<Result<Unit>>? = null
        try {
            terminal.start(); assertTrue(wrapped.ioReady.await(3, TimeUnit.SECONDS))
            restart = async(Dispatchers.IO) { runCatching { terminal.restart() } }
            assertTrue(cancelEntered.await(3, TimeUnit.SECONDS))
            val concurrent = withTimeout(3_500) { withContext(Dispatchers.IO) { runCatching { terminal.start() } } }
            assertTrue(concurrent.isFailure); assertEquals(1, starts.get()); assertTrue(wrapped.isAlive)
            // Seal before releasing cancellation so the restart cannot admit a replacement.
            terminal.destroy(); cancelRelease.countDown(); wrapped.release.countDown()
            withTimeout(4_000) { restart.await(); while (runCatching { terminal.destroyAndJoinForExit() }.isFailure) delay(5) }
            assertEquals(1, starts.get())
        } finally {
            cancelRelease.countDown(); wrapped.release.countDown(); terminal.destroy()
            restart?.cancelAndJoin(); scope.coroutineContext[Job]!!.cancelAndJoin()
            wrapped.delegate.destroyForcibly(); wrapped.delegate.waitFor()
        }
    }

    @Test fun parentCancellationStopsExactChildBeforeBlockedLeaseReturns() = runBlocking {
        val owned = child(); val unrelated = child()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val cleanups = AtomicInteger()
        val launched = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val terminal = InteractiveTerminalProcess(scope, { launched.countDown(); owned }, {
            cleanups.incrementAndGet(); entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
        }, {})
        try {
            terminal.start(); assertTrue(launched.await(3, TimeUnit.SECONDS))
            scope.cancel()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertTrue("Exact child must stop after bounded lease observation", owned.waitFor(8, TimeUnit.SECONDS))
            assertTrue(unrelated.isAlive)
            assertEquals(1, cleanups.get())
            release.countDown()
            withTimeout(3_000) { terminal.destroyAndJoinForExit() }
            assertEquals(1, cleanups.get())
        } finally {
            release.countDown(); terminal.destroy(); scope.coroutineContext[Job]!!.cancelAndJoin()
            owned.destroyForcibly(); unrelated.destroyForcibly(); owned.waitFor(); unrelated.waitFor()
        }
    }

    @Test fun permanentDestroyReturnsWithoutCallingBlockingProcessMethodInline() = runBlocking {
        val wrapped = BlockingDestroy(child())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val launched = CountDownLatch(1)
        val terminal = InteractiveTerminalProcess(scope, { launched.countDown(); wrapped }, {}, {})
        var stopping: Deferred<Unit>? = null
        try {
            terminal.start(); assertTrue(launched.await(3, TimeUnit.SECONDS)); assertTrue(wrapped.ioReady.await(3, TimeUnit.SECONDS))
            stopping = async(Dispatchers.IO) { terminal.destroy() }
            withTimeout(1_000) { stopping.await() }
            assertTrue(wrapped.entered.await(3, TimeUnit.SECONDS)); assertTrue(wrapped.isAlive)
            terminal.destroy()
            assertEquals(1, wrapped.calls.get())
            wrapped.release.countDown()
            withTimeout(4_000) { terminal.destroyAndJoinForExit() }
            assertFalse(wrapped.isAlive); assertEquals(1, wrapped.calls.get())
        } finally {
            wrapped.release.countDown(); terminal.destroy(); stopping?.cancelAndJoin()
            scope.coroutineContext[Job]!!.cancelAndJoin(); wrapped.delegate.destroyForcibly(); wrapped.delegate.waitFor()
        }
    }
    @Test fun timedOutKnownStopBlocksRestartAndExplicitStartUntilSameAttemptCompletes() = runBlocking {
        val wrapped = BlockingDestroy(child())
        val children = java.util.concurrent.CopyOnWriteArrayList<Process>()
        val starts = AtomicInteger(); val cleanups = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val terminal = InteractiveTerminalProcess(scope, {
            (if (starts.incrementAndGet() == 1) wrapped else child()).also { children += it }
        }, { cleanups.incrementAndGet() }, {})
        try {
            terminal.start(); assertTrue(wrapped.ioReady.await(3, TimeUnit.SECONDS))
            val outcome = withTimeout(3_500) { withContext(Dispatchers.IO) { runCatching { terminal.restart() } } }
            assertTrue(outcome.isFailure); assertEquals(1, starts.get()); assertTrue(wrapped.isAlive)
            assertTrue(runCatching { terminal.start() }.isFailure); assertEquals(1, starts.get())
            assertEquals(1, wrapped.calls.get())
            wrapped.release.countDown(); until { !wrapped.isAlive }
            withTimeout(3_000) { while (runCatching { terminal.restart() }.isFailure) delay(5) }
            until { children.size == 2 }
            assertFalse(children.first().isAlive); assertTrue(children.last().isAlive)
            withTimeout(4_000) { terminal.destroyAndJoinForExit() }
            assertEquals(2, cleanups.get()); assertEquals(1, wrapped.calls.get())
        } finally {
            wrapped.release.countDown(); terminal.destroy(); scope.coroutineContext[Job]!!.cancelAndJoin()
            children.forEach { it.destroyForcibly(); it.waitFor() }
        }
    }

    @Test fun timedOutLeaseRemainsUnconfirmedThenSameCallbackReceiptCanComplete() = runBlocking {
        val owned = BlockingDestroy(child()).apply { release.countDown() }; val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val returned = CountDownLatch(1); val calls = AtomicInteger(); val launched = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val terminal = InteractiveTerminalProcess(scope, { launched.countDown(); owned }, {
            calls.incrementAndGet(); entered.countDown(); check(release.await(20, TimeUnit.SECONDS)); returned.countDown()
        }, {})
        try {
            terminal.start(); assertTrue(launched.await(3, TimeUnit.SECONDS)); assertTrue(owned.ioReady.await(3, TimeUnit.SECONDS)); terminal.destroy()
            assertTrue(entered.await(3, TimeUnit.SECONDS)); assertTrue(owned.waitFor(1, TimeUnit.SECONDS))
            val failure = withTimeout(9_000) { runCatching { terminal.destroyAndJoinForExit() }.exceptionOrNull() }
            assertNotNull("A timed-out callback is not a receipt", failure); assertEquals(1, calls.get())
            release.countDown(); assertTrue(returned.await(3, TimeUnit.SECONDS))
            withTimeout(4_000) { while (runCatching { terminal.destroyAndJoinForExit() }.isFailure) delay(5) }
            terminal.destroyAndJoinForExit(); assertEquals(1, calls.get())
        } finally {
            release.countDown(); terminal.destroy(); scope.coroutineContext[Job]!!.cancelAndJoin()
            owned.destroyForcibly(); owned.waitFor()
        }
    }

    private class BlockingClose(val delegate: Process) : Process() {
        val closeEntered = CountDownLatch(1); val release = CountDownLatch(1)
        val ioReady = CountDownLatch(1)
        val closes = Array(3) { AtomicInteger() }
        private val out = object : java.io.FilterOutputStream(delegate.outputStream) {
            override fun close() { closes[0].incrementAndGet(); closeEntered.countDown(); check(release.await(10, TimeUnit.SECONDS)); out.close() }
        }
        private val input = object : java.io.FilterInputStream(delegate.inputStream) {
            override fun close() { closes[1].incrementAndGet(); super.close() }
        }
        private val errors = object : java.io.FilterInputStream(delegate.errorStream) {
            override fun close() { closes[2].incrementAndGet(); super.close() }
        }
        override fun getOutputStream() = out
        override fun getInputStream(): java.io.InputStream { ioReady.countDown(); return input }
        override fun getErrorStream() = errors
        override fun waitFor() = delegate.waitFor()
        override fun waitFor(timeout: Long, unit: TimeUnit) = delegate.waitFor(timeout, unit)
        override fun exitValue() = delegate.exitValue()
        override fun isAlive() = delegate.isAlive
        override fun destroy() = delegate.destroy()
        override fun destroyForcibly(): Process { delegate.destroyForcibly(); return this }
    }

    @Test fun blockedPipeCloseKeepsReceiptPendingAndOwnedStreamsAreNotClosedTwice() = runBlocking {
        val wrapped = BlockingClose(child()); val calls = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val terminal = InteractiveTerminalProcess(scope, { wrapped }, { calls.incrementAndGet() }, {})
        try {
            terminal.start(); assertTrue(wrapped.ioReady.await(3, TimeUnit.SECONDS))
            terminal.destroy(); assertTrue(wrapped.closeEntered.await(3, TimeUnit.SECONDS))
            assertTrue(wrapped.delegate.waitFor(1, TimeUnit.SECONDS))
            val failure = withTimeout(4_000) { runCatching { terminal.destroyAndJoinForExit() }.exceptionOrNull() }
            assertNotNull(failure); assertEquals(1, calls.get()); assertEquals(1, wrapped.closes[0].get())
            wrapped.release.countDown()
            withTimeout(4_000) { while (runCatching { terminal.destroyAndJoinForExit() }.isFailure) delay(5) }
            terminal.destroyAndJoinForExit()
            assertTrue(wrapped.closes.all { it.get() == 1 }); assertEquals(1, calls.get())
        } finally {
            wrapped.release.countDown(); terminal.destroy(); scope.coroutineContext[Job]!!.cancelAndJoin()
            wrapped.delegate.destroyForcibly(); wrapped.delegate.waitFor()
        }
    }

    @Test fun fatalLeaseFailureIsRetainedAndNeverEscapesAnUncaughtWorker() = runBlocking {
        val fatal = AssertionError("lease callback failed")
        val calls = AtomicInteger(); val uncaught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t -> uncaught += t })
        val owned = child(); val launched = CountDownLatch(1)
        val terminal = InteractiveTerminalProcess(scope, { launched.countDown(); owned }, { calls.incrementAndGet(); throw fatal }, {})
        try {
            terminal.start(); assertTrue(launched.await(3, TimeUnit.SECONDS)); terminal.destroy()
            repeat(2) {
                val failure = withTimeout(4_000) { runCatching { terminal.destroyAndJoinForExit() }.exceptionOrNull() }
                assertTrue(failure is IllegalStateException)
                assertTrue(generateSequence(failure) { it.cause }.any { it === fatal })
            }
            assertEquals(1, calls.get()); assertTrue(uncaught.isEmpty()); assertFalse(owned.isAlive)
        } finally { terminal.destroy(); scope.coroutineContext[Job]!!.cancelAndJoin(); owned.destroyForcibly(); owned.waitFor() }
    }

    @Test fun cancelledExitObserverDoesNotCancelOrReplayLeaseCleanup() = runBlocking {
        val owned = BlockingDestroy(child()).apply { release.countDown() }; val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val calls = AtomicInteger(); val launched = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val terminal = InteractiveTerminalProcess(scope, { launched.countDown(); owned }, {
            calls.incrementAndGet(); entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
        }, {})
        var exiting: Deferred<Unit>? = null
        try {
            terminal.start(); assertTrue(launched.await(3, TimeUnit.SECONDS)); assertTrue(owned.ioReady.await(3, TimeUnit.SECONDS))
            exiting = async(Dispatchers.IO) { terminal.destroyAndJoinForExit() }
            assertTrue(entered.await(3, TimeUnit.SECONDS)); assertTrue(owned.waitFor(1, TimeUnit.SECONDS))
            withTimeout(1_000) { exiting.cancelAndJoin() }
            assertEquals(1, calls.get()); release.countDown()
            withTimeout(4_000) { terminal.destroyAndJoinForExit() }
            assertEquals(1, calls.get())
        } finally {
            release.countDown(); exiting?.cancelAndJoin(); terminal.destroy(); scope.coroutineContext[Job]!!.cancelAndJoin()
            owned.destroyForcibly(); owned.waitFor()
        }
    }

}
