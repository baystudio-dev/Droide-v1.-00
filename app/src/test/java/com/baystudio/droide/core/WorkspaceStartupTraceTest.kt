package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class WorkspaceStartupTraceTest {
    @Test
    fun eachSubphaseRecordsItsOwnMonotonicDurationAndStableAttempt() = runTest {
        var nanos = 10_000_000L
        val measured = mutableListOf<WorkspaceStartupMeasurement>()
        val trace = WorkspaceStartupTrace(4, WorkspaceStartupPhase.ACTIVE_RUNTIME, measured::add) { nanos }
        trace.phase {
            trace.step(WorkspaceStartupStep.EXTENSIONS) { nanos += 42_000_000L }
            trace.step(WorkspaceStartupStep.TERMINALS) { nanos += 7_000_000L }
        }
        assertEquals(listOf(0L, 0L, 42L, 0L, 7L, 49L), measured.map { it.elapsedMs })
        assertEquals(listOf(null, WorkspaceStartupStep.EXTENSIONS, WorkspaceStartupStep.EXTENSIONS,
            WorkspaceStartupStep.TERMINALS, WorkspaceStartupStep.TERMINALS, null), measured.map { it.step })
        assertEquals(setOf(4L), measured.map { it.attempt }.toSet())
        assertEquals(setOf(WorkspaceStartupPhase.ACTIVE_RUNTIME), measured.map { it.phase }.toSet())
        assertEquals(WorkspaceStartupOutcome.SUCCEEDED, measured.last().outcome)
    }

    @Test
    fun failureAndCancellationRemainDistinctAndKeepTheirOriginalCause() = runTest {
        val measured = mutableListOf<WorkspaceStartupMeasurement>()
        val trace = WorkspaceStartupTrace(1, WorkspaceStartupPhase.PROJECT_CATALOG, measured::add) { 0 }
        val failure = IllegalStateException("store failed")
        assertSame(failure, runCatching { trace.step(WorkspaceStartupStep.PROJECT_STORE) { throw failure } }.exceptionOrNull())
        assertEquals(WorkspaceStartupOutcome.FAILED, measured.last().outcome)
        val cancelled = CancellationException("caller left")
        assertSame(cancelled, runCatching { trace.step(WorkspaceStartupStep.PROJECT_STORE) { throw cancelled } }.exceptionOrNull())
        assertEquals(WorkspaceStartupOutcome.CANCELLED, measured.last().outcome)
    }

    @Test
    fun failingLogSinkCannotHideAResourceOrMaskFailureOrCancellation() = runTest {
        var attempts = 0
        val trace = WorkspaceStartupTrace(1, WorkspaceStartupPhase.ACTIVE_RUNTIME, {
            attempts++
            if (attempts % 2 == 0) throw NoClassDefFoundError("logger unavailable")
            throw IllegalStateException("logger unavailable")
        }) { 0 }
        val resource = Any()
        assertSame(resource, trace.step(WorkspaceStartupStep.TERMINALS) { resource })
        val failure = IllegalStateException("real startup failure")
        assertSame(failure, runCatching { trace.step(WorkspaceStartupStep.TERMINALS) { throw failure } }.exceptionOrNull())
        val cancelled = CancellationException("real cancellation")
        assertSame(cancelled, runCatching { trace.step(WorkspaceStartupStep.TERMINALS) { throw cancelled } }.exceptionOrNull())
        currentCoroutineContext().ensureActive()
        assertEquals(6, attempts)
    }

    @Test
    fun nullableStepExecutesOnceAndHasSuccessfulOutcome() = runTest {
        var calls = 0
        val measured = mutableListOf<WorkspaceStartupMeasurement>()
        val trace = WorkspaceStartupTrace(2, WorkspaceStartupPhase.ACTIVE_RUNTIME, measured::add) { 0 }
        assertEquals(null, trace.step<String?>(WorkspaceStartupStep.WORKSPACE_FILES) { calls++; null })
        assertEquals(1, calls)
        assertEquals(WorkspaceStartupOutcome.SUCCEEDED, measured.last().outcome)
    }
}
