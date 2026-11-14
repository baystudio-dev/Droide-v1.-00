package com.baystudio.droide.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SessionHostedProcessShutdownTest {
    private class Child(holdClose: Boolean = false, holdReceipt: Boolean = false, val failBeforeStop: Boolean = false) : HostedStdioProcess {
        private val process = ProcessBuilder("/bin/sh", "-c", "exec sleep 60").start()
        val closeCalls = AtomicInteger()
        val closeEntered = CompletableDeferred<Unit>()
        val awaitEntered = CompletableDeferred<Unit>()
        val closeGate = CountDownLatch(if (holdClose) 1 else 0)
        val receipt = CompletableDeferred<Unit>().also { if (!holdReceipt) it.complete(Unit) }
        val closeFailure = AtomicReference<Throwable?>()
        val receiptFailure = AtomicReference<Throwable?>()
        override val stdin get() = process.outputStream
        override val stdout get() = process.inputStream
        override val stderr get() = process.errorStream
        override val isAlive get() = process.isAlive
        override val exitCode get() = if (process.isAlive) null else process.exitValue()
        override fun close() {
            closeCalls.incrementAndGet()
            closeEntered.complete(Unit)
            check(closeGate.await(8, TimeUnit.SECONDS)) { "Fixture close gate was not released" }
            if (failBeforeStop) closeFailure.get()?.let { throw it }
            process.destroy()
            closeFailure.get()?.let { throw it }
        }
        override suspend fun awaitExit(): Int {
            awaitEntered.complete(Unit)
            // Like production LocalHostedProcess, awaitExit exposes an asynchronous
            // receipt. Waiting for a live fixture child must not occupy the sole IO
            // lane used to test independent stop dispatch and receipt publication.
            while (process.isAlive) delay(5)
            val code = process.exitValue()
            receipt.await()
            receiptFailure.get()?.let { throw it }
            return code
        }
        fun release() { closeGate.countDown(); receipt.complete(Unit) }
        fun disposeFixture() {
            release()
            process.destroyForcibly()
            check(process.waitFor(5, TimeUnit.SECONDS))
            stdin.close(); stdout.close(); stderr.close()
        }
    }

    private fun host(owner: SessionHostedProcessOwner, action: suspend () -> HostedStdioProcess): StdioProcessHost =
        owner.bind(object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command: String) = command
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?) = action()
        })

    @Test(timeout = 10_000)
    fun lateBirthStopsWhileItsCancelledLaunchFinalizerIsStillHeld() = runBlocking {
        val owner = SessionHostedProcessOwner()
        val child = Child()
        val entered = CompletableDeferred<Unit>()
        val finalizing = CompletableDeferred<Unit>()
        val permitBirth = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var stopping: Deferred<Unit>? = null
        val starting = scope.launch {
            host(owner) {
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) {
                        finalizing.complete(Unit)
                        permitBirth.await()
                        currentCoroutineContext()[SessionHostedProcessOwner]!!.retain(child)
                        release.await()
                    }
                }
            }.start(listOf("fixture"))
        }
        try {
            entered.await()
            stopping = scope.async { owner.stopAndAwait() }
            finalizing.await()
            permitBirth.complete(Unit)
            withTimeout(2_000) { child.awaitEntered.await() }
            withTimeout(2_000) { child.awaitExit() }
            assertFalse(child.isAlive)
            assertFalse("Launch custody must still delay completion", stopping.isCompleted)
            assertEquals(1, child.closeCalls.get())
            release.complete(Unit)
            withTimeout(2_000) { stopping.await() }
            assertTrue(starting.isCancelled)
            assertTrue(runCatching { host(owner) { child }.start(listOf("refused")) }.isFailure)
        } finally {
            permitBirth.complete(Unit); release.complete(Unit); child.release()
            withTimeout(3_000) { starting.join(); stopping?.await() }
            scope.cancel(); child.disposeFixture()
        }
    }

    @Test(timeout = 10_000)
    fun blockingCloseCannotStarveSiblingOrLateBirth() = runBlocking {
        val owner = SessionHostedProcessOwner()
        val blocked = Child(holdClose = true)
        val sibling = Child()
        val late = Child()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var stopping: Deferred<Unit>? = null
        try {
            owner.retain(blocked); owner.retain(sibling)
            stopping = scope.async { owner.stopAndAwait() }
            withTimeout(2_000) { blocked.closeEntered.await(); sibling.awaitEntered.await() }
            owner.retain(late)
            withTimeout(2_000) { late.awaitEntered.await(); sibling.awaitExit(); late.awaitExit() }
            assertFalse(sibling.isAlive); assertFalse(late.isAlive)
            assertTrue(blocked.isAlive)
            assertFalse(stopping.isCompleted)
            blocked.release()
            withTimeout(2_000) { stopping.await() }
            listOf(blocked, sibling, late).forEach { assertEquals(1, it.closeCalls.get()) }
        } finally {
            blocked.release(); sibling.release(); late.release()
            withTimeout(3_000) { stopping?.await() }
            scope.cancel()
            listOf(blocked, sibling, late).forEach(Child::disposeFixture)
        }
    }

    @Test(timeout = 10_000)
    fun cancelledAndTimedOutObserversReuseBlockedAttempt() = runBlocking {
        val owner = SessionHostedProcessOwner()
        val child = Child(holdClose = true, holdReceipt = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var observer: Deferred<Unit>? = null
        var retry: Deferred<Unit>? = null
        try {
            owner.retain(child)
            observer = scope.async { owner.stopAndAwait() }
            child.closeEntered.await()
            observer.cancelAndJoin()
            assertNull(withTimeoutOrNull(100) { owner.stopAndAwait(); true })
            assertEquals(1, child.closeCalls.get())
            child.closeGate.countDown()
            child.awaitEntered.await()
            retry = scope.async { owner.stopAndAwait() }
            assertNull(withTimeoutOrNull(100) { retry.await(); true })
            assertFalse(retry.isCompleted)
            assertEquals(1, child.closeCalls.get())
            child.receipt.complete(Unit)
            withTimeout(2_000) { retry.await() }
            owner.retain(child); owner.closeAdmission(); owner.stopAndAwait()
            assertEquals("Duplicate successful retain must not replay close", 1, child.closeCalls.get())
        } finally {
            child.release()
            withTimeout(3_000) { runCatching { observer?.await() }; retry?.await() }
            scope.cancel(); child.disposeFixture()
        }
    }

    @Test(timeout = 10_000)
    fun explicitRetryReplacesOnlyCompletedFailedAttempt() = runBlocking {
        val owner = SessionHostedProcessOwner()
        val failed = Child()
        val success = Child()
        val failure = IllegalStateException("fixture receipt failed")
        failed.receiptFailure.set(failure)
        try {
            owner.retain(failed); owner.retain(success)
            assertSame(failure, runCatching { owner.stopAndAwait() }.exceptionOrNull())
            assertEquals(1, failed.closeCalls.get()); assertEquals(1, success.closeCalls.get())
            failed.receiptFailure.set(null)
            owner.closeAdmission(); owner.retain(failed)
            assertEquals("Admission close is not an implicit retry", 1, failed.closeCalls.get())
            owner.stopAndAwait()
            assertEquals(2, failed.closeCalls.get()); assertEquals(1, success.closeCalls.get())
            assertFalse(failed.isAlive); assertFalse(success.isAlive)
        } finally { failed.disposeFixture(); success.disposeFixture() }
    }

    @Test(timeout = 10_000)
    fun closeFailureWhileChildRemainsAliveIsRecordedAndExplicitlyRetryable() = runBlocking {
        val owner = SessionHostedProcessOwner()
        val child = Child(failBeforeStop = true)
        val sibling = Child()
        val closeFailure = AssertionError("fixture close failure")
        child.closeFailure.set(closeFailure)
        try {
            owner.retain(child); owner.retain(sibling)
            val failure = withTimeout(2_000) { runCatching { owner.stopAndAwait() }.exceptionOrNull() }
            assertTrue(failure is IllegalStateException)
            assertSame(closeFailure, failure?.cause)
            assertEquals("A throwing close must report failure without waiting for natural exit", 1, child.closeCalls.get())
            assertTrue(child.isAlive)
            assertFalse(sibling.isAlive)
            child.closeFailure.set(null)
            withTimeout(2_000) { owner.stopAndAwait() }
            assertEquals(2, child.closeCalls.get()); assertEquals(1, sibling.closeCalls.get())
            assertFalse(child.isAlive)
        } finally { child.disposeFixture(); sibling.disposeFixture() }
    }

    @Test(timeout = 10_000)
    fun fatalExitReceiptIsRecordedWithoutUncaughtWorkerFailure() = runBlocking {
        val owner = SessionHostedProcessOwner()
        val child = Child()
        val exitFailure = AssertionError("fixture exit failure")
        child.receiptFailure.set(exitFailure)
        try {
            owner.retain(child)
            val failure = withTimeout(2_000) { runCatching { owner.stopAndAwait() }.exceptionOrNull() }
            assertTrue(failure is IllegalStateException)
            assertSame(exitFailure, failure?.cause)
            assertTrue(child.awaitEntered.isCompleted)
            assertFalse(child.isAlive)
            child.receiptFailure.set(null)
            owner.stopAndAwait()
            assertEquals(2, child.closeCalls.get())
        } finally { child.disposeFixture() }
    }

    @Test(timeout = 10_000)
    fun lifetimeStopsKnownChildBeforeCancelledBootstrapFinalizesAndWaitsReceipt() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lifetime = WorkspaceAgentSessionLifetime(parent)
        val child = Child(holdReceipt = true)
        val entered = CompletableDeferred<Unit>()
        val finalizing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val observers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var stopping: Deferred<Unit>? = null
        val starting = lifetime.scope.launch {
            host(lifetime.processes) {
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { finalizing.complete(Unit); release.await() }
                }
            }.start(listOf("bootstrap"))
        }
        try {
            host(lifetime.processes) { child }.start(listOf("known-cli"))
            entered.await()
            stopping = observers.async { lifetime.closeAndAwait() }
            withTimeout(2_000) { finalizing.await(); child.awaitEntered.await() }
            assertFalse(stopping.isCompleted)
            release.complete(Unit)
            starting.join()
            assertNull(withTimeoutOrNull(100) { stopping.await(); true })
            assertFalse("Actual cleanup receipt must remain required", stopping.isCompleted)
            child.receipt.complete(Unit)
            withTimeout(2_000) { stopping.await() }
            lifetime.closeAndAwait()
            assertFalse(child.isAlive)
            assertEquals(1, child.closeCalls.get())
            assertFalse(lifetime.scope.isActive)
        } finally {
            release.complete(Unit); child.release()
            withTimeout(3_000) { starting.join(); stopping?.await() }
            parent.cancel(); observers.cancel(); child.disposeFixture()
        }
    }

    @Test(timeout = 10_000)
    fun concurrentObserversAndRepeatedRetentionShareOneReceipt() = runBlocking {
        val owner = SessionHostedProcessOwner()
        val child = Child(holdReceipt = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val observers = mutableListOf<Deferred<Unit>>()
        try {
            repeat(20) { owner.retain(child) }
            repeat(10) { observers += scope.async { owner.stopAndAwait() } }
            child.closeEntered.await()
            assertEquals(1, child.closeCalls.get())
            assertTrue(observers.none { it.isCompleted })
            repeat(20) { owner.retain(child); owner.closeAdmission() }
            child.receipt.complete(Unit)
            withTimeout(2_000) { observers.awaitAll() }
            assertEquals(1, child.closeCalls.get())
        } finally {
            child.release()
            withTimeout(3_000) { observers.awaitAll() }
            scope.cancel(); child.disposeFixture()
        }
    }
}
