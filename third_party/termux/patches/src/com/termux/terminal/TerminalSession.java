package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A terminal session, consisting of a process coupled to a terminal interface.
 * <p>
 * The subprocess will be executed by the constructor, and when the size is made known by a call to
 * {@link #updateSize(int, int)} terminal emulation will begin and threads will be spawned to handle the subprocess I/O.
 * All terminal emulation and callback methods will be performed on the main thread.
 * <p>
 * The child process may be exited forcefully by using the {@link #finishIfRunning()} method.
 * <p>
 * NOTE: The terminal session may outlive the EmulatorView, so be careful with callbacks!
 */
public final class TerminalSession extends TerminalOutput {

    /** Non-blocking owner hook for terminal input; implementations must copy reused input bytes. */
    public interface InputHandler {
        void write(byte[] data, int offset, int count);
        /** Runs after mandatory writer cleanup; never signals or restarts the shell. */
        default void onPtyWriteFailure(int reason) {}
    }

    /** Installed once before publishing a PTY to human or Agent input. */
    private volatile InputHandler mInputHandler;

    static final int MSG_NEW_INPUT = 1;
    static final int MSG_PROCESS_EXITED = 4;
    static final int MSG_READER_FINISHED = 5;
    static final int MSG_DRAIN_TIMEOUT = 6;
    static final int MSG_WRITER_FINISHED = 7;
    static final long EXIT_DRAIN_TIMEOUT_MILLIS = 5000;
    private volatile boolean mStopReader;
    private volatile boolean mStopWriter;
    private volatile long mReaderDeadlineNanos;
    private volatile boolean mWriterFinished;
    private volatile int mInputWriteFailureReason;
    private volatile int mProcessExitFailureReason;
    private volatile TerminalProcessOwner mOwner;
    private boolean mInitializationClaimed;
    private boolean mProcessExited;
    private boolean mReaderFinished;
    private boolean mCompletionStarted;
    private volatile boolean mCompletionDelivered;
    private volatile boolean mOutputIncomplete;
    private boolean mReaderFailureNoticeSent;

    /** False means the completion receipt must not be treated as a complete diagnostic tail. */
    public boolean isOutputComplete() { return mCompletionDelivered && !mOutputIncomplete; }

    /** Zero is no observed failure, not proof that the child consumed queued input. */
    public int getInputWriteFailureReason() { return mInputWriteFailureReason; }
    /** 0 exited; 1 no child; 2 ownership lost; 3 quarantined; 4 group cleanup unconfirmed. */
    public int getProcessExitFailureReason() { return mProcessExitFailureReason; }

    private synchronized void recordInputFailure(int reason) {
        if (mInputWriteFailureReason == 0) mInputWriteFailureReason = reason;
    }

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /**
     * A queue written to from a separate thread when the process outputs, and read by main thread to process by
     * terminal emulator.
     */
    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(4096);
    /**
     * A queue written to from the main thread due to user interaction, and read by another thread which forwards by
     * writing through the native-owned PTY handle.
     */
    final ByteQueue mTerminalToProcessIOQueue = new ByteQueue(4096);
    /** Buffer to write translate code points into utf8 before writing to mTerminalToProcessIOQueue */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Callback which gets notified when a session finishes or changes title. */
    TerminalSessionClient mClient;

    /** The pid of the shell process. 0 if not started and -1 if finished running. */
    volatile int mShellPid;
    /** Immutable successful-spawn receipt; never use this historical PID to signal a process. */
    private int mSpawnedPid;

    public int getSpawnedPid() { return mSpawnedPid; }

    /** The exit status of the shell process. Only valid if ${@link #mShellPid} is -1. */
    int mShellExitStatus;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    private final String mShellPath;
    private final String mCwd;
    private final String[] mArgs;
    private final String[] mEnv;
    private final Integer mTranscriptRows;


    private static final String LOG_TAG = "TerminalSession";

    public TerminalSession(String shellPath, String cwd, String[] args, String[] env, Integer transcriptRows, TerminalSessionClient client) {
        this.mShellPath = shellPath;
        this.mCwd = cwd;
        this.mArgs = args;
        this.mEnv = env;
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /** Inform the attached pty of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows);
        } else {
            mEmulator.resize(columns, rows);
            TerminalProcessOwner owner = mOwner;
            if (owner != null && owner.handle() > 0)
                JNI.resizeOwnedPty(owner.handle(), mEmulator.mRows, mEmulator.mColumns);
        }
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /**
     * Set the terminal emulator's window size and start terminal emulation.
     *
     * @param columns The number of columns in the terminal window.
     * @param rows    The number of rows in the terminal window.
     */
    public void initializeEmulator(int columns, int rows) {
        synchronized (this) {
            if (mInitializationClaimed) throw new IllegalStateException("Terminal startup already claimed");
            mInitializationClaimed = true;
        }
        mEmulator = new TerminalEmulator(this, columns, rows, mTranscriptRows, mClient);
        final TerminalProcessOwner owner = new TerminalProcessOwner(new NativeBackend(mEmulator.mRows, mEmulator.mColumns));
        mOwner = owner;
        // Allocate every worker, buffer, receipt and adapter before reserving or spawning a child.
        final byte[] readBuffer = new byte[TerminalPtyIo.MAX_CHUNK];
        final byte[] writeBuffer = new byte[TerminalPtyIo.MAX_CHUNK];
        final TerminalPtyIo.WriteReceipt writeReceipt = new TerminalPtyIo.WriteReceipt();
        final WriterBackend writerBackend = new WriterBackend(owner);
        final ReaderStopCondition readerStop = new ReaderStopCondition();
        final ReaderBackend readerBackend = new ReaderBackend(owner, readerStop);
        final TerminalPtyIo.ReadReceipt readReceipt = new TerminalPtyIo.ReadReceipt();
        final Thread waiter = new Thread("TermSessionWaiter") {
            @Override public void run() {
                long result = ((long) JNI.QUARANTINED) << 32;
                try {
                    owner.awaitProcess();
                    result = owner.waitForProcess();
                } finally {
                    // Publish stop/deadline before any main-Looper delivery or I/O-ready wait.
                    mStopWriter = true;
                    mReaderDeadlineNanos = System.nanoTime() + TerminalPtyIo.CHUNK_TIMEOUT_NANOS;
                    mTerminalToProcessIOQueue.close();
                    mProcessExitFailureReason = JNI.tag(result);
                    synchronized (TerminalSession.this) {
                        mShellPid = -1;
                        mShellExitStatus = (JNI.tag(result) == JNI.EXITED || JNI.tag(result) == JNI.EXITED_CLEANUP_UNCONFIRMED)
                            ? JNI.value(result) : 0;
                    }
                    try { owner.cancelIo(JNI.WRITE); }
                    finally { owner.releaseWaiter(); }
                    mMainThreadHandler.sendEmptyMessage(MSG_PROCESS_EXITED);
                }
            }
        };
        final Thread reader = new Thread("TermSessionInputReader") {
            @Override public void run() {
                boolean failed = false;
                try {
                    if (!owner.awaitIo()) return;
                    TerminalPtyIo.readUntilClosed(readerBackend, readBuffer, readReceipt);
                    failed = readReceipt.incomplete;
                } catch (Throwable failure) {
                    // Includes JNI copy exceptions after consumption: never replay those bytes.
                    failed = true;
                } finally {
                    if (failed) mOutputIncomplete = true;
                    try { owner.releaseLane(JNI.READ); }
                    finally { mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_READER_FINISHED, failed ? 1 : 0, 0)); }
                }
            }
        };
        final Thread writer = new Thread("TermSessionOutputWriter") {
            @Override public void run() {
                int staged = 0;
                try {
                    if (!owner.awaitIo()) return;
                    while (!mStopWriter) {
                        staged = mTerminalToProcessIOQueue.read(writeBuffer, true);
                        if (staged < 0) { staged = 0; break; }
                        TerminalPtyIo.writeChunk(writerBackend, writeBuffer, staged, writeReceipt);
                        if (writeReceipt.failure != 0) { recordInputFailure(writeReceipt.failure); break; }
                        staged = 0;
                    }
                } catch (Throwable failure) {
                    recordInputFailure(TerminalPtyIo.FAILURE_EXCEPTION);
                } finally {
                    mStopWriter = true;
                    mTerminalToProcessIOQueue.close();
                    if (staged > writeReceipt.confirmed || mTerminalToProcessIOQueue.pendingBytes() > 0)
                        recordInputFailure(TerminalPtyIo.FAILURE_CANCELLED);
                    // A completion cannot be loss-free until an admitted write has settled here.
                    try { owner.releaseLane(JNI.WRITE); }
                    finally { mWriterFinished = true; }
                    mMainThreadHandler.sendEmptyMessage(MSG_WRITER_FINISHED);
                    InputHandler input = mInputHandler;
                    if (input != null && mInputWriteFailureReason != 0) {
                        try { input.onPtyWriteFailure(mInputWriteFailureReason); }
                        catch (Throwable ignored) { /* Failure receipt and lane release already committed. */ }
                    }
                }
            }
        };
        if (mStopWriter) owner.stop();
        owner.start(waiter::start, reader::start, writer::start);
    }

    private final class NativeBackend implements TerminalProcessOwner.Backend {
        private final int rows, columns;
        NativeBackend(int rows, int columns) { this.rows = rows; this.columns = columns; }
        public int apiVersion() { return JNI.ownedProcessApiVersion(); }
        public long reserve() { return JNI.reserveOwnedProcess(); }
        public int spawn(long owner) {
            int pid = JNI.createOwnedSubprocess(owner, mShellPath, mCwd, mArgs, mEnv, rows, columns);
            // Before the helper's process handoff; a fast waiter can never be overwritten later.
            mShellPid = pid;
            mSpawnedPid = pid;
            return pid;
        }
        public long waitFor(long owner) { return JNI.waitForOwnedProcess(owner); }
        public int stop(long owner) { return JNI.requestOwnedStop(owner, JNI.ANCHORED_SESSION_GROUP); }
        public void cancelIo(long owner, int lanes) { JNI.cancelOwnedPtyIo(owner, lanes); }
        public void release(long owner) { JNI.releaseOwnedProcess(owner); }
        public void releaseLane(long owner, int lane) { JNI.releaseOwnedPtyLane(owner, lane); }
    }

    private final class WriterBackend implements TerminalPtyIo.Backend {
        private final TerminalProcessOwner owner;
        WriterBackend(TerminalProcessOwner owner) { this.owner = owner; }
        public long write(byte[] bytes, int offset, int count) { return JNI.writeOwnedPtyOnce(owner.handle(), bytes, offset, count); }
        public long poll(int millis) { return JNI.pollOwnedPtyOnce(owner.handle(), JNI.WRITE, millis); }
        public long nanoTime() { return System.nanoTime(); }
        public boolean cancelled() { return mStopWriter || owner.isStopped(); }
        public void backoff(int millis) throws InterruptedException { Thread.sleep(millis); }
    }

    private final class ReaderBackend implements TerminalPtyIo.ReaderBackend {
        private final TerminalProcessOwner owner;
        private final ReaderStopCondition stop;
        ReaderBackend(TerminalProcessOwner owner, ReaderStopCondition stop) { this.owner = owner; this.stop = stop; }
        public long read(byte[] bytes) { return JNI.readOwnedPtyOnce(owner.handle(), bytes, 0, bytes.length); }
        public long poll(int millis) { return JNI.pollOwnedPtyOnce(owner.handle(), JNI.READ, millis); }
        public boolean stopped() { return stop.shouldStop(); }
        public boolean enqueue(byte[] bytes, int count) { return mProcessToTerminalIOQueue.writeUntil(bytes, 0, count, stop); }
        public void queued() { mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT); }
        public void backoff(int millis) throws InterruptedException { Thread.sleep(millis); }
    }

    private final class ReaderStopCondition implements ByteQueue.StopCondition {
        public boolean shouldStop() {
            long deadline = mReaderDeadlineNanos;
            return mStopReader || (deadline != 0 && System.nanoTime() - deadline >= 0);
        }
    }

    /**
     * Install a non-blocking input owner once for a terminal session.
     * The handler must copy the requested range before returning because code-point input reuses a buffer.
     * Droide installs this for both human and Agent sessions; unset retains upstream behavior.
     */
    public synchronized void setInputHandler(InputHandler handler) {
        if (handler == null) throw new IllegalArgumentException("Input handler must not be null");
        if (mInputHandler != null) throw new IllegalStateException("Input handler is already installed");
        mInputHandler = handler;
    }

    /** Write data to the shell process through the optional non-blocking input owner. */
    @Override
    public void write(byte[] data, int offset, int count) {
        InputHandler handler = mInputHandler;
        if (handler != null) {
            handler.write(data, offset, count);
        } else {
            writeToPty(data, offset, count);
        }
    }

    /**
     * Forward already-owned input to the original blocking queue from the sole input worker.
     * Sessions with an input owner must never use this path on Android's main thread.
     */
    public boolean writeToPty(byte[] data, int offset, int count) {
        if (mInputHandler != null && Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("Owned PTY input must be forwarded off the main thread");
        }
        return mShellPid > 0 && mTerminalToProcessIOQueue.write(data, offset, count);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    /** Request termination only through this library's generation-checked ownership. */
    public void finishIfRunning() {
        mStopWriter = true;
        mTerminalToProcessIOQueue.close();
        TerminalProcessOwner owner = mOwner;
        if (owner != null) {
            int outcome = owner.stop();
            if (outcome >= 2) mClient.logWarn(LOG_TAG, "Native PTY stop cleanup is unconfirmed (reason " + outcome + ")");
        }
    }

    /** UI cleanup retires admissions; workers own their native lane releases. Never close an fd here. */
    void cleanupResources(int exitStatus) {
        synchronized (this) { mShellPid = -1; mShellExitStatus = exitStatus; }
        mStopReader = true;
        mStopWriter = true;
        mTerminalToProcessIOQueue.close();
        mProcessToTerminalIOQueue.close();
        TerminalProcessOwner owner = mOwner;
        if (owner != null) owner.cancelIo(JNI.READ | JNI.WRITE);
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        return mShellPid != -1;
    }

    /** Only valid if not {@link #isRunning()}. */
    public synchronized int getExitStatus() {
        return mShellExitStatus;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    public int getPid() {
        return mShellPid;
    }

    /** Returns the shell's working directory or null if it was unavailable. */
    public String getCwd() {
        if (mShellPid < 1) {
            return null;
        }
        try {
            final String cwdSymlink = String.format("/proc/%s/cwd/", mShellPid);
            String outputPath = new File(cwdSymlink).getCanonicalPath();
            String outputPathWithTrailingSlash = outputPath;
            if (!outputPath.endsWith("/")) {
                outputPathWithTrailingSlash += '/';
            }
            if (!cwdSymlink.equals(outputPathWithTrailingSlash)) {
                return outputPath;
            }
        } catch (IOException | SecurityException e) {
            mClient.logStackTraceWithMessage(LOG_TAG, "Error getting current directory", e);
        }
        return null;
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[4 * 1024];
        int mReceiveOffset;
        int mReceiveLength;

        @Override
        public void handleMessage(Message msg) {
            if (mCompletionStarted) return;
            if (msg.what == MSG_PROCESS_EXITED) {
                mProcessExited = true;
                synchronized (TerminalSession.this) {
                    // Numeric PID is diagnostic only; native wait receipt may report ownership loss.
                    mShellPid = -1;
                }
                mTerminalToProcessIOQueue.close();
                sendEmptyMessageDelayed(MSG_DRAIN_TIMEOUT, EXIT_DRAIN_TIMEOUT_MILLIS);
            } else if (msg.what == MSG_WRITER_FINISHED) {
                mWriterFinished = true;
            } else if (msg.what == MSG_READER_FINISHED) {
                mReaderFinished = true;
                if (msg.arg1 != 0) mOutputIncomplete = true;
                if (msg.arg1 != 0) mClient.logError(LOG_TAG, "PTY reader failed; output is incomplete and the child may still be running");
                mProcessToTerminalIOQueue.closeForDrain();
            } else if (msg.what == MSG_DRAIN_TIMEOUT && mProcessExited) {
                // Bounded fallback for inherited/open PTYs or an enormous pending expansion.
                // This is an explicit incomplete-output receipt, never a successful full drain.
                mOutputIncomplete = true;
                if (!mWriterFinished) recordInputFailure(TerminalPtyIo.FAILURE_UNCONFIRMED);
                mStopReader = true;
                mProcessToTerminalIOQueue.close();
                if (mEmulator != null) mEmulator.cancelPendingInputWork();
                finishCompletion();
                return;
            }

            boolean queueEmpty = false;
            if (mReceiveOffset == mReceiveLength) {
                mReceiveLength = Math.max(0, mProcessToTerminalIOQueue.read(mReceiveBuffer, false));
                mReceiveOffset = 0;
                queueEmpty = mReceiveLength == 0;
            }
            if (mEmulator != null && (mReceiveOffset < mReceiveLength || mEmulator.hasPendingInputWork())) {
                mReceiveOffset += mEmulator.appendBounded(mReceiveBuffer, mReceiveOffset,
                    mReceiveLength - mReceiveOffset, TerminalEmulator.INPUT_WORK_BUDGET);
                notifyScreenUpdate();
            }
            boolean pending = mReceiveOffset < mReceiveLength || (mEmulator != null && mEmulator.hasPendingInputWork());
            if (mReaderFinished && mOutputIncomplete && !mProcessExited && queueEmpty && !pending
                && !mReaderFailureNoticeSent && mEmulator != null) {
                mReaderFailureNoticeSent = true;
                mEmulator.finishInput();
                byte[] notice = "\r\n[PTY reader failed; output incomplete; process may still be running]\r\n".getBytes(StandardCharsets.UTF_8);
                mEmulator.append(notice, notice.length);
                notifyScreenUpdate();
            }
            if (mProcessExited && mReaderFinished && mWriterFinished && queueEmpty && !pending) {
                finishCompletion();
            } else if (pending || !queueEmpty) {
                // At most one self-scheduled continuation; yield to UI, stop and timeout messages.
                removeMessages(MSG_NEW_INPUT);
                sendEmptyMessage(MSG_NEW_INPUT);
            }
        }

        private void finishCompletion() {
            if (mCompletionStarted) return;
            mCompletionStarted = true;
            removeMessages(MSG_NEW_INPUT);
            removeMessages(MSG_DRAIN_TIMEOUT);
            cleanupResources(mShellExitStatus);
            if (mEmulator != null) {
                if (mEmulator.finishInput()) mOutputIncomplete = true;
                String exitDescription = mProcessExitFailureReason == 0 || mProcessExitFailureReason == 4
                    ? "\r\n[Process completed" : "\r\n[Process cleanup unconfirmed (reason " + mProcessExitFailureReason + ")";
                if (mShellExitStatus > 0) exitDescription += " (code " + mShellExitStatus + ")";
                else if (mShellExitStatus < 0) exitDescription += " (signal " + (-mShellExitStatus) + ")";
                if (mOutputIncomplete) exitDescription += "; output incomplete";
                if (mInputWriteFailureReason != 0) exitDescription += "; input incomplete/unconfirmed (reason " + mInputWriteFailureReason + ")";
                if (mProcessExitFailureReason == 4) exitDescription += "; anchored group cleanup unconfirmed";
                exitDescription += " - press Enter]";
                byte[] bytes = exitDescription.getBytes(StandardCharsets.UTF_8);
                mEmulator.append(bytes, bytes.length);
                notifyScreenUpdate();
            }
            mCompletionDelivered = true;
            mClient.onSessionFinished(TerminalSession.this);
        }
    }
}
