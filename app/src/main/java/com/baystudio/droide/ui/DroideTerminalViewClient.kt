package com.baystudio.droide.ui

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import com.baystudio.droide.core.TerminalLinks
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

 
internal class DroideTerminalViewClient(
    private val context: Context,
    private val viewProvider: () -> TerminalView?,
    initialTextSizeSp: Float = NativeTerminalTextSize.DEFAULT_SP,
    private val accessoryModifiers: AccessoryModifierController? = null,
    private val inputViewProvider: () -> View? = { viewProvider() },
    private val onCommandInput: () -> Unit = {},
    private val onGeometryChanged: () -> Unit = {},
) : TerminalViewClient {
    private var textSizeSp = initialTextSizeSp
    // Confirmed single taps can arrive after a view has been replaced. A mutable
    // Compose reference must never redirect an old gesture to the new terminal.
    private val originatingView = requireNotNull(viewProvider()) { "Terminal view must exist before its client" }
    private fun currentView(): TerminalView? = viewProvider()?.takeIf { it === originatingView }

    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            textSizeSp = (textSizeSp + if (scale > 1f) 1f else -1f)
                .coerceIn(NativeTerminalTextSize.MIN_SP, NativeTerminalTextSize.MAX_SP)
            currentView()?.let {
                it.setTextSize(nativeTerminalTextSizePixels(context, textSizeSp))
                onGeometryChanged()
            }
            return 1f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent) {
        val terminal = currentView()?.takeIf {
            it.isAttachedToWindow && it.isShown && it.windowVisibility == View.VISIBLE && it.hasWindowFocus()
        } ?: return
        if (e.x >= 0 && e.y >= 0 && e.x < terminal.width && e.y < terminal.height) {
            val emulator = terminal.mEmulator
            val renderer = terminal.mRenderer
            if (emulator != null && renderer != null) {
                val cell = terminal.getColumnAndRow(e, true)
                // Upstream truncates negative fractions toward zero, so the
                // blank top inset otherwise looks like row zero. Use its public
                // coordinate API one full row lower to distinguish real cells.
                val oneRowBelow = MotionEvent.obtain(e)
                val insideRow = try {
                    oneRowBelow.offsetLocation(0f, renderer.fontLineSpacing.toFloat())
                    terminal.getColumnAndRow(oneRowBelow, true)[1] == cell[1] + 1
                } finally { oneRowBelow.recycle() }
                if (insideRow) {
                    TerminalLinks.atCell(emulator, cell[0], cell[1])?.let { url ->
                        TerminalLinkOpener.open(context, url)
                        return
                    }
                }
            }
        }
        inputViewProvider()?.let { view ->
            view.requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    // DroideNativeTerminalView applies the user's valid IME profile after connection creation.
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {
        if (!copyMode && currentView() != null) {
            // Termux ends selection before its tap callback; restore the IME owner without stealing external focus.
            inputViewProvider()?.takeIf { it.hasFocus() }?.requestFocus()
        }
    }
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) onCommandInput()
        return false
    }
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
        accessoryModifiers?.consumeArmed()
        return false
    }
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = accessoryModifiers?.isActive(AccessoryModifier.CTRL) == true
    override fun readAltKey(): Boolean = accessoryModifiers?.isActive(AccessoryModifier.ALT) == true
    override fun readShiftKey(): Boolean = accessoryModifiers?.isActive(AccessoryModifier.SHIFT) == true
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        if (codePoint == 10 || codePoint == 13 || ctrlDown && codePoint in listOf(74, 77, 106, 109)) onCommandInput()
        accessoryModifiers?.consumeArmed()
        return false
    }

    override fun onEmulatorSet() {
        currentView()?.takeIf { it.isAttachedToWindow && it.windowVisibility == View.VISIBLE }?.let { view ->
            view.setTerminalCursorBlinkerRate(600)
            view.setTerminalCursorBlinkerState(true, true)
        }
    }

    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, e.message ?: "terminal view error", e) }
}
