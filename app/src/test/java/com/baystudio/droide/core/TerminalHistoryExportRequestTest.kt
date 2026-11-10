package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class TerminalHistoryExportRequestTest {
    private fun snapshot(text: String = "first\n😀") = TerminalRetainedHistory.capture(text)
    @Test fun pickerFreezesExactSnapshotAcrossTerminalChanges() {
        val owner = TerminalHistoryExportRequest(); val original = snapshot()
        val ticket = owner.begin(original, "epoch-a", "request")
        snapshot("replacement tab")
        assertSame(ticket, owner.pickerResult("request",true, "epoch-a").ticket)
        assertSame(original, ticket.history)
    }
    @Test fun secondPickerAndActiveWriteAreRejected() {
        val owner = TerminalHistoryExportRequest(); val first = owner.begin(snapshot(), "e", "request")
        assertThrows(IllegalStateException::class.java) { owner.begin(snapshot(), "e", "request") }
        owner.pickerResult("request",true, "e")
        assertThrows(IllegalStateException::class.java) { owner.begin(snapshot(), "e", "request") }
        assertTrue(owner.finish(first)); assertFalse(owner.busy)
        owner.begin(snapshot(), "e", "request")
    }
    @Test fun recreationNeverRecapturesAndCanRetryAfterResult() {
        val restored = TerminalHistoryExportRequest();restored.restoreOutstanding("request")
        assertTrue(restored.busy)
        val result = restored.pickerResult("request",true, "e")
        assertNull(result.ticket);assertTrue(result.message!!.contains("recreation"));assertFalse(restored.busy)
        assertNotNull(restored.begin(snapshot(), "e"))
    }
    @Test fun cancelledPickerDoesNotWarnOrWrite() {
        val owner = TerminalHistoryExportRequest();owner.begin(snapshot(), "e", "request")
        val result=owner.pickerResult("request",false, "e")
        assertNull(result.ticket);assertNull(result.message);assertFalse(owner.busy)
    }
    @Test fun closedOrReplacementEpochCannotWrite() {
        for (epoch in listOf(null,"replacement")) {
            val owner=TerminalHistoryExportRequest();owner.begin(snapshot(),"original","request")
            val result=owner.pickerResult("request",true,epoch)
            assertNull(result.ticket);assertNotNull(result.message);assertFalse(owner.busy)
        }
    }
    @Test fun duplicatedResultCannotConsumeActiveWrite() {
        val owner=TerminalHistoryExportRequest();val ticket=owner.begin(snapshot(),"e","request")
        assertSame(ticket,owner.pickerResult("request",true,"e").ticket)
        assertNull(owner.pickerResult("request",true,"e").ticket);assertTrue(owner.writeActive)
        assertTrue(owner.finish(ticket));assertFalse(owner.finish(ticket))
    }
    @Test fun failedLaunchOnlyClearsItsExactRequest() {
        val owner=TerminalHistoryExportRequest();val first=owner.begin(snapshot(),"e","request")
        owner.launchFailed(first);assertFalse(owner.busy)
        val second=owner.begin(snapshot("second"),"e","second")
        owner.launchFailed(first);assertTrue(owner.busy)
        assertSame(second,owner.pickerResult("second",true,"e").ticket)
    }
    @Test fun staleCompletionCannotClearNewWrite() {
        val owner=TerminalHistoryExportRequest();val a=owner.begin(snapshot(),"e","request")
        owner.pickerResult("request",true,"e");owner.finish(a)
        val b=owner.begin(snapshot("b"),"e","b");owner.pickerResult("b",true,"e")
        assertFalse(owner.finish(a));assertTrue(owner.writeActive);assertTrue(owner.finish(b))
    }
    @Test fun staleRestoredMarkerCanBeRetiredWithoutRedirectingItsLateResult() {
        val owner=TerminalHistoryExportRequest();owner.restoreOutstanding("old")
        assertTrue(owner.retireUnavailableSnapshot())
        val current=owner.begin(snapshot("new"),"e","new")
        assertNull(owner.pickerResult("old",true,"e").ticket)
        assertEquals("new",owner.pickerOutstanding)
        assertSame(current,owner.pickerResult("new",true,"e").ticket)
    }
    @Test fun liveRequestCannotBeRetiredAsLost() {
        val owner=TerminalHistoryExportRequest();owner.begin(snapshot(),"e","request")
        assertFalse(owner.retireUnavailableSnapshot());assertTrue(owner.busy)
    }
    @Test fun markerContainsNoSnapshotAndRestorationCannotReplaceActiveOwner() {
        val owner=TerminalHistoryExportRequest();owner.begin(snapshot("private"),"e","request")
        assertEquals("request",owner.pickerOutstanding);assertFalse(owner.writeActive)
        assertThrows(IllegalStateException::class.java) { owner.restoreOutstanding("request") }
    }
}
