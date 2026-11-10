package com.baystudio.droide.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class ExitBuildCancelStarvationTest {
    @Test(timeout = 10_000) fun heldBuildCancellationMustNotBlockOtherExitOwnersOrMainObservation() = runBlocking {
        listOf(BuildRunDebugOperationGate::class.java, ExitOwnerBarrier::class.java,
            ApplicationExitSequence::class.java).forEach {
            println("ORIGIN ${it.name} ${it.protectionDomain.codeSource.location}")
        }
        val uiThread = AtomicReference<Thread>()
        val executor = Executors.newSingleThreadExecutor { action ->
            Thread(action, "f154-main-immediate-surrogate").apply { isDaemon = true; uiThread.set(this) }
        }
        val immediate = object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext) = Thread.currentThread() !== uiThread.get()
            override fun dispatch(context: CoroutineContext, block: Runnable) { executor.execute(block) }
        }
        val exitScope = CoroutineScope(SupervisorJob() + immediate)
        val workScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = BuildRunDebugOperationGate<String>()
        val operationStarted = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val siblingStopped = CountDownLatch(1)
        val historyReached = CountDownLatch(1)
        val mainResponsive = CountDownLatch(1)
        val cancellations = AtomicInteger()
        val drafts = AtomicInteger()
        val child = ProcessBuilder("/bin/sh", "-c", "exec sleep 30").start()
        val operation = workScope.launch(start = CoroutineStart.UNDISPATCHED) {
            gate.run("DEBUG") {
                suspendCancellableCoroutine<Unit> { continuation ->
                    continuation.invokeOnCancellation {
                        cancellations.incrementAndGet()
                        closeEntered.countDown()
                        check(releaseClose.await(6, TimeUnit.SECONDS)) { "Test cleanup release was not observed" }
                        child.destroyForcibly()
                        check(child.waitFor(2, TimeUnit.SECONDS))
                    }
                    operationStarted.countDown()
                }
            }
        }
        check(operationStarted.await(1, TimeUnit.SECONDS))
        val barrier = ExitOwnerBarrier(exitScope, listOf(
            ApplicationExitSequence.Step("Build and tasks") { gate.closeAndJoin() },
            ApplicationExitSequence.Step("Independent owner") { siblingStopped.countDown() },
        ))
        val sequence = ApplicationExitSequence(exitScope, listOf(
            ApplicationExitSequence.Step("Preserving drafts") { drafts.incrementAndGet() },
            ApplicationExitSequence.Step("Stopping Agent and saving final history") {
                barrier.requestStop()
                historyReached.countDown()
            },
            ApplicationExitSequence.Step("Stopping owned development work") { barrier.stopAndAwait() },
        ), observationMs = 100)
        val exiting = sequence.request()
        try {
            assertTrue("Cancellation boundary must be reached", closeEntered.await(2, TimeUnit.SECONDS))
            exitScope.launch { mainResponsive.countDown() }
            val siblingProgress = siblingStopped.await(500, TimeUnit.MILLISECONDS)
            val mainProgress = mainResponsive.await(500, TimeUnit.MILLISECONDS)
            println("HELD_CLOSE drafts=${drafts.get()} cancellations=${cancellations.get()} siblingStopped=$siblingProgress historyReached=${historyReached.count == 0L} mainResponsive=$mainProgress exitRunning=${sequence.state.value.running} exitCompleted=${sequence.state.value.completed} childAlive=${child.isAlive}")
            assertEquals("Drafts must be preserved before requesting cancellation", 1, drafts.get())
            assertFalse("Held cleanup cannot certify Exit completion", sequence.state.value.completed)
            assertTrue("A synchronous build transport close must not starve sibling owner stops", siblingProgress)
            assertTrue("Main must remain responsive while cancellation is blocked", mainProgress)
            assertEquals("History checkpoint stage must be allowed to proceed", 0L, historyReached.count)
            withTimeout(1_500) { sequence.state.first { it.stage.contains("still finishing") } }
            assertTrue("Cleanup must continue automatically beyond the UI observation budget", sequence.state.value.running)
            assertNull("Slow cleanup is not a failed operation", sequence.state.value.failure)
            assertSame(exiting, sequence.request())
            assertFalse(sequence.state.value.completed)
            assertTrue("Observation timeout cannot claim child cleanup", child.isAlive)
            println("OBSERVED_HELD failure=${sequence.state.value.failure} exitCompleted=${sequence.state.value.completed} childAlive=${child.isAlive}")
        } finally {
            releaseClose.countDown()
            withTimeout(3_000) { exiting.join(); operation.join() }
            child.destroyForcibly(); child.waitFor(2, TimeUnit.SECONDS)
            child.outputStream.close(); child.inputStream.close(); child.errorStream.close()
            workScope.cancel(); exitScope.cancel(); executor.shutdownNow()
            assertTrue("Cleanup completion must finish Exit without retry", sequence.state.value.completed)
            assertEquals("Continuation must not preserve drafts twice", 1, drafts.get())
            assertEquals("Continuation must join the exact cancellation", 1, cancellations.get())
            println("RELEASED_CLOSE cancellations=${cancellations.get()} siblingStopped=${siblingStopped.count == 0L} historyReached=${historyReached.count == 0L} exitCompleted=${sequence.state.value.completed} childAlive=${child.isAlive}")
        }
    }
}
