package com.baystudio.droide.ui

import android.content.Context
import android.view.ActionMode
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import com.baystudio.droide.core.CodingKeyboardMode
import com.baystudio.droide.core.TerminalGeometrySnapshot
import com.baystudio.droide.core.TerminalResizeReceipt
import com.baystudio.droide.core.TerminalViewportGeometry
import com.baystudio.droide.core.TerminalUiUpdateDispatcher
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

// The upstream TerminalView is final; this container owns IME metadata and delegates PTY input.
internal class DroideNativeTerminalView(context: Context) : FrameLayout(context) {
    val terminalView = TerminalView(context, null)
    private val codingKeyboard = CodingKeyboardController()
    private val imeOwner = TerminalImeOwner()
    private var hadInputFocus = false
    private var released = false
    private var clipboardGeneration = 0L
    private val middleMousePaste = NativeTerminalMiddleMousePaste()
    private var geometryReady = false
    private var geometryPresented = false
    var viewportChanged: ((TerminalSession, TerminalViewportGeometry) -> Unit)? = null
    private var geometryReceipt: TerminalResizeReceipt? = null
    private val resizeRetry = TerminalResizeRetry { action ->
        val callback = Runnable(action)
        postOnAnimation(callback)
        val cancel = { removeCallbacks(callback); Unit }
        cancel
    }
    var inputFocusChanged: ((Boolean) -> Unit)? = null
    var copySelection: ((TerminalSession, String) -> Boolean)? = null
    private var focusObserver: ViewTreeObserver? = null
    private val screenUpdates = TerminalUiUpdateDispatcher(
        schedule = { action ->
            val callback = Runnable(action)
            postOnAnimation(callback)
            val cancel = { removeCallbacks(callback); Unit }
            cancel
        },
        update = { if (isAttachedToWindow) terminalView.onScreenUpdated() },
    )
    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, _ ->
        val focused = isFocused
        if (hadInputFocus && !focused) imeOwner.invalidate()
        if (!hadInputFocus && focused) restartActiveInput()
        hadInputFocus = focused
        inputFocusChanged?.invoke(hasFocus())
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        // Preserve child focus: upstream text selection requires requestFocus(). This app-side adapter
        // guards the container-served IME route only.
        descendantFocusability = FOCUS_BEFORE_DESCENDANTS
        terminalView.isFocusable = true
        terminalView.isFocusableInTouchMode = true
        addView(terminalView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        geometryReady = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        screenUpdates.attach()
        resizeRetry.lifecycle(currentSession)
        geometryPresented = isShown && windowVisibility == VISIBLE
        reportViewport()
        considerResizeRetry()
        terminalView.setTerminalCursorBlinkerState(windowVisibility == VISIBLE, true)
        focusObserver = viewTreeObserver.also { it.addOnGlobalFocusChangeListener(focusListener) }
        hadInputFocus = isFocused
        inputFocusChanged?.invoke(hasFocus())
    }

    override fun onDetachedFromWindow() {
        clipboardGeneration++
        imeOwner.invalidate()
        resizeRetry.lifecycle(null)
        geometryPresented = false
        hadInputFocus = false
        screenUpdates.detach()
        terminalView.setTerminalCursorBlinkerState(false, false)
        focusObserver?.takeIf { it.isAlive }?.removeOnGlobalFocusChangeListener(focusListener)
        focusObserver = null
        inputFocusChanged?.invoke(false)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) { imeOwner.invalidate(); clipboardGeneration++ }
        terminalView.setTerminalCursorBlinkerState(isAttachedToWindow && visibility == VISIBLE, true)
        updateGeometryPresentation()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!isShown) { imeOwner.invalidate(); clipboardGeneration++ } else restartActiveInput()
        updateGeometryPresentation()
    }

    private fun updateGeometryPresentation() {
        if (!geometryReady || released) return
        val presented = isAttachedToWindow && isShown && windowVisibility == VISIBLE
        if (presented == geometryPresented) return
        geometryPresented = presented
        if (!presented) resizeRetry.suspendPresentation()
        else { resizeRetry.lifecycle(currentSession); reportViewport(); considerResizeRetry() }
    }

    fun release() {
        released = true
        clipboardGeneration++
        resizeRetry.lifecycle(null)
        viewportChanged = null
        imeOwner.invalidate()
        screenUpdates.detach()
        terminalView.setTerminalCursorBlinkerState(false, false)
        inputFocusChanged = null
        copySelection = null
    }

    val currentSession: TerminalSession? get() = terminalView.currentSession

    fun attachSession(session: TerminalSession): Boolean {
        if (currentSession === session) return false
        clipboardGeneration++
        imeOwner.invalidate()
        geometryReceipt = null
        resizeRetry.lifecycle(session)
        val changed = terminalView.attachSession(session)
        restartActiveInput()
        reportViewport()
        return changed
    }
    fun setTerminalViewClient(client: TerminalViewClient) = terminalView.setTerminalViewClient(client)
    fun setTextSize(size: Int) { terminalView.setTextSize(size); reportViewport() }
    fun setTerminalCursorBlinkerRate(rate: Int) = terminalView.setTerminalCursorBlinkerRate(rate)
    fun setTerminalCursorBlinkerState(start: Boolean, onlyIfEnabled: Boolean) =
        terminalView.setTerminalCursorBlinkerState(start, onlyIfEnabled)
    fun onScreenUpdated() = screenUpdates.request()
    fun inputCodePoint(codePoint: Int, ctrlDown: Boolean, altDown: Boolean) =
        terminalView.inputCodePoint(codePoint, ctrlDown, altDown)

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        reportViewport()
        considerResizeRetry()
    }

    private fun viewport(): TerminalViewportGeometry? {
        val renderer = terminalView.mRenderer ?: return null
        val model = currentSession?.emulator ?: return null
        val width = terminalView.width
        val height = terminalView.height
        val cell = renderer.fontWidth
        val line = renderer.fontLineSpacing
        if (width <= 0 || height <= 0 || !cell.isFinite() || cell <= 0f || line <= 0) return null
        return TerminalViewportGeometry(width, height, cell, line,
            (width / cell).toInt().coerceAtLeast(4),
            com.termux.view.DroideViewportMetrics.rows(renderer, height),
            model.mColumns, model.mRows)
    }

    fun reportViewport() {
        if (!geometryReady || released) return
        val session = currentSession ?: return
        val size = viewport() ?: return
        viewportChanged?.invoke(session, size)
    }

    fun updateGeometry(snapshot: TerminalGeometrySnapshot?) {
        geometryReceipt = snapshot?.takeIf { it.owner === currentSession }?.receipt
        considerResizeRetry()
    }

    private fun considerResizeRetry() {
        if (!geometryReady || released) return
        val session = currentSession ?: return
        val receipt = geometryReceipt ?: return
        fun eligible() = !released && isAttachedToWindow && isShown && windowVisibility == VISIBLE &&
            currentSession === session && session.isRunning
        resizeRetry.offer(session, receipt, ::eligible, stillLatest = {
            val latest = geometryReceipt
            val size = viewport()
            latest == receipt && size != null && size.expectedColumns == receipt.requestedColumns &&
                size.expectedRows == receipt.requestedRows
        }) {
            val size = viewport() ?: return@offer

            session.updateSize(size.expectedColumns, size.expectedRows)
            reportViewport()
        }
    }

    fun applyKeyboardMode(mode: CodingKeyboardMode) = codingKeyboard.applyMode(this, mode)

    override fun onCheckIsTextEditor(): Boolean = true

    override fun checkInputConnectionProxy(view: View): Boolean =
        view === terminalView || super.checkInputConnectionProxy(view)

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val session = currentSession ?: return null
        if (released) return null
        val upstream = terminalView.onCreateInputConnection(outAttrs) ?: return null
        val lease = imeOwner.connect(session, { currentSession }) {
            !released && isAttachedToWindow && isShown && isFocused && hasWindowFocus() && windowVisibility == VISIBLE
        }
        val connection = NativeTerminalInputConnection(this, lease,
            commitToTerminal = { upstream.commitText(it, 1) },
            dispatchToTerminal = { event ->
                when (event.action) {
                    KeyEvent.ACTION_DOWN, KeyEvent.ACTION_MULTIPLE -> terminalView.onKeyDown(event.keyCode, event)
                    KeyEvent.ACTION_UP -> terminalView.onKeyUp(event.keyCode, event)
                    else -> false
                }
            })
        codingKeyboard.configure(connection, outAttrs, multiline = false)
        return connection
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) { imeOwner.invalidate(); clipboardGeneration++ } else restartActiveInput()
    }

    private fun restartActiveInput() {
        if (released || !isAttachedToWindow || !isShown || !isFocused || !hasWindowFocus()) return
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        if (manager.isActive(this)) manager.restartInput(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        terminalView.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        terminalView.onKeyUp(keyCode, event) || super.onKeyUp(keyCode, event)

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean =
        terminalView.onKeyPreIme(keyCode, event) || super.onKeyPreIme(keyCode, event)

    override fun startActionModeForChild(
        originalView: View,
        callback: ActionMode.Callback,
        type: Int,
    ): ActionMode? {
        val session = currentSession
        val emulator = terminalView.mEmulator
        if (originalView !== terminalView || type != ActionMode.TYPE_FLOATING ||
            session == null || emulator == null) {
            return super.startActionModeForChild(originalView, callback, type)
        }
        val generation = ++clipboardGeneration
        val copyForSession = copySelection
        val wrapped = NativeTerminalSelectionCallback(
            callback,
            isCurrent = {
                generation == clipboardGeneration && clipboardViewIsCurrent(session) &&
                    terminalView.mEmulator === emulator && copySelection === copyForSession
            },
            isSelecting = { terminalView.isSelectingText },
            selectedText = { com.termux.view.DroideSelectionText.getSelectedText(terminalView) },
            copy = { text ->
                if (copyForSession != null) copyForSession(session, text)
                else {
                    Toast.makeText(context, "Copy is unavailable for this terminal", Toast.LENGTH_SHORT).show()
                    false
                }
            },
            stopSelection = { terminalView.stopTextSelectionMode() },
        )
        return super.startActionModeForChild(originalView, wrapped, type)
    }

    private fun clipboardViewIsCurrent(session: TerminalSession): Boolean =
        !released && isAttachedToWindow && isShown && hasWindowFocus() && windowVisibility == VISIBLE &&
            terminalView.parent === this && terminalView.isShown && currentSession === session

    private fun routeMiddleMousePaste(event: MotionEvent): Boolean =
        middleMousePaste.handle(event, terminalView.isSelectingText) {
            val session = currentSession
            if (session != null && clipboardViewIsCurrent(session) && terminalView.mEmulator != null) {
                // Never use TerminalView's direct emulator.paste path: the owner admits one safe paste.
                session.onPasteTextFromClipboard()
            }
        }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        routeMiddleMousePaste(event) || super.dispatchTouchEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        routeMiddleMousePaste(event) || super.dispatchGenericMotionEvent(event)

    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        terminalView.onGenericMotionEvent(event) || super.onGenericMotionEvent(event)
}
