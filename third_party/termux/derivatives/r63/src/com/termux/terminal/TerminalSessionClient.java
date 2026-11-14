package com.termux.terminal;

/**
 * The interface for communication between {@link TerminalSession} and its client. It is used to
 * send callbacks to the client when {@link TerminalSession} changes or for sending other
 * back data to the client like logs.
 */
public interface TerminalSessionClient {

    void onTextChanged(TerminalSession changedSession);

    void onTitleChanged(TerminalSession changedSession);

    void onSessionFinished(TerminalSession finishedSession);

    /**
     * Observational checked-PTY receipt. Status: 1 accepted, 2 ioctl failed, 3 unavailable, 4 closed.
     * Requested dimensions can differ from the emulator's memory-budget-normalized dimensions.
     * Accepted dimensions are the last genuinely accepted spawn/resize geometry for this owner;
     * 0/0 means unknown, and matching emulator dimensions alone never imply PTY acceptance.
     * This is not a TIOCGWINSZ readback or a device/runtime validation claim.
     * Generation is stable per session owner. Request ID is stable across unchanged-geometry retries
     * and changes when the requested or emulator-normalized geometry changes (including A-to-B-to-A).
     * State is committed before this callback; throwing or same-request reentry cannot trigger retry.
     * Nested notifications are suppressed. The session performs no automatic retry or scheduling.
     * Only existing TerminalSession and primitive types appear so an old-AAR Java bridge can declare
     * this method without an Override annotation and receive it when the additive derivative is used.
     */
    default void onPtyResizeResult(TerminalSession session, long generation, long requestId,
            int requestedColumns, int requestedRows, int emulatorColumns, int emulatorRows,
            int acceptedColumns, int acceptedRows, int status) {}

    void onCopyTextToClipboard(TerminalSession session, String text);

    void onPasteTextFromClipboard(TerminalSession session);

    void onBell(TerminalSession session);

    void onColorsChanged(TerminalSession session);

    void onTerminalCursorStateChange(boolean state);



    Integer getTerminalCursorStyle();



    void logError(String tag, String message);

    void logWarn(String tag, String message);

    void logInfo(String tag, String message);

    void logDebug(String tag, String message);

    void logVerbose(String tag, String message);

    void logStackTraceWithMessage(String tag, String message, Exception e);

    void logStackTrace(String tag, Exception e);

}
