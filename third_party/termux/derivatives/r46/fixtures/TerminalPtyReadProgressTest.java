package com.termux.terminal;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Reader retry budgeting with deterministic bytes; no Android, JNI, PTY or network. */
public class TerminalPtyReadProgressTest {
    static long result(int tag, int value) { return ((long) tag << 32) | (value & 0xffffffffL); }

    static final class Attempt {
        final long poll, read;
        final byte[] payload;
        Attempt(long poll, long read, byte[] payload) {
            this.poll = poll; this.read = read; this.payload = payload;
        }
    }

    static class ScriptedReader implements TerminalPtyIo.ReaderBackend {
        final List<Attempt> attempts = new ArrayList<>();
        final ByteArrayOutputStream expected = new ByteArrayOutputStream();
        final ByteArrayOutputStream delivered = new ByteArrayOutputStream();
        Attempt current;
        int cursor, readCalls, notifications, backoffs, requestedBackoffMillis;
        boolean stop;
        void retry(int kind) {
            if (kind == 0) attempts.add(new Attempt(result(1, 4), 0, null)); // poll EINTR
            else if (kind == 1) attempts.add(new Attempt(result(0, 0), 0, null)); // no event
            else attempts.add(new Attempt(result(0, 1), result(1, kind == 2 ? 11 : 4), null)); // read EAGAIN/EINTR
        }
        void data(int sequence, int length) {
            byte[] payload = new byte[length];
            for (int i = 0; i < length; i++) payload[i] = (byte) (sequence * 31 + i * 17 + (i >>> 8));
            expected.write(payload, 0, length);
            attempts.add(new Attempt(result(0, 1), result(0, length), payload));
        }
        void eof() { attempts.add(new Attempt(result(0, 16), result(1, 11), null)); }
        public long poll(int millis) {
            assertEquals(TerminalPtyIo.MAX_POLL_MILLIS, millis);
            assertTrue("read loop exceeded its finite script", cursor < attempts.size());
            current = attempts.get(cursor++);
            return current.poll;
        }
        public long read(byte[] bytes) {
            readCalls++;
            if (current.payload != null) System.arraycopy(current.payload, 0, bytes, 0, current.payload.length);
            return current.read;
        }
        public boolean stopped() { return stop; }
        public boolean enqueue(byte[] bytes, int count) { delivered.write(bytes, 0, count); return true; }
        public void queued() { notifications++; }
        public void backoff(int millis) throws InterruptedException {
            assertEquals(10, millis); backoffs++; requestedBackoffMillis += millis;
        }
        void assertComplete(TerminalPtyIo.ReadReceipt receipt, int chunks) {
            assertFalse(receipt.incomplete);
            assertEquals(attempts.size(), cursor);
            assertEquals(chunks, notifications);
            assertArrayEquals("ordered bytes must have no loss, duplication or mutation", expected.toByteArray(), delivered.toByteArray());
        }
    }

    @Test public void sustainedSuccessfulDeliveryDoesNotBackoff() {
        ScriptedReader io = new ScriptedReader();
        for (int i = 0; i < 256; i++) io.data(i, TerminalPtyIo.MAX_CHUNK);
        io.eof();
        TerminalPtyIo.ReadReceipt receipt = new TerminalPtyIo.ReadReceipt();
        TerminalPtyIo.readUntilClosed(io, new byte[TerminalPtyIo.MAX_CHUNK], receipt);
        io.assertComplete(receipt, 256);
        System.out.println("READER_METRIC sustained bytes=" + io.delivered.size() + " polls=" + io.cursor + " backoffs=" + io.backoffs + " requestedBackoffMillis=" + io.requestedBackoffMillis);
        assertEquals("successful delivery must reset the consecutive retry budget", 0, io.backoffs);
    }

    @Test public void intermittentRetriesResetAfterEachSuccessfulDelivery() {
        ScriptedReader io = new ScriptedReader();
        for (int i = 0; i < 64; i++) {
            for (int kind = 0; kind < 4; kind++) io.retry(kind);
            io.data(i, 97 + i);
        }
        io.eof();
        TerminalPtyIo.ReadReceipt receipt = new TerminalPtyIo.ReadReceipt();
        TerminalPtyIo.readUntilClosed(io, new byte[TerminalPtyIo.MAX_CHUNK], receipt);
        io.assertComplete(receipt, 64);
        System.out.println("READER_METRIC mixed bytes=" + io.delivered.size() + " polls=" + io.cursor + " backoffs=" + io.backoffs + " requestedBackoffMillis=" + io.requestedBackoffMillis);
        assertEquals("four retries per successful chunk must not accumulate across chunks", 0, io.backoffs);
    }

    private static void assertRetryStormYields(int kind) {
        ScriptedReader io = new ScriptedReader();
        for (int i = 0; i < 64; i++) io.retry(kind);
        io.eof();
        TerminalPtyIo.ReadReceipt receipt = new TerminalPtyIo.ReadReceipt();
        TerminalPtyIo.readUntilClosed(io, new byte[TerminalPtyIo.MAX_CHUNK], receipt);
        io.assertComplete(receipt, 0);
        assertEquals(4, io.backoffs);
        assertEquals(40, io.requestedBackoffMillis);
        assertEquals(kind < 2 ? 1 : 65, io.readCalls);
    }
    @Test public void uninterruptedPollEintrStillBacksOff() { assertRetryStormYields(0); }
    @Test public void uninterruptedZeroEventsStillBackOff() { assertRetryStormYields(1); }
    @Test public void uninterruptedReadEagainStillBacksOff() { assertRetryStormYields(2); }
    @Test public void uninterruptedReadEintrStillBacksOff() { assertRetryStormYields(3); }

    @Test public void successfulDeliveryStillObservesCancellationBeforeNextPoll() {
        ScriptedReader io = new ScriptedReader() { @Override public void queued() { super.queued(); stop = true; } };
        io.data(3, 41);
        TerminalPtyIo.ReadReceipt receipt = new TerminalPtyIo.ReadReceipt();
        TerminalPtyIo.readUntilClosed(io, new byte[TerminalPtyIo.MAX_CHUNK], receipt);
        assertTrue(receipt.incomplete);
        assertEquals(1, io.cursor); assertEquals(1, io.readCalls); assertEquals(1, io.notifications);
        assertArrayEquals(io.expected.toByteArray(), io.delivered.toByteArray());
        assertEquals(0, io.backoffs);
    }

    @Test public void queuedNotificationFaultNeverReplaysAcceptedBytes() {
        ScriptedReader io = new ScriptedReader() { @Override public void queued() { super.queued(); throw new IllegalStateException("injected notification fault"); } };
        io.data(5, 29);
        TerminalPtyIo.ReadReceipt receipt = new TerminalPtyIo.ReadReceipt();
        try { TerminalPtyIo.readUntilClosed(io, new byte[TerminalPtyIo.MAX_CHUNK], receipt); fail("missing injected fault"); }
        catch (IllegalStateException expected) { assertEquals("injected notification fault", expected.getMessage()); }
        assertTrue(receipt.incomplete);
        assertEquals(1, io.cursor); assertEquals(1, io.readCalls); assertEquals(1, io.notifications);
        assertArrayEquals(io.expected.toByteArray(), io.delivered.toByteArray());
    }
}
