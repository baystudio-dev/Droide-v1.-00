package com.baystudio.droide.ui

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DroideSheetDismissalTest {
    @Test fun repeatedCloseWaitsForTheRealExitAndCompletesOnce() = runBlocking {
        val exited = CompletableDeferred<Unit>()
        var hidden = false; var hideCalls = 0; var closes = 0
        val owner = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val dismiss = DroideSheetDismissal(owner, { hideCalls++; exited.await() }, { hidden }, { closes++ })
            dismiss.request(); dismiss.request(); yield()
            assertEquals(1, hideCalls); assertEquals(0, closes)
            hidden = true; exited.complete(Unit); yield()
            assertEquals(1, closes)
            dismiss.dismissed(); dismiss.request(); yield()
            assertEquals(1, closes); assertEquals(1, hideCalls)
        } finally { owner.cancel() }
    }

    @Test fun disposingTheSurfaceCannotCloseItsReplacementLater() = runBlocking {
        var closes = 0
        val owner = CoroutineScope(coroutineContext + SupervisorJob())
        val entered = CompletableDeferred<Unit>()
        val dismiss = DroideSheetDismissal(owner, { entered.complete(Unit); awaitCancellation() }, { true }, { closes++ })
        dismiss.request(); entered.await()
        owner.coroutineContext.job.cancelAndJoin()
        assertEquals(0, closes)
    }

    @Test fun cancelledOrVetoedHideCanBeRetriedWithoutPrematureRemoval() = runBlocking {
        var hides = 0; var hidden = false; var closes = 0
        val owner = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val dismiss = DroideSheetDismissal(owner, {
                hides++
                if (hides == 1) throw CancellationException("Drag interrupted hide")
            }, { hidden }, { closes++ })
            dismiss.request(); yield(); assertEquals(0, closes)
            dismiss.request(); yield(); assertEquals(0, closes)
            hidden = true; dismiss.request(); yield()
            assertEquals(1, closes); assertEquals(3, hides)
        } finally { owner.cancel() }
    }

    @Test fun nativeDismissalAndProgrammaticExitCannotDoubleDeliver() = runBlocking {
        val exited = CompletableDeferred<Unit>(); var closes = 0
        val owner = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val dismiss = DroideSheetDismissal(owner, { exited.await() }, { true }, { closes++ })
            dismiss.request(); yield(); dismiss.dismissed(); exited.complete(Unit); yield()
            assertEquals(1, closes)
        } finally { owner.cancel() }
    }
}
