package com.baystudio.droide.ui

import org.junit.Assert.*
import org.junit.Test

class TerminalImeOwnerTest {
    private class Harness {
        val owner = TerminalImeOwner()
        var session: Any? = Any()
        var eligible = true
        val events = mutableListOf<String>()
        fun connect() = owner.connect(requireNotNull(session), { session }, { eligible })
        fun write(lease: TerminalImeOwner.Lease, value: String = "text") = lease.deliver { events += value; true }
    }
    @Test fun currentOwnerDeliversUnicodeUnchanged() { val h=Harness();assertTrue(h.write(h.connect(), "日本語🙂\n"));assertEquals(listOf("日本語🙂\n"),h.events) }
    @Test fun replacementConnectionRejectsOldConnection() { val h=Harness();val old=h.connect();val next=h.connect();assertFalse(h.write(old));assertTrue(h.write(next));assertEquals(1,h.events.size) }
    @Test fun changedSessionRejectsConnection() { val h=Harness();val old=h.connect();h.session=Any();assertFalse(h.write(old));assertTrue(h.events.isEmpty()) }
    @Test fun equalSessionValuesAreNotSameSessionIdentity() { val h=Harness();h.session=String(charArrayOf('a'));val old=h.connect();h.session=String(charArrayOf('a'));assertFalse(h.write(old)) }
    @Test fun nullCurrentSessionRejectsConnection() { val h=Harness();val old=h.connect();h.session=null;assertFalse(h.write(old)) }
    @Test fun focusOrPresentationIneligibilityBlocksDelivery() { val h=Harness();val old=h.connect();h.eligible=false;assertFalse(h.write(old));assertTrue(h.events.isEmpty()) }
    @Test fun invalidationCannotReviveWhenEligibilityReturns() { val h=Harness();val old=h.connect();h.eligible=false;h.owner.invalidate();h.eligible=true;assertFalse(h.write(old));assertTrue(h.write(h.connect())) }
    @Test fun sameSessionAfterInvalidationNeedsFreshConnection() { val h=Harness();val old=h.connect();h.owner.invalidate();val next=h.connect();assertFalse(h.write(old));assertTrue(h.write(next)) }
    @Test fun oldCloseDoesNotInvalidateReplacement() { val h=Harness();val old=h.connect();val next=h.connect();old.close();assertTrue(h.write(next));assertFalse(h.write(old)) }
    @Test fun repeatedCloseIsSafe() { val h=Harness();val old=h.connect();old.close();old.close();assertFalse(h.write(old));assertTrue(h.write(h.connect())) }
    @Test fun closeStopsDeletionAndAllLaterDelivery() { val h=Harness();val old=h.connect();old.close();assertFalse(old.deleteKeys(1,1,{true},{true}));assertFalse(h.write(old)) }
    @Test fun zeroDeleteProducesNoKeys() { val h=Harness();val lease=h.connect();assertTrue(lease.deleteKeys(0,0,{h.events+="back";true},{h.events+="forward";true}));assertTrue(h.events.isEmpty()) }
    @Test fun backwardAndForwardOrderAndCountAreExact() { val h=Harness();assertTrue(h.connect().deleteKeys(2,3,{h.events+="back";true},{h.events+="forward";true}));assertEquals(listOf("back","back","forward","forward","forward"),h.events) }
    @Test fun negativeBeforeRejectsWholeCallback() { val h=Harness();assertFalse(h.connect().deleteKeys(-1,2,{h.events+="back";true},{h.events+="forward";true}));assertTrue(h.events.isEmpty()) }
    @Test fun negativeAfterRejectsWholeCallback() { val h=Harness();assertFalse(h.connect().deleteKeys(2,-1,{h.events+="back";true},{h.events+="forward";true}));assertTrue(h.events.isEmpty()) }
    @Test fun existingPerDirectionDeleteBoundIsPreserved() { val h=Harness();assertTrue(h.connect().deleteKeys(Int.MAX_VALUE,Int.MAX_VALUE,{h.events+="back";true},{h.events+="forward";true}));assertEquals(256,h.events.count{it=="back"});assertEquals(256,h.events.count{it=="forward"}) }
    @Test fun ownerInvalidationDuringDeleteStopsRemainingKeys() { val h=Harness();val lease=h.connect();assertFalse(lease.deleteKeys(3,2,{h.events+="back";h.owner.invalidate();true},{h.events+="forward";true}));assertEquals(listOf("back"),h.events) }
    @Test fun sessionChangeDuringDeleteStopsRemainingKeys() { val h=Harness();val lease=h.connect();assertFalse(lease.deleteKeys(3,2,{h.events+="back";h.session=Any();true},{h.events+="forward";true}));assertEquals(listOf("back"),h.events) }
    @Test fun replacementDuringForwardDeleteStopsOldCallback() { val h=Harness();val lease=h.connect();assertFalse(lease.deleteKeys(0,3,{true},{h.events+="forward";h.connect();true}));assertEquals(listOf("forward"),h.events) }
    @Test fun sinkFailureDoesNotRetryOrContinue() { val h=Harness();assertFalse(h.connect().deleteKeys(3,2,{h.events+="back";false},{h.events+="forward";true}));assertEquals(listOf("back"),h.events) }
    @Test fun sinkExceptionKeepsIdentityAndDoesNotRetry() { val h=Harness();val expected=IllegalStateException("sink");try { h.connect().deleteKeys(2,0,{h.events+="back";throw expected},{true});fail() }catch(actual:IllegalStateException){assertSame(expected,actual)};assertEquals(listOf("back"),h.events) }
    @Test fun repeatedTabCyclesRejectEveryRetiredConnection() { val h=Harness();val retired=mutableListOf<TerminalImeOwner.Lease>();repeat(100){ h.owner.invalidate();h.session=Any();val current=h.connect();retired.forEach{assertFalse(h.write(it))};assertTrue(h.write(current,"$it"));retired+=current };assertEquals(100,h.events.size) }
}
