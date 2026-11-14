package com.termux.terminal;

/**
 * Android-independent checked resize receipts. There are no retries, timers or output hooks here.
 * The session serializes calls with its close boundary; this helper also tolerates reentrant observers.
 */
final class TerminalResizeState {
    static final int AVAILABLE = 0;
    static final int ACCEPTED = 1;
    static final int FAILED = 2;
    static final int UNAVAILABLE = 3;
    static final int CLOSED = 4;

    interface Backend {
        /** Validate the exact owner object, native handle and session generation. */
        int availability(Object owner, long handle, long generation);
        boolean resize(Object owner, long handle, long generation, int rows, int columns);
    }

    interface Listener { void onResult(Receipt receipt); }

    /** Every field is a value captured for one request; observers cannot mutate committed state. */
    static final class Receipt {
        final long generation, requestId;
        final int requestedColumns, requestedRows, emulatorColumns, emulatorRows;
        final int acceptedColumns, acceptedRows, status;
        final boolean hasAcceptedGeometry;

        Receipt(long generation, long requestId, int requestedColumns, int requestedRows,
                int emulatorColumns, int emulatorRows, int acceptedColumns, int acceptedRows,
                boolean hasAcceptedGeometry, int status) {
            this.generation = generation;
            this.requestId = requestId;
            this.requestedColumns = requestedColumns;
            this.requestedRows = requestedRows;
            this.emulatorColumns = emulatorColumns;
            this.emulatorRows = emulatorRows;
            this.acceptedColumns = acceptedColumns;
            this.acceptedRows = acceptedRows;
            this.hasAcceptedGeometry = hasAcceptedGeometry;
            this.status = status;
        }
    }

    private Object owner;
    private long handle, generation, requestId;
    private int requestedColumns, requestedRows, emulatorColumns, emulatorRows;
    private int acceptedColumns, acceptedRows;
    private boolean hasAcceptedGeometry, closed, attempting, notifying;
    private Receipt receipt = new Receipt(0, 0, 0, 0, 0, 0, 0, 0, false, UNAVAILABLE);
    private Receipt lastNotified;

    synchronized Receipt snapshot() { return receipt; }

    /** Closing is sticky and never dispatches a callback from a worker thread. */
    synchronized void close() {
        closed = true;
        receipt = capture(CLOSED);
    }

    /** Only a successful, still-owned spawn establishes the initial PTY geometry. */
    synchronized Receipt initialSpawn(Object owner, long handle, long generation,
            int requestedColumns, int requestedRows, int emulatorColumns, int emulatorRows,
            boolean spawnSucceeded, Backend backend, Listener listener) {
        select(owner, handle, generation, requestedColumns, requestedRows, emulatorColumns, emulatorRows);
        int status = availability(backend, owner, handle, generation);
        if (status == AVAILABLE && spawnSucceeded && emulatorColumns >= 2 && emulatorRows >= 2) {
            accept(emulatorColumns, emulatorRows);
            status = ACCEPTED;
        } else if (status == AVAILABLE) {
            status = UNAVAILABLE;
        }
        Receipt result = receipt = capture(status);
        notifyResult(result, listener);
        return result;
    }

    /** One explicit call permits at most one native attempt, including for unchanged failed geometry. */
    synchronized Receipt resize(Object owner, long handle, long generation,
            int requestedColumns, int requestedRows, int emulatorColumns, int emulatorRows,
            Backend backend, Listener listener) {
        boolean changed = select(owner, handle, generation, requestedColumns, requestedRows,
            emulatorColumns, emulatorRows);
        // An observer cannot turn a failed callback into an unbounded same-request retry chain.
        if (notifying && !changed) return receipt;
        int status = availability(backend, owner, handle, generation);
        if (status != AVAILABLE || emulatorColumns < 2 || emulatorRows < 2 || attempting) {
            Receipt result = receipt = capture(status == AVAILABLE ? UNAVAILABLE : status);
            notifyResult(result, listener);
            return result;
        }
        if (hasAcceptedGeometry && acceptedColumns == emulatorColumns && acceptedRows == emulatorRows) {
            // This is a genuine prior native/spawn receipt, never an emulator-equality inference.
            if (receipt.status != ACCEPTED) receipt = capture(ACCEPTED);
            Receipt result = receipt;
            notifyResult(result, listener);
            return result;
        }
        final long attemptedRequestId = requestId;
        final int previousAcceptedColumns = acceptedColumns, previousAcceptedRows = acceptedRows;
        final boolean previouslyAccepted = hasAcceptedGeometry;
        boolean success = false;
        attempting = true;
        try {
            // The backend repeats the exact ownership check immediately before its JNI call.
            success = backend.resize(owner, handle, generation, emulatorRows, emulatorColumns);
        } catch (Throwable ignored) {
            // An exception is not a successful ioctl receipt. A later explicit call may reattempt.
        } finally {
            attempting = false;
        }
        status = availability(backend, owner, handle, generation);
        boolean ownerMatches = sameOwner(owner, handle, generation);
        if (status == AVAILABLE && !ownerMatches) status = UNAVAILABLE;
        if (status == AVAILABLE) {
            if (success) {
                accept(emulatorColumns, emulatorRows);
                status = ACCEPTED;
            } else {
                status = FAILED;
            }
        }
        // Completion belongs to the dimensions actually attempted, even under backend reentry.
        Receipt result = new Receipt(generation, attemptedRequestId, requestedColumns, requestedRows,
            emulatorColumns, emulatorRows,
            ownerMatches ? acceptedColumns : previousAcceptedColumns,
            ownerMatches ? acceptedRows : previousAcceptedRows,
            ownerMatches ? hasAcceptedGeometry : previouslyAccepted, status);
        if (requestId == attemptedRequestId && sameOwner(owner, handle, generation)) receipt = result;
        else receipt = capture(closed ? CLOSED : UNAVAILABLE);
        notifyResult(result, listener);
        return result;
    }

    private boolean sameOwner(Object owner, long handle, long generation) {
        return this.owner == owner && this.handle == handle && this.generation == generation;
    }

    private boolean select(Object owner, long handle, long generation,
            int requestedColumns, int requestedRows, int emulatorColumns, int emulatorRows) {
        boolean ownerChanged = !sameOwner(owner, handle, generation);
        boolean changed = requestId == 0 || ownerChanged || this.requestedColumns != requestedColumns
            || this.requestedRows != requestedRows || this.emulatorColumns != emulatorColumns
            || this.emulatorRows != emulatorRows;
        if (!changed) return false;
        if (ownerChanged) {
            hasAcceptedGeometry = false;
            acceptedColumns = acceptedRows = 0;
        }
        this.owner = owner;
        this.handle = handle;
        this.generation = generation;
        this.requestedColumns = requestedColumns;
        this.requestedRows = requestedRows;
        this.emulatorColumns = emulatorColumns;
        this.emulatorRows = emulatorRows;
        requestId++;
        receipt = capture(closed ? CLOSED : UNAVAILABLE);
        return true;
    }

    private int availability(Backend backend, Object owner, long handle, long generation) {
        if (closed) return CLOSED;
        if (owner == null || handle <= 0 || generation <= 0) return UNAVAILABLE;
        int status;
        try { status = backend.availability(owner, handle, generation); }
        catch (Throwable ignored) { return UNAVAILABLE; }
        if (status == CLOSED) { closed = true; return CLOSED; }
        return status == AVAILABLE ? AVAILABLE : UNAVAILABLE;
    }

    private void accept(int columns, int rows) {
        acceptedColumns = columns;
        acceptedRows = rows;
        hasAcceptedGeometry = true;
    }

    private Receipt capture(int status) {
        return new Receipt(generation, requestId, requestedColumns, requestedRows,
            emulatorColumns, emulatorRows, acceptedColumns, acceptedRows, hasAcceptedGeometry, status);
    }

    private void notifyResult(Receipt result, Listener listener) {
        if (listener == null || notifying || result == lastNotified) return;
        // Commit this notification before invoking untrusted, observational client code.
        lastNotified = result;
        notifying = true;
        try { listener.onResult(result); }
        catch (Throwable ignored) { /* Acceptance/failure remains committed. */ }
        finally { notifying = false; }
    }
}
