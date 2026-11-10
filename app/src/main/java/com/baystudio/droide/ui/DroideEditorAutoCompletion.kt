package com.baystudio.droide.ui

import android.graphics.Rect
import android.os.Build
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.baystudio.droide.core.CompletionPopupPlacement
import com.baystudio.droide.core.CompletionViewport
import com.baystudio.droide.core.CompletionViewportPolicy
import io.github.rosemoe.sora.event.EditorAttachStateChangeEvent
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import kotlin.math.ceil

/** Keeps Sora's providers, acceptance and keyboard handling; replaces only popup geometry. */
internal class DroideEditorAutoCompletion(private val target: DroideCodeEditor) : EditorAutoCompletion(target) {
    private val localVisible = Rect()
    private val visibleFrame = Rect()
    private val screenPosition = IntArray(2)
    private var windowBounds: Rect? = null
    private var observingTree: ViewTreeObserver? = null
    private var tracking = false
    private var placing = false
    internal var placement: CompletionPopupPlacement? = null
        private set
    internal val observesViewport: Boolean get() = observingTree != null
    private val viewportListener = ViewTreeObserver.OnGlobalLayoutListener {
        windowBounds = null
        if (tracking && target.acceptsFeatureUpdates) updateCompletionWindowPosition(false)
    }

    init {
        popup.isClippingEnabled = true
        popup.isAttachedInDecor = true
        eventManager.subscribeEvent(EditorAttachStateChangeEvent::class.java) { event, _ ->
            if (!event.isAttachedToWindow) hide()
        }
    }

    override fun select(pos: Int): Boolean = target.acceptsMutations && super.select(pos)

    override fun requireCompletion() {
        if (target.acceptsFeatureUpdates) super.requireCompletion()
    }

    override fun updateCompletionWindowPosition(scrollEditor: Boolean) {
        if (!target.acceptsFeatureUpdates) return
        val count = adapter.count
        if (count <= 0) { placement = null; setMaxHeight(0); if (tracking) hide(); return }
        val wanted = (adapter.itemHeight.toLong() * count).coerceAtMost((240 * target.dpUnit).toLong()).toInt()
        place(wanted)
    }

    override fun setSize(width: Int, height: Int) { place(height) }

    override fun show() {
        if (cancelShowUp || !isEnabled || adapter.count <= 0 || !target.acceptsFeatureUpdates || !target.isAttachedToWindow) return
        windowBounds = null
        updateCompletionWindowPosition(false)
        if (placement == null) return
        tracking = true
        val tree = target.viewTreeObserver
        if (observingTree !== tree) {
            stopObserving()
            tracking = true
            tree.addOnGlobalLayoutListener(viewportListener)
            observingTree = tree
        }
        super.show()
    }

    override fun hide() {
        stopObserving()
        placement = null
        super.hide()
    }

    override fun dismiss() {
        stopObserving()
        placement = null
        super.dismiss()
    }

    override fun setEnabled(enabled: Boolean) {
        if (!enabled) stopObserving()
        super.setEnabled(enabled)
    }

    private fun stopObserving() {
        tracking = false
        observingTree?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(viewportListener)
        observingTree = null
        windowBounds = null
    }

    /** Coordinates are editor viewport pixels; Sora's document scroll offset is applied only at publication. */
    internal fun visibleViewport(): CompletionViewport? {
        if (!target.isAttachedToWindow || !target.getLocalVisibleRect(localVisible)) return null
        localVisible.offset(-target.scrollX, -target.scrollY)
        if (!localVisible.intersect(0, 0, target.width, target.height)) return null
        target.getLocationOnScreen(screenPosition)
        target.getWindowVisibleDisplayFrame(visibleFrame)
        visibleFrame.offset(-screenPosition[0], -screenPosition[1])
        if (!localVisible.intersect(visibleFrame)) return null
        val insets = ViewCompat.getRootWindowInsets(target)
        if (Build.VERSION.SDK_INT >= 30 && insets?.isVisible(WindowInsetsCompat.Type.ime()) == true) {

            val bounds = windowBounds ?: runCatching {
                target.context.getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds?.let(::Rect)
            }.getOrNull()?.also { windowBounds = it }
            if (bounds != null) {
                val safe = insets.getInsets(WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                val safeFrame = Rect(bounds.left + safe.left, bounds.top + safe.top, bounds.right - safe.right, bounds.bottom - safe.bottom)
                safeFrame.offset(-screenPosition[0], -screenPosition[1])
                if (!localVisible.intersect(safeFrame)) return null
            }
        }
        return CompletionViewport(localVisible.left, localVisible.top, localVisible.right, localVisible.bottom)
    }

    private fun place(requestedHeight: Int) {
        if (placing) return
        if (!target.acceptsFeatureUpdates || !isEnabled) { placement = null; return }
        placing = true
        try {
            val viewport = visibleViewport()
            if (viewport == null) { placement = null; setMaxHeight(0); if (tracking) hide(); return }
            val caret = target.cursor.right()
            val offset = target.layout.getCharLayoutOffset(caret.line, caret.column)
            val bottom = offset[0] - target.offsetY
            val dp = target.dpUnit
            val fullWidth = completionWndPositionMode == WINDOW_POS_MODE_FULL_WIDTH_ALWAYS
            val next = viewport.let {
                CompletionViewportPolicy.place(

                    it, target.updateCursorAnchor().coerceIn(it.left.toFloat(), it.right.toFloat()), bottom - target.rowHeight, bottom,
                    if (fullWidth) it.right - it.left else (320 * dp).toInt(),
                    requestedHeight.coerceAtMost((240 * dp).toInt()),
                    adapter.itemHeight.coerceAtLeast(1), ceil(6 * dp).toInt(), ceil(4 * dp).toInt(), ceil(96 * dp).toInt(),
                )
            }
            if (next == null) {
                placement = null
                setMaxHeight(0)
                if (isShowing || tracking) hide()
                return
            }
            setMaxHeight(next.height)
            if (next != placement || width != next.width || height != next.height) {
                placement = next
                super.setLocationAbsolutely(next.left, next.top)
                super.setSize(next.width, next.height)
            }
        } finally { placing = false }
    }
}
