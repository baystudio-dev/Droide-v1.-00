package com.baystudio.droide.ui

import com.baystudio.droide.core.TerminalResizeReceipt
import com.baystudio.droide.core.TerminalGeometrySnapshot
import com.baystudio.droide.core.TerminalViewportGeometry
import org.junit.Assert.*
import org.junit.Test

class TerminalResizeRetryTest {
    private fun receipt(request: Long = 1, generation: Long = 1, status: Int = TerminalResizeReceipt.FAILED) =
        TerminalResizeReceipt(generation, request, 90, 30, 90, 30, 80, 24, status)
    private class Frames {
        val frames = mutableListOf<() -> Unit>()
        var cancelled = 0
        val retry = TerminalResizeRetry { action -> frames += action; { cancelled++ } }
        fun drain() { val pending = frames.toList(); frames.clear(); pending.forEach { it() } }
    }
    @Test fun oneRetryForSameFailureEvenIfRepeatedUpdates() {
        val f=Frames(); val owner=Any(); var calls=0; f.retry.lifecycle(owner)
        repeat(100) { f.retry.offer(owner,receipt(),{true},{true}){calls++} }
        assertEquals(1,f.frames.size); f.drain()
        f.retry.offer(owner,receipt(),{true},{true}){calls++}; f.drain(); assertEquals(1,calls)
    }
    @Test fun newerRequestCancelsOldFrame() {
        val f=Frames();val owner=Any();val seen=mutableListOf<Int>();f.retry.lifecycle(owner)
        f.retry.offer(owner,receipt(1),{true},{true}){seen+=1}
        f.retry.offer(owner,receipt(2),{true},{true}){seen+=2};f.drain();assertEquals(listOf(2),seen)
    }
    @Test fun olderReceiptCannotReviveSpentBudget() {
        val f=Frames();val owner=Any();var calls=0;f.retry.lifecycle(owner)
        for (id in listOf(2L,1L,2L,1L)) f.retry.offer(owner,receipt(id),{true},{true}){calls++}
        f.drain();assertEquals(1,calls)
    }
    @Test fun olderGenerationIsRejected() {
        val f=Frames();val owner=Any();var calls=0;f.retry.lifecycle(owner)
        f.retry.offer(owner,receipt(1,2),{true},{true}){calls++}
        f.retry.offer(owner,receipt(100,1),{true},{true}){calls+=100};f.drain();assertEquals(1,calls)
    }
    @Test fun exactOwnerRequiredEvenWithEqualObjects() {
        val f=Frames();val a=String(charArrayOf('a'));val b=String(charArrayOf('a'));var calls=0
        f.retry.lifecycle(a);f.retry.offer(b,receipt(),{true},{true}){calls++};f.drain();assertEquals(0,calls)
    }
    @Test fun ownerSwitchInvalidatesQueuedFrame() {
        val f=Frames();val a=Any();val b=Any();var calls=0;f.retry.lifecycle(a)
        f.retry.offer(a,receipt(),{true},{true}){calls++};f.retry.lifecycle(b);f.drain();assertEquals(0,calls)
    }
    @Test fun detachInvalidatesEvenUncancelledRunnable() {
        val f=Frames();val a=Any();var calls=0;f.retry.lifecycle(a)
        f.retry.offer(a,receipt(),{true},{true}){calls++};f.retry.lifecycle(null);f.drain();assertEquals(0,calls)
    }
    @Test fun hiddenFrameNeverRevivesAfterEligibilityReturns() {
        val f=Frames();val a=Any();var calls=0;f.retry.lifecycle(a)
        f.retry.offer(a,receipt(),{true},{true}){calls++};f.retry.suspendPresentation();f.drain()
        f.retry.offer(a,receipt(),{true},{true}){calls++};f.drain();assertEquals(0,calls)
    }
    @Test fun explicitNewLifecyclePermitsOneFreshAttempt() {
        val f=Frames();val a=Any();var calls=0;f.retry.lifecycle(a)
        f.retry.offer(a,receipt(),{true},{true}){calls++};f.drain();f.retry.lifecycle(a)
        f.retry.offer(a,receipt(),{true},{true}){calls++};f.drain();assertEquals(2,calls)
    }
    @Test fun closedAtFrameTimeIsSkipped() {
        val f=Frames();val a=Any();var alive=true;var calls=0;f.retry.lifecycle(a)
        f.retry.offer(a,receipt(),{alive},{true}){calls++};alive=false;f.drain();assertEquals(0,calls)
    }
    @Test fun geometryChangedBeforeFrameSkipsOldSize() {
        val f=Frames();val a=Any();var latest=true;var calls=0;f.retry.lifecycle(a)
        f.retry.offer(a,receipt(),{true},{latest}){calls++};latest=false;f.drain();assertEquals(0,calls)
    }
    @Test fun nonFailureNeverRetries() {
        val f=Frames();val a=Any();f.retry.lifecycle(a)
        for (s in listOf(1,3,4,99)) f.retry.offer(a,receipt(status=s),{true},{true}){fail()}
        f.retry.offer(a,null,{true},{true}){fail()};assertTrue(f.frames.isEmpty())
    }
    @Test fun initialIneligibilityDoesNotSpendBudget() {
        val f=Frames();val a=Any();var calls=0;f.retry.lifecycle(a)
        f.retry.offer(a,receipt(),{false},{true}){fail()}
        f.retry.offer(a,receipt(),{true},{true}){calls++};f.drain();assertEquals(1,calls)
    }
    @Test fun synchronousSchedulerCannotReplayOrRecurse() {
        var calls=0;val a=Any();lateinit var r:TerminalResizeRetry
        r=TerminalResizeRetry { action -> action(); {} };r.lifecycle(a)
        r.offer(a,receipt(),{true},{true}) { calls++;r.offer(a,receipt(),{true},{true}){calls++} }
        assertEquals(1,calls)
    }
    @Test fun failedRetryDoesNotResetBudget() {
        val f=Frames();val a=Any();f.retry.lifecycle(a)
        f.retry.offer(a,receipt(),{true},{true}){throw IllegalStateException("fixture")}
        try { f.drain();fail() } catch (_:IllegalStateException) {}
        f.retry.offer(a,receipt(),{true},{true}){fail()};assertTrue(f.frames.isEmpty())
    }
    @Test fun receiptDescriptionNeverClaimsReadback() {
        val s=TerminalGeometrySnapshot(receipt=receipt(status=1));assertNull(s.warning)
        assertTrue(s.description().contains("ioctl accepted (not readback)"))
        assertTrue(s.description().contains("Kernel size readback is not available"))
    }
    @Test fun oldRuntimeExplicitlyUnknown() {
        val s=TerminalGeometrySnapshot();assertNull(s.warning)
        assertTrue(s.description().contains("has not supplied a checked resize result"))
    }
    @Test fun failedResizeAndGridMismatchAreVisible() {
        assertNotNull(TerminalGeometrySnapshot(receipt=receipt()).warning)
        val v=TerminalViewportGeometry(900,600,10f,20,90,30,80,24)
        assertNotNull(TerminalGeometrySnapshot(viewport=v).warning)
        assertNull(TerminalGeometrySnapshot(viewport=v.copy(emulatorColumns=90,emulatorRows=30)).warning)
    }
    @Test fun descriptionDoesNotIncludeOwnerToString() {
        val secret=object {override fun toString()="credential-fixture"}
        assertFalse(TerminalGeometrySnapshot(owner=secret,receipt=receipt()).description().contains("credential-fixture"))
    }
}
