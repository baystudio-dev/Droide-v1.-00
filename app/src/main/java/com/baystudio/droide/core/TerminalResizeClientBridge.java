package com.baystudio.droide.core;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

/** Statically declared additive callback; also compiles against the unchanged historical AAR. */
public final class TerminalResizeClientBridge implements TerminalSessionClient {
    public interface Listener {
        void onResult(TerminalSession session, long generation, long requestId,
            int requestedColumns, int requestedRows, int emulatorColumns, int emulatorRows,
            int acceptedColumns, int acceptedRows, int status);
    }
    private final TerminalSessionClient delegate;
    private final Listener listener;
    public TerminalResizeClientBridge(TerminalSessionClient delegate, Listener listener) {
        if (delegate == null || listener == null) throw new NullPointerException();
        this.delegate = delegate;
        this.listener = listener;
    }
    // Intentionally no @Override: old AAR clients retain their exact behavior, with no fabricated receipt.
    public void onPtyResizeResult(TerminalSession session, long generation, long requestId,
            int requestedColumns, int requestedRows, int emulatorColumns, int emulatorRows,
            int acceptedColumns, int acceptedRows, int status) {
        listener.onResult(session, generation, requestId, requestedColumns, requestedRows,
            emulatorColumns, emulatorRows, acceptedColumns, acceptedRows, status);
    }
    @Override public void onTextChanged(TerminalSession s) { delegate.onTextChanged(s); }
    @Override public void onTitleChanged(TerminalSession s) { delegate.onTitleChanged(s); }
    @Override public void onSessionFinished(TerminalSession s) { delegate.onSessionFinished(s); }
    @Override public void onCopyTextToClipboard(TerminalSession s, String text) { delegate.onCopyTextToClipboard(s, text); }
    @Override public void onPasteTextFromClipboard(TerminalSession s) { delegate.onPasteTextFromClipboard(s); }
    @Override public void onBell(TerminalSession s) { delegate.onBell(s); }
    @Override public void onColorsChanged(TerminalSession s) { delegate.onColorsChanged(s); }
    @Override public void onTerminalCursorStateChange(boolean state) { delegate.onTerminalCursorStateChange(state); }
    @Override public Integer getTerminalCursorStyle() { return delegate.getTerminalCursorStyle(); }
    @Override public void logError(String tag, String message) { delegate.logError(tag, message); }
    @Override public void logWarn(String tag, String message) { delegate.logWarn(tag, message); }
    @Override public void logInfo(String tag, String message) { delegate.logInfo(tag, message); }
    @Override public void logDebug(String tag, String message) { delegate.logDebug(tag, message); }
    @Override public void logVerbose(String tag, String message) { delegate.logVerbose(tag, message); }
    @Override public void logStackTraceWithMessage(String tag, String message, Exception e) { delegate.logStackTraceWithMessage(tag, message, e); }
    @Override public void logStackTrace(String tag, Exception e) { delegate.logStackTrace(tag, e); }
}
