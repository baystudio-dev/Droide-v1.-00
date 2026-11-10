package com.termux.terminal;

import org.junit.Test;
import static org.junit.Assert.*;

/** Host-only receipt-state contracts, compiled against the actual generated engine JAR. */
public final class TerminalResizeStateContractTest {
    private final TerminalResizeState state = new TerminalResizeState();
    private final Object owner = new Object();
    private final Backend backend = new Backend();

    private static final class Backend implements TerminalResizeState.Backend {
        int attempts, rows, columns, availability = TerminalResizeState.AVAILABLE;
        boolean succeeds = true, throwsOnResize, throwsOnAvailability;
        Runnable duringResize;
        public int availability(Object owner, long handle, long generation) {
            if (throwsOnAvailability) throw new IllegalStateException("fixture availability failure");
            return availability;
        }
        public boolean resize(Object owner, long handle, long generation, int rows, int columns) {
            attempts++; this.rows = rows; this.columns = columns;
            if (duringResize != null) duringResize.run();
            if (throwsOnResize) throw new IllegalStateException("fixture resize failure");
            return succeeds;
        }
    }
    private TerminalResizeState.Receipt resize(int columns, int rows) {
        return state.resize(owner, 7, 1, columns, rows, columns, rows, backend, null);
    }
    @Test public void initialSpawnNeedsSuccessfulOwnershipAndDoesNotResize() {
        TerminalResizeState.Receipt failed = state.initialSpawn(owner, 7, 1, 80, 24, 80, 24, false, backend, null);
        assertEquals(TerminalResizeState.UNAVAILABLE, failed.status);
        assertFalse(failed.hasAcceptedGeometry);
        TerminalResizeState.Receipt accepted = state.initialSpawn(owner, 7, 1, 80, 24, 80, 24, true, backend, null);
        assertEquals(TerminalResizeState.ACCEPTED, accepted.status);
        assertEquals(80, accepted.acceptedColumns); assertEquals(24, accepted.acceptedRows);
        assertEquals(0, backend.attempts);
    }
    @Test public void acceptedGeometrySuppressesOnlyProvenRedundantAttempt() {
        TerminalResizeState.Receipt first = resize(100, 30);
        assertEquals(TerminalResizeState.ACCEPTED, first.status);
        assertEquals(30, backend.rows); assertEquals(100, backend.columns);
        assertSame(first, resize(100, 30)); assertEquals(1, backend.attempts);
    }
    @Test public void failedGeometryRetriesOnlyOnAnotherExplicitCall() {
        backend.succeeds = false;
        TerminalResizeState.Receipt first = resize(80, 24);
        assertEquals(TerminalResizeState.FAILED, first.status); assertEquals(1, backend.attempts);
        TerminalResizeState.Receipt second = resize(80, 24);
        assertEquals(TerminalResizeState.FAILED, second.status);
        assertEquals(first.requestId, second.requestId); assertEquals(2, backend.attempts);
    }
    @Test public void failurePreservesLastAcceptedGeometry() {
        resize(80, 24); backend.succeeds = false;
        TerminalResizeState.Receipt failed = resize(100, 30);
        assertEquals(TerminalResizeState.FAILED, failed.status);
        assertTrue(failed.hasAcceptedGeometry); assertEquals(80, failed.acceptedColumns);
        assertEquals(24, failed.acceptedRows); assertEquals(100, failed.emulatorColumns);
    }
    @Test public void ownerGenerationChangeInvalidatesAcceptedGeometry() {
        TerminalResizeState.Receipt old = resize(80, 24); backend.availability = TerminalResizeState.UNAVAILABLE;
        TerminalResizeState.Receipt next = state.resize(owner, 7, 2, 80, 24, 80, 24, backend, null);
        assertFalse(next.hasAcceptedGeometry); assertEquals(0, next.acceptedColumns);
        assertTrue(next.requestId > old.requestId); assertEquals(1, backend.attempts);
    }
    @Test public void invalidGeometryAndOwnerNeverCallNativeBackend() {
        assertEquals(TerminalResizeState.UNAVAILABLE, resize(1, 24).status);
        assertEquals(TerminalResizeState.UNAVAILABLE,
            state.resize(null, 7, 1, 80, 24, 80, 24, backend, null).status);
        assertEquals(TerminalResizeState.UNAVAILABLE,
            state.resize(owner, 0, 1, 80, 24, 80, 24, backend, null).status);
        assertEquals(0, backend.attempts);
    }
    @Test public void closeItselfDoesNotNotifyAndExplicitResizeReportsClosed() {
        final int[] callbacks = {0}; state.close();
        TerminalResizeState.Receipt result = state.resize(owner, 7, 1, 80, 24, 80, 24, backend, r -> callbacks[0]++);
        assertEquals(TerminalResizeState.CLOSED, result.status); assertEquals(0, backend.attempts);
        assertEquals(1, callbacks[0]); state.close(); assertEquals(1, callbacks[0]);
    }
    @Test public void backendExceptionsCannotBecomeAcceptance() {
        backend.throwsOnResize = true;
        assertEquals(TerminalResizeState.FAILED, resize(80, 24).status);
        backend.throwsOnAvailability = true;
        assertEquals(TerminalResizeState.UNAVAILABLE, resize(100, 30).status);
        assertEquals(1, backend.attempts);
    }
    @Test public void sameRequestListenerReentryCannotCreateRetryLoop() {
        backend.succeeds = false; final int[] callbacks = {0};
        TerminalResizeState.Listener listener = r -> { callbacks[0]++; resize(80, 24); };
        TerminalResizeState.Receipt result = state.resize(owner, 7, 1, 80, 24, 80, 24, backend, listener);
        assertEquals(TerminalResizeState.FAILED, result.status);
        assertEquals(1, callbacks[0]); assertEquals(1, backend.attempts);
    }
    @Test public void listenerExceptionDoesNotRollbackAcceptance() {
        TerminalResizeState.Receipt result = state.resize(owner, 7, 1, 80, 24, 80, 24, backend,
            r -> { throw new IllegalStateException("fixture observer failure"); });
        assertEquals(TerminalResizeState.ACCEPTED, result.status); assertSame(result, state.snapshot());
    }
    @Test public void closeDuringNativeAttemptRejectsSuccessfulReturn() {
        backend.duringResize = () -> state.close();
        TerminalResizeState.Receipt result = resize(80, 24);
        assertEquals(TerminalResizeState.CLOSED, result.status); assertFalse(result.hasAcceptedGeometry);
        assertEquals(TerminalResizeState.CLOSED, state.snapshot().status);
    }
    @Test public void changedRequestDuringNativeAttemptCannotReceiveOldReceipt() {
        backend.duringResize = () -> state.resize(owner, 7, 2, 100, 30, 100, 30, backend, null);
        TerminalResizeState.Receipt result = resize(80, 24);
        assertEquals(1, result.generation); assertEquals(80, result.requestedColumns);
        assertEquals(TerminalResizeState.UNAVAILABLE, result.status);
        assertEquals(2, state.snapshot().generation); assertFalse(state.snapshot().hasAcceptedGeometry);
        assertEquals(1, backend.attempts);
    }
}
