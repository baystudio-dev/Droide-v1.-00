package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class TerminalHistoryExportWriterTest {
    private val history = TerminalRetainedHistory.capture("line 1\r\n  😀\n")
    @Test fun writesExactUtf8AndClosesBeforeSuccess() {
        var closed=false
        val out=object:ByteArrayOutputStream() { override fun close() { closed=true;super.close() } }
        val count=TerminalHistoryExportWriter.write(history,{out}) {}
        assertTrue(closed);assertArrayEquals(history.text.toByteArray(Charsets.UTF_8),out.toByteArray());assertEquals(out.size(),count)
    }
    @Test fun nullStreamFails() { assertThrows(IOException::class.java) { TerminalHistoryExportWriter.write(history,{null}) {} } }
    @Test fun openFailureKeepsIdentity() {
        val error=IOException("fixture")
        assertSame(error,assertThrows(IOException::class.java) { TerminalHistoryExportWriter.write(history,{throw error}) {} })
    }
    @Test fun writeFailureClosesAndDoesNotReportSuccess() {
        val error=IOException("write");var closed=false
        val out=object:OutputStream() { override fun write(b:Int) { throw error };override fun close() {closed=true} }
        assertSame(error,assertThrows(IOException::class.java) { TerminalHistoryExportWriter.write(history,{out}) {} });assertTrue(closed)
    }
    @Test fun closeFailureIsFailure() {
        val error=IOException("close")
        val out=object:ByteArrayOutputStream() { override fun close() {throw error} }
        assertSame(error,assertThrows(IOException::class.java) { TerminalHistoryExportWriter.write(history,{out}) {} })
    }
    @Test fun cancellationBeforeOpenCannotCreateFile() {
        var opened=false;val cancellation=CancellationException("owner")
        assertSame(cancellation,assertThrows(CancellationException::class.java) {
            TerminalHistoryExportWriter.write(history,{opened=true;ByteArrayOutputStream()}) {throw cancellation}
        });assertFalse(opened)
    }
    @Test fun cancellationDuringWriteClosesStream() {
        val cancellation=CancellationException("cancel");var checks=0;var closed=false
        val out=object:ByteArrayOutputStream() { override fun close() {closed=true} }
        assertSame(cancellation,assertThrows(CancellationException::class.java) {
            TerminalHistoryExportWriter.write(history,{out}) {if (++checks==2) throw cancellation}
        });assertTrue(closed)
    }
    @Test fun ownerRetirementDuringCloseSuppressesSuccess() {
        var retired=false
        val out=object:ByteArrayOutputStream() { override fun close() {retired=true} }
        assertThrows(IllegalStateException::class.java) { TerminalHistoryExportWriter.write(history,{out}) {check(!retired)} }
    }
    @Test fun emptySnapshotStillCloses() {
        var closed=false;val out=object:ByteArrayOutputStream() { override fun close() {closed=true} }
        assertEquals(0,TerminalHistoryExportWriter.write(TerminalRetainedHistory.capture(""),{out}) {});assertTrue(closed)
    }
}
