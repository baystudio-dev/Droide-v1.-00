package com.baystudio.droide.core

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HumanTerminalCpuProtectionTest {
    private class Request(
        val acquired: () -> Unit,
        val lost: (String) -> Unit,
        val stop: () -> Unit,
    ) {
        val releases = AtomicInteger()
        fun release() { releases.incrementAndGet() }
    }

    private class Fixture {
        val requests = CopyOnWriteArrayList<Request>()
        val stops = CopyOnWriteArrayList<Pair<Long, Long>>()
        val warnings = CopyOnWriteArrayList<String>()
        var acquireAction: (Request) -> (() -> Unit)? = { request -> request::release }
        var stopAction: () -> Unit = {}
        var warningAction: () -> Unit = {}
        val protection = HumanTerminalCpuProtection(
            acquire = { acquired, lost, stop ->
                val request = Request(acquired, lost, stop)
                requests += request
                acquireAction(request)
            },
            stop = { epoch, generation -> stops += epoch to generation; stopAction() },
            warn = { warnings += it; warningAction() },
        )
    }

    private fun activity(
        phase: TerminalCommandPhase = TerminalCommandPhase.RUNNING,
        generation: Long = 1,
        lastOutputAtMs: Long? = null,
    ) = TerminalCommandActivity(phase, generation = generation,
        startedAtMs = Long.MIN_VALUE, lastOutputAtMs = lastOutputAtMs)

    private fun assertPhase(fixture: Fixture, phase: HumanTerminalCpuProtection.Phase) =
        assertEquals(phase, fixture.protection.status.value.phase)

    @Test fun idlePhasesDoNotAcquireAndZeroEpochCanOwnTheFirstAttempt() {
        val fixture = Fixture()
        assertPhase(fixture, HumanTerminalCpuProtection.Phase.IDLE)
        assertNull(fixture.protection.status.value.shellEpoch)
        for (phase in listOf(TerminalCommandPhase.STARTING, TerminalCommandPhase.READY,
            TerminalCommandPhase.CLOSED, TerminalCommandPhase.ERROR)) {
            fixture.protection.observe(activity(phase, generation = 0), 0)
        }
        assertTrue(fixture.requests.isEmpty())
        fixture.protection.observe(activity(TerminalCommandPhase.AWAITING_RESPONSE, generation = 0), 0)
        assertEquals(1, fixture.requests.size)
        assertPhase(fixture, HumanTerminalCpuProtection.Phase.PENDING)
        assertEquals(0L, fixture.protection.status.value.shellEpoch)
        assertEquals(0L, fixture.protection.status.value.generation)
    }

    @Test fun pendingAndAcquiredOutputUpdatesNeverRepeatAcquisition() {
        val fixture = Fixture()
        fixture.protection.observe(activity(TerminalCommandPhase.AWAITING_RESPONSE), 8)
        repeat(100) { fixture.protection.observe(activity(lastOutputAtMs = it.toLong()), 8) }
        assertEquals(1, fixture.requests.size)
        assertPhase(fixture, HumanTerminalCpuProtection.Phase.PENDING)
        fixture.requests.single().acquired()
        repeat(100) { fixture.protection.observe(activity(lastOutputAtMs = Long.MAX_VALUE - it), 8) }
        assertPhase(fixture, HumanTerminalCpuProtection.Phase.ACQUIRED)
        assertEquals(1, fixture.requests.size)
        assertEquals(0, fixture.requests.single().releases.get())
    }

    @Test fun failedAttemptAndOneHundredUpdatesWarnOnceWithoutRetry() {
        val fixture = Fixture().apply { acquireAction = { null } }
        fixture.protection.observe(activity(generation = 0), Long.MIN_VALUE)
        repeat(100) { fixture.protection.observe(activity(generation = 0, lastOutputAtMs = it.toLong()), Long.MIN_VALUE) }
        assertEquals(1, fixture.requests.size)
        assertEquals(listOf(HumanTerminalCpuProtection.UNAVAILABLE_WARNING), fixture.warnings)
        assertPhase(fixture, HumanTerminalCpuProtection.Phase.UNAVAILABLE)
        assertTrue(fixture.stops.isEmpty())
        fixture.protection.observe(activity(TerminalCommandPhase.READY, generation = 0), Long.MIN_VALUE)
        fixture.protection.observe(activity(generation = 0), Long.MIN_VALUE)
        assertEquals(1, fixture.requests.size)
        fixture.protection.observe(activity(generation = 1), Long.MIN_VALUE)
        assertEquals(2, fixture.requests.size)
    }

    @Test fun throwingAcquisitionIsUnavailableAndDoesNotExposeExceptionText() {
        val privateText = "private command or credential " + "x".repeat(1_000)
        val fixture = Fixture().apply { acquireAction = { throw IllegalStateException(privateText) } }
        fixture.protection.observe(activity(), 1)
        repeat(100) { fixture.protection.observe(activity(lastOutputAtMs = it.toLong()), 1) }
        assertEquals(1, fixture.requests.size)
        assertPhase(fixture, HumanTerminalCpuProtection.Phase.UNAVAILABLE)
        assertEquals(1, fixture.warnings.size)
        assertTrue(fixture.warnings.single().length <= 160)
        assertFalse(fixture.warnings.single().contains("private"))
        assertTrue(fixture.stops.isEmpty())
    }

    @Test fun synchronousAcquisitionThenDuplicateFinishReleasesExactlyOnce() {
        val fixture = Fixture().apply { acquireAction = { request -> request.acquired(); request::release } }
        fixture.protection.observe(activity(), 3)
        assertPhase(fixture, HumanTerminalCpuProtection.Phase.ACQUIRED)
        repeat(3) { fixture.protection.observe(activity(TerminalCommandPhase.READY), 3) }
        fixture.protection.observe(activity(TerminalCommandPhase.ERROR), 3)
        fixture.protection.observe(activity(TerminalCommandPhase.CLOSED), 3)
        fixture.protection.close()
        fixture.protection.close()
        assertEquals(1, fixture.requests.single().releases.get())
        assertPhase(fixture, HumanTerminalCpuProtection.Phase.IDLE)
        assertTrue(fixture.warnings.isEmpty())
    }

    @Test fun synchronousLossBeforeHandleReturnsReleasesItOnceAndCannotBeReacquired() {
        val fixture = Fixture().apply {
            acquireAction = { request ->
                request.acquired()
                request.lost("unbounded private provider detail " + "x".repeat(10_000))
                request.acquired()
                request.stop()
                request.lost("duplicate")
                request::release
            }
        }
        fixture.protection.observe(activity(), 4)
        assertPhase(fixture, HumanTerminalCpuProtection.Phase.UNAVAILABLE)
        assertEquals(1, fixture.requests.single().releases.get())
        assertEquals(listOf(HumanTerminalCpuProtection.UNAVAILABLE_WARNING), fixture.warnings)
        assertTrue(fixture.stops.isEmpty())
        fixture.protection.close()
        assertEquals(1, fixture.requests.single().releases.get())
    }

    @Test fun synchronousAcquiredFollowedByNullHandleIsUnavailable() {
        val fixture = Fixture().apply { acquireAction = { it.acquired(); null } }
        fixture.protection.observe(activity(), 1)
        assertPhase(fixture, HumanTerminalCpuProtection.Phase.UNAVAILABLE)
        assertEquals(1, fixture.warnings.size)
        assertTrue(fixture.stops.isEmpty())
    }

    @Test fun cancelledAcquirePropagatesRetiresPendingAttemptAndNeverWarnsOrRetries() {
        val cancellation = CancellationException("cancelled")
        val fixture = Fixture().apply { acquireAction = { it.acquired(); throw cancellation } }
        try {
            fixture.protection.observe(activity(), 1)
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) { assertSame(cancellation, actual) }
        assertPhase(fixture, HumanTerminalCpuProtection.Phase.IDLE)
        fixture.requests.single().apply { acquired(); lost("late"); stop() }
        repeat(100) { fixture.protection.observe(activity(lastOutputAtMs = it.toLong()), 1) }
        assertEquals(1, fixture.requests.size)
        assertTrue(fixture.warnings.isEmpty())
        assertTrue(fixture.stops.isEmpty())
        assertPhase(fixture, HumanTerminalCpuProtection.Phase.IDLE)
    }

    @Test fun cancellationFromOldAcquireDoesNotRetireReentrantReplacement() {
        val fixture = Fixture()
        fixture.acquireAction = { request ->
            if (fixture.requests.size == 1) {
                fixture.protection.observe(activity(generation = 2), 0)
                throw CancellationException("old owner")
            }
            request.acquired()
            request::release
        }
        try {
            fixture.protection.observe(activity(), 0)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(2, fixture.requests.size)
        assertPhase(fixture, HumanTerminalCpuProtection.Phase.ACQUIRED)
        assertEquals(2L, fixture.protection.status.value.generation)
        assertEquals(0, fixture.requests[1].releases.get())
        assertTrue(fixture.warnings.isEmpty())
        assertTrue(fixture.stops.isEmpty())
    }

    @Test fun synchronousStatusCollectorCanFinishBeforeAcquireReturns() = runBlocking {
        val fixture = Fixture().apply { acquireAction = { it.acquired(); it::release } }
        val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            fixture.protection.status.collect {
                if (it.phase == HumanTerminalCpuProtection.Phase.ACQUIRED) {
                    fixture.protection.observe(activity(TerminalCommandPhase.READY), 4)
                }
            }
        }
        try {
            fixture.protection.observe(activity(), 4)
            assertPhase(fixture, HumanTerminalCpuProtection.Phase.IDLE)
            assertEquals(1, fixture.requests.single().releases.get())
        } finally { collector.cancelAndJoin() }
    }

    @Test fun synchronousWarningMayReplaceTheOwnerBeforeTheOldHandleReturns() {
        val fixture = Fixture()
        fixture.acquireAction = { request ->
            if (fixture.requests.size == 1) request.lost("lost") else request.acquired()
            request::release
        }
        fixture.warningAction = { fixture.protection.observe(activity(generation = 2), 9) }
        fixture.protection.observe(activity(), 9)
        assertEquals(2, fixture.requests.size)
        assertEquals(1, fixture.requests[0].releases.get())
        assertEquals(0, fixture.requests[1].releases.get())
        assertPhase(fixture, HumanTerminalCpuProtection.Phase.ACQUIRED)
        assertEquals(2L, fixture.protection.status.value.generation)
    }

    @Test fun lateCallbacksAfterFinishCannotWarnStopOrChangeTheNextCommand() {
        val fixture = Fixture()
        fixture.protection.observe(activity(), 5)
        val old = fixture.requests.single()
        fixture.protection.observe(activity(TerminalCommandPhase.READY), 5)
        old.acquired(); old.lost("late"); old.stop()
        assertPhase(fixture, HumanTerminalCpuProtection.Phase.IDLE)
        fixture.protection.observe(activity(generation = 2), 5)
        old.acquired(); old.lost("later"); old.stop()
        assertPhase(fixture, HumanTerminalCpuProtection.Phase.PENDING)
        assertEquals(2L, fixture.protection.status.value.generation)
        assertTrue(fixture.warnings.isEmpty())
        assertTrue(fixture.stops.isEmpty())
        assertEquals(1, old.releases.get())
    }

    @Test fun newEpochMayReuseGenerationAndOlderObservationsCannotReplaceIt() {
        val fixture = Fixture()
        fixture.protection.observe(activity(generation = 20), 0)
        val old = fixture.requests.single()
        old.acquired()
        fixture.protection.observe(activity(generation = 0), 1)
        assertEquals(2, fixture.requests.size)
        assertEquals(1, old.releases.get())
        old.acquired(); old.lost("old epoch"); old.stop()
        fixture.protection.observe(activity(generation = 21), 0)
        fixture.protection.observe(activity(TerminalCommandPhase.CLOSED, generation = 21), 0)
        assertEquals(2, fixture.requests.size)
        assertPhase(fixture, HumanTerminalCpuProtection.Phase.PENDING)
        assertEquals(1L, fixture.protection.status.value.shellEpoch)
        assertEquals(0L, fixture.protection.status.value.generation)
        assertTrue(fixture.warnings.isEmpty())
        assertTrue(fixture.stops.isEmpty())
    }

    @Test fun newerGenerationRetiresOwnerAndOlderGenerationCannotRevive() {
        val fixture = Fixture()
        fixture.protection.observe(activity(), 0)
        fixture.protection.observe(activity(generation = 2), 0)
        fixture.protection.observe(activity(), 0)
        fixture.protection.observe(activity(TerminalCommandPhase.ERROR), 0)
        assertEquals(2, fixture.requests.size)
        assertEquals(1, fixture.requests[0].releases.get())
        assertEquals(0, fixture.requests[1].releases.get())
        assertEquals(2L, fixture.protection.status.value.generation)
    }

    @Test fun everyInactivePhaseRetiresWithoutWarningOrSameOwnerRetry() {
        for (phase in listOf(TerminalCommandPhase.STARTING, TerminalCommandPhase.READY,
            TerminalCommandPhase.ERROR, TerminalCommandPhase.CLOSED)) {
            val fixture = Fixture()
            fixture.protection.observe(activity(), 0)
            fixture.protection.observe(activity(phase), 0)
            fixture.protection.observe(activity(), 0)
            assertEquals(1, fixture.requests.size)
            assertEquals(1, fixture.requests.single().releases.get())
            assertPhase(fixture, HumanTerminalCpuProtection.Phase.IDLE)
            assertTrue(fixture.warnings.isEmpty())
            assertTrue(fixture.stops.isEmpty())
        }
    }

    @Test(timeout = 5_000) fun closeWhileAcquireIsBlockedSealsStateAndReleasesLateHandleOnce() {
        val fixture = Fixture()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        fixture.acquireAction = { request -> entered.countDown(); finish.await(); request::release }
        val worker = thread { try { fixture.protection.observe(activity(), 6) } catch (t: Throwable) { failure.set(t) } }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            fixture.protection.close()
            fixture.protection.close()
            assertPhase(fixture, HumanTerminalCpuProtection.Phase.IDLE)
            assertNull(fixture.protection.status.value.shellEpoch)
            fixture.requests.single().apply { acquired(); lost("closed"); stop() }
            fixture.protection.observe(activity(generation = 2), 7)
        } finally { finish.countDown(); worker.join(1_000) }
        assertFalse(worker.isAlive)
        assertNull(failure.get())
        assertEquals(1, fixture.requests.size)
        assertEquals(1, fixture.requests.single().releases.get())
        assertTrue(fixture.warnings.isEmpty())
        assertTrue(fixture.stops.isEmpty())
        assertPhase(fixture, HumanTerminalCpuProtection.Phase.IDLE)
    }

    @Test(timeout = 5_000) fun replacementWhileAcquireIsBlockedKeepsOnlyTheNewHandle() {
        val fixture = Fixture()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        fixture.acquireAction = { request ->
            if (fixture.requests.size == 1) { entered.countDown(); finish.await() }
            request.acquired()
            request::release
        }
        val worker = thread { try { fixture.protection.observe(activity(), 6) } catch (t: Throwable) { failure.set(t) } }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            fixture.protection.observe(activity(generation = 2), 6)
            fixture.requests[0].apply { acquired(); lost("old"); stop() }
            assertPhase(fixture, HumanTerminalCpuProtection.Phase.ACQUIRED)
        } finally { finish.countDown(); worker.join(1_000) }
        assertFalse(worker.isAlive)
        assertNull(failure.get())
        assertEquals(1, fixture.requests[0].releases.get())
        assertEquals(0, fixture.requests[1].releases.get())
        assertEquals(2L, fixture.protection.status.value.generation)
        fixture.protection.close()
        assertEquals(1, fixture.requests[1].releases.get())
    }

    @Test fun explicitStopUsesExactOwnerOnceAndNeverWarns() {
        val fixture = Fixture()
        fixture.protection.observe(activity(generation = 17), 23)
        val request = fixture.requests.single()
        request.stop(); request.stop(); request.acquired(); request.lost("after stop")
        repeat(100) { fixture.protection.observe(activity(generation = 17, lastOutputAtMs = it.toLong()), 23) }
        assertEquals(listOf(23L to 17L), fixture.stops)
        assertTrue(fixture.warnings.isEmpty())
        assertEquals(1, fixture.requests.size)
        assertEquals(1, request.releases.get())
        assertPhase(fixture, HumanTerminalCpuProtection.Phase.IDLE)
    }

    @Test fun synchronousStopBeforeHandleReturnsStillReleasesExactlyOnce() {
        val fixture = Fixture().apply { acquireAction = { request -> request.stop(); request::release } }
        fixture.protection.observe(activity(), 0)
        assertEquals(listOf(0L to 1L), fixture.stops)
        assertEquals(1, fixture.requests.single().releases.get())
        assertTrue(fixture.warnings.isEmpty())
        assertPhase(fixture, HumanTerminalCpuProtection.Phase.IDLE)
    }

    @Test fun protectionLossOnlyWarnsEvenWhenProviderReasonMentionsStop() {
        val fixture = Fixture()
        fixture.protection.observe(activity(), 2)
        val request = fixture.requests.single()
        request.acquired()
        request.lost("stop/Exit/notification failure; private command text")
        request.stop(); request.acquired(); request.lost("duplicate")
        repeat(100) { fixture.protection.observe(activity(lastOutputAtMs = it.toLong()), 2) }
        assertTrue(fixture.stops.isEmpty())
        assertEquals(1, fixture.warnings.size)
        assertEquals(1, fixture.requests.size)
        assertEquals(1, request.releases.get())
        assertPhase(fixture, HumanTerminalCpuProtection.Phase.UNAVAILABLE)
    }

    @Test fun silentLongRunningCommandHasNoExpiryAndDoesNotNeedOutput() {
        val fixture = Fixture().apply { acquireAction = { it.acquired(); it::release } }
        fixture.protection.observe(activity(), 0)
        repeat(100) { fixture.protection.observe(activity(), 0) }
        fixture.protection.observe(activity(lastOutputAtMs = Long.MAX_VALUE), 0)
        fixture.protection.observe(activity(), 0)
        assertEquals(1, fixture.requests.size)
        assertPhase(fixture, HumanTerminalCpuProtection.Phase.ACQUIRED)
        assertEquals(0, fixture.requests.single().releases.get())
        assertTrue(fixture.warnings.isEmpty())
        assertTrue(fixture.stops.isEmpty())
    }

    @Test fun stopMaySynchronouslyStartTheNextCommandWithoutReleasingItsHandle() {
        val fixture = Fixture()
        fixture.stopAction = { fixture.protection.observe(activity(generation = 2), 3) }
        fixture.protection.observe(activity(), 3)
        fixture.requests.single().stop()
        assertEquals(listOf(3L to 1L), fixture.stops)
        assertEquals(2, fixture.requests.size)
        assertEquals(1, fixture.requests[0].releases.get())
        assertEquals(0, fixture.requests[1].releases.get())
        assertPhase(fixture, HumanTerminalCpuProtection.Phase.PENDING)
        assertEquals(2L, fixture.protection.status.value.generation)
    }

    @Test(timeout = 5_000) fun injectedCallbacksNeverHoldTheOwnerLock() {
        val duringAcquire = Fixture()
        duringAcquire.acquireAction = { request ->
            closeOnAnotherThread(duringAcquire.protection)
            request::release
        }
        duringAcquire.protection.observe(activity(), 0)
        assertEquals(1, duringAcquire.requests.single().releases.get())

        val duringRelease = Fixture()
        duringRelease.acquireAction = { request -> {
            request.release()
            closeOnAnotherThread(duringRelease.protection)
        } }
        duringRelease.protection.observe(activity(), 0)
        duringRelease.protection.observe(activity(TerminalCommandPhase.READY), 0)

        val duringWarning = Fixture()
        duringWarning.warningAction = { closeOnAnotherThread(duringWarning.protection) }
        duringWarning.protection.observe(activity(), 0)
        duringWarning.requests.single().lost("failed")

        val duringStop = Fixture()
        duringStop.stopAction = { closeOnAnotherThread(duringStop.protection) }
        duringStop.protection.observe(activity(), 0)
        duringStop.requests.single().stop()
        assertEquals(1, duringStop.requests.single().releases.get())
    }

    @Test(timeout = 5_000) fun stateFlowPublicationDoesNotHoldOwnerLockOrOverwriteReentrantClose() = runBlocking {
        val fixture = Fixture()
        val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            fixture.protection.status.collect {
                if (it.phase == HumanTerminalCpuProtection.Phase.PENDING) {
                    closeOnAnotherThread(fixture.protection)
                }
            }
        }
        try {
            fixture.protection.observe(activity(), 0)
            assertPhase(fixture, HumanTerminalCpuProtection.Phase.IDLE)
            assertTrue(fixture.requests.isEmpty())
        } finally { collector.cancelAndJoin() }
    }

    @Test fun throwingReleaseCannotPreventIdleOrPermitDuplicateRelease() {
        val fixture = Fixture().apply {
            acquireAction = { request -> { request.release(); throw IllegalStateException("release") } }
        }
        fixture.protection.observe(activity(), 0)
        fixture.protection.close()
        fixture.protection.close()
        fixture.requests.single().apply { acquired(); lost("late"); stop() }
        assertEquals(1, fixture.requests.single().releases.get())
        assertPhase(fixture, HumanTerminalCpuProtection.Phase.IDLE)
    }

    @Test(timeout = 10_000) fun concurrentLossStopAndCloseNeverDoubleReleaseOrRevive() {
        repeat(100) {
            val fixture = Fixture()
            fixture.protection.observe(activity(), 0)
            val request = fixture.requests.single()
            val start = CyclicBarrier(4)
            val failure = AtomicReference<Throwable?>()
            val actions = listOf<() -> Unit>(
                { request.acquired(); request.lost("race") },
                { request.stop() },
                { fixture.protection.close() },
            )
            val workers = actions.map { action -> thread {
                try { start.await(); action() } catch (t: Throwable) { failure.compareAndSet(null, t) }
            } }
            start.await()
            workers.forEach { it.join(1_000); assertFalse(it.isAlive) }
            assertNull(failure.get())
            assertEquals(1, request.releases.get())
            assertTrue(fixture.stops.size + fixture.warnings.size <= 1)
            assertPhase(fixture, HumanTerminalCpuProtection.Phase.IDLE)
        }
    }

    private fun closeOnAnotherThread(protection: HumanTerminalCpuProtection) {
        val finished = CountDownLatch(1)
        val worker = thread { try { protection.close() } finally { finished.countDown() } }
        assertTrue("Callback ran while holding the owner lock", finished.await(1, TimeUnit.SECONDS))
        worker.join(1_000)
        assertFalse(worker.isAlive)
    }
}
