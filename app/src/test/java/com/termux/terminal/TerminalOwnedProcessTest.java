package com.termux.terminal;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

/** Pure deterministic fakes only: no JNI class initialization, thread, fd, PTY or child process. */
public class TerminalOwnedProcessTest {
    static final class FakeOwner implements TerminalProcessOwner.Backend {
        final List<String> events = new ArrayList<>();
        long token;
        int version = 2, liveSlots;
        boolean spawnThrows;
        public int apiVersion() { return version; }
        public long reserve() { events.add("reserve"); liveSlots++; return ++token; }
        public int spawn(long owner) { events.add("spawn"); if (spawnThrows) throw new LinkageError("injected"); return 123; }
        public long waitFor(long owner) { events.add("wait"); return 0; }
        public int stop(long owner) { events.add("stop"); return 0; }
        public void cancelIo(long owner, int lanes) { events.add("cancel:" + lanes); }
        public void release(long owner) { events.add("release"); liveSlots--; }
        public void releaseLane(long owner, int lane) { events.add("lane:" + lane); }
    }
    @Test public void waiterReaderAndWriterStartBeforeNativeSpawn() {
        FakeOwner backend = new FakeOwner();
        TerminalProcessOwner owner = new TerminalProcessOwner(backend);
        assertEquals(123, owner.start(() -> backend.events.add("waiter"), () -> backend.events.add("reader"), () -> backend.events.add("writer")));
        assertEquals(Arrays.asList("reserve", "waiter", "reader", "writer", "spawn"), backend.events);
        owner.awaitProcess();
        assertTrue(owner.awaitIo());
        owner.releaseLane(1); owner.releaseLane(2); owner.releaseWaiter();
        assertEquals(0, backend.liveSlots);
    }
    @Test public void repeatedWaiterStartFailureReturnsCreatorHoldsWithoutSpawning() {
        FakeOwner backend = new FakeOwner();
        for (int attempt = 0; attempt < 128; attempt++) {
            TerminalProcessOwner owner = new TerminalProcessOwner(backend);
            try { owner.start(() -> { throw new OutOfMemoryError("injected start failure"); }, () -> fail("reader started"), () -> fail("writer started")); fail("missing failure"); }
            catch (OutOfMemoryError expected) { }
            owner.awaitProcess();
            assertFalse(owner.awaitIo());
            assertEquals(0, backend.liveSlots);
        }
        assertFalse(backend.events.contains("spawn"));
        assertEquals(128, backend.events.stream().filter("release"::equals).count());
    }
    @Test public void acceptedWaiterOwnsLifecycleWhenLaterStartFails() {
        FakeOwner backend = new FakeOwner();
        TerminalProcessOwner owner = new TerminalProcessOwner(backend);
        try { owner.start(() -> backend.events.add("waiter"), () -> { throw new IllegalStateException("reader start"); }, () -> fail("writer started")); fail("missing failure"); }
        catch (IllegalStateException expected) { }
        owner.awaitProcess(); assertFalse(owner.awaitIo());
        assertFalse(backend.events.contains("spawn"));
        assertFalse(backend.events.contains("release"));
        assertTrue(backend.events.containsAll(Arrays.asList("lane:1", "lane:2")));
        owner.waitForProcess(); owner.releaseWaiter();
        assertEquals(0, backend.liveSlots);
    }
    @Test public void nativeSpawnErrorPublishesBothHandoffsToAcceptedWorkers() {
        FakeOwner backend = new FakeOwner(); backend.spawnThrows = true;
        TerminalProcessOwner owner = new TerminalProcessOwner(backend);
        try { owner.start(() -> {}, () -> {}, () -> {}); fail("missing failure"); }
        catch (LinkageError expected) { }
        owner.awaitProcess(); assertFalse(owner.awaitIo());
        assertFalse(backend.events.contains("release"));
        owner.releaseLane(1); owner.releaseLane(2); owner.releaseWaiter();
        assertEquals(0, backend.liveSlots);
    }
    @Test public void preStartStopSkipsSpawnAndOldAbiCannotReserve() {
        FakeOwner backend = new FakeOwner();
        TerminalProcessOwner owner = new TerminalProcessOwner(backend);
        owner.stop(); assertEquals(0, owner.start(() -> {}, () -> {}, () -> {}));
        assertFalse(backend.events.contains("spawn"));
        assertFalse(owner.awaitIo());
        owner.releaseLane(1); owner.releaseLane(2); owner.releaseWaiter();
        backend.version = 1; backend.events.clear();
        try { new TerminalProcessOwner(backend).start(() -> {}, () -> {}, () -> {}); fail("old ABI accepted"); }
        catch (IllegalStateException expected) { }
        assertFalse(backend.events.contains("reserve"));
    }
    @Test public void ownerCannotSpawnTwice() {
        FakeOwner backend = new FakeOwner(); TerminalProcessOwner owner = new TerminalProcessOwner(backend);
        owner.start(() -> {}, () -> {}, () -> {});
        try { owner.start(() -> {}, () -> {}, () -> {}); fail("double startup accepted"); }
        catch (IllegalStateException expected) { }
        assertEquals(1, backend.events.stream().filter("spawn"::equals).count());
        owner.releaseLane(1); owner.releaseLane(2); owner.releaseWaiter();
    }

    static long result(int tag, int value) { return ((long) tag << 32) | (value & 0xffffffffL); }
    static class FakeIo implements TerminalPtyIo.Backend {
        long now, step;
        boolean stop, cancelDuringWrite;
        int writes, polls, backoffs;
        final List<Integer> offsets = new ArrayList<>();
        final List<Long> writeResults = new ArrayList<>(), pollResults = new ArrayList<>();
        long defaultWrite = result(1, 11), defaultPoll = result(0, 4);
        public long write(byte[] bytes, int offset, int count) {
            offsets.add(offset); writes++; now += step;
            if (cancelDuringWrite) stop = true; // paused after native admission, before syscall result.
            return writeResults.isEmpty() ? defaultWrite : writeResults.remove(0);
        }
        public long poll(int millis) {
            assertTrue(millis >= 0 && millis <= 100); polls++; now += Math.max(1, millis) * 1_000_000L;
            return pollResults.isEmpty() ? defaultPoll : pollResults.remove(0);
        }
        public long nanoTime() { return now; }
        public boolean cancelled() { return stop; }
        public void backoff(int millis) { assertTrue(millis >= 1 && millis <= 10); backoffs++; now += millis * 1_000_000L; }
    }
    @Test public void partialWritesInterruptsAndWouldBlockNeverReplayAcceptedPrefix() {
        FakeIo io = new FakeIo();
        io.writeResults.addAll(Arrays.asList(result(0, 2), result(1, 4), result(1, 11), result(0, 2)));
        io.pollResults.addAll(Arrays.asList(result(1, 4), result(0, 4)));
        TerminalPtyIo.WriteReceipt receipt = new TerminalPtyIo.WriteReceipt();
        TerminalPtyIo.writeChunk(io, new byte[]{1,2,3,4}, 4, receipt);
        assertEquals(4, receipt.confirmed); assertEquals(0, receipt.failure);
        assertEquals(Arrays.asList(0,2,2,2), io.offsets); assertEquals(2, io.polls);
    }
    @Test public void successfulProgressDoesNotResetAbsoluteDeadline() {
        FakeIo io = new FakeIo(); io.step = 2_000_000_000L; io.defaultWrite = result(0, 1);
        TerminalPtyIo.WriteReceipt receipt = new TerminalPtyIo.WriteReceipt();
        TerminalPtyIo.writeChunk(io, new byte[4], 4, receipt);
        assertEquals(3, receipt.confirmed); assertEquals(TerminalPtyIo.FAILURE_TIMEOUT, receipt.failure);
    }
    @Test public void signalStormHasAttemptBackoffAndFiniteDeadline() {
        FakeIo io = new FakeIo(); io.defaultWrite = result(1, 4);
        TerminalPtyIo.WriteReceipt receipt = new TerminalPtyIo.WriteReceipt();
        TerminalPtyIo.writeChunk(io, new byte[4], 4, receipt);
        assertEquals(TerminalPtyIo.FAILURE_TIMEOUT, receipt.failure);
        assertEquals(0, receipt.confirmed); assertTrue(io.backoffs > 0); assertTrue(io.writes <= 8000);
    }
    @Test public void admittedWriteMaySettleAfterStopButItsSuffixIsNeverReplayed() {
        FakeIo io = new FakeIo(); io.cancelDuringWrite = true; io.defaultWrite = result(0, 2);
        TerminalPtyIo.WriteReceipt receipt = new TerminalPtyIo.WriteReceipt();
        TerminalPtyIo.writeChunk(io, new byte[4], 4, receipt);
        assertEquals(2, receipt.confirmed); assertEquals(TerminalPtyIo.FAILURE_CANCELLED, receipt.failure);
        assertEquals(1, io.writes);
    }
    @Test public void cancellationObservedAfterPollPreventsNextWriteBorrow() {
        FakeIo io = new FakeIo() { @Override public long poll(int millis) { long r = super.poll(millis); stop = true; return r; } };
        TerminalPtyIo.WriteReceipt receipt = new TerminalPtyIo.WriteReceipt();
        TerminalPtyIo.writeChunk(io, new byte[4], 4, receipt);
        assertEquals(1, io.writes); assertEquals(TerminalPtyIo.FAILURE_CANCELLED, receipt.failure);
    }
    @Test public void fullQueueUsesPublishedCancellationAndKeepsDiscardReceipt() {
        ByteQueue queue = new ByteQueue(2);
        assertTrue(queue.write(new byte[]{1,2}, 0, 2));
        assertFalse(queue.writeUntil(new byte[]{3}, 0, 1, () -> true));
        queue.close(); assertEquals(2, queue.pendingBytes()); assertEquals(-1, queue.read(new byte[2], false));
    }
    @Test public void adapterErrorKeepsPartialAcceptanceAndDoesNotReplay() {
        FakeIo io = new FakeIo() { @Override public long write(byte[] bytes, int offset, int count) {
            if (writes == 1) throw new AssertionError("copy/result fault");
            return super.write(bytes, offset, count);
        } };
        io.defaultWrite = result(0, 1);
        TerminalPtyIo.WriteReceipt receipt = new TerminalPtyIo.WriteReceipt();
        try { TerminalPtyIo.writeChunk(io, new byte[4], 4, receipt); fail("missing error"); }
        catch (AssertionError expected) { }
        assertEquals(1, receipt.confirmed); assertEquals(TerminalPtyIo.FAILURE_EXCEPTION, receipt.failure);
    }
    static class FakeReader implements TerminalPtyIo.ReaderBackend {
        final List<Long> polls = new ArrayList<>(), reads = new ArrayList<>();
        boolean stop, accept = true;
        int pollCalls, readCalls, accepted, notifications, backoffs;
        public long read(byte[] bytes) { readCalls++; return reads.remove(0); }
        public long poll(int millis) { assertEquals(100, millis); pollCalls++; return polls.remove(0); }
        public boolean stopped() { return stop; }
        public boolean enqueue(byte[] bytes, int count) { if (accept) accepted += count; return accept; }
        public void queued() { notifications++; }
        public void backoff(int millis) { assertEquals(10, millis); backoffs++; }
    }
    @Test public void hangupStillDrainsTheReadableTailBeforeEof() {
        FakeReader io = new FakeReader();
        io.polls.addAll(Arrays.asList(result(0, 17), result(0, 16)));
        io.reads.addAll(Arrays.asList(result(0, 2), result(1, 11)));
        TerminalPtyIo.ReadReceipt receipt = new TerminalPtyIo.ReadReceipt();
        TerminalPtyIo.readUntilClosed(io, new byte[4], receipt);
        assertFalse(receipt.incomplete); assertEquals(2, io.accepted); assertEquals(1, io.notifications);
    }
    @Test public void fullOutputQueueCancellationIsAnIncompleteReceipt() {
        FakeReader io = new FakeReader(); io.accept = false;
        io.polls.add(result(0, 1)); io.reads.add(result(0, 4));
        TerminalPtyIo.ReadReceipt receipt = new TerminalPtyIo.ReadReceipt();
        TerminalPtyIo.readUntilClosed(io, new byte[4], receipt);
        assertTrue(receipt.incomplete); assertEquals(1, io.readCalls); assertEquals(0, io.notifications);
    }
    @Test public void readCopyFaultIsIncompleteAndNeverReplayed() {
        FakeReader io = new FakeReader() { @Override public long read(byte[] bytes) { readCalls++; throw new AssertionError("consumed then copy failed"); } };
        io.polls.add(result(0, 1)); TerminalPtyIo.ReadReceipt receipt = new TerminalPtyIo.ReadReceipt();
        try { TerminalPtyIo.readUntilClosed(io, new byte[4], receipt); fail("missing error"); }
        catch (AssertionError expected) { }
        assertTrue(receipt.incomplete); assertEquals(1, io.readCalls); assertEquals(0, io.accepted);
    }
    @Test public void readerInterruptStormYieldsAndObservesPublishedStop() {
        FakeReader io = new FakeReader() { @Override public long poll(int millis) {
            if (++pollCalls == 20) stop = true;
            return result(1, 4);
        } };
        TerminalPtyIo.ReadReceipt receipt = new TerminalPtyIo.ReadReceipt();
        TerminalPtyIo.readUntilClosed(io, new byte[4], receipt);
        assertTrue(receipt.incomplete); assertEquals(20, io.pollCalls); assertEquals(0, io.readCalls); assertEquals(1, io.backoffs);
    }
}
