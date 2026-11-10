package com.termux.terminal;

/** Complete, registered owner ABI. No numeric PID/fd operations are exposed to Java. */
final class JNI {
    static { System.loadLibrary("termux"); }
    static final int API_VERSION = 2;
    static final int READ = 1, WRITE = 2;
    static final int DIRECT_CHILD = 0, ANCHORED_SESSION_GROUP = 1;
    static final int SUCCESS = 0, ERRNO = 1, CANCELLED = 2, LANE_CLOSED = 3,
        BUSY = 4, INVALID_OWNER = 5, STATE_ERROR = 6;
    static final int EXITED = 0, NO_CHILD = 1, OWNERSHIP_LOST = 2, QUARANTINED = 3,
        EXITED_CLEANUP_UNCONFIRMED = 4;
    static int tag(long result) { return (int) (result >>> 32); }
    static int value(long result) { return (int) result; }
    static native int ownedProcessApiVersion();
    static native long reserveOwnedProcess();
    static native int createOwnedSubprocess(long owner, String command, String cwd,
        String[] args, String[] environment, int rows, int columns);
    static native int requestOwnedStop(long owner, int scope);
    static native long waitForOwnedProcess(long owner);
    static native boolean releaseOwnedProcess(long owner);
    static native long readOwnedPtyOnce(long owner, byte[] bytes, int offset, int count);
    static native long writeOwnedPtyOnce(long owner, byte[] bytes, int offset, int count);
    static native long pollOwnedPtyOnce(long owner, int lane, int timeoutMillis);
    static native void cancelOwnedPtyIo(long owner, int laneMask);
    static native boolean releaseOwnedPtyLane(long owner, int lane);
    static native boolean resizeOwnedPty(long owner, int rows, int columns);
}
