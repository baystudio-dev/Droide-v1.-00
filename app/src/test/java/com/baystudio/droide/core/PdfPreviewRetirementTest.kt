package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class PdfPreviewRetirementTest {
    @Test fun pendingNativeWorkBlocksBothNewWorkAndExitUntilActualDeath() {
        val state = PdfPreviewRetirement(); val token = Any()
        state.requireStopped(); state.started(token)
        repeat(3) { assertTrue(runCatching { state.requireStopped() }.isFailure) }
        assertTrue(runCatching { state.started(Any()) }.isFailure)
        state.died(token); state.requireStopped()
    }
    @Test fun staleDeathCannotRetireNewWorker() {
        val state = PdfPreviewRetirement(); val old = Any(); val current = Any()
        state.started(old); state.died(old); state.started(current)
        state.died(old)
        assertTrue(runCatching { state.requireStopped() }.isFailure)
        state.died(current); state.requireStopped()
    }
    @Test fun deathBeforeTimeoutObservationCannotBeOverwrittenByTimeout() {
        val state = PdfPreviewRetirement(); val token = Any()
        state.started(token); state.died(token)
        // A timeout performs no state mutation. Repeated final checks remain confirmed.
        repeat(10) { state.requireStopped(); state.died(token) }
    }
}
