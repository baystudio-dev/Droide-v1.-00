package com.baystudio.droide.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.util.TypedValue
import android.os.Bundle
import android.os.Looper
import android.text.InputType
import android.view.DragEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.baystudio.droide.core.CodeStyleProfile
import com.baystudio.droide.core.CodingKeyboardMode
import com.baystudio.droide.core.EditorInputInteractionPolicy
import com.baystudio.droide.core.EditorTextAdmission
import com.baystudio.droide.core.ProfessionalSmartTyping
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.LayoutStateChangeEvent
import io.github.rosemoe.sora.event.SubscriptionReceipt
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.util.IntPair
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import kotlin.math.abs
import kotlin.math.roundToInt


internal class DroideCodeEditor(
    context: Context,
    private val languageId: String,
    private val accessoryModifiers: AccessoryModifierController? = null,
    codeStyle: CodeStyleProfile,
) : CodeEditor(context) {

    private var codeStyle: CodeStyleProfile = codeStyle
    private val codingKeyboard = CodingKeyboardController()

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchMoved = false
    private var touchUsedMultiplePointers = false
    private var pendingImeFromUserTap = false
    private var dispatchingEditorTouch = false
    private var caretRevealPosted = false
    private var pendingViewport: EditorViewportSnapshot? = null
    private var viewportGeneration = 0L
    private var viewportLayoutBusy = false
    private var zoomResetRequested = false
    private val viewportLayoutSubscription = subscribeEvent(LayoutStateChangeEvent::class.java) { event, _ ->
        viewportLayoutBusy = event.isLayoutBusy
        if (!viewportLayoutBusy && zoomResetRequested) postInLifecycle {
            if (zoomResetRequested && acceptsFeatureUpdates) resetZoomPreservingViewport()
        }
    }

    internal fun captureViewport(): EditorViewportSnapshot = pendingViewport ?: EditorViewportSnapshot(
        offsetX.coerceAtLeast(0), offsetY.coerceAtLeast(0),
        if (Build.VERSION.SDK_INT >= 34) {
            TypedValue.deriveDimension(TypedValue.COMPLEX_UNIT_SP, textSizePx, resources.displayMetrics)
        } else textSizePx / resources.displayMetrics.scaledDensity,
    )

    internal fun restoreViewport(snapshot: EditorViewportSnapshot) {
        viewportGeneration++
        pendingViewport = snapshot
        invalidate()
    }

    internal fun cancelViewportRestore() { pendingViewport = null; zoomResetRequested = false }

    /** Change presentation only. The reading position, selection, content and undo stack stay owned by this view. */
    internal fun resetZoomPreservingViewport() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Editor zoom requires Main" }
        if (!acceptsFeatureUpdates || isFormatting) return
        if (viewportLayoutBusy) { zoomResetRequested = true; return }
        zoomResetRequested = false
        val defaultPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
            DefaultEditorTextSizeSp, resources.displayMetrics)
        if (abs(textSizePx - defaultPx) < .01f) return
        applyPendingViewport()
        val snapshot = captureViewport()
        val position = getPointPosition(measureTextRegionOffset() + snapshot.offsetX, snapshot.offsetY.toFloat())
        val line = IntPair.getFirst(position)
        val column = IntPair.getSecond(position)
        val offsets = layout.getCharLayoutOffset(line, column)
        val anchor = EditorZoomAnchor(line, column,
            (snapshot.offsetY - (offsets[0] - rowHeight)) / rowHeight,
            (snapshot.offsetX - offsets[1]) / textSizePx)
        // Fence deferred caret reveal before changing metrics. A tab switch during reflow
        // captures this anchor too, so it cannot restore old pixels at the new font size.
        restoreViewport(snapshot.copy(textSizeSp = DefaultEditorTextSizeSp, zoomAnchor = anchor))
        setTextSize(DefaultEditorTextSizeSp)
        applyPendingViewport()
    }

    private fun applyPendingViewport() {
        val snapshot = pendingViewport ?: return
        if (viewportLayoutBusy || width <= 0 || height <= 0) return
        val anchor = snapshot.zoomAnchor
        val resolved = if (anchor == null) snapshot else {
            val line = anchor.line.coerceIn(0, text.lineCount - 1)
            val column = anchor.column.coerceIn(0, text.getColumnCount(line))
            val offsets = layout.getCharLayoutOffset(line, column)
            snapshot.copy(
                offsetX = if (isWordwrap) 0 else (offsets[1] + anchor.horizontalEm * textSizePx).roundToInt(),
                offsetY = (offsets[0] - rowHeight + anchor.rowFraction * rowHeight).roundToInt(),
                zoomAnchor = null,
            )
        }
        val x = resolved.offsetX.coerceIn(0, scrollMaxX)
        val y = resolved.offsetY.coerceIn(0, scrollMaxY)
        scroller.forceFinished(true)
        scroller.startScroll(x, y, 0, 0, 0)
        scroller.abortAnimation()
        pendingViewport = null
    }
    internal var themeFingerprint: String? = null
    internal var onEditorFailure: ((Throwable) -> Unit)? = null
    @Volatile private var surfaceFailed = false
    @Volatile private var releaseStarted = false
    @Volatile private var nativeHandoff: NativeHandoff? = null
    private var retainedInputConnection: InputConnection? = null
    // These belong to this exact view, not to the earlier pane DisposableEffect cleanup.
    internal var nativeCapture: (() -> Unit)? = null
    internal var nativeContentSubscription: SubscriptionReceipt<ContentChangeEvent>? = null
    internal var nativeHandoffFailure: Throwable? = null
        private set
    private var nativeCaptureFailed = false
    internal var nativeUnexpectedDelta: Boolean = false
        private set
    internal val acceptsMutations: Boolean
        get() = nativeHandoff == null && !releaseStarted && !isReleased && !surfaceFailed
    internal val acceptsFeatureUpdates: Boolean get() = acceptsMutations
    internal val retainsNativeHandoff: Boolean get() = nativeHandoff != null

    /** Settle the existing delegate; creating another connection would reset IME state. */
    internal fun settleWorkspaceMutation() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Workspace mutation requires Main" }
        check(acceptsMutations) { "Native editor is unavailable for workspace mutation" }
        if (hasComposingText()) {
            try {
                check(retainedInputConnection?.finishComposingText() == true && !hasComposingText()) {
                    "Finish the current composition before applying this edit"
                }
            } finally { nativeCapture?.invoke() }
        }
        check(!text.isInBatchEdit) { "Native input batch is pending; retry this edit after input completes" }
    }

    override fun isEditable(): Boolean = acceptsMutations && super.isEditable()

    /** Main-thread, non-suspending admission. No policy setter or early native release. */
    internal fun beginProjectHandoff(onReleased: (Throwable?) -> Unit): EditorNativeHandoffLease {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Native handoff requires Main" }
        check(acceptsMutations) { "Native editor is unavailable for handoff" }
        val capture = checkNotNull(nativeCapture) { "Native capture is not bound" }
        // Never create another connection here: Sora resets composition and batch state there.
        if (hasComposingText()) {
            try {
                check(retainedInputConnection?.finishComposingText() == true && !hasComposingText()) {
                    "Native composition could not be finished"
                }
            } finally {
                capture()
            }
        }
        check(!text.isInBatchEdit) { "Native batch edit is still pending" }
        nativeHandoffFailure = null
        nativeCaptureFailed = false
        nativeUnexpectedDelta = false
        val lease = NativeHandoff(capture, onReleased)
        nativeHandoff = lease
        pendingImeFromUserTap = false
        try {
            capture()
            check(!releaseStarted && !isReleased && !surfaceFailed && nativeHandoffFailure == null) {
                "Native editor changed or failed during handoff capture"
            }
            return lease
        } catch (failure: Throwable) {
            lease.abort()
            throw failure
        }
    }

    private fun recordHandoffFailure(failure: Throwable) {
        val first = nativeHandoffFailure
        if (first == null) nativeHandoffFailure = failure
        else if (first !== failure) {
            // Retain the first failure even if collecting a secondary error cannot allocate.
            try { first.addSuppressed(failure) } catch (_: Throwable) { }
        }
    }

    private inner class NativeHandoff(
        capture: () -> Unit,
        onReleased: (Throwable?) -> Unit,
    ) : EditorNativeHandoffLease {
        var capture: (() -> Unit)? = capture
            private set
        private var onReleased: ((Throwable?) -> Unit)? = onReleased
        var committed = false
            private set
        private var aborted = false
        private var releaseFinished = false
        private var acknowledged = false
        private val deferredCallbacks = mutableListOf<Runnable>()

        fun defer(action: Runnable) {
            if (!committed && !aborted) deferredCallbacks.add(action)
        }

        fun remove(action: Runnable) { deferredCallbacks.removeAll { it === action } }

        override fun commit() {
            if (aborted || nativeHandoff !== this) return
            committed = true
            deferredCallbacks.clear()
            acknowledgeRelease()
        }

        override fun abort() {
            if (committed || aborted || nativeHandoff !== this) return
            aborted = true
            nativeHandoff = null
            // A release/failure remains sticky; removing this token cannot revive that view.
            if (acceptsFeatureUpdates) {
                nativeHandoffFailure = null
                nativeCaptureFailed = false
                nativeUnexpectedDelta = false
                // Rollback may follow immediately. Reposting must never make abort throw.
                var index = 0
                while (index < deferredCallbacks.size) {
                    try { postInLifecycle(deferredCallbacks[index]) }
                    catch (failure: Throwable) { recordHandoffFailure(failure) }
                    index++
                }
            }
            deferredCallbacks.clear()
            capture = null
            onReleased = null
        }

        fun released() {
            releaseFinished = true
            acknowledgeRelease()
        }

        private fun acknowledgeRelease() {
            if (!committed || !releaseFinished || acknowledged) return
            acknowledged = true
            try { onReleased?.invoke(nativeHandoffFailure) }
            catch (failure: Throwable) { recordHandoffFailure(failure) }
            finally {
                // releaseStarted remains the permanent gate. A held view/IC need not keep
                // the old coordinator/model alive through this completed callback lease.
                capture = null
                onReleased = null
                if (nativeHandoff === this) nativeHandoff = null
            }
        }
    }

    private fun deliverLifecycleCallback(action: Runnable) {
        val handoff = nativeHandoff
        if (handoff != null) {
            // Preserve callbacks while admission is reversible; discard only after commit.
            handoff.defer(action)
        } else if (acceptsFeatureUpdates) {
            runUiCatching(onError = ::reportSurfaceFailure) { action.run() }
        }
    }
    // Superclass initialization may call the overridden posting methods. A lateinit backing
    // field has no initializer that could overwrite a queue created during super construction.
    @Volatile private lateinit var callbackQueue: EditorCallbackQueue
    @Synchronized private fun callbacks(): EditorCallbackQueue {
        if (!::callbackQueue.isInitialized) callbackQueue = EditorCallbackQueue()
        return callbackQueue
    }
    internal val pendingEditorCallbackCount: Int
        get() = if (::callbackQueue.isInitialized) callbackQueue.pendingCount else 0

    internal fun reportSurfaceFailure(failure: Throwable) {
        if (surfaceFailed || isReleased) return
        surfaceFailed = true
        if (nativeHandoff != null) recordHandoffFailure(failure)
        pendingImeFromUserTap = false
        parent?.requestDisallowInterceptTouchEvent(false)
        val callback = onEditorFailure ?: throw failure
        // Report outside drawing/touch dispatch so Compose can remove this surface safely.
        callbacks().post(Runnable { if (!isReleased) callback(failure) }) { it.run() }
    }

    override fun postInLifecycle(action: Runnable): Boolean {
        if (releaseStarted || isReleased || surfaceFailed) return false
        return callbacks().post(action, run = ::deliverLifecycleCallback)
    }

    override fun postDelayedInLifecycle(action: Runnable, delayMillis: Long): Boolean {
        if (releaseStarted || isReleased || surfaceFailed) return false
        return callbacks().post(action, delayMillis, ::deliverLifecycleCallback)
    }

    override fun removeCallbacks(action: Runnable): Boolean {
        if (::callbackQueue.isInitialized) callbackQueue.remove(action)
        nativeHandoff?.remove(action)
        return super.removeCallbacks(action)
    }

    override fun onDraw(canvas: Canvas) {
        if (isReleased || surfaceFailed) return
        runUiCatching(onError = ::reportSurfaceFailure) { applyPendingViewport() }
        val checkpoint = canvas.save()
        try {
            // Sora's sticky lines can draw cached long lines outside the view's bounds.
            // Android supplies a canvas in scrolled content coordinates; Sora translates
            // it back to viewport coordinates inside its renderer.
            canvas.clipRect(scrollX, scrollY, scrollX + width, scrollY + height)
            runUiCatching(onError = ::reportSurfaceFailure) { super.onDraw(canvas) }
        } finally {
            canvas.restoreToCount(checkpoint)
        }
    }

    override fun release() {
        if (releaseStarted || isReleased) return
        releaseStarted = true
        surfaceFailed = true
        pendingImeFromUserTap = false
        caretRevealPosted = false
        onEditorFailure = null
        // Close even if the first producer is racing with release on a worker thread.
        try { callbacks().close() }
        catch (failure: Throwable) { recordHandoffFailure(failure) }
        try { parent?.requestDisallowInterceptTouchEvent(false) }
        catch (failure: Throwable) { recordHandoffFailure(failure) }
        val lease = nativeHandoff
        val capture = lease?.capture ?: nativeCapture
        fun captureFinal() {
            try { capture?.invoke() }
            catch (failure: Throwable) {
                nativeCaptureFailed = true
                recordHandoffFailure(failure)
            }
        }
        captureFinal()
        try { super.release() }
        catch (failure: Throwable) { recordHandoffFailure(failure) }
        // EditorReleaseEvent runs inside super.release and may still touch raw Content.
        captureFinal()
        try { nativeContentSubscription?.unsubscribe() }
        catch (failure: Throwable) { recordHandoffFailure(failure) }
        nativeContentSubscription = null
        try { viewportLayoutSubscription.unsubscribe() }
        catch (failure: Throwable) { recordHandoffFailure(failure) }
        pendingViewport = null
        try { sealedContentSubscription.unsubscribe() }
        catch (failure: Throwable) { recordHandoffFailure(failure) }
        retainedInputConnection = null
        if (nativeCaptureFailed) nativeCapture = capture
        lease?.released()
        if (!nativeCaptureFailed) nativeCapture = null
        if (lease == null) nativeHandoffFailure?.let { throw it }
    }

    // Observe only sealed deltas here. Normal keystrokes keep the pane's incremental capture.
    private val sealedContentSubscription = subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
        val lease = nativeHandoff
        if (lease != null) {
            nativeUnexpectedDelta = true
            try { lease.capture?.invoke() }
            catch (failure: Throwable) {
                nativeCaptureFailed = true
                recordHandoffFailure(failure)
            }
        }
    }

    init {
        // Sora defaults to Typeface.DEFAULT. Use the cached platform monospace family once per view;
        // Android retains its Unicode fallback. Keep the user's size, spacing and wrapping settings.
        setTypefaceText(Typeface.MONOSPACE)
        setTypefaceLineNumber(Typeface.MONOSPACE)
        setTextSize(DefaultEditorTextSizeSp)
        

        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        setEditable(true)
        setSoftKeyboardEnabled(true)


        setDisableSoftKbdIfHardKbdAvailable(false)

        
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_NORMAL or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE


        props.disallowSuggestions = false
        props.adjustToSelectionOnResize = false
        props.useICULibToSelectWords = true
        setTabWidth(codeStyle.tabWidth)


        setBlockLineEnabled(true)
        setBlockLineWidth(0.75f)
        setHighlightCurrentBlock(true)
        getComponent(EditorAutoCompletion::class.java).unregister()
        replaceComponent(EditorAutoCompletion::class.java, DroideEditorAutoCompletion(this))
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!acceptsMutations) return null
        val connection = runUiCatching(onError = ::reportSurfaceFailure) {
            super.onCreateInputConnection(outAttrs)
        } ?: return null
        // Keep the real delegate for composition; Sora also retains its concrete field.
        retainedInputConnection = connection
        val external = editorHandoffInputConnection(this, connection)
        codingKeyboard.configure(external, outAttrs, multiline = true)
        return external
    }

    fun applyKeyboardMode(mode: CodingKeyboardMode) {
        if (acceptsFeatureUpdates) codingKeyboard.applyMode(this, mode)
    }

    fun applyCodeStyle(profile: CodeStyleProfile) {
        if (!acceptsFeatureUpdates) return
        codeStyle = profile
        if (tabWidth != profile.tabWidth) setTabWidth(profile.tabWidth)
    }

    fun applyReviewMode(enabled: Boolean) {
        if (!acceptsFeatureUpdates) return
        setEditable(!enabled)
        setSoftKeyboardEnabled(!enabled)
        if (enabled) {
            pendingImeFromUserTap = false
            ViewCompat.getWindowInsetsController(this)?.hide(WindowInsetsCompat.Type.ime())
        }
    }


    fun restoreInputFocus(showKeyboard: Boolean = true) {
        if (isReleased || surfaceFailed || !isEnabled || !isEditable) return
        acquireInputFocus()
        if (showKeyboard) requestImeAfterUserInteraction()
    }


    internal fun revealSelectionForActiveIme() {
        if (pendingViewport != null || isReleased || surfaceFailed || !isAttachedToWindow || !isFocused || !isEnabled || !isEditable) return
        val imeVisible = ViewCompat.getRootWindowInsets(this)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (!imeVisible || caretRevealPosted) return
        caretRevealPosted = true
        val generation = viewportGeneration
        postOnAnimation {
            caretRevealPosted = false
            if (generation != viewportGeneration || pendingViewport != null || isReleased || surfaceFailed || !isAttachedToWindow || !isFocused || !isEnabled || !isEditable) return@postOnAnimation
            val stillVisible = ViewCompat.getRootWindowInsets(this)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (!stillVisible) return@postOnAnimation
            runUiCatching(onError = ::reportSurfaceFailure) {
                val caret = cursor.right()
                ensurePositionVisible(caret.line, caret.column, true)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measured = if (acceptsFeatureUpdates) runUiCatching(onError = ::reportSurfaceFailure) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            true
        } else false
        if (measured != true) setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec),
        )
    }

    override fun computeScroll() {
        if (acceptsFeatureUpdates) runUiCatching(onError = ::reportSurfaceFailure) { super.computeScroll() }
    }

    // Android/Compose query these outside onDraw (scroll arbitration and accessibility).
    // A failed surface may remain attached until the next composition removes it.
    override fun computeVerticalScrollRange(): Int =
        if (acceptsFeatureUpdates) runUiCatching(onError = ::reportSurfaceFailure) {
            super.computeVerticalScrollRange()
        } ?: 0 else 0

    override fun computeVerticalScrollOffset(): Int =
        if (acceptsFeatureUpdates) runUiCatching(onError = ::reportSurfaceFailure) {
            super.computeVerticalScrollOffset()
        } ?: 0 else 0

    override fun computeHorizontalScrollRange(): Int =
        if (acceptsFeatureUpdates) runUiCatching(onError = ::reportSurfaceFailure) {
            super.computeHorizontalScrollRange()
        } ?: 0 else 0

    override fun computeHorizontalScrollOffset(): Int =
        if (acceptsFeatureUpdates) runUiCatching(onError = ::reportSurfaceFailure) {
            super.computeHorizontalScrollOffset()
        } ?: 0 else 0

    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        if (acceptsFeatureUpdates) runUiCatching(onError = ::reportSurfaceFailure) {
            super.onGenericMotionEvent(event)
        } ?: false else false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (!acceptsFeatureUpdates) return
        runUiCatching(onError = ::reportSurfaceFailure) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (h < oldh || w != oldw) revealSelectionForActiveIme()
        }
    }

    private fun acquireInputFocus(): Boolean {
        if (isReleased || surfaceFailed || !isEnabled || !isEditable) return false
        if (!isFocused && isInTouchMode) requestFocusFromTouch()
        if (!isFocused) requestFocus()
        return isFocused
    }

    private fun requestImeAfterUserInteraction() {
        if (isReleased || surfaceFailed || !isEnabled || !isEditable) {
            pendingImeFromUserTap = false
            return
        }
        pendingImeFromUserTap = true
        post {
            if (!pendingImeFromUserTap) return@post
            if (!canActivateEditor() || !isEditable) {
                pendingImeFromUserTap = false
                return@post
            }
            if (!acquireInputFocus()) return@post
            

            if (!hasWindowFocus()) return@post
            pendingImeFromUserTap = false
            runUiCatching(onError = ::reportSurfaceFailure) {
                showSoftInput()
                ViewCompat.getWindowInsetsController(this)?.show(WindowInsetsCompat.Type.ime())
            }
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        if (acceptsFeatureUpdates) runUiCatching(onError = ::reportSurfaceFailure) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        }
        if (!gainFocus) {
            // Never carry an old tap-triggered IME request across panel/dialog focus changes.

            pendingImeFromUserTap = false
        }
    }

    override fun onAttachedToWindow() {
        runUiCatching(onError = ::reportSurfaceFailure) { super.onAttachedToWindow() }
    }

    override fun onDetachedFromWindow() {
        pendingImeFromUserTap = false
        runUiCatching(onError = ::reportSurfaceFailure) { super.onDetachedFromWindow() }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (!acceptsFeatureUpdates) return
        runUiCatching(onError = ::reportSurfaceFailure) { super.onWindowFocusChanged(hasWindowFocus) }
        if (hasWindowFocus && pendingImeFromUserTap && isFocused) {
            requestImeAfterUserInteraction()
        }
    }

    private fun canActivateEditor(): Boolean =
        acceptsFeatureUpdates && isEnabled && isAttachedToWindow && isShown && windowVisibility == VISIBLE

    override fun performClick(): Boolean {
        if (!canActivateEditor()) return false
        super.performClick()
        // Sora 0.24.6 already calls performClick from its tap handler, before placing the
        // caret. Leave that handler and our existing tap-only IME retry in charge of touch.
        if (!dispatchingEditorTouch && canActivateEditor()) {
            if (isEditable) restoreInputFocus() else requestFocus()
        }
        return true
    }

    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo {
        if (acceptsFeatureUpdates) {
            runUiCatching(onError = ::reportSurfaceFailure) { super.createAccessibilityNodeInfo() }?.let { return it }
        }
        // Do not reenter Sora to build the fallback: it reads the same failed layout.
        return AccessibilityNodeInfo.obtain().apply {
            setSource(this@DroideCodeEditor)
            packageName = context.packageName
            className = CodeEditor::class.java.name
            isEnabled = false
        }
    }

    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        if (acceptsFeatureUpdates && runUiCatching(onError = ::reportSurfaceFailure) {
                super.onInitializeAccessibilityEvent(event)
                true
            } == true) return
        event.isEnabled = false
        event.isScrollable = false
        event.scrollX = 0
        event.scrollY = 0
        event.maxScrollX = 0
        event.maxScrollY = 0
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (!acceptsMutations) return false
        if (action == AccessibilityNodeInfo.ACTION_CLICK && !canActivateEditor()) return false
        return runUiCatching(onError = ::reportSurfaceFailure) {
            super.performAccessibilityAction(action, arguments)
        } ?: false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!acceptsFeatureUpdates) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                applyPendingViewport()
                cancelViewportRestore()
                touchDownX = event.x
                touchDownY = event.y
                touchMoved = false
                touchUsedMultiplePointers = false


                parent?.requestDisallowInterceptTouchEvent(true)
                acquireInputFocus()
            }
            MotionEvent.ACTION_POINTER_DOWN -> touchUsedMultiplePointers = true
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) touchUsedMultiplePointers = true
                if (!touchMoved && EditorInputInteractionPolicy.movedBeyondSlop(
                        event.x - touchDownX,
                        event.y - touchDownY,
                        touchSlop,
                    )
                ) {
                    touchMoved = true
                }
            }
        }


        val previouslyDispatching = dispatchingEditorTouch
        dispatchingEditorTouch = true
        val handled = try {
            runUiCatching(onError = ::reportSurfaceFailure) { super.onTouchEvent(event) } ?: false
        } finally {
            dispatchingEditorTouch = previouslyDispatching
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)


                if (EditorInputInteractionPolicy.shouldRetryImeAfterTouch(
                        handledByEditor = handled,
                        moved = touchMoved,
                        usedMultiplePointers = touchUsedMultiplePointers,
                        gestureDurationMs = event.eventTime - event.downTime,
                        longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong(),
                    )
                ) {
                    requestImeAfterUserInteraction()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                pendingImeFromUserTap = false
            }
        }
        return handled
    }

    private fun setSelectionOffset(offset: Int) {
        val safe = offset.coerceIn(0, text.length)
        val position = text.indexer.getCharPosition(safe)
        setSelection(position.line, position.column)
    }

    override fun commitText(text: CharSequence, applyAutoIndent: Boolean) {
        if (!isEditable) return
        

        if (text.isEmpty()) {
            super.commitText(text, applyAutoIndent)
            return
        }


        val accessory = accessoryModifiers
        if (text.length == 1 && accessory?.hasCtrlOrAlt() == true) {
            val keyCode = android.view.KeyEvent.keyCodeFromString("KEYCODE_${text[0].uppercaseChar()}")
            if (keyCode != android.view.KeyEvent.KEYCODE_UNKNOWN) {
                dispatchAccessoryKey(this, keyCode, accessory.androidMetaState())
                accessory.consumeArmed()
                restoreInputFocus(showKeyboard = true)
                return
            }
        }
        val cursor = this.cursor
        if (!cursor.isSelected && (text == "\n" || text == "\r" || text == "\r\n")) {
            val line = this.text.getLine(cursor.leftLine).toString()
            val before = line.substring(0, cursor.leftColumn)
            val after = line.substring(cursor.leftColumn)
            if (ProfessionalSmartTyping.betweenMatchingPair(languageId, before, after)) {
                val baseIndent = before.takeWhile { it == ' ' || it == '\t' }
                val opener = before.lastOrNull { !it.isWhitespace() }
                val indentUnit = if (opener == '(' || opener == '[') codeStyle.continuationUnit else codeStyle.indentUnit
                val separator = lineSeparator.content.ifEmpty { "\n" }
                val inserted = separator + baseIndent + indentUnit + separator + baseIndent
                insertText(inserted, separator.length + baseIndent.length + indentUnit.length)
                return
            }
        }
        if (!cursor.isSelected && text.length == 1) {
            val typed = text[0]
            val cursorIndex = cursor.left
            if (ProfessionalSmartTyping.shouldOvertypeClosing(this.text, cursorIndex, typed, languageId)) {
                setSelectionOffset(cursorIndex + 1)
                return
            }
        }


        val allowSymbolCompletion = if (text.length == 1 && text[0] in charArrayOf('"', '\'', '`')) {
            val line = this.text.getLine(cursor.leftLine)
            ProfessionalSmartTyping.shouldAutoPairQuote(
                line = line,
                cursorColumn = cursor.leftColumn,
                quote = text[0],
                languageId = languageId,
                hasSelection = cursor.isSelected,
            )
        } else {
            true
        }
        super.commitText(text, applyAutoIndent, allowSymbolCompletion)
    }

    override fun deleteText() {
        if (!isEditable) return
        val cursor = this.cursor
        if (!cursor.isSelected) {
            val range = ProfessionalSmartTyping.pairedDeleteRange(this.text, cursor.left, languageId)
            if (range != null) {
                this.text.beginBatchEdit()
                try {
                    this.text.delete(range.first, range.last + 1)
                } finally {
                    this.text.endBatchEdit()
                }
                setSelectionOffset(range.first)
                return
            }
        }
        super.deleteText()
    }

    // Sora's direct roots do not all consult isEditable (notably undo, insert and setText).
    override fun commitText(text: CharSequence, applyAutoIndent: Boolean, applySymbolCompletion: Boolean) {
        if (isEditable) super.commitText(text, applyAutoIndent, applySymbolCompletion)
    }

    override fun insertText(text: String, selectionOffset: Int) {
        if (isEditable) super.insertText(text, selectionOffset)
    }

    override fun setText(text: CharSequence?, reuseContent: Boolean, extraArguments: Bundle?) {
        if (!acceptsMutations) return
        // Includes restored unsaved buffers, which do not pass through FileRepository.
        // Reject before Sora replaces its Content; never sanitize or overwrite the draft.
        EditorTextAdmission.requireNoNul(text)
        super.setText(text, reuseContent, extraArguments)
    }

    override fun undo() { if (isEditable) super.undo() }
    override fun redo() { if (isEditable) super.redo() }
    override fun indentLines(requireSelection: Boolean) {
        if (isEditable) super.indentLines(requireSelection)
    }
    override fun unindentSelection() { if (isEditable) super.unindentSelection() }
    override fun indentOrCommitTab() { if (isEditable) super.indentOrCommitTab() }
    override fun pasteText() { if (isEditable) super.pasteText() }
    override fun pasteText(text: CharSequence?) { if (isEditable) super.pasteText(text) }
    override fun cutText() { if (isEditable) super.cutText() }
    override fun cutLine() { if (isEditable) super.cutLine() }
    override fun duplicateLine() { if (isEditable) super.duplicateLine() }
    override fun duplicateSelection(prefix: String?, selectDuplicate: Boolean) {
        if (isEditable) super.duplicateSelection(prefix, selectDuplicate)
    }
    @Synchronized override fun formatCodeAsync(): Boolean =
        acceptsFeatureUpdates && super.formatCodeAsync()
    @Synchronized override fun formatCodeAsync(start: CharPosition, end: CharPosition): Boolean =
        acceptsFeatureUpdates && super.formatCodeAsync(start, end)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        acceptsMutations && super.dispatchKeyEvent(event)
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!acceptsMutations) return false
        applyPendingViewport()
        cancelViewportRestore()
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        acceptsMutations && super.onKeyUp(keyCode, event)
    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean =
        acceptsMutations && super.onKeyMultiple(keyCode, repeatCount, event)
    override fun onDragEvent(event: DragEvent): Boolean =
        acceptsMutations && super.onDragEvent(event)
}
