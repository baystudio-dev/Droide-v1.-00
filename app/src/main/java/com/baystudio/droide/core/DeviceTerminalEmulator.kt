package com.baystudio.droide.core

import android.graphics.Canvas
import android.os.Looper
import java.util.concurrent.CancellationException
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow









class DeviceTerminalEmulator(
    private val writeToPty: (ByteArray) -> Unit,
    columns: Int = 120,
    rows: Int = 32,
    // Production captures an exact relay here; a reconnect may reject but never reroute a paste.
    private val pasteAdmission: (() -> ((ByteArray) -> Boolean)?)? = null,

) {
    interface UiListener {
        fun onScreenChanged()
        fun onTitleChanged(title: String?) = Unit
        fun onCopyTextRequested(text: String) = Unit
        fun onPasteRequested() = Unit
        fun onBell() = Unit
        fun onInputRejected(message: String) = Unit
    }

    data class Selection(val startX: Int, val startY: Int, val endX: Int, val endY: Int)

    private val lock = Any()
    @Volatile private var listener: UiListener? = null
    private val _title = MutableStateFlow<String?>(null)
    val title: StateFlow<String?> = _title.asStateFlow()
    private val _workingDirectory = MutableStateFlow<String?>(null)
    val workingDirectory: StateFlow<String?> = _workingDirectory.asStateFlow()
    internal val commandTracker = TerminalCommandActivityTracker()
    val commandActivity = commandTracker.state

    private val pasteTransaction = TerminalPasteTransaction(
        send = { bytes, offset, count ->
            if (count > 0) writeToPty(bytes.copyOfRange(offset, offset + count))
            true
        },
        onRejected = { listener?.onInputRejected(it) },
    )
    private val output = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {
            pasteTransaction.write(data, offset, count)
        }

        override fun titleChanged(oldTitle: String?, newTitle: String?) {
            if (commandTracker.consumeTitle(newTitle)) {
                _title.value = null
                listener?.onTitleChanged(null)
                return
            } else if (newTitle?.startsWith(TerminalPromptPresentation.TITLE_PREFIX) == true) {
                TerminalPromptPresentation.directoryFromTitle(newTitle)?.let { _workingDirectory.value = it }
                _title.value = null
                listener?.onTitleChanged(null)
                return
            }
            val displayTitle = TerminalTitlePolicy.display(newTitle)
            _title.value = displayTitle
            listener?.onTitleChanged(displayTitle)
        }

        override fun onCopyTextToClipboard(text: String) {
            listener?.onCopyTextRequested(text)
        }

        override fun onPasteTextFromClipboard() {
            listener?.onPasteRequested()
        }

        override fun onBell() {
            listener?.onBell()
        }

        override fun onColorsChanged() {
            listener?.onScreenChanged()
        }
    }

    fun initializeWorkingDirectory(path: String) {
        if (_workingDirectory.value == null) _workingDirectory.value = path
    }

    private val client = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) = Unit
        override fun onTitleChanged(changedSession: TerminalSession) = Unit
        override fun onSessionFinished(finishedSession: TerminalSession) = Unit
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) = Unit
        override fun onPasteTextFromClipboard(session: TerminalSession) = Unit
        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) { listener?.onScreenChanged() }
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String, message: String) = Unit
        override fun logWarn(tag: String, message: String) = Unit
        override fun logInfo(tag: String, message: String) = Unit
        override fun logDebug(tag: String, message: String) = Unit
        override fun logVerbose(tag: String, message: String) = Unit
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Unit
        override fun logStackTrace(tag: String, e: Exception) = Unit
    }

    private val emulator = TerminalEmulator(output, columns.coerceAtLeast(2), rows.coerceAtLeast(2), 10_000, client).apply {
        setCursorBlinkingEnabled(true)
        setCursorBlinkState(true)
    }

    fun setUiListener(value: UiListener?) {
        synchronized(lock) { listener = value }
        value?.onScreenChanged()
    }

    fun clearUiListener(owner: UiListener) {
        synchronized(lock) { if (listener === owner) listener = null }
    }

    /** Stream readers provide their cancellation check and never hold the render lock across slices. */
    fun append(bytes: ByteArray, ensureActive: () -> Unit = {}) {
        if (bytes.isEmpty()) return
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Device terminal stream must be decoded by its background reader"
        }
        var offset = 0
        try {
            do {
                ensureActive()
                if (Thread.currentThread().isInterrupted) throw CancellationException("Terminal reader interrupted")
                val pending = synchronized(lock) {
                    offset += emulator.appendBounded(bytes, offset, bytes.size - offset, TerminalEmulator.INPUT_WORK_BUDGET)
                    emulator.hasPendingInputWork()
                }
                // Rendering, selection and user cancellation may acquire the lock between every slice.
                if (offset >= bytes.size && !pending) break
                Thread.yield()
            } while (true)
        } catch (cancelled: CancellationException) {
            // A cancelled stream must not replay an old deferred REP into a later connection.
            synchronized(lock) { emulator.reset() }
            throw cancelled
        }
        listener?.onScreenChanged()
    }

    fun appendText(text: String) = append(text.encodeToByteArray())

    fun resize(columns: Int, rows: Int) {
        val safeColumns = columns.coerceAtLeast(2)
        val safeRows = rows.coerceAtLeast(2)
        val changed = synchronized(lock) {
            if (emulator.mColumns == safeColumns && emulator.mRows == safeRows) {
                false
            } else {
                emulator.resize(safeColumns, safeRows)
                true
            }
        }
        if (changed) listener?.onScreenChanged()
    }

    fun resetAndClear() {
        synchronized(lock) {
            emulator.reset()
            val clear = "\u001b[2J\u001b[3J\u001b[H".encodeToByteArray()
            emulator.append(clear, clear.size)
            emulator.clearScrollCounter()
        }
        listener?.onScreenChanged()
    }

    fun render(
        renderer: TerminalRenderer,
        canvas: Canvas,
        topRow: Int,
        selection: Selection?,
    ) {
        synchronized(lock) {
            val normalized = selection?.normalized()
            renderer.render(
                emulator,
                canvas,
                topRow.coerceIn(-emulator.screen.activeTranscriptRows, 0),
                normalized?.startY ?: -1,
                normalized?.endY ?: -1,
                normalized?.startX ?: -1,
                normalized?.endX ?: -1,
            )
        }
    }

    fun snapshotText(limit: Int = 300_000): String = synchronized(lock) {
        TerminalTranscriptSnapshot.capture(emulator, limit)
    }

    fun selectedText(selection: Selection): String = synchronized(lock) {
        val normalized = selection.normalized().clamped(emulator.mColumns, emulator.screen.activeTranscriptRows, emulator.mRows)
        emulator.getSelectedText(normalized.startX, normalized.startY, normalized.endX, normalized.endY)
    }

    fun activeTranscriptRows(): Int = synchronized(lock) { emulator.screen.activeTranscriptRows }
    fun linkAt(column: Int, row: Int): String? = synchronized(lock) { TerminalLinks.atCell(emulator, column, row) }
    fun selectedLink(selection: Selection): String? = synchronized(lock) {
        val range = selection.normalized().clamped(emulator.mColumns, emulator.screen.activeTranscriptRows, emulator.mRows)
        val first = TerminalLinks.atCell(emulator, range.startX, range.startY, explicitSelection = true)
        first?.takeIf { it == TerminalLinks.atCell(emulator, range.endX, range.endY, explicitSelection = true) }
    }
    fun columns(): Int = synchronized(lock) { emulator.mColumns }
    fun rows(): Int = synchronized(lock) { emulator.mRows }
    fun isAlternateBufferActive(): Boolean = synchronized(lock) { emulator.isAlternateBufferActive }
    fun isMouseTrackingActive(): Boolean = synchronized(lock) { emulator.isMouseTrackingActive }
    fun cursorKeysApplicationMode(): Boolean = synchronized(lock) { emulator.isCursorKeysApplicationMode }
    fun keypadApplicationMode(): Boolean = synchronized(lock) { emulator.isKeypadApplicationMode }

    fun consumeScrollCounter(): Int = synchronized(lock) {
        val value = emulator.scrollCounter
        emulator.clearScrollCounter()
        value
    }

    fun setCursorBlinkState(visible: Boolean) {
        synchronized(lock) { emulator.setCursorBlinkState(visible) }
        listener?.onScreenChanged()
    }

     
    fun resetThemeColors() {
        synchronized(lock) { emulator.mColors.reset() }
        listener?.onScreenChanged()
    }

    fun paste(text: String): Boolean {
        val captured = pasteAdmission?.invoke()
        if (pasteAdmission != null && captured == null) {
            listener?.onInputRejected("Terminal is unavailable; the whole paste was rejected")
            return false
        }
        val accepted = synchronized(lock) {
            pasteTransaction.paste(text, isCurrent = { true },
                admit = { bytes, offset, count ->
                    val owned = bytes.copyOfRange(offset, offset + count)
                    if (captured != null) captured(owned) else { writeToPty(owned); true }
                }, produce = emulator::paste)
        }
        if (accepted) setCursorBlinkState(true)
        return accepted
    }

    fun keySequence(keyCode: Int, keyModifiers: Int): String? = synchronized(lock) {
        KeyHandler.getCode(keyCode, keyModifiers, emulator.isCursorKeysApplicationMode, emulator.isKeypadApplicationMode)
    }

    fun sendMouseEvent(button: Int, column: Int, row: Int, pressed: Boolean) {
        synchronized(lock) { emulator.sendMouseEvent(button, column, row, pressed) }
    }

    private fun Selection.normalized(): Selection {
        return if (startY < endY || (startY == endY && startX <= endX)) this
        else Selection(endX, endY, startX, startY)
    }

    private fun Selection.clamped(columns: Int, transcriptRows: Int, visibleRows: Int): Selection = Selection(
        startX.coerceIn(0, columns - 1),
        startY.coerceIn(-transcriptRows, visibleRows - 1),
        endX.coerceIn(0, columns - 1),
        endY.coerceIn(-transcriptRows, visibleRows - 1),
    )
}
