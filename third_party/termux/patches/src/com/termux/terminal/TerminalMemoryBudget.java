package com.termux.terminal;

import java.util.WeakHashMap;

/** Droide's process-wide reservation for retained terminal arrays, not a whole-app heap guarantee. */
public final class TerminalMemoryBudget {
    public static final long BUFFER_BYTES = 2L * 1024 * 1024;
    public static final long EMULATOR_BYTES = 2 * BUFFER_BYTES;
    public static final long RETAINED_BYTES = 32L * 1024 * 1024;
    public static final long TOTAL_BYTES_WITH_RESIZE = 64L * 1024 * 1024;
    public static final int MAX_COLUMNS = 512;
    public static final int MAX_ROWS = 256;
    public static final int MAX_VISIBLE_CELLS = 10000;
    private static final WeakHashMap<TerminalEmulator, Boolean> owners = new WeakHashMap<>();

    private TerminalMemoryBudget() { }

    static synchronized void register(TerminalEmulator owner) {
        // Each reachable owner reserves its own second copy for resize/reflow. There is no
        // blocking global resize lock or mid-resize admission failure on the UI thread.
        if ((owners.size() + 1L) * 2 * EMULATOR_BYTES > TOTAL_BYTES_WITH_RESIZE)
            throw new IllegalStateException("Terminal memory budget reached; close unused terminal histories and retry after they are released");
        owners.put(owner, Boolean.TRUE);
    }

    public static int columns(int requested) { return Math.max(2, Math.min(MAX_COLUMNS, requested)); }
    public static int rows(int columns, int requested) {
        return Math.max(2, Math.min(Math.min(MAX_ROWS, requested), MAX_VISIBLE_CELLS / columns(columns)));
    }
}
